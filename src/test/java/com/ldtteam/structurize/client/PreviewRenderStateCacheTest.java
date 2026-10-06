package com.ldtteam.structurize.client;

import org.junit.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * FX1 #15: the ghost re-extracted every block entity's and entity's render state every frame, also for ones outside
 * the view and ones whose state cannot change between ticks. Off-screen ones are now skipped and static ones are
 * extracted once per game tick; animated ones (ticked preview block entities, banners, ticking preview entities) still
 * every frame.
 */
public class PreviewRenderStateCacheTest
{
    private static final int FRAMES_PER_TICK = 6;
    private static final int TICKS = 50;

    @Test
    public void staticVisibleStatesAreExtractedOncePerTickNotPerFrame()
    {
        final PreviewRenderStateCache<Object, Object> cache = new PreviewRenderStateCache<>();
        final List<Object> blockEntities = owners(40);
        final Map<Object, Integer> extracts = new IdentityHashMap<>();
        final Function<Object, Object> extractor = counting(extracts);

        for (int tick = 0; tick < TICKS; tick++)
        {
            final Map<Object, Object> firstFrame = new IdentityHashMap<>();
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++)
            {
                for (final Object be : blockEntities)
                {
                    final Object state = cache.state(be, true, tick, false, extractor);
                    if (frame == 0)
                    {
                        firstFrame.put(be, state);
                    }
                    else
                    {
                        assertSame("same state reused within a tick", firstFrame.get(be), state);
                    }
                }
            }
        }

        for (final Object be : blockEntities)
        {
            assertEquals("extracts of one static block entity over " + TICKS + " ticks", TICKS, (int) extracts.get(be));
        }
        assertEquals(40 * TICKS, cache.extracts());
    }

    @Test
    public void culledOwnersAreNeverExtractedAndDrawNothing()
    {
        final PreviewRenderStateCache<Object, Object> cache = new PreviewRenderStateCache<>();
        final List<Object> owners = owners(20);
        final Map<Object, Integer> extracts = new IdentityHashMap<>();
        final Function<Object, Object> extractor = counting(extracts);

        for (int tick = 0; tick < TICKS; tick++)
        {
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++)
            {
                for (int i = 0; i < owners.size(); i++)
                {
                    final boolean visible = i % 2 == 0;
                    final Object state = cache.state(owners.get(i), visible, tick, i % 4 == 2, extractor);
                    if (!visible)
                    {
                        assertNull("an off-screen block entity is submitted", state);
                    }
                }
            }
        }

        for (int i = 1; i < owners.size(); i += 2)
        {
            assertNull("off-screen owner " + i + " was extracted", extracts.get(owners.get(i)));
        }
    }

    @Test
    public void animatedStatesAreStillExtractedEveryFrame()
    {
        final PreviewRenderStateCache<Object, Object> cache = new PreviewRenderStateCache<>();
        final Object beacon = new Object();
        final Map<Object, Integer> extracts = new IdentityHashMap<>();
        final Function<Object, Object> extractor = counting(extracts);

        for (int tick = 0; tick < TICKS; tick++)
        {
            Object previous = null;
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++)
            {
                final Object state = cache.state(beacon, true, tick, true, extractor);
                if (previous != null && state == previous)
                {
                    throw new AssertionError("an animated state was reused within a tick");
                }
                previous = state;
            }
        }
        assertEquals(TICKS * FRAMES_PER_TICK, (int) extracts.get(beacon));
    }

    @Test
    public void nothingToDrawIsRememberedTooAndClearForcesAFreshExtract()
    {
        final PreviewRenderStateCache<Object, Object> cache = new PreviewRenderStateCache<>();
        final Object noRenderer = new Object();
        final int[] calls = {0};
        for (int frame = 0; frame < FRAMES_PER_TICK; frame++)
        {
            assertNull(cache.state(noRenderer, true, 7L, false, owner -> {
                calls[0]++;
                return null;
            }));
        }
        assertEquals(1, calls[0]);

        final Object chest = new Object();
        final Object first = cache.state(chest, true, 7L, false, owner -> new Object());
        assertSame(first, cache.state(chest, true, 7L, false, owner -> new Object()));
        cache.clear();
        final Object fresh = new Object();
        assertSame(fresh, cache.state(chest, true, 7L, false, owner -> fresh));

        // coming back on screen within the same tick reuses what was extracted before it left
        cache.state(chest, false, 7L, false, owner -> new Object());
        assertSame(fresh, cache.state(chest, true, 7L, false, owner -> new Object()));
    }

    private static List<Object> owners(final int count)
    {
        final List<Object> owners = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            owners.add(new Object());
        }
        return owners;
    }

    private static Function<Object, Object> counting(final Map<Object, Integer> extracts)
    {
        return owner -> {
            extracts.merge(owner, 1, Integer::sum);
            return new Object();
        };
    }
}
