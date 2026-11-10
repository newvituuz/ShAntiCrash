package br.com.singlehope.shanticrash.protection;

public final class RiskSettings {
    public static final RiskSettings DEFAULT = new RiskSettings(true, 60_000_000_000L, 10.0D, 25.0D, 50.0D,
            3.0D, 1.0D, 0.75D, 5, 30_000_000_000L);

    public final boolean enabled;
    public final long halfLifeNanos;
    public final double moderate;
    public final double high;
    public final double critical;
    public final double structuralWeight;
    public final double behavioralWeight;
    public final double moderateRateFactor;
    public final int criticalEvidence;
    public final long evidenceWindowNanos;

    public RiskSettings(boolean enabled, long halfLifeNanos, double moderate, double high, double critical,
                        double structuralWeight, double behavioralWeight, double moderateRateFactor,
                        int criticalEvidence, long evidenceWindowNanos) {
        if (halfLifeNanos < 1_000_000_000L || moderate <= 0.0D || high < moderate || critical < high
                || structuralWeight < 0.0D || behavioralWeight < 0.0D
                || moderateRateFactor < 0.1D || moderateRateFactor > 1.0D
                || criticalEvidence < 1 || evidenceWindowNanos < 1_000_000_000L) {
            throw new IllegalArgumentException("configuração de risco inválida");
        }
        this.enabled = enabled;
        this.halfLifeNanos = halfLifeNanos;
        this.moderate = moderate;
        this.high = high;
        this.critical = critical;
        this.structuralWeight = structuralWeight;
        this.behavioralWeight = behavioralWeight;
        this.moderateRateFactor = moderateRateFactor;
        this.criticalEvidence = criticalEvidence;
        this.evidenceWindowNanos = evidenceWindowNanos;
    }

    public RiskLevel level(double score) {
        if (score >= critical) {
            return RiskLevel.CRITICAL;
        }
        if (score >= high) {
            return RiskLevel.HIGH;
        }
        if (score >= moderate) {
            return RiskLevel.MODERATE;
        }
        return RiskLevel.LOW;
    }
}
