package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each attack goes through the full chain, so these also confirm that nothing earlier in the chain
 * stops it first and that legitimate traffic passes through everything.
 */
class ChecksTest {
    private static void assertRejected(TestHarness harness, PlayerData data, PacketKind kind, Wire body, String expected) {
        PacketContext context = harness.run(data, kind, body);
        assertTrue(harness.flagged(expected), "esperava " + expected + ", houve " + harness.recorder.flags);
        assertTrue(context.isCancelled(), "pacote deveria ser cancelado");
        assertTrue(harness.errors.isEmpty(), "erro: " + harness.errors);
    }

    private static void assertAccepted(TestHarness harness, PlayerData data, PacketKind kind, Wire body) {
        PacketContext context = harness.run(data, kind, body);
        assertTrue(harness.recorder.flags.isEmpty(), "violações inesperadas: " + harness.recorder.flags + harness.recorder.infos);
        assertFalse(context.isCancelled());
        assertTrue(harness.errors.isEmpty(), "erro: " + harness.errors);
    }

    // ------------------------------------------------------------------ position

    private static Wire position(double x, double y, double z) {
        return Wire.create().d(x).d(y).d(z).b(1);
    }

    private static Wire positionRotation(double x, double y, double z, float yaw, float pitch) {
        return Wire.create().d(x).d(y).d(z).f(yaw).f(pitch).b(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 754, 758, 762, 765, 766, 769, 774, 775, 777})
    void validMovementPasses(int protocol) {
        TestHarness h = new TestHarness(protocol);
        PlayerData d = h.newPlayer();
        assertAccepted(h, d, PacketKind.MOVE_POSITION, position(10.5, 70, -3.25));
        assertAccepted(h, d, PacketKind.MOVE_POSITION_ROTATION, positionRotation(1, 2, 3, 7200.5F, -90F));
        assertAccepted(h, d, PacketKind.MOVE_ROTATION, Wire.create().f(180F).f(45F).b(0));
        assertAccepted(h, d, PacketKind.MOVE_FLYING, Wire.create().b(1));
    }

    @Test
    void nanInfinityAndBounds() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            TestHarness h = new TestHarness(47);
            assertRejected(h, h.newPlayer(), PacketKind.MOVE_POSITION, position(bad, 64, 0), "position.invalid-numbers");
        }
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_ROTATION, Wire.create().f(Float.NaN).f(0F).b(0), "position.invalid-numbers");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_POSITION, position(3.1E7, 64, 0), "position.bounds");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_POSITION, position(0, 2.1E7, 0), "position.bounds");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_POSITION_ROTATION, positionRotation(0, 64, 0, 0F, 400F), "position.rotation");
    }

    @Test
    void movementWithWrongSize() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_POSITION, Wire.create().d(1).d(2), "position.format");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.MOVE_FLYING, Wire.create().raw(new byte[64]), "position.format");
    }

    @Test
    void vehiclePacketGrewOneByteIn1214() {
        Wire without = Wire.create().d(1).d(2).d(3).f(0F).f(0F);
        TestHarness old = new TestHarness(768);
        assertAccepted(old, old.newPlayer(), PacketKind.VEHICLE_MOVE, without);
        TestHarness modern = new TestHarness(769);
        assertRejected(modern, modern.newPlayer(), PacketKind.VEHICLE_MOVE, without, "position.format");
    }

    // ------------------------------------------------------------------ window

    private static Wire click(ServerProtocol p, int window, int slot, int button, int mode, byte[] items) {
        Wire w = Wire.create();
        if (p.v1_21_2) {
            w.v(window);
        } else {
            w.b(window);
        }
        if (p.v1_17_1) {
            w.v(1);
        }
        w.s(slot).b(button);
        if (!p.v1_17) {
            w.s(7);
        }
        w.v(mode);
        return w.raw(items);
    }

    /** An empty item, in the layout of the protocol. */
    private static byte[] emptyItem(ServerProtocol p) {
        if (p.v1_20_5) {
            return new byte[]{0};
        }
        if (p.v1_13_2) {
            return new byte[]{0};
        }
        return Wire.create().s(-1).toBytes();
    }

    private static byte[] itemWithNbt(ServerProtocol p, byte[] nbt) {
        Wire w = Wire.create();
        if (p.v1_13_2) {
            w.bool(true).v(1).b(1);
        } else if (p.v1_13) {
            w.s(1).b(1);
        } else {
            w.s(1).b(1).s(0);
        }
        return w.raw(nbt).toBytes();
    }

    /** Items of a click packet of the given protocol, when it uses whole items. */
    private static byte[] clickBody(ServerProtocol p, byte[] carried) {
        Wire w = Wire.create();
        if (p.v1_21_5) {
            return w.v(0).bool(false).toBytes();
        }
        if (p.v1_17) {
            w.v(0);
        }
        return w.raw(carried).toBytes();
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 754, 755, 756, 758, 762, 765, 770, 774, 775, 777})
    void validClicksPass(int protocol) {
        TestHarness h = new TestHarness(protocol);
        PlayerData d = h.newPlayer();
        ServerProtocol p = h.protocol;
        byte[] body = clickBody(p, p.v1_20_5 ? new byte[]{0} : emptyItem(p));
        assertAccepted(h, d, PacketKind.CLICK_WINDOW, click(p, 0, 10, 0, 0, body));
        assertAccepted(h, d, PacketKind.CLICK_WINDOW, click(p, 0, -999, 0, 0, body));
        assertAccepted(h, d, PacketKind.CLICK_WINDOW, click(p, 0, 12, 40, 2, body));
    }

    @Test
    void invalidClicks() {
        ServerProtocol p = ServerProtocol.of(340);
        byte[] body = clickBody(p, emptyItem(p));
        TestHarness h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, -5, 0, 0, body), "window.invalid-slot");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 30000, 0, 0, body), "window.invalid-slot");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 77, 0, body), "window.invalid-mode");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 99, body), "window.invalid-mode");
    }

    @Test
    void slotBoundsFollowWhatTheServerSent() {
        ServerProtocol p = ServerProtocol.of(340);
        byte[] body = clickBody(p, emptyItem(p));
        TestHarness h = new TestHarness(340);
        PlayerData d = h.newPlayer();
        d.openWindowId = 3;
        d.openWindowSlots = 63;
        assertAccepted(h, d, PacketKind.CLICK_WINDOW, click(p, 3, 62, 0, 0, body));
        assertRejected(h, d, PacketKind.CLICK_WINDOW, click(p, 3, 63, 0, 0, body), "window.invalid-slot");
    }

    @Test
    void clickItemsAreAnalysed() {
        ServerProtocol p = ServerProtocol.of(340);
        TestHarness h = new TestHarness(340);
        byte[] nbt = Wire.nbtDeep(200, true);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 0, itemWithNbt(p, nbt)), "item.nbt-depth");
    }

    @Test
    void truncatedClickIsMalformed() {
        TestHarness h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, Wire.create().b(0), "netty.malformed");
    }

    @Test
    void trailingBytesAreMalformed() {
        ServerProtocol p = ServerProtocol.of(340);
        TestHarness h = new TestHarness(340);
        byte[] items = Wire.create().raw(emptyItem(p)).raw(new byte[4]).toBytes();
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 0, items), "netty.malformed");
    }

    @Test
    void hashedClicks() {
        ServerProtocol p = ServerProtocol.of(770);
        Wire stack = Wire.create().v(1).s(5).bool(true).v(1).v(1).v(1000).v(0);
        TestHarness h = new TestHarness(770);
        assertRejected(h, h.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 0, stack.toBytes()), "window.hashed-stacks");

        TestHarness ok = new TestHarness(770);
        byte[] valid = Wire.create().v(1).s(5).bool(true).v(1).v(1).v(1).v(3).i(99).v(0).bool(false).toBytes();
        assertAccepted(ok, ok.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 0, valid));

        TestHarness many = new TestHarness(770);
        byte[] tooMany = Wire.create().v(50_000).toBytes();
        assertRejected(many, many.newPlayer(), PacketKind.CLICK_WINDOW, click(p, 0, 1, 0, 0, tooMany), "window.hashed-stacks");
    }

    // ------------------------------------------------------------------ creative

    private static Wire creative(ServerProtocol p, int slot, byte[] item) {
        return Wire.create().s(slot).raw(item);
    }

    @Test
    void survivalCanNotUseCreativePackets() {
        ServerProtocol p = ServerProtocol.of(340);
        TestHarness h = new TestHarness(340);
        PlayerData d = h.newPlayer();
        byte[] item = itemWithNbt(p, Wire.nbtDeep(20, true));
        PacketContext context = null;
        for (int i = 0; i < 10; i++) {
            context = h.run(d, PacketKind.CREATIVE_SLOT, creative(p, 5, item));
            assertTrue(context.isCancelled());
        }
        assertTrue(h.flagged("creative.not-creative"));
        assertFalse(h.flagged("item.nbt-depth"), "análise não deveria ser alcançada");
    }

    @Test
    void gameModeChangeHasGracePeriod() {
        ServerProtocol p = ServerProtocol.of(340);
        TestHarness h = new TestHarness(340);
        PlayerData d = h.newPlayer();
        d.creative(false, System.nanoTime());
        for (int i = 0; i < 10; i++) {
            assertTrue(h.run(d, PacketKind.CREATIVE_SLOT, creative(p, 5, emptyItem(p))).isCancelled());
        }
        assertTrue(h.recorder.flags.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 393, 404, 754, 765})
    void creativeItemsPerProtocol(int protocol) {
        TestHarness h = new TestHarness(protocol);
        ServerProtocol p = h.protocol;
        boolean named = !p.namelessNbtRoot();
        assertAccepted(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, emptyItem(p)));

        byte[] normal = Wire.create().nbtRoot(named).nbtOpenCompound("display").nbtString("Name", "x").nbtEnd().nbtEnd().toBytes();
        assertAccepted(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, normal)));

        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtDeep(50, named))), "item.nbt-depth");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtManyNodes(40, 1000, named))), "item.nbt-nodes");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtLongString(32768, named))), "item.string-length");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtBigArray(20_000, named))), "item.array-length");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtBigList(5_000, named))), "item.list-length");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtNestedContainers(6, named))), "item.item-depth");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 500, emptyItem(p)), "creative.invalid-slot");
        h = new TestHarness(protocol);
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, itemWithNbt(p, Wire.nbtText("{\"action\":\"run_command\",\"value\":\"/op a\"}", named))), "creative.run-command");
    }

    @Test
    void negativeStackAmount() {
        TestHarness h = new TestHarness(340);
        ServerProtocol p = h.protocol;
        byte[] item = Wire.create().s(1).b(-3).s(0).b(0).toBytes();
        assertRejected(h, h.creativePlayer(), PacketKind.CREATIVE_SLOT, creative(p, 36, item), "item.amount");
    }

    // ------------------------------------------------------------------ place

    private static long packed(boolean v114, int x, int y, int z) {
        if (v114) {
            return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
        }
        return ((x & 0x3FFFFFFL) << 38) | ((y & 0xFFFL) << 26) | (z & 0x3FFFFFFL);
    }

    private static Wire placement(ServerProtocol p, int hand, long position, int face, float cx, float cy, float cz) {
        Wire w = Wire.create();
        if (p.v1_14) {
            w.v(hand).l(position).v(face).f(cx).f(cy).f(cz).bool(false);
            if (p.v1_21_2) {
                w.bool(false);
            }
            if (p.v1_19) {
                w.v(1);
            }
            return w;
        }
        if (!p.v1_9) {
            return w.l(position).b(face).raw(emptyItem(p)).b(8).b(8).b(8);
        }
        w.l(position).v(face).v(hand);
        return p.v1_11 ? w.f(cx).f(cy).f(cz) : w.b(8).b(8).b(8);
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 107, 210, 340, 477, 754, 759, 765, 768, 774, 777})
    void validPlacementPasses(int protocol) {
        TestHarness h = new TestHarness(protocol);
        ServerProtocol p = h.protocol;
        assertAccepted(h, h.newPlayer(), PacketKind.BLOCK_PLACE,
                placement(p, 0, packed(p.v1_14, 100, 64, -200), 1, 0.5F, 0.5F, 0.5F));
    }

    @Test
    void legacyUseInAirLayoutIsValid() {
        TestHarness h = new TestHarness(47);
        ServerProtocol p = h.protocol;
        Wire w = Wire.create().l(-1L).b(255).raw(emptyItem(p)).b(0).b(0).b(0);
        assertAccepted(h, h.newPlayer(), PacketKind.BLOCK_PLACE, w);
    }

    @Test
    void printerModsKeepWorking() {
        TestHarness h = new TestHarness(765);
        ServerProtocol p = h.protocol;
        assertAccepted(h, h.newPlayer(), PacketKind.BLOCK_PLACE,
                placement(p, 0, packed(true, 1, 64, 1), 1, 18.5F, 0.5F, 0.5F));
    }

    @Test
    void invalidPlacements() {
        TestHarness h = new TestHarness(765);
        ServerProtocol p = h.protocol;
        assertRejected(h, h.newPlayer(), PacketKind.BLOCK_PLACE, placement(p, 0, packed(true, 0, 64, 0), 9, 0, 0, 0), "place.invalid-placement");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.BLOCK_PLACE, placement(p, 7, packed(true, 0, 64, 0), 1, 0, 0, 0), "place.invalid-placement");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.BLOCK_PLACE, placement(p, 0, packed(true, 33_000_000, 64, 0), 1, 0, 0, 0), "place.invalid-placement");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.BLOCK_PLACE, placement(p, 0, packed(true, 0, 64, 0), 1, Float.NaN, 0, 0), "place.invalid-placement");
        h = new TestHarness(47);
        assertRejected(h, h.newPlayer(), PacketKind.BLOCK_PLACE, Wire.create().l(0).b(9).s(-1).b(0).b(0).b(0), "place.legacy-header");
    }

    @Test
    void invalidInteractions() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.USE_ITEM, Wire.create().v(4).v(1), "place.invalid-use");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.DIGGING, Wire.create().v(99).l(0).b(1).v(1), "place.invalid-dig");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.INTERACT_ENTITY, Wire.create().v(1).v(9), "place.invalid-interact");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.INTERACT_ENTITY,
                Wire.create().v(1).v(2).f(Float.NaN).f(0).f(0).v(0).bool(false), "place.invalid-interact");
        h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.DIGGING, Wire.create().v(2).l(packed(true, 1, 64, 1)).b(1).v(4));
        h = new TestHarness(47);
        assertAccepted(h, h.newPlayer(), PacketKind.DIGGING, Wire.create().v(5).l(0).b(255));
    }

    // ------------------------------------------------------------------ payload

    private static Wire payload(String channel, byte[] data) {
        return Wire.create().str(channel).raw(data);
    }

    @Test
    void legitimateChannelsPass() {
        TestHarness modern = new TestHarness(765);
        PlayerData d = modern.newPlayer();
        List<String> channels = Arrays.asList("minecraft:brand", "fabric:registry/sync", "floodgate:form",
                "geyser:handshake", "bungeecord:main", "worldedit:cui", "axiom:hello");
        for (String channel : channels) {
            assertAccepted(modern, d, PacketKind.PLUGIN_MESSAGE, payload(channel, new byte[32]));
        }
        TestHarness legacy = new TestHarness(47);
        PlayerData old = legacy.newPlayer();
        for (String channel : Arrays.asList("MC|Brand", "REGISTER", "BungeeCord", "WDL|INIT", "FML|HS")) {
            assertAccepted(legacy, old, PacketKind.PLUGIN_MESSAGE, payload(channel, new byte[8]));
        }
    }

    @Test
    void invalidChannels() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("Bad Channel!", new byte[1]), "payload.invalid-channel");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload(Wire.repeat('a', 300), new byte[1]), "payload.invalid-channel");
        h = new TestHarness(47);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("MC|\u0001Brand", new byte[1]), "payload.invalid-channel");
    }

    @Test
    void oversizedPayload() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("a:b", new byte[40_000]), "payload.size");
    }

    @Test
    void registerAbuse() {
        TestHarness h = new TestHarness(765);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            names.append("a:").append(i).append('\0');
        }
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("minecraft:register", names.toString().getBytes()), "payload.register");
    }

    @Test
    void brandAbuse() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("minecraft:brand", new byte[400]), "payload.brand");
    }

    // ------------------------------------------------------------------ book

    private static Wire book(int slot, int pages, int pageLength) {
        Wire w = Wire.create().v(slot).v(pages);
        for (int i = 0; i < pages; i++) {
            w.str(Wire.repeat('a', pageLength));
        }
        return w.bool(false);
    }

    @Test
    void books() {
        TestHarness h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.EDIT_BOOK, book(0, 3, 256));
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.EDIT_BOOK, book(0, 101, 1), "book.content");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.EDIT_BOOK, book(0, 1, 4000), "book.content");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.EDIT_BOOK, book(900, 1, 1), "book.content");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.EDIT_BOOK, Wire.create().v(0).v(1), "netty.malformed");
    }

    @Test
    void legacyBooksInChannels() {
        TestHarness h = new TestHarness(47);
        ServerProtocol p = h.protocol;
        byte[] item = itemWithNbt(p, Wire.nbtBook(200, 10, true));
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("MC|BEdit", item), "item.book-content");
        h = new TestHarness(340);
        p = h.protocol;
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("MC|BSign", itemWithNbt(p, Wire.nbtDeep(100, true))), "item.nbt-depth");
    }

    @Test
    void signsAndNames() {
        TestHarness h = new TestHarness(765);
        Wire sign = Wire.create().l(0).bool(true).str("a").str(Wire.repeat('b', 2000)).str("").str("");
        assertRejected(h, h.newPlayer(), PacketKind.UPDATE_SIGN, sign, "book.sign-text");

        h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.UPDATE_SIGN, Wire.create().l(0).bool(true).str("a").str("b").str("c").str("d"));
        // The side flag moved to the end in 26.3.
        h = new TestHarness(777);
        assertAccepted(h, h.newPlayer(), PacketKind.UPDATE_SIGN, Wire.create().l(0).str("a").str("b").str("c").str("d").bool(true));
        h = new TestHarness(47);
        assertAccepted(h, h.newPlayer(), PacketKind.UPDATE_SIGN, Wire.create().l(0).str("a").str("b").str("c").str("d"));

        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.NAME_ITEM, Wire.create().str(Wire.repeat('a', 2000)), "book.item-name");
    }

    // ------------------------------------------------------------------ tab

    @Test
    void tabComplete() {
        TestHarness h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().v(1).str("/gamemode cr"));
        h = new TestHarness(47);
        assertAccepted(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().str("/gamemode cr").bool(false));
        h = new TestHarness(340);
        assertAccepted(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().str("/tp ").bool(true).bool(false));

        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().v(1).str(Wire.repeat('a', 9000)), "tab.length");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().v(1).str("//calc for(i=0;i<1e9;i++)"), "tab.pattern");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().v(1).str("/a @e[nbt={a:" + Wire.repeat('[', 40)), "tab.pattern");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.TAB_COMPLETE, Wire.create().v(-1).str("/a"), "tab.structure");
    }

    // ------------------------------------------------------------------ command

    @Test
    void commands() {
        TestHarness h = new TestHarness(340);
        PlayerData d = h.newPlayer();
        assertAccepted(h, d, PacketKind.CHAT_MESSAGE, Wire.create().str("olá, tudo bem? //calc é legal"));
        assertAccepted(h, d, PacketKind.CHAT_MESSAGE, Wire.create().str("/mv list"));
        assertAccepted(h, d, PacketKind.CHAT_MESSAGE, Wire.create().str("/calculator"));
        assertAccepted(h, d, PacketKind.CHAT_MESSAGE, Wire.create().str("//calculadora"));

        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_MESSAGE, Wire.create().str("//calc 1+1"), "command.blocked");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_MESSAGE, Wire.create().str("/MV ^(.*)$"), "command.blocked");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_COMMAND, Wire.create().str("/calc"), "command.blocked");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_MESSAGE, Wire.create().str("oi\u0000"), "command.illegal-characters");
        h = new TestHarness(340);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_MESSAGE, Wire.create().str(Wire.repeat('a', 2000)), "command.length");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.CHAT_COMMAND, Wire.create().str("tp " + Wire.repeat('[', 40)), "command.complexity");
    }

    // ------------------------------------------------------------------ recipe

    @Test
    void recipes() {
        TestHarness h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().b(0).str("minecraft:stick").bool(false));
        h = new TestHarness(340);
        assertAccepted(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().b(0).v(10).bool(false));
        h = new TestHarness(774);
        assertAccepted(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().v(0).v(10).bool(false));

        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().b(0).str("Inválido Aqui").bool(false), "recipe.invalid-request");
        h = new TestHarness(774);
        assertRejected(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().v(0).v(-1).bool(false), "recipe.invalid-request");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.CRAFT_RECIPE, Wire.create().b(0).str("a:b"), "netty.malformed");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.RECIPE_DISPLAYED, Wire.create().str("MAIÚSCULA"), "recipe.invalid-displayed");
    }

    // ------------------------------------------------------------------ netty

    @Test
    void frameLimitsDependOnProtocolAndState() {
        TestHarness h = new TestHarness(47);
        PlayerData d = h.newPlayer();
        assertRejected(h, d, PacketKind.PLUGIN_MESSAGE, payload("MC|Brand", new byte[70_000]), "netty.frame-size");
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.PLUGIN_MESSAGE, payload("a:b", new byte[300_000]), "netty.frame-size");
        h = new TestHarness(765);
        assertAccepted(h, h.newPlayer(), PacketKind.LOGIN_PLUGIN_RESPONSE, Wire.create().raw(new byte[500_000]));
        h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.HANDSHAKE, Wire.create().raw(new byte[70_000]), "netty.frame-size");
    }

    @Test
    void smallPacketsMustBeSmall() {
        TestHarness h = new TestHarness(765);
        assertRejected(h, h.newPlayer(), PacketKind.KEEP_ALIVE, Wire.create().raw(new byte[5_000]), "netty.small-packet");
    }

    @Test
    void connectionStateRepeats() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        assertFalse(h.run(d, PacketKind.LOGIN_START, Wire.create().str("a")).isCancelled());
        assertTrue(h.run(d, PacketKind.LOGIN_START, Wire.create().str("a")).isCancelled());
        assertTrue(h.flagged("netty.connection-state"));
    }

    // ------------------------------------------------------------------ flood

    private static void flood(TestHarness h, PlayerData d, PacketKind kind, Consumer<Wire> body, int packets, long gapNanos) {
        long now = 1_000L;
        for (int i = 0; i < packets; i++) {
            Wire w = Wire.create();
            body.accept(w);
            h.run(d, kind, w.toBytes(), now);
            now += gapNanos;
        }
    }

    @Test
    void floodIsPerTypeAndRespectsDifferentRates() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        // 20 movement packets per second for a minute: the normal rate of a client.
        flood(h, d, PacketKind.MOVE_POSITION, w -> w.d(0).d(64).d(0).b(1), 1200, 50_000_000L);
        assertFalse(h.flagged("flood.packet-limits"));

        // Instantly flooding movement exceeds the movement limit.
        TestHarness attack = new TestHarness(765);
        PlayerData victim = attack.newPlayer();
        flood(attack, victim, PacketKind.MOVE_POSITION, w -> w.d(0).d(64).d(0).b(1), 3000, 1_000L);
        assertTrue(attack.flagged("flood.packet-limits"));

        // A book every two seconds is fine, 200 at once is not.
        TestHarness books = new TestHarness(765);
        PlayerData writer = books.newPlayer();
        flood(books, writer, PacketKind.EDIT_BOOK, w -> w.v(0).v(0).bool(false), 30, 2_000_000_000L);
        assertTrue(books.recorder.flags.isEmpty());
        flood(books, writer, PacketKind.EDIT_BOOK, w -> w.v(0).v(0).bool(false), 200, 1_000L);
        assertTrue(books.flagged("flood.packet-limits"));
    }

    @Test
    void droppingGroupsCancelExcessEvenBeforeAViolation() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        int cancelled = 0;
        long now = 1_000L;
        for (int i = 0; i < 30; i++) {
            if (h.run(d, PacketKind.EDIT_BOOK, Wire.create().v(0).v(0).bool(false).toBytes(), now++).isCancelled()) {
                cancelled++;
            }
        }
        assertEquals(30 - 8, cancelled);
    }

    @Test
    void movementFloodIsNeverDropped() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        long now = 1_000L;
        // Below the violation threshold of the per-type limiter and below global: nothing is dropped.
        for (int i = 0; i < 415; i++) {
            assertFalse(h.run(d, PacketKind.MOVE_POSITION, position(0, 64, 0).toBytes(), now++).isCancelled());
        }
    }

    @Test
    void bytesAreLimited() {
        TestHarness h = new TestHarness(765);
        PlayerData d = h.newPlayer();
        long now = 1_000L;
        byte[] big = payload("a:b", new byte[20_000]).toBytes();
        for (int i = 0; i < 3_000; i++) {
            h.run(d, PacketKind.PLUGIN_MESSAGE, big, now);
            now += 1_000L;
        }
        assertTrue(h.flagged("flood.big-frames") || h.flagged("netty.byte-rate"));
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void disabledChecksDoNothing() {
        TestHarness h = new TestHarness(765);
        CheckSettings off = new CheckSettings();
        off.enabled = false;
        h.check("position.invalid-numbers").configure(off);
        PacketContext context = h.run(h.newPlayer(), PacketKind.MOVE_POSITION, position(Double.NaN, 0, 0));
        assertFalse(h.flagged("position.invalid-numbers"));
        assertFalse(context.isCancelled());
    }

    @Test
    void bedrockExemptionIsPerCheck() {
        TestHarness h = new TestHarness(765);
        CheckSettings exempt = h.check("window.mismatch").defaults();
        exempt.exemptBedrock = true;
        h.check("window.mismatch").configure(exempt);
        PlayerData d = h.newPlayer();
        d.bedrock(true);
        d.openWindowId = 2;
        ServerProtocol p = h.protocol;
        for (int i = 0; i < 50; i++) {
            h.run(d, PacketKind.CLICK_WINDOW, click(p, 5, 1, 0, 0, clickBody(p, new byte[]{0})));
        }
        assertFalse(h.flagged("window.mismatch"));
    }

    @Test
    void everyCheckHasAUniqueIdAndDescription() {
        TestHarness h = new TestHarness(765);
        long unique = h.checks.all().stream().map(Check::id).distinct().count();
        assertEquals(h.checks.all().size(), unique);
        assertTrue(h.checks.all().stream().noneMatch(c -> c.description() == null || c.description().isEmpty()));
    }
}
