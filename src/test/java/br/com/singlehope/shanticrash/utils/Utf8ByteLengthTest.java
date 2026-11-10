package br.com.singlehope.shanticrash.utils;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class Utf8ByteLengthTest {
    @Test
    void correctlyCountsMultibyteTextAndCaps() {
        assertEquals(5, Utf8ByteLength.upTo("Hello", 20));
        assertEquals(2, Utf8ByteLength.upTo("é", 20));
        assertEquals(4, Utf8ByteLength.upTo("💜", 20));
        assertEquals(4, Utf8ByteLength.upTo("💜", 3));
        assertEquals(3, Utf8ByteLength.upTo("💜", 2));
        assertEquals(1, Utf8ByteLength.upTo("\uD83D", 5)); // JDK replaces malformed surrogates with '?'
        assertEquals(0, Utf8ByteLength.upTo(null, 5));
    }

    @Test
    void matchesJavaEncoderForRandomUnicode() {
        Random random = new Random(8309L);
        for (int round = 0; round < 1000; round++) {
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < 20; j++) text.append((char) random.nextInt(65536));
            int expected = text.toString().getBytes(StandardCharsets.UTF_8).length;
            assertEquals(expected, Utf8ByteLength.upTo(text.toString(), Integer.MAX_VALUE));
            assertEquals(Math.min(13, expected), Utf8ByteLength.upTo(text.toString(), 12));
        }
    }
}
