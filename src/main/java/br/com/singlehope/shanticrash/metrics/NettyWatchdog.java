package br.com.singlehope.shanticrash.metrics;

import br.com.singlehope.shanticrash.logging.EventLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic that reports where a Netty thread is stuck while handling a packet.
 *
 * <p>It samples stack traces, which pauses the target thread for a moment, so it is off by default and
 * limited by a cooldown. It only observes and logs: it never interrupts a thread or drops a connection.
 */
public final class NettyWatchdog {
    private final Map<Thread, AtomicLong> running = new ConcurrentHashMap<>();
    private final ThreadLocal<AtomicLong> local = ThreadLocal.withInitial(() -> {
        AtomicLong slot = new AtomicLong();
        running.put(Thread.currentThread(), slot);
        return slot;
    });
    private final long maxNanos;
    private final long checkMillis;
    private final long cooldownNanos;
    private final EventLogger logger;
    private final Thread thread;
    private volatile boolean active = true;
    private long lastReport;

    public NettyWatchdog(long maxMillis, long checkMillis, long cooldownMillis, EventLogger logger) {
        this.maxNanos = maxMillis * 1_000_000L;
        this.checkMillis = Math.max(5L, checkMillis);
        this.cooldownNanos = cooldownMillis * 1_000_000L;
        this.logger = logger;
        this.thread = new Thread(this::loop, "ShAntiCrash-Watchdog");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        active = false;
        thread.interrupt();
    }

    /** Marks the start of the handling of a packet on the calling thread. */
    public void enter(long nowNanos) {
        local.get().lazySet(nowNanos);
    }

    public void exit() {
        local.get().lazySet(0L);
    }

    private void loop() {
        while (active) {
            try {
                Thread.sleep(checkMillis);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            for (Map.Entry<Thread, AtomicLong> entry : running.entrySet()) {
                Thread target = entry.getKey();
                if (!target.isAlive()) {
                    running.remove(target);
                    continue;
                }
                long started = entry.getValue().get();
                if (started != 0L && now - started > maxNanos && now - lastReport > cooldownNanos) {
                    lastReport = now;
                    report(target, now - started);
                }
            }
        }
    }

    private void report(Thread target, long elapsedNanos) {
        StringBuilder trace = new StringBuilder("Watchdog: ").append(target.getName())
                .append(" no mesmo pacote há ").append(elapsedNanos / 1_000_000L).append(" ms:");
        StackTraceElement[] elements = target.getStackTrace();
        for (int i = 0; i < Math.min(elements.length, 12); i++) {
            trace.append(" | ").append(elements[i]);
        }
        logger.log(trace.toString());
    }
}
