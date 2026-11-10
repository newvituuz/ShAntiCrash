package br.com.singlehope.shanticrash.flood;

/** Lazily allocated, per-connection state; replacing the rules on /reload resets all windows. */
public final class WindowRuleState {
    private WindowRuleSet loaded;
    private RollingWindowLimiter[] windows;
    private long lastFlagNanos;

    /**
     * Check each applicable rule. Returns the strongest violated action; the first rejected rule
     * does not short circuit accounting of other windows.
     */
    public WindowRuleSet.Action account(WindowRuleSet rules, int bytes, long nowNanos) {
        if (rules != loaded) {
            loaded = rules;
            windows = new RollingWindowLimiter[rules.rules.length];
            for (int i = 0; i < windows.length; i++) {
                WindowRuleSet.Rule rule = rules.rules[i];
                windows[i] = new RollingWindowLimiter(rule.maxEvents, rule.intervalMs);
            }
            lastFlagNanos = 0;
        }
        WindowRuleSet.Action action = null;
        for (int i = 0; i < rules.rules.length; i++) {
            WindowRuleSet.Rule rule = rules.rules[i];
            if (!rule.applies(bytes) || windows[i].allow(nowNanos)) {
                continue;
            }
            if (rule.action == WindowRuleSet.Action.DROP) {
                action = WindowRuleSet.Action.DROP;
            } else if (rule.action == WindowRuleSet.Action.FLAG && action != WindowRuleSet.Action.DROP) {
                action = WindowRuleSet.Action.FLAG;
            } else if (action == null) {
                action = WindowRuleSet.Action.COUNT;
            }
        }
        return action;
    }

    public long approximateBytes() {
        RollingWindowLimiter[] active = windows;
        long bytes = 32L;
        if (active != null) {
            for (RollingWindowLimiter limiter : active) {
                bytes += 32L + 8L * limiter.capacity();
            }
        }
        return bytes;
    }

    /** Limit expensive VL/alert processing during a sustained flood. */
    public boolean shouldFlag(long nowNanos) {
        if (lastFlagNanos != 0 && nowNanos - lastFlagNanos < 500_000_000L) {
            return false;
        }
        lastFlagNanos = nowNanos;
        return true;
    }
}
