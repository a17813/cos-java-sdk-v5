package com.qcloud.cos.rapid;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.Headers;
import com.qcloud.cos.auth.*;
import com.qcloud.cos.endpoint.EndpointBuilder;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.DefaultCosHttpClient;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CreateSessionRequest;
import com.qcloud.cos.model.CreateSessionResult;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.retry.PredefinedRetryPolicies;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

public class RapidCredentialProviderTest {

    // --- RapidCOSCredentialProvider 构造 ---

    @Test(expected = CosClientException.class)
    public void testConstructWithNullProvider() {
        COSCredentialsProvider nullProvider = null;
        new RapidCOSCredentialProvider(nullProvider);
    }

    @Test(expected = CosClientException.class)
    public void testConstructWithNestedRapidProvider() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider inner = new RapidCOSCredentialProvider(cred);
        new RapidCOSCredentialProvider(inner);
    }

    @Test
    public void testConstructWithStaticCredentials() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(cred);
        assertNotNull(provider.getBaseCredProvider());
    }

    // --- getCredentials 路由 ---

    @Test
    public void testGetCredentialsNonRapidReturnsBase() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(cred);
        // useRapidSession=false 应返回基础凭证
        COSCredentials result = provider.getCredentials(null, false, "bucket-125");
        assertEquals("testAK", result.getCOSAccessKeyId());
        assertEquals("testSK", result.getCOSSecretKey());
    }

    @Test
    public void testGetCredentialsBaseMethod() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(cred);
        COSCredentials result = provider.getCredentials();
        assertEquals("testAK", result.getCOSAccessKeyId());
    }

    @Test(expected = CosClientException.class)
    public void testGetCredentialsRapidWithNullBucket() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(cred);
        provider.getCredentials(null, true, null);
    }

    // --- RapidSessionCredentials ---

    @Test
    public void testIsAboutToExpireTrue() {
        long nowSec = System.currentTimeMillis() / 1000L;
        RapidSessionCredentials cred = new RapidSessionCredentials(
                "ak", "sk", "token", nowSec + 30, "baseAK");
        assertTrue(cred.isAboutToExpire(60));
    }

    @Test
    public void testIsAboutToExpireFalse() {
        long nowSec = System.currentTimeMillis() / 1000L;
        RapidSessionCredentials cred = new RapidSessionCredentials(
                "ak", "sk", "token", nowSec + 300, "baseAK");
        assertFalse(cred.isAboutToExpire(60));
    }

    @Test
    public void testIsBaseAKIDChanged() {
        RapidSessionCredentials cred = new RapidSessionCredentials(
                "ak", "sk", "token", 999999999L, "originalAK");
        assertFalse(cred.isBaseAKIDChanged("originalAK"));
        assertTrue(cred.isBaseAKIDChanged("differentAK"));
    }

    // --- COSClient 构造不自动包裹 + resolveCredentials 校验 ---

    @Test
    public void testCOSClientWithNonRapidProviderOnRapidBucket() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setConnectionTimeout(1000);
        config.setSocketTimeout(1000);
        config.setConnectionRequestTimeout(1000);
        COSClient client = new COSClient(cred, config);
        try {
            // 对 rapid bucket 发起请求应抛异常（credProvider 不是 RapidCOSCredentialProvider）
            client.doesObjectExist("mybucket-x--1250000000", "test.txt");
            fail("Expected CosClientException for non-RapidCOSCredentialProvider on rapid bucket");
        } catch (CosClientException e) {
            assertTrue(e.getMessage().contains("RapidCOSCredentialProvider"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testCOSClientWithRapidProviderOnNormalBucket() {
        COSCredentials cred = new BasicCOSCredentials("testAK", "testSK");
        RapidCOSCredentialProvider rapidProvider = new RapidCOSCredentialProvider(cred);
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setConnectionTimeout(1000);
        config.setSocketTimeout(1000);
        config.setConnectionRequestTimeout(1000);
        COSClient client = new COSClient(rapidProvider, config);
        // 对常规桶不应抛异常（回退到基础凭证），但会因为网络不通而失败
        // 这里只验证不会抛 RapidCOSCredentialProvider 相关异常
        try {
            client.doesObjectExist("mybucket-1250000000", "test.txt");
        } catch (CosClientException e) {
            assertFalse("Should not be a RapidCOSCredentialProvider error",
                    e.getMessage().contains("RapidCOSCredentialProvider"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testRefreshFailureFallsBackToUnexpiredSession() throws Exception {
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("newBaseAK", "testSK")));
        RapidSessionCredentials cached = cacheSession(manager, 60);
        COSClient client = mock(COSClient.class);
        when(client.createSession(any(CreateSessionRequest.class)))
                .thenThrow(new CosServiceException("CreateSession unavailable"));

        assertSame(cached, manager.getActiveSession(client, "bucket"));
        verify(client).createSession(any(CreateSessionRequest.class));
    }

    @Test
    public void testRefreshFallbackLogsWarningWithoutCredentials() throws Exception {
        org.slf4j.Logger logger = mock(org.slf4j.Logger.class);
        Field loggerField = RapidSessionManager.class.getDeclaredField("log");
        loggerField.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        int originalModifiers = loggerField.getModifiers();
        modifiersField.setInt(loggerField, originalModifiers & ~java.lang.reflect.Modifier.FINAL);
        Object originalLogger = loggerField.get(null);
        try {
            loggerField.set(null, logger);
            RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK")));
            RapidSessionCredentials cached = cacheSession(manager, 60);
            COSClient client = mock(COSClient.class);
            CosServiceException failure = new CosServiceException("secret-must-not-be-logged");
            failure.setErrorCode("AccessDenied");
            failure.setRequestId("refresh-request");
            when(client.createSession(any(CreateSessionRequest.class))).thenThrow(failure);

            assertSame(cached, manager.getActiveSession(client, "bucket"));
            verify(logger).warn(anyString(), eq("bucket"), anyLong(),
                    eq("CosServiceException"), eq("AccessDenied"), eq("refresh-request"));
            verifyNoMoreInteractions(logger);

            reset(logger);
            doThrow(new CosClientException("provider-secret-must-not-be-logged"))
                    .when(client).createSession(any(CreateSessionRequest.class));
            assertSame(cached, manager.getActiveSession(client, "bucket"));
            verify(logger).warn(anyString(), eq("bucket"), anyLong(),
                    eq("CosClientException"), isNull(), isNull());
            verifyNoMoreInteractions(logger);

            reset(logger);
            cacheSession(manager, 0);
            try {
                manager.getActiveSession(client, "bucket");
                fail("Expected refresh failure for expired session");
            } catch (CosClientException expected) {
                verifyZeroInteractions(logger);
            }
        } finally {
            loggerField.set(null, originalLogger);
            modifiersField.setInt(loggerField, originalModifiers);
        }
    }

    @Test
    public void testRefreshFailureWithoutUsableSessionRethrowsOriginalError() throws Exception {
        for (int remaining : new int[] {-1, 0}) {
            RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK")));
            if (remaining == 0) {
                cacheSession(manager, 0);
            }
            COSClient client = mock(COSClient.class);
            CosClientException failure = new CosClientException("CreateSession failed");
            when(client.createSession(any(CreateSessionRequest.class))).thenThrow(failure);
            try {
                manager.getActiveSession(client, "bucket");
                fail("Expected original refresh error");
            } catch (CosClientException e) {
                assertSame(failure, e);
            }
        }
    }

    @Test
    public void testBaseProviderFailureFallsBackToUnexpiredSession() throws Exception {
        COSCredentialsProvider baseProvider = mock(COSCredentialsProvider.class);
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(baseProvider));
        RapidSessionCredentials cached = cacheSession(manager, 60);
        when(baseProvider.getCredentials()).thenThrow(new CosClientException("Base provider unavailable"));
        assertSame(cached, manager.getActiveSession(null, "bucket"));
    }

    @Test
    public void testRapidObjectUrlDoesNotReadBaseProvider() {
        COSCredentialsProvider baseProvider = mock(COSCredentialsProvider.class);
        when(baseProvider.getCredentials()).thenThrow(new CosClientException("Base provider unavailable"));
        COSClient client = new COSClient(new RapidCOSCredentialProvider(baseProvider),
                new ClientConfig(new Region("ap-nanjing")));
        try {
            java.net.URL url = client.getObjectUrl("bucket-x--1250000000", "test.txt", "v1");
            assertEquals("bucket-x--1250000000.cosrapid.ap-nanjing.myqcloud.com", url.getHost());
            assertEquals("/test.txt", url.getPath());
            assertEquals("versionId=v1", url.getQuery());
            verify(baseProvider, never()).getCredentials();
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testNormalObjectUrlStillCompletesAppId() {
        COSCredentialsProvider baseProvider = mock(COSCredentialsProvider.class);
        when(baseProvider.getCredentials()).thenReturn(new BasicCOSCredentials("1250000000", "testAK", "testSK"));
        COSClient client = new COSClient(baseProvider, new ClientConfig(new Region("ap-nanjing")));
        try {
            for (String bucket : new String[] {"bucket", "bucket-1250000000"}) {
                assertEquals("bucket-1250000000.cos.ap-nanjing.myqcloud.com",
                        client.getObjectUrl(bucket, "test.txt").getHost());
            }
            verify(baseProvider, atLeastOnce()).getCredentials();
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testDeleteObjectSurvivesBaseProviderFailure() throws Exception {
        for (int remaining : new int[] {300, 60}) {
            checkObjectBaseProviderFailure("delete", remaining, false);
            checkObjectBaseProviderFailure("delete", remaining, true);
        }
    }

    @Test
    public void testCopyObjectSurvivesBaseProviderFailure() throws Exception {
        for (int remaining : new int[] {300, 60}) {
            checkObjectBaseProviderFailure("copy", remaining, false);
        }
    }

    @Test
    public void testCopyPartSurvivesBaseProviderFailure() throws Exception {
        for (int remaining : new int[] {300, 60}) {
            checkObjectBaseProviderFailure("copyPart", remaining, false);
        }
    }

    @Test
    public void testObjectWithoutUsableSessionStillRequiresBaseProvider() throws Exception {
        for (String operation : new String[] {"delete", "copy", "copyPart"}) {
            for (int remaining : new int[] {-1, 0}) {
                checkObjectBaseProviderFailure(operation, remaining, false);
            }
        }
    }

    @Test
    public void testBucketOperationsStillRequireBaseProviderWithCachedSession() throws Exception {
        checkObjectBaseProviderFailure("createBucket", 300, false);
        checkObjectBaseProviderFailure("deleteBucket", 300, false);
        checkObjectBaseProviderFailure("createSession", 300, false);
    }

    private void checkObjectBaseProviderFailure(String operation, int remaining, boolean retry)
            throws Exception {
        String bucket = "bucket-x--1250000000";
        String sourceBucket = "source-x--1250000001";
        String host = bucket + ".cosrapid.ap-nanjing.myqcloud.com";
        COSCredentialsProvider baseProvider = mock(COSCredentialsProvider.class);
        CosClientException failure = new CosClientException("Base provider unavailable");
        when(baseProvider.getCredentials()).thenThrow(failure);
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(baseProvider);
        Field managerField = RapidCOSCredentialProvider.class.getDeclaredField("rapidSessionManager");
        managerField.setAccessible(true);
        RapidSessionManager manager = (RapidSessionManager) managerField.get(provider);
        if (remaining >= 0) {
            cacheSession(manager, bucket, remaining);
        }
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setMaxErrorRetry(retry ? 1 : 0);
        config.setBackoffStrategy(retryIndex -> 0);
        config.turnOnRefreshEndpointAddrSwitch();
        COSClient client = new COSClient(provider, config);
        boolean baseSigned = operation.equals("createBucket") || operation.equals("deleteBucket")
                || operation.equals("createSession");
        boolean succeeds = remaining > 0 && !baseSigned;
        AtomicInteger calls = new AtomicInteger();
        try {
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            HttpClient network = mock(HttpClient.class);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        assertEquals(host, request.getURI().getHost());
                        assertEquals(host, request.getFirstHeader(Headers.HOST).getValue());
                        assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue()
                                .contains("q-ak=sessionAK&"));
                        assertEquals("token", request.getFirstHeader(Headers.SECURITY_TOKEN).getValue());
                        boolean isCopy = operation.startsWith("copy");
                        assertEquals(isCopy ? "PUT" : "DELETE", request.getMethod());
                        if (isCopy) {
                            assertEquals(sourceBucket + ".cosrapid.ap-nanjing.myqcloud.com/source.txt",
                                    request.getFirstHeader("x-cos-copy-source").getValue());
                        }
                        if (retry && calls.getAndIncrement() == 0) {
                            BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1,
                                    503, "Service Unavailable");
                            response.setEntity(new StringEntity("<Error><Code>ServiceUnavailable</Code>"
                                    + "<Message>retry</Message></Error>", "UTF-8"));
                            return response;
                        }
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1,
                                isCopy ? 200 : 204, "OK");
                        if (isCopy) {
                            String root = operation.equals("copy") ? "CopyObjectResult" : "CopyPartResult";
                            response.setEntity(new StringEntity("<" + root + "><ETag>etag</ETag>"
                                    + "<LastModified>2026-01-01T00:00:00.000Z</LastModified></" + root + ">",
                                    "UTF-8"));
                        }
                        return response;
                    });
            try {
                if (operation.equals("copy")) {
                    assertEquals("etag", client.copyObject(new com.qcloud.cos.model.CopyObjectRequest(
                            sourceBucket, "source.txt", bucket, "test.txt")).getETag());
                } else if (operation.equals("copyPart")) {
                    com.qcloud.cos.model.CopyPartRequest request = new com.qcloud.cos.model.CopyPartRequest();
                    request.setSourceBucketName(sourceBucket);
                    request.setSourceKey("source.txt");
                    request.setDestinationBucketName(bucket);
                    request.setDestinationKey("test.txt");
                    request.setUploadId("upload-id");
                    request.setPartNumber(1);
                    assertEquals("etag", client.copyPart(request).getETag());
                } else if (operation.equals("createBucket")) {
                    client.createBucket(bucket);
                } else if (operation.equals("deleteBucket")) {
                    client.deleteBucket(bucket);
                } else if (operation.equals("createSession")) {
                    client.createSession(new CreateSessionRequest(bucket));
                } else {
                    client.deleteObject(bucket, "test.txt");
                }
                assertTrue("An unexpired session is required", succeeds);
            } catch (CosClientException e) {
                assertFalse("An unexpired session must remain usable", succeeds);
                assertSame(failure, e);
            }
            verify(network, times(succeeds ? (retry ? 2 : 1) : 0))
                    .execute(any(HttpUriRequest.class), any(HttpContext.class));
            if (remaining > RapidSessionManager.REFRESH_THRESHOLD_SEC && !baseSigned) {
                verify(baseProvider, never()).getCredentials();
            } else {
                verify(baseProvider, atLeastOnce()).getCredentials();
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testPresignFallbackStillChecksRequiredLifetime() throws Exception {
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")));
        RapidSessionCredentials cached = cacheSession(manager, 60);
        COSClient client = mock(COSClient.class);
        when(client.createSession(any(CreateSessionRequest.class)))
                .thenThrow(new CosClientException("CreateSession failed"));

        assertSame(cached, manager.getActiveSessionForPresign(client, "bucket", 30));
        verify(client, never()).createSession(any(CreateSessionRequest.class));
        try {
            manager.getActiveSessionForPresign(client, "bucket", 120);
            fail("Expected insufficient session lifetime error");
        } catch (CosClientException e) {
            assertTrue(e.getMessage().contains("less than the required pre-signed URL duration"));
        }
        verify(client).createSession(any(CreateSessionRequest.class));
    }

    @Test
    public void testPresignReusesSessionBeyond300Seconds() throws Exception {
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")));
        RapidSessionCredentials cached = cacheSession(manager, 600);
        COSClient client = mock(COSClient.class);

        assertSame(cached, manager.getActiveSessionForPresign(client, "bucket", 400));
        verify(client, never()).createSession(any(CreateSessionRequest.class));
    }

    @Test
    public void testPresignRefreshUsesActualSessionLifetime() throws Exception {
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")));
        RapidSessionCredentials cached = cacheSession(manager, 180);
        COSClient client = mock(COSClient.class);
        CreateSessionResult result = new CreateSessionResult();
        CreateSessionResult.Credentials credentials = new CreateSessionResult.Credentials();
        credentials.setTmpSecretId("newSessionAK");
        credentials.setTmpSecretKey("newSessionSK");
        credentials.setSessionToken("newSessionToken");
        result.setCredentials(credentials);
        result.setExpiration(Instant.now().plusSeconds(600).toString());
        when(client.createSession(any(CreateSessionRequest.class))).thenReturn(result);

        RapidSessionCredentials refreshed = manager.getActiveSessionForPresign(client, "bucket", 400);
        assertNotSame(cached, refreshed);
        assertEquals("newSessionAK", refreshed.getCOSAccessKeyId());
        assertSame(refreshed, manager.getActiveSessionForPresign(client, "bucket", 400));
        verify(client).createSession(any(CreateSessionRequest.class));
    }

    @Test
    public void testPresignRejectsInsufficientRefreshedLifetime() throws Exception {
        for (long required : new long[] {400, (long) Integer.MAX_VALUE + 1}) {
            RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK")));
            cacheSession(manager, 180);
            COSClient client = mock(COSClient.class);
            CreateSessionResult result = new CreateSessionResult();
            CreateSessionResult.Credentials credentials = new CreateSessionResult.Credentials();
            credentials.setTmpSecretId("newSessionAK");
            credentials.setTmpSecretKey("newSessionSK");
            credentials.setSessionToken("newSessionToken");
            result.setCredentials(credentials);
            result.setExpiration(Instant.now().plusSeconds(300).toString());
            when(client.createSession(any(CreateSessionRequest.class))).thenReturn(result);

            try {
                manager.getActiveSessionForPresign(client, "bucket", required);
                fail("Expected insufficient session lifetime error");
            } catch (CosClientException e) {
                assertTrue(e.getMessage().contains("less than the required pre-signed URL duration"));
            }
            verify(client).createSession(any(CreateSessionRequest.class));
        }
    }

    @Test
    public void testOrdinaryRequestKeeps90SecondRefreshWindow() throws Exception {
        assertEquals(90, RapidSessionManager.REFRESH_THRESHOLD_SEC);
        for (int remaining : new int[] {60, 120}) {
            RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK")));
            RapidSessionCredentials cached = cacheSession(manager, remaining);
            COSClient client = mock(COSClient.class);
            when(client.createSession(any(CreateSessionRequest.class)))
                    .thenThrow(new CosClientException("CreateSession failed"));

            assertSame(cached, manager.getActiveSession(client, "bucket"));
            verify(client, times(remaining < 90 ? 1 : 0)).createSession(any(CreateSessionRequest.class));
        }
    }

    @Test
    public void testSuccessfulRefreshAfterFallbackUpdatesCache() throws Exception {
        RapidSessionManager manager = new RapidSessionManager(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")));
        RapidSessionCredentials cached = cacheSession(manager, 60);
        COSClient client = mock(COSClient.class);
        CreateSessionResult result = new CreateSessionResult();
        CreateSessionResult.Credentials credentials = new CreateSessionResult.Credentials();
        credentials.setTmpSecretId("newSessionAK");
        credentials.setTmpSecretKey("newSessionSK");
        credentials.setSessionToken("newSessionToken");
        result.setCredentials(credentials);
        result.setExpiration(Instant.now().plusSeconds(300).toString());
        when(client.createSession(any(CreateSessionRequest.class)))
                .thenThrow(new CosClientException("CreateSession failed")).thenReturn(result);

        assertSame(cached, manager.getActiveSession(client, "bucket"));
        RapidSessionCredentials refreshed = manager.getActiveSession(client, "bucket");
        assertNotSame(cached, refreshed);
        assertEquals("newSessionAK", refreshed.getCOSAccessKeyId());
        assertSame(refreshed, manager.getActiveSession(client, "bucket"));
        verify(client, times(2)).createSession(any(CreateSessionRequest.class));
    }

    @Test
    public void testExpiredTokenRetriesWithCachedSession() throws Exception {
        checkSessionRetry(300, 2, "ExpiredToken", 1, false, false, false, 2, 1, 0);
    }

    @Test
    public void testExpiredTokenRefreshesAndResignsWhenWithinRefreshWindow() throws Exception {
        checkSessionRetry(60, 2, "ExpiredToken", 1, false, false, false, 2, 2, 0);
    }

    @Test
    public void testExpiredTokenHonorsRetryLimitAndPreservesError() throws Exception {
        checkSessionRetry(300, 2, "ExpiredToken", 10, false, false, false, 3, 1, 503);
    }

    @Test
    public void testExpiredTokenHonorsZeroRetries() throws Exception {
        checkSessionRetry(300, 0, "ExpiredToken", 1, false, false, false, 1, 1, 503);
    }

    @Test
    public void testExpiredTokenHonorsNoRetryPolicy() throws Exception {
        checkSessionRetry(300, 2, "ExpiredToken", 1, true, false, false, 1, 1, 503);
    }

    @Test
    public void testOtherSessionErrorsAreNotRewritten() throws Exception {
        checkSessionRetry(300, 2, "AccessDenied", 1, false, false, false, 1, 1, 400);
    }

    @Test
    public void testNormalBucketExpiredTokenIsNotRewritten() throws Exception {
        checkSessionRetry(300, 2, "ExpiredToken", 1, false, true, false, 1, 0, 400);
    }

    @Test
    public void testCreateSessionExpiredTokenIsNotRewritten() throws Exception {
        checkSessionRetry(300, 2, "ExpiredToken", 1, false, false, true, 1, 0, 400);
    }

    @Test
    public void testInternalCreateSession503HonorsClientRetries() throws Exception {
        for (int remaining : new int[] {-1, 0, 60}) {
            checkCreateSessionFailure(false, remaining);
        }
    }

    @Test
    public void testInternalCreateSessionNetworkFailureHonorsClientRetries() throws Exception {
        for (int remaining : new int[] {-1, 0, 60}) {
            checkCreateSessionFailure(true, remaining);
        }
    }

    private void checkCreateSessionFailure(boolean networkFailure, int remaining) throws Exception {
        String bucket = "bucket-x--1250000000";
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK"));
        RapidSessionManager manager = new RapidSessionManager(provider);
        RapidSessionCredentials cached = remaining < 0 ? null : cacheSession(manager, bucket, remaining);
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setMaxErrorRetry(2);
        config.setBackoffStrategy(retryIndex -> 0);
        config.setErrorLogStatusCodeThresh(600);
        COSClient client = new COSClient(provider, config);
        IOException failure = new IOException("connection reset");
        try {
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            HttpClient network = mock(HttpClient.class);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        assertTrue(request.getURI().getQuery().contains("session"));
                        assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue()
                                .contains("q-ak=testAK&"));
                        assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                        if (networkFailure) {
                            throw failure;
                        }
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1,
                                503, "Service Unavailable");
                        response.setEntity(new StringEntity("<Error><Code>ServiceUnavailable</Code>"
                                + "<Message>unavailable</Message><RequestId>test-request</RequestId></Error>",
                                "UTF-8"));
                        return response;
                    });
            try {
                assertSame(cached, manager.getActiveSession(client, bucket));
                assertTrue("Only an unexpired cache may be reused", remaining > 0);
            } catch (CosClientException e) {
                assertTrue("Unexpired cache must be reused", remaining <= 0);
                if (networkFailure) {
                    assertSame(failure, e.getCause());
                } else {
                    assertTrue(e instanceof CosServiceException);
                    assertEquals(503, ((CosServiceException) e).getStatusCode());
                    assertEquals("test-request", ((CosServiceException) e).getRequestId());
                }
            }
            verify(network, times(3)).execute(any(HttpUriRequest.class), any(HttpContext.class));
            assertEquals(2, config.getMaxErrorRetry());

            // 同一 Client 的直接调用仍沿用原重试配置。
            try {
                client.createSession(new CreateSessionRequest(bucket));
                fail("Expected CreateSession failure");
            } catch (CosClientException e) {
                if (networkFailure) {
                    assertSame(failure, e.getCause());
                } else {
                    assertEquals(503, ((CosServiceException) e).getStatusCode());
                }
            }
            verify(network, times(6)).execute(any(HttpUriRequest.class), any(HttpContext.class));
        } finally {
            client.shutdown();
        }
    }

    @Test(timeout = 15000)
    public void testInternalCreateSessionPreservesApacheNetworkRetries() throws Exception {
        checkApacheNetworkRetries(true, 4);
    }

    @Test(timeout = 15000)
    public void testDirectCreateSessionPreservesApacheNetworkRetries() throws Exception {
        checkApacheNetworkRetries(false, 4);
    }

    private void checkApacheNetworkRetries(boolean internal, int expectedAttempts) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> peer = executor.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            }
                            attempts.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        throw new RuntimeException(e);
                    }
                }
            });
            ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
            config.setHttpProtocol(HttpProtocol.http);
            config.setMaxErrorRetry(0);
            config.setConnectionTimeout(2000);
            config.setSocketTimeout(2000);
            config.setEndpointResolver(new EndpointResolver() {
                @Override
                public String resolveGeneralApiEndpoint(String endpoint) {
                    return "127.0.0.1:" + server.getLocalPort();
                }

                @Override
                public String resolveGetServiceApiEndpoint(String endpoint) {
                    return resolveGeneralApiEndpoint(endpoint);
                }
            });
            RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK"));
            COSClient client = new COSClient(provider, config);
            try {
                if (internal) {
                    provider.getCredentials(client, true, "bucket-x--1250000000");
                } else {
                    client.createSession(new CreateSessionRequest("bucket-x--1250000000"));
                }
                fail("Expected connection failure");
            } catch (CosClientException e) {
                assertTrue(e.getCause() instanceof IOException);
                assertEquals(expectedAttempts, attempts.get());
            } finally {
                client.shutdown();
                server.close();
                peer.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void checkSessionRetry(int initialLifetime, int maxRetries, String errorCode,
            int failures, boolean noRetry, boolean normalBucket, boolean createSessionRequest,
            int expectedAttempts, int expectedSessions, int expectedStatus) throws Exception {
        String bucket = normalBucket ? "bucket-1250000000" : "bucket-x--1250000000";
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setMaxErrorRetry(maxRetries);
        config.setBackoffStrategy(retryIndex -> 0);
        config.setErrorLogStatusCodeThresh(600);
        if (noRetry) {
            config.setRetryPolicy(PredefinedRetryPolicies.NO_RETRY_POLICY);
        }
        COSClient client = spy(new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")), config));
        List<String> sessionIds = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        List<HttpResponse> responses = new ArrayList<>();
        String errorXml = "<Error><Code>" + errorCode + "</Code><Message>rejected</Message>"
                + "<RequestId>test-request</RequestId></Error>";
        try {
            if (!createSessionRequest) {
                doAnswer(invocation -> {
                    String id = "session" + sessionIds.size();
                    sessionIds.add(id);
                    CreateSessionResult result = new CreateSessionResult();
                    CreateSessionResult.Credentials credentials = new CreateSessionResult.Credentials();
                    credentials.setTmpSecretId(id);
                    credentials.setTmpSecretKey("testSessionSK");
                    credentials.setSessionToken(id + "Token");
                    result.setCredentials(credentials);
                    result.setExpiration(Instant.now().plusSeconds(
                            sessionIds.size() == 1 ? initialLifetime : 300).toString());
                    return result;
                }).when(client).createSession(any(CreateSessionRequest.class));
            }
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            HttpClient network = mock(HttpClient.class);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        signatures.add(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue());
                        tokens.add(request.getFirstHeader(Headers.SECURITY_TOKEN) == null ? null
                                : request.getFirstHeader(Headers.SECURITY_TOKEN).getValue());
                        boolean failed = responses.size() < failures;
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1,
                                failed ? 400 : 204, failed ? "Bad Request" : "No Content");
                        if (failed) {
                            response.setEntity(new StringEntity(errorXml, "UTF-8"));
                        }
                        responses.add(response);
                        return response;
                    });
            try {
                if (createSessionRequest) {
                    client.createSession(new CreateSessionRequest(bucket));
                } else {
                    client.deleteObject(bucket, "test.txt");
                }
                assertEquals("Expected a service error", 0, expectedStatus);
            } catch (CosServiceException e) {
                assertEquals(expectedStatus, e.getStatusCode());
                assertEquals(errorCode, e.getErrorCode());
                assertEquals("test-request", e.getRequestId());
                assertEquals(errorXml, e.getErrorResponseXml());
            }
            assertEquals(expectedAttempts, responses.size());
            assertEquals(expectedSessions, sessionIds.size());
            int errorStatus = !normalBucket && !createSessionRequest
                    && "ExpiredToken".equals(errorCode) ? 503 : 400;
            assertEquals(errorStatus, responses.get(0).getStatusLine().getStatusCode());
            if (expectedSessions > 0) {
                assertEquals("session0Token", tokens.get(0));
                assertTrue(signatures.get(0).contains("q-ak=session0&"));
                int lastSession = expectedSessions - 1;
                assertEquals("session" + lastSession + "Token", tokens.get(tokens.size() - 1));
                assertTrue(signatures.get(signatures.size() - 1)
                        .contains("q-ak=session" + lastSession + "&"));
                for (String signature : signatures) {
                    assertFalse("Old token must not participate in signing",
                            signature.contains(Headers.SECURITY_TOKEN.toLowerCase()));
                }
            } else {
                assertNull(tokens.get(0));
                assertTrue(signatures.get(0).contains("q-ak=testAK&"));
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testSessionInheritsRequestEndpointAndDistinguishesHost() throws Exception {
        checkSessionRequestRouting("127.0.0.1:18080", true);
    }

    @Test
    public void testSessionInheritsRequestEndpointOnly() throws Exception {
        checkSessionRequestRouting("127.0.0.1:18080", false);
    }

    @Test
    public void testSessionUsesDistinguishedHost() throws Exception {
        checkSessionRequestRouting(null, true);
    }

    @Test
    public void testSessionUsesClientRoutingByDefault() throws Exception {
        checkSessionRequestRouting(null, false);
    }

    private void checkSessionRequestRouting(String fixedEndpointAddr, boolean distinguishHost) throws Exception {
        String bucket = "bucket-x--1250000000";
        String defaultEndpoint = "127.0.0.1:8080";
        String defaultHost = bucket + ".cosrapid.ap-nanjing.myqcloud.com";
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setHttpProtocol(HttpProtocol.http);
        config.setMaxErrorRetry(0);
        config.setIsDistinguishHost(distinguishHost);
        EndpointResolver resolver = new EndpointResolver() {
            @Override
            public String resolveGeneralApiEndpoint(String endpoint) {
                return defaultEndpoint;
            }

            @Override
            public String resolveGetServiceApiEndpoint(String endpoint) {
                return defaultEndpoint;
            }
        };
        config.setEndpointResolver(resolver);
        EndpointBuilder defaultBuilder = config.getEndpointBuilder();
        if (distinguishHost) {
            config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(defaultEndpoint, defaultEndpoint));
        }
        COSClient client = new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")), config);
        AtomicInteger sessionCalls = new AtomicInteger();
        AtomicInteger businessCalls = new AtomicInteger();
        try {
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            HttpClient network = mock(HttpClient.class);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        boolean useOverrides = businessCalls.get() < 2;
                        assertEquals(useOverrides && fixedEndpointAddr != null
                                ? fixedEndpointAddr : defaultEndpoint, request.getURI().getAuthority());
                        assertEquals(defaultHost, request.getFirstHeader(Headers.HOST).getValue());
                        assertEquals("http", request.getURI().getScheme());
                        boolean session = request.getURI().getQuery() != null
                                && request.getURI().getQuery().contains("session");
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1,
                                session ? 200 : 204, "OK");
                        String signature = request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue();
                        if (session) {
                            int index = sessionCalls.incrementAndGet();
                            assertTrue(signature.contains("q-ak=testAK&"));
                            assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                            response.setEntity(new StringEntity("<CreateSessionResult><Credentials>"
                                    + "<AccessKeyId>session" + index + "</AccessKeyId>"
                                    + "<SecretAccessKey>testSessionSK</SecretAccessKey>"
                                    + "<SessionToken>token" + index + "</SessionToken>"
                                    + "<Expiration>" + Instant.now().plusSeconds(index < 3 ? 60 : 300)
                                    + "</Expiration></Credentials></CreateSessionResult>", "UTF-8"));
                        } else {
                            businessCalls.incrementAndGet();
                            assertTrue(signature.contains("q-ak=session" + sessionCalls.get() + "&"));
                            assertEquals("token" + sessionCalls.get(),
                                    request.getFirstHeader(Headers.SECURITY_TOKEN).getValue());
                        }
                        return response;
                    });
            // 首次获取和刷新继承请求地址，Host 始终由 Client 配置生成。
            for (int i = 0; i < 3; i++) {
                if (i == 2) {
                    // 恢复默认域名入口，Host 保持不变。
                    config.setEndpointBuilder(defaultBuilder);
                }
                DeleteObjectRequest request = new DeleteObjectRequest(bucket, "test.txt");
                if (i < 2) {
                    request.setFixedEndpointAddr(fixedEndpointAddr);
                }
                client.deleteObject(request);
            }
            assertEquals(3, sessionCalls.get());
            assertEquals(3, businessCalls.get());
            assertSame(resolver, config.getEndpointResolver());
            assertEquals(HttpProtocol.http, config.getHttpProtocol());
            assertEquals(defaultHost, config.getEndpointBuilder().buildGeneralApiEndpoint(bucket));
            verify(network, times(6)).execute(any(HttpUriRequest.class), any(HttpContext.class));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testPresignExpirationUsesSignatureSecondPrecision() throws Exception {
        String bucket = "bucket-x--1250000000";
        long sessionEnd = System.currentTimeMillis() / 1000L + 300;
        RapidCOSCredentialProvider provider = mock(RapidCOSCredentialProvider.class);
        when(provider.getCredentials()).thenReturn(new BasicCOSCredentials("testAK", "testSK"));
        when(provider.getCredentialsForPresign(any(COSClient.class), eq(bucket), anyLong(),
                isNull())).thenReturn(new RapidSessionCredentials(
                        "sessionAK", "sessionSK", "token", sessionEnd, "testAK"));
        COSClient client = new COSClient(provider, new ClientConfig(new Region("ap-nanjing")));
        try {
            for (long offsetMillis : new long[] {-1000, 0, 999, 1000}) {
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, "test.txt");
                request.setExpiration(new java.util.Date(sessionEnd * 1000L + offsetMillis));
                try {
                    java.net.URL url = client.generatePresignedUrl(request);
                    assertTrue("Must reject a signature ending after the session", offsetMillis < 1000);
                    String query = java.net.URLDecoder.decode(url.getQuery(), "UTF-8");
                    long expectedEnd = request.getExpiration().getTime() / 1000L;
                    assertTrue(query.matches(".*q-sign-time=[0-9]+;" + expectedEnd + "&.*"));
                    assertTrue(query.matches(".*q-key-time=[0-9]+;" + expectedEnd + "&.*"));
                    assertTrue(expectedEnd <= sessionEnd);
                } catch (CosClientException e) {
                    assertEquals(1000, offsetMillis);
                    assertTrue(e.getMessage().contains("exceeds the session credential expiration"));
                }
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testPresignDefaultExpirationIsFixedBeforeCredentials() throws Exception {
        String bucket = "bucket-x--1250000000";
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, "test.txt");
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setSignExpired(120);
        RapidCOSCredentialProvider provider = mock(RapidCOSCredentialProvider.class);
        when(provider.getCredentials()).thenReturn(new BasicCOSCredentials("testAK", "testSK"));
        long[] fixedEnd = new long[1];
        when(provider.getCredentialsForPresign(any(COSClient.class), eq(bucket), eq(120L),
                isNull())).thenAnswer(invocation -> {
                    assertNotNull("Expiration must be fixed before CreateSession", request.getExpiration());
                    fixedEnd[0] = request.getExpiration().getTime();
                    return new RapidSessionCredentials("sessionAK", "sessionSK", "token",
                            fixedEnd[0] / 1000L, "testAK");
                });
        COSClient client = new COSClient(provider, config);
        try {
            java.net.URL url = client.generatePresignedUrl(request);
            assertEquals(fixedEnd[0], request.getExpiration().getTime());
            String query = java.net.URLDecoder.decode(url.getQuery(), "UTF-8");
            assertTrue(query.matches(".*q-sign-time=[0-9]+;" + fixedEnd[0] / 1000L + "&.*"));
            verify(provider).getCredentialsForPresign(eq(client), eq(bucket), eq(120L), isNull());
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testPresignRejectsExpirationPassedDuringCredentialLookup() {
        String bucket = "bucket-x--1250000000";
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, "test.txt");
        request.setExpiration(new java.util.Date(System.currentTimeMillis() + 60000));
        RapidCOSCredentialProvider provider = mock(RapidCOSCredentialProvider.class);
        when(provider.getCredentials()).thenReturn(new BasicCOSCredentials("testAK", "testSK"));
        when(provider.getCredentialsForPresign(any(COSClient.class), eq(bucket), anyLong(),
                isNull())).thenAnswer(invocation -> {
                    request.setExpiration(new java.util.Date(System.currentTimeMillis() - 1000));
                    return new RapidSessionCredentials("sessionAK", "sessionSK", "token",
                            System.currentTimeMillis() / 1000L + 300, "testAK");
                });
        COSClient client = new COSClient(provider, new ClientConfig(new Region("ap-nanjing")));
        try {
            client.generatePresignedUrl(request);
            fail("Expected expiration validation after credential lookup");
        } catch (CosClientException e) {
            assertTrue(e.getMessage().contains("expiration time has already passed"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testPresignSessionInheritsEndpointAndDistinguishesHost() throws Exception {
        checkPresignSessionRouting("127.0.0.1:18080", true);
    }

    @Test
    public void testPresignSessionInheritsEndpointOnly() throws Exception {
        checkPresignSessionRouting("127.0.0.1:18080", false);
    }

    @Test
    public void testPresignSessionUsesDistinguishedHost() throws Exception {
        checkPresignSessionRouting(null, true);
    }

    @Test
    public void testPresignSessionUsesClientRoutingByDefault() throws Exception {
        checkPresignSessionRouting(null, false);
    }

    @Test
    public void testNormalPresignKeepsEndpointBuilderHost() {
        String bucket = "bucket-1250000000";
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        String endpoint = "127.0.0.1:8080";
        config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(endpoint, endpoint));
        config.setIsDistinguishHost(true);
        COSClient client = new COSClient(new BasicCOSCredentials("testAK", "testSK"), config);
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, "test.txt");
            request.setFixedEndpointAddr("127.0.0.1:18080");
            assertEquals(config.getEndpointBuilder().buildGeneralApiEndpoint(bucket),
                    client.generatePresignedUrl(request).getAuthority());
        } finally {
            client.shutdown();
        }
    }

    private void checkPresignSessionRouting(String fixedEndpointAddr, boolean distinguishHost) throws Exception {
        String bucket = "bucket-x--1250000000";
        String defaultEndpoint = "127.0.0.1:8080";
        String defaultHost = bucket + ".cosrapid.ap-nanjing.myqcloud.com";
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setHttpProtocol(HttpProtocol.http);
        config.setMaxErrorRetry(0);
        config.setIsDistinguishHost(distinguishHost);
        EndpointResolver resolver = new EndpointResolver() {
            @Override
            public String resolveGeneralApiEndpoint(String endpoint) {
                return defaultEndpoint;
            }

            @Override
            public String resolveGetServiceApiEndpoint(String endpoint) {
                return defaultEndpoint;
            }
        };
        config.setEndpointResolver(resolver);
        EndpointBuilder defaultBuilder = config.getEndpointBuilder();
        if (distinguishHost) {
            config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(defaultEndpoint, defaultEndpoint));
        }
        COSClient client = new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")), config);
        AtomicInteger sessionCalls = new AtomicInteger();
        try {
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            HttpClient network = mock(HttpClient.class);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        int index = sessionCalls.incrementAndGet();
                        assertTrue(request.getURI().getQuery().contains("session"));
                        assertEquals(index < 3 && fixedEndpointAddr != null
                                ? fixedEndpointAddr : defaultEndpoint, request.getURI().getAuthority());
                        assertEquals(defaultHost, request.getFirstHeader(Headers.HOST).getValue());
                        assertEquals("http", request.getURI().getScheme());
                        assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue()
                                .contains("q-ak=testAK&"));
                        assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
                        response.setEntity(new StringEntity("<CreateSessionResult><Credentials>"
                                + "<AccessKeyId>session" + index + "</AccessKeyId>"
                                + "<SecretAccessKey>testSessionSK</SecretAccessKey>"
                                + "<SessionToken>token" + index + "</SessionToken>"
                                + "<Expiration>" + Instant.now().plusSeconds(index * 120)
                                + "</Expiration></Credentials></CreateSessionResult>", "UTF-8"));
                        return response;
                    });
            // 首次获取和刷新继承请求地址，Host 始终由 Client 配置生成。
            for (int i = 0; i < 3; i++) {
                if (i == 2) {
                    // 恢复默认域名入口，Host 保持不变。
                    config.setEndpointBuilder(defaultBuilder);
                }
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, "test.txt");
                request.setExpiration(java.util.Date.from(Instant.now().plusSeconds(60 + i * 120)));
                if (i < 2) {
                    request.setFixedEndpointAddr(fixedEndpointAddr);
                }
                // URL 保留 EndpointBuilder 地址，签名使用请求 Host，同时验证缓存复用。
                for (boolean signHost : new boolean[] {true, false}) {
                    java.net.URL url = client.generatePresignedUrl(request, signHost);
                    assertEquals(i < 2 && distinguishHost ? defaultEndpoint : defaultHost, url.getAuthority());
                    assertEquals("http", url.getProtocol());
                    assertEquals("/test.txt", url.getPath());
                    Map<String, String> query = new java.util.HashMap<>();
                    for (String parameter : url.getQuery().split("&")) {
                        String[] pair = parameter.split("=", 2);
                        query.put(java.net.URLDecoder.decode(pair[0], "UTF-8"),
                                java.net.URLDecoder.decode(pair[1], "UTF-8"));
                    }
                    assertEquals("token" + (i + 1), query.get(Headers.SECURITY_TOKEN));
                    assertEquals(signHost ? "host" : "", query.get("q-header-list"));
                    String[] signTime = query.get("q-sign-time").split(";");
                    Map<String, String> headers = new java.util.HashMap<>();
                    if (signHost) {
                        headers.put(Headers.HOST, defaultHost);
                    }
                    String expectedAuth = new COSSigner().buildAuthorizationStr(
                            request.getMethod(), url.getPath(), headers, new java.util.HashMap<>(),
                            new BasicSessionCredentials("session" + (i + 1), "testSessionSK", "token" + (i + 1)),
                            new java.util.Date(Long.parseLong(signTime[0]) * 1000L),
                            new java.util.Date(Long.parseLong(signTime[1]) * 1000L), signHost);
                    assertTrue(expectedAuth.endsWith("q-signature=" + query.get("q-signature")));
                    assertEquals(i + 1, sessionCalls.get());
                }
            }
            verify(network, times(3)).execute(any(HttpUriRequest.class), any(HttpContext.class));
            assertSame(resolver, config.getEndpointResolver());
            assertEquals(HttpProtocol.http, config.getHttpProtocol());
            assertEquals(defaultHost, config.getEndpointBuilder().buildGeneralApiEndpoint(bucket));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testCreateSessionReadsCompleteElementText() throws Exception {
        String expiration = Instant.now().plusSeconds(300).toString();
        String xml = "<CreateSessionResult><Credentials>"
                + "<AccessKeyId> session<![CDATA[AK]]> </AccessKeyId>"
                + "<SecretAccessKey> session<!-- split -->SK </SecretAccessKey>"
                + "<SessionToken> token&amp;<![CDATA[part]]> </SessionToken>"
                + "<Expiration> " + expiration.substring(0, 10) + "<![CDATA["
                + expiration.substring(10) + "]]> </Expiration></Credentials>"
                + "<RequestId>body<![CDATA[-request]]></RequestId></CreateSessionResult>";
        checkCreateSessionResponse(xml, true);
    }

    @Test
    public void testCreateSessionRejectsMissingAndEmptyCredentials() throws Exception {
        checkCreateSessionResponse(null, false);
        checkCreateSessionResponse("<CreateSessionResult/>", false);
        checkCreateSessionResponse("<CreateSessionResult><Credentials/></CreateSessionResult>", false);
        String xml = sessionResponseXml(Instant.now().plusSeconds(300).toString());
        for (String field : new String[] {"AccessKeyId", "SecretAccessKey", "SessionToken"}) {
            for (String replacement : new String[] {"", "<" + field + "/>",
                    "<" + field + "> \n\t </" + field + ">"}) {
                checkCreateSessionResponse(xml.replaceAll(
                        "<" + field + ">.*?</" + field + ">", replacement), false);
            }
        }
    }

    @Test
    public void testCreateSessionRejectsInvalidAndExpiredExpiration() throws Exception {
        checkCreateSessionResponse(sessionResponseXml(Instant.now().plusSeconds(300).toString())
                .replaceAll("<Expiration>.*?</Expiration>", ""), false);
        for (String expiration : new String[] {"", " \n\t ", "not-a-time",
                "2026-02-30T00:00:00Z", "1970-01-01T00:00:00Z",
                Instant.now().minusSeconds(60).toString(), Instant.now().toString()}) {
            checkCreateSessionResponse(sessionResponseXml(expiration), false);
        }
    }

    @Test
    public void testCreateSessionRejectsMalformedXml() throws Exception {
        checkCreateSessionResponse("<CreateSessionResult><Credentials>", false);
    }

    private String sessionResponseXml(String expiration) {
        return "<CreateSessionResult><Credentials><AccessKeyId>sessionAK</AccessKeyId>"
                + "<SecretAccessKey>sessionSK</SecretAccessKey>"
                + "<SessionToken>token&amp;part</SessionToken>"
                + "<Expiration>" + expiration + "</Expiration></Credentials>"
                + "<RequestId>body-request</RequestId></CreateSessionResult>";
    }

    private void checkCreateSessionResponse(String xml, boolean valid) throws Exception {
        String bucket = "bucket-x--1250000000";
        RapidCOSCredentialProvider provider = new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK"));
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        config.setMaxErrorRetry(0);
        COSClient client = new COSClient(provider, config);
        try {
            Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
            transportField.setAccessible(true);
            DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
            HttpClient network = mock(HttpClient.class);
            Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
            networkField.setAccessible(true);
            networkField.set(transport, network);
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
                        response.addHeader(Headers.REQUEST_ID, "header-request");
                        if (xml != null) {
                            response.setEntity(new StringEntity(xml, "UTF-8"));
                        }
                        return response;
                    });
            try {
                CreateSessionResult result = client.createSession(new CreateSessionRequest(bucket));
                assertTrue("Invalid response must fail for direct callers", valid);
                assertEquals("sessionAK", result.getCredentials().getTmpSecretId());
                assertEquals("sessionSK", result.getCredentials().getTmpSecretKey());
                assertEquals("token&part", result.getCredentials().getSessionToken());
                assertEquals("body-request", result.getRequestId());
                assertTrue(Instant.parse(result.getExpiration()).isAfter(Instant.now()));
            } catch (CosClientException e) {
                assertFalse("Valid response must succeed for direct callers", valid);
                assertTrue(e.getCause() instanceof CosClientException);
                if (xml != null && xml.contains("not-a-time")) {
                    assertTrue(e.getCause().getCause() instanceof java.time.format.DateTimeParseException);
                }
            }
            // 异常响应只能回退到尚未过期的缓存，不能覆盖旧凭证。
            for (int remaining : new int[] {-1, 0, 60}) {
                RapidSessionManager manager = new RapidSessionManager(provider);
                RapidSessionCredentials cached = remaining < 0 ? null : cacheSession(manager, bucket, remaining);
                try {
                    RapidSessionCredentials result = manager.getActiveSession(client, bucket);
                    if (valid) {
                        assertNotSame(cached, result);
                        assertEquals("sessionAK", result.getCOSAccessKeyId());
                        assertEquals("sessionSK", result.getCOSSecretKey());
                        assertEquals("token&part", result.getSessionToken());
                        assertSame(result, manager.getActiveSession(client, bucket));
                    } else {
                        assertEquals("Only an unexpired cache may be reused", 60, remaining);
                        assertSame(cached, result);
                    }
                } catch (CosClientException e) {
                    assertFalse("Valid response must refresh successfully", valid);
                    assertTrue("Unexpired cache must be reused", remaining <= 0);
                    assertTrue(e.getCause() instanceof CosClientException);
                }
            }
            verify(network, times(4)).execute(any(HttpUriRequest.class), any(HttpContext.class));
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void testServiceHostWithAndWithoutDistinguishHost() throws Exception {
        for (boolean rapid : new boolean[] {false, true}) {
            for (boolean distinguishHost : new boolean[] {false, true}) {
                for (boolean customEndpoint : new boolean[] {false, true}) {
                    String endpoint = "127.0.0.1:18080";
                    String serviceHost = rapid ? "service.cosrapid.ap-nanjing.myqcloud.com"
                            : "service.cos.myqcloud.com";
                    ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
                    config.setHttpProtocol(HttpProtocol.http);
                    config.setIsDistinguishHost(distinguishHost);
                    config.setMaxErrorRetry(0);
                    if (customEndpoint) {
                        config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(endpoint, endpoint));
                    }
                    COSCredentialsProvider provider = new COSStaticCredentialsProvider(
                            new BasicCOSCredentials("testAK", "testSK"));
                    COSClient client = new COSClient(rapid ? new RapidCOSCredentialProvider(provider) : provider, config);
                    try {
                        Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
                        transportField.setAccessible(true);
                        DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
                        Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
                        networkField.setAccessible(true);
                        HttpClient network = mock(HttpClient.class);
                        networkField.set(transport, network);
                        when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                                .thenAnswer(invocation -> {
                                    HttpUriRequest request = invocation.getArgument(0);
                                    assertEquals(customEndpoint ? endpoint : serviceHost, request.getURI().getAuthority());
                                    // 关闭区分且使用 IP 时仅验证原有 SDK 透传行为，不代表网关接受此 Host。
                                    assertEquals(distinguishHost || !customEndpoint ? serviceHost : endpoint,
                                            request.getFirstHeader(Headers.HOST).getValue());
                                    assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue()
                                            .contains("q-ak=testAK&"));
                                    assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                                    BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
                                    response.setEntity(new StringEntity(
                                            "<ListAllMyBucketsResult><Buckets/></ListAllMyBucketsResult>", "UTF-8"));
                                    return response;
                                });
                        client.listBuckets();
                        verify(network).execute(any(HttpUriRequest.class), any(HttpContext.class));
                    } finally {
                        client.shutdown();
                    }
                }
            }
        }
    }

    @Test
    public void testCreateDeleteBucketsUseServiceAddressAndBucketHost() throws Exception {
        checkCreateDeleteBucketHost(true);
    }

    @Test
    public void testCreateDeleteBucketsKeepBucketHostByDefault() throws Exception {
        checkCreateDeleteBucketHost(false);
    }

    private void checkCreateDeleteBucketHost(boolean distinguishHost) throws Exception {
        for (boolean customEndpoint : new boolean[] {false, true}) {
            String endpoint = "127.0.0.1:18080";
            ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
            config.setHttpProtocol(HttpProtocol.http);
            assertFalse(config.getIsDistinguishHost());
            if (distinguishHost) {
                config.setIsDistinguishHost(true);
            }
            config.setMaxErrorRetry(0);
            if (customEndpoint) {
                config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(endpoint, endpoint));
            }
            COSClient client = new COSClient(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials("testAK", "testSK")), config);
            AtomicInteger calls = new AtomicInteger();
            try {
                Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
                transportField.setAccessible(true);
                DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
                Field networkField = DefaultCosHttpClient.class.getDeclaredField("httpClient");
                networkField.setAccessible(true);
                HttpClient network = mock(HttpClient.class);
                networkField.set(transport, network);
                when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                        .thenAnswer(invocation -> {
                            HttpUriRequest request = invocation.getArgument(0);
                            int index = calls.getAndIncrement();
                            assertEquals(customEndpoint ? endpoint : "service.cosrapid.ap-nanjing.myqcloud.com",
                                    request.getURI().getAuthority());
                            assertEquals("bucket" + index / 2 + "-x--1250000000.cosrapid.ap-nanjing.myqcloud.com",
                                    request.getFirstHeader(Headers.HOST).getValue());
                            assertEquals(index % 2 == 0 ? "PUT" : "DELETE", request.getMethod());
                            assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue().contains("q-ak=testAK&"));
                            assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                            return new BasicHttpResponse(HttpVersion.HTTP_1_1, index % 2 == 0 ? 200 : 204, "OK");
                        });
                for (int i = 0; i < 2; i++) {
                    String bucket = "bucket" + i + "-x--1250000000";
                    client.createBucket(new com.qcloud.cos.model.CreateBucketRequest(bucket));
                    client.deleteBucket(new com.qcloud.cos.model.DeleteBucketRequest(bucket));
                }
                assertEquals(4, calls.get());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    public void testPresignOverloadsUseConfiguredHost() throws Exception {
        for (boolean rapid : new boolean[] {false, true}) {
            String bucket = rapid ? "bucket-x--1250000000" : "bucket-1250000000";
            String bucketHost = bucket + (rapid ? ".cosrapid." : ".cos.") + "ap-nanjing.myqcloud.com";
            String endpoint = "127.0.0.1:19990";
            for (boolean distinguishHost : new boolean[] {false, true}) {
                ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
                config.setIsDistinguishHost(distinguishHost);
                if (distinguishHost) {
                    config.setEndpointBuilder(new UserSpecifiedEndpointBuilder(endpoint, endpoint));
                }
                COSCredentials credentials = new BasicCOSCredentials("testAK", "testSK");
                COSCredentialsProvider provider = new COSStaticCredentialsProvider(credentials);
                if (rapid) {
                    credentials = new RapidSessionCredentials("sessionAK", "sessionSK", "token",
                            Instant.now().getEpochSecond() + 600, "testAK");
                    RapidCOSCredentialProvider rapidProvider = mock(RapidCOSCredentialProvider.class);
                    when(rapidProvider.getCredentials()).thenReturn(new BasicCOSCredentials("testAK", "testSK"));
                    when(rapidProvider.getCredentialsForPresign(any(COSClient.class), eq(bucket), anyLong(), isNull()))
                            .thenReturn(credentials);
                    provider = rapidProvider;
                }
                COSClient client = new COSClient(provider, config);
                try {
                    for (boolean signHost : new boolean[] {false, true}) {
                        for (boolean customHost : new boolean[] {false, true}) {
                            String expectedHost = customHost ? bucketHost.replace("ap-nanjing", "ap-guangzhou") : bucketHost;
                            Map<String, String> headers = new java.util.HashMap<>();
                            if (customHost) {
                                headers.put(Headers.HOST, expectedHost);
                            }
                            String key = "dir/空 格+%.txt";
                            java.util.Date expiration = java.util.Date.from(Instant.now().plusSeconds(120));
                            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key);
                            request.setExpiration(expiration);
                            request.setSignPrefixMode(false);
                            if (customHost) {
                                request.putCustomRequestHeader(Headers.HOST, expectedHost);
                            }
                            java.net.URL[] urls = {
                                    client.generatePresignedUrl(request, signHost),
                                    client.generatePresignedUrl(bucket, key, expiration,
                                            com.qcloud.cos.http.HttpMethodName.GET, headers,
                                            new java.util.HashMap<>(), false, signHost)
                            };
                            for (java.net.URL url : urls) {
                                assertEquals(distinguishHost ? endpoint : bucketHost, url.getAuthority());
                                Map<String, String> query = new java.util.HashMap<>();
                                for (String parameter : url.getQuery().split("&")) {
                                    String[] pair = parameter.split("=", 2);
                                    query.put(java.net.URLDecoder.decode(pair[0], "UTF-8"),
                                            java.net.URLDecoder.decode(pair[1], "UTF-8"));
                                }
                                assertEquals(signHost ? "host" : "", query.get("q-header-list"));
                                String[] times = query.get("q-sign-time").split(";");
                                Map<String, String> expectedHeaders = new java.util.HashMap<>();
                                if (signHost) {
                                    expectedHeaders.put(Headers.HOST, expectedHost);
                                }
                                String expected = new COSSigner().buildAuthorizationStr(request.getMethod(), "/" + key,
                                        expectedHeaders, new java.util.HashMap<>(), credentials,
                                        new java.util.Date(Long.parseLong(times[0]) * 1000L),
                                        new java.util.Date(Long.parseLong(times[1]) * 1000L), signHost);
                                assertTrue(expected.endsWith("q-signature=" + query.get("q-signature")));
                                assertEquals(rapid ? "token" : null, query.get(Headers.SECURITY_TOKEN));
                            }
                            assertEquals(urls[0].getPath(), urls[1].getPath());
                        }
                    }
                } finally {
                    client.shutdown();
                }
            }
        }
    }

    private RapidSessionCredentials cacheSession(RapidSessionManager manager, int remainingSec)
            throws Exception {
        return cacheSession(manager, "bucket", remainingSec);
    }

    private RapidSessionCredentials cacheSession(RapidSessionManager manager, String bucket, int remainingSec)
            throws Exception {
        RapidSessionCredentials cached = new RapidSessionCredentials("sessionAK", "sessionSK", "token",
                System.currentTimeMillis() / 1000L + remainingSec, "testAK");
        Field field = RapidSessionManager.class.getDeclaredField("bucketToRapidCredMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, RapidSessionCredentials> cache = (Map<String, RapidSessionCredentials>) field.get(manager);
        cache.put(bucket, cached);
        return cached;
    }
}
