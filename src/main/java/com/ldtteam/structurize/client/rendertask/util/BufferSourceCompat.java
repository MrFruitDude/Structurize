package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stand-in for the removed MultiBufferSource.BufferSource. Since 26.x world geometry can only be
 * drawn by submitting it while the frame is collected ({@code SubmitCustomGeometryEvent}), so this
 * records the vertices written per render type on the heap and {@link #endBatch} hands each batch
 * to the collector, which replays it into the real buffer when the frame is drawn.
 */
public final class BufferSourceCompat
{
    private static final PoseStack IDENTITY = new PoseStack();

    private final Map<RenderType, VertexRecorder> batches = new LinkedHashMap<>();

    public VertexConsumer getBuffer(final RenderType renderType)
    {
        return batches.computeIfAbsent(renderType, type -> new VertexRecorder());
    }

    /**
     * Submits everything recorded since the last call and starts a fresh batch. Recorded positions are
     * already transformed by the caller's pose, so they are submitted with an identity pose.
     *
     * @param collector the collector of the frame being built
     */
    public void endBatch(final SubmitNodeCollector collector)
    {
        batches.forEach((type, recorder) -> {
            if (!recorder.isEmpty())
            {
                collector.submitCustomGeometry(IDENTITY, type, (pose, buffer) -> recorder.replay(buffer));
            }
        });
        // The collector replays after the submit event returns, so each recorder now belongs to it.
        batches.clear();
    }
}
