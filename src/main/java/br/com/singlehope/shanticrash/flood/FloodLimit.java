package br.com.singlehope.shanticrash.flood;

/** Configured limit of one packet group. */
public final class FloodLimit {
    public final double rate;
    public final double burst;
    public final long observationNanos;
    public final long recoveryNanos;
    public final int maxOverflows;
    public final boolean drop;

    public FloodLimit(double rate, double burst, long observationMs, long recoveryMs, int maxOverflows, boolean drop) {
        this.rate = rate;
        this.burst = burst;
        this.observationNanos = observationMs * 1_000_000L;
        this.recoveryNanos = recoveryMs * 1_000_000L;
        this.maxOverflows = maxOverflows;
        this.drop = drop;
    }
}
