package com.qcloud.cos.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.CreateSessionRequest;
import com.qcloud.cos.model.CreateSessionResult;

/**
 * cos-rapid 高性能桶 Session 管理器。
 * 按桶缓存 CreateSession 临时密钥，普通请求在剩余有效期不超过 90 秒时刷新。
 */
public class RapidSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RapidSessionManager.class);

    public static final int REFRESH_THRESHOLD_SEC = 90;

    private final ConcurrentMap<String, RapidSessionCredentials> bucketToRapidCredMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> lockMap = new ConcurrentHashMap<>();
    private final RapidCOSCredentialProvider owner;

    public RapidSessionManager(RapidCOSCredentialProvider owner) {
        if (owner == null) {
            throw new CosClientException("owner must not be null for RapidSessionManager");
        }
        this.owner = owner;
    }

    /** 获取指定桶的临时密钥，必要时惰性刷新。cosClient 用于发起 CreateSession。 */
    public RapidSessionCredentials getActiveSession(COSClient cosClient, String bucketName) {
        return getActiveSession(cosClient, bucketName, null);
    }

    /** 获取临时密钥，刷新时继承请求级连接地址。 */
    public RapidSessionCredentials getActiveSession(COSClient cosClient, String bucketName,
            String fixedEndpointAddr) {
        return getActiveSession(cosClient, bucketName, REFRESH_THRESHOLD_SEC, fixedEndpointAddr);
    }

    private RapidSessionCredentials getActiveSession(
        COSClient cosClient, String bucketName, long reuseWindowSec,
        String fixedEndpointAddr) throws CosClientException {
        RapidSessionCredentials cred = bucketToRapidCredMap.get(bucketName);

        // 不检查 baseAKID 变化：AssumeRole 场景下 baseAK 按分钟级轮换，
        // 但服务端颁发的 Session 临时密钥在有效期内始终可用，无需跟随 baseAK 刷新。
        if (cred != null && cred.getExpiredTimeSec() - System.currentTimeMillis() / 1000L > reuseWindowSec) {
            return cred;
        }

        synchronized (lockFor(bucketName)) {
            cred = bucketToRapidCredMap.get(bucketName);
            if (cred != null && cred.getExpiredTimeSec() - System.currentTimeMillis() / 1000L > reuseWindowSec) {
                return cred;
            }

            try {
                String currentBaseAKID = resolveCurrentBaseAKID();
                RapidSessionCredentials refreshed = doRefresh(cosClient, bucketName, currentBaseAKID,
                        fixedEndpointAddr);
                bucketToRapidCredMap.put(bucketName, refreshed);
                return refreshed;
            } catch (CosClientException e) {
                // 刷新失败时，继续使用尚未过期的缓存凭证。
                if (cred != null && !cred.isAboutToExpire(0)) {
                    CosServiceException serviceError = e instanceof CosServiceException
                            ? (CosServiceException) e : null;
                    log.warn("CreateSession refresh failed; reusing cached session for bucket: {}, "
                                    + "remainingSeconds: {}, exceptionType: {}, errorCode: {}, requestId: {}",
                            bucketName, cred.getExpiredTimeSec() - System.currentTimeMillis() / 1000L,
                            e.getClass().getSimpleName(),
                            serviceError == null ? null : serviceError.getErrorCode(),
                            serviceError == null ? null : serviceError.getRequestId());
                    return cred;
                }
                throw e;
            }
        }
    }

    private String resolveCurrentBaseAKID() throws CosClientException {
        COSCredentials baseCred = owner.getBaseCredProvider().getCredentials();
        if (baseCred == null) {
            throw new CosClientException(
                    "base credentials from Provider is null. please check your base credentials provider");
        }
        return baseCred.getCOSAccessKeyId();
    }

    private RapidSessionCredentials doRefresh(COSClient cosClient, String bucketName, String baseAKID,
            String fixedEndpointAddr) {
        if (cosClient == null) {
            throw new CosClientException(
                    "cosClient is required to create session for bucket: " + bucketName);
        }
        // 继承请求级连接地址，Host 由 Client 配置生成。
        CreateSessionRequest request = new CreateSessionRequest(bucketName);
        request.setFixedEndpointAddr(fixedEndpointAddr);
        CreateSessionResult result = cosClient.createSession(request);

        CreateSessionResult.Credentials c = result.getCredentials();
        long expiredTimeSec = java.time.Instant.parse(result.getExpiration()).getEpochSecond();

        log.debug("CreateSession success for bucket: {}, requestId: {}, expiredTime: {}",
                bucketName, result.getRequestId(), expiredTimeSec);
        return new RapidSessionCredentials(c.getTmpSecretId(), c.getTmpSecretKey(), c.getSessionToken(), expiredTimeSec, baseAKID);
    }

    /** 获取临时密钥并确保剩余有效期满足预签名需求。 */
    public RapidSessionCredentials getActiveSessionForPresign(COSClient cosClient, String bucketName,
            long requiredRemainingSec) {
        return getActiveSessionForPresign(cosClient, bucketName, requiredRemainingSec, null);
    }

    /** 获取预签名临时密钥，刷新时继承请求级连接地址。 */
    public RapidSessionCredentials getActiveSessionForPresign(COSClient cosClient, String bucketName,
            long requiredRemainingSec, String fixedEndpointAddr) {
        RapidSessionCredentials cred = getActiveSession(
                cosClient, bucketName, requiredRemainingSec, fixedEndpointAddr);

        long refreshedRemaining = cred.getExpiredTimeSec() - (System.currentTimeMillis() / 1000L);
        if (refreshedRemaining < requiredRemainingSec) {
            throw new CosClientException(
                "After refreshing session token, the remaining validity (" + refreshedRemaining
                    + "s) is still less than the required pre-signed URL duration ("
                    + requiredRemainingSec + "s). "
                    + "Please reduce the pre-signed URL expiration time.");
        }
        return cred;
    }

    private Object lockFor(String bucketName) {
        return lockMap.computeIfAbsent(bucketName, k -> new Object());
    }
}
