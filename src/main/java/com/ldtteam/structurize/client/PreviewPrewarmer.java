package com.ldtteam.structurize.client;

import com.ldtteam.structurize.util.RotationMirror;
import net.minecraft.world.level.block.Rotation;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * FX1: decides which neighbouring rotation of a ghost to prepare ahead of time, so the next rotate (T) finds its
 * renderer and mesh already built instead of tessellating the whole blueprint on the render thread.
 * <p>
 * Only ghosts the user is actually rotating are prepared (seen with a different rotation than the frame before, or
 * {@link #arm armed} explicitly): MineColonies' building previews and Colony Command's order and road ghosts never
 * rotate, and preparing two extra meshes for each of them would cost memory and worker time for nothing.
 * The neighbours are the clockwise and counter-clockwise rotation with the same mirror, one per call (frame), so the
 * render-thread part of a prepare (copy + rotate + instantiate) is spread over frames.
 *
 * @param <P> the preview (identity matters, not equality)
 */
final class PreviewPrewarmer<P>
{
    private final Map<P, RotationMirror> lastSeen = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<P> armed = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /**
     * @param preview     the preview drawn this frame
     * @param current     its rotation/mirror this frame
     * @param currentReady whether its own mesh is built (never prewarm while the visible one is still missing)
     * @param cached      whether a renderer for a rotation/mirror of this blueprint already exists
     * @return the rotation/mirror to prepare now (at most one per call), or null
     */
    RotationMirror next(final P preview, final RotationMirror current, final boolean currentReady, final Predicate<RotationMirror> cached)
    {
        final RotationMirror previous = lastSeen.put(preview, current);
        if (previous != null && previous != current)
        {
            armed.add(preview);
        }
        if (!currentReady || !armed.contains(preview))
        {
            return null;
        }
        final RotationMirror clockwise = current.rotate(Rotation.CLOCKWISE_90);
        if (!cached.test(clockwise))
        {
            return clockwise;
        }
        final RotationMirror counterClockwise = current.rotate(Rotation.COUNTERCLOCKWISE_90);
        if (!cached.test(counterClockwise))
        {
            return counterClockwise;
        }
        return null;
    }

    /**
     * Asks for this preview's neighbours to be prepared even before it was seen rotating.
     */
    void arm(final P preview)
    {
        armed.add(preview);
    }
}
