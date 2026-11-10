package br.com.singlehope.shanticrash.connection;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionLimiter {
    public enum Result {
        UNTRACKED,
        ALLOWED,
        REJECTED
    }

    public static final class Settings {
        public static final Settings DISABLED = new Settings(0, true, Collections.<InetAddress>emptySet(), null);

        public final int maxPerAddress;
        public final boolean exemptLoopback;
        public final Set<InetAddress> trustedProxies;
        public final String proxy;

        public Settings(int maxPerAddress, boolean exemptLoopback, Set<InetAddress> trustedProxies, String proxy) {
            this.maxPerAddress = Math.max(0, maxPerAddress);
            this.exemptLoopback = exemptLoopback;
            this.trustedProxies = Collections.unmodifiableSet(trustedProxies);
            this.proxy = proxy;
        }

        public boolean active() {
            return maxPerAddress > 0 && (proxy == null || !trustedProxies.isEmpty());
        }
    }

    private final ConcurrentHashMap<InetAddress, Integer> counts = new ConcurrentHashMap<>();
    private volatile Settings settings = Settings.DISABLED;

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    public Result acquire(InetAddress address) {
        Settings active = settings;
        if (address == null || !active.active() || active.trustedProxies.contains(address)
                || (active.exemptLoopback && (address.isLoopbackAddress() || address.isAnyLocalAddress()))) {
            return Result.UNTRACKED;
        }
        int max = active.maxPerAddress;
        boolean[] rejected = new boolean[1];
        counts.compute(address, (key, current) -> {
            int value = current == null ? 0 : current;
            if (value >= max) {
                rejected[0] = true;
                return current;
            }
            return value + 1;
        });
        return rejected[0] ? Result.REJECTED : Result.ALLOWED;
    }

    public void release(InetAddress address) {
        if (address != null) {
            counts.computeIfPresent(address, (key, current) -> current <= 1 ? null : current - 1);
        }
    }

    public int count(InetAddress address) {
        Integer value = counts.get(address);
        return value == null ? 0 : value;
    }

    public int trackedAddresses() {
        return counts.size();
    }

    public void clear() {
        counts.clear();
    }
}
