package br.com.singlehope.shanticrash.packet;

import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.player.PlayerDataManager;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several connections attacked at once, each on its own thread, as Netty would run them. */
class ConcurrencyTest {
    private static List<byte[]> attacks() {
        List<byte[]> bodies = new ArrayList<>();
        bodies.add(Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtDeep(200, true)).toBytes());
        bodies.add(Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtManyNodes(40, 1000, true)).toBytes());
        bodies.add(Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtNestedContainers(6, true)).toBytes());
        bodies.add(Wire.create().s(36).s(1).b(1).s(0).raw(new byte[]{10, 0, 5, 1}).toBytes());
        bodies.add(Wire.create().s(36).s(1).b(1).s(0).b(0).toBytes());
        return bodies;
    }

    @Test
    void simultaneousAttacksKeepStateSeparate() throws InterruptedException {
        TestHarness harness = new TestHarness(340);
        int threads = 16;
        int rounds = 300;
        List<byte[]> attacks = attacks();
        List<PlayerData> players = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            players.add(harness.creativePlayer());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            PlayerData data = players.get(i);
            pool.execute(() -> {
                try {
                    long now = 1_000L;
                    for (int round = 0; round < rounds; round++) {
                        for (byte[] attack : attacks) {
                            harness.run(data, PacketKind.CREATIVE_SLOT, Unpooled.wrappedBuffer(attack), now);
                            now += 100_000_000L;
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "travou");
        pool.shutdownNow();
        assertTrue(harness.errors.isEmpty(), "erros: " + harness.errors);

        String[] expected = {"item.nbt-depth", "item.nbt-nodes", "item.item-depth", "netty.malformed"};
        for (PlayerData data : players) {
            for (String id : expected) {
                int index = harness.check(id).index();
                assertTrue(data.peekState(index) != null, id);
                assertEquals(rounds, data.peekState(index).flags(), "contagem independente em " + id);
            }
        }
    }

    @Test
    void connectionsComeAndGoWithoutLeaving() throws InterruptedException {
        PlayerDataManager manager = new PlayerDataManager(4, 8, 100_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    for (int i = 0; i < 20_000; i++) {
                        Object connection = new Object();
                        TestHarness.FakeHandle handle = new TestHarness.FakeHandle();
                        PlayerData data = manager.create(connection, handle);
                        manager.bindUuid(handle.uuid(), data);
                        manager.remove(connection);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(0, manager.size());
        assertTrue(manager.namesStartingWith("").isEmpty(), "mapa por UUID deveria estar vazio");
    }

    /** A kick closes the channel synchronously, which removes the state while the chain is running. */
    @Test
    void removalDuringAChainDoesNotBreakIt() {
        TestHarness harness = new TestHarness(340);
        PlayerDataManager manager = new PlayerDataManager(harness.checks.all().size(), 8, 10);
        Object connection = new Object();
        PlayerData data = manager.create(connection, new TestHarness.FakeHandle());
        PacketContext context = data.context().prepare(PacketKind.MOVE_POSITION, Unpooled.wrappedBuffer(new byte[25]), 0, 25, 26,
                1_000L, harness.protocol, data, null);
        manager.remove(connection);
        assertEquals(0L, context.read().readDouble());
    }

    @Test
    void chainStopsWhenTheConnectionIsClosing() {
        TestHarness harness = new TestHarness(340);
        PlayerData data = harness.newPlayer();
        data.closing(true);
        PacketContext context = harness.run(data, PacketKind.MOVE_POSITION, Unpooled.wrappedBuffer(new byte[25]), 1_000L);
        assertTrue(context.isCancelled());
        assertTrue(harness.errors.isEmpty());
    }

    @Test
    void connectionLimitIsEnforcedAndSweepRemovesClosed() {
        PlayerDataManager manager = new PlayerDataManager(4, 8, 3);
        List<TestHarness.FakeHandle> handles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestHarness.FakeHandle handle = new TestHarness.FakeHandle();
            handles.add(handle);
            assertTrue(manager.create(new Object(), handle) != null);
        }
        assertEquals(null, manager.create(new Object(), new TestHarness.FakeHandle()));
        handles.get(0).close();
        handles.get(1).close();
        assertEquals(2, manager.sweep());
        assertEquals(1, manager.size());
        assertTrue(manager.create(new Object(), new TestHarness.FakeHandle()) != null);
    }
}
