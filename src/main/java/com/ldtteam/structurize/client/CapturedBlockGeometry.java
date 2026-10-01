package com.ldtteam.structurize.client;

import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import net.neoforged.neoforge.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * The block model of a tag substitution replacement as 1.21's {@code renderSingleBlock} drew it: the replacement's own block
 * entity answers model data (so e.g. a Domum block shows its materials) and tints come from the block's colour sources, or its
 * dynamic tint values for blocks that imitate others.
 *
 * @param parts model parts to draw
 * @param tints tint value per tint index
 */
public record CapturedBlockGeometry(List<BlockStateModelPart> parts, int[] tints)
{
    public static final CapturedBlockGeometry EMPTY = new CapturedBlockGeometry(List.of(), new int[0]);

    /**
     * @param replacement the replacement block
     * @param world       the level around the anchor; for an item the client level (or null) that only answers biome tints
     * @param pos         where the replacement is drawn
     * @param inWorld     true for a placed anchor; false for an item, whose vanilla tints use the block's default colour
     * @return geometry, {@link #EMPTY} when the replacement has no block model
     */
    public static CapturedBlockGeometry collect(final BlockEntityTagSubstitution.ReplacementBlock replacement,
        @Nullable final BlockAndTintGetter world,
        final BlockPos pos,
        final boolean inWorld)
    {
        final BlockState state = replacement.getBlockState();
        if (replacement.isEmpty() || state.getRenderShape() != RenderShape.MODEL)
        {
            return EMPTY;
        }

        final BlockAndTintGetter level = new ReplacementLevel(world, inWorld, pos, state, replacement.getBlockEntity(pos));
        final BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        final List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(level, pos, state, RandomSource.create(42L), parts);
        return new CapturedBlockGeometry(parts, tints(state, level, pos, inWorld).toIntArray());
    }

    private static IntList tints(final BlockState state, final BlockAndTintGetter level, final BlockPos pos, final boolean inWorld)
    {
        final IntList tints = new IntArrayList();
        final BlockColors colors = Minecraft.getInstance().getBlockColors();
        final List<BlockTintSource> sources = colors.getTintSources(state);
        if (sources.isEmpty())
        {
            IClientBlockExtensions.of(state).collectDynamicTintValues(state, level, pos, tints);
            return tints;
        }
        for (final BlockTintSource source : sources)
        {
            tints.add(source == null ? -1 : inWorld ? source.colorInWorld(state, level, pos) : source.color(state));
        }
        return tints;
    }

    /** The world as the replacement model sees it: the replacement and its block entity are in place at {@code pos}. */
    private record ReplacementLevel(@Nullable BlockAndTintGetter world, boolean inWorld, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity)
        implements BlockAndTintGetter
    {
        @Override
        public @Nullable BlockEntity getBlockEntity(final BlockPos at)
        {
            return pos.equals(at) ? blockEntity : world == null || !inWorld ? null : world.getBlockEntity(at);
        }

        @Override
        public BlockState getBlockState(final BlockPos at)
        {
            return pos.equals(at) ? state : world == null || !inWorld ? Blocks.AIR.defaultBlockState() : world.getBlockState(at);
        }

        @Override
        public FluidState getFluidState(final BlockPos at)
        {
            return getBlockState(at).getFluidState();
        }

        @Override
        public ModelData getModelData(final BlockPos at)
        {
            if (pos.equals(at))
            {
                return blockEntity == null ? ModelData.EMPTY : blockEntity.getModelData();
            }
            return world == null || !inWorld ? ModelData.EMPTY : world.getModelData(at);
        }

        @Override
        public int getHeight()
        {
            return world == null || !inWorld ? 1 : world.getHeight();
        }

        @Override
        public int getMinY()
        {
            return world == null || !inWorld ? pos.getY() : world.getMinY();
        }

        @Override
        public LevelLightEngine getLightEngine()
        {
            return world == null || !inWorld ? LevelLightEngine.EMPTY : world.getLightEngine();
        }

        @Override
        public CardinalLighting cardinalLighting()
        {
            return world == null || !inWorld ? CardinalLighting.DEFAULT : world.cardinalLighting();
        }

        @Override
        public int getBlockTint(final BlockPos at, final ColorResolver resolver)
        {
            return world == null ? -1 : world.getBlockTint(at, resolver);
        }
    }
}
