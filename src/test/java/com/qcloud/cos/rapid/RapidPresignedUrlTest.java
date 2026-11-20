package com.qcloud.cos.rapid;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.Headers;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CreateSessionRequest;
import com.qcloud.cos.model.CreateSessionResult;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.Md5Utils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RapidPresignedUrlTest extends AbstractRapidCOSClientTest {

    private static final long EXPIRATION_MS = 3 * 60 * 1000;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        AbstractRapidCOSClientTest.initRapidCosClient();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        AbstractRapidCOSClientTest.destroyRapidCosClient();
    }

    // ==================== helper ====================

    private HttpURLConnection openPresignedConn(URL url) throws IOException {
        try {
            return openPresignedConnection(url);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void putFileWithUrl(URL putUrl, File localFile) throws IOException {
        HttpURLConnection conn = openPresignedConn(putUrl);
        try {
            conn.setDoOutput(true);
            conn.setRequestMethod("PUT");
            conn.setFixedLengthStreamingMode(localFile.length());
            try (InputStream in = new FileInputStream(localFile);
                    OutputStream out = conn.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            assertEquals(200, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    private void getFileWithUrl(URL getUrl, File downloadFile) throws IOException {
        HttpURLConnection conn = openPresignedConn(getUrl);
        try {
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(downloadFile)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void deleteFileWithUrl(URL delUrl) throws IOException {
        HttpURLConnection conn = openPresignedConn(delUrl);
        try {
            conn.setRequestMethod("DELETE");
            assertEquals(204, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    private Date expiration() {
        return new Date(System.currentTimeMillis() + EXPIRATION_MS);
    }

    // ==================== tests ====================

    /**
     * 参考 GeneratePresignedUrlTest#testGetFile：
     * 先 putObject 上传，再用预签名 GET URL 下载并校验 ResponseHeader。
     */
    @Test
    public void testGetFile() throws IOException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) return;

        long fileLen = 1024L;
        File localFile = buildTestFile(fileLen);
        String key = "ut/rapid_presigned_get_" + localFile.getName();
        try {
            putObjectFromLocalFile(localFile, key);

            GeneratePresignedUrlRequest req =
                    new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
            req.setExpiration(expiration());

            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
            overrides.setContentType("application/octet-stream");
            overrides.setContentDisposition("attachment;filename=\"test.bin\"");
            req.setResponseHeaders(overrides);

            URL url = cosclient.generatePresignedUrl(req);
            assertNotNull(url);

            HttpURLConnection conn = openPresignedConn(url);
            try {
                conn.setRequestMethod("GET");
                assertEquals(200, conn.getResponseCode());
                assertEquals(fileLen, conn.getContentLength());
                assertEquals("application/octet-stream", conn.getContentType());
            } finally {
                conn.disconnect();
            }
        } finally {
            clearObject(key);
            assertTrue(localFile.delete());
        }
    }

    /**
     * 参考 GeneratePresignedUrlTest#testGeneralUrl：
     * 用预签名 URL 完成 PUT → HEAD → GET → DELETE 全流程，校验 MD5。
     */
    @Test
    public void testPutGetDeleteWithUrl() throws IOException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) return;

        String key = "ut/rapid_presigned_general_test.txt";
        File localFile = buildTestFile(1024L);
        File downloadFile = new File(localFile.getAbsolutePath() + ".down");
        try {
            Date exp = expiration();
            // signPrefixMode=false 以展开 q-signature= 参数，与普通桶 testGeneralUrl 一致
            URL putUrl = cosclient.generatePresignedUrl(bucket, key, exp,
                    HttpMethodName.PUT, new HashMap<>(), new HashMap<>(), false, true);
            URL getUrl = cosclient.generatePresignedUrl(bucket, key, exp,
                    HttpMethodName.GET, new HashMap<>(), new HashMap<>(), false, true);
            URL delUrl = cosclient.generatePresignedUrl(bucket, key, exp,
                    HttpMethodName.DELETE, new HashMap<>(), new HashMap<>(), false, true);

            assertNotNull(putUrl);
            assertNotNull(getUrl);
            assertNotNull(delUrl);

            // Rapid 桶使用临时密钥，URL 必须包含 signature 和 security-token
            for (URL url : new URL[]{putUrl, getUrl, delUrl}) {
                String s = url.toString();
                assertTrue("should contain q-signature", s.contains("q-signature="));
                assertTrue("should contain security-token",
                        s.contains("&" + Headers.SECURITY_TOKEN));
            }

            putFileWithUrl(putUrl, localFile);
            headSimpleObject(key, localFile.length(), Md5Utils.md5Hex(localFile));
            getFileWithUrl(getUrl, downloadFile);
            assertEquals(localFile.length(), downloadFile.length());
            assertEquals(Md5Utils.md5Hex(localFile), Md5Utils.md5Hex(downloadFile));
            deleteFileWithUrl(delUrl);
            assertFalse(cosclient.doesObjectExist(bucket, key));
        } finally {
            clearObject(key);
            if (localFile.exists()) assertTrue(localFile.delete());
            if (downloadFile.exists()) assertTrue(downloadFile.delete());
        }
    }

    /**
     * 参考 GeneratePresignedUrlTest#testTemporyTokenUrl，但 Rapid 桶本身就是临时密钥。
     * signPrefixMode=true（默认），Rapid桶不支持这种模式，验证内部会强制设置为 false。
     */
    @Test
    public void testSignPrefixMode() throws IOException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) return;

        String key = "ut/rapid_presigned_prefix_test.txt";
        File localFile = buildTestFile(1024L);
        File downloadFile = new File(localFile.getAbsolutePath() + ".down");
        try {
            Date exp = expiration();
            // 默认 signPrefixMode=true, 但是内部检测到是 Rapid 桶，会自动设置为 false
            GeneratePresignedUrlRequest putReq =
                    new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.PUT);
            putReq.setExpiration(exp);
            URL putUrl = cosclient.generatePresignedUrl(putReq);

            GeneratePresignedUrlRequest getReq =
                    new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
            getReq.setExpiration(exp);
            URL getUrl = cosclient.generatePresignedUrl(getReq);

            GeneratePresignedUrlRequest delReq =
                    new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.DELETE);
            delReq.setExpiration(exp);
            URL delUrl = cosclient.generatePresignedUrl(delReq);

            putFileWithUrl(putUrl, localFile);
            getFileWithUrl(getUrl, downloadFile);
            assertEquals(localFile.length(), downloadFile.length());
            assertEquals(Md5Utils.md5Hex(localFile), Md5Utils.md5Hex(downloadFile));
            deleteFileWithUrl(delUrl);
            assertFalse(cosclient.doesObjectExist(bucket, key));
        } finally {
            clearObject(key);
            if (localFile.exists()) assertTrue(localFile.delete());
            if (downloadFile.exists()) assertTrue(downloadFile.delete());
        }
    }

    @Test
    public void testDistinguishedHostKeepsEndpointAddress() throws Exception {
        skipIfNotRapid();
        org.junit.Assume.assumeTrue("Requires a local gateway",
                System.getenv("localRapid") != null && !System.getenv("localRapid").isEmpty());
        String endpoint = System.getenv("gatewayAddr");
        org.junit.Assume.assumeTrue("Requires gatewayAddr", endpoint != null && !endpoint.isEmpty());
        String bucketHost = clientConfig.getEndpointBuilder().buildGeneralApiEndpoint(bucket);
        com.qcloud.cos.ClientConfig config = new com.qcloud.cos.ClientConfig(
                new com.qcloud.cos.region.Region(region));
        config.setHttpProtocol(com.qcloud.cos.http.HttpProtocol.http);
        config.setEndpointBuilder(new com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder(endpoint, endpoint));
        config.setIsDistinguishHost(true);
        config.setConnectionTimeout(5000);
        config.setSocketTimeout(5000);
        config.setMaxErrorRetry(0);
        com.qcloud.cos.COSClient client = new com.qcloud.cos.COSClient(
                new com.qcloud.cos.auth.RapidCOSCredentialProvider(
                        new com.qcloud.cos.auth.BasicCOSCredentials(secretId, secretKey)), config);
        byte[] data = "presigned endpoint and host separation".getBytes("UTF-8");
        String key = "ut/presigned-host-" + java.util.UUID.randomUUID();
        try {
            for (boolean signHost : new boolean[] {true, false}) {
                for (boolean convenienceOverload : new boolean[] {false, true}) {
                    for (HttpMethodName method : new HttpMethodName[] {
                            HttpMethodName.PUT, HttpMethodName.HEAD, HttpMethodName.GET, HttpMethodName.DELETE}) {
                        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, key, method);
                        req.setExpiration(expiration());
                        URL url = convenienceOverload
                                ? client.generatePresignedUrl(bucket, key, expiration(), method,
                                        new HashMap<String, String>(), new HashMap<String, String>(), false, signHost)
                                : client.generatePresignedUrl(req, signHost);
                        assertEquals(endpoint, url.getAuthority());
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestMethod(method.toString());
                        conn.setRequestProperty(Headers.HOST, bucketHost);
                        try {
                            if (method == HttpMethodName.PUT) {
                                conn.setDoOutput(true);
                                conn.setFixedLengthStreamingMode(data.length);
                                try (OutputStream out = conn.getOutputStream()) {
                                    out.write(data);
                                }
                            }
                            assertEquals("method=" + method + ", signHost=" + signHost
                                            + ", convenienceOverload=" + convenienceOverload,
                                    method == HttpMethodName.DELETE ? 204 : 200, conn.getResponseCode());
                            if (method == HttpMethodName.HEAD) {
                                assertEquals(data.length, conn.getContentLength());
                            }
                            if (method == HttpMethodName.GET) {
                                try (InputStream in = conn.getInputStream();
                                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[1024];
                                    int n;
                                    while ((n = in.read(buffer)) != -1) {
                                        out.write(buffer, 0, n);
                                    }
                                    assertArrayEquals(data, out.toByteArray());
                                }
                            }
                        } finally {
                            conn.disconnect();
                        }
                    }
                }
            }
        } finally {
            try {
                com.qcloud.cos.model.DeleteObjectRequest cleanup =
                        new com.qcloud.cos.model.DeleteObjectRequest(bucket, key);
                client.deleteObject(cleanup);
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    public void testFirstPresignInheritsEndpointAndReusesSession() throws Exception {
        skipIfNotRapid();
        String endpoint = System.getenv("gatewayAddr");
        org.junit.Assume.assumeTrue("Requires a local gateway",
                localProxyAddr != null && endpoint != null && !endpoint.isEmpty());
        ClientConfig config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.http);
        config.setIsDistinguishHost(true);
        config.setConnectionTimeout(1000);
        config.setSocketTimeout(5000);
        config.setMaxErrorRetry(0);
        config.setEndpointResolver(new EndpointResolver() {
            @Override
            public String resolveGeneralApiEndpoint(String host) {
                return "127.0.0.1:1";
            }

            @Override
            public String resolveGetServiceApiEndpoint(String host) {
                return "127.0.0.1:1";
            }
        });
        AtomicInteger sessions = new AtomicInteger();
        COSClient client = new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials(secretId, secretKey)), config) {
            @Override
            public CreateSessionResult createSession(CreateSessionRequest request) {
                assertEquals(endpoint, request.getFixedEndpointAddr());
                sessions.incrementAndGet();
                return super.createSession(request);
            }
        };
        String key = "ut/presigned-first-" + UUID.randomUUID();
        File file = buildTestFile(1024);
        File download = new File(file.getAbsolutePath() + ".down");
        try {
            assertEquals(0, sessions.get());
            for (HttpMethodName method : new HttpMethodName[] {HttpMethodName.PUT, HttpMethodName.GET}) {
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, method);
                request.setFixedEndpointAddr(endpoint);
                request.setExpiration(expiration());
                URL url = client.generatePresignedUrl(request);
                assertEquals(config.getEndpointBuilder().buildGeneralApiEndpoint(bucket), url.getAuthority());
                assertEquals(1, sessions.get());
                if (method == HttpMethodName.PUT) {
                    putFileWithUrl(url, file);
                } else {
                    getFileWithUrl(url, download);
                    assertEquals(Md5Utils.md5Hex(file), Md5Utils.md5Hex(download));
                }
            }
        } finally {
            try {
                clearObject(key);
            } finally {
                client.shutdown();
                if (file.exists()) assertTrue(file.delete());
                if (download.exists()) assertTrue(download.delete());
            }
        }
    }
}