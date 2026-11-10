package br.com.singlehope.shanticrash.flood;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingWindowLimiterTest {
    @Test
    void exactWindowAndExpiry() {
        RollingWindowLimiter limiter = new RollingWindowLimiter(10, 80);
        for (int i = 0; i < 10; i++) assertTrue(limiter.allow(1_000_000L + i * 1_000_000L));
        assertFalse(limiter.allow(15_000_000L));
        assertEquals(10, limiter.count());
        assertTrue(limiter.allow(81_000_000L));
        assertEquals(10, limiter.count());
    }

    @Test
    void rulesAreIndependentAndResetOnReload() {
        WindowRuleSet.Rule shortRule = new WindowRuleSet.Rule(80, 2, 0, 2048, WindowRuleSet.Action.FLAG);
        WindowRuleSet rules = new WindowRuleSet(new WindowRuleSet.Rule[]{shortRule});
        WindowRuleState state = new WindowRuleState();
        assertNull(state.account(rules, 32, 10_000_000L));
        assertNull(state.account(rules, 32, 20_000_000L));
        assertEquals(WindowRuleSet.Action.FLAG, state.account(rules, 32, 30_000_000L));
        // Out-of-band bytes must not consume the small-packet window.
        assertNull(state.account(rules, 3000, 35_000_000L));
        assertNull(state.account(new WindowRuleSet(new WindowRuleSet.Rule[]{shortRule}), 32, 40_000_000L));
    }

    @Test
    void dropHasPriorityAndStateIsBounded() {
        WindowRuleSet rules = new WindowRuleSet(new WindowRuleSet.Rule[]{
                new WindowRuleSet.Rule(200, 1, 0, Integer.MAX_VALUE, WindowRuleSet.Action.FLAG),
                new WindowRuleSet.Rule(200, 1, 0, Integer.MAX_VALUE, WindowRuleSet.Action.DROP)
        });
        WindowRuleState state = new WindowRuleState();
        assertNull(state.account(rules, 15, 1_000_000L));
        assertEquals(WindowRuleSet.Action.DROP, state.account(rules, 15, 2_000_000L));
        for (int i = 0; i < 50_000; i++) {
            assertEquals(WindowRuleSet.Action.DROP, state.account(rules, 15, 3_000_000L + i));
        }
    }

    @Test
    void rejectsInvalidConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new RollingWindowLimiter(513, 80));
        assertThrows(IllegalArgumentException.class, () -> new WindowRuleSet.Rule(80, 0, 0, 100, WindowRuleSet.Action.FLAG));
    }
}
