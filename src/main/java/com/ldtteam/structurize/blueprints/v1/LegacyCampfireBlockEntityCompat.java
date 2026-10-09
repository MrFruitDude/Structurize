package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import java.util.Set;

/** Repairs the stale decorative-only Domum tile found over a vanilla campfire in old blueprints. */
final class LegacyCampfireBlockEntityCompat
{
    private static final Set<String> DECORATIVE_KEYS = Set.of("id", "x", "y", "z", "textureData");

    private LegacyCampfireBlockEntityCompat() { }

    static CompoundTag rewrite(final CompoundTag tile, final String blockId)
    {
        if (!"minecraft:campfire".equals(blockId)
              || !"domum_ornamentum:materially_retexturable".equals(tile.getStringOr("id", ""))
              || !tile.keySet().equals(DECORATIVE_KEYS))
        {
            return tile;
        }
        final var textures = tile.getCompound("textureData");
        if (textures.isEmpty() || textures.get().isEmpty()
              || textures.get().keySet().stream().anyMatch(key -> textures.get().getString(key).filter(value -> !value.isBlank()).isEmpty()))
        {
            return tile;
        }
        // A campfire cannot render Domum materials. Keep every source field, including the stale texture data,
        // and select the native block entity for the unchanged campfire block. Never reinterpret inventory payloads.
        final CompoundTag corrected = tile.copy();
        corrected.putString("id", "minecraft:campfire");
        return corrected;
    }
}
