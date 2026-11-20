package com.qcloud.cos.rapid;

import com.qcloud.cos.AbstractCOSClientTest;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.endpoint.RegionEndpointBuilder;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.AbortMultipartUploadRequest;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.ListMultipartUploadsRequest;
import com.qcloud.cos.model.MultipartUpload;
import com.qcloud.cos.model.MultipartUploadListing;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.EndpointUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class AbstractRapidCOSClientTest extends AbstractCOSClientTest {

    protected static boolean rapidBucketAvailable = false;
    // 本地对接时 fusion-proxy 地址，供桶级请求（CreateBucket 等）设 fixedEndpointAddr 用；
    // 非本地模式为 null。
    protected static String localProxyAddr = null;
    private static boolean ownsTestBucket;
    private static String testFileSystemId;
    private static final AtomicInteger hostsBucketSequence = new AtomicInteger();

    protected static boolean isHostsMode() {
        return Boolean.parseBoolean(System.getenv("rapidHostsMode"));
    }

    public static void initRapidCosClient() throws Exception {
        rapidBucketAvailable = false;
        ownsTestBucket = false;
        testFileSystemId = null;
        localProxyAddr = null;
        cosclient = null;
        if (!initConfig()) {
            return;
        }

        // rapid 桶是预先创建的，桶名不能拼接随机数和 appid，需要用原始环境变量覆盖
        String envBucket = System.getenv("bucket");
        if (envBucket != null && !envBucket.isEmpty()) {
            bucket = envBucket;
        }

        rapidBucketAvailable = EndpointUtils.isRapidBucket(bucket);
        if (!rapidBucketAvailable) {
            return;
        }

        COSCredentials baseCred = new BasicCOSCredentials(secretId, secretKey);
        RapidCOSCredentialProvider rapidProvider = new RapidCOSCredentialProvider(baseCred);
        clientConfig = new ClientConfig(new Region(region));
        clientConfig.setIsDistinguishHost(true);
        clientConfig.setConnectionTimeout(5000);
        clientConfig.setSocketTimeout(10000);
        String scheme = System.getenv("scheme");
        if ("http".equalsIgnoreCase(scheme)) {
            clientConfig.setHttpProtocol(HttpProtocol.http);
        }
        // 本地对接 fusion-io / fusion-proxy：保留默认 RegionEndpointBuilder 生成生产桶域名
        // 用于 host 头与签名，另注入 EndpointResolver 把实际连接拨到本地端口。
        // gatewayAddr 承接对象级 / CreateSession 请求（fusion-io 网关，默认 127.0.0.1:9990）；
        // proxyAddr 承接 GetService/ListBuckets 请求（fusion-proxy，默认 127.0.0.1:8080）。
        final String gatewayAddr = envOrDefault("gatewayAddr", "127.0.0.1:9990");
        final String proxyAddr = envOrDefault("proxyAddr", "127.0.0.1:8080");
        String localRapid = System.getenv("localRapid");
        if (isHostsMode()) {
            if (localRapid != null && !localRapid.isEmpty()) {
                throw new IllegalStateException("rapidHostsMode cannot be combined with localRapid");
            }
            final int proxyPort = Integer.parseInt(envOrDefault("rapidHostsProxyPort", "8080"));
            if (proxyPort < 1 || proxyPort > 65535) {
                throw new IllegalArgumentException("Invalid rapidHostsProxyPort");
            }
            // hosts 仅映射 IP，service 端口由测试环境指定。
            final RegionEndpointBuilder endpoints = new RegionEndpointBuilder(new Region(region));
            final String serviceEndpoint = endpoints.buildGetServiceApiEndpoint(bucket)
                    + (proxyPort == 80 ? "" : ":" + proxyPort);
            clientConfig.setEndpointBuilder(new com.qcloud.cos.endpoint.EndpointBuilder() {
                @Override
                public String buildGeneralApiEndpoint(String bucketName) {
                    return endpoints.buildGeneralApiEndpoint(bucketName);
                }

                @Override
                public String buildGetServiceApiEndpoint() {
                    return serviceEndpoint;
                }
            });
        } else if (localRapid != null && !localRapid.isEmpty()) {
            localProxyAddr = proxyAddr;
            clientConfig.setEndpointResolver(new EndpointResolver() {
                @Override
                public String resolveGeneralApiEndpoint(String generalApiEndpoint) {
                    return gatewayAddr;
                }

                @Override
                public String resolveGetServiceApiEndpoint(String getServiceApiEndpoint) {
                    return proxyAddr;
                }
            });
        } else if (generalApiEndpoint != null && generalApiEndpoint.trim().length() > 0 &&
                serviceApiEndpoint != null && serviceApiEndpoint.trim().length() > 0) {
            clientConfig.setEndpointBuilder(
                    new UserSpecifiedEndpointBuilder(generalApiEndpoint, serviceApiEndpoint));
        }
        cosclient = new COSClient(rapidProvider, clientConfig);
        if (Boolean.parseBoolean(System.getenv("rapidIsolatedBucket"))) {
            if (localProxyAddr == null && !isHostsMode()) {
                throw new IllegalStateException("rapidIsolatedBucket requires localRapid or rapidHostsMode");
            }
            bucket = createTemporaryRapidBucket();
            ownsTestBucket = true;
            System.out.println("Rapid isolated test bucket: " + bucket);
            if (System.getenv("rapidRootStubUrl") != null) {
                prepareStubFileSystem();
            }
        }

        tmpDir = new File("ut_test_tmp_data");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    protected static String newRapidBucketName() {
        String appId = bucket.substring(bucket.lastIndexOf('-') + 1);
        if (isHostsMode()) {
            String prefix = System.getenv("rapidHostsBucketPrefix");
            if (prefix == null || !prefix.matches("j[a-z0-9]{6,9}")) {
                throw new IllegalStateException("rapidHostsBucketPrefix must be a short unique test prefix");
            }
            int index = hostsBucketSequence.incrementAndGet();
            if (index > 64) {
                throw new IllegalStateException("Exhausted the 64 preconfigured hosts bucket names");
            }
            return prefix + index + "-x--" + appId;
        }
        return "jut" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + "-x--" + appId;
    }

    protected static String createTemporaryRapidBucket() {
        String name = newRapidBucketName();
        CreateBucketRequest request = new CreateBucketRequest(name);
        request.putCustomRequestHeader("x-cos-vpc-id", "vpc-rapidut");
        request.putCustomRequestHeader("x-cos-cidr-block", "10.0.0.0/24");
        request.putCustomRequestHeader("x-cos-subnet-id", "subnet-rapidut");
        request.putCustomRequestHeader("x-cos-zone", region + "-1");
        cosclient.createBucket(request);
        return name;
    }

    private static JSONObject callRootStub(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                System.getenv("rapidRootStubUrl") + path).openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Root stub " + path + ": HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void prepareStubFileSystem() throws Exception {
        JSONObject state = callRootStub("GET", "/api/dump", null);
        String metaServerId = System.getenv("rapidMetaServerId");
        if (metaServerId == null || !state.getJSONObject("metaServers").has(metaServerId)) {
            throw new IllegalStateException("rapidMetaServerId must identify a running root stub MetaServer");
        }
        // root stub 不分配文件系统，仅为本次新建桶补齐独立映射。
        testFileSystemId = "fs-" + bucket;
        JSONObject testBucket = state.getJSONObject("buckets").getJSONObject(bucket);
        testBucket.put("fileSystemId", testFileSystemId);
        JSONObject fileSystem = new JSONObject().put("fileSystemId", testFileSystemId)
                .put("name", testFileSystemId).put("status", 2)
                .put("ownerUin", Long.parseLong(ownerUin)).put("ownerAppId", Long.parseLong(appid));
        JSONObject body = new JSONObject()
                .put("buckets", new JSONArray().put(testBucket))
                .put("fileSystems", new JSONArray().put(fileSystem))
                .put("fileSystemConfigs", new JSONArray().put(new JSONObject().put("fileSystemId", testFileSystemId)))
                .put("fsLeaders", new JSONObject().put(testFileSystemId, metaServerId));
        callRootStub("POST", "/api/init", body);
    }

    public static void destroyRapidCosClient() throws Exception {
        try {
            if (ownsTestBucket) {
                ListMultipartUploadsRequest request = new ListMultipartUploadsRequest(bucket);
                MultipartUploadListing uploads;
                do {
                    uploads = cosclient.listMultipartUploads(request);
                    for (MultipartUpload upload : uploads.getMultipartUploads()) {
                        cosclient.abortMultipartUpload(new AbortMultipartUploadRequest(
                                bucket, upload.getKey(), upload.getUploadId()));
                    }
                    request.setKeyMarker(uploads.getNextKeyMarker());
                    request.setUploadIdMarker(uploads.getNextUploadIdMarker());
                } while (uploads.isTruncated());
                ObjectListing objects = cosclient.listObjects(bucket);
                while (true) {
                    for (COSObjectSummary object : objects.getObjectSummaries()) {
                        cosclient.deleteObject(bucket, object.getKey());
                    }
                    if (!objects.isTruncated()) {
                        break;
                    }
                    objects = cosclient.listNextBatchOfObjects(objects);
                }
                cosclient.deleteBucket(bucket);
                if (testFileSystemId != null) {
                    callRootStub("DELETE", "/api/fs-leader/" + testFileSystemId, null);
                    callRootStub("DELETE", "/api/filesystem/" + testFileSystemId, null);
                    testFileSystemId = null;
                }
                System.out.println("Rapid isolated test bucket deleted: " + bucket);
            }
        } finally {
            ownsTestBucket = false;
            if (cosclient != null) {
                cosclient.shutdown();
                cosclient = null;
            }
            if (tmpDir != null) {
                deleteDir(tmpDir);
                tmpDir = null;
            }
        }
    }

    protected static void skipIfNotRapid() {
        Assume.assumeTrue("Skipping: configured bucket is not a rapid bucket", rapidBucketAvailable);
    }

    /**
     * 打开预签名 URL 的连接。本地对接（localRapid）时，预签名 URL 的 host 是生产桶域名
     * （已参与签名，不能改），但开发机连不上生产网络，故把 TCP 连接重写到本地网关
     * （gatewayAddr，默认 127.0.0.1:9990），同时保留原 Host 请求头使签名仍然有效。
     * 非本地模式下直接 openConnection。
     * 注意：设置 Host 头需 JVM 参数 -Dsun.net.http.allowRestrictedHeaders=true。
     */
    protected static HttpURLConnection openPresignedConnection(URL url) throws Exception {
        String localRapid = System.getenv("localRapid");
        if (localRapid == null || localRapid.isEmpty()) {
            return (HttpURLConnection) url.openConnection();
        }
        String gatewayAddr = envOrDefault("gatewayAddr", "127.0.0.1:9990");
        String[] hp = gatewayAddr.split(":");
        String localHost = hp[0];
        int localPort = hp.length > 1 ? Integer.parseInt(hp[1]) : 80;
        String originHost = url.getHost();

        String file = url.getFile();
        URL localUrl = new URL(url.getProtocol(), localHost, localPort, file);
        HttpURLConnection conn = (HttpURLConnection) localUrl.openConnection(java.net.Proxy.NO_PROXY);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        // 保留原生产桶域名作为 Host 头，签名基于它，网关据此路由与校验。
        conn.setRequestProperty("Host", originHost);
        return conn;
    }
}