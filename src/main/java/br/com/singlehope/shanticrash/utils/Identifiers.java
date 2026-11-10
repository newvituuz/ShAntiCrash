package br.com.singlehope.shanticrash.utils;

/** Validation of namespaced identifiers and legacy channel names. */
public final class Identifiers {
    private Identifiers() {
    }

    /** {@code namespace:path} with the same character set vanilla accepts. */
    public static boolean isValidResourceLocation(String value, int maxLength) {
        int length = value.length();
        if (length == 0 || length > maxLength) {
            return false;
        }
        int separator = value.indexOf(':');
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (i == separator) {
                continue;
            }
            boolean common = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            boolean pathOnly = c == '/' && i > separator;
            if (!common && !pathOnly) {
                return false;
            }
        }
        return separator < length - 1 || separator == -1;
    }

    /** Channels before 1.13 had no format: anything printable, such as {@code MC|Brand}. */
    public static boolean isValidLegacyChannel(String value) {
        int length = value.length();
        if (length == 0) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Matches a channel against a config pattern. Only a trailing {@code *} is supported, on purpose:
     * patterns run on the Netty thread and must not be able to backtrack.
     */
    public static boolean matchesPattern(String value, String pattern) {
        if (pattern.endsWith("*")) {
            return value.regionMatches(0, pattern, 0, pattern.length() - 1);
        }
        return value.equals(pattern);
    }
}
