package br.com.singlehope.shanticrash.flood;

/**
 * Per player, per group limiter state.
 *
 * <p>A denied token alone is not a violation: a lag spike makes a real client flush its queued
 * packets at once. Only repeated denials inside the observation window count, and that count is
 * forgotten after the recovery period without denials.
 */
public final class FloodState {
    /** Outcome of accounting one packet. */
    public enum Result {
        ALLOWED,
        DENIED,
        VIOLATION
    }

    private final TokenBucket bucket = new TokenBucket();
    private int overflows;
    private long windowStart;
    private long lastOverflow;

    public Result account(FloodLimit limit, double multiplier, double cost, long nowNanos) {
        if (bucket.tryConsume(limit.rate * multiplier, limit.burst * multiplier, cost, nowNanos)) {
            if (overflows != 0 && nowNanos - lastOverflow > limit.recoveryNanos) {
                overflows = 0;
            }
            return Result.ALLOWED;
        }
        if (overflows == 0 || nowNanos - windowStart > limit.observationNanos) {
            windowStart = nowNanos;
            overflows = 0;
        }
        lastOverflow = nowNanos;
        if (++overflows >= limit.maxOverflows) {
            overflows = 0;
            return Result.VIOLATION;
        }
        return Result.DENIED;
    }
}
