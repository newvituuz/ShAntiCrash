package br.com.singlehope.shanticrash.checks.command;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.utils.TextInspector;

import java.util.Arrays;
import java.util.List;

/** Chat and commands, which arrive through different packets depending on the version. */
public final class CommandChecks {
    private static final int WIRE_MAX = 32767;

    private CommandChecks() {
    }

    /** Commands from the 1.19 packets lack the leading slash that chat commands have. */
    private static String readCommand(PacketContext context) {
        PacketReader reader = context.read();
        String text = reader.readString(WIRE_MAX);
        if (context.kind == PacketKind.CHAT_COMMAND) {
            return "/" + text;
        }
        return text.startsWith("/") ? text : null;
    }

    /** Length of messages and commands, measured before anything is decoded. */
    public static final class Length extends Check {
        private int maxChatBytes = 1024;
        private int maxCommandBytes = 8192;

        public Length() {
            super("command", "length", "Mensagem ou comando maior que o limite.",
                    PacketKind.CHAT_MESSAGE, PacketKind.CHAT_COMMAND);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxChatBytes = options.getInt("max-chat-bytes", 1024);
            maxCommandBytes = options.getInt("max-command-bytes", 8192);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int start = reader.position();
            int size = reader.skipString();
            boolean command = context.kind == PacketKind.CHAT_COMMAND;
            if (!command && size > 0) {
                reader.position(start);
                reader.readVarInt();
                command = reader.buffer().getByte(reader.position()) == '/';
            }
            int limit = command ? maxCommandBytes : maxChatBytes;
            if (size > limit) {
                reject(context, size + " bytes (limite " + limit + ")");
            }
        }
    }

    /** Characters a vanilla client refuses to send. Newlines and section signs are the usual probes. */
    public static final class IllegalCharacters extends Check {
        public IllegalCharacters() {
            super("command", "illegal-characters", "Mensagem com caracteres que o cliente original não envia.",
                    PacketKind.CHAT_MESSAGE, PacketKind.CHAT_COMMAND);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void process(PacketContext context) {
            String text = context.read().readString(WIRE_MAX);
            if (TextInspector.hasIllegalCharacters(text)) {
                reject(context, "caractere de controle em texto de " + text.length() + " caracteres");
            }
        }
    }

    /**
     * Commands of popular plugins known to crash or stall servers when called with certain arguments.
     * Only those argument shapes are listed, so regular use of the same plugins keeps working. The list
     * can be cleared if the versions in use have been fixed.
     */
    public static final class Blocked extends Check {
        private static final List<String> DEFAULT_COMMANDS = Arrays.asList(
                "//calc", "//calculate", "//eval", "//evaluate", "//solve",
                "/hd readtext", "/holo readtext", "/hologram readtext", "/holograms readtext",
                "/holographicdisplays readtext",
                "/mv ^", "/mv help ^", "/mvhelp ^", "/mv <", "/mv help <", "/mvhelp <",
                "/$");

        private volatile List<String> commands = DEFAULT_COMMANDS;

        public Blocked() {
            super("command", "blocked", "Comando com argumentos conhecidos por travar o servidor.",
                    PacketKind.CHAT_MESSAGE, PacketKind.CHAT_COMMAND);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            commands = options.getStringList("commands", DEFAULT_COMMANDS);
        }

        @Override
        protected void process(PacketContext context) {
            String command = readCommand(context);
            if (command == null) {
                return;
            }
            String match = TextInspector.findBlockedCommand(command, commands);
            if (match != null) {
                reject(context, "comando " + match);
            }
        }
    }

    /** Selectors and NBT paths: vanilla parses them before checking whether the sender may use them. */
    public static final class Complexity extends Check {
        private int maxBrackets = 32;
        private int maxArguments = 256;

        public Complexity() {
            super("command", "complexity", "Comando com aninhamento ou argumentos excessivos.",
                    PacketKind.CHAT_MESSAGE, PacketKind.CHAT_COMMAND);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBrackets = options.getInt("max-brackets", 32);
            maxArguments = options.getInt("max-arguments", 256);
        }

        @Override
        protected void process(PacketContext context) {
            String command = readCommand(context);
            if (command == null) {
                return;
            }
            int brackets = TextInspector.countBrackets(command);
            if (brackets > maxBrackets) {
                reject(context, brackets + " colchetes e chaves (limite " + maxBrackets + ")");
                return;
            }
            int arguments = 0;
            for (int i = 0, length = command.length(); i < length; i++) {
                if (command.charAt(i) == ' ') {
                    arguments++;
                }
            }
            if (arguments > maxArguments) {
                reject(context, arguments + " argumentos (limite " + maxArguments + ")");
            }
        }
    }
}
