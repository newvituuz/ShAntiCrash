package br.com.singlehope.shanticrash.benchmark;

import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.support.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * Measures the real pipeline on one thread: nanoseconds per packet and bytes allocated per packet.
 *
 * <p>Run by hand, it is not part of the unit tests:
 * {@code java -cp target/test-classes;target/classes;<classpath> ...benchmark.PipelineBenchmark}
 *
 * <p>Each case runs through warm up rounds first. The player is replaced regularly so attacks keep being
 * inspected instead of being cut off by the rate limiter, which would measure the wrong thing.
 */
public final class PipelineBenchmark {
    private static final int WARMUP_ROUNDS = 5;
    private static final int ROUNDS = 5;
    private static final int PACKETS_PER_ROUND = 200_000;
    private static final long CLOCK_STEP_NANOS = 50_000_000L;

    private PipelineBenchmark() {
    }

    private static long allocatedBytes() {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static void measure(String name, int protocol, PacketKind kind, byte[] body, boolean creative, int packets) {
        TestHarness harness = new TestHarness(protocol);
        harness.violations.enabled(false);
        ByteBuf buffer = Unpooled.wrappedBuffer(body);
        buffer.markWriterIndex();
        PlayerData data = creative ? harness.creativePlayer() : harness.newPlayer();
        long now = 1_000L;
        long sink = 0;
        double[] nanos = new double[ROUNDS];
        double allocated = 0;
        for (int round = -WARMUP_ROUNDS; round < ROUNDS; round++) {
            long allocationBefore = allocatedBytes();
            long started = System.nanoTime();
            for (int i = 0; i < packets; i++) {
                now += CLOCK_STEP_NANOS;
                if (harness.run(data, kind, buffer, now).isCancelled()) {
                    sink++;
                }
            }
            long elapsed = System.nanoTime() - started;
            if (round >= 0) {
                nanos[round] = (double) elapsed / packets;
                allocated += (double) (allocatedBytes() - allocationBefore) / packets;
            }
        }
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "| %-34s | %6d | %9.0f | %8.0f | %s |%n",
                name, body.length, nanos[ROUNDS / 2], allocated / ROUNDS, sink > 0 ? "descartado" : "aceito");
    }

    private static byte[] typicalItemNbt() {
        Wire w = Wire.create().nbtRoot(true);
        w.nbtOpenCompound("display").nbtString("Name", "Espada Lendaria");
        w.nbtOpenList("Lore", 8, 3);
        for (int i = 0; i < 3; i++) {
            byte[] line = "Uma linha de descricao".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            w.s(line.length).raw(line);
        }
        w.nbtEnd();
        w.nbtOpenList("ench", 10, 3);
        for (int i = 0; i < 3; i++) {
            w.nbtByte("id", 16).nbtByte("lvl", 5).nbtEnd();
        }
        return w.nbtInt("HideFlags", 1).nbtEnd().toBytes();
    }

    private static long usedAfterCollection() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(150L);
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Heap held per connection, right after connecting and after using the state of several checks. Counts
     * only the plugin's own state: what PacketEvents and the server keep per connection is not included.
     */
    private static void memoryPerConnection(byte[] move, byte[] keepAlive, byte[] click, byte[] creative) throws InterruptedException {
        int connections = 5_000;
        TestHarness harness = new TestHarness(340);
        harness.violations.enabled(false);
        PlayerData[] held = new PlayerData[connections];
        long before = usedAfterCollection();
        for (int i = 0; i < connections; i++) {
            held[i] = harness.newPlayer();
        }
        long fresh = usedAfterCollection();
        long now = 1_000L;
        for (PlayerData data : held) {
            harness.run(data, PacketKind.MOVE_POSITION_ROTATION, move, now);
            harness.run(data, PacketKind.KEEP_ALIVE, keepAlive, now);
            harness.run(data, PacketKind.CLICK_WINDOW, click, now);
            harness.run(data, PacketKind.CREATIVE_SLOT, creative, now);
        }
        long used = usedAfterCollection();
        System.out.printf(Locale.ROOT, "%nMemória por conexão (%d conexões): %d bytes ao conectar, %d bytes após uso%n",
                held.length, (fresh - before) / connections, (used - before) / connections);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("| caso | bytes | ns/pacote | bytes alocados/pacote | resultado |");
        System.out.println("|---|---|---|---|---|");
        Wire position = Wire.create().d(10.5).d(70).d(-3.25).f(90F).f(10F).b(1);
        measure("movimento", 340, PacketKind.MOVE_POSITION_ROTATION, position.toBytes(), false, PACKETS_PER_ROUND);
        measure("keep-alive", 340, PacketKind.KEEP_ALIVE, Wire.create().l(1).toBytes(), false, PACKETS_PER_ROUND);

        byte[] click = Wire.create().b(0).s(3).b(0).s(1).v(0).s(1).b(1).s(0).raw(typicalItemNbt()).toBytes();
        measure("clique com item típico", 340, PacketKind.CLICK_WINDOW, click, false, PACKETS_PER_ROUND);
        byte[] creative = Wire.create().s(36).s(1).b(1).s(0).raw(typicalItemNbt()).toBytes();
        measure("criativo com item típico", 340, PacketKind.CREATIVE_SLOT, creative, true, PACKETS_PER_ROUND);

        byte[] book = Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtBook(50, 256, true)).toBytes();
        measure("criativo com livro de 50 páginas", 340, PacketKind.CREATIVE_SLOT, book, true, 20_000);

        byte[] deep = Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtDeep(500, true)).toBytes();
        measure("ataque: NBT profundo", 340, PacketKind.CREATIVE_SLOT, deep, true, PACKETS_PER_ROUND);
        byte[] nodes = Wire.create().s(36).s(1).b(1).s(0).raw(Wire.nbtManyTags(12_000, true)).toBytes();
        measure("ataque: maior item aceito (60 KB)", 340, PacketKind.CREATIVE_SLOT, nodes, true, 5_000);
        byte[] oversized = Wire.create().s(36).s(1).b(1).s(0).raw(new byte[300_000]).toBytes();
        measure("ataque: frame de 300 KB", 340, PacketKind.CREATIVE_SLOT, oversized, true, PACKETS_PER_ROUND);

        Wire nan = Wire.create().d(Double.NaN).d(0).d(0).b(1);
        measure("ataque: posição NaN", 340, PacketKind.MOVE_POSITION, nan.toBytes(), false, PACKETS_PER_ROUND);

        memoryPerConnection(position.toBytes(), Wire.create().l(1).toBytes(), click, creative);
    }
}
