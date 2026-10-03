package com.ldtteam.structurize.client;

import com.ldtteam.structurize.client.rendertask.util.VertexRecorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * PF1: the cached, tessellated mesh of one blueprint preview (one rotation/mirror of one blueprint, see
 * {@link RenderingCacheKey}), recorded once in blueprint-local space per layer and replayed every frame.
 * It is rebuilt only when its key changes; moving the ghost only changes the replay pose, never the key.
 *
 * @param <K> what the mesh depends on besides the blueprint (see {@link PreviewMeshKey})
 * @param <L> the layer a recorder belongs to (a render type)
 */
final class PreviewMesh<K, L>
{
    private final Map<L, VertexRecorder> layers = new LinkedHashMap<>();
    private final Map<L, VertexRecorder> view = Collections.unmodifiableMap(layers);
    private K key;
    private int builds;
    /** FX1: a prewarm tessellating on a worker; only the render thread reads or replaces this field. */
    private Pending<K, L> pending;

    /**
     * Returns the mesh for this key, tessellating it first only if the key differs from the one it was built for.
     *
     * @param key     the current inputs
     * @param builder fills the (cleared) layer map; layers are created with {@code computeIfAbsent}
     * @return the layers in insertion order, read-only
     */
    Map<L, VertexRecorder> get(final K key, final Consumer<Map<L, VertexRecorder>> builder)
    {
        final Pending<K, L> job = pending;
        if (job != null)
        {
            if (!job.done)
            {
                if (job.key.equals(key))
                {
                    // FX1: still tessellating on a worker; draw nothing this frame rather than stall the render thread
                    return Map.of();
                }
                // the inputs changed while it was building (transparency, smooth lighting, reload): its result is stale
                pending = null;
            }
            else
            {
                pending = null;
                if (job.layers != null && job.key.equals(key))
                {
                    layers.clear();
                    layers.putAll(job.layers);
                    this.key = key;
                    builds++;
                    return view;
                }
            }
        }

        if (this.key == null || !this.key.equals(key))
        {
            layers.clear();
            builder.accept(layers);
            this.key = key;
            builds++;
        }
        return view;
    }

    /**
     * FX1: tessellates the mesh for this key ahead of its first draw (a prewarmed neighbouring rotation).
     *
     * @param key      the inputs the mesh is built for
     * @param builder  fills a fresh layer map; must not touch GL or the live level
     * @param executor where the builder runs
     */
    void buildAsync(final K key, final Consumer<Map<L, VertexRecorder>> builder, final Executor executor)
    {
        final Pending<K, L> job = new Pending<>(key);
        pending = job;
        CompletableFuture.supplyAsync(() -> {
            final Map<L, VertexRecorder> built = new LinkedHashMap<>();
            builder.accept(built);
            return built;
        }, executor).whenComplete((built, failure) -> job.complete(built, failure));
    }

    /**
     * @return whether a mesh is built and installed (a pending asynchronous build does not count)
     */
    boolean isBuilt()
    {
        return key != null;
    }

    /**
     * Drops the mesh, so the next {@link #get} tessellates again (blueprint re-initialised, renderer closed).
     */
    void invalidate()
    {
        layers.clear();
        key = null;
        pending = null;
    }

    /**
     * @return how many times the mesh was tessellated (test seam)
     */
    int builds()
    {
        return builds;
    }

    /**
     * FX1: one asynchronous build. The worker publishes its result through the volatile flag; the render thread reads
     * it only after seeing {@code done}.
     */
    private static final class Pending<K, L>
    {
        private final K key;
        private Map<L, VertexRecorder> layers;
        private volatile boolean done;

        private Pending(final K key)
        {
            this.key = key;
        }

        private void complete(final Map<L, VertexRecorder> built, final Throwable failure)
        {
            layers = failure == null ? built : null;
            done = true;
        }
    }
}
