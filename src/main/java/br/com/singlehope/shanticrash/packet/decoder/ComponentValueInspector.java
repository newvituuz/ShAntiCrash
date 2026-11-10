package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import br.com.singlehope.shanticrash.utils.Utf8ByteLength;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAdventurePredicate;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttributeModifiers;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemEnchantments;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemFireworks;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemPotionContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTool;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WritableBookContent;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.util.Filterable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Applies the limits to a component value that PacketEvents has already read. Dispatches by the class
 * of the value, so it needs no per version knowledge, and walks trees with explicit stacks. NBT is not
 * handled here: {@link BoundedPacketWrapper} validates it on the wire bytes before it is read.
 */
final class ComponentValueInspector {
    boolean inspect(Object value, ItemLimits limits, ItemReport report) {
        if (value instanceof Component) {
            return inspectText((Component) value, limits, report);
        }
        if (value instanceof ItemLore) {
            List<Component> lines = ((ItemLore) value).getLines();
            if (!checkList(lines.size(), limits, report)) {
                return false;
            }
            if (lines.size() > limits.maxLoreLines) {
                report.note = "lore.lines";
                return report.fail(ItemReport.Violation.LIST_LENGTH, lines.size(), limits.maxLoreLines);
            }
            for (Component line : lines) {
                if (!inspectText(line, limits, report)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof WritableBookContent) {
            return inspectWritable((WritableBookContent) value, limits, report);
        }
        if (value instanceof WrittenBookContent) {
            return inspectWritten((WrittenBookContent) value, limits, report);
        }
        if (value instanceof ItemProfile) {
            ItemProfile profile = (ItemProfile) value;
            int size = profile.getProperties().size();
            if (!checkList(size, limits, report)) {
                return false;
            }
            if (size > limits.maxProfileProperties) {
                report.note = "profile.properties";
                return report.fail(ItemReport.Violation.LIST_LENGTH, size, limits.maxProfileProperties);
            }
            int remaining = limits.maxProfileBytes;
            for (ItemProfile.Property property : profile.getProperties()) {
                String[] values = {property.getName(), property.getValue(), property.getSignature()};
                for (String text : values) {
                    int bytes = Utf8ByteLength.upTo(text, remaining);
                    if (bytes > remaining) {
                        report.note = "profile.bytes";
                        return report.fail(ItemReport.Violation.STRING_LENGTH,
                                (long) limits.maxProfileBytes + 1L, limits.maxProfileBytes);
                    }
                    remaining -= bytes;
                }
            }
            return true;
        }
        if (value instanceof ItemFireworks) {
            return checkList(((ItemFireworks) value).getExplosions().size(), limits, report);
        }
        if (value instanceof ItemAttributeModifiers) {
            return checkList(((ItemAttributeModifiers) value).getModifiers().size(), limits, report);
        }
        if (value instanceof ItemEnchantments) {
            return checkList(((ItemEnchantments) value).getEnchantmentCount(), limits, report);
        }
        if (value instanceof ItemPotionContents) {
            return checkList(((ItemPotionContents) value).getCustomEffects().size(), limits, report);
        }
        if (value instanceof ItemAdventurePredicate) {
            List<ItemAdventurePredicate.BlockPredicate> predicates = ((ItemAdventurePredicate) value).getPredicates();
            long total = predicates.size();
            for (ItemAdventurePredicate.BlockPredicate predicate : predicates) {
                List<ItemAdventurePredicate.PropertyMatcher> properties = predicate.getProperties();
                if (properties != null) {
                    total += properties.size();
                }
                if (total > limits.maxPredicates) {
                    break;
                }
            }
            if (total > report.maxListLength) {
                report.maxListLength = (int) Math.min(Integer.MAX_VALUE, total);
            }
            if (total > limits.maxPredicates) {
                report.note = "predicates";
                return report.fail(ItemReport.Violation.LIST_LENGTH, total, limits.maxPredicates);
            }
            return true;
        }
        if (value instanceof ItemTool) {
            int size = ((ItemTool) value).getRules().size();
            if (!checkList(size, limits, report)) {
                return false;
            }
            if (size > limits.maxToolRules) {
                report.note = "tool.rules";
                return report.fail(ItemReport.Violation.LIST_LENGTH, size, limits.maxToolRules);
            }
            return true;
        }
        return true;
    }

    private boolean checkList(int size, ItemLimits limits, ItemReport report) {
        if (size > report.maxListLength) {
            report.maxListLength = size;
        }
        if (size > limits.maxListLength) {
            return report.fail(ItemReport.Violation.LIST_LENGTH, size, limits.maxListLength);
        }
        return true;
    }

    private boolean inspectWritable(WritableBookContent book, ItemLimits limits, ItemReport report) {
        List<Filterable<String>> pages = book.getPages();
        recordPages(pages.size(), report);
        if (pages.size() > limits.maxBookPages) {
            return report.fail(ItemReport.Violation.BOOK_CONTENT, pages.size(), limits.maxBookPages);
        }
        for (Filterable<String> page : pages) {
            int size = Utf8ByteLength.upTo(page.getRaw(), limits.maxBookPageBytes);
            if (page.getFiltered() != null) {
                size = Math.max(size, Utf8ByteLength.upTo(page.getFiltered(), limits.maxBookPageBytes));
            }
            if (size > report.maxBookPageBytes) {
                report.maxBookPageBytes = size;
            }
            if (size > limits.maxBookPageBytes) {
                return report.fail(ItemReport.Violation.BOOK_CONTENT, size, limits.maxBookPageBytes);
            }
        }
        return true;
    }

    private boolean inspectWritten(WrittenBookContent book, ItemLimits limits, ItemReport report) {
        List<Filterable<Component>> pages = book.getPages();
        recordPages(pages.size(), report);
        if (pages.size() > limits.maxBookPages) {
            return report.fail(ItemReport.Violation.BOOK_CONTENT, pages.size(), limits.maxBookPages);
        }
        for (Filterable<Component> page : pages) {
            if (!inspectText(page.getRaw(), limits, report)) {
                return false;
            }
            if (page.getFiltered() != null && !inspectText(page.getFiltered(), limits, report)) {
                return false;
            }
        }
        return true;
    }

    private void recordPages(int pages, ItemReport report) {
        if (pages > report.bookPages) {
            report.bookPages = pages;
        }
    }

    private boolean inspectText(Component root, ItemLimits limits, ItemReport report) {
        ArrayDeque<Component> nodes = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        nodes.push(root);
        depths.push(1);
        int visited = 0;
        int translate = 0;
        while (!nodes.isEmpty()) {
            Component node = nodes.pop();
            int depth = depths.pop();
            if (++visited > limits.maxTextNodes) {
                report.note = "nós";
                return report.fail(ItemReport.Violation.TEXT_COMPLEXITY, visited, limits.maxTextNodes);
            }
            if (++report.textNodes > limits.maxTextNodesTotal) {
                report.note = "nós no pacote";
                return report.fail(ItemReport.Violation.TEXT_COMPLEXITY, report.textNodes, limits.maxTextNodesTotal);
            }
            if (depth > limits.maxJsonDepth) {
                report.note = "aninhamento";
                return report.fail(ItemReport.Violation.TEXT_COMPLEXITY, depth, limits.maxJsonDepth);
            }
            for (Component child : node.children()) {
                nodes.push(child);
                depths.push(depth + 1);
            }
            if (node instanceof TranslatableComponent) {
                if (++translate > limits.maxTranslatePerString) {
                    report.note = "translate";
                    return report.fail(ItemReport.Violation.TEXT_COMPLEXITY, translate, limits.maxTranslatePerString);
                }
                for (TranslationArgument argument : ((TranslatableComponent) node).arguments()) {
                    nodes.push(argument.asComponent());
                    depths.push(depth + 1);
                }
            }
            HoverEvent<?> hover = node.hoverEvent();
            if (hover != null && hover.value() instanceof Component) {
                nodes.push((Component) hover.value());
                depths.push(depth + 1);
            }
            ClickEvent click = node.clickEvent();
            if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
                report.runCommand = true;
            }
        }
        return true;
    }
}
