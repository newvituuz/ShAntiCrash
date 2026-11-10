package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodWindowRulesTest {
    private static final long MS = 1_000_000L;

    private static Map<String, Object> rule(int intervalMs, int max, Object action) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("interval-ms", intervalMs);
        rule.put("max", max);
        if (action != null) {
            rule.put("action", action);
        }
        return rule;
    }

    private static TestHarness harness(boolean enabled, List<Map<String, Object>> animationRules) {
        TestHarness h = new TestHarness(765);
        Check check = h.check("flood.packet-limits");
        CheckSettings settings = check.defaults();
        Map<String, Object> options = new HashMap<>();
        options.put("advanced-rules-enabled", enabled);
        options.put("advanced-rules.ANIMATION", animationRules);
        settings.options = new CheckOptions(options);
        check.configure(settings);
        return h;
    }

    private static PacketContext swing(TestHarness h, PlayerData d, long now) {
        return h.run(d, PacketKind.ANIMATION, Wire.create().v(0).toBytes(), now);
    }

    @Test
    void dropRuleCancelsOnlyAboveTheWindow() {
        TestHarness h = harness(true, Arrays.asList(rule(200, 5, "DROP")));
        PlayerData d = h.newPlayer();
        long now = 1_000L;
        for (int i = 0; i < 5; i++) {
            assertFalse(swing(h, d, now).isCancelled());
            now += 10 * MS;
        }
        assertTrue(swing(h, d, now).isCancelled());
        assertTrue(h.flagged("flood.packet-limits"));
        assertFalse(swing(h, d, now + 250 * MS).isCancelled());
    }

    @Test
    void flagRuleRecordsWithoutCancelling() {
        TestHarness h = harness(true, Arrays.asList(rule(200, 3, "FLAG")));
        PlayerData d = h.newPlayer();
        PacketContext last = null;
        for (int i = 0; i < 4; i++) {
            last = swing(h, d, 1_000L + i * MS);
        }
        assertFalse(last.isCancelled());
        assertTrue(h.flagged("flood.packet-limits"));
    }

    @Test
    void countRuleNeitherCancelsNorFlags() {
        TestHarness h = harness(true, Arrays.asList(rule(200, 2, "COUNT")));
        PlayerData d = h.newPlayer();
        for (int i = 0; i < 10; i++) {
            assertFalse(swing(h, d, 1_000L + i * MS).isCancelled());
        }
        assertFalse(h.flagged("flood.packet-limits"));
    }

    @Test
    void rulesAreIgnoredWhileDisabled() {
        TestHarness h = harness(false, Arrays.asList(rule(200, 1, "DROP")));
        PlayerData d = h.newPlayer();
        for (int i = 0; i < 10; i++) {
            assertFalse(swing(h, d, 1_000L + i * MS).isCancelled());
        }
        assertFalse(h.flagged("flood.packet-limits"));
    }

    @Test
    void invalidRulesAreSkippedAndValidOnesKept() {
        TestHarness h = harness(true, Arrays.asList(
                rule(10, 1, "DROP"),
                rule(200, 0, "DROP"),
                rule(200, 1, "XYZ"),
                rule(200, 2, "drop")));
        PlayerData d = h.newPlayer();
        assertFalse(swing(h, d, 1_000L).isCancelled());
        assertFalse(swing(h, d, 1_000L + MS).isCancelled());
        assertTrue(swing(h, d, 1_000L + 2 * MS).isCancelled());
    }

    @Test
    void sizeFilterLimitsWhichPacketsCount() {
        Map<String, Object> big = rule(200, 1, "DROP");
        big.put("min-bytes", 1000);
        TestHarness h = harness(true, Arrays.asList(big));
        PlayerData d = h.newPlayer();
        for (int i = 0; i < 10; i++) {
            assertFalse(swing(h, d, 1_000L + i * MS).isCancelled());
        }
        assertFalse(h.flagged("flood.packet-limits"));
    }

    @Test
    void windowsAreSeparatePerConnection() {
        TestHarness h = harness(true, Arrays.asList(rule(200, 2, "DROP")));
        PlayerData first = h.newPlayer();
        PlayerData second = h.newPlayer();
        swing(h, first, 1_000L);
        swing(h, first, 2_000L);
        assertTrue(swing(h, first, 3_000L).isCancelled());
        assertFalse(swing(h, second, 3_000L).isCancelled());
        assertEquals(1, h.recorder.flags.stream().filter(f -> f.contains("flood.packet-limits")).count());
    }
}
