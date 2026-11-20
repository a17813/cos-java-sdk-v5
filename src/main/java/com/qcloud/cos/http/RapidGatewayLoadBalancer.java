package com.qcloud.cos.http;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.internal.CosClientAbortTaskMonitor;
import com.qcloud.cos.utils.EndpointUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.util.InetAddressUtils;
import org.apache.http.protocol.HttpContext;

/** 高性能桶网关的请求级选点与节点冷却，不增加内部重试。 */
final class RapidGatewayLoadBalancer {
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long DNS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long BASE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long FAILURE_RESET_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final String SELECTION = RapidGatewayLoadBalancer.class.getName() + ".selection";
    static final String ABORT_MONITOR = RapidGatewayLoadBalancer.class.getName() + ".abortMonitor";
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "cos-rapid-gateway-dns");
                thread.setDaemon(true);
                return thread;
            });

    static {
        DNS_EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private RapidGatewayLoadBalancer() {
    }

    static HttpResponse execute(HttpClient client, ClientConfig config,
            HttpRequestBase request, HttpContext context) throws IOException {
        context.removeAttribute(SELECTION);
        if (!eligible(request, config)) {
            return client.execute(request, context);
        }
        Header hostHeader = request.getFirstHeader("Host");
        String rawHost = request.getURI().getRawAuthority();
        // DNS、缓存和节点健康状态均以实际连接域名为准。
        String host = canonicalHost(request.getURI().getHost());
        int dot = host.indexOf('.');
        if (dot < 0 || !EndpointUtils.isRapidBucket(host.substring(0, dot))) {
            return client.execute(request, context);
        }
        Entry entry = ENTRIES.computeIfAbsent(host, key -> new Entry());
        InetAddress node = entry.pick(entry.resolve(host));
        if (node == null) {
            return client.execute(request, context);
        }
        Selection selection = new Selection(entry, node);
        context.setAttribute(SELECTION, selection);
        if (hostHeader == null) {
            request.setHeader("Host", rawHost);
        }
        try {
            // 连接池按物理节点分路，原 URI、签名和 Host 保持不变。
            HttpResponse response = client.execute(new HttpHost(node.getHostAddress(), 80, "http"),
                    request, context);
            selection.complete(response != null && isNodeFailure(response), response != null);
            return response;
        } catch (IOException e) {
            CosClientAbortTaskMonitor monitor =
                    (CosClientAbortTaskMonitor) context.getAttribute(ABORT_MONITOR);
            boolean timedOut = monitor != null && monitor.hasTimeoutExpired();
            boolean canceled = request.isAborted() || Thread.currentThread().isInterrupted();
            // 连接池等待超时不代表节点故障；请求超时与主动取消分别处理。
            selection.complete(!(e instanceof ConnectionPoolTimeoutException)
                    && (timedOut || !canceled), false);
            throw e;
        } finally {
            if (hostHeader == null) {
                request.removeHeaders("Host");
            }
        }
    }

    static void timedOut(HttpContext context) {
        Selection selection = (Selection) context.getAttribute(SELECTION);
        if (selection != null) {
            selection.complete(true, false);
        }
    }

    private static boolean isNodeFailure(HttpResponse response) {
        if (response.getStatusLine() == null) {
            return false;
        }
        int status = response.getStatusLine().getStatusCode();
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    static boolean eligible(HttpRequestBase request, ClientConfig config) {
        URI uri = request.getURI();
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1
                || uri.getHost() == null || uri.getUserInfo() != null || request.isAborted()) {
            return false;
        }
        String host = uri.getHost();
        if (host.startsWith("[") || InetAddressUtils.isIPv4Address(host)
                || InetAddressUtils.isIPv6Address(host)) {
            return false;
        }
        if (config.getHttpProxyIp() != null || (request.getConfig() != null
                && request.getConfig().getProxy() != null)) {
            return false;
        }
        // 保守跳过代理场景，不依据 NO_PROXY 决定是否改写目标。
        try {
            for (String key : new String[] {"http_proxy", "HTTP_PROXY", "all_proxy", "ALL_PROXY"}) {
                String value = System.getenv(key);
                if (value != null && !value.isEmpty()) {
                    return false;
                }
            }
            String proxyHost = System.getProperty("http.proxyHost");
            String socksHost = System.getProperty("socksProxyHost");
            return (proxyHost == null || proxyHost.isEmpty())
                    && (socksHost == null || socksHost.isEmpty())
                    && !Boolean.getBoolean("java.net.useSystemProxies");
        } catch (SecurityException e) {
            return false;
        }
    }

    static String canonicalHost(String host) {
        if (host == null) {
            return "";
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static final class Selection {
        private final Entry entry;
        private final InetAddress node;
        private final AtomicBoolean completed = new AtomicBoolean();

        Selection(Entry entry, InetAddress node) {
            this.entry = entry;
            this.node = node;
        }

        void complete(boolean failed, boolean responded) {
            if (completed.compareAndSet(false, true)) {
                if (failed) {
                    entry.markFailed(node);
                } else if (responded) {
                    entry.markSucceeded(node);
                }
            }
        }
    }

    static final class Entry {
        private List<InetAddress> nodes = Collections.emptyList();
        private long nextRefresh;
        private boolean resolving;
        private final Map<InetAddress, Health> health = new HashMap<>();

        synchronized List<InetAddress> resolve(final String host) {
            long now = System.nanoTime();
            if (!resolving && (nextRefresh == 0 || now - nextRefresh >= 0)) {
                resolving = true;
                try {
                    DNS_EXECUTOR.execute(() -> refresh(host));
                } catch (RejectedExecutionException e) {
                    resolving = false;
                    nextRefresh = now + REFRESH_NANOS;
                }
            }
            return nodes;
        }

        private void refresh(String host) {
            List<InetAddress> resolved = Collections.emptyList();
            long start = System.nanoTime();
            try {
                // JVM DNS 不保证可中断；限制任务总量，丢弃超时结果，不修改全局 DNS 缓存。
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (System.nanoTime() - start <= DNS_TIMEOUT_NANOS) {
                    LinkedHashSet<InetAddress> unique = new LinkedHashSet<>();
                    for (InetAddress address : addresses) {
                        if (address != null) {
                            unique.add(address);
                        }
                    }
                    resolved = new ArrayList<>(unique);
                }
            } catch (IOException | SecurityException e) {
                // 解析失败时保留旧快照，并限制下一次解析频率。
            } finally {
                synchronized (this) {
                    if (!resolved.isEmpty()) {
                        nodes = Collections.unmodifiableList(resolved);
                        health.keySet().retainAll(resolved);
                    }
                    nextRefresh = System.nanoTime() + REFRESH_NANOS;
                    resolving = false;
                }
            }
        }

        synchronized InetAddress pick(List<InetAddress> snapshot) {
            long now = System.nanoTime();
            InetAddress selected = null;
            int count = 0;
            for (InetAddress node : snapshot) {
                Health state = health.get(node);
                if (state == null || now - state.until >= 0) {
                    if (ThreadLocalRandom.current().nextInt(++count) == 0) {
                        selected = node;
                    }
                }
            }
            return selected;
        }

        synchronized void markFailed(InetAddress node) {
            // 刷新已摘除的节点不再写入健康记录。
            if (!nodes.contains(node)) {
                return;
            }
            long now = System.nanoTime();
            Health state = health.get(node);
            if (state == null) {
                state = new Health();
                health.put(node, state);
            }
            if (state.cooldown == 0 || now - state.lastFailure > FAILURE_RESET_NANOS) {
                state.cooldown = BASE_COOLDOWN_NANOS;
            } else {
                state.cooldown = Math.min(state.cooldown * 2, MAX_COOLDOWN_NANOS);
            }
            state.lastFailure = now;
            state.until = now + state.cooldown;
        }

        synchronized void markSucceeded(InetAddress node) {
            Health state = health.get(node);
            if (state != null) {
                // 并发成功不提前解除其他请求已触发的冷却。
                state.cooldown = 0;
                state.lastFailure = 0;
            }
        }
    }

    private static final class Health {
        private long until;
        private long lastFailure;
        private long cooldown;
    }
}
