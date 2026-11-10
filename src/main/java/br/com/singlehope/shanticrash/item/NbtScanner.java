package br.com.singlehope.shanticrash.item;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Iterative NBT validator working directly on the wire bytes.
 *
 * <p>Nothing is materialised: no tag objects, no strings. The only state is an explicit stack bounded
 * by the configured depth, so hostile nesting can neither overflow the thread stack nor allocate
 * memory. Every step consumes at least one byte of the frame, so the total work is linear in the frame
 * size, and that size was already validated by the frame check.
 */
public final class NbtScanner {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private static final int[] FIXED_SIZE = {0, 1, 2, 4, 8, 4, 8, -1, -1, -1, -1, -1, -1};
    /** Smallest possible encoding of each tag payload, used to reject impossible list sizes early. */
    private static final int[] MIN_SIZE = {0, 1, 2, 4, 8, 4, 8, 4, 2, 5, 1, 4, 4};

    private static final int FRAME_COMPOUND = -1;
    private static final int KEY_NONE = 0;
    private static final int KEY_ITEM = 1;
    private static final int KEY_ITEM_LIST = 2;
    private static final int KEY_PAGES = 3;

    private static final byte[] NAME_ITEM = ascii("Item");
    private static final byte[] NAME_ITEMS = ascii("Items");
    private static final byte[] NAME_INVENTORY = ascii("Inventory");
    private static final byte[] NAME_ARMOR_ITEMS = ascii("ArmorItems");
    private static final byte[] NAME_HAND_ITEMS = ascii("HandItems");
    private static final byte[] NAME_CHARGED = ascii("ChargedProjectiles");
    private static final byte[] NAME_PAGES = ascii("pages");

    private static final byte[] NEEDLE_TRANSLATE = ascii("translate");
    private static final byte[] NEEDLE_EXTRA = ascii("extra");
    private static final byte[] NEEDLE_RUN_COMMAND = ascii("run_command");

    private int[] frameType = new int[32];
    private int[] frameRemaining = new int[32];
    private int[] frameItemDepth = new int[32];
    private boolean[] frameItemElements = new boolean[32];
    private boolean[] framePages = new boolean[32];
    private int depth;

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Validates a root tag.
     *
     * @param namedRoot whether the root carries a name, which is the case before 1.20.2
     * @param baseItemDepth nesting level of the item that owns this tag
     * @return the position after the tag, or -1 with the reason stored in {@code report}
     */
    public int scanRoot(ByteBuf buf, int pos, int end, boolean namedRoot, int baseItemDepth,
                        ItemLimits limits, ItemReport report) {
        return scan(buf, pos, end, namedRoot, false, baseItemDepth, limits, report);
    }

    /**
     * Same as {@link #scanRoot}, for positions where the protocol accepts any tag as the root. Some data
     * components are stored that way, such as a list or a single string.
     */
    public int scanAnyRoot(ByteBuf buf, int pos, int end, boolean namedRoot, int baseItemDepth,
                           ItemLimits limits, ItemReport report) {
        return scan(buf, pos, end, namedRoot, true, baseItemDepth, limits, report);
    }

    private int scan(ByteBuf buf, int pos, int end, boolean namedRoot, boolean anyRoot, int baseItemDepth,
                     ItemLimits limits, ItemReport report) {
        if (pos >= end) {
            report.malformed("NBT truncado");
            return -1;
        }
        int rootType = buf.getUnsignedByte(pos++);
        if (rootType == TAG_END) {
            return pos;
        }
        if (rootType > TAG_LONG_ARRAY || (!anyRoot && rootType != TAG_COMPOUND)) {
            report.malformed("raiz NBT não é compound");
            return -1;
        }
        if (namedRoot) {
            if (end - pos < 2) {
                report.malformed("nome da raiz truncado");
                return -1;
            }
            int nameLength = buf.getUnsignedShort(pos);
            pos += 2;
            if (nameLength > end - pos) {
                report.malformed("nome da raiz truncado");
                return -1;
            }
            pos += nameLength;
        }
        depth = 0;
        // Any other root behaves as a list holding that single value.
        boolean pushed = rootType == TAG_COMPOUND
                ? push(FRAME_COMPOUND, 0, baseItemDepth, false, false, limits, report)
                : push(rootType, 1, baseItemDepth, false, false, limits, report);
        if (!pushed) {
            return -1;
        }

        int steps = 0;
        while (depth > 0) {
            if ((++steps & 0x3FF) == 0 && report.deadlineNanos != 0 && System.nanoTime() > report.deadlineNanos) {
                report.fail(ItemReport.Violation.PROCESSING_TIME, limits.maxProcessingNanos, limits.maxProcessingNanos);
                return -1;
            }
            int top = depth - 1;
            int tagType;
            int key = KEY_NONE;
            if (frameType[top] == FRAME_COMPOUND) {
                if (pos >= end) {
                    report.malformed("compound sem TAG_End");
                    return -1;
                }
                tagType = buf.getUnsignedByte(pos++);
                if (tagType == TAG_END) {
                    depth--;
                    continue;
                }
                if (tagType > TAG_LONG_ARRAY) {
                    report.malformed("tipo de tag desconhecido " + tagType);
                    return -1;
                }
                if (end - pos < 2) {
                    report.malformed("nome de tag truncado");
                    return -1;
                }
                int nameLength = buf.getUnsignedShort(pos);
                pos += 2;
                if (nameLength > end - pos) {
                    report.malformed("nome de tag truncado");
                    return -1;
                }
                key = classifyName(buf, pos, nameLength);
                pos += nameLength;
            } else {
                if (frameRemaining[top] == 0) {
                    depth--;
                    continue;
                }
                frameRemaining[top]--;
                tagType = frameType[top];
            }

            if (++report.nbtNodes > limits.maxNbtNodes) {
                report.fail(ItemReport.Violation.NBT_NODES, report.nbtNodes, limits.maxNbtNodes);
                return -1;
            }
            pos = payload(buf, pos, end, tagType, key, top, limits, report);
            if (pos < 0) {
                return -1;
            }
        }
        return pos;
    }

    private int payload(ByteBuf buf, int pos, int end, int tagType, int key, int top,
                        ItemLimits limits, ItemReport report) {
        int fixed = FIXED_SIZE[tagType];
        if (fixed >= 0) {
            if (fixed > end - pos) {
                report.malformed("valor truncado");
                return -1;
            }
            return pos + fixed;
        }
        switch (tagType) {
            case TAG_STRING:
                return stringPayload(buf, pos, end, top, limits, report);
            case TAG_BYTE_ARRAY:
                return arrayPayload(buf, pos, end, 1, limits, report);
            case TAG_INT_ARRAY:
                return arrayPayload(buf, pos, end, 4, limits, report);
            case TAG_LONG_ARRAY:
                return arrayPayload(buf, pos, end, 8, limits, report);
            case TAG_LIST:
                return listPayload(buf, pos, end, key, top, limits, report);
            case TAG_COMPOUND:
                return compoundPayload(pos, key, top, limits, report);
            default:
                report.malformed("tipo de tag desconhecido " + tagType);
                return -1;
        }
    }

    private int stringPayload(ByteBuf buf, int pos, int end, int top, ItemLimits limits, ItemReport report) {
        if (end - pos < 2) {
            report.malformed("string truncada");
            return -1;
        }
        int length = buf.getUnsignedShort(pos);
        pos += 2;
        if (length > limits.maxStringBytes) {
            report.fail(ItemReport.Violation.STRING_LENGTH, length, limits.maxStringBytes);
            return -1;
        }
        if (length > end - pos) {
            report.malformed("string truncada");
            return -1;
        }
        if (length > report.maxStringBytes) {
            report.maxStringBytes = length;
        }
        if (framePages[top]) {
            if (length > report.maxBookPageBytes) {
                report.maxBookPageBytes = length;
            }
            if (length > limits.maxBookPageBytes) {
                report.fail(ItemReport.Violation.BOOK_CONTENT, length, limits.maxBookPageBytes);
                return -1;
            }
        }
        // Shorter strings can not hold any of the patterns being searched for.
        if (length >= NEEDLE_EXTRA.length && !inspectText(buf, pos, length, limits, report)) {
            return -1;
        }
        return pos + length;
    }

    private int arrayPayload(ByteBuf buf, int pos, int end, int elementSize, ItemLimits limits, ItemReport report) {
        if (end - pos < 4) {
            report.malformed("array truncado");
            return -1;
        }
        int length = buf.getInt(pos);
        pos += 4;
        if (length < 0) {
            report.malformed("array com tamanho negativo");
            return -1;
        }
        if (length > limits.maxArrayLength) {
            report.fail(ItemReport.Violation.ARRAY_LENGTH, length, limits.maxArrayLength);
            return -1;
        }
        long bytes = (long) length * elementSize;
        if (bytes > end - pos) {
            report.malformed("array truncado");
            return -1;
        }
        if (length > report.maxArrayLength) {
            report.maxArrayLength = length;
        }
        return pos + (int) bytes;
    }

    private int listPayload(ByteBuf buf, int pos, int end, int key, int top, ItemLimits limits, ItemReport report) {
        if (end - pos < 5) {
            report.malformed("lista truncada");
            return -1;
        }
        int elementType = buf.getUnsignedByte(pos);
        int length = buf.getInt(pos + 1);
        pos += 5;
        if (elementType > TAG_LONG_ARRAY) {
            report.malformed("lista com tipo desconhecido");
            return -1;
        }
        if (length < 0) {
            report.malformed("lista com tamanho negativo");
            return -1;
        }
        if (length > 0 && elementType == TAG_END) {
            report.malformed("lista de TAG_End não vazia");
            return -1;
        }
        if (length > limits.maxListLength) {
            report.fail(ItemReport.Violation.LIST_LENGTH, length, limits.maxListLength);
            return -1;
        }
        if ((long) length * MIN_SIZE[elementType] > end - pos) {
            report.malformed("lista maior que o pacote");
            return -1;
        }
        if (length > report.maxListLength) {
            report.maxListLength = length;
        }
        if (key == KEY_PAGES) {
            if (length > report.bookPages) {
                report.bookPages = length;
            }
            if (length > limits.maxBookPages) {
                report.fail(ItemReport.Violation.BOOK_CONTENT, length, limits.maxBookPages);
                return -1;
            }
        }
        if (length == 0) {
            return pos;
        }
        int fixed = FIXED_SIZE[elementType];
        if (fixed >= 0) {
            // Primitive lists need no frame: the size was verified against the frame above.
            int nodes = report.nbtNodes + length;
            if (nodes < 0 || nodes > limits.maxNbtNodes) {
                report.fail(ItemReport.Violation.NBT_NODES, nodes, limits.maxNbtNodes);
                return -1;
            }
            report.nbtNodes = nodes;
            return pos + length * fixed;
        }
        boolean items = key == KEY_ITEM_LIST && elementType == TAG_COMPOUND;
        int itemDepth = frameItemDepth[top];
        if (!push(elementType, length, items ? itemDepth + 1 : itemDepth, items, key == KEY_PAGES, limits, report)) {
            return -1;
        }
        return pos;
    }

    private int compoundPayload(int pos, int key, int top, ItemLimits limits, ItemReport report) {
        int itemDepth = frameItemDepth[top];
        boolean isItem = key == KEY_ITEM || frameItemElements[top];
        if (isItem) {
            if (key == KEY_ITEM) {
                itemDepth++;
            }
            if (++report.items > limits.maxItemsPerPacket) {
                report.fail(ItemReport.Violation.ITEM_COUNT, report.items, limits.maxItemsPerPacket);
                return -1;
            }
            if (itemDepth > report.maxItemDepth) {
                report.maxItemDepth = itemDepth;
            }
            if (itemDepth > limits.maxItemDepth) {
                report.fail(ItemReport.Violation.ITEM_DEPTH, itemDepth, limits.maxItemDepth);
                return -1;
            }
        }
        if (!push(FRAME_COMPOUND, 0, itemDepth, false, false, limits, report)) {
            return -1;
        }
        return pos;
    }

    private boolean push(int type, int remaining, int itemDepth, boolean itemElements, boolean pages,
                         ItemLimits limits, ItemReport report) {
        int newDepth = depth + 1;
        if (newDepth > limits.maxNbtDepth) {
            report.fail(ItemReport.Violation.NBT_DEPTH, newDepth, limits.maxNbtDepth);
            return false;
        }
        if (depth == frameType.length) {
            int grown = depth * 2;
            frameType = Arrays.copyOf(frameType, grown);
            frameRemaining = Arrays.copyOf(frameRemaining, grown);
            frameItemDepth = Arrays.copyOf(frameItemDepth, grown);
            frameItemElements = Arrays.copyOf(frameItemElements, grown);
            framePages = Arrays.copyOf(framePages, grown);
        }
        frameType[depth] = type;
        frameRemaining[depth] = remaining;
        frameItemDepth[depth] = itemDepth;
        frameItemElements[depth] = itemElements;
        framePages[depth] = pages;
        depth = newDepth;
        if (newDepth > report.maxNbtDepth) {
            report.maxNbtDepth = newDepth;
        }
        return true;
    }

    private static int classifyName(ByteBuf buf, int pos, int length) {
        switch (length) {
            case 4:
                return matches(buf, pos, NAME_ITEM) ? KEY_ITEM : KEY_NONE;
            case 5:
                if (matches(buf, pos, NAME_ITEMS)) {
                    return KEY_ITEM_LIST;
                }
                return matches(buf, pos, NAME_PAGES) ? KEY_PAGES : KEY_NONE;
            case 9:
                return matches(buf, pos, NAME_INVENTORY) || matches(buf, pos, NAME_HAND_ITEMS)
                        ? KEY_ITEM_LIST : KEY_NONE;
            case 10:
                return matches(buf, pos, NAME_ARMOR_ITEMS) ? KEY_ITEM_LIST : KEY_NONE;
            case 18:
                return matches(buf, pos, NAME_CHARGED) ? KEY_ITEM_LIST : KEY_NONE;
            default:
                return KEY_NONE;
        }
    }

    private static boolean matches(ByteBuf buf, int pos, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (buf.getByte(pos + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Measures serialized chat inside a string without parsing it: bracket nesting and how many
     * translation and child nodes it declares. These are the shapes that make JSON components expensive.
     */
    private static boolean inspectText(ByteBuf buf, int pos, int length, ItemLimits limits, ItemReport report) {
        int end = pos + length;
        int nesting = 0;
        int maxNesting = 0;
        int translate = 0;
        int extra = 0;
        for (int i = pos; i < end; i++) {
            byte b = buf.getByte(i);
            switch (b) {
                case '{':
                case '[':
                    if (++nesting > maxNesting) {
                        maxNesting = nesting;
                    }
                    break;
                case '}':
                case ']':
                    if (nesting > 0) {
                        nesting--;
                    }
                    break;
                case 't':
                    if (end - i >= NEEDLE_TRANSLATE.length && matches(buf, i, NEEDLE_TRANSLATE)) {
                        translate++;
                    }
                    break;
                case 'e':
                    if (end - i >= NEEDLE_EXTRA.length && matches(buf, i, NEEDLE_EXTRA)) {
                        extra++;
                    }
                    break;
                case 'r':
                    if (end - i >= NEEDLE_RUN_COMMAND.length && matches(buf, i, NEEDLE_RUN_COMMAND)) {
                        report.runCommand = true;
                    }
                    break;
                default:
                    break;
            }
        }
        if (maxNesting > limits.maxJsonDepth) {
            report.fail(ItemReport.Violation.TEXT_COMPLEXITY, maxNesting, limits.maxJsonDepth);
            report.note = "aninhamento";
            return false;
        }
        if (translate > limits.maxTranslatePerString) {
            report.fail(ItemReport.Violation.TEXT_COMPLEXITY, translate, limits.maxTranslatePerString);
            report.note = "translate";
            return false;
        }
        if (extra > limits.maxExtraPerString) {
            report.fail(ItemReport.Violation.TEXT_COMPLEXITY, extra, limits.maxExtraPerString);
            report.note = "extra";
            return false;
        }
        return true;
    }
}
