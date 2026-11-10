package br.com.singlehope.shanticrash.checks.creative;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

/**
 * Creative inventory packets. The server takes whatever item this packet carries, which makes it the
 * main way to introduce malicious items into a world.
 */
public final class CreativeChecks {
    private CreativeChecks() {
    }

    /**
     * Vanilla ignores the packet from anyone not in creative mode, but only after decoding it, which is
     * the expensive step that exploits abuse. Refusing here means survival players never reach that step.
     */
    public static final class NotCreative extends Check {
        private long graceNanos = 3_000_000_000L;

        public NotCreative() {
            super("creative", "not-creative", "Pacote criativo enviado fora do modo criativo.", PacketKind.CREATIVE_SLOT);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D).buffer(3.0D, 0.5D, 0.2D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            graceNanos = options.getLong("grace-ms", 3_000L) * 1_000_000L;
        }

        @Override
        protected void process(PacketContext context) {
            PlayerData data = context.data;
            if (data.isCreative()) {
                return;
            }
            // Packets in flight when an administrator changes the game mode are not the player's fault.
            if (context.nowNanos - data.gameModeChangedNanos() < graceNanos && data.gameModeChangedNanos() != 0L) {
                context.cancel();
                return;
            }
            flag(context, "jogador fora do modo criativo");
        }
    }

    /** The slots of the player inventory view, or -1 which the client uses to drop an item. */
    public static final class InvalidSlot extends Check {
        private int maxSlot = 45;

        public InvalidSlot() {
            super("creative", "invalid-slot", "Slot criativo fora do inventário do jogador.", PacketKind.CREATIVE_SLOT);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxSlot = options.getInt("max-slot", 45);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            int slot = reader.readShort();
            if (slot < -1 || slot > maxSlot) {
                reject(context, "slot " + slot);
            }
        }
    }

    /** Item size inside the creative packet, stricter than for items echoed by clicks. */
    public static final class ItemSize extends Check {
        private int maxBytes = 65536;

        public ItemSize() {
            super("creative", "item-size", "Item criativo serializado maior que o limite.", PacketKind.CREATIVE_SLOT);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxBytes = options.getInt("max-bytes", 65536);
        }

        @Override
        protected void process(PacketContext context) {
            ItemReport report = context.itemReport();
            if (report != null && report.ok() && report.maxItemBytes > maxBytes) {
                reject(context, report.maxItemBytes + " bytes (limite " + maxBytes + ")");
            }
        }
    }

    /** Stack sizes the client can not produce. A stack over the limit breaks stacking math in plugins. */
    public static final class InvalidAmount extends Check {
        private int maxAmount = 127;

        public InvalidAmount() {
            super("creative", "invalid-amount", "Quantidade de itens impossível.", PacketKind.CREATIVE_SLOT);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxAmount = options.getInt("max-amount", 127);
        }

        @Override
        protected void process(PacketContext context) {
            ItemReport report = context.itemReport();
            if (report != null && report.ok() && report.maxAmount > maxAmount) {
                reject(context, "quantidade " + report.maxAmount + " (limite " + maxAmount + ")");
            }
        }
    }

    /**
     * Text with {@code run_command} placed by a creative player runs with the permissions of whoever
     * clicks it, and signs run it at a high permission level on several versions. The default action is
     * to discard the item without punishing, since copying a sign with middle click can trigger this.
     */
    public static final class RunCommand extends Check {
        public RunCommand() {
            super("creative", "run-command", "Item criativo com evento de clique que executa comandos.", PacketKind.CREATIVE_SLOT);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D).actions(PunishmentAction.ALERT);
        }

        @Override
        protected void process(PacketContext context) {
            ItemReport report = context.itemReport();
            if (report != null && report.ok() && report.runCommand) {
                reject(context, "texto com run_command");
            }
        }
    }
}
