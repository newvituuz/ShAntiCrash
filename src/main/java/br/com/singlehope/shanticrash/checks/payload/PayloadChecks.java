package br.com.singlehope.shanticrash.checks.payload;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.MalformedPacketException;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.utils.Identifiers;
import io.netty.buffer.ByteBuf;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Custom payload packets. No channel is blocked unless the administrator asks for it, because almost
 * everything using this packet is a legitimate mod or proxy.
 */
public final class PayloadChecks {
    /** Longest channel name accepted by the 1.8 to 1.12 protocol. */
    private static final int LEGACY_CHANNEL_MAX = 20;
    private static final int MODERN_CHANNEL_MAX = 32767;

    private PayloadChecks() {
    }

    private static String readChannel(PacketContext context, PacketReader reader) {
        return reader.readString(context.protocol.v1_13 ? MODERN_CHANNEL_MAX : LEGACY_CHANNEL_MAX);
    }

    /** Body size per connection. Vanilla refuses custom payloads over 32767 bytes anyway. */
    public static final class Size extends Check {
        private int maxBytes = 32767;
        private volatile List<String> exemptChannels = Collections.emptyList();

        public Size() {
            super("payload", "size", "Mensagem de plugin maior que o permitido.",
                    PacketKind.PLUGIN_MESSAGE, PacketKind.CONFIG_PLUGIN_MESSAGE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 32767);
            exemptChannels = options.getStringList("exempt-channels", Collections.<String>emptyList());
        }

        @Override
        protected void process(PacketContext context) {
            if (context.bodySize() <= maxBytes) {
                return;
            }
            PacketReader reader = context.read();
            String channel = readChannel(context, reader);
            int size = reader.remaining();
            if (size <= maxBytes) {
                return;
            }
            for (String pattern : exemptChannels) {
                if (Identifiers.matchesPattern(channel, pattern)) {
                    return;
                }
            }
            reject(context, size + " bytes (limite " + maxBytes + ")");
        }
    }

    /** Channel names. Modern versions require a valid resource location or vanilla disconnects. */
    public static final class InvalidChannel extends Check {
        private int maxLength = 256;

        public InvalidChannel() {
            super("payload", "invalid-channel", "Canal de mensagem com nome inválido.",
                    PacketKind.PLUGIN_MESSAGE, PacketKind.CONFIG_PLUGIN_MESSAGE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxLength = options.getInt("max-length", 256);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int size = reader.readVarInt();
            if (size < 0 || size > maxLength * 3L) {
                reject(context, "nome com " + size + " bytes");
                return;
            }
            reader.position(context.bodyStart);
            String channel = readChannel(context, reader);
            boolean valid = context.protocol.v1_13
                    ? Identifiers.isValidResourceLocation(channel, maxLength)
                    : Identifiers.isValidLegacyChannel(channel);
            if (!valid) {
                reject(context, "nome com " + channel.length() + " caracteres");
            }
        }
    }

    /**
     * Channel registration. The server keeps one entry per registered channel for the whole session,
     * which makes it an attractive way to grow memory if nothing limits it.
     */
    public static final class Register extends Check {
        private static final String MODERN_REGISTER = "minecraft:register";
        private static final String LEGACY_REGISTER = "REGISTER";
        private int maxPerPacket = 1024;
        private int maxPerConnection = 4096;
        private int maxNameBytes = 256;

        public Register() {
            super("payload", "register", "Registro abusivo de canais de mensagem.",
                    PacketKind.PLUGIN_MESSAGE, PacketKind.CONFIG_PLUGIN_MESSAGE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxPerPacket = options.getInt("max-per-packet", 1024);
            maxPerConnection = options.getInt("max-per-connection", 4096);
            maxNameBytes = options.getInt("max-name-bytes", 256);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            String channel = readChannel(context, reader);
            if (!MODERN_REGISTER.equals(channel) && !LEGACY_REGISTER.equals(channel)) {
                return;
            }
            ByteBuf buffer = reader.buffer();
            int names = 0;
            int nameStart = reader.position();
            int end = reader.end();
            for (int i = nameStart; i < end; i++) {
                if (buffer.getByte(i) == 0) {
                    names++;
                    if (i - nameStart > maxNameBytes) {
                        reject(context, "nome de canal com " + (i - nameStart) + " bytes");
                        return;
                    }
                    nameStart = i + 1;
                }
            }
            if (nameStart < end) {
                names++;
                if (end - nameStart > maxNameBytes) {
                    reject(context, "nome de canal com " + (end - nameStart) + " bytes");
                    return;
                }
            }
            context.data.registeredChannels += names;
            if (names > maxPerPacket) {
                reject(context, names + " canais em um pacote (limite " + maxPerPacket + ")");
            } else if (context.data.registeredChannels > maxPerConnection) {
                reject(context, context.data.registeredChannels + " canais na conexão (limite " + maxPerConnection + ")");
            }
        }
    }

    /** The brand is a short string sent once per configuration phase. */
    public static final class Brand extends Check {
        private static final String MODERN_BRAND = "minecraft:brand";
        private static final String LEGACY_BRAND = "MC|Brand";
        private int maxBytes = 256;
        private int maxPackets = 8;

        public Brand() {
            super("payload", "brand", "Identificação de cliente abusiva.",
                    PacketKind.PLUGIN_MESSAGE, PacketKind.CONFIG_PLUGIN_MESSAGE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 256);
            maxPackets = options.getInt("max-packets", 8);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            String channel = readChannel(context, reader);
            if (!MODERN_BRAND.equals(channel) && !LEGACY_BRAND.equals(channel)) {
                return;
            }
            int size = reader.remaining();
            if (size > maxBytes) {
                reject(context, "marca com " + size + " bytes (limite " + maxBytes + ")");
            } else if (++context.data.brandPackets > maxPackets) {
                reject(context, context.data.brandPackets + " pacotes de marca (limite " + maxPackets + ")");
            }
        }
    }

    /** Channels the administrator chose to block. Empty by default. */
    public static final class Blocked extends Check {
        private volatile List<String> patterns = Collections.emptyList();

        public Blocked() {
            super("payload", "blocked-channel", "Mensagem em um canal bloqueado pelo administrador.",
                    PacketKind.PLUGIN_MESSAGE, PacketKind.CONFIG_PLUGIN_MESSAGE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            patterns = options.getStringList("channels", Collections.<String>emptyList());
        }

        @Override
        protected void process(PacketContext context) {
            List<String> active = patterns;
            if (active.isEmpty()) {
                return;
            }
            String channel = readChannel(context, context.read());
            for (String pattern : active) {
                if (Identifiers.matchesPattern(channel, pattern)) {
                    reject(context, "canal " + channel);
                    return;
                }
            }
        }
    }

    /** Legacy channels that carry items are handled by the book checks. */
    public static boolean isLegacyBookChannel(String channel) {
        return LEGACY_BOOK_CHANNELS.contains(channel);
    }

    private static final List<String> LEGACY_BOOK_CHANNELS = Arrays.asList("MC|BEdit", "MC|BSign");

    /** Reads the channel, translating a truncated name into a structure error. */
    public static String channelOf(PacketContext context, PacketReader reader) throws MalformedPacketException {
        return readChannel(context, reader);
    }
}
