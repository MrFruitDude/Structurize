package com.ldtteam.structurize.client;

import com.ldtteam.structurize.client.rendertask.util.VertexRecorder;
import com.ldtteam.structurize.util.RotationMirror;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.block.Rotation;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FX1 #5: rotating the ghost used to tessellate the whole blueprint on the render thread on the first frame of each
 * new rotation (a 93-138 ms hitch with University 5). The neighbouring rotations of a ghost the user is rotating are
 * now prepared ahead of time, their mesh tessellated on a worker thread, and the render thread never waits for it.
 */
public class PreviewPrewarmTest
{
    private static final BlockStateModelSet BLOCKS = new BlockStateModelSet(Map.of(), null);
    private static final FluidStateModelSet FLUIDS = new FluidStateModelSet(Map.of(), null);
    private static final PreviewMeshKey KEY = new PreviewMeshKey(0.4f, true, false, BLOCKS, FLUIDS);

    private static final Consumer<Map<String, VertexRecorder>> NO_SYNC_BUILD =
        layers -> fail("the render thread tessellated a mesh that was being prepared on a worker");

    @Test(timeout = 20_000)
    public void aPrewarmedMeshIsTessellatedOnAWorkerThread() throws Exception
    {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
            final AtomicReference<Thread> builtOn = new AtomicReference<>();
            mesh.buildAsync(copy(KEY), layers -> {
                builtOn.set(Thread.currentThread());
                layers.computeIfAbsent("translucent", type -> new VertexRecorder()).addVertex(1f, 2f, 3f);
            }, worker);

            final Map<String, VertexRecorder> layers = awaitInstalled(mesh, KEY);
            assertNotNull("the builder ran", builtOn.get());
            assertNotSame("tessellated on the render (calling) thread", Thread.currentThread(), builtOn.get());
            assertEquals(Set.of("translucent"), layers.keySet());
            assertEquals(1, layers.get("translucent").vertexCount());
            assertEquals(1, mesh.builds());
            assertTrue(mesh.isBuilt());

            // later frames use the installed mesh and never tessellate again
            for (int frame = 0; frame < 100; frame++)
            {
                mesh.get(copy(KEY), NO_SYNC_BUILD);
            }
            assertEquals(1, mesh.builds());
        }
        finally
        {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 20_000)
    public void theRenderThreadNeverWaitsForAPendingBuild() throws Exception
    {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
            final CountDownLatch release = new CountDownLatch(1);
            final long start = System.nanoTime();
            mesh.buildAsync(KEY, layers -> {
                try
                {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (final InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                layers.computeIfAbsent("solid", type -> new VertexRecorder());
            }, worker);

            // frames while the worker is still busy: nothing drawn yet, no stall, no second tessellation
            for (int frame = 0; frame < 30; frame++)
            {
                assertTrue(mesh.get(copy(KEY), NO_SYNC_BUILD).isEmpty());
            }
            assertFalse(mesh.isBuilt());
            assertTrue("render thread was blocked by the build", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1000));

            release.countDown();
            assertEquals(Set.of("solid"), awaitInstalled(mesh, KEY).keySet());
            assertEquals(1, mesh.builds());
        }
        finally
        {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 20_000)
    public void aKeyChangeDiscardsThePrewarmedMeshAndAnInvalidateDropsAPendingOne() throws Exception
    {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            final PreviewMesh<PreviewMeshKey, String> mesh = new PreviewMesh<>();
            final CountDownLatch built = new CountDownLatch(1);
            mesh.buildAsync(KEY, layers -> {
                layers.computeIfAbsent("translucent", type -> new VertexRecorder());
                built.countDown();
            }, worker);
            assertTrue(built.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);

            // the transparency changed before the prewarmed rotation was ever shown: build for the current key
            final PreviewMeshKey opaque = new PreviewMeshKey(1f, true, false, BLOCKS, FLUIDS);
            final Map<String, VertexRecorder> layers =
                mesh.get(opaque, map -> map.computeIfAbsent("solid", type -> new VertexRecorder()));
            assertEquals(Set.of("solid"), layers.keySet());
            assertEquals(1, mesh.builds());

            // a renderer closed while its prewarm is still running must not install it later
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(1);
            mesh.buildAsync(KEY, map -> {
                try
                {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (final InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                map.computeIfAbsent("late", type -> new VertexRecorder());
                done.countDown();
            }, worker);
            mesh.invalidate();
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(mesh.isBuilt());
            assertEquals(Set.of("fresh"), mesh.get(KEY, map -> map.computeIfAbsent("fresh", type -> new VertexRecorder())).keySet());
        }
        finally
        {
            worker.shutdownNow();
        }
    }

    @Test
    public void aGhostThatNeverRotatesIsNeverPrewarmed()
    {
        // MineColonies' building previews and CC's order/road ghosts: many ghosts, none rotated by the user
        final PreviewPrewarmer<Object> prewarmer = new PreviewPrewarmer<>();
        final Object[] ghosts = {new Object(), new Object(), new Object()};
        for (int frame = 0; frame < 600; frame++)
        {
            for (final Object ghost : ghosts)
            {
                assertNull(prewarmer.next(ghost, RotationMirror.R90, true, rotation -> rotation == RotationMirror.R90));
            }
        }
    }

    @Test
    public void afterARotateBothNeighboursArePreparedOneAtATime()
    {
        for (final RotationMirror start : RotationMirror.values())
        {
            final PreviewPrewarmer<Object> prewarmer = new PreviewPrewarmer<>();
            final Object ghost = new Object();
            final Set<RotationMirror> cached = EnumSet.of(start);
            assertNull(prewarmer.next(ghost, start, true, cached::contains));

            // the user presses rotate (T)
            final RotationMirror now = start.rotate(Rotation.CLOCKWISE_90);
            cached.add(now);
            final Set<RotationMirror> expected = EnumSet.of(now.rotate(Rotation.CLOCKWISE_90), now.rotate(Rotation.COUNTERCLOCKWISE_90));
            expected.removeAll(cached);

            for (int i = 0; i < expected.size(); i++)
            {
                final RotationMirror next = prewarmer.next(ghost, now, true, cached::contains);
                assertNotNull("rotation " + start + " -> " + now + ", neighbour " + i, next);
                assertTrue(next + " is not a neighbour of " + now, expected.contains(next));
                assertEquals("a rotate never flips the mirror", now.isMirrored(), next.isMirrored());
                cached.add(next);
            }
            assertTrue(cached.containsAll(expected));
            for (int frame = 0; frame < 100; frame++)
            {
                assertNull("nothing left to prepare", prewarmer.next(ghost, now, true, cached::contains));
            }
        }
    }

    @Test
    public void noPrewarmWhileTheVisibleMeshIsMissingAndAnExplicitRequestNeedsNoRotate()
    {
        final PreviewPrewarmer<Object> prewarmer = new PreviewPrewarmer<>();
        final Object ghost = new Object();
        final Set<RotationMirror> cached = EnumSet.of(RotationMirror.NONE, RotationMirror.R90);
        prewarmer.next(ghost, RotationMirror.NONE, true, cached::contains);
        for (int frame = 0; frame < 20; frame++)
        {
            assertNull(prewarmer.next(ghost, RotationMirror.R90, false, cached::contains));
        }
        assertEquals(RotationMirror.R180, prewarmer.next(ghost, RotationMirror.R90, true, cached::contains));

        // CC arming a tile asks before any rotate
        final Object armed = new Object();
        prewarmer.arm(armed);
        final Set<RotationMirror> armedCached = EnumSet.of(RotationMirror.NONE);
        final RotationMirror first = prewarmer.next(armed, RotationMirror.NONE, true, armedCached::contains);
        assertEquals(RotationMirror.NONE.rotate(Rotation.CLOCKWISE_90), first);
        armedCached.add(first);
        assertEquals(RotationMirror.NONE.rotate(Rotation.COUNTERCLOCKWISE_90), prewarmer.next(armed, RotationMirror.NONE, true, armedCached::contains));
    }

    @Test
    public void aBlueprintCopyIsEqualButSharesNoMutableState()
    {
        final net.minecraft.nbt.CompoundTag te = new net.minecraft.nbt.CompoundTag();
        te.putShort("x", (short) 1);
        te.putShort("y", (short) 0);
        te.putShort("z", (short) 2);
        te.putString("id", "minecraft:chest");
        final net.minecraft.nbt.CompoundTag entity = new net.minecraft.nbt.CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        final com.ldtteam.structurize.blueprints.v1.Blueprint hall = new com.ldtteam.structurize.blueprints.v1.Blueprint(
            (short) 3, (short) 2, (short) 3, (short) 0, java.util.List.of(), new short[2][3][3], new net.minecraft.nbt.CompoundTag[] {te}, java.util.List.of())
            .setName("townhall5").setPackName("Original").setFileName("townhall5");
        hall.getStructure()[1][2][0] = 7;

        final com.ldtteam.structurize.blueprints.v1.Blueprint copy = hall.copy();
        assertEquals("same render-cache identity", hall, copy);
        assertEquals(hall.hashCode(), copy.hashCode());
        assertEquals(new RenderingCacheKey(RotationMirror.R90, hall), new RenderingCacheKey(RotationMirror.R90, copy));
        assertEquals(hall.getRotationMirror(), copy.getRotationMirror());
        assertEquals(7, copy.getStructure()[1][2][0]);
        assertEquals("minecraft:chest", copy.getTileEntities()[0][2][1].getStringOr("id", ""));

        // what a rotation of the copy does in place must not reach the original
        copy.getStructure()[1][2][0] = 3;
        copy.getTileEntities()[0][2][1].putInt("x", 99);
        assertEquals(7, hall.getStructure()[1][2][0]);
        assertEquals(1, hall.getTileEntities()[0][2][1].getShortOr("x", (short) -1));
        assertNotSame(hall.getEntities(), copy.getEntities());
    }

    private static Map<String, VertexRecorder> awaitInstalled(final PreviewMesh<PreviewMeshKey, String> mesh, final PreviewMeshKey key)
        throws InterruptedException
    {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline)
        {
            final Map<String, VertexRecorder> layers = mesh.get(copy(key), NO_SYNC_BUILD);
            if (!layers.isEmpty())
            {
                return layers;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("prewarmed mesh never installed");
    }

    private static PreviewMeshKey copy(final PreviewMeshKey key)
    {
        return new PreviewMeshKey(key.alpha(), key.ambientOcclusion(), key.cutoutLeaves(), key.blockModels(), key.fluidModels());
    }
}
