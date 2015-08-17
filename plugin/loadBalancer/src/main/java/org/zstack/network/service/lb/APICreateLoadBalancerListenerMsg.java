package org.zstack.network.service.lb;

import org.zstack.header.message.APICreateMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.tag.TagResourceType;

/**
 * Created by frank on 8/8/2015.
 */
@TagResourceType(LoadBalancerListenerVO.class)
public class APICreateLoadBalancerListenerMsg extends APICreateMessage implements LoadBalancerMessage {
    @APIParam(resourceType = LoadBalancerVO.class, checkAccount = true, operationTarget = true)
    private String loadBalancerUuid;
    @APIParam(maxLength = 255)
    private String name;
    @APIParam(maxLength = 2048, required = false)
    private String description;
    @APIParam(numberRange = {1, 65536})
    private int instancePort;
    @APIParam(numberRange = {1, 65536})
    private int loadBalancerPort;
    @APIParam(maxLength = 255)
    private String protocol;

    @Override
    public String getLoadBalancerUuid() {
        return loadBalancerUuid;
    }

    public void setLoadBalancerUuid(String loadBalancerUuid) {
        this.loadBalancerUuid = loadBalancerUuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getInstancePort() {
        return instancePort;
    }

    public void setInstancePort(int instancePort) {
        this.instancePort = instancePort;
    }

    public int getLoadBalancerPort() {
        return loadBalancerPort;
    }

    public void setLoadBalancerPort(int loadBalancerPort) {
        this.loadBalancerPort = loadBalancerPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
}
