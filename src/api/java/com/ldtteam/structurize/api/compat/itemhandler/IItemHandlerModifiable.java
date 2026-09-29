/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/*
 * PORT-26.3: vendored from NeoForge 26.2.0.66 net.neoforged.neoforge.items (package renamed),
 * which NeoForge 26.3 removed. Keeps the legacy IItemHandler bridge that Structurize and
 * MineColonies build on; new code should use ResourceHandler<ItemResource> directly.
 */
package com.ldtteam.structurize.api.compat.itemhandler;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.IndexModifier;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * @deprecated Replaced by the {@link IndexModifier} interface for usage in {@link ResourceHandlerSlot}.
 */
public interface IItemHandlerModifiable extends IItemHandler {
    /**
     * Overrides the stack in the given slot. This method is used by the
     * standard Forge helper methods and classes. It is not intended for
     * general use by other mods, and the handler may throw an error if it
     * is called unexpectedly.
     *
     * @param slot  Slot to modify
     * @param stack ItemStack to set slot to (may be empty).
     * @throws RuntimeException if the handler is called in a way that the handler
     *                          was not expecting.
     **/
    void setStackInSlot(int slot, ItemStack stack);
}
