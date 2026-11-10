package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.support.TestPacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.BundleContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingItemSanitizerTest {
    @BeforeAll
    static void install() {
        TestPacketEvents.install(ServerVersion.V_1_21_4);
    }

    private static OutgoingItemSanitizer sanitizer(int lore, int chars, int nodes, int depth) {
        OutgoingItemSanitizer sanitizer = new OutgoingItemSanitizer();
        sanitizer.settings(new OutgoingItemSanitizer.Settings(true, lore, chars, nodes, depth));
        return sanitizer;
    }

    private static ItemStack withLore(int lines) {
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            lore.add(Component.text("linha " + i));
        }
        ItemStack item = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).amount(1).build();
        item.setComponent(ComponentTypes.LORE, new ItemLore(lore));
        return item;
    }

    private static ItemStack shulker(ItemStack... contents) {
        ItemStack item = ItemStack.builder().type(ItemTypes.SHULKER_BOX).amount(1).build();
        item.setComponent(ComponentTypes.CONTAINER, new ItemContainerContents(new ArrayList<>(Arrays.asList(contents))));
        return item;
    }

    @Test
    void disabledOrSmallItemsAreReturnedUntouched() {
        OutgoingItemSanitizer off = new OutgoingItemSanitizer();
        ItemStack big = withLore(500);
        assertSame(big, off.sanitize(big));
        OutgoingItemSanitizer on = sanitizer(256, 2048, 512, 4);
        ItemStack small = withLore(3);
        assertSame(small, on.sanitize(small));
        assertSame(ItemStack.EMPTY, on.sanitize(ItemStack.EMPTY));
        assertEquals(0L, on.sanitizedCount());
    }

    @Test
    void loreIsTruncatedOnACopyKeepingTypeAndAmount() {
        OutgoingItemSanitizer on = sanitizer(10, 2048, 512, 4);
        ItemStack original = ItemStack.builder().type(ItemTypes.DIAMOND).amount(7).build();
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lines.add(Component.text("x" + i));
        }
        original.setComponent(ComponentTypes.LORE, new ItemLore(lines));
        ItemStack clean = on.sanitize(original);
        assertNotSame(original, clean);
        assertEquals(10, clean.getComponent(ComponentTypes.LORE).get().getLines().size());
        assertEquals(40, original.getComponent(ComponentTypes.LORE).get().getLines().size());
        assertEquals(original.getType(), clean.getType());
        assertEquals(7, clean.getAmount());
        assertEquals(1L, on.sanitizedCount());
    }

    @Test
    void oversizedNamesBecomePlainTextWithinTheLimit() {
        OutgoingItemSanitizer on = sanitizer(256, 32, 512, 4);
        ItemStack item = ItemStack.builder().type(ItemTypes.STONE).amount(1).build();
        Component name = Component.text("a").append(Component.text(String.join("", Collections.nCopies(100, "b"))));
        item.setComponent(ComponentTypes.CUSTOM_NAME, name);
        ItemStack clean = on.sanitize(item);
        Component result = clean.getComponent(ComponentTypes.CUSTOM_NAME).get();
        assertTrue(result instanceof net.kyori.adventure.text.TextComponent);
        assertEquals(32, ((net.kyori.adventure.text.TextComponent) result).content().length());
        assertSame(name, item.getComponent(ComponentTypes.CUSTOM_NAME).get());
    }

    @Test
    void deepNestingIsCutOnlyBelowTheConfiguredDepth() {
        OutgoingItemSanitizer on = sanitizer(256, 2048, 512, 2);
        ItemStack inner = shulker(ItemStack.builder().type(ItemTypes.DIRT).amount(64).build());
        ItemStack outer = shulker(inner);
        ItemStack clean = on.sanitize(outer);
        List<ItemStack> level1 = clean.getComponent(ComponentTypes.CONTAINER).get().getItems();
        assertEquals(1, level1.size());
        assertTrue(level1.get(0).getComponent(ComponentTypes.CONTAINER).get().getItems().isEmpty());
        assertEquals(1, inner.getComponent(ComponentTypes.CONTAINER).get().getItems().size());

        OutgoingItemSanitizer roomy = sanitizer(256, 2048, 512, 4);
        assertSame(outer, roomy.sanitize(outer));
    }

    @Test
    void bundlesAndListsKeepPositionsAndUnchangedEntries() {
        OutgoingItemSanitizer on = sanitizer(5, 2048, 512, 4);
        ItemStack plain = ItemStack.builder().type(ItemTypes.STONE).amount(2).build();
        ItemStack heavy = withLore(50);
        ItemStack bundle = ItemStack.builder().type(ItemTypes.BUNDLE).amount(1).build();
        bundle.setComponent(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(new ArrayList<>(Arrays.asList(plain, heavy))));
        List<ItemStack> window = Arrays.asList(plain, bundle, ItemStack.EMPTY, heavy);
        List<ItemStack> clean = on.sanitize(window);
        assertNotSame(window, clean);
        assertEquals(4, clean.size());
        assertSame(plain, clean.get(0));
        assertSame(ItemStack.EMPTY, clean.get(2));
        assertEquals(5, clean.get(3).getComponent(ComponentTypes.LORE).get().getLines().size());
        List<ItemStack> inside = clean.get(1).getComponent(ComponentTypes.BUNDLE_CONTENTS).get().getItems();
        assertSame(plain, inside.get(0));
        assertEquals(5, inside.get(1).getComponent(ComponentTypes.LORE).get().getLines().size());
        List<ItemStack> untouched = Arrays.asList(plain, ItemStack.EMPTY);
        assertSame(untouched, on.sanitize(untouched));
    }
}
