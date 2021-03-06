package io.mycat.mycat2.net;


import io.mycat.mycat2.MycatConfig;
import io.mycat.mycat2.MycatSession;
import io.mycat.mycat2.Version;
import io.mycat.mycat2.beans.conf.FireWallBean;
import io.mycat.mycat2.beans.conf.UserBean;
import io.mycat.mycat2.beans.conf.UserConfig;
import io.mycat.mysql.CapabilityFlags;
import io.mycat.mysql.ComQueryState;
import io.mycat.mysql.MysqlNativePasswordPluginUtil;
import io.mycat.mysql.packet.ErrorPacket;
import io.mycat.mysql.packet.AuthPacket;
import io.mycat.mysql.packet.HandshakePacket;
import io.mycat.proxy.ConfigEnum;
import io.mycat.proxy.NIOHandler;
import io.mycat.proxy.ProxyBuffer;
import io.mycat.proxy.ProxyRuntime;
import io.mycat.util.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MySQL客户端登录认证的Handler，为第一个Handler
 *
 * @author wuzhihui
 */
public class MySQLClientAuthHandler implements NIOHandler<MycatSession> {
    private static final byte[] AUTH_OK = new byte[]{7, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0};

    private static Logger logger = LoggerFactory.getLogger(MySQLClientAuthHandler.class);


    /**
     * 认证中的seed报文数据
     */
    public byte[] seed;
    public MycatSession mycatSession;

    public MySQLClientAuthHandler() {
    }

    /**
     * 给客户端（front）发送认证报文
     *
     */
    public void sendAuthPackge() {
        byte[][] seedParts = MysqlNativePasswordPluginUtil.nextSeedBuild();
        this.seed = seedParts[2];
        // 发送握手数据包
        HandshakePacket hs = new HandshakePacket();
        hs.packetId = 0;
        hs.protocolVersion = Version.PROTOCOL_VERSION;
        hs.serverVersion = new String(Version.SERVER_VERSION);
        hs.connectionId = mycatSession.getSessionId();
        hs.authPluginDataPartOne = new String(seedParts[0]);
        hs.capabilities = new CapabilityFlags(mycatSession.getServerCapabilities());
        hs.hasPartTwo = true;
        hs.characterSet = 8;
        hs.statusFlags = 2;
        hs.authPluginDataLen = 21; // 有插件的话，总长度必是21, seed
        hs.authPluginDataPartTwo = new String(seedParts[1]);
        hs.authPluginName = MysqlNativePasswordPluginUtil.PROTOCOL_PLUGIN_NAME;
        mycatSession.responseMySQLPacket(hs);
    }

    @Override
    public void onSocketRead(MycatSession session) throws IOException {

        if (!session.readFromChannel()) {
            return;
        }
        if (!session.curPacketInf.readFully()){
            return;
        }
        ProxyBuffer frontBuffer = session.curPacketInf.getProxyBuffer();

        // 处理用户认证报文
        try {
            AuthPacket auth = new AuthPacket();
            auth.read(frontBuffer);

            MycatConfig config = ProxyRuntime.INSTANCE.getConfig();
            UserConfig userConfig = config.getConfig(ConfigEnum.USER);
            UserBean userBean = null;
            String username = auth.username;
            for (UserBean user : userConfig.getUsers()) {
                if (user.getName().equals(username)) {
                    userBean = user;
                    break;
                }
            }

            // check user
            if (!checkUser(session, userConfig, userBean)) {
                failure(session, ErrorCode.ER_ACCESS_DENIED_ERROR, "Access denied for user '" + username + "' with addr '" + session.addr + "'");
                return;
            }

//			 check password
            if (!checkPassword(session, userBean, auth.password)) {
                failure(session, ErrorCode.ER_ACCESS_DENIED_ERROR, "Access denied for user '" + username + "', because password is error ");
                return;
            }

//			 check degrade
//			if (isDegrade(auth.user)) {
//				failure(ErrorCode.ER_ACCESS_DENIED_ERROR, "Access denied for user '" + auth.user + "', because service be degraded ");
//				return;
//			}

            // check mycatSchema
            String database = auth.database;
            switch (checkSchema(userBean, database)) {
                case ErrorCode.ER_BAD_DB_ERROR:
                    failure(session, ErrorCode.ER_BAD_DB_ERROR, "Unknown database '" + database + "'");
                    break;
                case ErrorCode.ER_DBACCESS_DENIED_ERROR:
                    String s = "Access denied for user '" + username + "' to database '" + database + "'";
                    failure(session, ErrorCode.ER_DBACCESS_DENIED_ERROR, s);
                    break;
                default:
                    // set mycatSchema
                    if (auth.database == null) {
                        session.setMycatSchema((userBean.getSchemas() == null) ?
                                config.getDefaultSchemaBean() : config.getSchemaBean(userBean.getSchemas().get(0)));
                    } else {
                        session.setMycatSchema(config.getSchemaBean(database));
                    }
                    if (Objects.isNull(session.getMycatSchema())) {
                        logger.error(" mycatSchema:{} can not match user: {}", session.getMycatSchema(), username);
                    }
                    logger.debug("set mycatSchema: {} for user: {}", session.getMycatSchema(), username);
                    if (success(session, auth)) {
                        session.clientUser = username;//设置session用户
                        session.curPacketInf.reset();
                        session.answerFront(AUTH_OK);
                        // 认证通过，设置当前SQL Handler为默认Handler
                        session.setCurNIOHandler(MainMycatNIOHandler.INSTANCE);
                    }
            }
        } catch (Throwable e) {
            logger.warn("Frontend FrontendAuthenticatingState error:", e);
        }
    }

    private boolean checkUser(MycatSession session, UserConfig userConfig, UserBean userBean) {
        if (userBean == null) {
            return false;
        }

        FireWallBean firewall = userConfig.getFirewall();
        if (!firewall.isEnable()) {
            return true;
        }

        // 防火墙 白名单处理
        boolean isPassed = false;
        List<FireWallBean.WhiteBean> whiteBeanList = firewall.getWhite();

        if ((whiteBeanList != null && whiteBeanList.size() > 0)) {
            for (FireWallBean.WhiteBean whiteBean : whiteBeanList) {
                if (userBean.getName().equals(whiteBean.getUser()) && Pattern.matches(whiteBean.getHost(), session.host)) {
                    isPassed = true;
                    break;
                }
            }
        }

        if (!isPassed) {
            logger.error("firewall attack from host: {}, user: {}", session.host, userBean.getName());
            return false;
        }
        return true;
    }

    private boolean checkPassword(MycatSession session, UserBean userBean, byte[] password) {
        String pass = userBean.getPassword();

        // check null
        if (pass == null || pass.length() == 0) {
            return (password == null || password.length == 0);
        }

        if (password == null || password.length == 0) {
            return false;
        }

        byte[] encryptPass = MysqlNativePasswordPluginUtil.scramble411(pass, seed);

        if (encryptPass != null && (encryptPass.length == password.length)) {
            int i = encryptPass.length;
            while (i-- != 0) {
                if (encryptPass[i] != password[i]) {
                    return false;
                }
            }
        } else {
            return false;
        }

        return true;
    }

    private int checkSchema(UserBean userBean, String schema) {
        if (schema == null) {
            return 0;
        }

        List<String> schemas = userBean.getSchemas();
        if (schemas == null || schemas.size() == 0 || schemas.contains(schema)) {
            return 0;
        } else {
            return ErrorCode.ER_DBACCESS_DENIED_ERROR;
        }
    }

    private void failure(MycatSession session, int errno, String info) throws IOException {
        ErrorPacket errorPacket = new ErrorPacket();
        errorPacket.packetId = 2;
        errorPacket.errno = errno;
        errorPacket.message = info;
        session.responseMySQLPacket(errorPacket);
    }

    private boolean success(MycatSession session, AuthPacket auth) {
        // 设置字符集编码
        int charsetIndex = (auth.characterSet & 0xff);
        // 保存字符集索引
        session.charSet.charsetIndex = charsetIndex;
//		ProxyRuntime.INSTANCE.getConfig().getMySQLRepBean(session.mycatSchema.getDefaultDN().getReplica()).getMetaBeans().get(0).INDEX_TO_CHARSET.get(charsetIndex);
        logger.debug("login success, charsetIndex = {}", charsetIndex);
        return true;
    }
    public void setMycatSession(MycatSession mycatSession) {
        this.mycatSession = mycatSession;
    }

    @Override
    public void onSocketWrite(MycatSession session) throws IOException {
        session.writeToChannel();
    }

    @Override
    public void onSocketClosed(MycatSession userSession, boolean normal) {
        userSession.lazyCloseSession(normal, "front closed");
    }

    @Override
    public void onConnect(SelectionKey curKey, MycatSession session, boolean success, String msg) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onWriteFinished(MycatSession session) {
        // 明确开启读操作
        session.curPacketInf.getProxyBuffer().flip();
        session.change2ReadOpts();
    }
}
