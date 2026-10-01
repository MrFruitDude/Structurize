package com.ldtteam.structurize.api.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import com.mojang.serialization.DynamicOps;
import com.ldtteam.structurize.api.util.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Utility methods for the inventories.
 */
public final class ItemStackUtils
{
    /**
     * Private constructor to hide the implicit one.
     */
    private ItemStackUtils()
    {
        /*
         * Intentionally left empty.
         */
    }

    /**
     * Get itemStack of tileEntityData. Retrieve the data from the tileEntity.
     *
     * @param compound the tileEntity stored in a compound.
     * @param state the block.
     * @return the list of itemstacks.
     */
    public static List<ItemStack> getItemStacksOfTileEntity(final CompoundTag compound, final BlockState state)
    {
        if (compound == null)
        {
            return List.of();
        }

        BlockPos blockpos = new BlockPos(
            compound.getIntOr("x", 0),
            compound.getIntOr("y", 0),
            compound.getIntOr("z", 0)
        );
        final BlockEntity tileEntity = BlockEntity.loadStatic(blockpos, state, compound, RegistryLookups.current());

        // PORT-26.3 (upstream #699): read contents through the block entity's own inventory, including nested contents
        // (eg. a filled shulker box in a chest) and block entities that keep their items outside "Items" (decorated pot,
        // jukebox). 1.21 queries item capabilities on a fake level for block entities that are no vanilla Container;
        // the port only reaches capabilities when the block entity has a level, and otherwise falls back to "Items".
        final List<ItemStack> items = new ArrayList<>();
        getItemHandlersFromProvider(tileEntity).forEach(handler -> deepExtractItemHandler(handler, items::add));
        if (items.isEmpty() && compound.contains("Items") && (tileEntity == null || !(tileEntity instanceof Container)))
        {
            getItemStacksFromNbt(compound).forEach(stack -> deepExtractStack(stack, items::add));
        }
        return items;
    }

    /**
     * Adds every non-empty stack of the handler to the sink, followed by the contents of each stack that itself holds
     * items (shulker boxes, bundles), recursively.
     *
     * @param handler root handler to extract, may be null
     * @param sink    receives copies of all found stacks
     */
    public static void deepExtractItemHandler(@Nullable final IItemHandler handler, final Consumer<ItemStack> sink)
    {
        if (handler == null)
        {
            return;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            deepExtractStack(handler.getStackInSlot(slot).copy(), sink);
        }
    }

    private static void deepExtractStack(final ItemStack stack, final Consumer<ItemStack> sink)
    {
        if (ItemStackUtils.isEmpty(stack))
        {
            return;
        }
        sink.accept(stack);
        final ResourceHandler<ItemResource> contents = stack.getCapability(Capabilities.Item.ITEM, ItemAccess.forStack(stack.copy()));
        if (contents != null)
        {
            deepExtractItemHandler(IItemHandler.of(contents), sink);
        }
    }

    @NotNull
    private static List<ItemStack> getItemStacksFromNbt(@NotNull final CompoundTag compound)
    {
        final List<ItemStack> items = new ArrayList<>();
        final ListTag listtag = compound.getListOrEmpty("Items");

        for (int i = 0; i < listtag.size(); ++i)
        {
            final CompoundTag compoundtag = listtag.getCompoundOrEmpty(i);
            final DynamicOps<Tag> ops = RegistryLookups.current().createSerializationContext(NbtOps.INSTANCE);
            final ItemStack stack = ItemStack.CODEC.parse(ops, compoundtag)
                .resultOrPartial(error -> { throw new IllegalArgumentException("Invalid item stack NBT: " + error); })
                .orElse(ItemStack.EMPTY);
            if (!stack.isEmpty())
            {
                items.add(stack);
            }
        }

        return items;
    }

    /**
     * Parses an item stack from NBT using the live registries.
     */
    @NotNull
    public static ItemStack getItemStackFromNbt(@NotNull final CompoundTag compound)
    {
        if (compound.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        return ItemStack.CODEC.parse(RegistryLookups.current().createSerializationContext(NbtOps.INSTANCE), compound)
            .resultOrPartial(error -> Log.getLogger().warn("Invalid item stack NBT: {}", error))
            .orElse(ItemStack.EMPTY);
    }

    /**
     * Writes an item stack using the pre-26 compound representation.
     */
    @NotNull
    public static CompoundTag writeToNbt(@NotNull final ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return new CompoundTag();
        }

        return ItemStack.CODEC.encodeStart(
                RegistryLookups.current().createSerializationContext(NbtOps.INSTANCE), stack)
            .resultOrPartial(error -> Log.getLogger().warn("Failed to encode item stack: {}", error))
            .map(tag -> tag instanceof CompoundTag compound ? compound : new CompoundTag())
            .orElseGet(CompoundTag::new);
    }

    /**
     * Method to get all the IItemHandlers from a given Provider.
     *
     * @param provider The provider to get the IItemHandlers from.
     * @return A list with all the unique IItemHandlers a provider has.
     */
    public static Set<IItemHandler> getItemHandlersFromProvider(@Nullable final Object provider)
    {
        // PORT-26.3 (upstream #699): prefer the provider's whole inventory, so a sided capability cannot hide or
        // duplicate slots.
        if (provider instanceof final IItemHandler itemHandler)
        {
            return Set.of(itemHandler);
        }
        if (provider instanceof final Container container && (provider instanceof BlockEntity || provider instanceof Entity))
        {
            return Set.of(IItemHandler.of(VanillaContainerWrapper.of(container)));
        }
        if (provider instanceof final BlockEntity blockEntity && blockEntity.getLevel() != null)
        {
            final ResourceHandler<ItemResource> unsided = Capabilities.Item.BLOCK.getCapability(
                blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity, null);
            if (unsided != null)
            {
                return Set.of(IItemHandler.of(unsided));
            }

            final Set<IItemHandler> handlerSet = new HashSet<>();
            for (final Direction side : Direction.values())
            {
                final ResourceHandler<ItemResource> handler = Capabilities.Item.BLOCK.getCapability(
                    blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity, side);
                if (handler != null)
                {
                    handlerSet.add(IItemHandler.of(handler));
                }
            }
            return handlerSet;
        }
        if (provider instanceof final Entity entity)
        {
            ResourceHandler<ItemResource> handler = entity.getCapability(Capabilities.Item.ENTITY);
            if (handler == null)
            {
                handler = entity.getCapability(Capabilities.Item.ENTITY_AUTOMATION, null);
            }
            if (handler != null)
            {
                return Set.of(IItemHandler.of(handler));
            }

            final Set<IItemHandler> handlerSet = new HashSet<>();
            for (final Direction side : Direction.values())
            {
                final ResourceHandler<ItemResource> sided = entity.getCapability(Capabilities.Item.ENTITY_AUTOMATION, side);
                if (sided != null)
                {
                    handlerSet.add(IItemHandler.of(sided));
                }
            }
            return handlerSet;
        }
        return Set.of();
    }

    /**
     * Wrapper method to check if a stack is empty.
     * Used for easy updating to 1.11.
     *
     * @param stack The stack to check.
     * @return True when the stack is empty, false when not.
     */
    public static boolean isEmpty(@Nullable final ItemStack stack)
    {
        return stack == null || stack.isEmpty() || stack == ItemStack.EMPTY || stack.getCount() <= 0;
    }

    /**
     * get the size of the stack.
     * This is for compatibility between 1.10 and 1.11
     *
     * @param stack to get the size from
     * @return the size of the stack
     */
    public static int getSize(final ItemStack stack)
    {
        if (ItemStackUtils.isEmpty(stack))
        {
            return 0;
        }

        return stack.getCount();
    }

    /**
     * Get the list of required resources for entities: the item that spawns the entity, then its contents (including
     * nested contents, eg. a shulker box in a chest minecart).
     *
     * @param entity the entity object.
     * @param pos the placer pos (unused, kept for API compatibility).
     * @return a list of stacks.
     */
    public static List<ItemStack> getListOfStackForEntity(final Entity entity, final BlockPos pos)
    {
        return getListOfStackForEntity(entity);
    }

    /**
     * Get the list of required resources for entities: the item that spawns the entity, then its contents (including
     * nested contents, eg. a shulker box in a chest minecart). Mobs never require their spawn egg.
     *
     * @param entity the entity object.
     * @return a list of stacks.
     */
    public static List<ItemStack> getListOfStackForEntity(@Nullable final Entity entity)
    {
        if (entity == null)
        {
            return List.of();
        }

        // PORT-26.3 (upstream #699): one implementation for every entity instead of the item frame / armor stand /
        // container entity special cases, so glow item frames need a glow item frame and plain minecarts or boats
        // need their item.
        final List<ItemStack> request = new ArrayList<>();
        final ItemStack spawnItem = getEntitySpawningItem(entity);
        if (spawnItem != null && !(spawnItem.getItem() instanceof SpawnEggItem))
        {
            request.add(spawnItem);
        }
        request.addAll(getItemStacksOfEntity(entity));
        return request.stream().filter(stack -> !stack.isEmpty()).collect(Collectors.toList());
    }

    /**
     * Get the contents of an entity, including nested contents.
     *
     * @param entity the entity object.
     * @return a list of stacks.
     */
    public static List<ItemStack> getItemStacksOfEntity(@Nullable final Entity entity)
    {
        if (entity == null)
        {
            return List.of();
        }

        final List<ItemStack> contents = new ArrayList<>();
        final Set<IItemHandler> handlers = getItemHandlersFromProvider(entity);
        if (!handlers.isEmpty())
        {
            handlers.forEach(handler -> deepExtractItemHandler(handler, contents::add));
        }
        // some vanilla entities hold an item without exposing an item capability
        else if (entity instanceof final ItemFrame itemFrame)
        {
            deepExtractStack(itemFrame.getItem().copyWithCount(1), contents::add);
        }
        else if (entity instanceof final ItemEntity itemEntity)
        {
            deepExtractStack(itemEntity.getItem().copy(), contents::add);
        }
        return contents;
    }

    /**
     * @return the item that places the given entity, or null if there is none
     */
    @Nullable
    public static ItemStack getEntitySpawningItem(final Entity entity)
    {
        if (entity instanceof final ItemFrame itemFrame)
        {
            // 26.3 ItemFrame#getPickResult returns the framed item when there is one; the frame item itself is protected.
            final ItemStack frame = new ItemStack(itemFrame instanceof GlowItemFrame ? Items.GLOW_ITEM_FRAME : Items.ITEM_FRAME);
            if (itemFrame.hasCustomName())
            {
                frame.set(DataComponents.CUSTOM_NAME, itemFrame.getCustomName());
            }
            return frame;
        }
        final ItemStack picked = entity.getPickResult();
        return picked == null ? null : picked.copy();
    }

    /**
     * Method to compare to stacks, ignoring their stacksize.
     *
     * @param itemStack1 The left stack to compare.
     * @param itemStack2 The right stack to compare.
     * @return True when they are equal except the stacksize, false when not.
     */
    public static boolean compareItemStacksIgnoreStackSize(final ItemStack itemStack1, final ItemStack itemStack2)
    {
        return compareItemStacksIgnoreStackSize(itemStack1, itemStack2, true, true);
    }

    /**
     * Method to compare to stacks, ignoring their stacksize.
     *
     * @param itemStack1  The left stack to compare.
     * @param itemStack2  The right stack to compare.
     * @param matchDamage Set to true to match damage data.
     * @param matchNBT    Set to true to match nbt
     * @return True when they are equal except the stacksize, false when not.
     */
    public static boolean compareItemStacksIgnoreStackSize(final ItemStack itemStack1, final ItemStack itemStack2, final boolean matchDamage, final boolean matchNBT)
    {
        return compareItemStacksIgnoreStackSize(itemStack1, itemStack2, matchDamage, matchNBT, false);
    }

    /**
     * Method to compare to stacks, ignoring their stacksize.
     *
     * @param itemStack1  The left stack to compare.
     * @param itemStack2  The right stack to compare.
     * @param matchDamage Set to true to match damage data.
     * @param matchNBT    Set to true to match nbt
     * @param min         if the count of stack2 has to be at least the same as stack1.
     * @return True when they are equal except the stacksize, false when not.
     */
    public static boolean compareItemStacksIgnoreStackSize(
      final ItemStack itemStack1,
      final ItemStack itemStack2,
      final boolean matchDamage,
      final boolean matchNBT,
      final boolean min)
    {
        if (isEmpty(itemStack1) && isEmpty(itemStack2))
        {
            return true;
        }

        if (isEmpty(itemStack1) != isEmpty(itemStack2))
        {
            return false;
        }

        if (itemStack1.getItem() == itemStack2.getItem() && (!matchDamage || itemStack1.getDamageValue() == itemStack2.getDamageValue()))
        {
            if (!matchNBT)
            {
                // Not comparing nbt
                return true;
            }

            if (min && itemStack1.getCount() > itemStack2.getCount())
            {
                return false;
            }

            // Data components replace the legacy item NBT map.
            if (matchNBT)
            {
                return ItemStack.matchesIgnoringComponents(
                    itemStack1.copyWithCount(itemStack2.getCount()),
                    itemStack2,
                    type -> !matchDamage && type == DataComponents.DAMAGE
                );
            }
            else
            {
                return true;
            }
        }
        return false;
    }
}
