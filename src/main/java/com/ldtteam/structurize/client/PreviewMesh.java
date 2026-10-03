package com.ldtteam.structurize.client;

import com.ldtteam.structurize.client.rendertask.util.VertexRecorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Returns the mesh for this key, tessellating it first only if the key differs from the one it was built for.
     *
     * @param key     the current inputs
     * @param builder fills the (cleared) layer map; layers are created with {@code computeIfAbsent}
     * @return the layers in insertion order, read-only
     */
    Map<L, VertexRecorder> get(final K key, final Consumer<Map<L, VertexRecorder>> builder)
    {
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
     * Drops the mesh, so the next {@link #get} tessellates again (blueprint re-initialised, renderer closed).
     */
    void invalidate()
    {
        layers.clear();
        key = null;
    }

    /**
     * @return how many times the mesh was tessellated (test seam)
     */
    int builds()
    {
        return builds;
    }
}
