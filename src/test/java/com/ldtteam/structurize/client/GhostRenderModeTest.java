package com.ldtteam.structurize.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * FX1 #4: under an Iris shader pack the ghost must leave the lit BLOCK-format path (Iris widens every BLOCK vertex and
 * computes tangents per quad) for the unlit path by default, while without a shader pack nothing changes; the switch
 * accepts explicit values so the bench can A/B each path.
 */
public class GhostRenderModeTest
{
    @Test
    public void autoDrawsUnlitOnlyWhileAShaderPackIsInUse()
    {
        assertEquals(GhostRenderMode.UNLIT, GhostRenderMode.effective(GhostRenderMode.AUTO, true));
        assertEquals(GhostRenderMode.LEGACY, GhostRenderMode.effective(GhostRenderMode.AUTO, false));
    }

    @Test
    public void explicitModesWinOverTheShaderPackState()
    {
        assertEquals(GhostRenderMode.UNLIT, GhostRenderMode.effective(GhostRenderMode.UNLIT, false));
        assertEquals(GhostRenderMode.UNLIT, GhostRenderMode.effective(GhostRenderMode.UNLIT, true));
        assertEquals(GhostRenderMode.LEGACY, GhostRenderMode.effective(GhostRenderMode.LEGACY, true));
        assertEquals(GhostRenderMode.LEGACY, GhostRenderMode.effective(GhostRenderMode.LEGACY, false));
    }

    @Test
    public void propertyValuesParseCaseInsensitivelyAndFallBack()
    {
        assertEquals(GhostRenderMode.UNLIT, GhostRenderMode.parse("unlit", GhostRenderMode.AUTO));
        assertEquals(GhostRenderMode.LEGACY, GhostRenderMode.parse(" Legacy ", GhostRenderMode.AUTO));
        assertEquals(GhostRenderMode.AUTO, GhostRenderMode.parse("AUTO", GhostRenderMode.LEGACY));
        assertEquals(GhostRenderMode.UNLIT, GhostRenderMode.parse(null, GhostRenderMode.UNLIT));
        assertEquals(GhostRenderMode.LEGACY, GhostRenderMode.parse("persistent", GhostRenderMode.LEGACY));
    }
}
