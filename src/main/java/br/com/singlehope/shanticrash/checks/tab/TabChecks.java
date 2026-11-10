package br.com.singlehope.shanticrash.checks.tab;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import br.com.singlehope.shanticrash.utils.TextInspector;

import java.util.Arrays;
import java.util.List;

/** Tab completion. Its frequency is limited in the flood category. */
public final class TabChecks {
    /** Largest text vanilla accepts in the tab completion packet. */
    private static final int WIRE_MAX = 32767;

    private TabChecks() {
    }

    /** Skips what precedes the text and returns the size of the text prefix. */
    private static int textSize(PacketContext context, PacketReader reader) {
        if (context.protocol.v1_13) {
            reader.readVarInt();
        }
        int start = reader.position();
        int size = reader.skipString();
        reader.position(start);
        return size;
    }

    /**
     * A request in the chat box is at most 256 characters. Command block GUIs also use this packet
     * with longer text, so the default leaves room for them.
     */
    public static final class Length extends Check {
        private int maxBytes = 8192;

        public Length() {
            super("tab", "length", "Pedido de sugestão com texto maior que o limite.", PacketKind.TAB_COMPLETE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 8192);
        }

        @Override
        protected void process(PacketContext context) {
            int size = textSize(context, context.read());
            if (size > maxBytes) {
                reject(context, "texto com " + size + " bytes (limite " + maxBytes + ")");
            }
        }
    }

    /**
     * Shapes known to burn CPU when a server or plugin parses them for suggestions: expression
     * evaluators and long nested selectors. Only the suggestion request is cancelled, the player keeps
     * playing, so the default action is only to alert.
     */
    public static final class Pattern extends Check {
        private static final List<String> DEFAULT_PREFIXES = Arrays.asList(
                "/to ", "//to ", "/calc ", "//calc", "//eval", "//solve", "/solve");
        private static final List<String> DEFAULT_CONTAINS = Arrays.asList(
                "for(", "while(", "^(.", "targetoffset");

        private volatile List<String> prefixes = DEFAULT_PREFIXES;
        private volatile List<String> contains = DEFAULT_CONTAINS;
        private volatile int maxBrackets = 32;

        public Pattern() {
            super("tab", "pattern", "Pedido de sugestão com padrão de abuso conhecido.", PacketKind.TAB_COMPLETE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(10.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            prefixes = options.getStringList("prefixes", DEFAULT_PREFIXES);
            contains = options.getStringList("contains", DEFAULT_CONTAINS);
            maxBrackets = options.getInt("max-brackets", 32);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            if (context.protocol.v1_13) {
                reader.readVarInt();
            }
            String text = reader.readString(WIRE_MAX);
            int brackets = TextInspector.countBrackets(text);
            if (brackets > maxBrackets) {
                reject(context, brackets + " colchetes e chaves (limite " + maxBrackets + ")");
                return;
            }
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            for (String prefix : prefixes) {
                if (lower.startsWith(prefix)) {
                    reject(context, "prefixo " + prefix);
                    return;
                }
            }
            String found = TextInspector.findContained(text, contains);
            if (found != null) {
                reject(context, "contém " + found);
            }
        }
    }

    /** Transaction ids are echoed back by the server and a client only counts up from zero. */
    public static final class Structure extends Check {
        public Structure() {
            super("tab", "structure", "Pedido de sugestão com estrutura inválida.", PacketKind.TAB_COMPLETE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D).actions(PunishmentAction.KICK);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            if (context.protocol.v1_13) {
                if (reader.readVarInt() < 0) {
                    reject(context, "transação negativa");
                    return;
                }
                reader.skipString();
                reader.expectEnd();
                return;
            }
            reader.skipString();
            if (context.protocol.v1_9) {
                reader.readBoolean();
            }
            if (reader.readBoolean()) {
                reader.skip(8);
            }
            reader.expectEnd();
        }
    }
}
