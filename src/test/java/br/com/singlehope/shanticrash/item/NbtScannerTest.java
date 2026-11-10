package br.com.singlehope.shanticrash.item;

import br.com.singlehope.shanticrash.support.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.time.Duration.ofSeconds;

class NbtScannerTest {
    private final NbtScanner scanner = new NbtScanner();
    private final ItemLimits limits = new ItemLimits();
    private final ItemReport report = new ItemReport();

    private int scan(byte[] data, boolean named) {
        report.reset();
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        return scanner.scanRoot(buffer, 0, data.length, named, 1, limits, report);
    }

    @Test
    void acceptsAbsentTag() {
        assertEquals(1, scan(Wire.nbtEmpty(), true));
        assertTrue(report.ok());
    }

    @Test
    void acceptsTypicalItemAndConsumesExactly() {
        byte[] item = Wire.create().nbtRoot(true)
                .nbtOpenCompound("display").nbtString("Name", "Espada").nbtEnd()
                .nbtOpenList("ench", 10, 2)
                .nbtByte("id", 16).nbtEnd()
                .nbtByte("id", 34).nbtEnd()
                .nbtInt("HideFlags", 1)
                .nbtEnd().toBytes();
        assertEquals(item.length, scan(item, true));
        assertTrue(report.ok());
        assertEquals(3, report.maxNbtDepth);
    }

    @Test
    void namelessRootIsReadCorrectly() {
        byte[] item = Wire.create().nbtRoot(false).nbtString("a", "b").nbtEnd().toBytes();
        assertEquals(item.length, scan(item, false));
        assertTrue(report.ok());
    }

    @Test
    void depthLimitStopsNesting() {
        assertEquals(-1, scan(Wire.nbtDeep(limits.maxNbtDepth + 1, true), true));
        assertEquals(ItemReport.Violation.NBT_DEPTH, report.violation);
        assertTrue(scan(Wire.nbtDeep(limits.maxNbtDepth, true), true) > 0);
    }

    /** A recursive parser would overflow the stack here; a real frame can hold this much nesting. */
    @Test
    void hostileNestingDoesNotRecurseOrGrowTheStack() {
        limits.maxNbtDepth = ItemLimits.UNLIMITED;
        limits.maxNbtNodes = ItemLimits.UNLIMITED;
        byte[] data = Wire.nbtDeep(200_000, true);
        assertTimeout(ofSeconds(5), () -> assertEquals(data.length, scan(data, true)));
        assertTrue(report.ok());
    }

    @Test
    void nodeLimit() {
        assertEquals(-1, scan(Wire.nbtManyTags(limits.maxNbtNodes + 1, true), true));
        assertEquals(ItemReport.Violation.NBT_NODES, report.violation);
    }

    @Test
    void primitiveListsCountAsNodes() {
        limits.maxNbtNodes = 100;
        byte[] data = Wire.create().nbtRoot(true).nbtOpenList("l", 1, 200).raw(new byte[200]).nbtEnd().toBytes();
        assertEquals(-1, scan(data, true));
        assertEquals(ItemReport.Violation.NBT_NODES, report.violation);
    }

    @Test
    void stringLimit() {
        limits.maxStringBytes = 10;
        assertEquals(-1, scan(Wire.nbtLongString(11, true), true));
        assertEquals(ItemReport.Violation.STRING_LENGTH, report.violation);
    }

    @Test
    void arrayLimit() {
        assertEquals(-1, scan(Wire.nbtBigArray(limits.maxArrayLength + 1, true), true));
        assertEquals(ItemReport.Violation.ARRAY_LENGTH, report.violation);
    }

    @Test
    void listLimit() {
        assertEquals(-1, scan(Wire.nbtBigList(limits.maxListLength + 1, true), true));
        assertEquals(ItemReport.Violation.LIST_LENGTH, report.violation);
    }

    @Test
    void itemNestingFollowsContainerKeys() {
        assertEquals(-1, scan(Wire.nbtNestedContainers(limits.maxItemDepth + 2, true), true));
        assertEquals(ItemReport.Violation.ITEM_DEPTH, report.violation);
        assertTrue(scan(Wire.nbtNestedContainers(2, true), true) > 0);
        assertEquals(2, report.items);
    }

    @Test
    void bookPagesAreLimited() {
        assertEquals(-1, scan(Wire.nbtBook(limits.maxBookPages + 1, 1, true), true));
        assertEquals(ItemReport.Violation.BOOK_CONTENT, report.violation);
        assertEquals(-1, scan(Wire.nbtBook(1, limits.maxBookPageBytes + 1, true), true));
        assertEquals(ItemReport.Violation.BOOK_CONTENT, report.violation);
        assertTrue(scan(Wire.nbtBook(limits.maxBookPages, 256, true), true) > 0);
    }

    @Test
    void textComplexity() {
        assertEquals(-1, scan(Wire.nbtText(Wire.repeat('[', limits.maxJsonDepth + 1), true), true));
        assertEquals(ItemReport.Violation.TEXT_COMPLEXITY, report.violation);

        StringBuilder translate = new StringBuilder();
        for (int i = 0; i <= limits.maxTranslatePerString; i++) {
            translate.append("{\"translate\":\"a\"}");
        }
        assertEquals(-1, scan(Wire.nbtText(translate.toString(), true), true));
        assertEquals(ItemReport.Violation.TEXT_COMPLEXITY, report.violation);
    }

    @Test
    void ordinaryRichTextIsAccepted() {
        StringBuilder gradient = new StringBuilder("{\"extra\":[");
        for (int i = 0; i < 40; i++) {
            gradient.append("{\"text\":\"a\",\"color\":\"#123456\"},");
        }
        gradient.append("{\"text\":\"\"}],\"text\":\"\"}");
        assertTrue(scan(Wire.nbtText(gradient.toString(), true), true) > 0);
        assertTrue(report.ok());
    }

    @Test
    void detectsRunCommand() {
        scan(Wire.nbtText("{\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op x\"}}", true), true);
        assertTrue(report.ok());
        assertTrue(report.runCommand);
    }

    @Test
    void rejectsEveryKindOfBadStructure() {
        assertMalformed(new byte[]{});
        assertMalformed(new byte[]{5, 0, 0});
        assertMalformed(new byte[]{10, 0, 0});
        assertMalformed(Wire.create().nbtRoot(true).b(99).s(0).toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(8).s(10).raw(new byte[3]).toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(8).s(0).s(500).raw(new byte[3]).toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(7).s(0).i(-1).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(7).s(0).i(100).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(11).s(0).i(4).raw(new byte[4]).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(9).s(0).b(1).i(-5).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(9).s(0).b(0).i(5).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(9).s(0).b(77).i(0).nbtEnd().toBytes());
        assertMalformed(Wire.create().nbtRoot(true).b(9).s(0).b(10).i(1000).nbtEnd().toBytes());
    }

    private void assertMalformed(byte[] data) {
        assertEquals(-1, scan(data, true));
        assertEquals(ItemReport.Violation.MALFORMED, report.violation);
    }

    @Test
    void deadlineStopsLongScans() {
        limits.maxNbtNodes = ItemLimits.UNLIMITED;
        limits.maxListLength = ItemLimits.UNLIMITED;
        byte[] data = Wire.nbtManyTags(100_000, true);
        report.reset();
        report.deadlineNanos = System.nanoTime() - 1;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        assertEquals(-1, scanner.scanRoot(buffer, 0, data.length, true, 1, limits, report));
        assertEquals(ItemReport.Violation.PROCESSING_TIME, report.violation);
    }

    @Test
    void scannerStateIsCleanAfterAFailure() {
        assertEquals(-1, scan(Wire.nbtDeep(limits.maxNbtDepth + 1, true), true));
        byte[] valid = Wire.create().nbtRoot(true).nbtByte("a", 1).nbtEnd().toBytes();
        assertEquals(valid.length, scan(valid, true));
        assertFalse(report.runCommand);
        assertTrue(report.ok());
    }
}
