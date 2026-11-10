package br.com.singlehope.shanticrash.checks.place;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

/** Structure of placement and interaction packets. Their frequency is in the flood category. */
public final class PlaceChecks {
    private static final int LEGACY_NO_FACE = 255;
    private static final long WORLD_LIMIT = 30_000_512L;

    private PlaceChecks() {
    }

    private static boolean positionInvalid(long packed, ServerProtocol protocol) {
        long x = packed >> 38;
        long y;
        long z;
        if (protocol.v1_14) {
            z = (packed << 26) >> 38;
            y = (packed << 52) >> 52;
        } else {
            y = (packed >> 26) & 0xFFFL;
            z = (packed << 38) >> 38;
        }
        // 1.8 uses (-1, -1, -1) with face 255 when an item is used in the air.
        if (!protocol.v1_9 && x == -1 && y == 4095 && z == -1) {
            return false;
        }
        return Math.abs(x) > WORLD_LIMIT || Math.abs(z) > WORLD_LIMIT;
    }

    private static boolean floatInvalid(float value, double limit) {
        return Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > limit;
    }

    /** Fields of the placement packet, which changed layout several times. */
    private static final class Placement {
        int hand;
        long position;
        int face;
        float cursorX;
        float cursorY;
        float cursorZ;
        int sequence;

        /** Layouts of 1.9 and newer. The 1.8 one embeds an item and has its own check. */
        void read(PacketReader reader, ServerProtocol protocol) {
            sequence = 0;
            if (protocol.v1_14) {
                hand = reader.readVarInt();
                position = reader.readLong();
                face = reader.readVarInt();
                cursorX = reader.readFloat();
                cursorY = reader.readFloat();
                cursorZ = reader.readFloat();
                reader.readBoolean();
                if (protocol.v1_21_2) {
                    reader.readBoolean();
                }
                if (protocol.v1_19) {
                    sequence = reader.readVarInt();
                }
                reader.expectEnd();
                return;
            }
            position = reader.readLong();
            face = reader.readVarInt();
            hand = reader.readVarInt();
            if (protocol.v1_11) {
                cursorX = reader.readFloat();
                cursorY = reader.readFloat();
                cursorZ = reader.readFloat();
            } else {
                cursorX = reader.readUByte() / 16.0F;
                cursorY = reader.readUByte() / 16.0F;
                cursorZ = reader.readUByte() / 16.0F;
            }
            reader.expectEnd();
        }
    }

    /**
     * Validates face, hand, position and cursor of a placement.
     *
     * <p>The cursor limit is intentionally loose. Accurate placement mods used by printers encode
     * block state in the X value, far outside 0..1, and Paper already rejects it when not configured
     * otherwise. Only values no such mod produces are refused.
     */
    public static final class InvalidPlacement extends Check {
        private final ThreadLocal<Placement> placement = ThreadLocal.withInitial(Placement::new);
        private double maxCursor = 1024.0D;

        public InvalidPlacement() {
            super("place", "invalid-placement", "Colocação com face, mão, posição ou cursor inválidos.", PacketKind.BLOCK_PLACE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxCursor = options.getDouble("max-cursor", 1024.0D);
        }

        @Override
        protected void process(PacketContext context) {
            ServerProtocol protocol = context.protocol;
            if (!protocol.v1_9) {
                return;
            }
            Placement p = placement.get();
            p.read(context.read(), protocol);
            verify(context, p, protocol);
        }

        private void verify(PacketContext context, Placement p, ServerProtocol protocol) {
            if (p.face < 0 || p.face > 5) {
                reject(context, "face " + p.face);
            } else if (p.hand != 0 && p.hand != 1) {
                reject(context, "mão " + p.hand);
            } else if (positionInvalid(p.position, protocol)) {
                reject(context, "posição fora do mundo");
            } else if (floatInvalid(p.cursorX, maxCursor) || floatInvalid(p.cursorY, maxCursor)
                    || floatInvalid(p.cursorZ, maxCursor)) {
                reject(context, "cursor " + p.cursorX + ", " + p.cursorY + ", " + p.cursorZ);
            } else if (p.sequence < 0) {
                reject(context, "sequência " + p.sequence);
            }
        }
    }

    /** Validates everything in the 1.8 placement except for the embedded item. */
    public static final class LegacyPlacementHeader extends Check {
        public LegacyPlacementHeader() {
            super("place", "legacy-header", "Colocação 1.8 com face ou posição inválidas.", PacketKind.BLOCK_PLACE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void process(PacketContext context) {
            if (context.protocol.v1_9) {
                return;
            }
            PacketReader reader = context.read();
            long position = reader.readLong();
            int face = reader.readUByte();
            if ((face > 5 && face != LEGACY_NO_FACE)) {
                reject(context, "face " + face);
            } else if (positionInvalid(position, context.protocol)) {
                reject(context, "posição fora do mundo");
            }
        }
    }

    /** Using an item in the air. */
    public static final class InvalidUse extends Check {
        public InvalidUse() {
            super("place", "invalid-use", "Uso de item com mão ou rotação inválidas.", PacketKind.USE_ITEM);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int hand = reader.readVarInt();
            if (hand != 0 && hand != 1) {
                reject(context, "mão " + hand);
                return;
            }
            if (context.protocol.v1_19) {
                int sequence = reader.readVarInt();
                if (sequence < 0) {
                    reject(context, "sequência " + sequence);
                    return;
                }
                if (context.protocol.v1_21) {
                    float yaw = reader.readFloat();
                    float pitch = reader.readFloat();
                    if (floatInvalid(yaw, Float.MAX_VALUE) || floatInvalid(pitch, Float.MAX_VALUE)) {
                        reject(context, "rotação " + yaw + ", " + pitch);
                        return;
                    }
                }
            }
            reader.expectEnd();
        }
    }

    /** Block breaking actions. */
    public static final class InvalidDig extends Check {
        public InvalidDig() {
            super("place", "invalid-dig", "Pacote de quebra com ação, face ou posição inválidas.", PacketKind.DIGGING);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int action = reader.readVarInt();
            long position = reader.readLong();
            int face = reader.readUByte();
            if (context.protocol.v1_19 && reader.readVarInt() < 0) {
                reject(context, "sequência negativa");
                return;
            }
            reader.expectEnd();
            boolean legacy = !context.protocol.v1_9;
            if (action < 0 || action > 6) {
                reject(context, "ação " + action);
            } else if (face > 5 && !(legacy && face == LEGACY_NO_FACE)) {
                reject(context, "face " + face);
            } else if (positionInvalid(position, context.protocol)) {
                reject(context, "posição fora do mundo");
            }
        }
    }

    /** Interaction with entities. */
    public static final class InvalidInteract extends Check {
        private double maxTarget = 64.0D;

        public InvalidInteract() {
            super("place", "invalid-interact", "Interação com entidade com ação, mão ou alvo inválidos.", PacketKind.INTERACT_ENTITY);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxTarget = options.getDouble("max-target-offset", 64.0D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            reader.readVarInt();
            ServerProtocol protocol = context.protocol;
            if (protocol.v26_1) {
                int hand = reader.readVarInt();
                if (hand != 0 && hand != 1) {
                    reject(context, "mão " + hand);
                }
                return;
            }
            int action = reader.readVarInt();
            if (action < 0 || action > 2) {
                reject(context, "ação " + action);
                return;
            }
            if (action == 2) {
                float x = reader.readFloat();
                float y = reader.readFloat();
                float z = reader.readFloat();
                if (floatInvalid(x, maxTarget) || floatInvalid(y, maxTarget) || floatInvalid(z, maxTarget)) {
                    reject(context, "alvo " + x + ", " + y + ", " + z);
                    return;
                }
            }
            if (protocol.v1_9 && action != 1) {
                int hand = reader.readVarInt();
                if (hand != 0 && hand != 1) {
                    reject(context, "mão " + hand);
                }
            }
        }
    }

    /**
     * Detects sustained placement at a rate only a printer reaches, which raises the placement limit.
     * Off unless {@code automatic} is set, because an attacker can do the same: for servers that do
     * not allow printers, this must not turn into a free pass.
     */
    public static final class PrinterDetector extends Check {
        private volatile boolean automatic;
        private volatile int placeThreshold = 10;
        private volatile int sustainedWindows = 3;
        private volatile long idleNanos = 3_000_000_000L;
        private PrinterListener listener = PrinterListener.NONE;

        public PrinterDetector() {
            super("place", "printer", "Detecção de modo printer. Não pune, apenas ajusta limites.", PacketKind.BLOCK_PLACE);
        }

        /** Told when a player enters or leaves printer mode. */
        public interface PrinterListener {
            PrinterListener NONE = (data, active) -> { };

            void changed(br.com.singlehope.shanticrash.player.PlayerData data, boolean active);
        }

        public void listener(PrinterListener listener) {
            this.listener = listener;
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).actions(PunishmentAction.NONE);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            automatic = options.getBoolean("automatic", false);
            placeThreshold = options.getInt("place-threshold", 10);
            sustainedWindows = options.getInt("sustained-seconds", 3);
            idleNanos = options.getLong("idle-ms", 3_000L) * 1_000_000L;
        }

        @Override
        protected void process(PacketContext context) {
            if (!automatic) {
                return;
            }
            br.com.singlehope.shanticrash.player.PlayerData data = context.data;
            long now = context.nowNanos;
            if (data.isPrinterMode() && !data.isPrinterForced() && now - data.printerLastPlacement > idleNanos) {
                data.printerMode(false);
                data.printerSustainedWindows = 0;
                listener.changed(data, false);
            }
            data.printerLastPlacement = now;
            if (now - data.printerWindowStart >= 1_000_000_000L) {
                if (data.printerWindowPlacements >= placeThreshold) {
                    data.printerSustainedWindows++;
                } else {
                    data.printerSustainedWindows = 0;
                }
                data.printerWindowPlacements = 0;
                data.printerWindowStart = now;
                if (!data.isPrinterMode() && data.printerSustainedWindows >= sustainedWindows) {
                    data.printerMode(true);
                    listener.changed(data, true);
                }
            }
            data.printerWindowPlacements++;
        }
    }
}
