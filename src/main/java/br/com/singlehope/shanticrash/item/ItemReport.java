package br.com.singlehope.shanticrash.item;

import java.util.Arrays;

/**
 * Result of analysing every item in one packet. Reused per thread: the analysis stops at the first
 * violation, so the fields describe everything read until that point.
 */
public final class ItemReport {
    /** What stopped the analysis. Each value maps to exactly one configurable check. */
    public enum Violation {
        NONE,
        MALFORMED,
        NBT_DEPTH,
        NBT_NODES,
        STRING_LENGTH,
        ARRAY_LENGTH,
        LIST_LENGTH,
        ITEM_BYTES,
        ITEM_COUNT,
        ITEM_DEPTH,
        COMPONENT_COUNT,
        TEXT_COMPLEXITY,
        ITEM_AMOUNT,
        BOOK_CONTENT,
        CHANGED_SLOTS,
        PROCESSING_TIME,
        /** The decoder could not read the item, which may be an incompatibility and not an attack. */
        UNDECODABLE
    }

    public Violation violation = Violation.NONE;
    public long observed;
    public long limit;
    public String note;

    public int nbtNodes;
    public int maxNbtDepth;
    public int maxStringBytes;
    public int maxArrayLength;
    public int maxListLength;
    public int items;
    public int maxItemDepth;
    public int components;
    public int maxItemBytes;
    public int bookPages;
    public int maxBookPageBytes;
    public int minAmount;
    public int maxAmount;
    public boolean runCommand;
    public int textNodes;
    public long deadlineNanos;
    public long elapsedNanos;
    public boolean slow;

    // Offsets of the top level items, used to compact them in place.
    private int[] itemStart = new int[16];
    private int[] itemHeaderEnd = new int[16];
    private int[] itemEnd = new int[16];
    private boolean[] itemHasData = new boolean[16];
    private int topLevelItems;

    public ItemReport reset() {
        violation = Violation.NONE;
        observed = 0;
        limit = 0;
        note = null;
        nbtNodes = 0;
        maxNbtDepth = 0;
        maxStringBytes = 0;
        maxArrayLength = 0;
        maxListLength = 0;
        items = 0;
        maxItemDepth = 0;
        components = 0;
        maxItemBytes = 0;
        bookPages = 0;
        maxBookPageBytes = 0;
        minAmount = 0;
        maxAmount = 0;
        runCommand = false;
        textNodes = 0;
        deadlineNanos = 0;
        elapsedNanos = 0;
        slow = false;
        topLevelItems = 0;
        return this;
    }

    public boolean ok() {
        return violation == Violation.NONE;
    }

    public boolean fail(Violation reason, long observed, long limit) {
        this.violation = reason;
        this.observed = observed;
        this.limit = limit;
        return false;
    }

    public boolean malformed(String why) {
        this.violation = Violation.MALFORMED;
        this.note = why;
        return false;
    }

    public boolean undecodable(String why) {
        this.violation = Violation.UNDECODABLE;
        this.note = why;
        return false;
    }

    public void recordAmount(int amount) {
        if (items == 0 || amount < minAmount) {
            minAmount = amount;
        }
        if (amount > maxAmount) {
            maxAmount = amount;
        }
    }

    public void addTopLevelItem(int start, int headerEnd, int end, boolean hasData) {
        if (topLevelItems == itemStart.length) {
            int grown = topLevelItems * 2;
            itemStart = Arrays.copyOf(itemStart, grown);
            itemHeaderEnd = Arrays.copyOf(itemHeaderEnd, grown);
            itemEnd = Arrays.copyOf(itemEnd, grown);
            itemHasData = Arrays.copyOf(itemHasData, grown);
        }
        itemStart[topLevelItems] = start;
        itemHeaderEnd[topLevelItems] = headerEnd;
        itemEnd[topLevelItems] = end;
        itemHasData[topLevelItems] = hasData;
        topLevelItems++;
    }

    public int topLevelItems() {
        return topLevelItems;
    }

    public int itemStart(int index) {
        return itemStart[index];
    }

    public int itemHeaderEnd(int index) {
        return itemHeaderEnd[index];
    }

    public int itemEnd(int index) {
        return itemEnd[index];
    }

    public boolean itemHasData(int index) {
        return itemHasData[index];
    }

    /** Short technical description for alerts and logs. */
    public String describe() {
        if (violation == Violation.MALFORMED) {
            return "estrutura inválida: " + note;
        }
        if (violation == Violation.UNDECODABLE) {
            return "item não decodificado: " + note;
        }
        if (violation == Violation.NONE) {
            return "sem violação";
        }
        return violation.name().toLowerCase() + "=" + observed + " (limite " + limit + ")";
    }
}
