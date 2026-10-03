package com.ldtteam.structurize.client;

import com.google.common.cache.LoadingCache;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.client.rendertask.util.VertexRecorder;
import com.ldtteam.structurize.util.RotationMirror;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * PF1: the ghost's mesh is tessellated once per actual change and never per frame or per move. Rotation, mirror and
 * blueprint changes select a different renderer (and so a fresh mesh); alpha, smooth lighting, cutout leaves and a
 * resource reload (new model sets) rebuild the current one; moving the ghost and plain frames rebuild nothing.
 */
public class PreviewMeshTest
{
    private static final BlockStateModelSet BLOCKS = new BlockStateModelSet(Map.of(), null);
    private static final FluidStateModelSet FLUIDS = new FluidStateModelSet(Map.of(), null);

    @Test
    public void framesAndMovesNeverRebuild()
    {
        final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
        final int[] builds = {0};
        for (int frame = 0; frame < 600; frame++)
        {
            // a new but equal key every frame, exactly like BlueprintRenderer.submitMesh builds one per frame
            final Map<String, VertexRecorder> layers = mesh.get(key(0.4f, true, false, BLOCKS, FLUIDS), map -> {
                builds[0]++;
                map.computeIfAbsent("translucent", type -> new VertexRecorder()).addVertex(1f, 2f, 3f).setColor(-1);
            });

            // the ghost moves every frame (camera-relative anchor offset); only the replay pose changes
            final PoseStack pose = new PoseStack();
            pose.translate(frame * 0.5f, 0f, -frame);
            final float[] written = new float[1];
            layers.get("translucent").replay(position(written), pose.last());
            assertEquals(1f + frame * 0.5f, written[0], 0f);
        }
        assertEquals("tessellations over 600 moving frames", 1, builds[0]);
        assertEquals(1, mesh.builds());
    }

    @Test
    public void eachMeshInputChangeRebuildsExactlyOnce()
    {
        final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
        final List<PreviewMeshKey> steps = List.of(
            key(0.4f, true, false, BLOCKS, FLUIDS),
            key(0.6f, true, false, BLOCKS, FLUIDS),                                                // transparency slider
            key(0.6f, false, false, BLOCKS, FLUIDS),                                               // smooth lighting
            key(0.6f, false, true, BLOCKS, FLUIDS),                                                // cutout leaves
            key(0.6f, false, true, new BlockStateModelSet(Map.of(), null), FLUIDS),               // reload: block models
            key(0.6f, false, true, new BlockStateModelSet(Map.of(), null), new FluidStateModelSet(Map.of(), null))); // fluids

        for (int i = 0; i < steps.size(); i++)
        {
            for (int frame = 0; frame < 50; frame++)
            {
                final PreviewMeshKey frameKey = copy(steps.get(i));
                mesh.get(frameKey, map -> map.computeIfAbsent("solid", type -> new VertexRecorder()));
            }
            assertEquals("after change " + i, i + 1, mesh.builds());
        }
    }

    @Test
    public void rebuildReplacesTheOldLayersAndInvalidateForcesOne()
    {
        final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
        mesh.get(key(0.4f, true, false, BLOCKS, FLUIDS), map -> map.computeIfAbsent("translucent", type -> new VertexRecorder()));
        final Map<String, VertexRecorder> opaque =
            mesh.get(key(1f, true, false, BLOCKS, FLUIDS), map -> map.computeIfAbsent("solid", type -> new VertexRecorder()));
        assertEquals(List.of("solid"), List.copyOf(opaque.keySet()));

        mesh.invalidate();
        assertEquals(List.of(), List.copyOf(mesh.get(key(1f, true, false, BLOCKS, FLUIDS), map -> {}).keySet()));
        assertEquals(3, mesh.builds());
        mesh.get(key(1f, true, false, BLOCKS, FLUIDS), map -> map.put("never", new VertexRecorder()));
        assertEquals(3, mesh.builds());
    }

    @Test
    public void rotateMirrorAndBlueprintSelectTheirOwnRenderer()
    {
        final List<Fake> made = new ArrayList<>();
        final LoadingCache<RenderingCacheKey, Fake> cache = PreviewRendererCache.create(key -> {
            final Fake fake = new Fake(key);
            made.add(fake);
            return fake;
        });

        final Blueprint hall = blueprint("townhall5");
        final Blueprint tavern = blueprint("tavern3");

        // plain frames and moves: the preview's key does not change, so one renderer
        final Fake first = cache.getUnchecked(new RenderingCacheKey(RotationMirror.NONE, hall));
        for (int frame = 0; frame < 300; frame++)
        {
            assertSame(first, cache.getUnchecked(new RenderingCacheKey(RotationMirror.NONE, hall)));
        }
        assertEquals(1, made.size());

        // rotate
        assertNotSame(first, cache.getUnchecked(new RenderingCacheKey(RotationMirror.R90, hall)));
        assertEquals(2, made.size());
        // mirror
        cache.getUnchecked(new RenderingCacheKey(RotationMirror.MIR_R90, hall));
        assertEquals(3, made.size());
        // blueprint change
        cache.getUnchecked(new RenderingCacheKey(RotationMirror.MIR_R90, tavern));
        assertEquals(4, made.size());
        // every remaining rotation/mirror state of the hall, once each
        for (final RotationMirror rotationMirror : RotationMirror.values())
        {
            cache.getUnchecked(new RenderingCacheKey(rotationMirror, hall));
            cache.getUnchecked(new RenderingCacheKey(rotationMirror, hall));
        }
        assertEquals(4 + RotationMirror.values().length - 3, made.size());

        // going back to an earlier state reuses its renderer (and its already tessellated mesh)
        assertSame(first, cache.getUnchecked(new RenderingCacheKey(RotationMirror.NONE, hall)));

        cache.invalidateAll();
        assertEquals(made.size(), made.stream().filter(fake -> fake.closed).count());
    }

    private static final class Fake implements AutoCloseable
    {
        final RenderingCacheKey key;
        boolean closed;

        Fake(final RenderingCacheKey key)
        {
            this.key = key;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private static Blueprint blueprint(final String name)
    {
        return new Blueprint((short) 3, (short) 2, (short) 3, (short) 0, List.of(), new short[2][3][3], new CompoundTag[0], List.of())
            .setName(name);
    }

    private static PreviewMeshKey key(
        final float alpha, final boolean ao, final boolean cutoutLeaves, final BlockStateModelSet blocks, final FluidStateModelSet fluids)
    {
        return new PreviewMeshKey(alpha, ao, cutoutLeaves, blocks, fluids);
    }

    private static PreviewMeshKey copy(final PreviewMeshKey key)
    {
        return new PreviewMeshKey(key.alpha(), key.ambientOcclusion(), key.cutoutLeaves(), key.blockModels(), key.fluidModels());
    }

    private static VertexConsumer position(final float[] x)
    {
        return new VertexConsumer()
        {
            @Override public VertexConsumer addVertex(float px, float py, float pz) { x[0] = px; return this; }
            @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            @Override public VertexConsumer setColor(int color) { return this; }
            @Override public VertexConsumer setUv(float u, float v) { return this; }
            @Override public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }
            @Override public VertexConsumer setUv3(float u, float v) { return this; }
            @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return this; }
            @Override public VertexConsumer setLineWidth(float width) { return this; }
        };
    }
}
