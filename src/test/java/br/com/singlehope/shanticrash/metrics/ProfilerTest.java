package br.com.singlehope.shanticrash.metrics;

import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerTest {
    private static final long S = 1_000_000_000L;

    @Test
    void bucketsAndPercentilesAreBoundedApproximations() {
        assertEquals(0, ProfileSession.bucket(0L));
        assertEquals(0, ProfileSession.bucket(1L));
        assertEquals(1, ProfileSession.bucket(2L));
        assertEquals(9, ProfileSession.bucket(1000L));
        assertEquals(62, ProfileSession.bucket(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ProfileSession.upperBound(62));
        ProfileSession session = new ProfileSession("x", 4, 0L, 10 * S, 1, null);
        for (int i = 0; i < 90; i++) {
            session.packet(PacketKind.ANIMATION, 10, false, 1_000L);
        }
        for (int i = 0; i < 10; i++) {
            session.packet(PacketKind.ANIMATION, 10, false, 1_000_000L);
        }
        assertEquals(1024L, session.percentileNanos(0.50D));
        assertEquals(1024L, session.percentileNanos(0.90D));
        assertEquals(1L << 20, session.percentileNanos(0.95D));
        assertEquals(100L, session.packets(PacketKind.ANIMATION));
        assertEquals(1000L, session.bytes());
    }

    @Test
    void samplingTimesOnlyOneInN() {
        ProfileSession session = new ProfileSession("x", 1, 0L, S, 4, null);
        int sampled = 0;
        for (int i = 0; i < 100; i++) {
            if (session.sample()) {
                sampled++;
            }
        }
        assertEquals(25, sampled);
    }

    @Test
    void sessionsExpireAndTurnInstrumentationOff() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        Profiler profiler = new Profiler();
        profiler.settings(new Profiler.Settings(2 * S, 1, 1));
        ProfileSession session = profiler.start(d, "dono", h.checks.all().size(), 0L);
        assertNotNull(session);
        assertSame(session, d.profile());
        assertNull(profiler.start(h.newPlayer(), "outro", h.checks.all().size(), 0L));
        assertTrue(profiler.expire(S).isEmpty());
        List<ProfileSession> expired = profiler.expire(3 * S);
        assertEquals(1, expired.size());
        assertNull(d.profile());
        assertEquals(0, profiler.activeSessions());
        assertFalse(profiler.stopAll(4 * S).iterator().hasNext());
    }

    @Test
    void pipelineRecordsChecksAndFlagsWhileProfiling() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        Profiler profiler = new Profiler();
        ProfileSession session = profiler.start(d, null, h.checks.all().size(), 0L);
        d.context().profileSampled = true;
        h.run(d, PacketKind.MOVE_POSITION, Wire.create().d(Double.NaN).d(0).d(0).b(1).toBytes(), 1_000L);
        int nan = h.check("position.invalid-numbers").index();
        int format = h.check("position.format").index();
        assertEquals(1L, session.checkRuns(format));
        assertEquals(1L, session.checkRuns(nan));
        assertEquals(1L, session.checkFlags(nan));
        profiler.stopAll(S);
        assertNull(d.profile());
        h.run(d, PacketKind.MOVE_POSITION, Wire.create().d(0).d(0).d(0).b(1).toBytes(), 2_000L);
        assertEquals(1L, session.checkRuns(format));
    }
}
