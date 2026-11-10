package br.com.singlehope.shanticrash.item;

import br.com.singlehope.shanticrash.packet.MalformedPacketException;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;

/**
 * Entry point of the item analysis. Scratch objects are per thread because a packet is analysed from
 * start to finish on one Netty thread, and there are few of those threads.
 */
public final class ItemAnalyzer {
    private static final class Workspace {
        final NbtScanner nbt = new NbtScanner();
        final ItemReport report = new ItemReport();
    }

    private final ThreadLocal<Workspace> workspace = ThreadLocal.withInitial(Workspace::new);
    private final ModernItemReader modernReader;
    private volatile ItemLimits limits = new ItemLimits();

    public ItemAnalyzer(ModernItemReader modernReader) {
        this.modernReader = modernReader;
    }

    public void limits(ItemLimits limits) {
        this.limits = limits;
    }

    public ItemLimits limits() {
        return limits;
    }

    private ItemReport begin(ItemLimits active) {
        ItemReport report = workspace.get().report.reset();
        if (active.maxProcessingNanos > 0) {
            report.deadlineNanos = System.nanoTime() + active.maxProcessingNanos;
        }
        return report;
    }

    /**
     * Measures the total time. An analysis that finished late still looked at everything, so the packet
     * is known to be fine and the delay was probably a pause of the JVM: it is only worth an alert.
     */
    private ItemReport finish(ItemReport report, long started, ItemLimits active) {
        report.elapsedNanos = System.nanoTime() - started;
        report.slow = active.maxProcessingNanos > 0 && report.elapsedNanos > active.maxProcessingNanos;
        return report;
    }

    private boolean readItem(PacketContext context, PacketReader reader, boolean lengthPrefixed,
                             ItemLimits active, ItemReport report) {
        ServerProtocol protocol = context.protocol;
        if (protocol.componentItems()) {
            if (modernReader == null) {
                return report.malformed("leitor de componentes indisponível");
            }
            return modernReader.read(context, reader, lengthPrefixed, active, report);
        }
        return LegacyItemParser.read(reader, protocol, workspace.get().nbt, active, report);
    }

    /**
     * Analyses the single item of the creative slot packet: {@code short slot, item}.
     */
    public ItemReport analyzeCreative(PacketContext context) {
        ItemLimits active = limits;
        ItemReport report = begin(active);
        long started = System.nanoTime();
        PacketReader reader = context.reader();
        try {
            reader.skip(2);
            if (readItem(context, reader, context.protocol.v1_21_5, active, report) && reader.hasRemaining()) {
                report.malformed(reader.remaining() + " bytes sobrando");
            }
        } catch (MalformedPacketException e) {
            report.malformed(e.getMessage());
        }
        return finish(report, started, active);
    }

    /**
     * Analyses the items of a click packet whose reader is already past the header. Hashed click
     * packets (1.21.5+) never reach this method because they hold no items.
     */
    public ItemReport analyzeClickItems(PacketContext context) {
        ItemLimits active = limits;
        ItemReport report = begin(active);
        long started = System.nanoTime();
        PacketReader reader = context.reader();
        try {
            readClickItems(context, reader, active, report);
        } catch (MalformedPacketException e) {
            report.malformed(e.getMessage());
        }
        return finish(report, started, active);
    }

    private void readClickItems(PacketContext context, PacketReader reader, ItemLimits active, ItemReport report) {
        if (context.protocol.v1_17) {
            int changed = reader.readVarInt();
            if (changed < 0) {
                report.malformed("lista de slots alterados negativa");
                return;
            }
            if (changed > active.maxChangedSlots) {
                report.fail(ItemReport.Violation.CHANGED_SLOTS, changed, active.maxChangedSlots);
                return;
            }
            for (int i = 0; i < changed; i++) {
                reader.skip(2);
                if (!readItem(context, reader, false, active, report)) {
                    return;
                }
            }
        }
        if (readItem(context, reader, false, active, report) && reader.hasRemaining()) {
            report.malformed(reader.remaining() + " bytes sobrando");
        }
    }

    /** Analyses one item stored at the reader position. The caller validates what comes after it. */
    public ItemReport analyzeEmbedded(PacketContext context) {
        ItemLimits active = limits;
        ItemReport report = begin(active);
        long started = System.nanoTime();
        try {
            readItem(context, context.reader(), false, active, report);
        } catch (MalformedPacketException e) {
            report.malformed(e.getMessage());
        }
        return finish(report, started, active);
    }
}
