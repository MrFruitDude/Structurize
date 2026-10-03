package com.ldtteam.structurize.client;

import com.ldtteam.structurize.util.RotationMirror;

import java.util.function.Predicate;

/**
 * FX1: decides which neighbouring rotation of a ghost to prepare ahead of time, so the next rotate (T) finds its
 * renderer and mesh already built instead of tessellating the whole blueprint on the render thread.
 *
 * @param <P> the preview (identity matters, not equality)
 */
final class PreviewPrewarmer<P>
{
    /**
     * @param preview     the preview drawn this frame
     * @param current     its rotation/mirror this frame
     * @param currentReady whether its own mesh is built (never prewarm while the visible one is still missing)
     * @param cached      whether a renderer for a rotation/mirror of this blueprint already exists
     * @return the rotation/mirror to prepare now (at most one per call), or null
     */
    RotationMirror next(final P preview, final RotationMirror current, final boolean currentReady, final Predicate<RotationMirror> cached)
    {
        return null;
    }

    /**
     * Asks for this preview's neighbours to be prepared even before it was seen rotating.
     */
    void arm(final P preview)
    {
    }
}
