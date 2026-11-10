package br.com.singlehope.shanticrash.integration;

import br.com.singlehope.shanticrash.support.TestPacketEvents;
import br.com.singlehope.shanticrash.support.Wire;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compares a server with and without the plugin under the same load, on the loopback interface.
 *
 * <p>Normal bots send what a client sends while walking: one position per tick plus occasional clicks.
 * Attackers send, as fast as the socket allows, frames that are expensive to decode, and reconnect when
 * dropped. A probe measures the round trip of a tab completion, which goes through the server's main
 * thread, so it shows what players would feel.
 *
 * <p>Arguments: {@code <server jar> <java home> <work dir> <plugin jar or none> <protocol name> <normal bots>
 * <attackers> <seconds>}.
 */
public final class LoadTest {
    private final ClientVersion client;
    private final TestServer server;
    private final int normalBots;
    private final int attackers;
    private final int seconds;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong attackConnections = new AtomicLong();
    private final List<ProtocolBot> open = Collections.synchronizedList(new ArrayList<>());

    private LoadTest(String[] args) {
        List<Path> plugins = new ArrayList<>();
        if (!args[3].equals("none")) {
            plugins.add(Paths.get(args[3]));
        }
        this.server = new TestServer(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), plugins);
        this.client = ServerVersion.valueOf(args[4]).toClientVersion();
        this.normalBots = Integer.parseInt(args[5]);
        this.attackers = Integer.parseInt(args[6]);
        this.seconds = Integer.parseInt(args[7]);
    }

    public static void main(String[] args) throws Exception {
        TestPacketEvents.install(ServerVersion.valueOf(args[4]));
        new LoadTest(args).run(args[3].equals("none") ? "sem plugin" : "com plugin");
        System.exit(0);
    }

    private byte[] position(double x) {
        return Wire.create().d(x).d(64).d(0.5).b(1).toBytes();
    }

    private boolean atLeast(int protocol) {
        return client.getProtocolVersion() >= protocol;
    }

    private byte[] emptyClick() {
        Wire w = Wire.create();
        if (atLeast(768)) {
            w.v(0);
        } else {
            w.b(0);
        }
        if (atLeast(756)) {
            w.v(1);
        }
        w.s(9).b(0);
        if (!atLeast(755)) {
            w.s(1);
        }
        w.v(0);
        if (atLeast(770)) {
            return w.v(0).bool(false).toBytes();
        }
        if (atLeast(755)) {
            w.v(0);
        }
        return w.raw(atLeast(404) ? new byte[]{0} : Wire.create().s(-1).toBytes()).toBytes();
    }

    /** A click carrying a heavy but valid item, which vanilla has to decode entirely on a Netty thread. */
    private byte[] heavyClick() {
        if (atLeast(770)) {
            return emptyClick();
        }
        Wire w = Wire.create();
        if (atLeast(768)) {
            w.v(0);
        } else {
            w.b(0);
        }
        if (atLeast(756)) {
            w.v(1);
        }
        w.s(9).b(0);
        if (!atLeast(755)) {
            w.s(1);
        }
        w.v(0);
        if (atLeast(755)) {
            w.v(0);
        }
        boolean named = !atLeast(764);
        byte[] nbt = Wire.nbtManyNodes(20, 1000, named);
        if (atLeast(766)) {
            return w.v(1).v(1).v(1).v(0).v(0).raw(nbt).toBytes();
        }
        if (atLeast(404)) {
            return w.bool(true).v(1).b(1).raw(nbt).toBytes();
        }
        return w.s(1).b(1).s(0).raw(nbt).toBytes();
    }

    private byte[] tab(String text) {
        Wire w = Wire.create();
        if (atLeast(393)) {
            return w.v(1).str(text).toBytes();
        }
        w.str(text);
        if (atLeast(107)) {
            w.bool(false);
        }
        return w.bool(false).toBytes();
    }

    private void normalBot(int index, CountDownLatch ready) {
        String name = "n" + index;
        try (ProtocolBot bot = new ProtocolBot(client, name)) {
            bot.join(TestServer.PORT);
            open.add(bot);
            ready.countDown();
            int move = bot.playId(PacketType.Play.Client.PLAYER_POSITION);
            int click = bot.playId(PacketType.Play.Client.CLICK_WINDOW);
            byte[] clickBody = emptyClick();
            int tick = 0;
            while (running.get() && !bot.isClosed()) {
                bot.send(move, position(0.5 + (tick % 20) * 0.01));
                if (tick % 40 == 0) {
                    bot.send(click, clickBody);
                }
                framesSent.incrementAndGet();
                tick++;
                Thread.sleep(50L);
            }
        } catch (Exception e) {
            ready.countDown();
            if (running.get()) {
                System.out.println("AVISO bot normal " + name + " caiu: " + e);
            }
        }
    }

    private void attacker(int index) {
        byte[] heavy = heavyClick();
        byte[] nan = Wire.create().d(Double.NaN).d(0).d(0).b(1).toBytes();
        int connection = 0;
        while (running.get()) {
            String name = "a" + index + "x" + (connection++ % 1000);
            try (ProtocolBot bot = new ProtocolBot(client, name)) {
                bot.join(TestServer.PORT);
                attackConnections.incrementAndGet();
                int click = bot.playId(PacketType.Play.Client.CLICK_WINDOW);
                int move = bot.playId(PacketType.Play.Client.PLAYER_POSITION);
                while (running.get() && !bot.isClosed()) {
                    bot.sendBurst(click, heavy, 50);
                    bot.sendBurst(move, nan, 5);
                    framesSent.addAndGet(55);
                }
            } catch (Exception e) {
                // Being dropped is the expected outcome.
            }
        }
    }

    private void run(String label) throws Exception {
        server.prepare();
        server.start();
        Thread.sleep(5_000L);

        CountDownLatch ready = new CountDownLatch(normalBots);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < normalBots; i++) {
            int index = i;
            Thread thread = new Thread(() -> normalBot(index, ready), "normal-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
            Thread.sleep(150L);
        }
        ready.await(120, TimeUnit.SECONDS);
        for (int i = 0; i < attackers; i++) {
            int index = i;
            Thread thread = new Thread(() -> attacker(index), "attacker-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        Thread.sleep(10_000L);

        List<Long> roundTrips = Collections.synchronizedList(new ArrayList<>());
        AtomicLong lost = new AtomicLong();
        long cpuBefore = server.cpuMillis();
        long started = System.currentTimeMillis();
        try (ProtocolBot probe = new ProtocolBot(client, "probe")) {
            probe.join(TestServer.PORT);
            AtomicLong sentAt = new AtomicLong();
            probe.onResponse(PacketType.Play.Server.TAB_COMPLETE, () -> {
                long at = sentAt.getAndSet(0L);
                if (at != 0L) {
                    roundTrips.add(System.nanoTime() - at);
                }
            });
            int tabId = probe.playId(PacketType.Play.Client.TAB_COMPLETE);
            byte[] body = tab("/ver");
            while (System.currentTimeMillis() - started < seconds * 1000L) {
                if (sentAt.get() != 0L) {
                    lost.incrementAndGet();
                }
                sentAt.set(System.nanoTime());
                probe.send(tabId, body);
                Thread.sleep(500L);
            }
        }
        long cpuAfter = server.cpuMillis();
        double elapsedSeconds = (System.currentTimeMillis() - started) / 1000.0;
        List<String> mspt = server.commandOutput("mspt", 1_500L);
        List<String> tps = server.commandOutput("tps", 1_000L);
        running.set(false);
        long heap = server.heapUsedMegabytes();

        List<Long> sorted = new ArrayList<>(roundTrips);
        Collections.sort(sorted);
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99)) / 1_000_000.0;
        double max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1_000_000.0;

        System.out.printf(Locale.ROOT, "RESULTADO %s | bots %d | atacantes %d | %d s%n", label, normalBots, attackers, seconds);
        System.out.printf(Locale.ROOT, "cpu-servidor-nucleos %.2f%n", (cpuAfter - cpuBefore) / 1000.0 / elapsedSeconds);
        System.out.printf(Locale.ROOT, "heap-apos-gc-mb %d%n", heap);
        System.out.printf(Locale.ROOT, "tab-rtt-ms media %.1f p99 %.1f max %.1f amostras %d sem-resposta %d%n",
                average, p99, max, sorted.size(), lost.get());
        System.out.printf(Locale.ROOT, "frames-enviados %d conexoes-de-ataque %d%n", framesSent.get(), attackConnections.get());
        print("mspt", mspt);
        print("tps", tps);
        server.stop();
    }

    private static void print(String label, List<String> lines) {
        for (String line : lines) {
            String clean = line.replaceAll("^\\[[^\\]]*\\]\\s*", "");
            if (!clean.isEmpty() && !clean.startsWith(">")) {
                System.out.println(label + " " + clean);
            }
        }
    }

}
