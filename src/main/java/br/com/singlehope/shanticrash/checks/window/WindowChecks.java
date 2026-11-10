package br.com.singlehope.shanticrash.checks.window;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.packet.ClickHeader;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.player.PlayerData;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;

/** Inventory packet validation. Window size comes from what the server itself sent to the client. */
public final class WindowChecks {
    private static final int SLOT_OUTSIDE = -999;
    private static final int MODE_PICKUP = 0;
    private static final int MODE_QUICK_MOVE = 1;
    private static final int MODE_SWAP = 2;
    private static final int MODE_CLONE = 3;
    private static final int MODE_THROW = 4;
    private static final int MODE_QUICK_CRAFT = 5;
    private static final int MODE_PICKUP_ALL = 6;

    private WindowChecks() {
    }

    /** Called with the {@code WINDOW_ITEMS} packet sent by the server. */
    public static void trackWindowContents(PlayerData data, int windowId, int slots) {
        if (windowId == 0) {
            data.playerWindowSlots = slots;
        } else {
            data.openWindowId = windowId;
            data.openWindowSlots = slots;
        }
    }

    public static void trackWindowClosed(PlayerData data) {
        data.openWindowId = -1;
        data.openWindowSlots = -1;
        data.dragging = false;
    }

    /** Slots outside the window: older servers do not bounds check them and throw on the main thread. */
    public static final class InvalidSlot extends Check {
        private int maxSlot = 1024;

        public InvalidSlot() {
            super("window", "invalid-slot", "Clique em um slot que não existe na janela.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxSlot = options.getInt("max-slot", 1024);
        }

        @Override
        protected void process(PacketContext context) {
            ClickHeader click = context.click();
            int slot = click.slot;
            if (slot == SLOT_OUTSIDE || slot == -1) {
                return;
            }
            if (slot < 0 || slot > maxSlot) {
                reject(context, "slot " + slot);
                return;
            }
            PlayerData data = context.data;
            int known = click.windowId == data.openWindowId ? data.openWindowSlots
                    : click.windowId == 0 ? data.playerWindowSlots : -1;
            if (known > 0 && slot >= known) {
                reject(context, "slot " + slot + " em janela de " + known);
            }
        }
    }

    /** Window ids a server never hands out. */
    public static final class InvalidId extends Check {
        private int maxId = 255;

        public InvalidId() {
            super("window", "invalid-id", "Identificador de janela fora do intervalo.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxId = options.getInt("max-id", 255);
        }

        @Override
        protected void process(PacketContext context) {
            int id = context.click().windowId;
            if (id < 0 || id > maxId) {
                reject(context, "janela " + id);
            }
        }
    }

    /**
     * Clicks on a window other than the open one. A click that crossed a window change on the wire
     * causes this legitimately and the server ignores it anyway, hence the buffer and no cancellation.
     */
    public static final class Mismatch extends Check {
        public Mismatch() {
            super("window", "mismatch", "Cliques repetidos em uma janela que não está aberta.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(20.0D).actions(PunishmentAction.ALERT)
                    .buffer(10.0D, 0.5D, 1.0D);
        }

        @Override
        protected void process(PacketContext context) {
            int id = context.click().windowId;
            if (id != 0 && context.data.openWindowId != -1 && id != context.data.openWindowId) {
                flag(context, "janela " + id + ", aberta " + context.data.openWindowId);
            }
        }
    }

    /** Click mode and button combinations the protocol defines. */
    public static final class InvalidMode extends Check {
        public InvalidMode() {
            super("window", "invalid-mode", "Combinação de modo e botão que o protocolo não define.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void process(PacketContext context) {
            ClickHeader click = context.click();
            if (!isValid(click.mode, click.button)) {
                reject(context, "modo " + click.mode + ", botão " + click.button);
            }
        }

        static boolean isValid(int mode, int button) {
            switch (mode) {
                case MODE_PICKUP:
                case MODE_QUICK_MOVE:
                case MODE_THROW:
                    return button == 0 || button == 1;
                case MODE_SWAP:
                    return (button >= 0 && button <= 8) || button == 40;
                case MODE_CLONE:
                    return button == 2;
                case MODE_QUICK_CRAFT:
                    return button >= 0 && button <= 10 && (button & 3) != 3;
                case MODE_PICKUP_ALL:
                    return button == 0 || button == 1;
                default:
                    return false;
            }
        }
    }

    /** The drag (quick craft) sequence is the only multi packet click: start, add slots, end. */
    public static final class DragSequence extends Check {
        private int maxDragSlots = 512;

        public DragSequence() {
            super("window", "drag-sequence", "Sequência de arrasto de itens fora de ordem ou longa demais.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().cancel(false).punishAt(20.0D).actions(PunishmentAction.ALERT)
                    .buffer(5.0D, 0.5D, 0.5D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxDragSlots = options.getInt("max-drag-slots", 512);
        }

        @Override
        protected void process(PacketContext context) {
            ClickHeader click = context.click();
            PlayerData data = context.data;
            if (click.mode != MODE_QUICK_CRAFT) {
                data.dragging = false;
                return;
            }
            int stage = click.button & 3;
            if (stage == 0) {
                data.dragging = true;
                data.dragSlots = 0;
            } else if (!data.dragging) {
                flag(context, "etapa " + stage + " sem início");
            } else if (stage == 1) {
                if (++data.dragSlots > maxDragSlots) {
                    data.dragging = false;
                    flag(context, "arrasto com mais de " + maxDragSlots + " slots");
                }
            } else {
                data.dragging = false;
            }
        }
    }

    /** Since 1.21.5 clicks carry hashes instead of items. Those lists are the only variable part. */
    public static final class HashedStacks extends Check {
        private int maxChanged = 128;
        private int maxComponentsPerStack = 96;
        private int maxTotalComponents = 512;

        public HashedStacks() {
            super("window", "hashed-stacks", "Pacote de clique com listas de hashes grandes demais.", PacketKind.CLICK_WINDOW);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(3.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxChanged = options.getInt("max-changed-slots", 128);
            maxComponentsPerStack = options.getInt("max-components-per-stack", 96);
            maxTotalComponents = options.getInt("max-total-components", 512);
        }

        @Override
        protected void process(PacketContext context) {
            if (!context.protocol.hashedClicks()) {
                return;
            }
            int bodyOffset = context.click().bodyOffset;
            PacketReader reader = context.reader();
            reader.position(bodyOffset);
            int changed = reader.readVarInt();
            if (changed < 0 || changed > maxChanged) {
                reject(context, changed + " slots alterados (limite " + maxChanged + ")");
                return;
            }
            int total = 0;
            for (int i = 0; i <= changed; i++) {
                if (i < changed) {
                    reader.skip(2);
                }
                int components = readStack(reader);
                if (components < 0 || components > maxComponentsPerStack) {
                    reject(context, Math.abs(components) + " componentes em um item (limite " + maxComponentsPerStack + ")");
                    return;
                }
                total += components;
                if (total > maxTotalComponents) {
                    reject(context, total + " componentes no pacote (limite " + maxTotalComponents + ")");
                    return;
                }
            }
            reader.expectEnd();
        }

        /** Returns the number of hashed components, or a negative value when a count is invalid. */
        private int readStack(PacketReader reader) {
            if (!reader.readBoolean()) {
                return 0;
            }
            reader.readVarInt();
            reader.readVarInt();
            int added = reader.readVarInt();
            if (added < 0 || added > maxComponentsPerStack) {
                return added < 0 ? -1 : -added;
            }
            for (int i = 0; i < added; i++) {
                reader.readVarInt();
                reader.skip(4);
            }
            int removed = reader.readVarInt();
            if (removed < 0 || removed > maxComponentsPerStack) {
                return removed < 0 ? -1 : -removed;
            }
            for (int i = 0; i < removed; i++) {
                reader.readVarInt();
            }
            return added + removed;
        }
    }

    /** Fixed layout window packets: button clicks, close and the old transaction confirmation. */
    public static final class SimplePackets extends Check {
        private int maxId = 255;

        public SimplePackets() {
            super("window", "simple-packets", "Pacote simples de janela com conteúdo inválido.",
                    PacketKind.CLICK_WINDOW_BUTTON, PacketKind.CLOSE_WINDOW, PacketKind.WINDOW_CONFIRMATION);
        }

        @Override
        public CheckSettings defaults() {
            return new CheckSettings().punishAt(5.0D);
        }

        @Override
        protected void applyOptions(CheckOptions options) {
            maxId = options.getInt("max-id", 255);
        }

        @Override
        protected void process(PacketContext context) {
            PacketReader reader = context.read();
            switch (context.kind) {
                case CLICK_WINDOW_BUTTON: {
                    int window = reader.readVarInt();
                    reader.readByte();
                    reader.expectEnd();
                    checkId(context, window);
                    break;
                }
                case CLOSE_WINDOW: {
                    int window = context.protocol.v1_21_2 ? reader.readVarInt() : reader.readUByte();
                    reader.expectEnd();
                    checkId(context, window);
                    if (!context.isCancelled() && (window == context.data.openWindowId || window == 0)) {
                        trackWindowClosed(context.data);
                    }
                    break;
                }
                default: {
                    int window = reader.readUByte();
                    reader.readShort();
                    reader.readBoolean();
                    reader.expectEnd();
                    checkId(context, window);
                    break;
                }
            }
        }

        private void checkId(PacketContext context, int window) {
            if (window < 0 || window > maxId) {
                reject(context, "janela " + window);
            }
        }
    }
}
