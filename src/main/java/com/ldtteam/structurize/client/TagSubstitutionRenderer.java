package com.ldtteam.structurize.client;

import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import net.minecraft.world.phys.Vec3;

public class TagSubstitutionRenderer implements BlockEntityRenderer<BlockEntityTagSubstitution, TagSubstitutionRenderer.State>
{
    private static TagSubstitutionRenderer instance;

    public static TagSubstitutionRenderer getInstance()
    {
        return instance;
    }

    private final BlockEntityRendererProvider.Context context;

    public TagSubstitutionRenderer(@NotNull final BlockEntityRendererProvider.Context context)
    {
        instance = this;
        this.context = context;
    }

    @Override
    public State createRenderState()
    {
        return new State();
    }

    @Override
    public void extractRenderState(@NotNull final BlockEntityTagSubstitution entity,
        @NotNull final State state,
        final float partialTick,
        @NotNull final Vec3 cameraPosition,
        final ModelFeatureRenderer.CrumblingOverlay breakProgress)
    {
        BlockEntityRenderState.extractBase(entity, state, breakProgress);
        state.partialTick = partialTick;
        state.replacement = entity.getReplacement();
        state.tilePos = entity.getTilePos();
        // 1.21 drew the replacement's block model (with its block entity's model data) AND its block entity renderer.
        state.geometry = state.replacement == null || state.tilePos == null
            ? CapturedBlockGeometry.EMPTY
            : CapturedBlockGeometry.collect(state.replacement,
                entity.getLevel() instanceof net.minecraft.client.renderer.block.BlockAndTintGetter world ? world : null,
                state.tilePos,
                true);
    }

    @Override
    public void submit(@NotNull final State state,
        @NotNull final PoseStack poseStack,
        @NotNull final SubmitNodeCollector collector,
        @NotNull final CameraRenderState camera)
    {
        if (state.replacement == null || state.tilePos == null || state.replacement.isEmpty())
        {
            return;
        }

        poseStack.pushPose();
        poseStack.scale(0.98F, 0.98F, 0.98F);
        poseStack.translate(0.01F, 0.01F, 0.01F);

        if (!state.geometry.parts().isEmpty())
        {
            collector.submitBlockModel(poseStack,
                RenderTypes.translucentMovingBlock(),
                state.geometry.parts(),
                state.geometry.tints(),
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                0);
        }

        final BlockEntity replacementEntity = state.replacement.getBlockEntity(state.tilePos);
        if (replacementEntity != null)
        {
            final BlockEntityRenderState nestedState = context.blockEntityRenderDispatcher()
                .tryExtractRenderState(replacementEntity, state.partialTick, null, false);
            if (nestedState != null)
            {
                context.blockEntityRenderDispatcher().submit(nestedState, poseStack, collector, camera);
            }
        }

        poseStack.popPose();
    }

    public static class State extends BlockEntityRenderState
    {
        private float partialTick;
        private BlockEntityTagSubstitution.ReplacementBlock replacement;
        private BlockPos tilePos;
        private CapturedBlockGeometry geometry = CapturedBlockGeometry.EMPTY;
    }
}
