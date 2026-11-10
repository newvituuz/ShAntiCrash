package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.checks.item.DecodingGuard;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.item.LegacyItemParser;
import br.com.singlehope.shanticrash.item.NbtScanner;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecodingGuardTest {
    private static byte[] heavyNbt(int bytes) {
        return Wire.create().nbtRoot(true).nbtString("s", Wire.repeat('x', bytes)).nbtEnd().toBytes();
    }

    private static byte[] item(byte[] nbt) {
        return Wire.create().s(1).b(1).s(0).raw(nbt).toBytes();
    }

    /** The layout that results from compaction: same item, no data. */
    private static byte[] strippedItem() {
        return Wire.create().s(1).b(1).s(0).b(0).toBytes();
    }

    /** Parses a sequence of items and returns the report the guard would receive. */
    private static ItemReport analyze(ByteBuf buffer, int start, int protocolVersion, int skipBetween) {
        ServerProtocol protocol = ServerProtocol.of(protocolVersion);
        ItemReport report = new ItemReport().reset();
        PacketReader reader = new PacketReader().reset(buffer, start, buffer.writerIndex());
        NbtScanner scanner = new NbtScanner();
        ItemLimits limits = new ItemLimits();
        while (reader.hasRemaining()) {
            if (!LegacyItemParser.read(reader, protocol, scanner, limits, report)) {
                break;
            }
            if (reader.remaining() > skipBetween) {
                reader.skip(skipBetween);
            }
        }
        return report;
    }

    @Test
    void compactsHeavyItemsInPlaceKeepingEverythingElse() {
        byte[] heavy = item(heavyNbt(5000));
        byte[] light = item(Wire.create().nbtRoot(true).nbtByte("a", 1).nbtEnd().toBytes());
        byte[] slotA = {0, 9};
        byte[] slotB = {0, 10};
        Wire packet = Wire.create().raw(new byte[]{7, 7}).raw(heavy).raw(slotA).raw(light).raw(slotB).raw(heavy);
        ByteBuf buffer = Unpooled.wrappedBuffer(packet.toBytes());
        ItemReport report = analyze(buffer, 2, 47, 2);
        assertTrue(report.ok());
        assertEquals(3, report.topLevelItems());

        assertEquals(2, DecodingGuard.compact(buffer, report, 2048, false));

        byte[] expected = Wire.create().raw(new byte[]{7, 7}).raw(strippedItem()).raw(slotA).raw(light).raw(slotB).raw(strippedItem()).toBytes();
        byte[] actual = new byte[buffer.writerIndex()];
        buffer.getBytes(0, actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void thresholdZeroCompactsEveryItemWithData() {
        byte[] light = item(Wire.create().nbtRoot(true).nbtByte("a", 1).nbtEnd().toBytes());
        ByteBuf buffer = Unpooled.wrappedBuffer(light.clone());
        ItemReport report = analyze(buffer, 0, 47, 0);
        assertEquals(1, DecodingGuard.compact(buffer, report, 0, false));
        byte[] actual = new byte[buffer.writerIndex()];
        buffer.getBytes(0, actual);
        assertArrayEquals(strippedItem(), actual);
    }

    @Test
    void nothingChangesWithoutHeavyItems() {
        byte[] light = item(new byte[]{0});
        ByteBuf buffer = Unpooled.wrappedBuffer(light.clone());
        ItemReport report = analyze(buffer, 0, 47, 0);
        assertEquals(0, DecodingGuard.compact(buffer, report, 0, false));
        assertEquals(light.length, buffer.writerIndex());
    }

    @Test
    void componentItemsGetTwoZeroCounts() {
        // VarInt count, VarInt id, then the component counts and values.
        byte[] packet = Wire.create().v(3).v(800).v(1).v(0).v(7).raw(new byte[3000]).toBytes();
        ByteBuf buffer = Unpooled.wrappedBuffer(packet);
        ItemReport report = new ItemReport().reset();
        int headerEnd = 1 + 2;
        report.addTopLevelItem(0, headerEnd, packet.length, true);
        assertEquals(1, DecodingGuard.compact(buffer, report, 2048, true));
        byte[] actual = new byte[buffer.writerIndex()];
        buffer.getBytes(0, actual);
        assertArrayEquals(Wire.create().v(3).v(800).v(0).v(0).toBytes(), actual);
    }

    /** The compacted packet must be what the protocol expects: analysing it again finds no problems. */
    @Test
    void compactedPacketIsStillValid() {
        TestHarness h = new TestHarness(340);
        h.checks.decodingGuard.configure(DecodingGuard.Mode.SMART, Collections.<String>emptySet(), Collections.<String>emptySet());
        ServerProtocol p = h.protocol;
        byte[] heavy = item(heavyNbt(4000));
        Wire click = Wire.create().b(0).s(3).b(0).s(1).v(0).raw(heavy);
        ByteBuf buffer = Unpooled.wrappedBuffer(click.toBytes());
        PlayerData d = h.newPlayer();
        PacketContext context = h.run(d, PacketKind.CLICK_WINDOW, buffer, 1000L);
        assertFalse(context.isCancelled());
        assertTrue(buffer.writerIndex() < click.size());

        h.run(d, PacketKind.CLICK_WINDOW, buffer, 2000L);
        assertTrue(h.recorder.flags.isEmpty());
        assertEquals(1 + 2 + 1 + 2 + 1 + strippedItem().length, buffer.writerIndex());
        assertTrue(p.protocol == 340);
    }

    @Test
    void modeNoneAndCreativeLeaveClicksAlone() {
        for (DecodingGuard.Mode mode : Arrays.asList(DecodingGuard.Mode.NONE, DecodingGuard.Mode.CREATIVE)) {
            TestHarness h = new TestHarness(340);
            h.checks.decodingGuard.configure(mode, Collections.<String>emptySet(), Collections.<String>emptySet());
            Wire click = Wire.create().b(0).s(3).b(0).s(1).v(0).raw(item(heavyNbt(4000)));
            ByteBuf buffer = Unpooled.wrappedBuffer(click.toBytes());
            h.run(h.newPlayer(), PacketKind.CLICK_WINDOW, buffer, 1000L);
            assertEquals(click.size(), buffer.writerIndex());
        }
    }

    @Test
    void modeAllCompactsEverythingButNotViolatingPackets() {
        TestHarness h = new TestHarness(340);
        h.checks.decodingGuard.configure(DecodingGuard.Mode.ALL, Collections.<String>emptySet(), Collections.<String>emptySet());
        Wire light = Wire.create().b(0).s(3).b(0).s(1).v(0).raw(item(Wire.create().nbtRoot(true).nbtByte("a", 1).nbtEnd().toBytes()));
        ByteBuf buffer = Unpooled.wrappedBuffer(light.toBytes());
        h.run(h.newPlayer(), PacketKind.CLICK_WINDOW, buffer, 1000L);
        assertTrue(buffer.writerIndex() < light.size());
    }

    @Test
    void hashedClicksAreNeverTouched() {
        TestHarness h = new TestHarness(770);
        h.checks.decodingGuard.configure(DecodingGuard.Mode.ALL, Collections.<String>emptySet(), Collections.<String>emptySet());
        Wire click = Wire.create().v(0).v(1).s(3).b(0).v(0).v(0).bool(false);
        ByteBuf buffer = Unpooled.wrappedBuffer(click.toBytes());
        h.run(h.newPlayer(), PacketKind.CLICK_WINDOW, buffer, 1000L);
        assertEquals(click.size(), buffer.writerIndex());
        assertTrue(h.recorder.flags.isEmpty());
    }

    @Test
    void analyzerUsesTheNewLimitsAfterReload() {
        ItemAnalyzer analyzer = new ItemAnalyzer(null);
        ItemLimits strict = new ItemLimits();
        strict.maxNbtDepth = 2;
        analyzer.limits(strict);
        TestHarness h = new TestHarness(340);
        ItemLimits limits = new ItemLimits();
        limits.maxNbtDepth = 2;
        h.checks.applyItemLimits(limits);
        PlayerData d = h.creativePlayer();
        h.run(d, PacketKind.CREATIVE_SLOT, Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtDeep(3, true)));
        assertTrue(h.flagged("item.nbt-depth"));
    }

    @Test
    void disablingALimitCheckRemovesTheLimit() {
        TestHarness h = new TestHarness(340);
        CheckSettings off = new CheckSettings();
        off.enabled = false;
        h.check("item.nbt-depth").configure(off);
        h.checks.applyItemLimits(new ItemLimits());
        assertEquals(ItemLimits.UNLIMITED, h.checks.analyzer().limits().maxNbtDepth);
        PlayerData d = h.creativePlayer();
        h.run(d, PacketKind.CREATIVE_SLOT, Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtDeep(100, true)));
        assertFalse(h.flagged("item.nbt-depth"));
    }
}
