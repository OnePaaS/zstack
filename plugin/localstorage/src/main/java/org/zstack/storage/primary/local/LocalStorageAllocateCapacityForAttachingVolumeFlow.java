package org.zstack.storage.primary.local;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.core.workflow.Flow;
import org.zstack.header.core.workflow.FlowTrigger;
import org.zstack.header.host.HostInventory;
import org.zstack.header.host.HostVO;
import org.zstack.header.message.MessageReply;
import org.zstack.header.storage.primary.*;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.volume.VolumeInventory;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.Map;

/**
 * Created by frank on 7/5/2015.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class LocalStorageAllocateCapacityForAttachingVolumeFlow implements Flow {
    @Autowired
    protected DatabaseFacade dbf;
    @Autowired
    protected CloudBus bus;
    @Autowired
    protected ErrorFacade errf;

    @Transactional(readOnly = true)
    private boolean isThereOtherStorageForTheHost(String hostUuid, String localStorageUuid) {
        String sql = "select count(pri) from PrimaryStorageVO pri, PrimaryStorageClusterRefVO ref, HostVO host where pri.uuid = ref.primaryStorageUuid and ref.clusterUuid = host.clusterUuid and host.uuid = :huuid and pri.uuid != :puuid";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("huuid", hostUuid);
        q.setParameter("puuid", localStorageUuid);
        return q.getSingleResult() > 0;
    }

    @Override
    public void run(final FlowTrigger trigger, final Map data) {
        final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        VolumeInventory volume = spec.getDestDataVolumes().get(0);

        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
        q.select(LocalStorageResourceRefVO_.hostUuid, LocalStorageResourceRefVO_.primaryStorageUuid);
        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, spec.getVmInventory().getRootVolumeUuid());
        Tuple t = q.findTuple();
        final String hostUuid = t.get(0, String.class);
        String priUuid = t.get(1, String.class);

        AllocatePrimaryStorageMsg msg = new AllocatePrimaryStorageMsg();
        if (isThereOtherStorageForTheHost(hostUuid, priUuid)) {
            // use network-shared primary storage
            msg.addExcludeAllocatorStrategy(LocalStorageConstants.LOCAL_STORAGE_ALLOCATOR_STRATEGY);
            msg.addExcludePrimaryStoratgeUuid(priUuid);
        } else {
            msg.setAllocationStrategy(LocalStorageConstants.LOCAL_STORAGE_ALLOCATOR_STRATEGY);
            msg.setPrimaryStorageUuid(spec.getVmInventory().getRootVolume().getPrimaryStorageUuid());
        }

        msg.setHostUuid(hostUuid);
        msg.setVmInstanceUuid(spec.getVmInventory().getUuid());
        msg.setSize(volume.getSize());
        bus.makeLocalServiceId(msg, PrimaryStorageConstant.SERVICE_ID);
        bus.send(msg, new CloudBusCallBack(trigger) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    trigger.fail(reply.getError());
                    return;
                }

                spec.setDestHost(HostInventory.valueOf(dbf.findByUuid(hostUuid, HostVO.class)));

                AllocatePrimaryStorageReply ar = (AllocatePrimaryStorageReply)reply;
                data.put(VmInstanceConstant.Params.DestPrimaryStorageInventoryForAttachingVolume.toString(), ar.getPrimaryStorageInventory());
                data.put(LocalStorageAllocateCapacityForAttachingVolumeFlow.class, ar.getSize());
                trigger.next();
            }
        });
    }

    @Override
    public void rollback(FlowTrigger trigger, Map data) {
        Long size = (Long) data.get(LocalStorageAllocateCapacityForAttachingVolumeFlow.class);
        if (size != null) {
            PrimaryStorageInventory pri = (PrimaryStorageInventory) data.get(VmInstanceConstant.Params.DestPrimaryStorageInventoryForAttachingVolume.toString());
            ReturnPrimaryStorageCapacityMsg rmsg = new ReturnPrimaryStorageCapacityMsg();
            rmsg.setPrimaryStorageUuid(pri.getUuid());
            rmsg.setDiskSize(size);
            bus.makeTargetServiceIdByResourceUuid(rmsg, PrimaryStorageConstant.SERVICE_ID, pri.getUuid());
            bus.send(rmsg);
        }

        trigger.rollback();
    }
}
