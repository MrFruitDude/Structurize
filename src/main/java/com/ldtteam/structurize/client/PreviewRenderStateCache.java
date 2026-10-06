package com.ldtteam.structurize.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * FX1: the extracted render states of a ghost's block entities or entities. An owner outside the view frustum is not
 * extracted at all; a static owner's state is extracted once per game tick and reused by the frames in between (its
 * inputs only change on a tick: the preview ticks nothing it does not animate, and its light level is static); an
 * animated owner is extracted every frame as before. A null state (nothing to draw) is remembered like any other.
 *
 * @param <T> the preview object (block entity or entity)
 * @param <S> its extracted render state
 */
final class PreviewRenderStateCache<T, S>
{
    private final Map<T, Entry<S>> entries = new IdentityHashMap<>();
    private int extracts;

    /**
     * @param owner     the preview object
     * @param visible   whether it is inside the view frustum this frame
     * @param gameTime  the client game time this frame
     * @param perFrame  whether its state animates within a tick and must be extracted every frame
     * @param extractor extracts a fresh state (may return null: nothing to draw)
     * @return the state to submit this frame, or null to submit nothing
     */
    S state(final T owner, final boolean visible, final long gameTime, final boolean perFrame, final Function<T, S> extractor)
    {
        if (!visible)
        {
            return null;
        }
        if (!perFrame)
        {
            final Entry<S> entry = entries.get(owner);
            if (entry != null && entry.gameTime() == gameTime)
            {
                return entry.state();
            }
        }
        extracts++;
        final S state = extractor.apply(owner);
        if (!perFrame)
        {
            entries.put(owner, new Entry<>(gameTime, state));
        }
        return state;
    }

    /**
     * Drops every cached state (renderer re-initialised or closed).
     */
    void clear()
    {
        entries.clear();
    }

    /**
     * @return how many times a state was extracted (test seam)
     */
    int extracts()
    {
        return extracts;
    }

    private record Entry<S>(long gameTime, S state)
    {
    }
}
