package br.com.singlehope.shanticrash.item;

import br.com.singlehope.shanticrash.packet.PacketContext;
import br.com.singlehope.shanticrash.packet.PacketReader;

/**
 * Reads data component items (1.20.5 and newer). Separated from the analyzer because every
 * implementation needs PacketEvents' per version component registries.
 */
public interface ModernItemReader {
    /**
     * Validates one item at the reader position and moves the reader past it.
     *
     * @param lengthPrefixed whether top level component values carry a length, as in the creative
     *                       slot packet since 1.21.5
     * @return false if a violation was recorded in the report
     */
    boolean read(PacketContext context, PacketReader reader, boolean lengthPrefixed,
                 ItemLimits limits, ItemReport report);
}
