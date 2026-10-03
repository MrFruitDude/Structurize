package com.ldtteam.structurize.client;

import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;

/**
 * Everything the cached preview mesh depends on besides the blueprint itself; a change re-tessellates it.
 * The model sets are replaced on every resource reload, so comparing them by identity catches reloads.
 * Rotation, mirror and the blueprint are not here: each of those has its own renderer (see {@link RenderingCacheKey}).
 * The anchor position is not here either: moving the ghost only changes the replay pose.
 */
record PreviewMeshKey(
    float alpha,
    boolean ambientOcclusion,
    boolean cutoutLeaves,
    BlockStateModelSet blockModels,
    FluidStateModelSet fluidModels)
{
    @Override
    public boolean equals(final Object other)
    {
        return other instanceof final PreviewMeshKey key
            && Float.compare(alpha, key.alpha) == 0
            && ambientOcclusion == key.ambientOcclusion
            && cutoutLeaves == key.cutoutLeaves
            && blockModels == key.blockModels
            && fluidModels == key.fluidModels;
    }

    @Override
    public int hashCode()
    {
        return Float.hashCode(alpha) * 31 + System.identityHashCode(blockModels);
    }
}
