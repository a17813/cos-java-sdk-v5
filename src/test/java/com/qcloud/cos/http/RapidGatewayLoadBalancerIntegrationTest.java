package com.qcloud.cos.http;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.region.Region;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.qcloud.cos.http.RapidGatewayLoadBalancerTest.*;

public class RapidGatewayLoadBalancerIntegrationTest {
    private HttpServer firstServer;
    private HttpServer secondServer;
    private ExecutorService workers;
    private InetAddress first;
    private InetAddress second;
    private final AtomicInteger firstHits = new AtomicInteger();
    private final AtomicInteger secondHits = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();
    private final AtomicInteger objects = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
    private volatile boolean failFirst;
    private volatile boolean slowFirst;
    private volatile byte[] expectedBody;
    private Socket unavailableSocket;
    private final Map<InetAddress, InetSocketAddress> socketRoutes = new HashMap<>();

    @Before
    public void startServers() throws Exception {
        registry().clear();
        first = InetAddress.getByName("127.0.0.2");
        second = InetAddress.getByName("127.0.0.3");
        workers = Executors.newCachedThreadPool();
        try {
            firstServer = server(first, firstHits, true);
            secondServer = server(second, secondHits, false);
            socketRoutes.put(first, firstServer.getAddress());
            socketRoutes.put(second, secondServer.getAddress());
            unavailableSocket = new Socket();
            InetAddress unavailable = InetAddress.getByName("127.0.0.4");
            unavailableSocket.bind(new InetSocketAddress(unavailable, 0));
            socketRoutes.put(unavailable, (InetSocketAddress) unavailableSocket.getLocalSocketAddress());
        } catch (Exception e) {
            stopServers();
            throw e;
        }
    }

    private HttpServer server(InetAddress address, AtomicInteger hits, boolean isFirst) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> handle(exchange, hits, isFirst));
        server.start();
        return server;
    }

    private void handle(HttpExchange exchange, AtomicInteger hits, boolean isFirst) throws IOException {
        hits.incrementAndGet();
        try {
            if (!HOST.equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Host"))) {
                errors.add("Host changed: " + exchange.getRequestHeaders().getFirst("Host"));
            }
            byte[] body = readAll(exchange.getRequestBody());
            if (expectedBody != null && !Arrays.equals(expectedBody, body)) {
                errors.add("request body changed");
            }
            if (isFirst && slowFirst) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = isFirst && failFirst ? 503 : 200;
            String query = exchange.getRequestURI().getRawQuery();
            String reply = "ok";
            if (query != null && query.contains("session")) {
                sessions.incrementAndGet();
                if (!exchange.getRequestHeaders().getFirst("Authorization").contains("q-ak=baseAK&")) {
                    errors.add("CreateSession did not use base credential");
                }
                reply = "<CreateSessionResult><Credentials><AccessKeyId>sessionAK</AccessKeyId>"
                        + "<SecretAccessKey>sessionSK</SecretAccessKey><SessionToken>session-token</SessionToken>"
                        + "<Expiration>" + Instant.now().plusSeconds(600) + "</Expiration>"
                        + "</Credentials></CreateSessionResult>";
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                objects.incrementAndGet();
                if (!exchange.getRequestHeaders().getFirst("Authorization").contains("q-ak=sessionAK&")
                        || !"session-token".equals(exchange.getRequestHeaders().getFirst("x-cos-security-token"))) {
                    errors.add("object request did not preserve session credential");
                }
                reply = status == 200 ? "" : "<Error><Code>SlowDown</Code><Message>retry</Message></Error>";
                exchange.getResponseHeaders().add("x-cos-request-id", "local-lb-test");
            } else if (!"unchanged".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                errors.add("Authorization changed");
            }
            if (exchange.getRequestURI().getRawPath().startsWith("/a%2F")) {
                if (!"q=%2B&token=a%2Fb".equals(query)) {
                    errors.add("query changed: " + query);
                }
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** 仅映射测试 TCP 地址，保留 SDK 原始连接池、请求和重试配置。 */
    @SuppressWarnings("deprecation")
    static void mapSocketRoutes(DefaultCosHttpClient client,
            Map<InetAddress, InetSocketAddress> routes) {
        Map<InetAddress, InetSocketAddress> snapshot = new HashMap<>(routes);
        Registry<ConnectionSocketFactory> factories = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", new PlainConnectionSocketFactory() {
                    @Override
                    public Socket connectSocket(int timeout, Socket socket, HttpHost host,
                            InetSocketAddress remote, InetSocketAddress local, HttpContext context)
                            throws IOException {
                        InetSocketAddress target = snapshot.get(remote.getAddress());
                        if (remote.getPort() != 80 || target == null) {
                            throw new IOException("Unexpected test route: " + remote);
                        }
                        return super.connectSocket(timeout, socket, host, target, local, context);
                    }
                }).build();
        CloseableHttpClient original = (CloseableHttpClient) client.httpClient;
        client.httpClient = new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request,
                    HttpContext context) throws IOException {
                HttpContext actual = context == null ? new BasicHttpContext() : context;
                actual.setAttribute("http.socket-factory-registry", factories);
                return original.execute(target, request, actual);
            }

            @Override
            public HttpParams getParams() {
                return original.getParams();
            }

            @Override
            public ClientConnectionManager getConnectionManager() {
                return original.getConnectionManager();
            }

            @Override
            public void close() throws IOException {
                original.close();
            }
        };
    }

    private DefaultCosHttpClient mappedClient(ClientConfig config) {
        DefaultCosHttpClient client = new DefaultCosHttpClient(config);
        mapSocketRoutes(client, socketRoutes);
        return client;
    }

    private COSClient mappedSdkClient() throws Exception {
        COSClient client = new COSClient(new RapidCOSCredentialProvider(
                new BasicCOSCredentials("baseAK", "baseSK")), config());
        try {
            mapSocketRoutes((DefaultCosHttpClient) get(client, "cosHttpClient"), socketRoutes);
            return client;
        } catch (Exception e) {
            client.shutdown();
            throw e;
        }
    }

    @After
    public void stopServers() throws Exception {
        if (unavailableSocket != null) {
            unavailableSocket.close();
        }
        if (firstServer != null) {
            firstServer.stop(0);
        }
        if (secondServer != null) {
            secondServer.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
            workers.awaitTermination(3, TimeUnit.SECONDS);
        }
        registry().clear();
    }

    private ClientConfig config() {
        ClientConfig config = new ClientConfig(new Region("ap-guangzhou"));
        config.setHttpProtocol(HttpProtocol.http);
        config.setConnectionTimeout(1000);
        config.setSocketTimeout(2000);
        config.setMaxErrorRetry(1);
        config.setRequestTimeout(2000);
        return config;
    }

    @Test
    public void realPoolDistributesAndPreservesEncodedRequestAndBody() throws Exception {
        seed(HOST, first, second);
        expectedBody = new byte[8192];
        for (int i = 0; i < expectedBody.length; i++) {
            expectedBody[i] = (byte) i;
        }
        DefaultCosHttpClient client = mappedClient(config());
        try {
            for (int i = 0; i < 80; i++) {
                HttpPut request = new HttpPut("http://" + HOST + "/a%2Fb?q=%2B&token=a%2Fb");
                request.setHeader("Authorization", "unchanged");
                request.setEntity(new ByteArrayEntity(expectedBody));
                HttpResponse response = client.executeOneRequest(new BasicHttpContext(), request);
                assertEquals(200, response.getStatusLine().getStatusCode());
                EntityUtils.consume(response.getEntity());
                assertEquals(HOST, request.getURI().getHost());
            }
            assertTrue(firstHits.get() > 15);
            assertTrue(secondHits.get() > 15);
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void connectionDomainSelectsItsOwnNodesOnBothClients() throws Exception {
        String endpoint = "connect-x--123.cosrapid.ap-guangzhou.myqcloud.com";
        for (boolean timeoutClient : new boolean[] {false, true}) {
            seed(endpoint, first);
            seed(HOST, second);
            firstHits.set(0);
            secondHits.set(0);
            ClientConfig config = config();
            DefaultCosHttpClient client = timeoutClient
                    ? new TimeOutCosHttpClient(config) : new DefaultCosHttpClient(config);
            mapSocketRoutes(client, socketRoutes);
            try {
                HttpGet request = new HttpGet("http://" + endpoint + "/a%2Fb?q=%2B&token=a%2Fb");
                request.setHeader("Host", HOST);
                request.setHeader("Authorization", "unchanged");
                HttpResponse response = client.executeOneRequest(new BasicHttpContext(), request);
                assertEquals(200, response.getStatusLine().getStatusCode());
                EntityUtils.consume(response.getEntity());
                assertEquals(1, firstHits.get());
                assertEquals(0, secondHits.get());
                assertEquals(endpoint, request.getURI().getHost());
                assertEquals(HOST, request.getFirstHeader("Host").getValue());
                assertTrue(errors.toString(), errors.isEmpty());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    public void real503CoolsNodeAndNextRequestUsesOtherNode() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        DefaultCosHttpClient client = mappedClient(config());
        try {
            failFirst = true;
            HttpGet request = new HttpGet("http://" + HOST + "/key");
            request.setHeader("Authorization", "unchanged");
            HttpResponse response = client.executeOneRequest(new BasicHttpContext(), request);
            assertEquals(503, response.getStatusLine().getStatusCode());
            EntityUtils.consume(response.getEntity());
            set(entry, "nodes", Arrays.asList(first, second));
            for (int i = 0; i < 5; i++) {
                request = new HttpGet("http://" + HOST + "/key");
                request.setHeader("Authorization", "unchanged");
                response = client.executeOneRequest(new BasicHttpContext(), request);
                assertEquals(200, response.getStatusLine().getStatusCode());
                EntityUtils.consume(response.getEntity());
            }
            assertEquals(1, firstHits.get());
            assertEquals(5, secondHits.get());
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void sessionAndObjectRequestsShareBalancerAndPresignStaysLogical() throws Exception {
        seed(HOST, first, second);
        COSClient client = mappedSdkClient();
        try {
            for (int i = 0; i < 30; i++) {
                client.deleteObject("test-x--123", "key");
            }
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("test-x--123", "key");
            request.setExpiration(new Date(System.currentTimeMillis() + 60000));
            URL url = client.generatePresignedUrl(request);
            assertEquals(HOST, url.getHost());
            assertTrue(url.getQuery().contains("session-token"));
            assertEquals(1, sessions.get());
            assertEquals(30, objects.get());
            assertTrue(firstHits.get() > 0);
            assertTrue(secondHits.get() > 0);
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void explicitConnectionDomainSurvivesCacheAndRetryOnBothClients() throws Exception {
        for (InetAddress address : InetAddress.getAllByName("localhost")) {
            socketRoutes.put(address, firstServer.getAddress());
        }
        for (boolean timeoutClient : new boolean[] {false, true}) {
            for (boolean fixedEndpoint : new boolean[] {false, true}) {
                for (boolean cached : new boolean[] {false, true}) {
                    failFirst = false;
                    seed(HOST, first);
                    ClientConfig config = config();
                    config.setRequestTimeOutEnable(true);
                    COSClient client = new COSClient(new RapidCOSCredentialProvider(
                            new BasicCOSCredentials("baseAK", "baseSK")), config);
                    try {
                        if (timeoutClient) {
                            DefaultCosHttpClient replacement = new TimeOutCosHttpClient(config);
                            ((DefaultCosHttpClient) get(client, "cosHttpClient")).shutdown();
                            set(client, "cosHttpClient", replacement);
                        }
                        mapSocketRoutes((DefaultCosHttpClient) get(client, "cosHttpClient"), socketRoutes);
                        client.deleteObject("test-x--123", "warmup");
                        firstHits.set(0);
                        secondHits.set(0);
                        registry().clear();
                        if (cached) {
                            seed(HOST, second);
                        }
                        if (!fixedEndpoint) {
                            config.setEndpointResolver(new com.qcloud.cos.endpoint.EndpointResolver() {
                                @Override
                                public String resolveGeneralApiEndpoint(String endpoint) {
                                    return "localhost";
                                }

                                @Override
                                public String resolveGetServiceApiEndpoint(String endpoint) {
                                    return endpoint;
                                }
                            });
                        }
                        com.qcloud.cos.model.DeleteObjectRequest request =
                                new com.qcloud.cos.model.DeleteObjectRequest("test-x--123", "key");
                        if (fixedEndpoint) {
                            request.setFixedEndpointAddr("localhost");
                        }
                        client.deleteObject(request);
                        assertEquals("Explicit endpoint must receive the request", 1, firstHits.get());
                        assertEquals("Bucket node must not receive the request", 0, secondHits.get());
                        failFirst = true;
                        try {
                            client.deleteObject(request);
                            fail("Expected the explicit endpoint's 503, not bucket-node success");
                        } catch (com.qcloud.cos.exception.CosServiceException expected) {
                            assertEquals(503, expected.getStatusCode());
                        }
                        assertEquals("Retry must retain the explicit endpoint", 3, firstHits.get());
                        assertEquals(0, secondHits.get());
                        if (cached) {
                            assertEquals(second, registry().get(HOST).pick(Arrays.asList(second)));
                        } else {
                            assertTrue("Bypass must not populate bucket DNS cache", registry().isEmpty());
                        }
                        assertTrue(errors.toString(), errors.isEmpty());
                    } finally {
                        client.shutdown();
                        failFirst = false;
                        registry().clear();
                    }
                }
            }
        }
    }

    @Test
    public void sdkRetryChoosesHealthyNode() throws Exception {
        seed(HOST, first, second);
        COSClient client = mappedSdkClient();
        try {
            client.deleteObject("test-x--123", "warmup");
            RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
            failFirst = true;
            // 首次失败只可能到第一个节点，收到后第二个节点加入快照。
            ExecutorService publisher = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<?> published = publisher.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (entry.pick(Arrays.asList(first)) != null && System.nanoTime() < deadline) {
                        Thread.yield();
                    }
                    synchronized (entry) {
                        try {
                            set(entry, "nodes", Arrays.asList(first, second));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                client.deleteObject("test-x--123", "retry-key");
                published.get(5, TimeUnit.SECONDS);
                assertEquals(3, objects.get());
                assertTrue(errors.toString(), errors.isEmpty());
            } finally {
                publisher.shutdownNow();
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void realRequestTimerCoolsNodeOnBothClients() throws Exception {
        for (boolean timeoutClient : new boolean[] {false, true}) {
            RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
            slowFirst = true;
            ClientConfig config = config();
            config.setRequestTimeout(100);
            config.setRequestTimeOutEnable(true);
            config.setMaxErrorRetry(0);
            DefaultCosHttpClient client = timeoutClient
                    ? new TimeOutCosHttpClient(config) : new DefaultCosHttpClient(config);
            mapSocketRoutes(client, socketRoutes);
            try {
                com.qcloud.cos.model.GetObjectRequest original =
                        new com.qcloud.cos.model.GetObjectRequest("test-x--123", "key");
                CosHttpRequest<com.qcloud.cos.model.GetObjectRequest> request = new CosHttpRequest<>(original);
                request.setProtocol(HttpProtocol.http);
                request.setEndpoint(HOST);
                request.setResourcePath("/key");
                request.setHttpMethod(HttpMethodName.GET);
                request.addHeader("Authorization", "unchanged");
                try {
                    client.exeute(request, null);
                    fail("expected request timeout");
                } catch (com.qcloud.cos.exception.CosClientException expected) {
                    assertTrue(request.getClientAbortTaskMonitor().hasTimeoutExpired()
                            || expected.getCause() instanceof java.util.concurrent.TimeoutException);
                    assertNull(entry.pick(Arrays.asList(first)));
                }
            } finally {
                Thread.interrupted();
                client.shutdown();
            }
        }
    }

    @Test
    public void realConnectionFailureCoolsNode() throws Exception {
        InetAddress unavailable = InetAddress.getByName("127.0.0.4");
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, unavailable);
        DefaultCosHttpClient client = mappedClient(config());
        try {
            HttpGet request = new HttpGet("http://" + HOST + "/key");
            request.setConfig(RequestConfig.custom().setConnectTimeout(500).build());
            try {
                client.executeOneRequest(new BasicHttpContext(), request);
                fail("expected connection failure");
            } catch (java.net.ConnectException expected) {
                assertNull(entry.pick(Arrays.asList(unavailable)));
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void realSocketTimeoutCoolsNode() throws Exception {
        RapidGatewayLoadBalancer.Entry entry = seed(HOST, first);
        slowFirst = true;
        DefaultCosHttpClient client = mappedClient(config());
        try {
            HttpGet request = new HttpGet("http://" + HOST + "/key");
            request.setHeader("Authorization", "unchanged");
            request.setConfig(RequestConfig.custom().setSocketTimeout(100).setConnectTimeout(1000).build());
            try {
                client.executeOneRequest(new BasicHttpContext(), request);
                fail("expected socket timeout");
            } catch (java.net.SocketTimeoutException expected) {
                assertNull(entry.pick(Arrays.asList(first)));
            }
        } finally {
            client.shutdown();
        }
    }
}