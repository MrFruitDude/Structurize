package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * BS-C2: the overlay buffer source must hand every written vertex on unchanged, instead of dropping it.
 */
public class VertexRecorderTest
{
    @Test
    public void replaysEveryVertexWithOnlyTheElementsThatWereSet()
    {
        final VertexRecorder recorder = new VertexRecorder();
        recorder.addVertex(1f, 2f, 3f).setColor(0x80ff0000).setNormal(0f, 1f, 0f).setLineWidth(2.5f);
        recorder.addVertex(-4f, 5.5f, 6f).setColor(10, 20, 30, 40).setUv(0.25f, 0.75f).setUv1(3, 4).setUv2(240, 15);

        final List<String> calls = new ArrayList<>();
        recorder.replay(logging(calls));

        assertEquals(List.of(
            "pos 1.0 2.0 3.0", "color 80ff0000", "normal 0.0 1.0 0.0", "width 2.5",
            "pos -4.0 5.5 6.0", "color 280a141e", "uv 0.25 0.75", "uv1 3 4", "uv2 240 15"), calls);
    }

    @Test
    public void growsPastInitialCapacity()
    {
        final VertexRecorder recorder = new VertexRecorder();
        for (int i = 0; i < 10_000; i++)
        {
            recorder.addVertex(i, 0, 0).setColor(i);
        }
        final List<String> calls = new ArrayList<>();
        recorder.replay(logging(calls));
        assertEquals(20_000, calls.size());
        assertEquals("pos 9999.0 0.0 0.0", calls.get(19_998));
        assertEquals("color " + Integer.toHexString(9999), calls.get(19_999));
        assertTrue(new VertexRecorder().isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsElementBeforeVertex()
    {
        new VertexRecorder().setColor(0xffffffff);
    }

    /**
     * BS-S1: the cached blueprint mesh is recorded once in blueprint-local space and replayed each frame
     * with that frame's camera-relative pose, so positions must be moved by the pose and normals rotated.
     */
    @Test
    public void replaysThroughPose()
    {
        final VertexRecorder recorder = new VertexRecorder();
        recorder.addVertex(1f, 2f, 3f).setColor(0xff00ff00).setUv(0.5f, 0.25f).setNormal(1f, 0f, 0f);

        final PoseStack poseStack = new PoseStack();
        poseStack.translate(10f, -20f, 30f);
        poseStack.rotate(new Quaternionf().rotationY((float) Math.toRadians(90)));

        final List<String> calls = new ArrayList<>();
        recorder.replay(logging(calls), poseStack.last());

        assertEquals(List.of("pos 13.0 -18.0 29.0", "color ff00ff00", "uv 0.5 0.25", "normal 0.0 0.0 -1.0"),
            calls.stream().map(VertexRecorderTest::round).toList());
    }

    private static String round(final String call)
    {
        final StringBuilder out = new StringBuilder();
        for (final String part : call.split(" "))
        {
            if (!out.isEmpty())
            {
                out.append(' ');
            }
            try
            {
                if (part.contains("."))
                {
                    final float value = Math.round(Float.parseFloat(part) * 1000f) / 1000f;
                    out.append(value == 0f ? 0.0f : value);
                    continue;
                }
            }
            catch (final NumberFormatException ignored)
            {
                // not a number
            }
            out.append(part);
        }
        return out.toString();
    }

    private static VertexConsumer logging(final List<String> calls)
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
}
