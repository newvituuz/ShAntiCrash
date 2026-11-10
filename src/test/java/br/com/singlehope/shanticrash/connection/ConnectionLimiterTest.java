package br.com.singlehope.shanticrash.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLimiterTest {
    private static InetAddress ip(String value) throws Exception {
        return InetAddress.getByName(value);
    }

    private static ConnectionLimiter limiter(int max, boolean loopback, List<String> trusted, String proxy) throws Exception {
        HashSet<InetAddress> set = new HashSet<>();
        for (String entry : trusted) {
            set.add(ip(entry));
        }
        ConnectionLimiter limiter = new ConnectionLimiter();
        limiter.settings(new ConnectionLimiter.Settings(max, loopback, set, proxy));
        return limiter;
    }

    @Test
    void limitsConcurrentConnectionsPerAddressAndReleases() throws Exception {
        ConnectionLimiter limiter = limiter(2, true, Collections.<String>emptyList(), null);
        InetAddress a = ip("203.0.113.7");
        assertEquals(ConnectionLimiter.Result.ALLOWED, limiter.acquire(a));
        assertEquals(ConnectionLimiter.Result.ALLOWED, limiter.acquire(a));
        assertEquals(ConnectionLimiter.Result.REJECTED, limiter.acquire(a));
        assertEquals(ConnectionLimiter.Result.ALLOWED, limiter.acquire(ip("203.0.113.8")));
        limiter.release(a);
        assertEquals(ConnectionLimiter.Result.ALLOWED, limiter.acquire(a));
        limiter.release(a);
        limiter.release(a);
        assertEquals(0, limiter.count(a));
        limiter.release(a);
        assertEquals(0, limiter.count(a));
        assertEquals(1, limiter.trackedAddresses());
    }

    @Test
    void loopbackAndTrustedProxiesAreNotCounted() throws Exception {
        ConnectionLimiter limiter = limiter(1, true, Collections.singletonList("198.51.100.1"), null);
        for (int i = 0; i < 10; i++) {
            assertEquals(ConnectionLimiter.Result.UNTRACKED, limiter.acquire(ip("127.0.0.1")));
            assertEquals(ConnectionLimiter.Result.UNTRACKED, limiter.acquire(ip("198.51.100.1")));
        }
        ConnectionLimiter strict = limiter(1, false, Collections.<String>emptyList(), null);
        assertEquals(ConnectionLimiter.Result.ALLOWED, strict.acquire(ip("127.0.0.1")));
        assertEquals(ConnectionLimiter.Result.REJECTED, strict.acquire(ip("127.0.0.1")));
    }

    @Test
    void behindAProxyWithoutTrustedAddressesTheLimitIsOff() throws Exception {
        ConnectionLimiter proxied = limiter(1, false, Collections.<String>emptyList(), "Velocity");
        assertFalse(proxied.settings().active());
        for (int i = 0; i < 5; i++) {
            assertEquals(ConnectionLimiter.Result.UNTRACKED, proxied.acquire(ip("10.0.0.5")));
        }
        ConnectionLimiter withTrusted = limiter(1, false, Collections.singletonList("10.0.0.5"), "Velocity");
        assertTrue(withTrusted.settings().active());
        assertEquals(ConnectionLimiter.Result.UNTRACKED, withTrusted.acquire(ip("10.0.0.5")));
        assertEquals(ConnectionLimiter.Result.ALLOWED, withTrusted.acquire(ip("203.0.113.9")));
        assertEquals(ConnectionLimiter.Result.REJECTED, withTrusted.acquire(ip("203.0.113.9")));
    }

    @Test
    void zeroDisablesAndNullAddressIsSafe() throws Exception {
        ConnectionLimiter off = limiter(0, false, Collections.<String>emptyList(), null);
        assertEquals(ConnectionLimiter.Result.UNTRACKED, off.acquire(ip("203.0.113.1")));
        assertEquals(ConnectionLimiter.Result.UNTRACKED, limiter(3, false, Collections.<String>emptyList(), null).acquire(null));
        off.release(null);
    }

    @Test
    void concurrentAcquiresNeverExceedTheLimit() throws Exception {
        ConnectionLimiter limiter = limiter(25, false, Collections.<String>emptyList(), null);
        InetAddress a = ip("203.0.113.50");
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[16];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 100; i++) {
                    if (limiter.acquire(a) == ConnectionLimiter.Result.ALLOWED) {
                        allowed.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(25, allowed.get());
        assertEquals(25, limiter.count(a));
    }

    @Test
    void detectsProxyModesFromServerFiles(@TempDir Path root) throws Exception {
        assertNull(ProxyDetector.detect(root.toFile()));
        Files.write(root.resolve("spigot.yml"), "settings:\n  bungeecord: true\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("BungeeCord", ProxyDetector.detect(root.toFile()));
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve("config/paper-global.yml"),
                "proxies:\n  velocity:\n    enabled: true\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Velocity", ProxyDetector.detect(root.toFile()));
        Files.write(root.resolve("config/paper-global.yml"), "proxies: [quebrado".getBytes(StandardCharsets.UTF_8));
        assertEquals("BungeeCord", ProxyDetector.detect(root.toFile()));
    }
}
