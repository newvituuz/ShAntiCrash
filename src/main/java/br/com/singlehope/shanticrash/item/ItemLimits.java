package br.com.singlehope.shanticrash.item;

/**
 * Immutable limits used while analysing items. Rebuilt on reload and swapped atomically; a disabled
 * check simply gets {@link #UNLIMITED} for its limit, so the scanners never look at the configuration.
 */
public final class ItemLimits {
    public static final int UNLIMITED = Integer.MAX_VALUE;

    public int maxNbtDepth = 32;
    public int maxNbtNodes = 32768;
    public int maxStringBytes = 32767;
    public int maxArrayLength = 16384;
    public int maxListLength = 1024;
    public int maxContainerItems = 96;
    public int maxChargedProjectiles = 16;
    public int maxProfileProperties = 16;
    public int maxProfileBytes = 8192;
    public int maxToolRules = 96;
    public int maxBundleItems = 64;
    public int maxLoreLines = 256;
    public int maxPredicates = 64;
    public int maxTextNodesTotal = 16384;
    public int maxItemBytes = 131072;
    public int maxItemsPerPacket = 1458;
    public int maxItemDepth = 4;
    public int maxComponents = 64;
    public int maxChangedSlots = 128;
    public int maxTranslatePerString = 16;
    public int maxExtraPerString = 1024;
    public int maxJsonDepth = 64;
    public int maxTextNodes = 4096;
    public int maxBookPages = 100;
    public int maxBookPageBytes = 3072;
    public int maxAmount = 127;
    public long maxProcessingNanos = 200_000_000L;
}
