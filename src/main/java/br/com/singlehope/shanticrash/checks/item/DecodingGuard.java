package br.com.singlehope.shanticrash.checks.item;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckOptions;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.punishment.PunishmentAction;
import io.netty.buffer.ByteBuf;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces what the server has to decode from items that passed the analysis.
 *
 * <p>In click packets the items are only the client's opinion of the result. The server computes the
 * real one, compares both and resynchronizes the window if they differ. So removing their data is
 * safe: the worst case is one extra window update, not a lost or duplicated item. Since 1.21.5 clicks
 * hold hashes and there is nothing to remove.
 *
 * <p>The creative slot is different: its item is the actual source of the data, so removing a component
 * really removes it from the item. That is why only {@link Mode#CREATIVE} and {@link Mode#ALL} do it.
 */
public final class DecodingGuard extends Check {
    /** Configurable behaviour. See the class documentation for what is safe in each packet. */
    public enum Mode {
        /** Items reach the server as received, after the analysis. */
        NONE,
        /** Only the heavy items of click packets lose their data. */
        SMART,
        /** Creative items keep only the allowed components. Click packets are not changed. */
        CREATIVE,
        /** All click items lose their data and creative items keep only the allowed components. */
        ALL
    }

    /** Rebuilds the creative item through PacketEvents, which knows how to encode each version. */
    public interface CreativeRewriter {
        /** @return whether the packet was changed */
        boolean rewrite(PacketContext context, Set<String> allowedComponents, Set<String> allowedNbt);
    }

    private final CreativeRewriter rewriter;
    private volatile Mode mode = Mode.SMART;
    private volatile int smartItemBytes = 2048;
    private volatile Set<String> allowedComponents = Collections.emptySet();
    private volatile Set<String> allowedNbt = Collections.emptySet();

    public DecodingGuard(CreativeRewriter rewriter) {
        super("item", "decoding-guard", "Remove dados de itens antes da decodificação pelo servidor.",
                PacketKind.CLICK_WINDOW, PacketKind.CREATIVE_SLOT);
        this.rewriter = rewriter;
    }

    @Override
    public CheckSettings defaults() {
        return new CheckSettings().cancel(false).actions(PunishmentAction.NONE);
    }

    @Override
    protected void applyOptions(CheckOptions options) {
        smartItemBytes = options.getInt("smart-item-bytes", 2048);
    }

    public void configure(Mode mode, Set<String> allowedComponents, Set<String> allowedNbt) {
        this.mode = mode;
        this.allowedComponents = normalize(allowedComponents);
        this.allowedNbt = new HashSet<>(allowedNbt);
    }

    private static Set<String> normalize(Set<String> names) {
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            normalized.add(lower.indexOf(':') < 0 ? "minecraft:" + lower : lower);
        }
        return normalized;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    protected void process(PacketContext context) {
        Mode active = mode;
        if (active == Mode.NONE) {
            return;
        }
        ItemReport report = context.itemReport();
        if (report == null || !report.ok()) {
            return;
        }
        if (context.kind == PacketKind.CLICK_WINDOW) {
            if (active == Mode.CREATIVE || context.protocol.hashedClicks()) {
                return;
            }
            compact(context.buffer, report, active == Mode.ALL ? 0 : smartItemBytes, context.protocol.componentItems());
            return;
        }
        if ((active == Mode.CREATIVE || active == Mode.ALL) && rewriter != null) {
            rewriter.rewrite(context, allowedComponents, allowedNbt);
        }
    }

    /**
     * Removes the data of the items at least {@code threshold} bytes long, in place. An item only gets
     * shorter, so writing behind the read position never overlaps data not yet copied.
     *
     * @return the number of items compacted
     */
    public static int compact(ByteBuf buffer, ItemReport report, int threshold, boolean components) {
        int items = report.topLevelItems();
        if (items == 0) {
            return 0;
        }
        int compacted = 0;
        for (int i = 0; i < items; i++) {
            if (report.itemHasData(i) && report.itemEnd(i) - report.itemStart(i) >= threshold) {
                compacted++;
            }
        }
        if (compacted == 0) {
            return 0;
        }
        int write = report.itemStart(0);
        int previousEnd = write;
        for (int i = 0; i < items; i++) {
            int start = report.itemStart(i);
            write = copy(buffer, previousEnd, start, write);
            int end = report.itemEnd(i);
            if (report.itemHasData(i) && end - start >= threshold) {
                write = copy(buffer, start, report.itemHeaderEnd(i), write);
                // No NBT is one TAG_End byte; no components is two zero counts.
                buffer.setByte(write++, 0);
                if (components) {
                    buffer.setByte(write++, 0);
                }
            } else {
                write = copy(buffer, start, end, write);
            }
            previousEnd = end;
        }
        write = copy(buffer, previousEnd, buffer.writerIndex(), write);
        buffer.writerIndex(write);
        return compacted;
    }

    private static int copy(ByteBuf buffer, int from, int to, int write) {
        if (from == write) {
            return write + (to - from);
        }
        for (int i = from; i < to; i++) {
            buffer.setByte(write++, buffer.getByte(i));
        }
        return write;
    }
}
