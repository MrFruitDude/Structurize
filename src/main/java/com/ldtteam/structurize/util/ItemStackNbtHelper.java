package com.ldtteam.structurize.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Compatibility boundary between Structurize's persisted item tags and Minecraft's data components.
 */
public final class ItemStackNbtHelper
{
    private ItemStackNbtHelper()
    {
    }

    public static boolean hasCustomTag(final ItemStack stack)
    {
        return stack.has(DataComponents.CUSTOM_DATA);
    }

    @Nullable
    public static CompoundTag getCustomTag(final ItemStack stack)
    {
        final CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? null : customData.copyTag();
    }

    /**
     * Read-only snapshot of the stack's custom data (empty tag if none). Writes to the returned tag are NOT stored on the stack:
     * item components are immutable, so use {@link #updateCustomTag} to change them.
     */
    public static CompoundTag copyCustomTag(final ItemStack stack)
    {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /**
     * Applies {@code writer} to a copy of the stack's custom data and stores the result back on the stack.
     */
    public static void updateCustomTag(final ItemStack stack, final Consumer<CompoundTag> writer)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, writer);
    }

    public static void setCustomTag(final ItemStack stack, final CompoundTag tag)
    {
        if (tag.isEmpty())
        {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
        else
        {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    public static ItemStack readNetworkStack(final FriendlyByteBuf buf)
    {
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(registryBuf(buf));
    }

    public static void writeNetworkStack(final FriendlyByteBuf buf, final ItemStack stack)
    {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(registryBuf(buf), stack);
    }

    /**
     * Structurize's network channel always hands messages a {@link RegistryFriendlyByteBuf} bound to the connection's registries,
     * which item stacks need for enchantments, banner patterns and other datapack registries.
     */
    public static RegistryFriendlyByteBuf registryBuf(final FriendlyByteBuf buf)
    {
        if (buf instanceof final RegistryFriendlyByteBuf registryBuf)
        {
            return registryBuf;
        }
        throw new IllegalStateException("Item stacks need a registry-aware buffer; send this message through Structurize's NetworkChannel");
    }
}
