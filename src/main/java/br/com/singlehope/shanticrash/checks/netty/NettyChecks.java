package br.com.singlehope.shanticrash.checks.netty;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.flood.FloodLimit;
import br.com.singlehope.shanticrash.flood.FloodState;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

/** Limits that apply to frames as a whole, before any of their content is looked at. */
public final class NettyChecks {
    private NettyChecks() {
    }

    /**
     * Frame size per connection state.
     *
     * <p>Automatic limits follow the largest payload a client can produce: before 1.13 custom payloads
     * and JSON are capped at 32767 bytes and items are small, while later versions allow large creative
     * items. The status and login numbers are the sizes vanilla itself enforces for those phases.
     */
    public static final class FrameSize extends Check {
        private boolean automatic = true;
        private int maxPlay = 262144;
        private int maxLegacyPlay = 65536;
        private int maxModernPlay = 262144;
        private int maxHandshake = 65536;
        private int maxLogin = 1048576;
        private int maxConfiguration = 1048576;

        public FrameSize() {
            super("netty", "frame-size", "Pacote maior que o tamanho seguro para o estado da conexão.");
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            automatic = options.getBoolean("automatic", true);
            maxPlay = options.getInt("max-play-bytes", 262144);
            maxLegacyPlay = options.getInt("automatic-legacy-bytes", 65536);
            maxModernPlay = options.getInt("automatic-modern-bytes", 262144);
            maxHandshake = options.getInt("max-handshake-bytes", 65536);
            maxLogin = options.getInt("max-login-bytes", 1048576);
            maxConfiguration = options.getInt("max-configuration-bytes", 1048576);
        }

        @Override
        protected void process(PacketContext context) {
            int limit;
            switch (context.kind) {
                case HANDSHAKE:
                case STATUS:
                    limit = maxHandshake;
                    break;
                case LOGIN_START:
                case LOGIN_PLUGIN_RESPONSE:
                case LOGIN_OTHER:
                    limit = maxLogin;
                    break;
                case CONFIG_PLUGIN_MESSAGE:
                case CONFIG_OTHER:
                    limit = maxConfiguration;
                    break;
                default:
                    limit = automatic ? (context.protocol.v1_13 ? maxModernPlay : maxLegacyPlay) : maxPlay;
                    break;
            }
            if (context.frameSize > limit) {
                reject(context, context.kind + " com " + context.frameSize + " bytes (limite " + limit + ")");
            }
        }
    }

    /** Packets that always have a small fixed layout have no reason to be large. */
    public static final class SmallPacket extends Check {
        private int maxBytes = 2048;

        public SmallPacket() {
            super("netty", "small-packet", "Pacote de layout pequeno com tamanho anormal.");
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 2048);
        }

        @Override
        protected void process(PacketContext context) {
            if (context.kind.isSmall() && context.frameSize > maxBytes) {
                reject(context, context.kind + " com " + context.frameSize + " bytes (limite " + maxBytes + ")");
            }
        }
    }

    /** Sustained volume of bytes from one connection. Protects against many mid sized frames. */
    public static final class ByteRate extends Check {
        private volatile FloodLimit limit = new FloodLimit(2_097_152D, 16_777_216D, 5_000L, 15_000L, 8, true);

        @Override
        public boolean isBehavioral() {
            return true;
        }

        public ByteRate() {
            super("netty", "byte-rate", "Volume de dados recebido acima do sustentável.");
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            limit = new FloodLimit(
                    options.getDouble("bytes-per-second", 2_097_152D),
                    options.getDouble("burst-bytes", 16_777_216D),
                    options.getLong("observation-ms", 5_000L),
                    options.getLong("recovery-ms", 15_000L),
                    options.getInt("max-overflows", 8),
                    options.getBoolean("drop", true));
        }

        @Override
        protected void process(PacketContext context) {
            FloodLimit active = limit;
            FloodState.Result result = context.data.byteFlood.account(active,
                    context.data.floodMultiplier() * scale(context), context.frameSize, context.nowNanos);
            if (result == FloodState.Result.ALLOWED) {
                return;
            }
            if (active.drop && enforcing()) {
                context.cancel();
            }
            if (result == FloodState.Result.VIOLATION) {
                reject(context, "acima de " + (long) active.rate + " bytes/s");
            }
        }
    }

    /**
     * Handshake, login and configuration packets. A client only sends a limited number of them, and
     * they run before the player exists, so plugins usually can not protect these phases.
     */
    public static final class PrePlayFlood extends Check {
        private double rate = 100.0D;
        private double burst = 1024.0D;

        public PrePlayFlood() {
            super("netty", "pre-play-flood", "Excesso de pacotes antes de entrar no jogo.",
                    PacketKind.HANDSHAKE, PacketKind.STATUS, PacketKind.LOGIN_START,
                    PacketKind.LOGIN_PLUGIN_RESPONSE, PacketKind.LOGIN_OTHER,
                    PacketKind.CONFIG_PLUGIN_MESSAGE, PacketKind.CONFIG_OTHER);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D).actions(PunishmentAction.DROP);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            rate = options.getDouble("packets-per-second", 100.0D);
            burst = options.getDouble("burst", 1024.0D);
        }

        @Override
        protected void process(PacketContext context) {
            if (!context.data.prePlayBucket.tryConsume(rate, burst, 1.0D, context.nowNanos)) {
                reject(context, "acima de " + (long) rate + " pacotes/s");
            }
        }
    }

    /**
     * A connection goes through handshake and login start exactly once. Other wrong state packets can
     * not reach this point, because the packet id is resolved using the state of the connection.
     */
    public static final class ConnectionState extends Check {
        public ConnectionState() {
            super("netty", "connection-state", "Pacote repetido que o estado da conexão não permite.",
                    PacketKind.HANDSHAKE, PacketKind.LOGIN_START);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D).actions(PunishmentAction.DROP);
        }

        @Override
        protected void process(PacketContext context) {
            int seen = context.kind == PacketKind.HANDSHAKE
                    ? ++context.data.handshakes
                    : ++context.data.loginStarts;
            if (seen > 1) {
                reject(context, context.kind + " repetido " + seen + " vezes");
            }
        }
    }

    /** Structure violations found by any check while reading a frame. */
    public static final class Malformed extends Check {
        public Malformed() {
            super("netty", "malformed", "Pacote com estrutura inválida.");
        }

        @Override
        public boolean isPassive() {
            return true;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void process(PacketContext context) {
        }
    }

    /**
     * The item decoder failed for a reason other than structure. It can mean a mod with custom registry
     * entries or a version mismatch, so the packet is discarded without any punishment by default.
     */
    public static final class DecodeFailure extends Check {
        public DecodeFailure() {
            super("netty", "decode-failure", "Item que o decodificador não conseguiu interpretar.");
        }

        @Override
        public boolean isPassive() {
            return true;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(20.0D).actions(PunishmentAction.ALERT)
                    .buffer(3.0D, 0.5D, 0.2D);
        }

        @Override
        protected void process(PacketContext context) {
        }
    }

    /**
     * Packets whose inspection took too long, observed only while packet monitoring is on. A garbage
     * collection pause can also cause this, so it only alerts.
     */
    public static final class ProcessingTime extends Check {
        public ProcessingTime() {
            super("netty", "processing-time", "Pacote cujo processamento demorou além do limite.");
        }

        @Override
        public boolean isPassive() {
            return true;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(20.0D).actions(PunishmentAction.ALERT)
                    .buffer(5.0D, 0.5D, 0.5D);
        }

        @Override
        protected void process(PacketContext context) {
        }

        public void report(PacketContext context, long elapsedNanos) {
            flag(context, context.kind + " levou " + elapsedNanos / 1000L + " µs");
        }
    }
}
