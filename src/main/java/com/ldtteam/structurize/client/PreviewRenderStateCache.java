package com.ldtteam.structurize.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * FX1: the extracted render states of a ghost's block entities or entities.
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
        extracts++;
        return extractor.apply(owner);
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
