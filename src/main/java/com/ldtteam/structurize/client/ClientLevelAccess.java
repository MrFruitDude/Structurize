package com.ldtteam.structurize.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The client's level, typed as {@link Level}.
 * <p>Common classes call this instead of {@code Minecraft.getInstance().level}: passing the {@code ClientLevel} on as a
 * {@code Level} makes the JVM verifier load {@code ClientLevel}, which a dedicated server lacks, so the whole calling class
 * failed to load there. Since 26.x {@code @OnlyIn} no longer strips such methods. Only call this on the client.
 */
public final class ClientLevelAccess
{
    private ClientLevelAccess()
    {
    }

    @Nullable
    public static Level level()
    {
        return Minecraft.getInstance().level;
    }
}
