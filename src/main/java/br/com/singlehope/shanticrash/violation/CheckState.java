package br.com.singlehope.shanticrash.violation;

import br.com.singlehope.shanticrash.checks.CheckSettings;

/**
 * Violation state of one player in one check.
 *
 * <p>Decay is computed when the value is touched, which removes the need for a periodic task walking
 * every player. Only the player's Netty thread writes these fields; commands read {@link #vl} from
 * other threads, which is why it is volatile.
 */
public final class CheckState {
    private volatile double vl;
    private long lastVlUpdate;
    private double buffer;
    private long lastBufferUpdate;
    private int streak;
    private long lastAlert;
    private volatile int flags;

    public double bufferAfterDecay(CheckSettings settings, long nowNanos) {
        double elapsedSeconds = (nowNanos - lastBufferUpdate) / 1_000_000_000.0D;
        return Math.max(0.0D, buffer - elapsedSeconds * settings.bufferDecayPerSecond);
    }

    public void buffer(double value, long nowNanos) {
        this.buffer = value;
        this.lastBufferUpdate = nowNanos;
    }

    /** Current VL with the elapsed decay intervals applied. Safe to call from any thread. */
    public double vlAfterDecay(CheckSettings settings, long nowNanos) {
        double value = vl;
        if (settings.vlDecayAmount <= 0.0D || settings.vlDecayIntervalNanos <= 0L || lastVlUpdate == 0L) {
            return value;
        }
        long intervals = (nowNanos - lastVlUpdate) / settings.vlDecayIntervalNanos;
        if (intervals <= 0L) {
            return value;
        }
        return Math.max(settings.vlMin, value - intervals * settings.vlDecayAmount);
    }

    public double add(CheckSettings settings, long nowNanos) {
        double current = vlAfterDecay(settings, nowNanos);
        if (current < vl) {
            streak = 0;
        }
        double increment = settings.vlIncrement;
        if (settings.vlMultiplier != 1.0D && streak > 0) {
            increment *= Math.pow(settings.vlMultiplier, Math.min(streak, 16));
        }
        streak++;
        double updated = Math.max(settings.vlMin, Math.min(settings.vlMax, current + increment));
        vl = updated;
        lastVlUpdate = nowNanos;
        flags++;
        return updated;
    }

    public void resetVl(CheckSettings settings, long nowNanos) {
        vl = settings.vlMin;
        streak = 0;
        lastVlUpdate = nowNanos;
    }

    public int flags() {
        return flags;
    }

    /** Whether an alert may be sent now, recording it if so. */
    public boolean acquireAlert(long cooldownNanos, long nowNanos) {
        if (lastAlert != 0L && nowNanos - lastAlert < cooldownNanos) {
            return false;
        }
        lastAlert = nowNanos;
        return true;
    }
}
