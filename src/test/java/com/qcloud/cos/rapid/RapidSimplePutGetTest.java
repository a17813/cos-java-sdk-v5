package com.qcloud.cos.rapid;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CreateSessionRequest;
import com.qcloud.cos.model.CreateSessionResult;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.Md5Utils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RapidSimplePutGetTest extends AbstractRapidCOSClientTest {

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        AbstractRapidCOSClientTest.initRapidCosClient();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        AbstractRapidCOSClientTest.destroyRapidCosClient();
    }

    @Test
    public void testSimplePutAndGet() throws IOException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }
        File localFile = buildTestFile(1024 * 1024L);
        String key = "ut/rapid_simple_put_get_" + localFile.getName();
        File downFile = new File(localFile.getAbsolutePath() + ".down");
        try {
            // put
            PutObjectResult putResult = cosclient.putObject(new PutObjectRequest(bucket, key, localFile));
            String expectedMd5 = Md5Utils.md5Hex(localFile);
            assertEquals(expectedMd5, putResult.getETag());
            assertNotNull(putResult.getRequestId());
            assertNotNull(putResult.getDateStr());

            // get
            ObjectMetadata meta = cosclient.getObject(new GetObjectRequest(bucket, key), downFile);
            assertEquals(1024 * 1024L, downFile.length());
            assertEquals(expectedMd5, Md5Utils.md5Hex(downFile));
        } finally {
            cosclient.deleteObject(bucket, key);
            if (localFile.exists()) assertTrue(localFile.delete());
            if (downFile.exists()) assertTrue(downFile.delete());
        }
    }

    @Test
    public void testFirstRequestInheritsEndpointAndReusesSession() throws Exception {
        skipIfNotRapid();
        String endpoint = System.getenv("gatewayAddr");
        org.junit.Assume.assumeTrue("Requires a local gateway",
                localProxyAddr != null && endpoint != null && !endpoint.isEmpty());
        ClientConfig config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.http);
        config.setIsDistinguishHost(true);
        config.setEndpointBuilder(new UserSpecifiedEndpointBuilder("127.0.0.1:1", "127.0.0.1:1"));
        config.setConnectionTimeout(1000);
        config.setSocketTimeout(5000);
        config.setMaxErrorRetry(0);
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
        File localFile = buildTestFile(1024);
        File download = new File(localFile.getAbsolutePath() + ".down");
        String key = "ut/first-session-" + UUID.randomUUID();
        boolean uploaded = false;
        try {
            assertEquals(0, sessions.get());
            PutObjectRequest put = new PutObjectRequest(bucket, key, localFile);
            put.setFixedEndpointAddr(endpoint);
            client.putObject(put);
            uploaded = true;
            assertEquals(1, sessions.get());
            GetObjectRequest get = new GetObjectRequest(bucket, key);
            get.setFixedEndpointAddr(endpoint);
            client.getObject(get, download);
            assertEquals(Md5Utils.md5Hex(localFile), Md5Utils.md5Hex(download));
            assertEquals("The object request must reuse the real session", 1, sessions.get());
        } finally {
            try {
                if (uploaded) {
                    DeleteObjectRequest delete = new DeleteObjectRequest(bucket, key);
                    delete.setFixedEndpointAddr(endpoint);
                    client.deleteObject(delete);
                }
            } finally {
                client.shutdown();
                if (localFile.exists()) assertTrue(localFile.delete());
                if (download.exists()) assertTrue(download.delete());
            }
        }
    }
}
