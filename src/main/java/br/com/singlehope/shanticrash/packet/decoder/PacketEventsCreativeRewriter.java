package br.com.singlehope.shanticrash.packet.decoder;

import br.com.singlehope.shanticrash.checks.item.DecodingGuard;
import br.com.singlehope.shanticrash.packet.PacketContext;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.PatchableComponentMap;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps only the allowed data of a creative item. Encoding per version is left to PacketEvents; this
 * runs after the analysis, so the item it decodes is already known to be within limits.
 */
public final class PacketEventsCreativeRewriter implements DecodingGuard.CreativeRewriter {
    @Override
    public boolean rewrite(PacketContext context, Set<String> allowedComponents, Set<String> allowedNbt) {
        PacketReceiveEvent event = (PacketReceiveEvent) context.event;
        WrapperPlayClientCreativeInventoryAction wrapper = new WrapperPlayClientCreativeInventoryAction(event);
        ItemStack stack = wrapper.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemStack rebuilt = context.protocol.componentItems()
                ? filterComponents(stack, allowedComponents)
                : filterNbt(stack, allowedNbt);
        if (rebuilt == null) {
            return false;
        }
        wrapper.setItemStack(rebuilt);
        event.markForReEncode(true);
        return true;
    }

    private ItemStack filterComponents(ItemStack stack, Set<String> allowed) {
        PatchableComponentMap components = stack.getComponents();
        Map<ComponentType<?>, Optional<?>> patches = components.getPatches();
        Map<ComponentType<?>, Optional<?>> kept = new HashMap<>();
        for (Map.Entry<ComponentType<?>, Optional<?>> patch : patches.entrySet()) {
            if (allowed.contains(patch.getKey().getName().toString())) {
                kept.put(patch.getKey(), patch.getValue());
            }
        }
        if (kept.size() == patches.size()) {
            return null;
        }
        return ItemStack.builder()
                .type(stack.getType())
                .amount(stack.getAmount())
                .components(new PatchableComponentMap(components.getBase(), kept))
                .build();
    }

    private ItemStack filterNbt(ItemStack stack, Set<String> allowed) {
        NBTCompound nbt = stack.getNBT();
        if (nbt == null || nbt.isEmpty()) {
            return null;
        }
        NBTCompound kept = new NBTCompound();
        for (Map.Entry<String, NBT> tag : nbt.getTags().entrySet()) {
            if (allowed.contains(tag.getKey())) {
                kept.setTag(tag.getKey(), tag.getValue());
            }
        }
        if (kept.size() == nbt.size()) {
            return null;
        }
        return ItemStack.builder()
                .type(stack.getType())
                .amount(stack.getAmount())
                .legacyData(stack.getLegacyData())
                .nbt(kept)
                .build();
    }
}
