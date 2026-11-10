package br.com.singlehope.shanticrash.violation;

import br.com.singlehope.shanticrash.buffer.BufferManager;
import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.metrics.ProfileSession;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.protection.RiskLevel;
import br.com.singlehope.shanticrash.protection.RiskScore;
import br.com.singlehope.shanticrash.protection.RiskSettings;

/**
 * Applies the buffer and VL rules of each check. State lives in {@link PlayerData}, so nothing here
 * is shared between players and no lock is needed.
 */
public final class ViolationManager {
    private final ViolationListener listener;
    private volatile boolean enabled = true;
    private volatile RiskSettings risk = RiskSettings.DEFAULT;
    private volatile Check riskCheck;

    public ViolationManager(ViolationListener listener) {
        this.listener = listener;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void risk(RiskSettings risk) {
        this.risk = risk;
    }

    public RiskSettings risk() {
        return risk;
    }

    public void riskCheck(Check riskCheck) {
        this.riskCheck = riskCheck;
    }

    /**
     * Records a violation.
     *
     * @param useBuffer false for structural problems, which are never forgiven by the buffer
     * @return whether the VL was raised
     */
    public boolean flag(Check check, CheckSettings settings, PlayerData data, String info, long nowNanos,
                        boolean useBuffer) {
        if (!enabled) {
            return false;
        }
        CheckState state = data.state(check.index());
        if (useBuffer && settings.bufferEnabled && !BufferManager.accumulate(state, settings, nowNanos)) {
            return false;
        }

        double vl = state.add(settings, nowNanos);
        data.history().add(System.currentTimeMillis(), check.id(), vl, info);
        ProfileSession profile = data.profile();
        if (profile != null) {
            profile.flagged(check.index());
        }
        if (settings.alert && vl >= settings.alertMinVl) {
            listener.onFlag(check, settings, data, vl, info, nowNanos);
        }
        if (vl >= settings.punishAt) {
            listener.onPunish(check, settings, data, vl, info, nowNanos);
            if (settings.resetOnPunish) {
                state.resetVl(settings, nowNanos);
            }
        }
        escalate(check, data, !useBuffer && !check.isBehavioral(), nowNanos);
        return true;
    }

    private void escalate(Check check, PlayerData data, boolean structural, long nowNanos) {
        RiskSettings active = risk;
        Check critical = riskCheck;
        if (!active.enabled || check == critical) {
            return;
        }
        RiskScore score = data.risk();
        RiskLevel level = score.add(active, structural, nowNanos);
        if (level != RiskLevel.CRITICAL || critical == null || data.isClosing()
                || !score.criticalEvidence(active, nowNanos)) {
            return;
        }
        flag(critical, critical.settings(), data, score.evidence() + " violações estruturais em "
                + active.evidenceWindowNanos / 1_000_000_000L + "s, pontuação "
                + Math.round(score.decayed(active, nowNanos)), nowNanos, false);
    }
}
