package br.com.singlehope.shanticrash.checks.item;

import br.com.singlehope.shanticrash.checks.Check;
import br.com.singlehope.shanticrash.checks.CheckSettings;
import br.com.singlehope.shanticrash.checks.netty.NettyChecks;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.packet.PacketReader;

import java.util.EnumMap;
import java.util.Map;

/**
 * The item analysis and the checks that report its violations.
 *
 * <p>All limits are enforced by one pass over the packet, because that pass is what must be cheap and
 * must stop at the first problem. Each limit still is a separate check, with its own switch and VL:
 * {@link Limits#apply} turns the limit of a disabled check into an unlimited value.
 */
public final class ItemChecks {
    private ItemChecks() {
    }

    /** Reports one kind of violation found by the analysis. */
    public static final class Limit extends Check {
        private final ItemReport.Violation violation;

        public Limit(ItemReport.Violation violation, String type, String description) {
            super("item", type, description);
            this.violation = violation;
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

        /** Reports a packet that was fully validated but took too long to analyse. */
        void reportSlow(PacketContext context, ItemReport report) {
            boolean wasCancelled = context.isCancelled();
            reject(context, "análise levou " + report.elapsedNanos / 1000L + " µs");
            if (!wasCancelled) {
                context.uncancel();
            }
        }

        public ItemReport.Violation violation() {
            return violation;
        }
    }

    /** The limit checks, grouped because the analysis reports through them and reloads rebuild them. */
    public static final class Limits {
        private final Map<ItemReport.Violation, Limit> byViolation = new EnumMap<>(ItemReport.Violation.class);
        private final NettyChecks.Malformed malformed;
        private final NettyChecks.DecodeFailure decodeFailure;

        public Limits(NettyChecks.Malformed malformed, NettyChecks.DecodeFailure decodeFailure) {
            this.malformed = malformed;
            this.decodeFailure = decodeFailure;
        }

        public Limit add(Limit limit) {
            byViolation.put(limit.violation(), limit);
            return limit;
        }

        public Map<ItemReport.Violation, Limit> all() {
            return byViolation;
        }

        private boolean disabled(ItemReport.Violation violation) {
            Limit limit = byViolation.get(violation);
            return limit != null && !limit.settings().enabled;
        }

        private int effective(ItemReport.Violation violation, int value) {
            return disabled(violation) ? ItemLimits.UNLIMITED : value;
        }

        /** Replaces the limits of disabled checks in the values loaded from the configuration. */
        public ItemLimits apply(ItemLimits limits) {
            limits.maxNbtDepth = effective(ItemReport.Violation.NBT_DEPTH, limits.maxNbtDepth);
            limits.maxNbtNodes = effective(ItemReport.Violation.NBT_NODES, limits.maxNbtNodes);
            limits.maxStringBytes = effective(ItemReport.Violation.STRING_LENGTH, limits.maxStringBytes);
            limits.maxArrayLength = effective(ItemReport.Violation.ARRAY_LENGTH, limits.maxArrayLength);
            limits.maxListLength = effective(ItemReport.Violation.LIST_LENGTH, limits.maxListLength);
            limits.maxContainerItems = effective(ItemReport.Violation.LIST_LENGTH, limits.maxContainerItems);
            limits.maxChargedProjectiles = effective(ItemReport.Violation.LIST_LENGTH, limits.maxChargedProjectiles);
            limits.maxProfileProperties = effective(ItemReport.Violation.LIST_LENGTH, limits.maxProfileProperties);
            limits.maxToolRules = effective(ItemReport.Violation.LIST_LENGTH, limits.maxToolRules);
            limits.maxBundleItems = effective(ItemReport.Violation.LIST_LENGTH, limits.maxBundleItems);
            limits.maxLoreLines = effective(ItemReport.Violation.LIST_LENGTH, limits.maxLoreLines);
            limits.maxPredicates = effective(ItemReport.Violation.LIST_LENGTH, limits.maxPredicates);
            limits.maxTextNodesTotal = effective(ItemReport.Violation.TEXT_COMPLEXITY, limits.maxTextNodesTotal);
            limits.maxProfileBytes = effective(ItemReport.Violation.STRING_LENGTH, limits.maxProfileBytes);
            limits.maxItemBytes = effective(ItemReport.Violation.ITEM_BYTES, limits.maxItemBytes);
            limits.maxItemsPerPacket = effective(ItemReport.Violation.ITEM_COUNT, limits.maxItemsPerPacket);
            limits.maxItemDepth = effective(ItemReport.Violation.ITEM_DEPTH, limits.maxItemDepth);
            limits.maxComponents = effective(ItemReport.Violation.COMPONENT_COUNT, limits.maxComponents);
            limits.maxChangedSlots = effective(ItemReport.Violation.CHANGED_SLOTS, limits.maxChangedSlots);
            limits.maxAmount = effective(ItemReport.Violation.ITEM_AMOUNT, limits.maxAmount);
            limits.maxBookPages = effective(ItemReport.Violation.BOOK_CONTENT, limits.maxBookPages);
            limits.maxBookPageBytes = effective(ItemReport.Violation.BOOK_CONTENT, limits.maxBookPageBytes);
            limits.maxJsonDepth = effective(ItemReport.Violation.TEXT_COMPLEXITY, limits.maxJsonDepth);
            limits.maxTranslatePerString = effective(ItemReport.Violation.TEXT_COMPLEXITY, limits.maxTranslatePerString);
            limits.maxExtraPerString = effective(ItemReport.Violation.TEXT_COMPLEXITY, limits.maxExtraPerString);
            limits.maxTextNodes = effective(ItemReport.Violation.TEXT_COMPLEXITY, limits.maxTextNodes);
            if (disabled(ItemReport.Violation.PROCESSING_TIME)) {
                limits.maxProcessingNanos = 0L;
            }
            return limits;
        }

        /** Sends a report to the check responsible for its violation. */
        public void report(PacketContext context, ItemReport report) {
            switch (report.violation) {
                case NONE:
                    Limit slow = byViolation.get(ItemReport.Violation.PROCESSING_TIME);
                    if (report.slow && slow != null) {
                        slow.reportSlow(context, report);
                    }
                    return;
                case PROCESSING_TIME:
                    // The analysis stopped halfway, so nothing is known about the rest of this packet.
                    Limit timeout = byViolation.get(ItemReport.Violation.PROCESSING_TIME);
                    if (timeout != null) {
                        timeout.reject(context, report.describe());
                    }
                    context.cancel();
                    return;
                case MALFORMED:
                    malformed.reject(context, report.describe());
                    return;
                case UNDECODABLE:
                    decodeFailure.reject(context, report.describe());
                    return;
                default:
                    Limit check = byViolation.get(report.violation);
                    if (check != null) {
                        check.reject(context, report.describe());
                    } else {
                        malformed.reject(context, report.describe());
                    }
            }
        }
    }

    /**
     * Runs the item analysis for the packets that carry whole items. Hashed clicks hold none, and the
     * creative check already refused whoever is not allowed to send creative packets.
     */
    public static final class Analysis extends Check {
        private final ItemAnalyzer analyzer;
        private final Limits limits;

        public Analysis(ItemAnalyzer analyzer, Limits limits) {
            super("item", "analysis", "Análise estrutural de itens recebidos dos clientes.",
                    PacketKind.CLICK_WINDOW, PacketKind.CREATIVE_SLOT, PacketKind.BLOCK_PLACE);
            this.analyzer = analyzer;
            this.limits = limits;
        }

        @Override
        protected void process(PacketContext context) {
            ItemReport report;
            switch (context.kind) {
                case CREATIVE_SLOT:
                    context.read();
                    report = analyzer.analyzeCreative(context);
                    break;
                case CLICK_WINDOW:
                    if (context.protocol.hashedClicks()) {
                        return;
                    }
                    int bodyOffset = context.click().bodyOffset;
                    context.reader().position(bodyOffset);
                    report = analyzer.analyzeClickItems(context);
                    break;
                default:
                    if (context.protocol.v1_9) {
                        return;
                    }
                    report = analyzeLegacyPlacement(context);
                    break;
            }
            context.itemReport(report);
            limits.report(context, report);
        }

        /** The 1.8 placement: {@code position, face, item, cursor x, y, z}. */
        private ItemReport analyzeLegacyPlacement(PacketContext context) {
            PacketReader reader = context.read();
            reader.skip(9);
            ItemReport report = analyzer.analyzeEmbedded(context);
            if (report.ok()) {
                reader.skip(3);
                reader.expectEnd();
            }
            return report;
        }
    }
}
