package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.item.NbtScanner;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.support.TestHarness;
import br.com.singlehope.shanticrash.utils.Utf8ByteLength;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzTest {
    private static final int[] PROTOCOLS = {47, 340, 404, 754, 765, 766, 769, 774, 777};

    private static byte[] randomBody(Random random) {
        int length;
        switch (random.nextInt(4)) {
            case 0:
                length = random.nextInt(8);
                break;
            case 1:
                length = random.nextInt(64);
                break;
            case 2:
                length = random.nextInt(1024);
                break;
            default:
                length = random.nextInt(9000);
                break;
        }
        byte[] body = new byte[length];
        random.nextBytes(body);
        if (length > 0 && random.nextBoolean()) {
            body[0] = (byte) random.nextInt(4);
        }
        return body;
    }

    @Test
    void randomFramesNeverCauseInternalErrors() {
        Random random = new Random(20261110L);
        long packets = 0L;
        for (int protocol : PROTOCOLS) {
            TestHarness h = new TestHarness(protocol);
            for (PacketKind kind : PacketKind.values()) {
                for (int i = 0; i < 150; i++) {
                    PlayerData d = random.nextInt(5) == 0 ? h.creativePlayer() : h.newPlayer();
                    h.run(d, kind, randomBody(random), 1_000L + i * 1_000_000L);
                    packets++;
                }
            }
            assertTrue(h.errors.isEmpty(), "protocolo " + protocol + ": " + h.errors);
        }
        assertTrue(packets > 50_000L);
    }

    @Test
    void sameConnectionSurvivesAMixedRandomStream() {
        Random random = new Random(7L);
        for (int protocol : PROTOCOLS) {
            TestHarness h = new TestHarness(protocol);
            PlayerData d = h.creativePlayer();
            PacketKind[] kinds = PacketKind.values();
            for (int i = 0; i < 4000; i++) {
                h.run(d, kinds[random.nextInt(kinds.length)], randomBody(random), 1_000L + i * 50_000L);
            }
            assertTrue(h.errors.isEmpty(), "protocolo " + protocol + ": " + h.errors);
        }
    }

    @Test
    void nbtScannerTerminatesOnRandomInput() {
        Random random = new Random(99L);
        NbtScanner scanner = new NbtScanner();
        ItemLimits limits = new ItemLimits();
        ItemReport report = new ItemReport();
        for (int i = 0; i < 20000; i++) {
            byte[] data = randomBody(random);
            if (data.length > 0) {
                data[0] = (byte) (1 + random.nextInt(12));
            }
            report.reset();
            int end = scanner.scanRoot(Unpooled.wrappedBuffer(data), 0, data.length, random.nextBoolean(), 1, limits, report);
            assertTrue(end == -1 || (end >= 0 && end <= data.length), "fim " + end + " de " + data.length);
            assertTrue(end != -1 || !report.ok());
        }
    }

    @Test
    void utf8LengthMatchesTheJdkEncoder() {
        Random random = new Random(1234L);
        for (int i = 0; i < 20000; i++) {
            int length = random.nextInt(64);
            StringBuilder text = new StringBuilder(length);
            for (int c = 0; c < length; c++) {
                switch (random.nextInt(6)) {
                    case 0:
                        text.append((char) random.nextInt(0x80));
                        break;
                    case 1:
                        text.append((char) (0x80 + random.nextInt(0x780)));
                        break;
                    case 2:
                        text.append((char) (0x800 + random.nextInt(0xD000)));
                        break;
                    case 3:
                        text.appendCodePoint(0x10000 + random.nextInt(0xFFFFF));
                        break;
                    case 4:
                        text.append((char) (0xD800 + random.nextInt(0x800)));
                        break;
                    default:
                        text.append((char) (0xE000 + random.nextInt(0x2000)));
                        break;
                }
            }
            String value = text.toString();
            int expected = value.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(expected, Utf8ByteLength.upTo(value, Integer.MAX_VALUE - 1), value);
            int limit = random.nextInt(200);
            int bounded = Utf8ByteLength.upTo(value, limit);
            assertEquals(expected > limit ? limit + 1 : expected, bounded);
        }
    }
}
