package br.com.singlehope.shanticrash.metrics;

import br.com.singlehope.shanticrash.packet.PacketKind;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Global counters. Striped so that several Netty threads can write without contention. */
public final class PacketMetrics {
    private final LongAdder processed = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder violations = new LongAdder();
    private final LongAdder checkErrors = new LongAdder();
    private final LongAdder rejectedConnections = new LongAdder();
    private final LongAdder uninspected = new LongAdder();
    private final LongAdder[] packets = new LongAdder[PacketKind.values().length];
    private final LongAdder[] nanos = new LongAdder[PacketKind.values().length];
    private final AtomicLongArray maxNanos = new AtomicLongArray(PacketKind.values().length);
    private volatile AtomicLongArray errorsByCheck = new AtomicLongArray(0);
    private final long startedNanos = System.nanoTime();

    public PacketMetrics() {
        for (int i = 0; i < packets.length; i++) {
            packets[i] = new LongAdder();
            nanos[i] = new LongAdder();
        }
    }

    public void checks(int count) {
        errorsByCheck = new AtomicLongArray(count);
    }

    public void packet() {
        processed.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }

    public void violation() {
        violations.increment();
    }

    public void checkError() {
        checkErrors.increment();
    }

    public void checkError(int checkIndex) {
        checkErrors.increment();
        AtomicLongArray byCheck = errorsByCheck;
        if (checkIndex >= 0 && checkIndex < byCheck.length()) {
            byCheck.incrementAndGet(checkIndex);
        }
    }

    public void connectionRejected() {
        rejectedConnections.increment();
    }

    public void uninspected() {
        uninspected.increment();
    }

    /** Records the time spent on a packet. Only used while monitoring is on. */
    public void timing(PacketKind kind, long elapsed) {
        int index = kind.ordinal();
        packets[index].increment();
        nanos[index].add(elapsed);
        long known;
        do {
            known = maxNanos.get(index);
        } while (elapsed > known && !maxNanos.compareAndSet(index, known, elapsed));
    }

    public long processed() {
        return processed.sum();
    }

    public long cancelledCount() {
        return cancelled.sum();
    }

    public long violations() {
        return violations.sum();
    }

    public long checkErrors() {
        return checkErrors.sum();
    }

    public long checkErrors(int checkIndex) {
        AtomicLongArray byCheck = errorsByCheck;
        return checkIndex >= 0 && checkIndex < byCheck.length() ? byCheck.get(checkIndex) : 0L;
    }

    public long rejectedConnections() {
        return rejectedConnections.sum();
    }

    public long uninspectedPackets() {
        return uninspected.sum();
    }

    public double uptimeSeconds() {
        return Math.max(0.001D, (System.nanoTime() - startedNanos) / 1_000_000_000.0D);
    }

    public long packets(PacketKind kind) {
        return packets[kind.ordinal()].sum();
    }

    public long averageNanos(PacketKind kind) {
        long count = packets[kind.ordinal()].sum();
        return count == 0L ? 0L : nanos[kind.ordinal()].sum() / count;
    }

    public long maxNanos(PacketKind kind) {
        return maxNanos.get(kind.ordinal());
    }
}
