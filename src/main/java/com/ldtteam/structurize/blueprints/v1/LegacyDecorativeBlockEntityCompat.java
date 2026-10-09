package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Repairs source decorative-only Domum tiles paired with known vanilla cooking blocks or obsolete beds. */
final class LegacyDecorativeBlockEntityCompat
{
    private static final Set<String> DECORATIVE_KEYS = Set.of("id", "x", "y", "z", "textureData");

    // Exact texture maps observed in the 24 audited source records. Unknown decoration is never discarded.
    private static final Map<String, Set<Map<String, String>>> AUDITED_TEXTURES = Map.ofEntries(
        Map.entry("minecraft:campfire", Set.of(
            Map.of("minecraft:block/clay", "domum_ornamentum:black_brick_extra", "minecraft:block/oak_planks", "minecraft:spruce_planks"),
            Map.of("minecraft:block/clay", "domum_ornamentum:green_brick_extra", "minecraft:block/oak_planks", "minecraft:spruce_planks"),
            Map.of("minecraft:block/clay", "domum_ornamentum:white_brick_extra", "minecraft:block/oak_planks", "minecraft:spruce_planks"),
            Map.of("minecraft:block/clay", "domum_ornamentum:yellow_brick_extra", "minecraft:block/oak_planks", "minecraft:spruce_planks")
        )),
        Map.entry("minecraft:furnace", Set.of(
            Map.of("minecraft:block/oak_planks", "minecraft:light_blue_terracotta")
        )),
        Map.entry("minecraft:light_gray_bed", Set.of(
            Map.of("minecraft:block/oak_planks", "minecraft:gray_wool")
        )),
        Map.entry("minecraft:smoker", Set.of(
            Map.of("minecraft:block/clay", "domum_ornamentum:brick_extra", "minecraft:block/oak_planks", "minecraft:spruce_planks"),
            Map.of("minecraft:block/clay", "minecraft:stripped_oak_log", "minecraft:block/oak_planks", "minecraft:spruce_planks"),
            Map.of("minecraft:block/clay", "minecraft:stripped_oak_log", "minecraft:block/oak_planks", "minecraft:stripped_spruce_log"),
            Map.of("minecraft:block/oak_planks", "minecraft:cyan_terracotta")
        )),
        Map.entry("minecraft:soul_campfire", Set.of(
            Map.of("minecraft:block/clay", "minecraft:oxidized_cut_copper", "minecraft:block/oak_planks", "minecraft:spruce_planks")
        ))
    );

    private LegacyDecorativeBlockEntityCompat() { }

    static CompoundTag rewrite(final CompoundTag tile, final String blockId)
    {
        final String nativeId = switch (blockId)
        {
            case "minecraft:campfire", "minecraft:soul_campfire" -> "minecraft:campfire";
            case "minecraft:smoker" -> "minecraft:smoker";
            case "minecraft:furnace" -> "minecraft:furnace";
            case "minecraft:light_gray_bed" -> ""; // Native 26.x beds have no block entity.
            default -> null;
        };
        if (nativeId == null || !"domum_ornamentum:materially_retexturable".equals(tile.getStringOr("id", ""))
              || !tile.keySet().equals(DECORATIVE_KEYS))
        {
            return tile;
        }
        final var textures = tile.getCompound("textureData");
        if (textures.isEmpty() || tile.getInt("x").isEmpty() || tile.getInt("y").isEmpty() || tile.getInt("z").isEmpty())
        {
            return tile;
        }
        final Map<String, String> materials = new HashMap<>();
        for (final String key : textures.get().keySet())
        {
            final var material = textures.get().getString(key);
            if (material.isEmpty()) return tile;
            materials.put(key, material.get());
        }
        if (!AUDITED_TEXTURES.get(blockId).contains(materials)) return tile;
        // Native 26.x removed bed block entities. This exact decorative-only source record has no inventory,
        // cooking or other semantic data; the bed state and original source asset remain intact.
        if (nativeId.isEmpty()) return null;
        // Vanilla cooking blocks cannot render Domum materials. Keep every source field, including stale textures,
        // and select the correct native block entity for the unchanged block. Never reinterpret inventory payloads.
        final CompoundTag corrected = tile.copy();
        corrected.putString("id", nativeId);
        return corrected;
    }
}
