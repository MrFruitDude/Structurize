package com.ldtteam.structurize.client;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.config.ClientConfiguration;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;

/**
 * FX1: the ghost performance switches, read every frame so a bench can flip them in a running client by editing the
 * client config. A system property, when set, overrides the config value:
 * <ul>
 * <li>{@code structurize.ghost.mode} = auto | unlit | legacy (config {@code ghost_mode}, default auto)</li>
 * <li>{@code structurize.ghost.prewarm} = true | false (config {@code ghost_prewarm}, default true)</li>
 * <li>{@code structurize.ghost.extractCache} = true | false (config {@code ghost_extract_cache}, default true)</li>
 * </ul>
 */
final class GhostSettings
{
    static final String PREWARM_PROPERTY = "structurize.ghost.prewarm";
    static final String EXTRACT_CACHE_PROPERTY = "structurize.ghost.extractCache";

    private GhostSettings()
    {
    }

    static GhostRenderMode mode()
    {
        final ClientConfiguration client = Structurize.getConfig().getClient();
        return GhostRenderMode.parse(System.getProperty(GhostRenderMode.PROPERTY), client == null ? GhostRenderMode.AUTO : client.ghostMode.get());
    }

    /**
     * Prewarming tessellates on a worker thread, which is only safe while the preview has a static light level: with
     * light level -1 the preview's light engine reads the live world's light, which must stay on the render thread.
     */
    static boolean prewarm()
    {
        final ClientConfiguration client = Structurize.getConfig().getClient();
        return client != null && flag(PREWARM_PROPERTY, client.ghostPrewarm) && client.rendererLightLevel.get() >= 0;
    }

    static boolean extractCache()
    {
        final ClientConfiguration client = Structurize.getConfig().getClient();
        return client == null || flag(EXTRACT_CACHE_PROPERTY, client.ghostExtractCache);
    }

    private static boolean flag(final String property, final BooleanValue config)
    {
        final String value = System.getProperty(property);
        return value == null ? config.get() : Boolean.parseBoolean(value.trim());
    }
}
