package br.com.singlehope.shanticrash.protection;

public final class RiskScore {
    private static final double LN2 = Math.log(2.0D);

    private volatile double score;
    private volatile long updatedNanos;
    private volatile long relaxAtNanos;
    private volatile double rateFactor = 1.0D;
    private long evidenceStart;
    private int evidence;
    private boolean criticalReported;

    public RiskLevel add(RiskSettings settings, boolean structural, long nowNanos) {
        double current = decayed(settings, nowNanos) + (structural ? settings.structuralWeight : settings.behavioralWeight);
        score = current;
        updatedNanos = nowNanos;
        if (structural) {
            if (evidence == 0 || nowNanos - evidenceStart > settings.evidenceWindowNanos) {
                evidenceStart = nowNanos;
                evidence = 0;
            }
            evidence++;
        }
        if (current >= settings.moderate) {
            double halfLives = Math.log(current / settings.moderate) / LN2;
            relaxAtNanos = nowNanos + (long) Math.min(Long.MAX_VALUE / 4.0D, halfLives * settings.halfLifeNanos);
            rateFactor = settings.moderateRateFactor;
        } else {
            relaxAtNanos = 0L;
            rateFactor = 1.0D;
        }
        RiskLevel level = settings.level(current);
        if (level != RiskLevel.CRITICAL) {
            criticalReported = false;
        }
        return level;
    }

    public boolean criticalEvidence(RiskSettings settings, long nowNanos) {
        if (criticalReported || evidence < settings.criticalEvidence
                || nowNanos - evidenceStart > settings.evidenceWindowNanos) {
            return false;
        }
        criticalReported = true;
        return true;
    }

    public double decayed(RiskSettings settings, long nowNanos) {
        double value = score;
        if (value <= 0.0D) {
            return 0.0D;
        }
        long elapsed = nowNanos - updatedNanos;
        if (elapsed <= 0L) {
            return value;
        }
        double result = value * Math.pow(0.5D, (double) elapsed / settings.halfLifeNanos);
        return result < 0.01D ? 0.0D : result;
    }

    public RiskLevel level(RiskSettings settings, long nowNanos) {
        return settings.level(decayed(settings, nowNanos));
    }

    public double rateFactor(long nowNanos) {
        long relax = relaxAtNanos;
        return relax != 0L && nowNanos - relax < 0L ? rateFactor : 1.0D;
    }

    public int evidence() {
        return evidence;
    }
}
