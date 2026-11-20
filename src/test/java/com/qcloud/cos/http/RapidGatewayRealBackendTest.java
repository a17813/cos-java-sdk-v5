package com.qcloud.cos.http;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.endpoint.EndpointResolver;
import com.qcloud.cos.model.CreateSessionRequest;
import com.qcloud.cos.model.CreateSessionResult;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.rapid.AbstractRapidCOSClientTest;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.Md5Utils;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.qcloud.cos.http.RapidGatewayLoadBalancerTest.get;
import static com.qcloud.cos.http.RapidGatewayLoadBalancerTest.registry;
import static com.qcloud.cos.http.RapidGatewayLoadBalancerTest.seed;
import static com.qcloud.cos.http.RapidGatewayLoadBalancerTest.set;
import static com.qcloud.cos.http.RapidGatewayLoadBalancerIntegrationTest.mapSocketRoutes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 连接真实网关验证域名解析、冷启动回退、已有节点选点及故障重试。 */
public class RapidGatewayRealBackendTest extends AbstractRapidCOSClientTest {
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        Assume.assumeTrue("Requires hosts mode or a local gateway on port 80",
                isHostsMode() || System.getenv("rapidLoadBalanceGateway") != null);
        initRapidCosClient();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        destroyRapidCosClient();
    }

    @Test
    public void testHostsResolutionReachesRealGateway() throws Exception {
        Assume.assumeTrue("Requires rapidHostsMode", isHostsMode());
        skipIfNotRapid();
        String host = clientConfig.getEndpointBuilder().buildGeneralApiEndpoint(bucket);
        String expectedIp = System.getenv("rapidHostsGatewayIp");
        assertNotNull("rapidHostsGatewayIp must be configured", expectedIp);
        assertTrue("rapidHostsGatewayIp must be an IPv4 literal",
                org.apache.http.conn.util.InetAddressUtils.isIPv4Address(expectedIp));
        InetAddress expected = InetAddress.getByName(expectedIp);
        assertEquals(Collections.singletonList(expected), Arrays.asList(InetAddress.getAllByName(host)));
        for (boolean timeoutClient : new boolean[] {false, true}) {
            registry().remove(host);
            ClientConfig config = new ClientConfig(new Region(region));
            config.setHttpProtocol(HttpProtocol.http);
            config.setIsDistinguishHost(true);
            config.setConnectionTimeout(2000);
            config.setSocketTimeout(5000);
            config.setRequestTimeout(10000);
            config.setRequestTimeOutEnable(timeoutClient);
            config.setMaxErrorRetry(0);
            COSClient client = new COSClient(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials(secretId, secretKey)), config);
            File file = null;
            File download = null;
            String key = "ut/hosts-dns-" + UUID.randomUUID();
            boolean uploaded = false;
            try {
                if (timeoutClient) {
                    DefaultCosHttpClient replacement = new TimeOutCosHttpClient(config);
                    try {
                        ((DefaultCosHttpClient) get(client, "cosHttpClient")).shutdown();
                        set(client, "cosHttpClient", replacement);
                    } catch (Exception e) {
                        replacement.shutdown();
                        throw e;
                    }
                }
                assertNotNull(client.createSession(new CreateSessionRequest(bucket))
                        .getCredentials().getSessionToken());
                RapidGatewayLoadBalancer.Entry entry = registry().get(host);
                assertNotNull("The domain request must enter the load balancer", entry);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    synchronized (entry) {
                        if (!((java.util.List<?>) get(entry, "nodes")).isEmpty()) {
                            break;
                        }
                    }
                    Thread.sleep(10);
                }
                synchronized (entry) {
                    assertEquals("SDK must discover the hosts address without node injection",
                            Collections.singletonList(expected), get(entry, "nodes"));
                }
                file = buildTestFile(1024);
                download = new File(file.getAbsolutePath() + ".down");
                client.putObject(new PutObjectRequest(bucket, key, file));
                uploaded = true;
                client.getObject(new GetObjectRequest(bucket, key), download);
                assertEquals(Md5Utils.md5Hex(file), Md5Utils.md5Hex(download));
                assertEquals(1024, client.getObjectMetadata(bucket, key).getContentLength());
                client.deleteObject(bucket, key);
                uploaded = false;
                System.out.println("Hosts DNS verified: " + host + ", timeoutClient=" + timeoutClient);
            } finally {
                try {
                    if (uploaded) {
                        cosclient.deleteObject(bucket, key);
                    }
                } finally {
                    client.shutdown();
                    registry().remove(host);
                    if (file != null && file.exists()) assertTrue(file.delete());
                    if (download != null && download.exists()) assertTrue(download.delete());
                }
            }
        }
    }

    @Test
    public void testCustomDomainBypassesLoadBalancer() throws Exception {
        checkRealRequests(false, false);
    }

    @Test
    public void testCachedNodeRoutesToRealGateway() throws Exception {
        checkRealRequests(true, false);
    }

    @Test
    public void testIpEndpointBypassesLoadBalancer() throws Exception {
        checkRealRequests(false, true);
    }

    @Test
    public void test503RetryReachesRealGatewayOnBothClients() throws Exception {
        Assume.assumeTrue("Requires local gateway mode",
                !isHostsMode() && System.getenv("rapidLoadBalanceGateway") != null);
        skipIfNotRapid();
        URL gateway = new URL("http://" + System.getenv("rapidLoadBalanceGateway"));
        assertTrue("Load balancing requires port 80", gateway.getPort() == -1 || gateway.getPort() == 80);
        String host = clientConfig.getEndpointBuilder().buildGeneralApiEndpoint(bucket);
        InetAddress healthy = InetAddress.getByName(gateway.getHost());
        InetAddress failed = InetAddress.getByName("127.0.0.2");
        assertTrue("Fault node and real gateway must differ", !failed.equals(healthy));
        for (boolean timeoutClient : new boolean[] {false, true}) {
            ClientConfig config = new ClientConfig(new Region(region));
            config.setHttpProtocol(HttpProtocol.http);
            config.setIsDistinguishHost(true);
            config.setConnectionTimeout(2000);
            config.setSocketTimeout(5000);
            config.setRequestTimeout(10000);
            config.setRequestTimeOutEnable(true);
            config.setMaxErrorRetry(1);
            COSClient client = new COSClient(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials(secretId, secretKey)), config);
            HttpServer faultServer = null;
            File file = null;
            File download = null;
            String key = "ut/real-lb-retry-" + UUID.randomUUID();
            boolean uploaded = false;
            AtomicInteger failures = new AtomicInteger();
            try {
                if (timeoutClient) {
                    DefaultCosHttpClient replacement = new TimeOutCosHttpClient(config);
                    try {
                        ((DefaultCosHttpClient) get(client, "cosHttpClient")).shutdown();
                        set(client, "cosHttpClient", replacement);
                    } catch (Exception e) {
                        replacement.shutdown();
                        throw e;
                    }
                }
                RapidGatewayLoadBalancer.Entry entry = seed(host, healthy);
                faultServer = HttpServer.create(new InetSocketAddress(failed, 0), 0);
                faultServer.createContext("/", exchange -> {
                    failures.incrementAndGet();
                    try {
                        synchronized (entry) {
                            try {
                                set(entry, "nodes", Arrays.asList(failed, healthy));
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                        }
                        byte[] error = "<Error><Code>SlowDown</Code><Message>test fault</Message></Error>"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/xml");
                        exchange.getResponseHeaders().set("x-cos-request-id", "java-rapid-fault");
                        exchange.sendResponseHeaders(503, error.length);
                        exchange.getResponseBody().write(error);
                    } finally {
                        exchange.close();
                    }
                });
                faultServer.start();
                Map<InetAddress, InetSocketAddress> routes = new HashMap<>();
                routes.put(failed, faultServer.getAddress());
                routes.put(healthy, new InetSocketAddress(healthy, 80));
                mapSocketRoutes((DefaultCosHttpClient) get(client, "cosHttpClient"), routes);
                file = buildTestFile(1024);
                download = new File(file.getAbsolutePath() + ".down");
                client.putObject(new PutObjectRequest(bucket, key, file));
                uploaded = true;
                synchronized (entry) {
                    set(entry, "nodes", Collections.singletonList(failed));
                }
                client.getObject(new GetObjectRequest(bucket, key), download);
                assertEquals("The first attempt must hit the fault node", 1, failures.get());
                assertEquals(Md5Utils.md5Hex(file), Md5Utils.md5Hex(download));
                assertNull("503 must cool the failed node", entry.pick(Collections.singletonList(failed)));
                assertEquals(1024, client.getObjectMetadata(bucket, key).getContentLength());
                assertEquals("Later requests must avoid the cooled node", 1, failures.get());
            } finally {
                try {
                    if (uploaded) {
                        cosclient.deleteObject(bucket, key);
                    }
                } finally {
                    client.shutdown();
                    if (faultServer != null) faultServer.stop(0);
                    registry().remove(host);
                    if (file != null && file.exists()) assertTrue(file.delete());
                    if (download != null && download.exists()) assertTrue(download.delete());
                }
            }
        }
    }

    private void checkRealRequests(boolean cachedNode, boolean ipEndpoint) throws Exception {
        Assume.assumeTrue("Requires local gateway mode",
                !isHostsMode() && System.getenv("rapidLoadBalanceGateway") != null);
        skipIfNotRapid();
        URL gateway = new URL("http://" + System.getenv("rapidLoadBalanceGateway"));
        assertTrue("Load balancing requires port 80", gateway.getPort() == -1 || gateway.getPort() == 80);
        String host = clientConfig.getEndpointBuilder().buildGeneralApiEndpoint(bucket);
        InetAddress node = InetAddress.getByName(gateway.getHost());
        for (boolean requestTimeout : new boolean[] {false, true}) {
            registry().remove(host);
            if (cachedNode) {
                seed(host, node);
            }
            ClientConfig config = new ClientConfig(new Region(region));
            config.setHttpProtocol(HttpProtocol.http);
            config.setIsDistinguishHost(true);
            config.setConnectionTimeout(2000);
            config.setSocketTimeout(5000);
            config.setRequestTimeout(10000);
            config.setRequestTimeOutEnable(requestTimeout);
            config.setMaxErrorRetry(0);
            config.setEndpointResolver(new EndpointResolver() {
                @Override
                public String resolveGeneralApiEndpoint(String endpoint) {
                    if (ipEndpoint) {
                        String address = node.getHostAddress();
                        return address.contains(":") ? "[" + address + "]" : address;
                    }
                    // 已有节点模式保留桶域名，自定义域名模式验证原连接地址直通。
                    return cachedNode ? endpoint : "localhost";
                }

                @Override
                public String resolveGetServiceApiEndpoint(String endpoint) {
                    return localProxyAddr;
                }
            });
            COSClient client = new COSClient(new RapidCOSCredentialProvider(
                    new BasicCOSCredentials(secretId, secretKey)), config);
            File file = buildTestFile(1024);
            File download = new File(file.getAbsolutePath() + ".down");
            String key = "ut/real-lb-" + UUID.randomUUID();
            boolean uploaded = false;
            try {
                if (!cachedNode && !ipEndpoint) {
                    // 仅映射 TCP 连接，保留 URL 域名及标准桶 Host。
                    Map<InetAddress, InetSocketAddress> routes = new HashMap<>();
                    for (InetAddress address : InetAddress.getAllByName("localhost")) {
                        routes.put(address, new InetSocketAddress(node, 80));
                    }
                    routes.put(node, new InetSocketAddress(node, 80));
                    mapSocketRoutes((DefaultCosHttpClient) get(client, "cosHttpClient"), routes);
                }
                if (!cachedNode) {
                    CreateSessionResult session = client.createSession(new CreateSessionRequest(bucket));
                    assertNotNull(session.getCredentials().getSessionToken());
                    assertNull("An explicit IP or different domain must bypass the load balancer",
                            registry().get(host));
                }
                client.putObject(new PutObjectRequest(bucket, key, file));
                uploaded = true;
                if (cachedNode) {
                    assertNotNull("The bucket-domain request must enter the load balancer", registry().get(host));
                } else {
                    assertNull("An explicit endpoint must bypass the load balancer", registry().get(host));
                }
                client.getObject(new GetObjectRequest(bucket, key), download);
                assertEquals(Md5Utils.md5Hex(file), Md5Utils.md5Hex(download));
                client.deleteObject(bucket, key);
                uploaded = false;
                if (!cachedNode) {
                    assertNull("Explicit endpoint requests must not populate the bucket cache", registry().get(host));
                }
            } finally {
                try {
                    if (uploaded) {
                        cosclient.deleteObject(bucket, key);
                    }
                } finally {
                    client.shutdown();
                    registry().remove(host);
                    if (file.exists()) assertTrue(file.delete());
                    if (download.exists()) assertTrue(download.delete());
                }
            }
        }
    }
}