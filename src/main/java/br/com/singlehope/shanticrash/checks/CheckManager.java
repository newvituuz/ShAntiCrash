package br.com.singlehope.shanticrash.checks;

import br.com.singlehope.shanticrash.checks.book.BookChecks;
import br.com.singlehope.shanticrash.checks.command.CommandChecks;
import br.com.singlehope.shanticrash.checks.creative.CreativeChecks;
import br.com.singlehope.shanticrash.checks.flood.FloodChecks;
import br.com.singlehope.shanticrash.checks.item.DecodingGuard;
import br.com.singlehope.shanticrash.checks.item.ItemChecks;
import br.com.singlehope.shanticrash.checks.netty.NettyChecks;
import br.com.singlehope.shanticrash.checks.payload.PayloadChecks;
import br.com.singlehope.shanticrash.checks.place.PlaceChecks;
import br.com.singlehope.shanticrash.checks.position.PositionChecks;
import br.com.singlehope.shanticrash.checks.recipe.RecipeChecks;
import br.com.singlehope.shanticrash.checks.risk.RiskChecks;
import br.com.singlehope.shanticrash.checks.tab.TabChecks;
import br.com.singlehope.shanticrash.checks.window.WindowChecks;
import br.com.singlehope.shanticrash.item.ItemAnalyzer;
import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.packet.PacketKind;
import br.com.singlehope.shanticrash.violation.ViolationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the checks and resolves the chain each packet goes through.
 *
 * <p>The registration order is the execution order, and it goes from the cheapest test to the most
 * expensive one, so a hostile packet is discarded having cost as little as possible: first the frame,
 * then the rate limiter, then fixed headers and only then the item analysis.
 */
public final class CheckManager {
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, Check> byId = new LinkedHashMap<>();
    private final Check[][] chains = new Check[PacketKind.values().length][];
    private final ItemAnalyzer analyzer;

    public final NettyChecks.Malformed malformed = new NettyChecks.Malformed();
    public final NettyChecks.DecodeFailure decodeFailure = new NettyChecks.DecodeFailure();
    public final NettyChecks.ProcessingTime processingTime = new NettyChecks.ProcessingTime();
    public final RiskChecks.Critical riskCritical = new RiskChecks.Critical();
    public final PlaceChecks.PrinterDetector printer = new PlaceChecks.PrinterDetector();
    public final ItemChecks.Limits limits = new ItemChecks.Limits(malformed, decodeFailure);
    public final DecodingGuard decodingGuard;

    public CheckManager(ItemAnalyzer analyzer, DecodingGuard.CreativeRewriter creativeRewriter,
                        ViolationManager violations) {
        this.analyzer = analyzer;
        this.decodingGuard = new DecodingGuard(creativeRewriter);

        // Whole frame.
        add(new NettyChecks.FrameSize());
        add(new NettyChecks.SmallPacket());
        add(new NettyChecks.PrePlayFlood());
        add(new NettyChecks.ConnectionState());
        add(new NettyChecks.ByteRate());
        add(malformed);
        add(decodeFailure);
        add(processingTime);
        add(riskCritical);

        // Rate limiting.
        add(new FloodChecks.Global());
        add(new FloodChecks.BigFrames());
        add(new FloodChecks.PacketLimits());

        // Fixed layouts.
        add(new PositionChecks.Format());
        add(new PositionChecks.InvalidNumbers());
        add(new PositionChecks.Bounds());
        add(new PositionChecks.Rotation());
        add(new WindowChecks.InvalidId());
        add(new WindowChecks.InvalidSlot());
        add(new WindowChecks.InvalidMode());
        add(new WindowChecks.Mismatch());
        add(new WindowChecks.DragSequence());
        add(new WindowChecks.HashedStacks());
        add(new WindowChecks.SimplePackets());
        add(new PlaceChecks.LegacyPlacementHeader());
        add(new PlaceChecks.InvalidPlacement());
        add(printer);
        add(new PlaceChecks.InvalidUse());
        add(new PlaceChecks.InvalidDig());
        add(new PlaceChecks.InvalidInteract());
        add(new RecipeChecks.InvalidRequest());
        add(new RecipeChecks.InvalidDisplayed());

        // Variable size, but read through length prefixes.
        add(new PayloadChecks.Size());
        add(new PayloadChecks.InvalidChannel());
        add(new PayloadChecks.Register());
        add(new PayloadChecks.Brand());
        add(new PayloadChecks.Blocked());
        add(new BookChecks.Content());
        add(new BookChecks.SignText());
        add(new BookChecks.ItemName());
        add(new TabChecks.Structure());
        add(new TabChecks.Length());
        add(new TabChecks.Pattern());
        add(new CommandChecks.Length());
        add(new CommandChecks.IllegalCharacters());
        add(new CommandChecks.Blocked());
        add(new CommandChecks.Complexity());

        // Items, the only expensive part. Creative permission comes first so survival players never get here.
        add(new CreativeChecks.NotCreative());
        add(new CreativeChecks.InvalidSlot());
        addLimit(ItemReport.Violation.NBT_DEPTH, "nbt-depth", "NBT aninhado além do limite.");
        addLimit(ItemReport.Violation.NBT_NODES, "nbt-nodes", "Quantidade de tags NBT além do limite.");
        addLimit(ItemReport.Violation.STRING_LENGTH, "string-length", "String em item maior que o limite.");
        addLimit(ItemReport.Violation.ARRAY_LENGTH, "array-length", "Array em item maior que o limite.");
        addLimit(ItemReport.Violation.LIST_LENGTH, "list-length", "Lista em item maior que o limite.");
        addLimit(ItemReport.Violation.ITEM_BYTES, "item-bytes", "Item serializado maior que o limite.");
        addLimit(ItemReport.Violation.ITEM_COUNT, "items-per-packet", "Mais itens em um pacote que o limite.");
        addLimit(ItemReport.Violation.ITEM_DEPTH, "item-depth", "Itens dentro de itens além do limite.");
        addLimit(ItemReport.Violation.COMPONENT_COUNT, "component-count", "Componentes em um item além do limite.");
        addLimit(ItemReport.Violation.TEXT_COMPLEXITY, "text-complexity", "Texto serializado complexo demais.");
        addLimit(ItemReport.Violation.ITEM_AMOUNT, "amount", "Quantidade de itens inválida.");
        addLimit(ItemReport.Violation.BOOK_CONTENT, "book-content", "Livro dentro de item com conteúdo excessivo.");
        addLimit(ItemReport.Violation.CHANGED_SLOTS, "changed-slots", "Clique alterando slots demais.");
        addLimit(ItemReport.Violation.PROCESSING_TIME, "processing-time", "Análise de item demorou além do limite.");
        add(new ItemChecks.Analysis(analyzer, limits));
        add(new BookChecks.LegacyItem(analyzer, limits));
        add(new CreativeChecks.ItemSize());
        add(new CreativeChecks.InvalidAmount());
        add(new CreativeChecks.RunCommand());
        add(decodingGuard);

        for (int i = 0; i < checks.size(); i++) {
            checks.get(i).initialize(i, violations);
        }
        violations.riskCheck(riskCritical);
        buildChains();
    }

    private void add(Check check) {
        checks.add(check);
        byId.put(check.id(), check);
    }

    private void addLimit(ItemReport.Violation violation, String type, String description) {
        add(limits.add(new ItemChecks.Limit(violation, type, description)));
    }

    private void buildChains() {
        for (PacketKind kind : PacketKind.values()) {
            List<Check> chain = new ArrayList<>();
            for (Check check : checks) {
                if (check.isPassive()) {
                    continue;
                }
                PacketKind[] kinds = check.kinds();
                if (kinds.length == 0) {
                    chain.add(check);
                    continue;
                }
                for (PacketKind accepted : kinds) {
                    if (accepted == kind) {
                        chain.add(check);
                        break;
                    }
                }
            }
            chains[kind.ordinal()] = chain.toArray(new Check[0]);
        }
    }

    /** Applies a new item configuration after the check settings have been loaded. */
    public void applyItemLimits(ItemLimits limitsFromConfig) {
        analyzer.limits(limits.apply(limitsFromConfig));
    }

    public Check[] chain(PacketKind kind) {
        return chains[kind.ordinal()];
    }

    public List<Check> all() {
        return Collections.unmodifiableList(checks);
    }

    public Check byId(String id) {
        return byId.get(id);
    }

    public ItemAnalyzer analyzer() {
        return analyzer;
    }
}
