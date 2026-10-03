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

    /**
     * PF1: the ghost's per-frame pose is a pure camera-relative translation, so replaying the cached mesh must not
     * run the full matrix transform and normal transform for every vertex of every frame. With a translation-only
     * pose each baked-quad vertex goes out as ONE bulk vertex call (BufferBuilder's direct BLOCK-format write) with
     * the translation added, and no per-vertex matrix or normal transform runs.
     */
    @Test
    public void translationOnlyPoseReplaysWithoutPerVertexTransforms()
    {
        final VertexRecorder recorder = bakedQuads(250);
        final PoseStack poseStack = new PoseStack();
        poseStack.translate(-123.25f, 4.5f, 77.125f);

        final int frames = 20;
        final Counting counting = new Counting();
        for (int frame = 0; frame < frames; frame++)
        {
            recorder.replay(counting, poseStack.last());
        }

        assertEquals("matrix transforms", 0, counting.matrixTransforms);
        assertEquals("normal transforms", 0, counting.normalTransforms);
        assertEquals("bulk vertices", frames * recorder.vertexCount(), counting.bulkVertices);
    }

    /**
     * PF1: the fast path must write exactly what the full-transform path writes, element by element, for every pose
     * the ghost sees: a pure translation (the normal case), a translation after an exact zero-angle rotation and unit
     * scale (CC's resting ghost motion), and real rotations/scales (CC's glide/turn), which must still transform.
     */
    @Test
    public void fastPathOutputMatchesFullTransformPath()
    {
        final VertexRecorder recorder = bakedQuads(64);
        // plus vertices that do not carry the full baked-quad element set (line width, missing normal)
        recorder.addVertex(1f, 2f, 3f).setColor(0x80ff0000).setNormal(0f, 1f, 0f).setLineWidth(2.5f);
        recorder.addVertex(-4f, 5.5f, 6f).setColor(10, 20, 30, 40).setUv(0.25f, 0.75f).setUv1(3, 4).setUv2(240, 15);
        // a full baked-quad vertex whose normal is not unit length, so renormalisation (untrusted normals) shows
        recorder.addVertex(0.5f, 0.5f, 0.5f, 0xffffffff, 0.1f, 0.2f, 0, 0xf000f0, 0.3f, 0.5f, 0.1f);

        final List<PoseStack> poses = new ArrayList<>();
        final PoseStack translated = new PoseStack();
        translated.translate(-1000.3f, 63.7f, 2048.9f);
        poses.add(translated);

        final PoseStack resting = new PoseStack();
        resting.translate(12.5f, -3f, 0.25f);
        resting.rotate(new Quaternionf().rotationY(0f));
        resting.scale(1f, 1f, 1f);
        poses.add(resting);

        final PoseStack turned = new PoseStack();
        turned.translate(5f, 6f, 7f);
        turned.rotate(new Quaternionf().rotationY((float) Math.toRadians(33)));
        poses.add(turned);

        final PoseStack scaled = new PoseStack();
        scaled.translate(5f, 6f, 7f);
        scaled.scale(1.1f, 0.9f, 1f);
        poses.add(scaled);

        // translation-only matrix but untrusted normals: the full path renormalises, so the output must still match
        final PoseStack untrusted = new PoseStack();
        untrusted.scale(1f, 2f, 1f);
        untrusted.scale(1f, 0.5f, 1f);
        untrusted.translate(3f, 2f, 1f);
        poses.add(untrusted);

        for (final PoseStack pose : poses)
        {
            final List<String> expected = new ArrayList<>();
            fullTransformReplay(recorder, logging(expected), pose.last());
            final List<String> actual = new ArrayList<>();
            recorder.replay(elementwise(logging(actual)), pose.last());
            // exact float text per element; only the sign of a zero is not compared (-0 and +0 rasterise and
            // pack into normal bytes identically)
            assertEquals(unsignZeros(expected), unsignZeros(actual));
        }
    }

    private static List<String> unsignZeros(final List<String> calls)
    {
        return calls.stream().map(call -> (call + " ").replace(" -0.0 ", " 0.0 ").replace(" -0.0 ", " 0.0 ").trim()).toList();
    }

    /**
     * The pre-PF1 replay, kept verbatim as the reference: every vertex through the pose's matrix and normal transform.
     */
    private static void fullTransformReplay(final VertexRecorder recorder, final VertexConsumer target, final PoseStack.Pose pose)
    {
        // decode the recording through the pose-free replay, which writes each vertex untouched
        final List<String> raw = new ArrayList<>();
        recorder.replay(logging(raw));
        for (final String call : raw)
        {
            final String[] parts = call.split(" ");
            switch (parts[0])
            {
                case "pos" -> target.addVertex(pose, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                case "normal" -> target.setNormal(pose, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                case "color" -> target.setColor(Integer.parseUnsignedInt(parts[1], 16));
                case "uv" -> target.setUv(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                case "uv1" -> target.setUv1(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                case "uv2" -> target.setUv2(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                case "uv3" -> target.setUv3(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                case "width" -> target.setLineWidth(Float.parseFloat(parts[1]));
                default -> throw new IllegalStateException(call);
            }
        }
    }

    /**
     * Records {@code quads} baked-quad shaped vertices the way {@code putBakedQuad} writes them into the recorder
     * (position, colour, uv, overlay, light, normal), with non-trivial values.
     */
    private static VertexRecorder bakedQuads(final int quads)
    {
        final VertexRecorder recorder = new VertexRecorder();
        for (int i = 0; i < quads * 4; i++)
        {
            final int face = i / 4 % 6;
            final float nx = face == 0 ? 1f : face == 1 ? -1f : 0f;
            final float ny = face == 2 ? 1f : face == 3 ? -1f : 0f;
            final float nz = face == 4 ? 1f : face == 5 ? -1f : 0f;
            recorder.addVertex(i * 0.37f + 0.01f, (i % 17) - 8.99f, -(i % 29) * 1.13f, 0x66000000 | (i * 2654435) & 0xffffff,
                (i % 16) / 16f, (i % 7) / 7f, 0xa << 16 | 3, (i % 15) << 20 | (15 - i % 15) << 4, nx, ny, nz);
        }
        return recorder;
    }

    /**
     * Wraps a logging consumer so a bulk vertex call is logged as the element calls it stands for, exactly as
     * {@link VertexConsumer}'s default bulk method splits it (and BufferBuilder's direct write stores it).
     */
    private static VertexConsumer elementwise(final VertexConsumer delegate)
    {
        return new VertexConsumer()
        {
            @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
            @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
            @Override public VertexConsumer setColor(int color) { delegate.setColor(color); return this; }
            @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
            @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
            @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
            @Override public VertexConsumer setUv3(float u, float v) { delegate.setUv3(u, v); return this; }
            @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
            @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
        };
    }

    /**
     * Counts how replay drives the target: per-vertex matrix transforms, per-vertex normal transforms, bulk vertices.
     */
    private static final class Counting implements VertexConsumer
    {
        int matrixTransforms;
        int normalTransforms;
        int bulkVertices;

        @Override
        public VertexConsumer addVertex(final org.joml.Matrix4fc pose, final float x, final float y, final float z)
        {
            matrixTransforms++;
            return VertexConsumer.super.addVertex(pose, x, y, z);
        }

        @Override
        public VertexConsumer setNormal(final PoseStack.Pose pose, final float x, final float y, final float z)
        {
            normalTransforms++;
            return VertexConsumer.super.setNormal(pose, x, y, z);
        }

        @Override
        public void addVertex(final float x, final float y, final float z, final int color, final float u, final float v,
            final int overlayCoords, final int lightCoords, final float nx, final float ny, final float nz)
        {
            bulkVertices++;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
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
