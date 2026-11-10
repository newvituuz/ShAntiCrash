package br.com.singlehope.shanticrash.metrics;

import br.com.singlehope.shanticrash.packet.PacketKind;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class ProfileSession {
    public static final int BUCKETS = 64;

    private final String target;
    private final long startedNanos;
    private final long expiresNanos;
    private final int sampleEvery;
    private final Object owner;

    private final AtomicLongArray packets = new AtomicLongArray(PacketKind.values().length);
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong timed = new AtomicLong();
    private final AtomicLongArray processing = new AtomicLongArray(BUCKETS);
    private final AtomicLongArray checkRuns;
    private final AtomicLongArray checkNanos;
    private final AtomicLongArray checkFlags;
    private long sampleCounter;
    private volatile long endedNanos;

    public ProfileSession(String target, int checks, long nowNanos, long durationNanos, int sampleEvery, Object owner) {
        this.target = target;
        this.startedNanos = nowNanos;
        this.expiresNanos = nowNanos + durationNanos;
        this.sampleEvery = Math.max(1, sampleEvery);
        this.owner = owner;
        this.checkRuns = new AtomicLongArray(checks);
        this.checkNanos = new AtomicLongArray(checks);
        this.checkFlags = new AtomicLongArray(checks);
    }

    public boolean sample() {
        return sampleCounter++ % sampleEvery == 0L;
    }

    public void packet(PacketKind kind, int frameBytes, boolean wasCancelled, long elapsedNanos) {
        packets.incrementAndGet(kind.ordinal());
        bytes.addAndGet(frameBytes);
        if (wasCancelled) {
            cancelled.incrementAndGet();
        }
        if (elapsedNanos >= 0L) {
            timed.incrementAndGet();
            processing.incrementAndGet(bucket(elapsedNanos));
        }
    }

    public void checkRun(int index, long elapsedNanos) {
        if (index < 0 || index >= checkRuns.length()) {
            return;
        }
        checkRuns.incrementAndGet(index);
        if (elapsedNanos > 0L) {
            checkNanos.addAndGet(index, elapsedNanos);
        }
    }

    public void flagged(int index) {
        if (index >= 0 && index < checkFlags.length()) {
            checkFlags.incrementAndGet(index);
        }
    }

    static int bucket(long nanos) {
        if (nanos <= 1L) {
            return 0;
        }
        return Math.min(BUCKETS - 1, 63 - Long.numberOfLeadingZeros(nanos));
    }

    public static long upperBound(int bucket) {
        return bucket >= 62 ? Long.MAX_VALUE : 1L << (bucket + 1);
    }

    public long percentileNanos(double fraction) {
        long total = timed.get();
        if (total == 0L) {
            return 0L;
        }
        long rank = (long) Math.ceil(fraction * total);
        long seen = 0L;
        for (int i = 0; i < BUCKETS; i++) {
            seen += processing.get(i);
            if (seen >= rank) {
                return upperBound(i);
            }
        }
        return upperBound(BUCKETS - 1);
    }

    public boolean expired(long nowNanos) {
        return endedNanos != 0L || nowNanos - expiresNanos >= 0L;
    }

    public void end(long nowNanos) {
        if (endedNanos == 0L) {
            endedNanos = nowNanos;
        }
    }

    public double seconds(long nowNanos) {
        long end = endedNanos != 0L ? endedNanos : nowNanos;
        return Math.max(0.001D, (end - startedNanos) / 1_000_000_000.0D);
    }

    public String target() {
        return target;
    }

    public Object owner() {
        return owner;
    }

    public long packets(PacketKind kind) {
        return packets.get(kind.ordinal());
    }

    public long totalPackets() {
        long sum = 0L;
        for (int i = 0; i < packets.length(); i++) {
            sum += packets.get(i);
        }
        return sum;
    }

    public long bytes() {
        return bytes.get();
    }

    public long cancelled() {
        return cancelled.get();
    }

    public long checkRuns(int index) {
        return checkRuns.get(index);
    }

    public long checkNanos(int index) {
        return checkNanos.get(index);
    }

    public long checkFlags(int index) {
        return checkFlags.get(index);
    }

    public int checks() {
        return checkRuns.length();
    }
}
