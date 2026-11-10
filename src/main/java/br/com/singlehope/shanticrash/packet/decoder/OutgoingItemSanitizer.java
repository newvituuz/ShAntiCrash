package br.com.singlehope.shanticrash.packet.decoder;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.BundleContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class OutgoingItemSanitizer {
    public static final class Settings {
        public static final Settings DISABLED = new Settings(false, 256, 2048, 512, 4);

        public final boolean enabled;
        public final int maxLoreLines;
        public final int maxNameChars;
        public final int maxTextNodes;
        public final int maxNestedDepth;

        public Settings(boolean enabled, int maxLoreLines, int maxNameChars, int maxTextNodes, int maxNestedDepth) {
            this.enabled = enabled;
            this.maxLoreLines = clamp(maxLoreLines, 1, 1024);
            this.maxNameChars = clamp(maxNameChars, 16, 32767);
            this.maxTextNodes = clamp(maxTextNodes, 8, 65536);
            this.maxNestedDepth = clamp(maxNestedDepth, 1, 16);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private volatile Settings settings = Settings.DISABLED;
    private final AtomicLong sanitized = new AtomicLong();

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return settings.enabled;
    }

    public long sanitizedCount() {
        return sanitized.get();
    }

    public ItemStack sanitize(ItemStack item) {
        Settings active = settings;
        if (!active.enabled || item == null || item.isEmpty()) {
            return item;
        }
        ItemStack result = sanitize(item, active, 0);
        if (result != item) {
            sanitized.incrementAndGet();
        }
        return result;
    }

    public List<ItemStack> sanitize(List<ItemStack> items) {
        if (!settings.enabled || items == null) {
            return items;
        }
        List<ItemStack> copy = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack original = items.get(i);
            ItemStack clean = sanitize(original);
            if (clean != original) {
                if (copy == null) {
                    copy = new ArrayList<>(items);
                }
                copy.set(i, clean);
            }
        }
        return copy == null ? items : copy;
    }

    private ItemStack sanitize(ItemStack item, Settings active, int depth) {
        if (item == null || item.isEmpty()) {
            return item;
        }
        ItemStack copy = null;

        Optional<ItemLore> lore = item.getComponent(ComponentTypes.LORE);
        if (lore.isPresent()) {
            ItemLore cleaned = cleanLore(lore.get(), active);
            if (cleaned != lore.get()) {
                copy = copy == null ? item.copy() : copy;
                copy.setComponent(ComponentTypes.LORE, cleaned);
            }
        }
        Optional<Component> customName = item.getComponent(ComponentTypes.CUSTOM_NAME);
        if (customName.isPresent()) {
            Component cleaned = cleanText(customName.get(), active);
            if (cleaned != customName.get()) {
                copy = copy == null ? item.copy() : copy;
                copy.setComponent(ComponentTypes.CUSTOM_NAME, cleaned);
            }
        }
        Optional<Component> itemName = item.getComponent(ComponentTypes.ITEM_NAME);
        if (itemName.isPresent()) {
            Component cleaned = cleanText(itemName.get(), active);
            if (cleaned != itemName.get()) {
                copy = copy == null ? item.copy() : copy;
                copy.setComponent(ComponentTypes.ITEM_NAME, cleaned);
            }
        }
        Optional<ItemContainerContents> container = item.getComponent(ComponentTypes.CONTAINER);
        if (container.isPresent()) {
            List<ItemStack> cleaned = cleanNested(container.get().getItems(), active, depth);
            if (cleaned != container.get().getItems()) {
                copy = copy == null ? item.copy() : copy;
                copy.setComponent(ComponentTypes.CONTAINER, new ItemContainerContents(cleaned));
            }
        }
        Optional<BundleContents> bundle = item.getComponent(ComponentTypes.BUNDLE_CONTENTS);
        if (bundle.isPresent()) {
            List<ItemStack> cleaned = cleanNested(bundle.get().getItems(), active, depth);
            if (cleaned != bundle.get().getItems()) {
                copy = copy == null ? item.copy() : copy;
                copy.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(cleaned));
            }
        }
        return copy == null ? item : copy;
    }

    private List<ItemStack> cleanNested(List<ItemStack> items, Settings active, int depth) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        if (depth + 1 >= active.maxNestedDepth) {
            return new ArrayList<>(Collections.<ItemStack>emptyList());
        }
        List<ItemStack> copy = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack original = items.get(i);
            ItemStack clean = sanitize(original, active, depth + 1);
            if (clean != original) {
                if (copy == null) {
                    copy = new ArrayList<>(items);
                }
                copy.set(i, clean);
            }
        }
        return copy == null ? items : copy;
    }

    private ItemLore cleanLore(ItemLore lore, Settings active) {
        List<Component> lines = lore.getLines();
        List<Component> copy = null;
        int limit = Math.min(lines.size(), active.maxLoreLines);
        if (limit < lines.size()) {
            copy = new ArrayList<>(lines.subList(0, limit));
        }
        for (int i = 0; i < limit; i++) {
            Component line = lines.get(i);
            Component clean = cleanText(line, active);
            if (clean != line) {
                if (copy == null) {
                    copy = new ArrayList<>(lines.subList(0, limit));
                }
                copy.set(i, clean);
            }
        }
        return copy == null ? lore : new ItemLore(copy);
    }

    Component cleanText(Component root, Settings active) {
        ArrayDeque<Component> nodes = new ArrayDeque<>();
        nodes.push(root);
        int visited = 0;
        int chars = 0;
        boolean exceeded = false;
        StringBuilder plain = new StringBuilder();
        while (!nodes.isEmpty()) {
            Component node = nodes.pop();
            if (++visited > active.maxTextNodes) {
                exceeded = true;
                break;
            }
            if (node instanceof TextComponent) {
                String content = ((TextComponent) node).content();
                chars += content.length();
                if (plain.length() < active.maxNameChars) {
                    plain.append(content, 0, Math.min(content.length(), active.maxNameChars - plain.length()));
                }
                if (chars > active.maxNameChars) {
                    exceeded = true;
                    break;
                }
            }
            List<Component> children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                nodes.push(children.get(i));
            }
        }
        return exceeded ? Component.text(plain.toString()) : root;
    }
}
