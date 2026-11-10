package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.item.ItemLimits;
import br.com.singlehope.shanticrash.item.ItemReport;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WritableBookContent;
import com.github.retrooper.packetevents.util.Filterable;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ComponentValueInspectorTest {
    @Test
    void rejectsUtf8PageOverLimit() {
        ItemLimits limits = new ItemLimits();
        limits.maxBookPageBytes = 3;
        ItemReport report = new ItemReport();
        WritableBookContent content = new WritableBookContent(
                Collections.singletonList(new Filterable<>("💜")));
        assertFalse(new ComponentValueInspector().inspect(content, limits, report));
        assertEquals(ItemReport.Violation.BOOK_CONTENT, report.violation);
        assertEquals(4, report.observed);
    }

    @Test
    void limitsProfilePropertyCountAndAggregateBytes() {
        ComponentValueInspector inspector = new ComponentValueInspector();
        ItemLimits limits = new ItemLimits();
        limits.maxProfileProperties = 1;
        ItemProfile.Property property = new ItemProfile.Property("textures", "value", null);
        ItemProfile oversized = new ItemProfile(null, null, Arrays.asList(property, property));
        ItemReport report = new ItemReport();
        assertFalse(inspector.inspect(oversized, limits, report));
        assertEquals(ItemReport.Violation.LIST_LENGTH, report.violation);
        limits.maxProfileProperties = 16;
        limits.maxProfileBytes = 5;
        report.reset();
        assertFalse(inspector.inspect(new ItemProfile(null, null, Collections.singletonList(property)), limits, report));
        assertEquals(ItemReport.Violation.STRING_LENGTH, report.violation);
    }
}
