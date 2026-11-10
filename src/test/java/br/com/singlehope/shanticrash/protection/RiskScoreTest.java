package br.com.singlehope.shanticrash.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScoreTest {
    private static final long S = 1_000_000_000L;
    private static final RiskSettings SETTINGS = new RiskSettings(true, 10 * S, 6.0D, 12.0D, 24.0D, 3.0D, 1.0D,
            0.5D, 3, 30 * S);

    @Test
    void scoreHalvesEveryHalfLife() {
        RiskScore score = new RiskScore();
        score.add(SETTINGS, true, 0L);
        score.add(SETTINGS, true, 0L);
        assertEquals(6.0D, score.decayed(SETTINGS, 0L), 1e-9);
        assertEquals(3.0D, score.decayed(SETTINGS, 10 * S), 1e-9);
        assertEquals(1.5D, score.decayed(SETTINGS, 20 * S), 1e-9);
        assertEquals(0.0D, score.decayed(SETTINGS, 1_000 * S), 1e-9);
    }

    @Test
    void levelsFollowThresholds() {
        RiskScore score = new RiskScore();
        assertEquals(RiskLevel.LOW, score.add(SETTINGS, false, 0L));
        assertEquals(RiskLevel.LOW, score.level(SETTINGS, 0L));
        for (int i = 0; i < 5; i++) {
            score.add(SETTINGS, false, 0L);
        }
        assertEquals(RiskLevel.MODERATE, score.level(SETTINGS, 0L));
        for (int i = 0; i < 2; i++) {
            score.add(SETTINGS, true, 0L);
        }
        assertEquals(RiskLevel.HIGH, score.level(SETTINGS, 0L));
        for (int i = 0; i < 4; i++) {
            score.add(SETTINGS, true, 0L);
        }
        assertEquals(RiskLevel.CRITICAL, score.level(SETTINGS, 0L));
    }

    @Test
    void rateFactorAppliesOnlyWhileModerate() {
        RiskScore score = new RiskScore();
        assertEquals(1.0D, score.rateFactor(0L), 0.0D);
        for (int i = 0; i < 4; i++) {
            score.add(SETTINGS, true, 0L);
        }
        assertEquals(0.5D, score.rateFactor(1L), 0.0D);
        assertEquals(0.5D, score.rateFactor(9 * S), 0.0D);
        assertEquals(1.0D, score.rateFactor(11 * S), 0.0D);
    }

    @Test
    void criticalNeedsStructuralEvidenceInsideTheWindowAndIsReportedOnce() {
        RiskScore behavioralOnly = new RiskScore();
        for (int i = 0; i < 40; i++) {
            behavioralOnly.add(SETTINGS, false, 0L);
        }
        assertEquals(RiskLevel.CRITICAL, behavioralOnly.level(SETTINGS, 0L));
        assertFalse(behavioralOnly.criticalEvidence(SETTINGS, 0L));

        RiskScore spread = new RiskScore();
        spread.add(SETTINGS, true, 0L);
        spread.add(SETTINGS, true, 40 * S);
        spread.add(SETTINGS, true, 80 * S);
        assertFalse(spread.criticalEvidence(SETTINGS, 80 * S));

        RiskScore burst = new RiskScore();
        for (int i = 0; i < 3; i++) {
            burst.add(SETTINGS, true, i * S);
        }
        assertTrue(burst.criticalEvidence(SETTINGS, 2 * S));
        assertFalse(burst.criticalEvidence(SETTINGS, 2 * S));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RiskSettings(true, S, 10, 5, 50, 3, 1, 0.75, 5, 30 * S));
        assertThrows(IllegalArgumentException.class, () -> new RiskSettings(true, 0L, 10, 25, 50, 3, 1, 0.75, 5, 30 * S));
        assertThrows(IllegalArgumentException.class, () -> new RiskSettings(true, S, 10, 25, 50, 3, 1, 2.0, 5, 30 * S));
        assertThrows(IllegalArgumentException.class, () -> new RiskSettings(true, S, 10, 25, 50, 3, 1, 0.75, 0, 30 * S));
    }
}
