/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/*
 * PORT-26.3: vendored from NeoForge 26.2.0.66 net.neoforged.neoforge.items (package renamed),
 * which NeoForge 26.3 removed. Keeps the legacy IItemHandler bridge that Structurize and
 * MineColonies build on; new code should use ResourceHandler<ItemResource> directly.
 */
package com.ldtteam.structurize.api.compat.itemhandler.wrapper;

import net.minecraft.world.item.ItemStack;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandler;
import com.ldtteam.structurize.api.compat.itemhandler.IItemHandlerModifiable;
import net.neoforged.neoforge.transfer.EmptyResourceHandler;

/**
 * @deprecated Use {@link EmptyResourceHandler} instead.
 */
public class EmptyItemHandler implements IItemHandlerModifiable {
    public static final IItemHandler INSTANCE = new EmptyItemHandler();

    @Override
    public int getSlots() {
        return 0;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        // nothing to do here
    }

    @Override
    public int getSlotLimit(int slot) {
        return 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
