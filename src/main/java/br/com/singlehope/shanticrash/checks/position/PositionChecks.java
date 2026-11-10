package br.com.singlehope.shanticrash.checks.position;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;

/**
 * Integrity of movement packets. Nothing here judges how far or how fast a player moves: that is the
 * work of an anti-cheat, not of this plugin.
 */
public final class PositionChecks {
    private static final int POSITION_BODY = 8 + 8 + 8 + 1;
    private static final int ROTATION_BODY = 4 + 4 + 1;
    private static final int FLYING_BODY = 1;
    private static final int VEHICLE_BODY = 8 + 8 + 8 + 4 + 4;

    private PositionChecks() {
    }

    /** Movement packets have one exact size per type on every supported version. */
    public static final class Format extends Check {
        public Format() {
            super("position", "format", "Pacote de movimento com tamanho incompatível com o protocolo.",
                    PacketKind.MOVE_POSITION, PacketKind.MOVE_POSITION_ROTATION,
                    PacketKind.MOVE_ROTATION, PacketKind.MOVE_FLYING, PacketKind.VEHICLE_MOVE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void process(PacketContext context) {
            int expected;
            switch (context.kind) {
                case MOVE_POSITION:
                    expected = POSITION_BODY;
                    break;
                case MOVE_POSITION_ROTATION:
                    expected = POSITION_BODY + ROTATION_BODY - 1;
                    break;
                case MOVE_ROTATION:
                    expected = ROTATION_BODY;
                    break;
                case MOVE_FLYING:
                    expected = FLYING_BODY;
                    break;
                default:
                    expected = VEHICLE_BODY + (context.protocol.v1_21_4 ? 1 : 0);
                    break;
            }
            if (context.bodySize() != expected) {
                reject(context, context.kind + " com " + context.bodySize() + " bytes, esperado " + expected);
            }
        }
    }

    /** NaN and Infinity break the math of the server and of every client watching the player. */
    public static final class InvalidNumbers extends Check {
        public InvalidNumbers() {
            super("position", "invalid-numbers", "NaN ou Infinity em posição ou rotação.",
                    PacketKind.MOVE_POSITION, PacketKind.MOVE_POSITION_ROTATION,
                    PacketKind.MOVE_ROTATION, PacketKind.VEHICLE_MOVE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            boolean hasPosition = context.kind != PacketKind.MOVE_ROTATION;
            boolean hasRotation = context.kind != PacketKind.MOVE_POSITION;
            if (hasPosition) {
                for (int i = 0; i < 3; i++) {
                    double value = reader.readDouble();
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        reject(context, "coordenada " + "xyz".charAt(i) + " = " + value);
                        return;
                    }
                }
            }
            if (hasRotation) {
                for (int i = 0; i < 2; i++) {
                    float value = reader.readFloat();
                    if (Float.isNaN(value) || Float.isInfinite(value)) {
                        reject(context, (i == 0 ? "yaw" : "pitch") + " = " + value);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Absolute limits. They are the world border plus a margin, and vanilla's clamp value for Y. Beyond
     * that a client has nothing to send but values meant to overflow chunk arithmetic.
     */
    public static final class Bounds extends Check {
        private double maxHorizontal = 30_000_512.0D;
        private double maxVertical = 20_000_000.0D;

        public Bounds() {
            super("position", "bounds", "Coordenadas fora dos limites do mundo.",
                    PacketKind.MOVE_POSITION, PacketKind.MOVE_POSITION_ROTATION, PacketKind.VEHICLE_MOVE);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(1.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxHorizontal = options.getDouble("max-horizontal", 30_000_512.0D);
            maxVertical = options.getDouble("max-vertical", 20_000_000.0D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            double x = reader.readDouble();
            double y = reader.readDouble();
            double z = reader.readDouble();
            if (Math.abs(x) > maxHorizontal || Math.abs(z) > maxHorizontal || Math.abs(y) > maxVertical) {
                reject(context, "x=" + x + " y=" + y + " z=" + z);
            }
        }
    }

    /**
     * Pitch is clamped to +-90 by every client. Yaw is not, it grows as the player spins, so only values
     * no one reaches by turning are rejected.
     */
    public static final class Rotation extends Check {
        private float maxPitch = 90.5F;
        private float maxYaw = 1.0E7F;

        public Rotation() {
            super("position", "rotation", "Rotação fora do intervalo possível.",
                    PacketKind.MOVE_POSITION_ROTATION, PacketKind.MOVE_ROTATION);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxPitch = (float) options.getDouble("max-pitch", 90.5D);
            maxYaw = (float) options.getDouble("max-yaw", 1.0E7D);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            if (context.kind == PacketKind.MOVE_POSITION_ROTATION) {
                reader.skip(24);
            }
            float yaw = reader.readFloat();
            float pitch = reader.readFloat();
            if (Math.abs(pitch) > maxPitch || Math.abs(yaw) > maxYaw) {
                reject(context, "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }
}
