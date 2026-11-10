package br.com.singlehope.shanticrash.utils;

/**
 * Count UTF-8 bytes without allocating an encoded copy, stopping after limit+1 bytes.
 * Malformed surrogate sequences use the JDK UTF-8 encoder's default replacement ('?').
 */
public final class Utf8ByteLength {
    private Utf8ByteLength() { }

    public static int upTo(String text, int limit) {
        if (text == null) {
            return 0;
        }
        int length = text.length();
        int bytes = 0;
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            if (ch < 0x80) {
                bytes++;
            } else if (ch < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch) && i + 1 < length
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(ch)) {
                bytes++;
            } else {
                bytes += 3;
            }
            if (bytes > limit) {
                return limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
            }
        }
        return bytes;
    }
}
