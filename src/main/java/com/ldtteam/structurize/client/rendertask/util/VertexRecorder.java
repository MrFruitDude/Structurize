package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.ARGB;

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
