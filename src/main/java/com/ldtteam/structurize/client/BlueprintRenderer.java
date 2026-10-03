package com.ldtteam.structurize.client;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtils;
import com.ldtteam.structurize.client.compat.IrisPipelineCompat;
import com.ldtteam.structurize.client.fakelevel.BlueprintBlockAccess;
import com.ldtteam.structurize.client.rendertask.util.VertexRecorder;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.ldtteam.structurize.tag.ModTags;
import com.ldtteam.structurize.util.BlockInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Prepares and submits blueprint preview geometry through Minecraft's current level render pipeline.
 */
public class BlueprintRenderer implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintRenderer.class);
    public static final float TRANSPARENCY_THRESHOLD = 0.99F;
    private static boolean hasWarnedExceptions = false;

    private final BlueprintBlockAccess blockAccess;
    private final List<Entity> entities = new ArrayList<>();

    /**
     * @return the preview entities of this renderer (upstream #699 contents window)
     */
    List<Entity> getEntities()
    {
        return entities;
    }
    private final List<BlockEntity> tileEntities = new ArrayList<>();
    private final List<MovingBlockRenderState> blockStates = new ArrayList<>();
    private final List<FluidInstance> fluidInstances = new ArrayList<>();
    private final PreviewMesh<PreviewMeshKey, RenderType> mesh = new PreviewMesh<>();
    /** FX1 #15: extracted render states, reused within a game tick for static ones; off-screen ones are skipped. */
    private final PreviewRenderStateCache<BlockEntity, BlockEntityRenderState> blockEntityStates = new PreviewRenderStateCache<>();
    private final PreviewRenderStateCache<Entity, EntityRenderState> entityStates = new PreviewRenderStateCache<>();
    /** FX1 #5: exceptions from a prewarm's init, reported on this renderer's first draw. */
    private Map<Object, Exception> prewarmExceptions = Map.of();
    private long lastGameTime;
    private Set<Object> crashingObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    public static BlueprintRenderer buildRendererForBlueprint(final Blueprint blueprint)
    {
        return new BlueprintRenderer(new BlueprintBlockAccess(blueprint));
    }

    private BlueprintRenderer(final BlueprintBlockAccess blockAccess)
    {
        this.blockAccess = blockAccess;
    }

    public void updateBlueprint(final BlueprintPreviewData previewData)
    {
        if (blockAccess.getLevelSource() != previewData.getBlueprint()
            && blockAccess.getLevelSource().hashCode() == previewData.getBlueprint().hashCode())
        {
            blockAccess.setLevelSource(previewData.getBlueprint());
        }
    }

    private void init(final BlueprintPreviewData previewData, final Blueprint blueprint, final Map<Object, Exception> suppressedExceptions)
    {
        final Minecraft minecraft = Minecraft.getInstance();
        // Blueprint tile entities can contribute model data that is required by
        // their block models (for example, Domum Ornamentum and MineColonies
        // blocks). Keep that data keyed by the blueprint-local position just as
        // the legacy baked-model renderer did. Passing ModelData.EMPTY for all
        // blocks makes those models collect no render parts, which leaves only
        // the placement outline visible in the preview.
        final Map<BlockPos, ModelData> teModelData = new HashMap<>();

        clearCachedState();

        final Map<BlockPos, BlockEntity> tileEntitiesMap =
            BlueprintUtils.instantiateTileEntities(blueprint, blockAccess, teModelData);
        entities.addAll(BlueprintUtils.instantiateEntities(blueprint, blockAccess));

        blockAccess.setBlockEntities(tileEntitiesMap);
        blockAccess.setEntities(entities);
        blockAccess.setSolidSubstitutionOverride(previewData.getSolidSubstitutionOverride());
        blockAccess.setRenderBlocksNiceOverride(previewData.getRenderBlocksNice());

        for (final BlockInfo blockInfo : blueprint.getBlockInfoAsList())
        {
            final BlockPos blockPos = blockInfo.getPos();
            BlockState state = blockInfo.getState();

            try
            {
                if (previewData.getRenderBlocksNice() && state.getBlock() == ModBlocks.blockTagSubstitution.get())
                {
                    if (tileEntitiesMap.remove(blockPos) instanceof final BlockEntityTagSubstitution tagTE)
                    {
                        final BlockEntityTagSubstitution.ReplacementBlock replacement = tagTE.getReplacement();
                        state = replacement.getBlockState();

                        Optional.ofNullable(replacement.createBlockEntity(blockPos)).ifPresent(newBe -> {
                            newBe.setLevel(blockAccess);
                            teModelData.put(blockPos, newBe.getModelData());
                            tileEntitiesMap.put(blockPos, newBe);
                        });
                    }
                    else
                    {
                        state = Blocks.AIR.defaultBlockState();
                    }
                }
                else
                {
                    state = blockAccess.prepareBlockStateForRendering(state, blockPos);
                }

                if (state.isAir())
                {
                    continue;
                }

                final FluidState fluidState = state.getFluidState();
                if (!fluidState.isEmpty())
                {
                    fluidInstances.add(new FluidInstance(blockPos, state, fluidState));
                }

                if (state.getRenderShape() != RenderShape.INVISIBLE)
                {
                    blockStates.add(createMovingBlockState(
                        minecraft,
                        blockPos,
                        state,
                        teModelData.getOrDefault(blockPos, ModelData.EMPTY)));
                }
            }
            catch (final ReportedException exception)
            {
                suppressedExceptions.put(blockInfo, exception);
            }
        }

        blockAccess.setSolidSubstitutionOverride(null);
        blockAccess.setRenderBlocksNiceOverride(Structurize.getConfig().getClient().renderPlaceholdersNice.get());
        tileEntities.addAll(tileEntitiesMap.values());
    }

    private MovingBlockRenderState createMovingBlockState(
        final Minecraft minecraft,
        final BlockPos pos,
        final BlockState state,
        final ModelData modelData)
    {
        final MovingBlockRenderState renderState = new MovingBlockRenderState();
        renderState.randomSeedPos = pos;
        renderState.blockPos = pos;
        renderState.blockState = state;
        renderState.modelData = modelData;
        renderState.cardinalLighting = minecraft.level.cardinalLighting();
        renderState.lightEngine = blockAccess.getLightEngine();

        final ClientLevel realLevel = minecraft.level;
        if (realLevel != null)
        {
            renderState.biome = realLevel.getBiome(blockAccess.getWorldPos().offset(pos));
        }
        return renderState;
    }

    public void draw(final BlueprintPreviewData previewData, final BlockPos pos, final SubmitCustomGeometryEvent ctx)
    {
        if (crashingObjects == null)
        {
            return;
        }

        try
        {
            reportSuppressedExceptions(previewData, drawUnsafe(previewData, pos, ctx));
        }
        catch (final Exception exception)
        {
            final CrashReport crashReport = CrashReport.forThrowable(exception, "Rendering blueprint");
            final CrashReportCategory category = crashReport.addCategory("Blueprint:");
            previewData.getBlueprint().describeSelfInCrashReport(category);
            LOGGER.error(crashReport.getDetails());

            crashingObjects = null;
            final var player = Minecraft.getInstance().player;
            if (player != null)
            {
                player.sendSystemMessage(Component.translatable(
                    "structurize.preview_renderer.cannot_render", previewData.getBlueprint().getName()));
            }
        }
    }

    private void reportSuppressedExceptions(
        final BlueprintPreviewData previewData,
        final Map<Object, Exception> suppressedExceptions)
    {
        if (suppressedExceptions.isEmpty())
        {
            return;
        }

        if (!hasWarnedExceptions)
        {
            hasWarnedExceptions = true;
            final var player = Minecraft.getInstance().player;
            if (player != null)
            {
                player.sendSystemMessage(Component.translatable("structurize.preview_renderer.exception"));
            }
        }

        boolean crashReported = false;
        boolean isEmpty = true;
        for (final Map.Entry<Object, Exception> entry : suppressedExceptions.entrySet())
        {
            if (!crashingObjects.add(entry.getKey()))
            {
                continue;
            }
            isEmpty = false;

            if (entry.getValue() instanceof final ReportedException reportedException)
            {
                previewData.getBlueprint()
                    .describeSelfInCrashReport(reportedException.getReport().addCategory("Rendering blueprint"));
                LOGGER.error(reportedException.getReport().getDetails());
                crashReported = true;
            }
            else
            {
                LOGGER.error("", entry.getValue());
            }
        }

        if (!crashReported && !isEmpty)
        {
            final CrashReport crashReport = CrashReport.forThrowable(new Exception(), "Summary");
            previewData.getBlueprint().describeSelfInCrashReport(crashReport.addCategory("Rendering blueprint"));
            LOGGER.error(crashReport.getDetails());
        }
    }

    public Map<Object, Exception> drawUnsafe(
        final BlueprintPreviewData previewData,
        final BlockPos pos,
        final SubmitCustomGeometryEvent ctx)
    {
        updateBlueprint(previewData);
        final BlockPos anchorPos = pos.subtract(previewData.getBlueprint().getPrimaryBlockOffset());
        blockAccess.setWorldPos(anchorPos);

        if (blockStates.isEmpty() && fluidInstances.isEmpty() && entities.isEmpty() && tileEntities.isEmpty())
        {
            init(previewData, previewData.getBlueprint(), new IdentityHashMap<>());
        }

        final Map<Object, Exception> suppressedExceptions = new IdentityHashMap<>();
        if (!prewarmExceptions.isEmpty())
        {
            suppressedExceptions.putAll(prewarmExceptions);
            prewarmExceptions = Map.of();
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final long gameTime = minecraft.level.getGameTime();
        final float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        final Vec3 viewPosition = minecraft.gameRenderer.mainCamera().position();
        final Vec3 realRoot = Vec3.atLowerCornerOf(anchorPos).subtract(viewPosition);

        final PoseStack poseStack = ctx.getPoseStack();
        // FX1 #15: frustum-cull block entities and entities only when the caller's pose is a plain translation (always,
        // except while CC animates a glide or turn); the culling box is then the world box moved by that translation.
        final Frustum frustum;
        final Vec3 cullOffset;
        if (GhostSettings.extractCache() && MatrixUtil.isPureTranslation(poseStack.last().pose()))
        {
            frustum = minecraft.gameRenderer.mainCamera().getCullFrustum();
            final org.joml.Matrix4f callerPose = poseStack.last().pose();
            cullOffset = Vec3.atLowerCornerOf(anchorPos).add(callerPose.m30(), callerPose.m31(), callerPose.m32());
        }
        else
        {
            frustum = null;
            cullOffset = Vec3.ZERO;
        }
        poseStack.pushPose();
        poseStack.translate(realRoot.x(), realRoot.y(), realRoot.z());

        // This collector is owned by LevelRenderer and is consumed by the
        // current frame's feature dispatcher. A private SubmitNodeStorage is
        // never rendered and makes the preview silently disappear.
        final SubmitNodeCollector collector = ctx.getSubmitNodeCollector();
        submitMesh(minecraft, collector, poseStack, previewData);
        final CameraRenderState cameraState = cameraState(minecraft);
        submitEntities(minecraft, collector, poseStack, cameraState, gameTime, partialTicks, frustum, cullOffset, suppressedExceptions);
        submitBlockEntities(minecraft, collector, poseStack, cameraState, anchorPos, gameTime, partialTicks, frustum, cullOffset);

        poseStack.popPose();
        lastGameTime = gameTime;
        return suppressedExceptions;
    }

    /**
     * Submits the cached block and fluid mesh. Like the 1.21 renderer, which baked the blueprint into vertex
     * buffers once, the blueprint is tessellated only when its inputs change (see {@link PreviewMeshKey}); every frame
     * just replays the recorded vertices, one submission per render type.
     */
    private void submitMesh(
        final Minecraft minecraft,
        final SubmitNodeCollector collector,
        final PoseStack poseStack,
        final BlueprintPreviewData previewData)
    {
        final PreviewMeshKey key = meshKey(minecraft, previewData);

        // PF1: replay goes through VertexRecorder's translation-only fast path whenever the pose is the plain
        // camera-relative offset (every frame unless CC is animating the ghost), so a frame costs one bulk vertex
        // write per vertex and no per-vertex matrix/normal transform.
        // FX1 #4: under a shader pack (mode AUTO) or with mode UNLIT the same recorded mesh is replayed unlit through
        // Structurize's POSITION_TEX_COLOR ghost types, which Iris does not widen into its extended vertex format.
        final boolean unlit =
            GhostRenderMode.effective(GhostSettings.mode(), IrisPipelineCompat.isShaderPackInUse()) == GhostRenderMode.UNLIT;
        for (final Map.Entry<RenderType, VertexRecorder> entry : mesh.get(key, layers -> buildMesh(
            minecraft.getBlockColors(), blockStates, fluidInstances, new BlueprintBlockTintGetter(), key, layers)).entrySet())
        {
            final VertexRecorder recorder = entry.getValue();
            if (unlit)
            {
                collector.submitCustomGeometry(poseStack, unlitRenderType(entry.getKey()), (pose, buffer) -> recorder.replayUnlit(buffer, pose));
            }
            else
            {
                collector.submitCustomGeometry(poseStack, entry.getKey(), (pose, buffer) -> recorder.replay(buffer, pose));
            }
        }
    }

    private PreviewMeshKey meshKey(final Minecraft minecraft, final BlueprintPreviewData previewData)
    {
        return new PreviewMeshKey(
            previewAlpha(previewData),
            minecraft.options.ambientOcclusion().get(),
            minecraft.options.cutoutLeaves().get(),
            minecraft.getModelManager().getBlockStateModelSet(),
            minecraft.getModelManager().getFluidStateModelSet());
    }

    /**
     * FX1 #4: the unlit ghost type for a moving-block layer: translucent stays blended (and sorted), solid and cutout
     * draw opaque with the shader's zero-alpha discard.
     */
    private static RenderType unlitRenderType(final RenderType movingType)
    {
        return movingType == RenderTypes.translucentMovingBlock()
            ? com.ldtteam.structurize.client.rendertask.util.RenderTypes.GHOST_UNLIT_TRANSLUCENT
            : com.ldtteam.structurize.client.rendertask.util.RenderTypes.GHOST_UNLIT_OPAQUE;
    }

    /**
     * @return whether this renderer's mesh is tessellated and installed (a prewarm still running does not count)
     */
    boolean isMeshReady()
    {
        return mesh.isBuilt();
    }

    /**
     * FX1 #5: prepares this renderer ahead of its first draw: instantiates the blueprint's blocks, block entities and
     * entities here on the render thread, then tessellates the mesh on {@code executor}. The worker only reads
     * snapshots taken here (block render states, fluid neighbourhood, biomes, block colours), never GL or the live
     * level; the mesh is installed by the first {@link #draw} that finds it finished.
     *
     * @param previewData the preview this renderer will draw
     * @param pos         where the preview is drawn now (for the biome tints)
     * @param executor    the worker executor
     */
    void prewarm(final BlueprintPreviewData previewData, final BlockPos pos, final Executor executor)
    {
        final Minecraft minecraft = Minecraft.getInstance();
        final BlockPos anchorPos = pos.subtract(blockAccess.getLevelSource().getPrimaryBlockOffset());
        blockAccess.setWorldPos(anchorPos);
        final Map<Object, Exception> suppressed = new IdentityHashMap<>();
        // the renderer's own (rotated) copy, not the preview's blueprint, which is still in the current rotation
        init(previewData, blockAccess.getLevelSource(), suppressed);
        prewarmExceptions = suppressed;

        final PreviewMeshKey key = meshKey(minecraft, previewData);
        final BlockColors blockColors = minecraft.getBlockColors();
        final List<MovingBlockRenderState> states = List.copyOf(blockStates);
        final List<FluidInstance> fluids = List.copyOf(fluidInstances);
        final BlockAndTintGetter fluidLevel = fluids.isEmpty() ? null : new FluidSnapshot(fluids, anchorPos, minecraft.level);
        mesh.buildAsync(key, layers -> buildMesh(blockColors, states, fluids, fluidLevel, key, layers), executor);
    }

    private static void buildMesh(
        final BlockColors blockColors,
        final List<MovingBlockRenderState> blockStates,
        final List<FluidInstance> fluidInstances,
        final BlockAndTintGetter fluidLevel,
        final PreviewMeshKey key,
        final Map<RenderType, VertexRecorder> mesh)
    {
        final boolean blendPreview = key.alpha() >= 0.0F && key.alpha() < TRANSPARENCY_THRESHOLD;
        final ModelBlockRenderer blockRenderer = new ModelBlockRenderer(key.ambientOcclusion(), false, blockColors);
        final PoseStack poseStack = new PoseStack();

        for (final MovingBlockRenderState state : blockStates)
        {
            // Same layer choice as vanilla MovingBlockFeatureRenderer (what submitMovingBlock used), or one
            // translucent layer with the preview alpha when the preview is transparent.
            final boolean forceOpaque = ModelBlockRenderer.forceOpaque(key.cutoutLeaves(), state.blockState);
            final BlockPos blockPos = state.blockPos;
            final BlockStateModel model = key.blockModels().get(state.blockState);
            blockRenderer.tesselateBlock(
                (x, y, z, quad, instance) -> {
                    final ChunkSectionLayer layer;
                    if (blendPreview)
                    {
                        instance.multiplyColor(ARGB.color(key.alpha(), -1));
                        layer = ChunkSectionLayer.TRANSLUCENT;
                    }
                    else
                    {
                        layer = forceOpaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
                    }
                    poseStack.pushPose();
                    // Blueprint-local position, plus the small legacy offset that avoids z-fighting with the
                    // terrain when a preview is placed directly on existing blocks.
                    poseStack.translate(blockPos.getX() + 0.01F + x, blockPos.getY() + 0.01F + y, blockPos.getZ() + 0.01F + z);
                    mesh.computeIfAbsent(movingRenderType(layer), type -> new VertexRecorder())
                        .putBakedQuad(poseStack.last(), quad, instance);
                    poseStack.popPose();
                },
                0.0F,
                0.0F,
                0.0F,
                state,
                blockPos,
                state.blockState,
                model,
                state.blockState.getSeed(state.randomSeedPos));
        }

        if (fluidInstances.isEmpty())
        {
            return;
        }
        final FluidRenderer fluidRenderer = new FluidRenderer(key.fluidModels());
        for (final FluidInstance instance : fluidInstances)
        {
            final ChunkSectionLayer sectionLayer = key.fluidModels().get(instance.fluidState()).layer();
            final VertexRecorder recorder = mesh.computeIfAbsent(movingRenderType(sectionLayer), type -> new VertexRecorder());
            // FluidRenderer emits section-local coordinates (the same contract used by the old chunk-buffer
            // wrapper). Translate by the section origin so fluids keep their blueprint-local position.
            final BlockPos fluidPos = instance.pos();
            poseStack.pushPose();
            poseStack.translate(
                fluidPos.getX() - (fluidPos.getX() & 15),
                fluidPos.getY() - (fluidPos.getY() & 15),
                fluidPos.getZ() - (fluidPos.getZ() & 15));
            final PoseVertexConsumer consumer = new PoseVertexConsumer(poseStack.last(), recorder);
            fluidRenderer.tesselate(
                fluidLevel,
                fluidPos,
                layer -> layer == sectionLayer ? consumer : null,
                instance.state(),
                instance.fluidState());
            poseStack.popPose();
        }
    }

    private float previewAlpha(final BlueprintPreviewData previewData)
    {
        final float override = previewData.getOverridePreviewTransparency();
        if (override >= 0.0F)
        {
            return override;
        }
        return Structurize.getConfig().getClient().rendererTransparency.get().floatValue();
    }

    private static RenderType movingRenderType(final ChunkSectionLayer layer)
    {
        return switch (layer)
        {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
        };
    }

    private void submitEntities(
        final Minecraft minecraft,
        final SubmitNodeCollector collector,
        final PoseStack poseStack,
        final CameraRenderState cameraState,
        final long gameTime,
        final float partialTicks,
        final Frustum frustum,
        final Vec3 cullOffset,
        final Map<Object, Exception> suppressedExceptions)
    {
        final EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        final boolean cache = frustum != null;
        for (final Entity entity : entities)
        {
            final boolean ticking = entity.getType().builtInRegistryHolder().is(ModTags.PREVIEW_TICKING_ENTITIES);
            if (gameTime != lastGameTime && ticking)
            {
                try
                {
                    entity.tick();
                }
                catch (final Exception exception)
                {
                    suppressedExceptions.put(entity, exception);
                }
            }

            try
            {
                final boolean visible = !cache || frustum.isVisible(entity.getBoundingBoxForCulling().move(cullOffset));
                final EntityRenderState state =
                    entityStates.state(entity, visible, gameTime, !cache || ticking, e -> dispatcher.extractEntity(e, partialTicks));
                if (state == null)
                {
                    continue;
                }
                dispatcher.submit(state, cameraState, entity.getX(), entity.getY(), entity.getZ(), poseStack, collector);
            }
            catch (final ClassCastException | ReportedException exception)
            {
                suppressedExceptions.put(entity, exception);
            }
        }
    }

    private void submitBlockEntities(
        final Minecraft minecraft,
        final SubmitNodeCollector collector,
        final PoseStack poseStack,
        final CameraRenderState cameraState,
        final BlockPos anchorPos,
        final long gameTime,
        final float partialTicks,
        final Frustum frustum,
        final Vec3 cullOffset)
    {
        final BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        dispatcher.prepare(Vec3.ZERO);
        final boolean cache = frustum != null;
        for (final BlockEntity tileEntity : tileEntities)
        {
            tickPreviewBlockEntity(minecraft, anchorPos, tileEntity, gameTime);
            final boolean visible = !cache || isVisible(dispatcher, tileEntity, frustum, cullOffset);
            final BlockEntityRenderState state = blockEntityStates.state(tileEntity, visible, gameTime, !cache || isAnimated(tileEntity),
                be -> dispatcher.tryExtractRenderState(be, partialTicks, null, false));
            if (state == null)
            {
                continue;
            }

            final BlockPos tePos = tileEntity.getBlockPos();
            poseStack.pushPose();
            poseStack.translate(tePos.getX(), tePos.getY(), tePos.getZ());
            dispatcher.submit(state, poseStack, collector, cameraState);
            poseStack.popPose();
        }
    }

    private CameraRenderState cameraState(final Minecraft minecraft)
    {
        final Camera camera = minecraft.gameRenderer.mainCamera();
        final CameraRenderState state = new CameraRenderState();
        state.initialized = true;
        state.pos = camera.position();
        state.blockPos = camera.blockPosition();
        state.xRot = camera.xRot();
        state.yRot = camera.yRot();
        state.orientation.set(camera.rotation());
        return state;
    }

    private void tickPreviewBlockEntity(
        final Minecraft minecraft,
        final BlockPos anchorPos,
        final BlockEntity tileEntity,
        final long gameTime)
    {
        if (gameTime == lastGameTime)
        {
            return;
        }

        final BlockPos tePos = tileEntity.getBlockPos();
        final BlockState blockState = blockAccess.getBlockState(tePos);
        if (tileEntity instanceof final SpawnerBlockEntity spawner)
        {
            SpawnerBlockEntity.clientTick(minecraft.level, anchorPos.offset(tePos), blockState, spawner);
        }
        else if (tileEntity instanceof final EnchantingTableBlockEntity enchantingTable)
        {
            EnchantingTableBlockEntity.bookAnimationTick(minecraft.level, anchorPos.offset(tePos), blockState, enchantingTable);
        }
        else if (tileEntity instanceof final CampfireBlockEntity campfire
            && blockState.getBlock() instanceof CampfireBlock
            && blockState.getValue(CampfireBlock.LIT))
        {
            CampfireBlockEntity.particleTick(minecraft.level, anchorPos.offset(tePos), blockState, campfire);
        }
        else if (tileEntity instanceof final SkullBlockEntity skull
            && blockState.getBlock() instanceof SkullBlock
            && (blockState.is(Blocks.DRAGON_HEAD) || blockState.is(Blocks.DRAGON_WALL_HEAD)))
        {
            SkullBlockEntity.animation(blockAccess, tePos, blockState, skull);
        }
        else if (tileEntity instanceof final BeaconBlockEntity beacon)
        {
            BeaconBlockEntity.tick(blockAccess, tePos, blockState, beacon);
        }
    }

    private void clearCachedState()
    {
        entities.clear();
        tileEntities.clear();
        blockStates.clear();
        fluidInstances.clear();
        mesh.invalidate();
        blockEntityStates.clear();
        entityStates.clear();
    }

    /**
     * FX1 #15: whether a preview block entity's render box (blueprint-local, moved to where the ghost is drawn) is in
     * the camera's view frustum. Unknown renderers count as visible; tryExtractRenderState rejects those itself.
     */
    private static boolean isVisible(
        final BlockEntityRenderDispatcher dispatcher,
        final BlockEntity blockEntity,
        final Frustum frustum,
        final Vec3 cullOffset)
    {
        final BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = dispatcher.getRenderer(blockEntity);
        return renderer == null || frustum.isVisible(renderer.getRenderBoundingBox(blockEntity).move(cullOffset));
    }

    /**
     * FX1 #15: block entities whose render state changes within a game tick (they interpolate with the partial tick or
     * the game time), so they keep being extracted every frame: the ones the preview ticks and vanilla's animated ones.
     * Everything else (chests, signs, beds, pots, shelves, most modded ones) is extracted once per game tick.
     */
    private static boolean isAnimated(final BlockEntity blockEntity)
    {
        return blockEntity instanceof SpawnerBlockEntity
            || blockEntity instanceof EnchantingTableBlockEntity
            || blockEntity instanceof CampfireBlockEntity
            || blockEntity instanceof SkullBlockEntity
            || blockEntity instanceof BeaconBlockEntity
            || blockEntity instanceof BannerBlockEntity
            || blockEntity instanceof ConduitBlockEntity
            || blockEntity instanceof BellBlockEntity
            || blockEntity instanceof TheEndGatewayBlockEntity
            || blockEntity instanceof TrialSpawnerBlockEntity
            || blockEntity instanceof VaultBlockEntity;
    }

    @Override
    public void close()
    {
        clearCachedState();
    }

    private record FluidInstance(BlockPos pos, BlockState state, FluidState fluidState)
    {
    }

    private record PoseVertexConsumer(PoseStack.Pose pose, VertexConsumer delegate) implements VertexConsumer
    {
        @Override
        public VertexConsumer addVertex(final float x, final float y, final float z)
        {
            delegate.addVertex(pose.pose(), x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha)
        {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(final int color)
        {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(final float u, final float v)
        {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(final int u, final int v)
        {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(final int u, final int v)
        {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(final float u, final float v)
        {
            delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(final float x, final float y, final float z)
        {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(final float width)
        {
            delegate.setLineWidth(width);
            return this;
        }
    }

    private final class BlueprintBlockTintGetter implements BlockAndTintGetter
    {
        @Override
        public net.minecraft.world.level.CardinalLighting cardinalLighting()
        {
            return Minecraft.getInstance().level.cardinalLighting();
        }

        @Override
        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine()
        {
            return blockAccess.getLightEngine();
        }

        @Override
        public int getBlockTint(final BlockPos pos, final ColorResolver color)
        {
            final ClientLevel level = Minecraft.getInstance().level;
            return level == null ? -1 : color.getColor(
                level.getBiome(blockAccess.getWorldPos().offset(pos)).value(), pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(final BlockPos pos)
        {
            return blockAccess.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(final BlockPos pos)
        {
            return blockAccess.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(final BlockPos pos)
        {
            return blockAccess.getFluidState(pos);
        }

        @Override
        public int getHeight()
        {
            return blockAccess.getHeight();
        }

        @Override
        public int getMinY()
        {
            return blockAccess.getMinY();
        }

        @Override
        public ModelData getModelData(final BlockPos pos)
        {
            return ModelData.EMPTY;
        }
    }

    /**
     * FX1 #5: what fluid tessellation reads, captured on the render thread for a worker-thread build: the block and
     * fluid states (as the preview level answers them, substitutions included) and biomes of every fluid block and
     * its 26 neighbours, which is everything FluidRenderer asks for. The light engine is the preview's static-level
     * one (prewarming is off for light level -1, see {@link GhostSettings#prewarm()}).
     */
    private final class FluidSnapshot implements BlockAndTintGetter
    {
        private final Map<BlockPos, BlockState> states = new HashMap<>();
        private final Map<BlockPos, FluidState> fluidStates = new HashMap<>();
        private final Map<BlockPos, Holder<Biome>> biomes = new HashMap<>();
        private final net.minecraft.world.level.CardinalLighting cardinalLighting;
        private final net.minecraft.world.level.lighting.LevelLightEngine lightEngine;
        private final Holder<Biome> anchorBiome;
        private final int height;
        private final int minY;

        private FluidSnapshot(final List<FluidInstance> fluids, final BlockPos anchorPos, final ClientLevel level)
        {
            for (final FluidInstance fluid : fluids)
            {
                for (final BlockPos pos : BlockPos.betweenClosed(fluid.pos().offset(-1, -1, -1), fluid.pos().offset(1, 1, 1)))
                {
                    final BlockPos key = pos.immutable();
                    if (!states.containsKey(key))
                    {
                        states.put(key, blockAccess.getBlockState(key));
                        fluidStates.put(key, blockAccess.getFluidState(key));
                        if (level != null)
                        {
                            biomes.put(key, level.getBiome(anchorPos.offset(key)));
                        }
                    }
                }
            }
            this.cardinalLighting = level == null ? net.minecraft.world.level.CardinalLighting.DEFAULT : level.cardinalLighting();
            this.lightEngine = blockAccess.getLightEngine();
            this.anchorBiome = level == null ? null : level.getBiome(anchorPos);
            this.height = blockAccess.getHeight();
            this.minY = blockAccess.getMinY();
        }

        @Override
        public net.minecraft.world.level.CardinalLighting cardinalLighting()
        {
            return cardinalLighting;
        }

        @Override
        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine()
        {
            return lightEngine;
        }

        @Override
        public int getBlockTint(final BlockPos pos, final ColorResolver color)
        {
            final Holder<Biome> biome = biomes.getOrDefault(pos, anchorBiome);
            return biome == null ? -1 : color.getColor(biome.value(), pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(final BlockPos pos)
        {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos)
        {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(final BlockPos pos)
        {
            final FluidState state = fluidStates.get(pos);
            return state != null ? state : getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight()
        {
            return height;
        }

        @Override
        public int getMinY()
        {
            return minY;
        }

        @Override
        public ModelData getModelData(final BlockPos pos)
        {
            return ModelData.EMPTY;
        }
    }
}
