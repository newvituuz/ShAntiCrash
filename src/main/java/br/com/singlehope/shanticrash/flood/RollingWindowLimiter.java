package br.com.singlehope.shanticrash.flood;

/**
 * Exact sliding window with bounded storage (one timestamp per accepted event). A limiter lives on
 * the connection's Netty event loop; it never schedules tasks, locks or allocates in the hot path.
 * Rejected events are not stored: their cost remains bounded even during a sustained flood.
 */
public final class RollingWindowLimiter {
    private final long[] timestamps;
    private final long windowNanos;
    private int head;
    private int count;
    private long lastSeen;

    public RollingWindowLimiter(int limit, long intervalMillis) {
        if (limit < 1 || limit > 512 || intervalMillis < 1 || intervalMillis > 60_000L) {
            throw new IllegalArgumentException("limite de janela inválido");
        }
        this.timestamps = new long[limit];
        this.windowNanos = intervalMillis * 1_000_000L;
    }

    /** Returns false if this event would exceed the configured window. */
    public boolean allow(long nowNanos) {
        if (count > 0 && nowNanos < lastSeen) { // clock discontinuity, or synthetic test reset
            count = 0;
            head = 0;
        }
        lastSeen = nowNanos;
        while (count > 0 && nowNanos - timestamps[head] >= windowNanos) {
            head = (head + 1) % timestamps.length;
            count--;
        }
        if (count == timestamps.length) {
            return false;
        }
        timestamps[(head + count) % timestamps.length] = nowNanos;
        count++;
        return true;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return timestamps.length;
    }
}
