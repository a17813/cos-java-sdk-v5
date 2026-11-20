package com.qcloud.cos.auth;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;

/**
 * cos-rapid 高性能桶透明凭证 provider。
 * 根据 useRapidSession 参数自动路由：桶级请求返回基础凭证，对象级请求返回 Session 临时密钥。
 */
public class RapidCOSCredentialProvider implements COSCredentialsProvider {

    private final COSCredentialsProvider baseCredProvider;
    private final RapidSessionManager rapidSessionManager;

    public RapidCOSCredentialProvider(COSCredentials cred) {
        this(new COSStaticCredentialsProvider(cred));
    }

    public RapidCOSCredentialProvider(COSCredentialsProvider baseCredProvider) {
        if (baseCredProvider == null) {
            throw new CosClientException("baseCredProvider must not be null for RapidCOSCredentialProvider");
        }
        if (baseCredProvider instanceof RapidCOSCredentialProvider) {
            throw new CosClientException("baseCredProvider must not be another RapidCOSCredentialProvider");
        }
        this.baseCredProvider = baseCredProvider;
        this.rapidSessionManager = new RapidSessionManager(this);
    }

    public COSCredentialsProvider getBaseCredProvider() {
        return baseCredProvider;
    }

    /** 返回基础凭证（桶级请求默认行为）。 */
    @Override
    public COSCredentials getCredentials() {
        return baseCredProvider.getCredentials();
    }

    /**
     * 按路由参数返回凭证：useRapidSession=true 时返回 Session 临时密钥，否则返回基础凭证。
     * cosClient 为发起 CreateSession 的主 client，复用其 EndpointResolver、协议与 region 配置。
     */
    @Override
    public COSCredentials getCredentials(
        COSClient cosClient, boolean useRapidSession, String bucketName) throws CosClientException {
        return getCredentials(cosClient, useRapidSession, bucketName, null);
    }

    /** 获取凭证，内部 CreateSession 继承请求级连接地址。 */
    public COSCredentials getCredentials(COSClient cosClient, boolean useRapidSession, String bucketName,
            String fixedEndpointAddr) throws CosClientException {
        if (!useRapidSession) {
            return getCredentials();
        }
        if (bucketName == null || bucketName.isEmpty()) {
            throw new CosClientException("bucketName is required for getCredentials");
        }
        return rapidSessionManager.getActiveSession(cosClient, bucketName, fixedEndpointAddr);
    }

    /** 为预签名获取凭证，确保 Session 剩余有效期 ≥ requiredRemainingSec。 */
    public COSCredentials getCredentialsForPresign(COSClient cosClient,
                                                   String bucketName, long requiredRemainingSec) throws CosClientException {
        return getCredentialsForPresign(cosClient, bucketName, requiredRemainingSec, null);
    }

    /** 为预签名获取凭证，内部 CreateSession 继承请求级连接地址。 */
    public COSCredentials getCredentialsForPresign(COSClient cosClient, String bucketName,
            long requiredRemainingSec, String fixedEndpointAddr) throws CosClientException {
        if (bucketName == null || bucketName.isEmpty()) {
            throw new CosClientException("bucketName is required for getCredentialsForPresign");
        }
        return rapidSessionManager.getActiveSessionForPresign(
                cosClient, bucketName, requiredRemainingSec, fixedEndpointAddr);
    }

    @Override
    public void refresh() {
        baseCredProvider.refresh();
    }
}
