package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * FX1 #4: the unlit ghost path writes a POSITION_TEX_COLOR vertex per recorded vertex: position (moved by the pose),
 * uv0 and colour, and nothing else. No normal, overlay, light or bulk BLOCK-format call reaches the target, because
 * those are exactly what Iris's extended-vertex BufferBuilder turns into per-quad tangent and mid-block work.
 */
public class VertexRecorderUnlitTest
{
    private static final int FULL_LIGHT = 0xf000f0;

    @Test
    public void unlitReplayWritesOnlyPositionUvAndColour()
    {
        final VertexRecorder recorder = bakedQuads(100, FULL_LIGHT);
        final PoseStack pose = new PoseStack();
        pose.translate(-50.5f, 3f, 12.25f);

        final Logging target = new Logging();
        for (int frame = 0; frame < 10; frame++)
        {
            target.calls.clear();
            recorder.replayUnlit(target, pose.last());
        }

        assertEquals("bulk BLOCK-format vertices", 0, target.bulk);
        assertEquals("normals", 0, target.normals);
        assertEquals("overlay (uv1)", 0, target.uv1);
        assertEquals("light (uv2)", 0, target.uv2);
        assertEquals("matrix transforms on a translation-only pose", 0, target.matrixTransforms);
        assertEquals(3 * recorder.vertexCount(), target.calls.size());

        final List<String> raw = new ArrayList<>();
        recorder.replay(rawLogger(raw));
        // raw per vertex: pos, color, uv, uv1, uv2, normal (6 calls); unlit per vertex: pos, uv, color
        for (int v = 0; v < recorder.vertexCount(); v++)
        {
            final String[] p = raw.get(v * 6).split(" ");
            assertEquals("pos " + (Float.parseFloat(p[1]) - 50.5f) + " " + (Float.parseFloat(p[2]) + 3f) + " " + (Float.parseFloat(p[3]) + 12.25f),
                target.calls.get(v * 3));
            assertEquals(raw.get(v * 6 + 2), target.calls.get(v * 3 + 1));
            assertEquals("full light keeps the colour", raw.get(v * 6 + 1), target.calls.get(v * 3 + 2));
        }
    }

    @Test
    public void unlitReplayFoldsTheRecordedLightIntoTheColour()
    {
        final VertexRecorder recorder = new VertexRecorder();
        recorder.addVertex(0f, 0f, 0f, 0x80c86440, 0.5f, 0.5f, 0, 0, 0f, 1f, 0f);                 // block 0, sky 0
        recorder.addVertex(0f, 0f, 0f, 0x80c86440, 0.5f, 0.5f, 0, 15 << 4, 0f, 1f, 0f);           // block 15
        recorder.addVertex(0f, 0f, 0f, 0x80c86440, 0.5f, 0.5f, 0, 15 << 20, 0f, 1f, 0f);          // sky 15
        recorder.addVertex(0f, 0f, 0f, 0x80c86440, 0.5f, 0.5f, 0, 5 << 4 | 10 << 20, 0f, 1f, 0f); // block 5, sky 10

        final Logging target = new Logging();
        recorder.replayUnlit(target, new PoseStack().last());

        assertEquals("color " + Integer.toHexString(scaled(0x80c86440, 0.25f)), target.calls.get(2));
        assertEquals("color 80c86440", target.calls.get(5));
        assertEquals("color 80c86440", target.calls.get(8));
        assertEquals("color " + Integer.toHexString(scaled(0x80c86440, 0.25f + 0.75f * 10f / 15f)), target.calls.get(11));
    }

    @Test
    public void unlitReplayStillTransformsThroughARotatedPose()
    {
        final VertexRecorder recorder = bakedQuads(4, FULL_LIGHT);
        final PoseStack pose = new PoseStack();
        pose.translate(5f, 6f, 7f);
        pose.rotate(new Quaternionf().rotationY((float) Math.toRadians(33)));

        final Logging target = new Logging();
        recorder.replayUnlit(target, pose.last());

        final List<String> raw = new ArrayList<>();
        recorder.replay(rawLogger(raw));
        assertEquals(0, target.normals + target.uv1 + target.uv2 + target.bulk);
        for (int v = 0; v < recorder.vertexCount(); v++)
        {
            final String[] p = raw.get(v * 6).split(" ");
            final Vector3f expected = pose.last().pose()
                .transformPosition(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), new Vector3f());
            assertEquals("pos " + expected.x() + " " + expected.y() + " " + expected.z(), target.calls.get(v * 3));
        }
    }

    private static int scaled(final int argb, final float factor)
    {
        final int r = Math.round((argb >> 16 & 0xff) * factor);
        final int g = Math.round((argb >> 8 & 0xff) * factor);
        final int b = Math.round((argb & 0xff) * factor);
        return argb & 0xff000000 | r << 16 | g << 8 | b;
    }

    private static VertexRecorder bakedQuads(final int quads, final int light)
    {
        final VertexRecorder recorder = new VertexRecorder();
        for (int i = 0; i < quads * 4; i++)
        {
            recorder.addVertex(i * 0.37f + 0.01f, (i % 17) - 8.99f, -(i % 29) * 1.13f, 0x66000000 | (i * 2654435) & 0xffffff,
                (i % 16) / 16f, (i % 7) / 7f, 0xa << 16 | 3, light, 0f, 1f, 0f);
        }
        return recorder;
    }

    private static VertexConsumer rawLogger(final List<String> calls)
    {
        return new VertexConsumer()
        {
            @Override public VertexConsumer addVertex(float x, float y, float z) { calls.add("pos " + x + " " + y + " " + z); return this; }
            @Override public VertexConsumer setColor(int r, int g, int b, int a) { calls.add("rgba"); return this; }
            @Override public VertexConsumer setColor(int color) { calls.add("color " + Integer.toHexString(color)); return this; }
            @Override public VertexConsumer setUv(float u, float v) { calls.add("uv " + u + " " + v); return this; }
            @Override public VertexConsumer setUv1(int u, int v) { calls.add("uv1 " + u + " " + v); return this; }
            @Override public VertexConsumer setUv2(int u, int v) { calls.add("uv2 " + u + " " + v); return this; }
            @Override public VertexConsumer setUv3(float u, float v) { calls.add("uv3 " + u + " " + v); return this; }
            @Override public VertexConsumer setNormal(float x, float y, float z) { calls.add("normal " + x + " " + y + " " + z); return this; }
            @Override public VertexConsumer setLineWidth(float width) { calls.add("width " + width); return this; }
        };
    }

    /**
     * Logs the element calls that reach the target and counts the ones the unlit path must never make.
     */
    private static final class Logging implements VertexConsumer
    {
        final List<String> calls = new ArrayList<>();
        int bulk;
        int normals;
        int uv1;
        int uv2;
        int matrixTransforms;

        @Override
        public void addVertex(final float x, final float y, final float z, final int color, final float u, final float v,
            final int overlayCoords, final int lightCoords, final float nx, final float ny, final float nz)
        {
            bulk++;
        }

        @Override
        public VertexConsumer addVertex(final org.joml.Matrix4fc pose, final float x, final float y, final float z)
        {
            matrixTransforms++;
            return VertexConsumer.super.addVertex(pose, x, y, z);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { calls.add("pos " + x + " " + y + " " + z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { calls.add("rgba"); return this; }
        @Override public VertexConsumer setColor(int color) { calls.add("color " + Integer.toHexString(color)); return this; }
        @Override public VertexConsumer setUv(float u, float v) { calls.add("uv " + u + " " + v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { uv1++; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { uv2++; return this; }
        @Override public VertexConsumer setUv3(float u, float v) { calls.add("uv3"); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { normals++; return this; }
        @Override public VertexConsumer setLineWidth(float width) { calls.add("width"); return this; }
    }
}
