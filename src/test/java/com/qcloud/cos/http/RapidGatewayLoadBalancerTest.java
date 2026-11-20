package com.qcloud.cos.http;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.Headers;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.internal.CosClientAbortTaskMonitor;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteBucketRequest;
import com.qcloud.cos.region.Region;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RapidGatewayLoadBalancerTest {
    static final String HOST = "test-x--123.cosrapid.ap-guangzhou.myqcloud.com";
    private ClientConfig config;
    private InetAddress first;
    private InetAddress second;

    @Before
    public void setUp() throws Exception {
        registry().clear();
        config = new ClientConfig();
        first = InetAddress.getByName("127.0.0.2");
        second = InetAddress.getByName("127.0.0.3");
    }

    @After
    public void tearDown() throws Exception {
        registry().clear();
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    static Map<String, RapidGatewayLoadBalancer.Entry> registry() throws Exception {
        return (Map<String, RapidGatewayLoadBalancer.Entry>) get(RapidGatewayLoadBalancer.class, "ENTRIES");
    }

    static Object get(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }

    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static RapidGatewayLoadBalancer.Entry seed(String host, InetAddress... nodes) throws Exception {
        RapidGatewayLoadBalancer.Entry entry = new RapidGatewayLoadBalancer.Entry();
        set(entry, "nodes", Collections.unmodifiableList(Arrays.asList(nodes)));
        set(entry, "nextRefresh", System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
        registry().put(host, entry);
        return entry;
    }

    private static HttpResponse response(int status) {
        return new BasicHttpResponse(HttpVersion.HTTP_1_1, status, "test");
    }

    @Test
    public void canonicalHostAndGates() {
        // 端口和尾点仅用于 SDK 规范化与门禁边界测试，不代表网关支持。
        assertEquals(HOST, RapidGatewayLoadBalancer.canonicalHost(HOST.toUpperCase() + ".:80"));
        assertEquals("", RapidGatewayLoadBalancer.canonicalHost(null));
        assertTrue(RapidGatewayLoadBalancer.eligible(new HttpGet("http://" + HOST), config));
        for (String uri : new String[] {"https://" + HOST, "http://" + HOST + ":80",
                "http://127.0.0.1", "http://[::1]", "http://user@" + HOST, "/relative"}) {
            assertFalse(uri, RapidGatewayLoadBalancer.eligible(new HttpGet(uri), config));
        }
        HttpGet request = new HttpGet("http://" + HOST);
        request.setConfig(RequestConfig.custom().setProxy(new HttpHost("proxy", 80)).build());
        assertFalse(RapidGatewayLoadBalancer.eligible(request, config));
        config.setHttpProxyIp("proxy");
        assertFalse(RapidGatewayLoadBalancer.eligible(new HttpGet("http://" + HOST), config));
    }

    @Test
    public void preservesHostAndUriSdkBoundaryAndDoesNotRetry() throws Exception {
        seed(HOST, first);
        HttpClient client = mock(HttpClient.class);
        HttpGet request = new HttpGet("http://" + HOST + "/a%2Fb?q=%2B&token=abc");
        // 仅验证原始 Host 透传，不向真实网关发送该边界输入。
        String rawHost = HOST.toUpperCase() + ".:80";
        request.setHeader("Host", rawHost);
        request.setHeader("Authorization", "unchanged");
        when(client.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                .thenAnswer(call -> {
                    assertEquals(first.getHostAddress(), ((HttpHost) call.getArgument(0)).getHostName());
                    assertEquals(rawHost, request.getFirstHeader("Host").getValue());
                    assertEquals("unchanged", request.getFirstHeader("Authorization").getValue());
                    assertEquals("http://" + HOST + "/a%2Fb?q=%2B&token=abc", request.getURI().toString());
                    return response(503);
                });
        assertEquals(503, RapidGatewayLoadBalancer.execute(client, config, request,
                new BasicHttpContext()).getStatusLine().getStatusCode());
        assertEquals(rawHost, request.getFirstHeader("Host").getValue());
        verify(client, times(1)).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
        assertNull(registry().get(HOST).pick(Arrays.asList(first)));
    }

    @Test
    public void pinsAbsentHostAndFallsBackWhenCooling() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        HttpClient client = mock(HttpClient.class);
        HttpGet request = new HttpGet("http://" + HOST + "/key");
        when(client.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                .thenAnswer(call -> {
                    assertEquals(HOST, request.getFirstHeader("Host").getValue());
                    return response(200);
                });
        RapidGatewayLoadBalancer.execute(client, config, request, new BasicHttpContext());
        assertNull(request.getFirstHeader("Host"));
        entry.markFailed(first);
        when(client.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(response(200));
        RapidGatewayLoadBalancer.execute(client, config, request, new BasicHttpContext());
        verify(client, times(1)).execute(any(HttpUriRequest.class), any(HttpContext.class));
    }

    @Test
    public void ordinaryAndServiceHostsPassThrough() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(response(200));
        for (String host : new String[] {"bucket-123.cos.ap-guangzhou.myqcloud.com",
                "service.cosrapid.ap-guangzhou.myqcloud.com"}) {
            RapidGatewayLoadBalancer.execute(client, config, new HttpGet("http://" + host), new BasicHttpContext());
        }
        verify(client, times(2)).execute(any(HttpUriRequest.class), any(HttpContext.class));
        assertTrue(registry().isEmpty());
    }

    @Test
    public void differentConnectionHostBypassesColdAndCachedNodes() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse expected = response(200);
        when(client.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(expected);
        HttpGet request = new HttpGet("http://localhost/key");
        request.setHeader("Host", HOST);
        request.setHeader("Authorization", "unchanged");
        HttpContext context = new BasicHttpContext();
        assertSame(expected, RapidGatewayLoadBalancer.execute(client, config, request, context));
        assertTrue("A different connection host must not start bucket DNS lookup", registry().isEmpty());
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        assertSame(expected, RapidGatewayLoadBalancer.execute(client, config, request, context));
        assertEquals("http://localhost/key", request.getURI().toString());
        assertEquals(HOST, request.getFirstHeader("Host").getValue());
        assertEquals("unchanged", request.getFirstHeader("Authorization").getValue());
        assertEquals(first, entry.pick(Arrays.asList(first)));
        verify(client, times(2)).execute(request, context);
        verify(client, never()).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
    }

    @Test
    public void emptyConnectionCacheDoesNotFallBackToHostNodes() throws Exception {
        String connectionHost = "connect-x--123.cosrapid.ap-guangzhou.myqcloud.com";
        seed(connectionHost);
        seed(HOST, second);
        HttpClient client = mock(HttpClient.class);
        HttpResponse expected = response(200);
        when(client.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(expected);
        HttpGet request = new HttpGet("http://" + connectionHost + "/key");
        request.setHeader("Host", HOST);
        HttpContext context = new BasicHttpContext();
        assertSame(expected, RapidGatewayLoadBalancer.execute(client, config, request, context));
        verify(client).execute(request, context);
        verify(client, never()).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
        assertEquals(connectionHost, request.getURI().getHost());
        assertEquals(HOST, request.getFirstHeader("Host").getValue());
        assertTrue(((Map<?, ?>) get(registry().get(HOST), "health")).isEmpty());
    }

    @Test
    public void connectionEndpointOwnsSelectionAndCooldownRegardlessOfHost() throws Exception {
        String connectionHost = "connect-x--123.cosrapid.ap-guangzhou.myqcloud.com";
        for (String signingHost : new String[] {HOST, "service.cosrapid.ap-guangzhou.myqcloud.com", "custom.invalid"}) {
            RapidGatewayLoadBalancer.Entry connectionEntry = seed(connectionHost, first);
            RapidGatewayLoadBalancer.Entry signingEntry = seed(signingHost, second);
            HttpClient client = mock(HttpClient.class);
            HttpGet request = new HttpGet("http://" + connectionHost.toUpperCase() + "./a%2Fb?q=%2B");
            request.setHeader("Host", signingHost);
            request.setHeader("Authorization", "unchanged");
            String originalUri = request.getURI().toString();
            when(client.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenAnswer(call -> {
                        HttpHost target = call.getArgument(0);
                        assertEquals(first.getHostAddress(), target.getHostName());
                        assertEquals(80, target.getPort());
                        assertEquals(signingHost, request.getFirstHeader("Host").getValue());
                        assertEquals("unchanged", request.getFirstHeader("Authorization").getValue());
                        assertEquals(originalUri, request.getURI().toString());
                        return response(503);
                    });
            assertEquals(503, RapidGatewayLoadBalancer.execute(client, config, request,
                    new BasicHttpContext()).getStatusLine().getStatusCode());
            assertNull(connectionEntry.pick(Arrays.asList(first)));
            assertEquals(second, signingEntry.pick(Arrays.asList(second)));
            assertTrue(((Map<?, ?>) get(signingEntry, "health")).isEmpty());
            verify(client, never()).execute(any(HttpUriRequest.class), any(HttpContext.class));
            registry().clear();
        }
    }

    @Test
    public void serviceEndpointBypassesColdAndCachedNodes() throws Exception {
        String serviceHost = "service.cosrapid.ap-guangzhou.myqcloud.com";
        String uri = "http://" + serviceHost + "/";
        for (boolean cached : new boolean[] {false, true}) {
            registry().clear();
            if (cached) {
                seed(HOST, first);
            }
            HttpClient client = mock(HttpClient.class);
            HttpResponse expected = response(503);
            when(client.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(expected);
            for (HttpRequestBase request : new HttpRequestBase[] {
                    new HttpPut(uri), new HttpDelete(uri), new HttpGet(uri)}) {
                String host = request instanceof HttpGet ? serviceHost : HOST;
                request.setHeader("Host", host);
                request.setHeader("Authorization", "unchanged");
                HttpContext context = new BasicHttpContext();
                assertSame(expected, RapidGatewayLoadBalancer.execute(client, config, request, context));
                assertEquals(uri, request.getURI().toString());
                assertEquals(host, request.getFirstHeader("Host").getValue());
                assertEquals("unchanged", request.getFirstHeader("Authorization").getValue());
                verify(client).execute(request, context);
            }
            verify(client, never()).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
            assertEquals(cached ? 1 : 0, registry().size());
            if (cached) {
                assertTrue(((Map<?, ?>) get(registry().get(HOST), "health")).isEmpty());
            }
        }
    }

    @Test
    public void createDeleteBucketRetriesKeepServiceRouteWithCachedNodes() throws Exception {
        checkCreateDeleteBucketRetries(true);
    }

    @Test
    public void createDeleteBucketRetriesKeepBucketHostByDefault() throws Exception {
        checkCreateDeleteBucketRetries(false);
    }

    private void checkCreateDeleteBucketRetries(boolean distinguishHost) throws Exception {
        String bucket = "test-x--123";
        String serviceHost = "service.cosrapid.ap-guangzhou.myqcloud.com";
        String retryHost = "service-retry.example.invalid";
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        config = new ClientConfig(new Region("ap-guangzhou"));
        config.setHttpProtocol(HttpProtocol.http);
        assertFalse(config.getIsDistinguishHost());
        if (distinguishHost) {
            config.setIsDistinguishHost(true);
        }
        config.setMaxErrorRetry(1);
        config.setBackoffStrategy(retryIndex -> 0);
        config.turnOnRefreshEndpointAddrSwitch();
        EndpointResolver resolver = mock(EndpointResolver.class);
        when(resolver.resolveGetServiceApiEndpoint(serviceHost))
                .thenReturn(serviceHost, retryHost, serviceHost, retryHost);
        config.setEndpointResolver(resolver);
        COSClient client = new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("testAK", "testSK")), config);
        DefaultCosHttpClient transport = (DefaultCosHttpClient) get(client, "cosHttpClient");
        HttpClient originalNetwork = transport.httpClient;
        HttpClient network = mock(HttpClient.class);
        transport.httpClient = network;
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            when(network.execute(any(HttpUriRequest.class), any(HttpContext.class)))
                    .thenAnswer(invocation -> {
                        HttpUriRequest request = invocation.getArgument(0);
                        int index = calls.getAndIncrement();
                        assertTrue("Only one retry is allowed per operation", index < 4);
                        assertEquals(index < 2 ? "PUT" : "DELETE", request.getMethod());
                        assertEquals(index % 2 == 0 ? serviceHost : retryHost,
                                request.getURI().getAuthority());
                        assertEquals(HOST, request.getFirstHeader(Headers.HOST).getValue());
                        assertTrue(request.getFirstHeader(Headers.COS_AUTHORIZATION).getValue()
                                .contains("q-ak=testAK&"));
                        assertNull(request.getFirstHeader(Headers.SECURITY_TOKEN));
                        verify(resolver, times(index + 1)).resolveGetServiceApiEndpoint(serviceHost);
                        assertTrue("Service failures must not cool bucket nodes",
                                ((Map<?, ?>) get(entry, "health")).isEmpty());
                        if (index % 2 == 0) {
                            BasicHttpResponse failure = new BasicHttpResponse(
                                    HttpVersion.HTTP_1_1, 503, "Service Unavailable");
                            failure.setEntity(new org.apache.http.entity.StringEntity(
                                    "<Error><Code>ServiceUnavailable</Code>"
                                            + "<Message>retry service</Message></Error>", "UTF-8"));
                            return failure;
                        }
                        assertEquals("true", request.getFirstHeader("x-cos-sdk-retry").getValue());
                        return response(index < 2 ? 200 : 204);
                    });
            when(network.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenThrow(new AssertionError("Service requests must not select a bucket node"));
            client.createBucket(new CreateBucketRequest(bucket));
            assertEquals(2, calls.get());
            client.deleteBucket(new DeleteBucketRequest(bucket));
            assertEquals(4, calls.get());
            verify(resolver, times(4)).resolveGetServiceApiEndpoint(serviceHost);
            verify(resolver, never()).resolveGeneralApiEndpoint(any(String.class));
            verify(network, times(4)).execute(any(HttpUriRequest.class), any(HttpContext.class));
            verify(network, never()).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
            assertEquals(1, registry().size());
            assertSame(entry, registry().get(HOST));
            assertTrue(((Map<?, ?>) get(entry, "health")).isEmpty());
            assertEquals(first, entry.pick(Arrays.asList(first)));
        } finally {
            transport.httpClient = originalNetwork;
            client.shutdown();
        }
    }

    @Test
    public void statusClassification() throws Exception {
        for (int status : new int[] {200, 403, 404, 429, 500, 501, 502, 503, 504}) {
            RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
            HttpClient client = mock(HttpClient.class);
            when(client.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenReturn(response(status));
            RapidGatewayLoadBalancer.execute(client, config, new HttpGet("http://" + HOST), new BasicHttpContext());
            assertEquals("status " + status, status == 500 || status == 502 || status == 503 || status == 504,
                    entry.pick(Arrays.asList(first)) == null);
        }
    }

    @Test
    public void exceptionCancellationAndTimerClassification() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            final int failureMode = mode;
            RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
            HttpGet request = new HttpGet("http://" + HOST);
            HttpContext context = new BasicHttpContext();
            CosClientAbortTaskMonitor monitor = mock(CosClientAbortTaskMonitor.class);
            when(monitor.hasTimeoutExpired()).thenReturn(mode == 3);
            context.setAttribute(RapidGatewayLoadBalancer.ABORT_MONITOR, monitor);
            HttpClient client = mock(HttpClient.class);
            when(client.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenAnswer(call -> {
                        if (failureMode == 2 || failureMode == 3) {
                            request.abort();
                        }
                        if (failureMode == 4) {
                            throw new ConnectionPoolTimeoutException("pool");
                        }
                        if (failureMode == 1) {
                            throw new SocketTimeoutException("socket");
                        }
                        throw new IOException("network");
                    });
            try {
                RapidGatewayLoadBalancer.execute(client, config, request, context);
                fail("expected IOException");
            } catch (IOException expected) {
                assertEquals(mode != 2 && mode != 4, entry.pick(Arrays.asList(first)) == null);
            }
        }
    }

    @Test
    public void distributionCooldownBackoffAndReset() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first, second);
        List<InetAddress> nodes = Arrays.asList(first, second);
        int firstCount = 0;
        for (int i = 0; i < 10000; i++) {
            if (first.equals(entry.pick(nodes))) {
                firstCount++;
            }
        }
        assertTrue(firstCount > 4500 && firstCount < 5500);
        for (long seconds : new long[] {5, 10, 20, 40, 60, 60}) {
            entry.markFailed(first);
            Object state = ((Map<?, ?>) get(entry, "health")).get(first);
            assertEquals(TimeUnit.SECONDS.toNanos(seconds), get(state, "cooldown"));
            assertEquals(second, entry.pick(nodes));
        }
        entry.markSucceeded(first);
        assertEquals(second, entry.pick(nodes));
        entry.markFailed(first);
        Object state = ((Map<?, ?>) get(entry, "health")).get(first);
        assertEquals(TimeUnit.SECONDS.toNanos(5), get(state, "cooldown"));
        set(state, "lastFailure", System.nanoTime() - TimeUnit.MINUTES.toNanos(6));
        entry.markFailed(first);
        assertEquals(TimeUnit.SECONDS.toNanos(5), get(state, "cooldown"));
        set(state, "until", System.nanoTime() - 1);
        assertEquals(first, entry.pick(Arrays.asList(first)));
    }

    @Test
    public void asyncDnsColdStartAndRefreshPrunesNodes() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = new RapidGatewayLoadBalancer.Entry();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                workers.submit(() -> entry.resolve("localhost")).get(1, TimeUnit.SECONDS);
            }
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((Boolean) get(entry, "resolving") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(entry.resolve("localhost").isEmpty());
            List<InetAddress> snapshot = entry.resolve("localhost");
            assertSame(snapshot, entry.resolve("localhost"));
            RapidGatewayLoadBalancer.Entry seeded = seed(HOST, first, second);
            seeded.markFailed(first);
            Method refresh = seeded.getClass().getDeclaredMethod("refresh", String.class);
            refresh.setAccessible(true);
            refresh.invoke(seeded, "localhost");
            assertFalse(seeded.resolve("localhost").contains(first));
            assertTrue(((Map<?, ?>) get(seeded, "health")).isEmpty());
            seeded.markFailed(first);
            assertTrue(((Map<?, ?>) get(seeded, "health")).isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void dnsFailureKeepsSnapshotAndRateLimits() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        Method refresh = entry.getClass().getDeclaredMethod("refresh", String.class);
        refresh.setAccessible(true);
        refresh.invoke(entry, "invalid host");
        assertEquals(Arrays.asList(first), entry.resolve("invalid host"));
        assertFalse((Boolean) get(entry, "resolving"));
        assertTrue((Long) get(entry, "nextRefresh") - System.nanoTime() > 0);
    }

    @Test
    public void bothExecutionPathsUseBalancer() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            seed(HOST, first);
            config.setRequestTimeout(2000);
            DefaultCosHttpClient client = timeout ? new TimeOutCosHttpClient(config) : new DefaultCosHttpClient(config);
            try {
                HttpClient network = mock(HttpClient.class);
                client.httpClient = network;
                when(network.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                        .thenReturn(response(200));
                client.executeOneRequest(new BasicHttpContext(), new HttpGet("http://" + HOST));
                verify(network).execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class));
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    public void threadPoolTimeoutMarksOnlyOnce() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        config.setRequestTimeout(200);
        TimeOutCosHttpClient client = new TimeOutCosHttpClient(config);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            HttpClient network = mock(HttpClient.class);
            client.httpClient = network;
            when(network.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenAnswer(call -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        } finally {
                            finished.countDown();
                        }
                        return response(200);
                    });
            HttpGet request = new HttpGet("http://" + HOST);
            try {
                client.executeOneRequest(new BasicHttpContext(), request);
                fail("expected timeout");
            } catch (TimeoutException expected) {
                assertTrue(request.isAborted());
                assertTrue(finished.await(2, TimeUnit.SECONDS));
                Object state = ((Map<?, ?>) get(entry, "health")).get(first);
                assertNotNull(state);
                assertEquals(TimeUnit.SECONDS.toNanos(5), get(state, "cooldown"));
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void callerCancellationDoesNotCoolNode() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        config.setRequestTimeout(5000);
        TimeOutCosHttpClient client = new TimeOutCosHttpClient(config);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            HttpClient network = mock(HttpClient.class);
            client.httpClient = network;
            when(network.execute(any(HttpHost.class), any(HttpRequestBase.class), any(HttpContext.class)))
                    .thenAnswer(call -> {
                        started.countDown();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        } finally {
                            finished.countDown();
                        }
                        return response(200);
                    });
            Future<?> task = caller.submit(() -> {
                try {
                    client.executeOneRequest(new BasicHttpContext(), new HttpGet("http://" + HOST));
                } catch (Exception expected) {
                    // 主动取消应结束等待，不标记节点故障。
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            task.cancel(true);
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(first, entry.pick(Arrays.asList(first)));
        } finally {
            caller.shutdownNow();
            client.shutdown();
        }
    }
}
