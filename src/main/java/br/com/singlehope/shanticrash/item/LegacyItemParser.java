package br.com.singlehope.shanticrash.item;

import br.com.singlehope.shanticrash.packet.PacketReader;
import br.com.singlehope.shanticrash.packet.ServerProtocol;

/**
 * Reads the NBT based item wire layouts used before 1.20.5:
 * <ul>
 *   <li>1.8 to 1.12.2: {@code short id, byte count, short damage, NBT}</li>
 *   <li>1.13 to 1.13.1: {@code short id, byte count, NBT}</li>
 *   <li>1.13.2 to 1.20.4: {@code boolean present, VarInt id, byte count, NBT}</li>
 * </ul>
 */
public final class LegacyItemParser {
    private LegacyItemParser() {
    }

    /**
     * Validates one item at the reader position.
     *
     * @return false if a violation was recorded in the report
     */
    public static boolean read(PacketReader reader, ServerProtocol protocol, NbtScanner nbt,
                               ItemLimits limits, ItemReport report) {
        int start = reader.position();
        if (protocol.v1_13_2) {
            if (!reader.readBoolean()) {
                report.addTopLevelItem(start, reader.position(), reader.position(), false);
                return true;
            }
            int id = reader.readVarInt();
            if (id < 0) {
                return report.malformed("id de item negativo");
            }
        } else {
            int id = reader.readShort();
            if (id < 0) {
                report.addTopLevelItem(start, reader.position(), reader.position(), false);
                return true;
            }
        }
        int amount = reader.readByte();
        if (!protocol.v1_13) {
            reader.skip(2);
        }
        if (!countItem(amount, limits, report)) {
            return false;
        }

        int headerEnd = reader.position();
        int after = nbt.scanRoot(reader.buffer(), headerEnd, reader.end(), !protocol.namelessNbtRoot(),
                1, limits, report);
        if (after < 0) {
            return false;
        }
        reader.position(after);
        int size = after - start;
        if (size > report.maxItemBytes) {
            report.maxItemBytes = size;
        }
        if (size > limits.maxItemBytes) {
            return report.fail(ItemReport.Violation.ITEM_BYTES, size, limits.maxItemBytes);
        }
        report.addTopLevelItem(start, headerEnd, after, after - headerEnd > 1);
        return true;
    }

    static boolean countItem(int amount, ItemLimits limits, ItemReport report) {
        report.recordAmount(amount);
        if (amount < 0 || amount > limits.maxAmount) {
            return report.fail(ItemReport.Violation.ITEM_AMOUNT, amount, limits.maxAmount);
        }
        if (++report.items > limits.maxItemsPerPacket) {
            return report.fail(ItemReport.Violation.ITEM_COUNT, report.items, limits.maxItemsPerPacket);
        }
        if (report.maxItemDepth == 0) {
            report.maxItemDepth = 1;
        }
        return true;
    }
}
