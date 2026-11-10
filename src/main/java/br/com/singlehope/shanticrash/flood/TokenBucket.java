package br.com.singlehope.shanticrash.flood;

/**
 * Token bucket with lazy refill: it costs nothing while idle and needs no scheduled task. Used by one
 * Netty thread only, so it is not synchronized.
 */
public final class TokenBucket {
    private double tokens = -1.0D;
    private long lastRefill;

    public boolean tryConsume(double ratePerSecond, double burst, double cost, long nowNanos) {
        if (tokens < 0.0D) {
            tokens = burst;
            lastRefill = nowNanos;
        } else {
            long elapsed = nowNanos - lastRefill;
            if (elapsed > 0L) {
                tokens = Math.min(burst, tokens + elapsed * ratePerSecond / 1_000_000_000.0D);
                lastRefill = nowNanos;
            }
        }
        if (tokens < cost) {
            return false;
        }
        tokens -= cost;
        return true;
    }

    public double tokens() {
        return tokens;
    }
}
