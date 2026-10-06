package com.ldtteam.structurize.client;

import com.google.common.cache.LoadingCache;
import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.ldtteam.structurize.util.RotationMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.List;

/**
 * The Blueprint render handler on the client side.
 */
public final class BlueprintHandler
{
    /**
     * A static instance on the client.
     */
    private static final BlueprintHandler ourInstance = new BlueprintHandler();
    /**
     * How long are cache entries valid
     */
    public static final int CACHE_EXPIRE_SECONDS = 45;
    /**
     * How often should cache cleanup happen
     */
    public static final int CACHE_EXPIRE_CHECK_SECONDS = CACHE_EXPIRE_SECONDS / 3;

    private final LoadingCache<RenderingCacheKey, BlueprintRenderer> rendererCache =
        PreviewRendererCache.create(key -> BlueprintRenderer.buildRendererForBlueprint(key.blueprint()));

    /**
     * FX1 #5: which neighbouring rotation of which preview to prepare ahead of time.
     */
    private final PreviewPrewarmer<BlueprintPreviewData> prewarmer = new PreviewPrewarmer<>();

    /**
     * Private constructor to hide public one.
     */
    private BlueprintHandler()
    {
        /*
         * Intentionally left empty.
         */
    }

    /**
     * Get the static instance.
     *
     * @return a static instance of this class.
     */
    public static BlueprintHandler getInstance()
    {
        return ourInstance;
    }

    /**
     * Draw a blueprint at given pos.
     *
     * @param previewData the blueprint and context to draw.
     * @param pos         position to render at
     * @param ctx         rendering event
     */
    public void draw(final BlueprintPreviewData previewData, final BlockPos pos, final SubmitCustomGeometryEvent ctx)
    {
        internalBackportDraw(previewData, pos, ctx);
    }

    /**
     * DO NOT USE IN MCOL
     */
    public void internalBackportDraw(final BlueprintPreviewData previewData, final BlockPos pos, final SubmitCustomGeometryEvent ctx)
    {
        if (previewData == null || previewData.getBlueprint() == null)
        {
            Log.getLogger().warn("Trying to draw null blueprint!");
            return;
        }
        Profiler.get().push("struct_render_cache");
        
        final RenderingCacheKey key = previewData.getRenderKey();
        final BlueprintRenderer renderer = rendererCache.getUnchecked(key);
        renderer.draw(previewData, pos, ctx);
        prewarmNeighbours(previewData, key, renderer, pos);

        Profiler.get().pop();
    }

    /**
     * FX1 #5: asks for the clockwise and counter-clockwise rotation of this preview to be prepared ahead of time, so
     * rotating it does not tessellate the whole blueprint on the render thread. Callers do this for the preview the
     * player is about to rotate (Colony Command's armed tile); a preview the player rotates is armed automatically.
     *
     * @param previewData the preview
     */
    public void prewarmRotations(final BlueprintPreviewData previewData)
    {
        if (previewData != null)
        {
            prewarmer.arm(previewData);
        }
    }

    /**
     * FX1 #5: prepares at most one neighbouring rotation per frame of a preview being rotated: copies and rotates its
     * blueprint and instantiates it here, then tessellates the mesh on a worker thread.
     */
    private void prewarmNeighbours(final BlueprintPreviewData previewData, final RenderingCacheKey key, final BlueprintRenderer renderer, final BlockPos pos)
    {
        if (key == null || !GhostSettings.prewarm())
        {
            return;
        }
        final RotationMirror next = prewarmer.next(previewData, key.rotationMirror(), renderer.isMeshReady(),
            rotationMirror -> rendererCache.asMap().containsKey(new RenderingCacheKey(rotationMirror, key.blueprint())));
        if (next == null)
        {
            return;
        }

        Profiler.get().push("struct_render_prewarm");
        try
        {
            final Blueprint copy = key.blueprint().copy();
            copy.setRotationMirror(next, ClientLevelAccess.level());
            final BlueprintRenderer warm = BlueprintRenderer.buildRendererForBlueprint(copy);
            warm.prewarm(previewData, pos, Util.backgroundExecutor());
            rendererCache.put(new RenderingCacheKey(next, copy), warm);
        }
        catch (final RuntimeException e)
        {
            // never break the visible ghost over a prepare; the rotation then just builds on first draw as before
            Log.getLogger().warn("Could not prepare rotation {} of blueprint {}", next, key.blueprint().getName(), e);
            rendererCache.put(new RenderingCacheKey(next, key.blueprint()), BlueprintRenderer.buildRendererForBlueprint(key.blueprint()));
        }
        finally
        {
            Profiler.get().pop();
        }
    }

    /**
     * Cleans entries that are older than CACHE_EVICT_TIME.
     */
    public void cleanCache()
    {
        rendererCache.cleanUp();
    }

    /**
     * Clear all entries.
     */
    public void clearCache()
    {
        rendererCache.invalidateAll();
    }

    /**
     * @return entities of the already built renderer for this preview (may become invalid), else an empty list
     */
    public java.util.List<net.minecraft.world.entity.Entity> getOptionalEntitiesForBlueprint(final BlueprintPreviewData previewData)
    {
        final BlueprintRenderer renderer = rendererCache.getIfPresent(previewData.getRenderKey());
        return renderer == null ? java.util.List.of() : java.util.List.copyOf(renderer.getEntities());
    }

    /**
     * Draw a blueprint at list of given pos.
     *
     * @param previewData the blueprint and context to draw.
     * @param points      list of positions to render at
     * @param ctx         rendering event
     */
    public void drawAtListOfPositions(final BlueprintPreviewData previewData,
        final List<BlockPos> points,
        final SubmitCustomGeometryEvent ctx)
    {
        if (points.isEmpty() || previewData == null || previewData.getBlueprint() == null)
        {
            return;
        }

        Profiler.get().push("struct_render_multi");

        final BlueprintRenderer renderer = rendererCache.getUnchecked(previewData.getRenderKey());

        for (final BlockPos coord : points)
        {
            renderer.draw(previewData, coord, ctx);
        }

        Profiler.get().pop();
    }
}
