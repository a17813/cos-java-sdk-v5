package com.qcloud.cos.rapid;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.Headers;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSStaticCredentialsProvider;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.endpoint.RegionEndpointBuilder;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.http.CosHttpRequest;
import com.qcloud.cos.http.DefaultCosHttpClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.internal.CIPicServiceRequest;
import com.qcloud.cos.internal.CIServiceRequest;
import com.qcloud.cos.internal.CosServiceRequest;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteBucketRequest;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.model.ListBucketsRequest;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.EndpointUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RapidEndpointUtilsTest {

    @Test
    public void firstAndRetryShareServiceAndBucketRoutes() throws Exception {
        for (boolean rapidClient : new boolean[] {false, true}) {
            for (boolean fixed : new boolean[] {false, true}) {
                ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
                BasicCOSCredentials credentials = new BasicCOSCredentials("123", "testAK", "testSK");
                COSClient client = new COSClient(rapidClient ? new RapidCOSCredentialProvider(credentials)
                        : new COSStaticCredentialsProvider(credentials), config);
                try {
                    for (String bucket : new String[] {"normal", "test-x--123"}) {
                        boolean rapidBucket = bucket.equals("test-x--123");
                        String bucketDomain = rapidBucket ? "test-x--123.cosrapid.ap-nanjing.myqcloud.com"
                                : "normal-123.cos.ap-nanjing.myqcloud.com";
                        String domain = rapidBucket ? "service.cosrapid.ap-nanjing.myqcloud.com" : bucketDomain;
                        assertRefreshRoute(client, new CreateBucketRequest(bucket), bucket, rapidBucket, domain, fixed);
                        assertRefreshRoute(client, new DeleteBucketRequest(bucket), bucket, rapidBucket, domain, fixed);
                        assertRefreshRoute(client, new DeleteObjectRequest(bucket, "key"), bucket, false, bucketDomain, fixed);
                    }
                    assertRefreshRoute(client, new ListBucketsRequest(), null, true,
                            rapidClient ? "service.cosrapid.ap-nanjing.myqcloud.com" : "service.cos.myqcloud.com", fixed);
                } finally {
                    client.shutdown();
                }
            }
        }
    }

    @Test
    public void customBuildersAndCiRoutesSurviveRefresh() throws Exception {
        for (boolean custom : new boolean[] {false, true}) {
            ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
            if (custom) {
                config.setEndpointBuilder(new UserSpecifiedEndpointBuilder("custom-bucket.invalid", "custom-service.invalid"));
            }
            COSClient client = new COSClient(new BasicCOSCredentials("testAK", "testSK"), config);
            try {
                for (boolean fixed : new boolean[] {false, true}) {
                    assertRefreshRoute(client, new CIServiceRequest(), "normal-123", false,
                            custom ? "custom-bucket.invalid" : "normal-123.ci.ap-nanjing.myqcloud.com", fixed);
                    assertRefreshRoute(client, new CIPicServiceRequest(), "normal-123", false,
                            "normal-123.pic.ap-nanjing.myqcloud.com", fixed);
                    assertRefreshRoute(client, new CreateBucketRequest("test-x--123"), "test-x--123", true,
                            custom ? "custom-service.invalid" : "service.cosrapid.ap-nanjing.myqcloud.com", fixed);
                    assertRefreshRoute(client, new DeleteBucketRequest("normal-123"), "normal-123", false,
                            custom ? "custom-bucket.invalid" : "normal-123.cos.ap-nanjing.myqcloud.com", fixed);
                    assertRefreshRoute(client, new ListBucketsRequest(), null, true,
                            custom ? "custom-service.invalid" : "service.cos.myqcloud.com", fixed);
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    public void listBucketRefreshTracksCredentialProviderChanges() throws Exception {
        ClientConfig config = new ClientConfig(new Region("ap-nanjing"));
        BasicCOSCredentials credentials = new BasicCOSCredentials("testAK", "testSK");
        COSClient client = new COSClient(credentials, config);
        try {
            assertRefreshRoute(client, new ListBucketsRequest(), null, true, "service.cos.myqcloud.com", false);
            client.setCOSCredentialsProvider(new RapidCOSCredentialProvider(credentials));
            assertRefreshRoute(client, new ListBucketsRequest(), null, true, "service.cosrapid.ap-nanjing.myqcloud.com", false);
            client.setCOSCredentials(credentials);
            assertRefreshRoute(client, new ListBucketsRequest(), null, true, "service.cos.myqcloud.com", false);
            client.setCOSCredentialsProvider(new RapidCOSCredentialProvider(credentials));
            client.setCOSCredentialsProvider(new COSStaticCredentialsProvider(credentials));
            assertRefreshRoute(client, new ListBucketsRequest(), null, true, "service.cos.myqcloud.com", false);
        } finally {
            client.shutdown();
        }
    }

    private void assertRefreshRoute(COSClient client, CosServiceRequest original, String bucket,
            boolean service, String domain, boolean fixed) throws Exception {
        ClientConfig config = client.getClientConfig();
        EndpointResolver resolver = mock(EndpointResolver.class);
        if (service) {
            when(resolver.resolveGetServiceApiEndpoint(domain)).thenReturn("first.invalid", "second.invalid", "third.invalid");
        } else {
            when(resolver.resolveGeneralApiEndpoint(domain)).thenReturn("first.invalid", "second.invalid", "third.invalid");
        }
        config.setEndpointResolver(resolver);
        if (fixed) {
            original.setFixedEndpointAddr("fixed.invalid");
        }
        Method create = COSClient.class.getDeclaredMethod("createRequest", String.class, String.class,
                CosServiceRequest.class, HttpMethodName.class);
        create.setAccessible(true);
        CosHttpRequest<?> request = (CosHttpRequest<?>) create.invoke(client, bucket, "/a%2Fb", original, HttpMethodName.GET);
        assertEquals(fixed ? "fixed.invalid" : "first.invalid", request.getEndpoint());
        String expectedHost = service && bucket != null
                ? bucket + ".cosrapid.ap-nanjing.myqcloud.com" : domain;
        assertEquals(expectedHost, request.getHeaders().get(Headers.HOST));
        if ("normal".equals(bucket)) {
            assertEquals("normal-123", request.getBucketName());
        }
        request.addHeader(Headers.COS_AUTHORIZATION, "unchanged");
        Map<String, String> headers = new HashMap<>(request.getHeaders());
        String path = request.getResourcePath();
        Object protocol = request.getProtocol();
        Field transportField = COSClient.class.getDeclaredField("cosHttpClient");
        transportField.setAccessible(true);
        DefaultCosHttpClient transport = (DefaultCosHttpClient) transportField.get(client);
        Method refresh = DefaultCosHttpClient.class.getDeclaredMethod("refreshEndpointAddr", CosHttpRequest.class);
        refresh.setAccessible(true);
        for (String address : new String[] {"second.invalid", "third.invalid"}) {
            refresh.invoke(transport, request);
            assertEquals(fixed ? "fixed.invalid" : address, request.getEndpoint());
            assertEquals(headers, request.getHeaders());
            assertEquals(path, request.getResourcePath());
            assertEquals(protocol, request.getProtocol());
        }
        if (service) {
            verify(resolver, times(3)).resolveGetServiceApiEndpoint(domain);
            verify(resolver, never()).resolveGeneralApiEndpoint(anyString());
        } else {
            verify(resolver, times(3)).resolveGeneralApiEndpoint(domain);
            verify(resolver, never()).resolveGetServiceApiEndpoint(anyString());
        }
    }

    // --- EndpointUtils.isRapidBucket ---

    @Test
    public void testIsRapidBucketTrue() {
        assertTrue(EndpointUtils.isRapidBucket("mybucket-x--1250000000"));
        assertTrue(EndpointUtils.isRapidBucket("a-x--1"));
        assertTrue(EndpointUtils.isRapidBucket("test-bucket-x--1253960454"));
    }

    @Test
    public void testIsRapidBucketFalse() {
        assertFalse(EndpointUtils.isRapidBucket("mybucket-1250000000"));
        assertFalse(EndpointUtils.isRapidBucket("mybucket-x-1250000000"));
        assertFalse(EndpointUtils.isRapidBucket("mybucket-x---1250000000"));
    }

    @Test
    public void testIsRapidBucketNull() {
        assertFalse(EndpointUtils.isRapidBucket(null));
    }

    @Test
    public void testIsRapidBucketEmpty() {
        assertFalse(EndpointUtils.isRapidBucket(""));
    }

    @Test
    public void testIsRapidBucketUpperCase() {
        assertFalse(EndpointUtils.isRapidBucket("MYBUCKET-x--1250000000"));
    }

    // --- RegionEndpointBuilder.buildGeneralApiEndpoint for rapid bucket ---

    @Test
    public void testBuildGeneralApiEndpointRapidBucket() {
        RegionEndpointBuilder builder = new RegionEndpointBuilder(new Region("ap-nanjing"));
        String endpoint = builder.buildGeneralApiEndpoint("mybucket-x--1250000000");
        assertEquals("mybucket-x--1250000000.cosrapid.ap-nanjing.myqcloud.com", endpoint);
    }

    @Test
    public void testBuildGeneralApiEndpointNormalBucket() {
        RegionEndpointBuilder builder = new RegionEndpointBuilder(new Region("ap-nanjing"));
        String endpoint = builder.buildGeneralApiEndpoint("mybucket-1250000000");
        assertEquals("mybucket-1250000000.cos.ap-nanjing.myqcloud.com", endpoint);
    }

    // --- RegionEndpointBuilder.buildGetServiceApiEndpoint(bucket) ---

    @Test
    public void testBuildGetServiceApiEndpointRapidBucket() {
        RegionEndpointBuilder builder = new RegionEndpointBuilder(new Region("ap-nanjing"));
        String endpoint = builder.buildGetServiceApiEndpoint("mybucket-x--1250000000");
        assertEquals("service.cosrapid.ap-nanjing.myqcloud.com", endpoint);
    }

    @Test
    public void testBuildGetServiceApiEndpointNormalBucket() {
        RegionEndpointBuilder builder = new RegionEndpointBuilder(new Region("ap-nanjing"));
        String endpoint = builder.buildGetServiceApiEndpoint("mybucket-1250000000");
        assertEquals("service.cos.myqcloud.com", endpoint);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildGetServiceApiEndpointNullRegion() {
        RegionEndpointBuilder builder = new RegionEndpointBuilder(null);
        builder.buildGetServiceApiEndpoint("mybucket-x--1250000000");
    }

    // --- EndpointUtils.extractEndpointSuffix ---

    @Test
    public void testExtractEndpointSuffix() {
        assertEquals("cosrapid.ap-nanjing.myqcloud.com",
                EndpointUtils.extractEndpointSuffix("mybucket-x--125.cosrapid.ap-nanjing.myqcloud.com"));
    }

    @Test
    public void testExtractEndpointSuffixNormal() {
        assertEquals("cos.ap-nanjing.myqcloud.com",
                EndpointUtils.extractEndpointSuffix("mybucket-125.cos.ap-nanjing.myqcloud.com"));
    }

    @Test
    public void testExtractEndpointSuffixNull() {
        assertNull(EndpointUtils.extractEndpointSuffix(null));
    }

    @Test
    public void testExtractEndpointSuffixNoDot() {
        assertNull(EndpointUtils.extractEndpointSuffix("nodothere"));
    }

    @Test
    public void testExtractEndpointSuffixTrailingDot() {
        assertNull(EndpointUtils.extractEndpointSuffix("bucket."));
    }
}
