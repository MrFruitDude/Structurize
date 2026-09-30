package com.ldtteam.structurize.api.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;

/**
 * Registry lookup for code paths that have no level at hand.
 * <p>
 * Item stacks and block entities reference datapack registries (enchantments, banner patterns, trims, ...). Encoding them with
 * only the built-in registries silently drops those components or the whole stack, so always prefer a level's or player's
 * {@code registryAccess()}; use this only where none is available.
 */
public final class RegistryLookups
{
    private static final RegistryAccess BUILT_IN = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private RegistryLookups()
    {
    }

    /**
     * @return the running server's registries, else the connected client's, else only the built-in registries (no world loaded).
     */
    @NotNull
    public static RegistryAccess current()
    {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            return server.registryAccess();
        }
        if (FMLEnvironment.getDist().isClient())
        {
            final RegistryAccess client = ClientLookup.get();
            if (client != null)
            {
                return client;
            }
        }
        return BUILT_IN;
    }

    /**
     * Kept in its own class so dedicated servers never load client classes.
     */
    private static final class ClientLookup
    {
        private static RegistryAccess get()
        {
            final ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection == null ? null : connection.registryAccess();
        }
    }
}
