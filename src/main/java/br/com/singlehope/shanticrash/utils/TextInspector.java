package br.com.singlehope.shanticrash.utils;

import java.util.List;
import java.util.Locale;

/** Bounded checks over short strings typed by a client. No regular expressions, so no backtracking. */
public final class TextInspector {
    private TextInspector() {
    }

    /** Counts the bracket characters that make selectors and NBT paths expensive to parse. */
    public static int countBrackets(String text) {
        int count = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') {
                count++;
            }
        }
        return count;
    }

    /** Whether the text has characters a vanilla client refuses to send. */
    public static boolean hasIllegalCharacters(String text) {
        for (int i = 0, length = text.length(); i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xA7) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a command against blocked prefixes. Compared up to a word boundary so {@code /calc}
     * does not catch {@code /calculator}, while {@code /mv ^} still matches {@code /mv ^foo}.
     */
    public static String findBlockedCommand(String command, List<String> blocked) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String prefix : blocked) {
            if (lower.startsWith(prefix)) {
                boolean atEnd = lower.length() == prefix.length();
                char last = prefix.charAt(prefix.length() - 1);
                boolean prefixEndsWord = !Character.isLetterOrDigit(last);
                if (atEnd || prefixEndsWord || lower.charAt(prefix.length()) == ' ') {
                    return prefix;
                }
            }
        }
        return null;
    }

    public static String findContained(String text, List<String> needles) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return needle;
            }
        }
        return null;
    }
}
