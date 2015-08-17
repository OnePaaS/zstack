package org.zstack.identity;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.config.GlobalConfigVO;
import org.zstack.core.config.GlobalConfigVO_;
import org.zstack.core.db.*;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.PeriodicTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.AbstractService;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.ApiMessageInterceptor;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.identity.*;
import org.zstack.header.identity.AccountConstant.StatementEffect;
import org.zstack.header.identity.PolicyInventory.Statement;
import org.zstack.header.identity.Quota.QuotaPair;
import org.zstack.header.managementnode.PrepareDbInitialValueExtensionPoint;
import org.zstack.header.message.APIListMessage;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.message.Message;
import org.zstack.header.search.APIGetMessage;
import org.zstack.header.search.APISearchMessage;
import org.zstack.utils.BeanUtils;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.FieldUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.io.File;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.zstack.utils.CollectionDSL.list;

public class AccountManagerImpl extends AbstractService implements AccountManager, PrepareDbInitialValueExtensionPoint,
        SoftDeleteEntityExtensionPoint, HardDeleteEntityExtensionPoint, GlobalApiMessageInterceptor, ApiMessageInterceptor {
    private static final CLogger logger = Utils.getLogger(AccountManagerImpl.class);

    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private DbEntityLister dl;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private PluginRegistry pluginRgty;

    private List<String> resourceTypeForAccountRef;
    private List<Class> resourceTypes;
    private Map<String, SessionInventory> sessions = new ConcurrentHashMap<String, SessionInventory>();
    private Map<Class, Quota> messageQuotaMap = new HashMap<Class, Quota>();

    class AccountCheckField {
        Field field;
        APIParam param;
    }

    class MessageAction {
        boolean adminOnly;
        List<String> actions;
        String category;
        boolean accountOnly;
        List<AccountCheckField> accountCheckFields;
    }

    private Map<Class, MessageAction> actions = new HashMap<Class, MessageAction>();
    private Future<Void> expiredSessionCollector;

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof AccountMessage) {
            passThrough((AccountMessage) msg);
        } else if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    private void handleLocalMessage(Message msg) {
        if (msg instanceof GenerateMessageIdentityCategoryMsg) {
            handle((GenerateMessageIdentityCategoryMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    @Override
    public Map<Class, Quota> getMessageQuotaMap() {
        return messageQuotaMap;
    }

    private void handle(GenerateMessageIdentityCategoryMsg msg) {
        List<String> adminMsgs = new ArrayList<String>();
        List<String> userMsgs = new ArrayList<String>();

        List<Class> apiMsgClasses = BeanUtils.scanClassByType("org.zstack", APIMessage.class);
        for (Class clz : apiMsgClasses) {
            if (APISearchMessage.class.isAssignableFrom(clz) || APIGetMessage.class.isAssignableFrom(clz)
                    || APIListMessage.class.isAssignableFrom(clz)) {
                continue;
            }

            String name = clz.getSimpleName().replaceAll("API", "").replaceAll("Msg", "");

            if (clz.isAnnotationPresent(Action.class)) {
                userMsgs.add(name);
            } else {
                adminMsgs.add(name);
            }
        }

        List<String> quotas = new ArrayList<String>();
        for (Quota q : messageQuotaMap.values()) {
            for (QuotaPair p : q.getQuotaPairs()) {
                quotas.add(String.format("%s        %s", p.getName(), p.getValue()));
            }
        }

        List<String> as = new ArrayList<String>();
        for (Map.Entry<Class, MessageAction> e : actions.entrySet()) {
            Class api = e.getKey();
            MessageAction a = e.getValue();
            if (a.adminOnly || a.accountOnly) {
                continue;
            }

            String name = api.getSimpleName().replaceAll("API", "").replaceAll("Msg", "");
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s: ", name));
            sb.append(StringUtils.join(a.actions, ", "));
            sb.append("\n");
            as.add(sb.toString());
        }

        try {
            String folder = PathUtil.join(System.getProperty("user.home"), "zstack-identity");
            FileUtils.deleteDirectory(new File(folder));

            new File(folder).mkdirs();

            String userMsgsPath = PathUtil.join(folder, "non-admin-api.txt");
            FileUtils.writeStringToFile(new File(userMsgsPath), StringUtils.join(userMsgs, "\n"));
            String adminMsgsPath = PathUtil.join(folder, "admin-api.txt");
            FileUtils.writeStringToFile(new File(adminMsgsPath), StringUtils.join(adminMsgs, "\n"));
            String quotaPath = PathUtil.join(folder, "quota.txt");
            FileUtils.writeStringToFile(new File(quotaPath), StringUtils.join(quotas, "\n"));
            String apiIdentityPath = PathUtil.join(folder, "api-identity.txt");
            FileUtils.writeStringToFile(new File(apiIdentityPath), StringUtils.join(as, "\n"));
            bus.reply(msg, new GenerateMessageIdentityCategoryReply());
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    private void passThrough(AccountMessage msg) {
        AccountVO vo = dbf.findByUuid(msg.getAccountUuid(), AccountVO.class);
        if (vo == null) {
            String err = String.format("unable to find account[uuid=%s]", msg.getAccountUuid());
            bus.replyErrorByMessageType((Message) msg, errf.instantiateErrorCode(SysErrors.RESOURCE_NOT_FOUND, err));
            return;
        }

        AccountBase base = new AccountBase(vo);
        base.handleMessage((Message) msg);
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APICreateAccountMsg) {
            handle((APICreateAccountMsg) msg);
        } else if (msg instanceof APIListAccountMsg) {
            handle((APIListAccountMsg) msg);
        } else if (msg instanceof APIListUserMsg) {
            handle((APIListUserMsg) msg);
        } else if (msg instanceof APIListPolicyMsg) {
            handle((APIListPolicyMsg) msg);
        } else if (msg instanceof APILogInByAccountMsg) {
            handle((APILogInByAccountMsg) msg);
        } else if (msg instanceof APILogInByUserMsg) {
            handle((APILogInByUserMsg)msg);
        } else if (msg instanceof APILogOutMsg) {
            handle((APILogOutMsg) msg);
        } else if (msg instanceof APIValidateSessionMsg) {
            handle((APIValidateSessionMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }


    private void handle(APIValidateSessionMsg msg) {
        APIValidateSessionReply reply = new APIValidateSessionReply();
        boolean s = sessions.containsKey(msg.getSessionUuid());
        if (!s) {
            SimpleQuery<SessionVO> q = dbf.createQuery(SessionVO.class);
            q.add(SessionVO_.uuid, Op.EQ, msg.getSessionUuid());
            s = q.isExists();
        }
        reply.setValidSession(s);
        bus.reply(msg, reply);
    }


    private void handle(APILogOutMsg msg) {
        APILogOutReply reply = new APILogOutReply();
        logOutSession(msg.getSessionUuid());
        bus.reply(msg, reply);
    }

    private SessionInventory getSession(String accountUuid, String userUuid) {
        int maxLoginTimes = org.zstack.identity.IdentityGlobalConfig.MAX_CONCURRENT_SESSION.value(Integer.class);
        SimpleQuery<SessionVO> query = dbf.createQuery(SessionVO.class);
        query.add(SessionVO_.accountUuid, Op.EQ, accountUuid);
        query.add(SessionVO_.userUuid, Op.EQ, userUuid);
        long count = query.count();
        if (count >= maxLoginTimes) {
            String err = String.format("Login sessions hit limit of max allowed concurrent login sessions, max allowed: %s", maxLoginTimes);
            throw new BadCredentialsException(err);
        }

        int sessionTimeout = IdentityGlobalConfig.SESSION_TIMEOUT.value(Integer.class);
        SessionVO svo = new SessionVO();
        svo.setUuid(Platform.getUuid());
        svo.setAccountUuid(accountUuid);
        svo.setUserUuid(userUuid);
        long expiredTime = getCurrentSqlDate().getTime() + TimeUnit.SECONDS.toMillis(sessionTimeout);
        svo.setExpiredDate(new Timestamp(expiredTime));
        svo = dbf.persistAndRefresh(svo);
        SessionInventory session = SessionInventory.valueOf(svo);
        sessions.put(session.getUuid(), session);
        return session;
    }

    private void handle(APILogInByUserMsg msg) {
        APILogInReply reply = new APILogInReply();
        SimpleQuery<UserVO> q = dbf.createQuery(UserVO.class);
        q.add(UserVO_.accountUuid, Op.EQ, msg.getAccountUuid());
        q.add(UserVO_.password, Op.EQ, msg.getPassword());
        q.add(UserVO_.name, Op.EQ, msg.getUserName());
        UserVO user = q.find();
        if (user == null) {
            reply.setError(errf.instantiateErrorCode(IdentityErrors.AUTHENTICATION_ERROR,
                    "wrong username or password"
            ));
            bus.reply(msg, reply);
            return;
        }

        reply.setInventory(getSession(user.getAccountUuid(), user.getUuid()));
        bus.reply(msg, reply);
    }

    private void handle(APILogInByAccountMsg msg) {
        APILogInReply reply = new APILogInReply();

        SimpleQuery<AccountVO> q = dbf.createQuery(AccountVO.class);
        q.add(AccountVO_.name, Op.EQ, msg.getAccountName());
        q.add(AccountVO_.password, Op.EQ, msg.getPassword());
        AccountVO vo = q.find();
        if (vo == null) {
            reply.setError(errf.instantiateErrorCode(IdentityErrors.AUTHENTICATION_ERROR, "wrong account name or password"));
            bus.reply(msg, reply);
            return;
        }

        reply.setInventory(getSession(vo.getUuid(), vo.getUuid()));
        bus.reply(msg, reply);
    }


    private void handle(APIListPolicyMsg msg) {
        List<PolicyVO> vos = dl.listByApiMessage(msg, PolicyVO.class);
        List<PolicyInventory> invs = PolicyInventory.valueOf(vos);
        APIListPolicyReply reply = new APIListPolicyReply();
        reply.setInventories(invs);
        bus.reply(msg, reply);
    }

    private void handle(APIListUserMsg msg) {
        List<UserVO> vos = dl.listByApiMessage(msg, UserVO.class);
        List<UserInventory> invs = UserInventory.valueOf(vos);
        APIListUserReply reply = new APIListUserReply();
        reply.setInventories(invs);
        bus.reply(msg, reply);
    }

    private void handle(APIListAccountMsg msg) {
        List<AccountVO> vos = dl.listByApiMessage(msg, AccountVO.class);
        List<AccountInventory> invs = AccountInventory.valueOf(vos);
        APIListAccountReply reply = new APIListAccountReply();
        reply.setInventories(invs);
        bus.reply(msg, reply);
    }

    private void handle(APICreateAccountMsg msg) {
        AccountVO vo = new AccountVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setName(msg.getName());
        vo.setDescription(msg.getDescription());
        vo.setPassword(msg.getPassword());
        vo.setType(msg.getType() != null ? AccountType.valueOf(msg.getType()) : AccountType.Normal);
        vo = dbf.persistAndRefresh(vo);

        List<PolicyVO> ps = new ArrayList<PolicyVO>();
        PolicyVO p = new PolicyVO();
        p.setUuid(Platform.getUuid());
        p.setAccountUuid(vo.getUuid());
        p.setName(String.format("DEFAULT-READ-%s", vo.getUuid()));
        Statement s = new Statement();
        s.setName(String.format("read-permission-for-account-%s", vo.getUuid()));
        s.setEffect(StatementEffect.Allow);
        s.addAction(".*:read");
        p.setData(JSONObjectUtil.toJsonString(list(s)));
        ps.add(p);

        p = new PolicyVO();
        p.setUuid(Platform.getUuid());
        p.setAccountUuid(vo.getUuid());
        p.setName(String.format("USER-RESET-PASSWORD-%s", vo.getUuid()));
        s = new Statement();
        s.setName(String.format("user-reset-password-%s", vo.getUuid()));
        s.setEffect(StatementEffect.Allow);
        s.addAction(String.format("%s:%s", AccountConstant.ACTION_CATEGORY, APIUpdateUserMsg.class.getSimpleName()));
        p.setData(JSONObjectUtil.toJsonString(list(s)));
        ps.add(p);

        dbf.persistCollection(ps);

        SimpleQuery<GlobalConfigVO> q = dbf.createQuery(GlobalConfigVO.class);
        q.select(GlobalConfigVO_.name, GlobalConfigVO_.value);
        q.add(GlobalConfigVO_.category, Op.EQ, AccountConstant.QUOTA_GLOBAL_CONFIG_CATETORY);
        List<Tuple> ts = q.listTuple();

        List<QuotaVO> quotas = new ArrayList<QuotaVO>();
        for (Tuple t : ts) {
            String rtype = t.get(0, String.class);
            long quota = Long.valueOf(t.get(1, String.class));

            QuotaVO qvo = new QuotaVO();
            qvo.setIdentityType(AccountVO.class.getSimpleName());
            qvo.setIdentityUuid(vo.getUuid());
            qvo.setName(rtype);
            qvo.setValue(quota);
            quotas.add(qvo);
        }

        dbf.persistCollection(quotas);

        AccountInventory inv = AccountInventory.valueOf(vo);
        APICreateAccountEvent evt = new APICreateAccountEvent(msg.getId());
        evt.setInventory(inv);
        bus.publish(evt);
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(AccountConstant.SERVICE_ID);
    }

    private void buildResourceTypes() throws ClassNotFoundException {
        resourceTypes = new ArrayList<Class>();
        for (String resrouceTypeName : resourceTypeForAccountRef) {
            Class<?> rs = Class.forName(resrouceTypeName);
            resourceTypes.add(rs);
        }
    }

    @Override
    public boolean start() {
        try {
            buildResourceTypes();
            buildActions();
            startExpiredSessionCollector();
            collectDefaultQuota();
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
        return true;
    }

    private void collectDefaultQuota() {
        Map<String, Long> defaultQuota = new HashMap<String, Long>();

        for (ReportQuotaExtensionPoint ext : pluginRgty.getExtensionList(ReportQuotaExtensionPoint.class)) {
            List<Quota> quotas = ext.reportQuota();
            DebugUtils.Assert(quotas != null, String.format("%s.getQuotaPairs() returns null", ext.getClass()));

            for (Quota quota : quotas) {
                DebugUtils.Assert(quota.getQuotaPairs() != null, String.format("%s reports a quota containing a null quotaPairs", ext.getClass()));

                for (QuotaPair p : quota.getQuotaPairs()) {
                    if (defaultQuota.containsKey(p.getName())) {
                        throw new CloudRuntimeException(String.format("duplicate DefaultQuota[resourceType: %s] reported by %s", p.getName(), ext.getClass()));
                    }

                    defaultQuota.put(p.getName(), p.getValue());
                }

                DebugUtils.Assert(quota.getMessageNeedValidation()!= null, String.format("%s reports a quota containing a null messagesNeedValidation", ext.getClass()));
                messageQuotaMap.put(quota.getMessageNeedValidation(), quota);
            }
        }

        SimpleQuery<GlobalConfigVO> q = dbf.createQuery(GlobalConfigVO.class);
        q.select(GlobalConfigVO_.name);
        q.add(GlobalConfigVO_.category, Op.EQ, AccountConstant.QUOTA_GLOBAL_CONFIG_CATETORY);
        List<String> existingQuota = q.listValue();

        List<GlobalConfigVO> quotaConfigs = new ArrayList<GlobalConfigVO>();
        for (Map.Entry<String, Long> e : defaultQuota.entrySet()) {
            String rtype = e.getKey();
            Long value = e.getValue();
            if (existingQuota.contains(rtype)) {
                continue;
            }

            GlobalConfigVO g = new GlobalConfigVO();
            g.setCategory(AccountConstant.QUOTA_GLOBAL_CONFIG_CATETORY);
            g.setDefaultValue(value.toString());
            g.setValue(g.getDefaultValue());
            g.setName(rtype);
            g.setDescription(String.format("default quota for %s", rtype));
            quotaConfigs.add(g);

            if (logger.isTraceEnabled()) {
                logger.trace(String.format("create default quota[name: %s, value: %s] global config", rtype, value));
            }
        }

        if (!quotaConfigs.isEmpty()) {
            dbf.persistCollection(quotaConfigs);
        }
    }

    private void startExpiredSessionCollector() {
        final int interval = IdentityGlobalConfig.SESSION_CELANUP_INTERVAL.value(Integer.class);
        expiredSessionCollector = thdf.submitPeriodicTask(new PeriodicTask() {

            @Transactional
            private List<String> deleteExpiredSessions() {
                String sql = "select s.uuid from SessionVO s where CURRENT_TIMESTAMP  >= s.expiredDate";
                TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                List<String> uuids = q.getResultList();
                if (!uuids.isEmpty()) {
                    String dsql = "delete from SessionVO s where s.uuid in :uuids";
                    Query dq = dbf.getEntityManager().createQuery(dsql);
                    dq.setParameter("uuids", uuids);
                    dq.executeUpdate();
                }
                return uuids;
            }

            @Override
            public void run() {
                List<String> uuids = deleteExpiredSessions();
                for (String uuid : uuids) {
                    sessions.remove(uuid);
                }
            }

            @Override
            public TimeUnit getTimeUnit() {
                return TimeUnit.SECONDS;
            }

            @Override
            public long getInterval() {
                return interval;
            }

            @Override
            public String getName() {
                return "ExpiredSessionCleanupThread";
            }

        });
    }

    private void buildActions() {
        List<Class> apiMsgClasses = BeanUtils.scanClassByType("org.zstack", APIMessage.class);
        for (Class clz : apiMsgClasses) {
            Action a = (Action) clz.getAnnotation(Action.class);
            if (a == null) {
                logger.debug(String.format("API message[%s] doesn't have annotation @Action, assume it's an admin only API", clz));
                MessageAction ma = new MessageAction();
                ma.adminOnly = true;
                ma.accountOnly = true;
                actions.put(clz, ma);
                continue;
            }

            MessageAction ma = new MessageAction();
            ma.accountOnly = a.accountOnly();
            ma.adminOnly = a.adminOnly();
            ma.category = a.category();
            ma.actions = new ArrayList<String>();
            ma.accountCheckFields = new ArrayList<AccountCheckField>();
            for (String ac : a.names()) {
                ma.actions.add(String.format("%s:%s", ma.category, ac));
            }

            List<Field> allFields = FieldUtils.getAllFields(clz);
            for (Field f : allFields) {
                APIParam at = f.getAnnotation(APIParam.class);
                if (at == null || !at.checkAccount()) {
                    continue;
                }

                if (!String.class.isAssignableFrom(f.getType()) && !Collection.class.isAssignableFrom(f.getType())) {
                    throw new CloudRuntimeException(String.format("@APIParam of %s.%s has checkAccount = true, however," +
                                    " the type of the field is not String or Collection but %s. This field must be a resource UUID or a collection(e.g. List) of UUIDs",
                            clz.getName(), f.getName(), f.getType()));
                }

                AccountCheckField af = new AccountCheckField();
                f.setAccessible(true);
                af.field = f;
                af.param = at;
                ma.accountCheckFields.add(af);
            }

            ma.actions.add(String.format("%s:%s", ma.category, clz.getSimpleName()));
            actions.put(clz, ma);
        }
    }

    @Override
    public boolean stop() {
        if (expiredSessionCollector != null) {
            expiredSessionCollector.cancel(true);
        }
        return true;
    }

    @Override
    public void prepareDbInitialValue() {
        try {
            SimpleQuery<AccountVO> q = dbf.createQuery(AccountVO.class);
            q.add(AccountVO_.name, Op.EQ, AccountConstant.INITIAL_SYSTEM_ADMIN_NAME);
            q.add(AccountVO_.type, Op.EQ, AccountType.SystemAdmin);
            if (!q.isExists()) {
                AccountVO vo = new AccountVO();
                vo.setUuid(AccountConstant.INITIAL_SYSTEM_ADMIN_UUID);
                vo.setName(AccountConstant.INITIAL_SYSTEM_ADMIN_NAME);
                vo.setPassword(AccountConstant.INITIAL_SYSTEM_ADMIN_PASSWORD);
                vo.setType(AccountType.SystemAdmin);
                dbf.persist(vo);
                logger.debug(String.format("Created initial system admin account[name:%s]", AccountConstant.INITIAL_SYSTEM_ADMIN_NAME));
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to create default system admin account", e);
        }
    }

    @Override
    public void createAccountResourceRef(String accountUuid, String resourceUuid, Class<?> resourceClass) {
        if (!resourceTypes.contains(resourceClass)) {
           throw new CloudRuntimeException(String.format("%s is not listed in resourceTypeForAccountRef of AccountManager.xml that is spring configuration. you forgot it???", resourceClass.getName()));
        }

        AccountResourceRefVO ref = AccountResourceRefVO.newOwn(accountUuid, resourceUuid, resourceClass);
        dbf.persist(ref);
    }

    @Override
    public boolean isResourceHavingAccountReference(Class entityClass) {
        return resourceTypes.contains(entityClass);
    }


    @Override
    @Transactional(readOnly = true)
    public List<String> getResourceUuidsCanAccessByAccount(String accountUuid, Class resourceType) {
        String sql = "select a.type from AccountVO a where a.uuid = :auuid";
        TypedQuery<AccountType> q = dbf.getEntityManager().createQuery(sql, AccountType.class);
        q.setParameter("auuid", accountUuid);
        List<AccountType> types = q.getResultList();
        if (types.isEmpty()) {
            throw new OperationFailureException(errf.stringToInvalidArgumentError(
                    String.format("cannot find the account[uuid:%s]", accountUuid)
            ));
        }

        AccountType atype = types.get(0);
        if (AccountType.SystemAdmin == atype) {
            return null;
        }

        sql = "select r.resourceUuid from AccountResourceRefVO r where r.accountUuid = :auuid" +
                " and r.resourceType = :rtype";
        TypedQuery<String> rq = dbf.getEntityManager().createQuery(sql, String.class);
        rq.setParameter("auuid", accountUuid);
        rq.setParameter("rtype", resourceType.getSimpleName());
        List<String> ownResourceUuids = rq.getResultList();

        sql = "select r.resourceUuid from SharedResourceVO r where" +
                " (r.toPublic = :toPublic or r.receiverAccountUuid = :auuid) and r.resourceType = :rtype";
        TypedQuery<String> srq = dbf.getEntityManager().createQuery(sql, String.class);
        srq.setParameter("toPublic", true);
        srq.setParameter("auuid", accountUuid);
        srq.setParameter("rtype", resourceType.getSimpleName());
        List<String> shared = srq.getResultList();
        shared.addAll(ownResourceUuids);

        return shared;
    }

    @Override
    public String getOwnerAccountUuidOfResource(String resourceUuid) {
        try {
            SimpleQuery<AccountResourceRefVO> q = dbf.createQuery(AccountResourceRefVO.class);
            q.select(AccountResourceRefVO_.ownerAccountUuid);
            q.add(AccountResourceRefVO_.resourceUuid, Op.EQ, resourceUuid);
            String ownerUuid = q.findValue();
            DebugUtils.Assert(ownerUuid != null, String.format("cannot find owner uuid for resource[uuid:%s]", resourceUuid));
            return ownerUuid;
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public List<Class> getEntityClassForSoftDeleteEntityExtension() {
        return resourceTypes;
    }

    @Override
    @Transactional
    public void postSoftDelete(Collection entityIds, Class entityClass) {
        String sql = "delete from AccountResourceRefVO ref where ref.resourceUuid in (:uuids) and ref.resourceType = :resourceType";
        Query q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuids", entityIds);
        q.setParameter("resourceType", entityClass.getSimpleName());
        q.executeUpdate();
    }

    @Override
    public List<Class> getEntityClassForHardDeleteEntityExtension() {
        return resourceTypes;
    }

    @Override
    public void postHardDelete(Collection entityIds, Class entityClass) {
        if (resourceTypes.contains(entityClass)) {
            postSoftDelete(entityIds, entityClass);
        }
    }

    @Override
    public List<Class> getMessageClassToIntercept() {
        return null;
    }

    @Override
    public InterceptorPosition getPosition() {
        return InterceptorPosition.FRONT;
    }

    private void logOutSession(String sessionUuid) {
        sessions.remove(sessionUuid);
        dbf.removeByPrimaryKey(sessionUuid, SessionVO.class);
    }

    @Transactional(readOnly = true)
    private Timestamp getCurrentSqlDate() {
        Query query = dbf.getEntityManager().createNativeQuery("select current_timestamp()");
        return (Timestamp) query.getSingleResult();
    }

    class Auth {
        APIMessage msg;
        SessionInventory session;
        MessageAction action;
        String username;

        void validate(APIMessage msg) {
            this.msg = msg;
            if (msg.getClass().isAnnotationPresent(SuppressCredentialCheck.class)) {
                return;
            }

            action = actions.get(msg.getClass());

            sessionCheck();
            policyCheck();

            msg.setSession(session);
        }

        private void accountFieldCheck() throws IllegalAccessException {
            Set resourceUuids = new HashSet();
            Set operationTargetResourceUuids = new HashSet();

            for (AccountCheckField af : action.accountCheckFields) {
                Object value = af.field.get(msg);
                if (value == null) {
                    continue;
                }

                if (String.class.isAssignableFrom(af.field.getType())) {
                    if (af.param.operationTarget()) {
                        operationTargetResourceUuids.add(value);
                    } else {
                        resourceUuids.add(value);
                    }
                } else if (Collection.class.isAssignableFrom(af.field.getType())) {
                    if (af.param.operationTarget()) {
                        operationTargetResourceUuids.addAll((Collection)value);
                    } else {
                        resourceUuids.addAll((Collection)value);
                    }
                }
            }

            if (resourceUuids.isEmpty() && operationTargetResourceUuids.isEmpty()) {
                return;
            }

            // if a resource uuid represents an operation target, it cannot be bypassed by
            // the shared resources, as we don't support roles for cross-account sharing.
            if (!resourceUuids.isEmpty()) {
                SimpleQuery<SharedResourceVO> sq = dbf.createQuery(SharedResourceVO.class);
                sq.select(SharedResourceVO_.receiverAccountUuid, SharedResourceVO_.toPublic, SharedResourceVO_.resourceUuid);
                sq.add(SharedResourceVO_.resourceUuid, Op.IN, resourceUuids);
                List<Tuple> ts = sq.listTuple();
                for (Tuple t : ts) {
                    String ruuid = t.get(0, String.class);
                    Boolean toPublic = t.get(1, Boolean.class);
                    String resUuid = t.get(2, String.class);
                    if (toPublic || session.getAccountUuid().equals(ruuid)) {
                        // this resource is shared to the account
                        resourceUuids.remove(resUuid);
                    }
                }
            }

            resourceUuids.addAll(operationTargetResourceUuids);
            if (resourceUuids.isEmpty()) {
                return;
            }

            SimpleQuery<AccountResourceRefVO> q = dbf.createQuery(AccountResourceRefVO.class);
            q.select(AccountResourceRefVO_.accountUuid, AccountResourceRefVO_.resourceUuid, AccountResourceRefVO_.resourceType);
            q.add(AccountResourceRefVO_.resourceUuid, Op.IN, resourceUuids);
            List<Tuple> ts = q.listTuple();

            for (Tuple t : ts) {
                String auuid = t.get(0, String.class);
                String ruuid = t.get(1, String.class);
                String type = t.get(2, String.class);
                if (!session.getAccountUuid().equals(auuid)) {
                    throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.PERMISSION_DENIED,
                            String.format("operation denied. The resource[uuid: %s, type: %s] doesn't belong to the account[uuid: %s]",
                                    ruuid, type, session.getAccountUuid())
                    ));
                } else {
                    if (logger.isTraceEnabled()) {
                        logger.trace(String.format("account-check pass. The resource[uuid: %s, type: %s] belongs to the account[uuid: %s]",
                                ruuid, type, session.getAccountUuid()));
                    }
                }
            }
        }

        private void useDecision(Decision d, boolean userPolicy) {
            String policyCategory = userPolicy ? "user policy" : "group policy";

            if (d.effect == StatementEffect.Allow) {
                logger.debug(String.format("API[name: %s, action: %s] is approved by a %s[name: %s, uuid: %s]," +
                        " statement[name: %s, action: %s]", msg.getClass().getSimpleName(), d.action, policyCategory, d.policy.getName(),
                        d.policy.getUuid(), d.statement.getName(), d.actionRule));
            } else {
                logger.debug(String.format("API[name: %s, action: %s] is denied by a %s[name: %s, uuid: %s]," +
                                " statement[name: %s, action: %s]", msg.getClass().getSimpleName(), d.action, policyCategory, d.policy.getName(),
                        d.policy.getUuid(), d.statement.getName(), d.actionRule));

                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.PERMISSION_DENIED,
                        String.format("%s denied. user[name: %s, uuid: %s] is denied to execute API[%s]", policyCategory, username, session.getUuid(), msg.getClass().getSimpleName())
                ));
            }
        }

        private void policyCheck() {
            SimpleQuery<AccountVO> q = dbf.createQuery(AccountVO.class);
            q.select(AccountVO_.type);
            q.add(AccountVO_.uuid, Op.EQ, session.getAccountUuid());
            AccountType type = q.findValue();

            if (type == AccountType.SystemAdmin) {
                return;
            }

            if (action.adminOnly) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.PERMISSION_DENIED,
                        String.format("API[%s] is admin only", msg.getClass().getSimpleName())));
            }

            if (action.accountOnly && !session.isAccountSession()) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.PERMISSION_DENIED,
                        String.format("API[%s] can only be called by an account, the current session is a user session[user uuid:%s]",
                                msg.getClass().getSimpleName(), session.getUserUuid())
                        ));
            }

            if (action.accountCheckFields != null && !action.accountCheckFields.isEmpty()) {
                try {
                    accountFieldCheck();
                } catch (ApiMessageInterceptionException ae) {
                    throw ae;
                } catch (Exception e) {
                    throw new CloudRuntimeException(e);
                }
            }

            if (session.isAccountSession()) {
                return;
            }

            SimpleQuery<UserVO> uq = dbf.createQuery(UserVO.class);
            uq.select(UserVO_.name);
            uq.add(UserVO_.uuid, Op.EQ, session.getUserUuid());
            username = uq.findValue();

            List<PolicyInventory> userPolicies = getUserPolicies();
            Decision d = decide(userPolicies);
            if (d != null) {
                useDecision(d, true);
                return;
            }

            List<PolicyInventory> groupPolicies = getGroupPolicies();
            d = decide(groupPolicies);
            if (d != null) {
                useDecision(d, false);
                return;
            }

            throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.PERMISSION_DENIED,
                    String.format("user[name: %s, uuid: %s] has no policy set for this operation, API[%s] is denied by default. You may either create policies for this user" +
                            " or add the user into a group with polices set", username, session.getUserUuid(), msg.getClass().getSimpleName())
            ));
        }


        @Transactional(readOnly = true)
        private List<PolicyInventory> getGroupPolicies() {
            String sql = "select p from PolicyVO p, UserGroupUserRefVO ref, UserGroupPolicyRefVO gref where" +
                    " p.uuid = gref.policyUuid and gref.groupUuid = ref.groupUuid and ref.userUuid = :uuid";
            TypedQuery<PolicyVO> q = dbf.getEntityManager().createQuery(sql, PolicyVO.class);
            q.setParameter("uuid", session.getUserUuid());
            return PolicyInventory.valueOf(q.getResultList());
        }

        class Decision {
            PolicyInventory policy;
            String action;
            Statement statement;
            String actionRule;
            StatementEffect effect;
        }

        private Decision decide(List<PolicyInventory> userPolicies) {
            for (String a : action.actions) {
                for (PolicyInventory p : userPolicies) {
                    for (Statement s : p.getStatements()) {
                        for (String ac : s.getActions()) {
                            Pattern pattern = Pattern.compile(ac);
                            Matcher m = pattern.matcher(a);
                            boolean ret = m.matches();
                            if (ret) {
                                Decision d = new Decision();
                                d.policy = p;
                                d.action = a;
                                d.statement = s;
                                d.actionRule = ac;
                                d.effect = s.getEffect();
                                return d;
                            }

                            if (logger.isTraceEnabled()) {
                                logger.trace(String.format("API[name: %s, action: %s] is not matched by policy[name: %s, uuid: %s" +
                                        ", statement[name: %s, action: %s, effect: %s]", msg.getClass().getSimpleName(), a, p.getName(),
                                        p.getUuid(), s.getName(), ac, s.getEffect()));
                            }
                        }
                    }
                }
            }

            return null;
        }

        @Transactional(readOnly = true)
        private List<PolicyInventory> getUserPolicies() {
            String sql = "select p from PolicyVO p, UserPolicyRefVO ref where ref.userUuid = :uuid and ref.policyUuid = p.uuid";
            TypedQuery<PolicyVO> q = dbf.getEntityManager().createQuery(sql, PolicyVO.class);
            q.setParameter("uuid", session.getUserUuid());
            return PolicyInventory.valueOf(q.getResultList());
        }

        private void sessionCheck() {
            if (msg.getSession() == null) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.INVALID_SESSION,
                        String.format("session of message[%s] is null", msg.getMessageName())));
            }

            if (msg.getSession().getUuid() == null) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.INVALID_SESSION,
                        "session uuid is null"));
            }

            SessionInventory session = sessions.get(msg.getSession().getUuid());
            if (session == null) {
                SessionVO svo = dbf.findByUuid(msg.getSession().getUuid(), SessionVO.class);
                if (svo == null) {
                    throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.INVALID_SESSION, "Session expired"));
                }
                session = SessionInventory.valueOf(svo);
                sessions.put(session.getUuid(), session);
            }

            Timestamp curr = getCurrentSqlDate();
            if (curr.after(session.getExpiredDate())) {
                logger.debug(String.format("session expired[%s < %s] for account[uuid:%s]", curr, session.getExpiredDate(), session.getAccountUuid()));
                logOutSession(session.getUuid());
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(IdentityErrors.INVALID_SESSION, "Session expired"));
            }

            this.session = session;
        }
    }

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        new Auth().validate(msg);

        if (msg instanceof APIUpdateAccountMsg) {
            validate((APIUpdateAccountMsg) msg);
        } else if (msg instanceof APICreatePolicyMsg) {
            validate((APICreatePolicyMsg) msg);
        } else if (msg instanceof APIAddUserToGroupMsg) {
            validate((APIAddUserToGroupMsg) msg);
        } else if (msg instanceof APIAttachPolicyToUserGroupMsg) {
            validate((APIAttachPolicyToUserGroupMsg) msg);
        } else if (msg instanceof APIAttachPolicyToUserMsg) {
            validate((APIAttachPolicyToUserMsg) msg);
        } else if (msg instanceof APIDetachPolicyFromUserGroupMsg) {
            validate((APIDetachPolicyFromUserGroupMsg) msg);
        } else if (msg instanceof APIDetachPolicyFromUserMsg) {
            validate((APIDetachPolicyFromUserMsg) msg);
        } else if (msg instanceof APIShareResourceMsg) {
            validate((APIShareResourceMsg) msg);
        } else if (msg instanceof APIRevokeResourceSharingMsg) {
            validate((APIRevokeResourceSharingMsg) msg);
        } else if (msg instanceof APIUpdateUserMsg) {
            validate((APIUpdateUserMsg) msg);
        } else if (msg instanceof APIDeleteAccountMsg) {
            validate((APIDeleteAccountMsg) msg);
        } else if (msg instanceof APICreateAccountMsg) {
            validate((APICreateAccountMsg) msg);
        } else if (msg instanceof APICreateUserMsg) {
            validate((APICreateUserMsg) msg);
        } else if (msg instanceof APICreateUserGroupMsg) {
            validate((APICreateUserGroupMsg) msg);
        }

        setServiceId(msg);

        return msg;
    }

    private void validate(APICreateUserGroupMsg msg) {
        SimpleQuery<UserGroupVO> q = dbf.createQuery(UserGroupVO.class);
        q.add(UserGroupVO_.accountUuid, Op.EQ, msg.getAccountUuid());
        q.add(UserGroupVO_.name, Op.EQ, msg.getName());
        if (q.isExists()) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("unable to create a group. A group called %s is already under the account[uuid:%s]", msg.getName(), msg.getAccountUuid())
            ));
        }
    }

    private void validate(APICreateUserMsg msg) {
        SimpleQuery<UserVO> q = dbf.createQuery(UserVO.class);
        q.add(UserVO_.accountUuid, Op.EQ, msg.getAccountUuid());
        q.add(UserVO_.name, Op.EQ, msg.getName());
        if (q.isExists()) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("unable to create a user. A user called %s is already under the account[uuid:%s]", msg.getName(), msg.getAccountUuid())
            ));
        }
    }

    private void validate(APICreateAccountMsg msg) {
        SimpleQuery<AccountVO> q = dbf.createQuery(AccountVO.class);
        q.add(AccountVO_.name, Op.EQ, msg.getName());
        if (q.isExists()) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("unable to create an account. An account already called %s", msg.getName())
            ));
        }
    }

    private void validate(APIDeleteAccountMsg msg) {
        SimpleQuery<AccountVO> q = dbf.createQuery(AccountVO.class);
        q.select(AccountVO_.type);
        q.add(AccountVO_.uuid, Op.EQ, msg.getUuid());
        AccountType type = q.findValue();
        if (AccountType.SystemAdmin == type) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    "unable to delete an account. The account is an admin account"
            ));
        }
    }

    private void validate(APIUpdateUserMsg msg) {
        if (msg.getUuid() == null && msg.getSession().isAccountSession()) {
            throw new ApiMessageInterceptionException (errf.stringToInvalidArgumentError(
                    "the current session is an account session. You need to specify the field 'uuid'" +
                            " to the user you want to reset the password"
            ));
        }

        if (msg.getSession().isAccountSession()) {
            return;
        }

        if (msg.getUuid() != null && !msg.getSession().getUserUuid().equals(msg.getUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("cannot change the password, you are not the owner user of user[uuid:%s]",
                            msg.getUuid())
            ));
        }

        msg.setUuid(msg.getSession().getUserUuid());
    }

    private void validate(APIRevokeResourceSharingMsg msg) {
        if (!msg.isAll() && (msg.getAccountUuids() == null || msg.getAccountUuids().isEmpty())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    "all is set to false, accountUuids cannot be null or empty"
            ));
        }
    }

    private void validate(APIShareResourceMsg msg) {
        if (!msg.isToPublic() && (msg.getAccountUuids() == null || msg.getAccountUuids().isEmpty())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    "toPublic is set to false, accountUuids cannot be null or empty"
            ));
        }
    }

    private void validate(APIDetachPolicyFromUserMsg msg) {
        PolicyVO policy = dbf.findByUuid(msg.getPolicyUuid(), PolicyVO.class);
        UserVO user = dbf.findByUuid(msg.getUserUuid(), UserVO.class);
        if (!policy.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("policy[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            policy.getName(), policy.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
        if (!user.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("user[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            user.getName(), user.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
    }

    private void validate(APIDetachPolicyFromUserGroupMsg msg) {
        PolicyVO policy = dbf.findByUuid(msg.getPolicyUuid(), PolicyVO.class);
        UserGroupVO group = dbf.findByUuid(msg.getGroupUuid(), UserGroupVO.class);
        if (!policy.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("policy[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            policy.getName(), policy.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
        if (!group.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("group[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            group.getName(), group.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
    }

    private void validate(APIAttachPolicyToUserMsg msg) {
        PolicyVO policy = dbf.findByUuid(msg.getPolicyUuid(), PolicyVO.class);
        UserVO user = dbf.findByUuid(msg.getUserUuid(), UserVO.class);
        if (!policy.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("policy[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            policy.getName(), policy.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
        if (!user.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("user[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            user.getName(), user.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
    }

    private void validate(APIAttachPolicyToUserGroupMsg msg) {
        PolicyVO policy = dbf.findByUuid(msg.getPolicyUuid(), PolicyVO.class);
        UserGroupVO group = dbf.findByUuid(msg.getGroupUuid(), UserGroupVO.class);
        if (!policy.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("policy[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            policy.getName(), policy.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
        if (!group.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("group[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            group.getName(), group.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
    }

    private void validate(APIAddUserToGroupMsg msg) {
        UserVO user = dbf.findByUuid(msg.getUserUuid(), UserVO.class);
        UserGroupVO group = dbf.findByUuid(msg.getGroupUuid(), UserGroupVO.class);
        if (!user.getAccountUuid().equals(msg.getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("user[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            user.getName(), user.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
        if (!group.getAccountUuid().equals(msg.getSession().getAccountUuid())) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("group[name: %s, uuid: %s] doesn't belong to the account[uuid: %s]",
                            group.getName(), group.getUuid(), msg.getSession().getAccountUuid())
            ));
        }
    }

    private void validate(APICreatePolicyMsg msg) {
        for (Statement s : msg.getStatements()) {
            if (s.getEffect() == null) {
                throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                        String.format("a statement must have effect field. Invalid statement[%s]", JSONObjectUtil.toJsonString(s))
                ));
            }
            if (s.getActions() == null) {
                throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                        String.format("a statement must have action field. Invalid statement[%s]", JSONObjectUtil.toJsonString(s))
                ));
            }
            if (s.getActions().isEmpty()) {
                throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                        String.format("a statement must have a non-empty action field. Invalid statement[%s]", JSONObjectUtil.toJsonString(s))
                ));
            }
        }
    }

    private void validate(APIUpdateAccountMsg msg) {
        AccountVO a = dbf.findByUuid(msg.getSession().getAccountUuid(), AccountVO.class);
        if (msg.getUuid() == null) {
            msg.setUuid(msg.getSession().getAccountUuid());
        }

        if (a.getType() == AccountType.SystemAdmin) {
            return;
        }

        AccountVO account = dbf.findByUuid(msg.getUuid(), AccountVO.class);
        if (!account.getUuid().equals(a.getUuid())) {
            throw new OperationFailureException(errf.stringToOperationError(
                    String.format("account[uuid: %s, name: %s] is a normal account, it cannot reset the password of another account[uuid: %s]",
                            account.getUuid(), account.getName(), msg.getUuid())
            ));
        }
    }

    private void setServiceId(APIMessage msg) {
        if (msg instanceof AccountMessage) {
            AccountMessage amsg = (AccountMessage) msg;
            bus.makeTargetServiceIdByResourceUuid(msg, AccountConstant.SERVICE_ID, amsg.getAccountUuid());
        }
    }

    public void setResourceTypeForAccountRef(List<String> resourceTypeForAccountRef) {
        this.resourceTypeForAccountRef = resourceTypeForAccountRef;
    }
}
