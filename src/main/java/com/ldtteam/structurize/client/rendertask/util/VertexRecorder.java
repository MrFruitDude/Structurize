package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import net.minecraft.util.ARGB;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Heap-only {@link VertexConsumer} that records vertices so they can be replayed later into the
 * consumer Minecraft hands out for a custom-geometry submission. Positions and normals are stored
 * exactly as written (callers apply their pose while writing), and only the elements that were set
 * are replayed, so the target format decides which of them it keeps.
 */
public final class VertexRecorder implements VertexConsumer
{
    private static final int POS         = 1;
    private static final int COLOR       = 1 << 1;
    private static final int UV0         = 1 << 2;
    private static final int UV1         = 1 << 3;
    private static final int UV2         = 1 << 4;
    private static final int UV3         = 1 << 5;
    private static final int NORMAL      = 1 << 6;
    private static final int LINE_WIDTH  = 1 << 7;
    /** What putBakedQuad writes per vertex: position, colour, uv, overlay (uv1), light (uv2), normal. */
    private static final int BAKED_QUAD  = POS | COLOR | UV0 | UV1 | UV2 | NORMAL;

    // Per-vertex layout (ints; floats stored as raw bits).
    private static final int O_MASK   = 0;
    private static final int O_X      = 1;
    private static final int O_COLOR  = 4;
    private static final int O_UV0    = 5;
    private static final int O_UV1    = 7;
    private static final int O_UV2    = 9;
    private static final int O_UV3    = 11;
    private static final int O_NORMAL = 13;
    private static final int O_WIDTH  = 16;
    private static final int STRIDE   = 17;

    private int[] data = new int[STRIDE * 64];
    private int vertexCount;

    public boolean isEmpty()
    {
        return vertexCount == 0;
    }

    public int vertexCount()
    {
        return vertexCount;
    }

    /**
     * Writes every recorded vertex into the given consumer, transforming positions and normals by the pose.
     * Used to replay geometry that was recorded once in a local space (for example a cached blueprint mesh).
     *
     * @param target the consumer to replay into
     * @param pose   the pose to apply
     */
    public void replay(final VertexConsumer target, final PoseStack.Pose pose)
    {
        if (isTranslationOnly(pose))
        {
            final Matrix4f matrix = pose.pose();
            replayTranslated(target, matrix.m30(), matrix.m31(), matrix.m32());
            return;
        }

        for (int v = 0; v < vertexCount; v++)
        {
            final int base = v * STRIDE;
            final int mask = data[base + O_MASK];
            target.addVertex(pose, f(base + O_X), f(base + O_X + 1), f(base + O_X + 2));
            replayElements(target, base, mask);
            if ((mask & NORMAL) != 0)
            {
                target.setNormal(pose, f(base + O_NORMAL), f(base + O_NORMAL + 1), f(base + O_NORMAL + 2));
            }
            if ((mask & LINE_WIDTH) != 0)
            {
                target.setLineWidth(f(base + O_WIDTH));
            }
        }
    }

    /**
     * PF1: whether replaying through this pose is a plain translation, which is what the blueprint ghost gets every
     * frame (camera-relative anchor offset, no rotation or scale unless CC animates a glide or turn). Then every
     * position is just offset and every normal passes through unchanged, so the per-vertex matrix and normal
     * transforms of the full path can be skipped with the same result (up to the sign of a zero).
     * Checked once per replay, not per vertex.
     */
    static boolean isTranslationOnly(final PoseStack.Pose pose)
    {
        if (!MatrixUtil.isPureTranslation(pose.pose()))
        {
            return false;
        }
        final Matrix3f n = pose.normal();
        if (n.m00() != 1f || n.m11() != 1f || n.m22() != 1f
            || n.m01() != 0f || n.m02() != 0f || n.m10() != 0f || n.m12() != 0f || n.m20() != 0f || n.m21() != 0f)
        {
            return false;
        }
        // A pose whose normals are not trusted renormalises every normal; that flag is private, so probe it with a
        // non-unit vector: a trusted identity normal matrix hands it back unchanged.
        return pose.transformNormal(2f, 0f, 0f, new Vector3f()).x() == 2f;
    }

    /**
     * Replays with a translation-only pose. A vertex carrying exactly the baked-quad element set goes out as one
     * bulk vertex call, the same call vanilla's putBakedQuad makes, which BufferBuilder writes directly for the BLOCK
     * format; any other vertex goes out element by element as in the full path.
     */
    private void replayTranslated(final VertexConsumer target, final float tx, final float ty, final float tz)
    {
        for (int v = 0; v < vertexCount; v++)
        {
            final int base = v * STRIDE;
            final int mask = data[base + O_MASK];
            final float x = f(base + O_X) + tx;
            final float y = f(base + O_X + 1) + ty;
            final float z = f(base + O_X + 2) + tz;
            if (mask == BAKED_QUAD)
            {
                target.addVertex(x, y, z,
                    data[base + O_COLOR],
                    f(base + O_UV0), f(base + O_UV0 + 1),
                    packUv(base + O_UV1),
                    packUv(base + O_UV2),
                    f(base + O_NORMAL), f(base + O_NORMAL + 1), f(base + O_NORMAL + 2));
                continue;
            }

            target.addVertex(x, y, z);
            replayElements(target, base, mask);
            if ((mask & NORMAL) != 0)
            {
                target.setNormal(f(base + O_NORMAL), f(base + O_NORMAL + 1), f(base + O_NORMAL + 2));
            }
            if ((mask & LINE_WIDTH) != 0)
            {
                target.setLineWidth(f(base + O_WIDTH));
            }
        }
    }

    /**
     * Re-packs a uv pair stored by setUv1/setUv2 into the packed int the bulk call takes; the default
     * setOverlay/setLight split it back into the same pair.
     */
    private int packUv(final int index)
    {
        return data[index + 1] << 16 | data[index] & 0xFFFF;
    }

    /**
     * Writes every recorded vertex into the given consumer, in order.
     *
     * @param target the consumer to replay into
     */
    public void replay(final VertexConsumer target)
    {
        for (int v = 0; v < vertexCount; v++)
        {
            final int base = v * STRIDE;
            final int mask = data[base + O_MASK];
            target.addVertex(f(base + O_X), f(base + O_X + 1), f(base + O_X + 2));
            replayElements(target, base, mask);
            if ((mask & NORMAL) != 0)
            {
                target.setNormal(f(base + O_NORMAL), f(base + O_NORMAL + 1), f(base + O_NORMAL + 2));
            }
            if ((mask & LINE_WIDTH) != 0)
            {
                target.setLineWidth(f(base + O_WIDTH));
            }
        }
    }

    private void replayElements(final VertexConsumer target, final int base, final int mask)
    {
        if ((mask & COLOR) != 0)
        {
            target.setColor(data[base + O_COLOR]);
        }
        if ((mask & UV0) != 0)
        {
            target.setUv(f(base + O_UV0), f(base + O_UV0 + 1));
        }
        if ((mask & UV1) != 0)
        {
            target.setUv1(data[base + O_UV1], data[base + O_UV1 + 1]);
        }
        if ((mask & UV2) != 0)
        {
            target.setUv2(data[base + O_UV2], data[base + O_UV2 + 1]);
        }
        if ((mask & UV3) != 0)
        {
            target.setUv3(f(base + O_UV3), f(base + O_UV3 + 1));
        }
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z)
    {
        final int base = vertexCount * STRIDE;
        if (base + STRIDE > data.length)
        {
            data = Arrays.copyOf(data, data.length * 2);
        }
        vertexCount++;
        data[base + O_MASK] = POS;
        putF(base + O_X, x);
        putF(base + O_X + 1, y);
        putF(base + O_X + 2, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(final int r, final int g, final int b, final int a)
    {
        return setColor(ARGB.color(a, r, g, b));
    }

    @Override
    public VertexConsumer setColor(final int color)
    {
        final int base = current(COLOR);
        data[base + O_COLOR] = color;
        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v)
    {
        final int base = current(UV0);
        putF(base + O_UV0, u);
        putF(base + O_UV0 + 1, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v)
    {
        final int base = current(UV1);
        data[base + O_UV1] = u;
        data[base + O_UV1 + 1] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v)
    {
        final int base = current(UV2);
        data[base + O_UV2] = u;
        data[base + O_UV2 + 1] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv3(final float u, final float v)
    {
        final int base = current(UV3);
        putF(base + O_UV3, u);
        putF(base + O_UV3 + 1, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z)
    {
        final int base = current(NORMAL);
        putF(base + O_NORMAL, x);
        putF(base + O_NORMAL + 1, y);
        putF(base + O_NORMAL + 2, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(final float width)
    {
        final int base = current(LINE_WIDTH);
        putF(base + O_WIDTH, width);
        return this;
    }

    /**
     * Marks an element as set on the most recent vertex and returns that vertex's offset.
     */
    private int current(final int element)
    {
        if (vertexCount == 0)
        {
            throw new IllegalStateException("Vertex element set before addVertex");
        }
        final int base = (vertexCount - 1) * STRIDE;
        data[base + O_MASK] |= element;
        return base;
    }

    private float f(final int index)
    {
        return Float.intBitsToFloat(data[index]);
    }

    private void putF(final int index, final float value)
    {
        data[index] = Float.floatToRawIntBits(value);
    }
}
