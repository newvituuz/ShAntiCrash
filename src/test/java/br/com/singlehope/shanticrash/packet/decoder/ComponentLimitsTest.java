package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAdventurePredicate;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentLimitsTest {
    private static ItemLore lore(int lines, int childrenPerLine) {
        List<Component> result = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            Component line = Component.text("l");
            for (int c = 0; c < childrenPerLine; c++) {
                line = line.append(Component.text("c"));
            }
            result.add(line);
        }
        return new ItemLore(result);
    }

    @Test
    void loreLinesHaveTheirOwnLimit() {
        ItemLimits limits = new ItemLimits();
        limits.maxLoreLines = 10;
        ItemReport report = new ItemReport();
        assertTrue(new ComponentValueInspector().inspect(lore(10, 0), limits, report));
        report.reset();
        assertFalse(new ComponentValueInspector().inspect(lore(11, 0), limits, report));
        assertEquals(ItemReport.Violation.LIST_LENGTH, report.violation);
        assertEquals("lore.lines", report.note);
    }

    @Test
    void textNodesAreBudgetedAcrossTheWholePacket() {
        ItemLimits limits = new ItemLimits();
        limits.maxTextNodes = 100;
        limits.maxTextNodesTotal = 120;
        ComponentValueInspector inspector = new ComponentValueInspector();
        ItemReport report = new ItemReport();
        assertTrue(inspector.inspect(lore(10, 9), limits, report));
        assertEquals(100, report.textNodes);
        assertFalse(inspector.inspect(lore(3, 9), limits, report));
        assertEquals(ItemReport.Violation.TEXT_COMPLEXITY, report.violation);
        report.reset();
        assertEquals(0, report.textNodes);
    }

    @Test
    void predicatesAreCountedWithTheirProperties() {
        ItemLimits limits = new ItemLimits();
        limits.maxPredicates = 3;
        List<ItemAdventurePredicate.BlockPredicate> predicates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            predicates.add(new ItemAdventurePredicate.BlockPredicate(null, Collections.<ItemAdventurePredicate.PropertyMatcher>emptyList(), null));
        }
        ItemReport report = new ItemReport();
        assertFalse(new ComponentValueInspector().inspect(new ItemAdventurePredicate(predicates), limits, report));
        assertEquals("predicates", report.note);
        report.reset();
        assertTrue(new ComponentValueInspector().inspect(new ItemAdventurePredicate(predicates.subList(0, 3)), limits, report));
    }
}
