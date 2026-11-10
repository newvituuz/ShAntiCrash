package br.com.singlehope.shanticrash.violation;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.flood.FloodLimit;
import br.com.singlehope.shanticrash.flood.FloodState;
import br.com.singlehope.shanticrash.flood.TokenBucket;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import br.com.singlehope.shanticrash.support.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationSystemTest {
    private static final long SECOND = 1_000_000_000L;

    private static final class Probe extends Check {
        Probe() {
            super("teste", "probe", "check de teste");
        }

        @Override
        protected void process(PacketContext context) {
        }
    }

    private TestHarness.Recorder recorder;
    private ViolationManager manager;
    private Probe check;
    private PlayerData data;
    private CheckSettings settings;
    private long now;

    @BeforeEach
    void setUp() {
        recorder = new TestHarness.Recorder();
        manager = new ViolationManager(recorder);
        check = new Probe();
        check.initialize(0, manager);
        data = new PlayerData(new TestHarness.FakeHandle(), 1, 4);
        settings = new CheckSettings();
        settings.alertMinVl = 1;
        settings.punishAt = 3;
        settings.actions = java.util.EnumSet.of(PunishmentAction.KICK);
        now = 1_000L;
    }

    private boolean flag(boolean useBuffer) {
        return manager.flag(check, settings, data, "info", now, useBuffer);
    }

    private double vl() {
        return data.state(0).vlAfterDecay(settings, now);
    }

    @Test
    void reachesThresholdAndPunishes() {
        flag(false);
        flag(false);
        assertTrue(recorder.punishments.isEmpty());
        flag(false);
        assertEquals(1, recorder.punishments.size());
    }

    @Test
    void oneSuspiciousPacketDoesNotPunish() {
        settings.punishAt = 5;
        flag(false);
        assertEquals(1.0, vl());
        assertTrue(recorder.punishments.isEmpty());
    }

    @Test
    void vlResetsAfterPunishing() {
        settings.resetOnPunish = true;
        for (int i = 0; i < 3; i++) {
            flag(false);
        }
        assertEquals(settings.vlMin, vl());
    }

    @Test
    void vlRespectsLimits() {
        settings.resetOnPunish = false;
        settings.vlMax = 2;
        settings.punishAt = 100;
        for (int i = 0; i < 10; i++) {
            flag(false);
        }
        assertEquals(2.0, vl());
    }

    @Test
    void decayLowersVlDownToMinimum() {
        settings.punishAt = 100;
        settings.vlDecayAmount = 1;
        settings.vlDecayIntervalNanos = 10 * SECOND;
        settings.vlMin = 1;
        for (int i = 0; i < 5; i++) {
            flag(false);
        }
        assertEquals(5.0, vl());
        now += 20 * SECOND;
        assertEquals(3.0, vl());
        now += 1000 * SECOND;
        assertEquals(1.0, vl());
    }

    @Test
    void multiplierEscalatesAndResetsAfterDecay() {
        settings.punishAt = 1000;
        settings.vlMultiplier = 2;
        settings.vlDecayIntervalNanos = 100 * SECOND;
        flag(false);
        assertEquals(1.0, vl());
        flag(false);
        assertEquals(3.0, vl());
        flag(false);
        assertEquals(7.0, vl());
    }

    @Test
    void bufferAbsorbsIsolatedEvents() {
        settings.bufferEnabled = true;
        settings.bufferMax = 3;
        settings.bufferMultiply = 0.5;
        settings.bufferDecayPerSecond = 1;
        assertFalse(flag(true));
        assertFalse(flag(true));
        assertTrue(flag(true));
        assertEquals(1.0, vl());
    }

    @Test
    void bufferDecaysWithTime() {
        settings.bufferEnabled = true;
        settings.bufferMax = 3;
        settings.bufferDecayPerSecond = 1;
        for (int i = 0; i < 50; i++) {
            assertFalse(flag(true));
            now += 2 * SECOND;
        }
        assertEquals(0.0, vl());
    }

    @Test
    void bufferKeepsPartAfterTriggering() {
        settings.bufferEnabled = true;
        settings.bufferMax = 4;
        settings.bufferMultiply = 0.75;
        settings.bufferDecayPerSecond = 0;
        settings.punishAt = 100;
        flag(true);
        flag(true);
        flag(true);
        assertTrue(flag(true));
        // 4 * 0.75 = 3, so the very next event triggers again.
        assertTrue(flag(true));
    }

    @Test
    void structuralFlagsSkipTheBuffer() {
        settings.bufferEnabled = true;
        settings.bufferMax = 100;
        assertTrue(flag(false));
    }

    @Test
    void playersDoNotShareState() {
        settings.punishAt = 100;
        PlayerData other = new PlayerData(new TestHarness.FakeHandle(), 1, 4);
        flag(false);
        flag(false);
        assertEquals(2.0, vl());
        assertTrue(other.peekState(0) == null);
    }

    @Test
    void alertRespectsMinimum() {
        settings.alertMinVl = 2;
        settings.punishAt = 100;
        flag(false);
        assertTrue(recorder.flags.isEmpty());
        flag(false);
        assertEquals(1, recorder.flags.size());
    }

    @Test
    void historyIsBoundedAndOrdered() {
        settings.punishAt = 1000;
        for (int i = 0; i < 10; i++) {
            manager.flag(check, settings, data, "n" + i, now, false);
        }
        List<ViolationHistory.Entry> history = data.history().snapshot();
        assertEquals(4, history.size());
        assertEquals("n6", history.get(0).info);
        assertEquals("n9", history.get(3).info);
    }

    @Test
    void globalSwitchDisablesEverything() {
        manager.enabled(false);
        assertFalse(flag(false));
        assertEquals(0.0, vl());
    }

    // ------------------------------------------------------------------ rate limiter

    @Test
    void bucketRefillsAtRateAndCapsAtBurst() {
        TokenBucket bucket = new TokenBucket();
        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryConsume(10, 10, 1, 0));
        }
        assertFalse(bucket.tryConsume(10, 10, 1, 0));
        assertTrue(bucket.tryConsume(10, 10, 1, SECOND / 10));
        assertFalse(bucket.tryConsume(10, 10, 1, SECOND / 10));
        bucket.tryConsume(10, 10, 0, 100 * SECOND);
        assertEquals(10.0, bucket.tokens(), 0.001);
    }

    @Test
    void sustainedRateAtTheLimitIsAllowedForever() {
        TokenBucket bucket = new TokenBucket();
        long time = 0;
        for (int i = 0; i < 100_000; i++) {
            assertTrue(bucket.tryConsume(100, 10, 1, time));
            time += SECOND / 100;
        }
    }

    @Test
    void floodStateCountsDenialsInTheWindowOnly() {
        FloodLimit limit = new FloodLimit(1, 1, 1000, 5000, 3, true);
        FloodState state = new FloodState();
        assertEquals(FloodState.Result.ALLOWED, state.account(limit, 1, 1, 1));
        assertEquals(FloodState.Result.DENIED, state.account(limit, 1, 1, 2));
        assertEquals(FloodState.Result.DENIED, state.account(limit, 1, 1, 3));
        assertEquals(FloodState.Result.VIOLATION, state.account(limit, 1, 1, 4));
    }

    @Test
    void floodStateForgetsAfterRecovery() {
        FloodLimit limit = new FloodLimit(1, 1, 60_000, 5000, 3, true);
        FloodState state = new FloodState();
        state.account(limit, 1, 1, 1);
        state.account(limit, 1, 1, 2);
        state.account(limit, 1, 1, 3);
        // Tokens came back and recovery has elapsed since the last denial.
        assertEquals(FloodState.Result.ALLOWED, state.account(limit, 1, 1, 10 * SECOND));
        state.account(limit, 1, 1, 10 * SECOND + 1);
        assertEquals(FloodState.Result.DENIED, state.account(limit, 1, 1, 10 * SECOND + 2));
    }

    @Test
    void multiplierRaisesTheLimit() {
        FloodLimit limit = new FloodLimit(1, 2, 1000, 5000, 100, true);
        FloodState normal = new FloodState();
        FloodState bedrock = new FloodState();
        int allowedNormal = 0;
        int allowedBedrock = 0;
        for (int i = 0; i < 10; i++) {
            if (normal.account(limit, 1, 1, 1) == FloodState.Result.ALLOWED) {
                allowedNormal++;
            }
            if (bedrock.account(limit, 2, 1, 1) == FloodState.Result.ALLOWED) {
                allowedBedrock++;
            }
        }
        assertEquals(2, allowedNormal);
        assertEquals(4, allowedBedrock);
    }
}
