package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketPipeline;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.TestPacketEvents;
import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data component items through the real PacketEvents registries. The creative slot is used because it is
 * the packet that reaches the reader for every version since 1.20.5.
 */
class PacketEventsItemReaderTest {
    private static final int STONE = 1;

    private static int id(ComponentType<?> type, ServerVersion version) {
        return type.getId(version.toClientVersion());
    }

    private static final class Fixture {
        final ServerVersion version;
        final TestHarness harness;
        final PlayerData data;

        Fixture(ServerVersion version) {
            TestPacketEvents.install(version);
            PacketEventsItemReader.warmUp(version.toClientVersion());
            this.version = version;
            this.harness = new TestHarness(version.getProtocolVersion(), new PacketEventsItemReader());
            this.data = harness.creativePlayer();
        }

        PacketContext run(byte[] body) throws Exception {
            ClientVersion client = version.toClientVersion();
            int packetId = PacketType.Play.Client.CREATIVE_INVENTORY_ACTION.getId(client);
            ByteBuf frame = Unpooled.wrappedBuffer(Wire.create().v(packetId).raw(body).toBytes());
            PacketReceiveEvent event = TestPacketEvents.event(frame, version);
            int bodyStart = frame.readerIndex();
            PacketContext context = data.context().prepare(PacketKind.CREATIVE_SLOT, frame, bodyStart, frame.writerIndex(),
                    frame.readableBytes() + 1, System.nanoTime(), harness.protocol, data, event);
            PacketPipeline.run(context, harness.checks.chain(PacketKind.CREATIVE_SLOT), harness.checks.malformed,
                    (check, error) -> harness.errors.add(error));
            return context;
        }

        boolean flagged(String id) {
            return harness.flagged(id);
        }
    }

    /** Length prefixes each top level value in the protocols that require it. */
    private static Wire component(Fixture f, Wire item, ComponentType<?> type, byte[] value) {
        item.v(id(type, f.version));
        return f.harness.protocol.v1_21_5 ? item.prefixed(value) : item.raw(value);
    }

    private static Wire header(int present, int absent) {
        return Wire.create().s(36).v(1).v(STONE).v(present).v(absent);
    }

    private static byte[] customData(Fixture f, byte[] nbt) {
        return component(f, header(1, 0), ComponentTypes.CUSTOM_DATA, nbt).toBytes();
    }

    /** {@code container} component holding one item, itself holding another, and so on. */
    private static byte[] nestedContainer(Fixture f, int levels) {
        int container = id(ComponentTypes.CONTAINER, f.version);
        Wire value = Wire.create();
        for (int i = 0; i < levels; i++) {
            // List of one item that has one component: the next container.
            value.v(1).v(1).v(STONE).v(1).v(0).v(container);
        }
        value.v(0);
        return component(f, header(1, 0), ComponentTypes.CONTAINER, value.toBytes()).toBytes();
    }

    @Test
    void emptyAndSimpleItemsPass() throws Exception {
        for (ServerVersion version : new ServerVersion[]{ServerVersion.V_1_20_5, ServerVersion.V_1_21_1, ServerVersion.V_1_21_4, ServerVersion.V_1_21_5}) {
            Fixture f = new Fixture(version);
            assertFalse(f.run(Wire.create().s(36).v(0).toBytes()).isCancelled(), version.name());
            assertFalse(f.run(header(0, 0).toBytes()).isCancelled(), version.name());
            byte[] nbt = Wire.create().nbtRoot(false).nbtString("a", "b").nbtEnd().toBytes();
            assertFalse(f.run(customData(f, nbt)).isCancelled(), version.name() + f.harness.recorder.infos);
            assertTrue(f.harness.recorder.flags.isEmpty(), version.name() + f.harness.recorder.infos);
            assertTrue(f.harness.errors.isEmpty(), f.harness.errors.toString());
        }
    }

    @Test
    void containersWithinTheLimitPass() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertFalse(f.run(nestedContainer(f, 2)).isCancelled(), f.harness.recorder.infos.toString());
    }

    /** The structure that would make a recursive reader overflow: it must stop at the limit. */
    @Test
    void deepContainersStopAtTheLimit() throws Exception {
        for (ServerVersion version : new ServerVersion[]{ServerVersion.V_1_20_5, ServerVersion.V_1_21_5}) {
            Fixture f = new Fixture(version);
            assertTrue(f.run(nestedContainer(f, 5)).isCancelled());
            assertTrue(f.flagged("item.item-depth"), f.harness.recorder.flags.toString());

            Fixture g = new Fixture(version);
            assertTrue(g.run(nestedContainer(g, 20_000)).isCancelled());
            assertTrue(g.flagged("item.item-depth"), g.harness.recorder.flags.toString());
        }
    }

    /** A count near 2^31 must not reserve memory: the frame has only a few bytes. */
    @Test
    void hostileCountsAllocateNothing() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertTimeoutPreemptively(ofSeconds(5), () -> {
            assertTrue(f.run(header(Integer.MAX_VALUE, Integer.MAX_VALUE).toBytes()).isCancelled());
        });
        assertTrue(f.flagged("netty.malformed") || f.flagged("item.component-count"), f.harness.recorder.flags.toString());

        Fixture g = new Fixture(ServerVersion.V_1_20_5);
        byte[] hugeList = component(g, header(1, 0), ComponentTypes.CONTAINER, Wire.create().v(Integer.MAX_VALUE).toBytes()).toBytes();
        assertTimeoutPreemptively(ofSeconds(5), () -> assertTrue(g.run(hugeList).isCancelled()));
        assertTrue(g.flagged("item.list-length"), g.harness.recorder.flags.toString());

        // Inside a nested item, where the top level check does not reach.
        Fixture n = new Fixture(ServerVersion.V_1_20_5);
        byte[] nested = component(n, header(1, 0), ComponentTypes.CONTAINER,
                Wire.create().v(1).v(1).v(STONE).v(Integer.MAX_VALUE).v(0).toBytes()).toBytes();
        assertTimeoutPreemptively(ofSeconds(5), () -> assertTrue(n.run(nested).isCancelled()));

        // A plain list inside a component value goes through BoundedPacketWrapper.
        Fixture l = new Fixture(ServerVersion.V_1_20_5);
        byte[] lore = component(l, header(1, 0), ComponentTypes.LORE, Wire.create().v(Integer.MAX_VALUE).toBytes()).toBytes();
        assertTimeoutPreemptively(ofSeconds(5), () -> assertTrue(l.run(lore).isCancelled()));
        assertTrue(l.flagged("netty.malformed"), l.harness.recorder.flags.toString());
    }

    /**
     * Found on a real server: repeated names collapse in the map PacketEvents builds, so counting after
     * the read saw one list instead of forty. The server still spends time on all of them.
     */
    @Test
    void repeatedNamesStillCountAsNodes() throws Exception {
        for (ServerVersion version : new ServerVersion[]{ServerVersion.V_1_20_5, ServerVersion.V_1_21_5}) {
            Fixture f = new Fixture(version);
            assertTrue(f.run(customData(f, Wire.nbtManyNodes(40, 1000, false))).isCancelled(), version.name());
            assertTrue(f.flagged("item.nbt-nodes"), f.harness.recorder.flags.toString());
        }
    }

    @Test
    void nbtLimitsApplyBeforeTheRecursiveRead() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_21_5);
        assertTrue(f.run(customData(f, Wire.nbtDeep(300, false))).isCancelled());
        assertTrue(f.flagged("item.nbt-depth"), f.harness.recorder.flags.toString());
        assertTrue(f.harness.errors.isEmpty(), f.harness.errors.toString());
    }

    /** Before 1.21.2 the lock component is a tag holding only a string, not a compound. */
    @Test
    void nonCompoundRootsAreValid() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        byte[] value = Wire.create().b(8).s(3).raw("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toBytes();
        assertFalse(f.run(component(f, header(1, 0), ComponentTypes.LOCK, value).toBytes()).isCancelled(),
                f.harness.recorder.infos.toString());
        assertTrue(f.harness.recorder.flags.isEmpty(), f.harness.recorder.infos.toString());
    }

    @Test
    void negativeCountsAreStructureErrors() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(f.run(header(-1, 0).toBytes()).isCancelled());
        assertTrue(f.flagged("netty.malformed"));
    }

    @Test
    void tooManyComponents() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(f.run(header(65, 0).toBytes()).isCancelled());
        assertTrue(f.flagged("item.component-count"));
    }

    @Test
    void limitsApplyInsideComponentValues() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(f.run(customData(f, Wire.nbtDeep(40, false))).isCancelled());
        assertTrue(f.flagged("item.nbt-depth"), f.harness.recorder.flags.toString() + f.harness.recorder.infos);

        Fixture s = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(s.run(customData(s, Wire.nbtLongString(32768, false))).isCancelled());
        assertTrue(s.flagged("item.string-length"), s.harness.recorder.flags.toString() + s.harness.recorder.infos);

        Fixture a = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(a.run(customData(a, Wire.nbtBigArray(20_000, false))).isCancelled());
        assertTrue(a.flagged("item.array-length"), a.harness.recorder.flags.toString() + a.harness.recorder.infos);
    }

    @Test
    void lengthPrefixMustMatchTheValue() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_21_5);
        byte[] nbt = Wire.create().nbtRoot(false).nbtEnd().toBytes();
        Wire wrong = header(1, 0).v(id(ComponentTypes.CUSTOM_DATA, f.version)).v(nbt.length + 4).raw(nbt).raw(new byte[4]);
        assertTrue(f.run(wrong.toBytes()).isCancelled());
        assertTrue(f.flagged("netty.malformed"));

        Fixture g = new Fixture(ServerVersion.V_1_21_5);
        Wire past = header(1, 0).v(id(ComponentTypes.CUSTOM_DATA, g.version)).v(5000).raw(nbt);
        assertTrue(g.run(past.toBytes()).isCancelled());
        assertTrue(g.flagged("netty.malformed"));
    }

    @Test
    void readerIndexIsRestoredSoTheFrameReachesTheServerIntact() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        ClientVersion client = f.version.toClientVersion();
        int packetId = PacketType.Play.Client.CREATIVE_INVENTORY_ACTION.getId(client);
        byte[] nbt = Wire.create().nbtRoot(false).nbtString("a", "b").nbtEnd().toBytes();
        ByteBuf frame = Unpooled.wrappedBuffer(Wire.create().v(packetId).raw(customData(f, nbt)).toBytes());
        PacketReceiveEvent event = TestPacketEvents.event(frame, f.version);
        int before = frame.readerIndex();
        int writer = frame.writerIndex();
        PacketContext context = f.data.context().prepare(PacketKind.CREATIVE_SLOT, frame, before, writer,
                frame.readableBytes() + 1, System.nanoTime(), f.harness.protocol, f.data, event);
        PacketPipeline.run(context, f.harness.checks.chain(PacketKind.CREATIVE_SLOT), f.harness.checks.malformed,
                (check, error) -> f.harness.errors.add(error));
        assertEquals(before, frame.readerIndex());
        assertEquals(writer, frame.writerIndex());
        assertFalse(context.isCancelled(), f.harness.recorder.flags.toString() + f.harness.recorder.infos);
    }

    @Test
    void unknownItemIdIsNotTreatedAsAnAttack() throws Exception {
        Fixture f = new Fixture(ServerVersion.V_1_20_5);
        assertTrue(f.run(Wire.create().s(36).v(1).v(9_000_000).v(0).v(0).toBytes()).isCancelled());
        assertTrue(f.flagged("netty.decode-failure"), f.harness.recorder.flags.toString() + f.harness.recorder.infos);
        assertFalse(f.flagged("netty.malformed"));
        assertTrue(f.harness.recorder.punishments.isEmpty(), "uma falha de decodificação não pune");
    }
}
