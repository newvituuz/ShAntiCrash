package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.protection.ProtectionMode;
import br.com.singlehope.shanticrash.protection.RiskSettings;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionModeAndRiskTest {
    private static final long S = 1_000_000_000L;

    private static void applyMode(TestHarness h, ProtectionMode mode) {
        java.util.Map<String, Object> options = new java.util.HashMap<>();
        options.put("limits.ANIMATION.drop", true);
        for (Check check : h.checks.all()) {
            CheckSettings settings = check.defaults();
            if (check.id().equals("flood.packet-limits")) {
                settings.options = new CheckOptions(options);
            }
            check.configure(mode.apply(check.isBehavioral(), settings));
        }
    }

    private static PacketContext move(TestHarness h, PlayerData d, double x, long now) {
        return h.run(d, PacketKind.MOVE_POSITION, Wire.create().d(x).d(64).d(0).b(1).toBytes(), now);
    }

    private static int floodUntilCancelled(TestHarness h, PlayerData d) {
        for (int i = 0; i < 5000; i++) {
            if (h.run(d, PacketKind.ANIMATION, Wire.create().v(0).toBytes(), 1_000L + i).isCancelled()) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void onlyBehavioralChecksAreChangedByTheMode() {
        TestHarness h = new TestHarness(765);
        for (Check check : h.checks.all()) {
            CheckSettings original = check.defaults();
            CheckSettings monitored = ProtectionMode.MONITOR_ONLY.apply(check.isBehavioral(), check.defaults());
            if (check.isBehavioral()) {
                assertFalse(monitored.enforce, check.id());
                assertFalse(monitored.cancel, check.id());
                assertEquals(EnumSet.of(PunishmentAction.ALERT), monitored.actions, check.id());
            } else {
                assertEquals(original.enforce, monitored.enforce, check.id());
                assertEquals(original.cancel, monitored.cancel, check.id());
                assertEquals(original.actions, monitored.actions, check.id());
                assertEquals(original.punishAt, monitored.punishAt, 0.0D, check.id());
            }
        }
        assertTrue(h.check("flood.packet-limits").isBehavioral());
        assertTrue(h.check("netty.byte-rate").isBehavioral());
        assertFalse(h.check("position.invalid-numbers").isBehavioral());
        assertFalse(h.check("netty.frame-size").isBehavioral());
    }

    @Test
    void monitorOnlyObservesFloodButStillBlocksInvalidStructure() {
        TestHarness balanced = new TestHarness(765);
        applyMode(balanced, ProtectionMode.BALANCED);
        assertTrue(floodUntilCancelled(balanced, balanced.newPlayer()) > 0);

        TestHarness h = new TestHarness(765);
        applyMode(h, ProtectionMode.MONITOR_ONLY);
        assertEquals(-1, floodUntilCancelled(h, h.newPlayer()));
        assertTrue(h.flagged("flood.packet-limits"));
        assertTrue(move(h, h.newPlayer(), Double.NaN, 1_000L).isCancelled());
    }

    @Test
    void strictCutsEarlierAndCompatibilityLaterThanBalanced() {
        TestHarness strict = new TestHarness(765);
        applyMode(strict, ProtectionMode.STRICT);
        TestHarness balanced = new TestHarness(765);
        applyMode(balanced, ProtectionMode.BALANCED);
        TestHarness compatible = new TestHarness(765);
        applyMode(compatible, ProtectionMode.COMPATIBILITY);
        int s = floodUntilCancelled(strict, strict.newPlayer());
        int b = floodUntilCancelled(balanced, balanced.newPlayer());
        int c = floodUntilCancelled(compatible, compatible.newPlayer());
        assertTrue(s > 0 && b > 0 && c > 0, s + " " + b + " " + c);
        assertTrue(s < b && b < c, s + " " + b + " " + c);
    }

    @Test
    void repeatedStructuralViolationsEscalateToRiskCritical() {
        TestHarness h = new TestHarness(765);
        h.violations.risk(new RiskSettings(true, 60 * S, 3.0D, 6.0D, 8.0D, 3.0D, 1.0D, 0.75D, 3, 30 * S));
        Check nan = h.check("position.invalid-numbers");
        CheckSettings lenient = nan.defaults();
        lenient.punishAt = 100.0D;
        lenient.actions = EnumSet.of(PunishmentAction.NONE);
        nan.configure(lenient);
        PlayerData d = h.newPlayer();
        move(h, d, Double.NaN, S);
        move(h, d, Double.NaN, 2 * S);
        assertFalse(h.recorder.punishments.contains("risk.critical"));
        move(h, d, Double.NaN, 3 * S);
        assertTrue(h.recorder.punishments.contains("risk.critical"));
        assertEquals(1, h.recorder.punishments.stream().filter("risk.critical"::equals).count());
    }

    @Test
    void floodAloneNeverReachesRiskCritical() {
        TestHarness h = new TestHarness(765);
        h.violations.risk(new RiskSettings(true, 60 * S, 1.0D, 2.0D, 3.0D, 3.0D, 1.0D, 0.75D, 2, 30 * S));
        PlayerData d = h.newPlayer();
        for (int i = 0; i < 20000; i++) {
            h.run(d, PacketKind.ANIMATION, Wire.create().v(0).toBytes(), 1_000L + i * 10L);
        }
        assertTrue(h.flagged("flood.packet-limits"));
        assertFalse(h.recorder.punishments.contains("risk.critical"));
    }

    @Test
    void moderateRiskTightensRateLimits() {
        TestHarness calm = new TestHarness(765);
        TestHarness tense = new TestHarness(765);
        tense.violations.risk(new RiskSettings(true, 600 * S, 1.0D, 50.0D, 90.0D, 3.0D, 1.0D, 0.5D, 99, 30 * S));
        PlayerData flagged = tense.newPlayer();
        Check nan = tense.check("position.invalid-numbers");
        CheckSettings lenient = nan.defaults();
        lenient.punishAt = 100.0D;
        lenient.actions = EnumSet.of(PunishmentAction.NONE);
        nan.configure(lenient);
        move(tense, flagged, Double.NaN, 500L);
        assertEquals(0.5D, flagged.riskRateFactor(1_000L), 0.0D);
        int calmLimit = firstFlag(calm, calm.newPlayer());
        int tenseLimit = firstFlag(tense, flagged);
        assertTrue(tenseLimit > 0 && tenseLimit < calmLimit, tenseLimit + " " + calmLimit);
    }

    private static int firstFlag(TestHarness h, PlayerData d) {
        int before = h.recorder.flags.size();
        for (int i = 0; i < 5000; i++) {
            h.run(d, PacketKind.ANIMATION, Wire.create().v(0).toBytes(), 1_000L + i);
            for (int j = before; j < h.recorder.flags.size(); j++) {
                if (h.recorder.flags.get(j).equals("flood.packet-limits")) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Test
    void behavioralExemptionNeverSkipsStructuralChecks() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        d.behavioralExempt(true);
        for (int i = 0; i < 5000; i++) {
            h.run(d, PacketKind.ANIMATION, Wire.create().v(0).toBytes(), 1_000L + i);
        }
        assertFalse(h.flagged("flood.packet-limits"));
        assertTrue(move(h, d, Double.NaN, 10_000L).isCancelled());
        assertTrue(h.flagged("position.invalid-numbers"));
    }
}
