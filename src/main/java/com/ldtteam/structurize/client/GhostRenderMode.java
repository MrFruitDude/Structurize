package com.ldtteam.structurize.client;

import java.util.Locale;

/**
 * FX1: how the blueprint ghost's cached mesh is drawn.
 * <ul>
 * <li>{@link #AUTO}: {@link #UNLIT} while an Iris shader pack is in use, else {@link #LEGACY}.</li>
 * <li>{@link #UNLIT}: position/uv/colour only, through Structurize's own POSITION_TEX_COLOR pipelines (Iris program
 * TEXTURED). Iris does not widen that vertex format, so no per-quad tangent/normal/mid-block work runs.</li>
 * <li>{@link #LEGACY}: the vanilla moving-block render types (lit, BLOCK format), as before FX1.</li>
 * </ul>
 * The client config value {@code ghost_mode} selects it; the system property {@value #PROPERTY} overrides the config.
 */
public enum GhostRenderMode
{
    AUTO,
    UNLIT,
    LEGACY;

    /**
     * System property that overrides the config, e.g. {@code -Dstructurize.ghost.mode=legacy}.
     */
    public static final String PROPERTY = "structurize.ghost.mode";

    /**
     * @param configured      the configured mode
     * @param shaderPackInUse whether an Iris shader pack is active this frame
     * @return the concrete mode to draw with this frame, never {@link #AUTO}
     */
    public static GhostRenderMode effective(final GhostRenderMode configured, final boolean shaderPackInUse)
    {
        return switch (configured)
        {
            case UNLIT -> UNLIT;
            case LEGACY -> LEGACY;
            case AUTO -> shaderPackInUse ? UNLIT : LEGACY;
        };
    }

    /**
     * @param value    a mode name in any case, or null
     * @param fallback returned when the value is null or names no mode
     * @return the parsed mode
     */
    public static GhostRenderMode parse(final String value, final GhostRenderMode fallback)
    {
        if (value == null)
        {
            return fallback;
        }
        try
        {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (final IllegalArgumentException e)
        {
            return fallback;
        }
    }
}
