package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyDecorativeBlockEntityCompatTest
{
    private static CompoundTag stale()
    {
        final CompoundTag tile = new CompoundTag();
        tile.putString("id", "domum_ornamentum:materially_retexturable");
        tile.putInt("x", 6); tile.putInt("y", 8); tile.putInt("z", 6);
        final CompoundTag textures = new CompoundTag();
        textures.putString("minecraft:block/oak_planks", "minecraft:spruce_planks");
        textures.putString("minecraft:block/clay", "domum_ornamentum:yellow_brick_extra");
        tile.put("textureData", textures);
        return tile;
    }

    @Test public void repairsExactOriginalCourierPayloadWithoutMutatingSource()
    {
        final CompoundTag input = stale(); final String snapshot = input.toString();
        final CompoundTag result = LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:campfire");
        assertNotSame(input, result); assertEquals(snapshot, input.toString());
        assertEquals("minecraft:campfire", result.getStringOr("id", ""));
        final CompoundTag restored = result.copy(); restored.putString("id", input.getStringOr("id", ""));
        assertEquals(input, restored);
    }

    @Test public void repairsEachAuditedCookingBlockWithoutChangingFields()
    {
        for (final String[] mapping : new String[][] {{"minecraft:campfire", "minecraft:campfire"},
          {"minecraft:soul_campfire", "minecraft:campfire"}, {"minecraft:smoker", "minecraft:smoker"},
          {"minecraft:furnace", "minecraft:furnace"}})
        {
            final CompoundTag input = stale();
            final CompoundTag textures = new CompoundTag();
            if (mapping[0].equals("minecraft:soul_campfire")) {
                textures.putString("minecraft:block/oak_planks", "minecraft:spruce_planks");
                textures.putString("minecraft:block/clay", "minecraft:oxidized_cut_copper");
            } else if (mapping[0].equals("minecraft:smoker")) {
                textures.putString("minecraft:block/oak_planks", "minecraft:cyan_terracotta");
            } else if (mapping[0].equals("minecraft:furnace")) {
                textures.putString("minecraft:block/oak_planks", "minecraft:light_blue_terracotta");
            } else {
                textures.putString("minecraft:block/oak_planks", "minecraft:spruce_planks");
                textures.putString("minecraft:block/clay", "domum_ornamentum:yellow_brick_extra");
            }
            input.put("textureData", textures); final String snapshot = input.toString();
            final CompoundTag result = LegacyDecorativeBlockEntityCompat.rewrite(input, mapping[0]);
            assertNotSame(input, result); assertEquals(snapshot, input.toString());
            assertEquals(mapping[1], result.getStringOr("id", ""));
            final CompoundTag restored = result.copy(); restored.putString("id", input.getStringOr("id", ""));
            assertEquals(input, restored);
        }
    }

    @Test public void removesOnlyAuditedDecorativeBedRecordAndPreservesSource()
    {
        final CompoundTag input = stale(); final CompoundTag textures = new CompoundTag();
        textures.putString("minecraft:block/oak_planks", "minecraft:gray_wool"); input.put("textureData", textures);
        final String snapshot = input.toString();
        assertNull(LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:light_gray_bed"));
        assertEquals(snapshot, input.toString());
        input.put("Items", new ListTag());
        assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:light_gray_bed"));
        assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:red_bed"));
    }

    @Test public void preservesValidDomumAndUnrelatedBlocks()
    {
        final CompoundTag input = stale();
        for (final String block : new String[] {"domum_ornamentum:plain", "minecraft:blast_furnace", "minecraft:chest", "minecraft:air"})
            assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, block));
    }

    @Test public void preservesNativeCampfireCookingContents()
    {
        final CompoundTag input = stale(); input.putString("id", "minecraft:campfire");
        input.put("Items", new ListTag()); input.putIntArray("CookingTimes", new int[] {1, 2, 3, 4});
        assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:campfire"));
    }

    @Test public void refusesToReinterpretUnknownOrInventoryPayloads()
    {
        for (final String key : new String[] {"Items", "components", "CookingTimes", "unknown_extension"})
        {
            final CompoundTag input = stale(); input.putString(key, "must be preserved");
            assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:campfire"));
        }
    }

    @Test public void preservesMalformedOrIncompleteDecorationData()
    {
        final CompoundTag missing = stale(); missing.remove("textureData");
        assertSame(missing, LegacyDecorativeBlockEntityCompat.rewrite(missing, "minecraft:campfire"));
        final CompoundTag invalid = stale(); invalid.putString("textureData", "invalid");
        assertSame(invalid, LegacyDecorativeBlockEntityCompat.rewrite(invalid, "minecraft:campfire"));
        final CompoundTag empty = stale(); empty.put("textureData", new CompoundTag());
        assertSame(empty, LegacyDecorativeBlockEntityCompat.rewrite(empty, "minecraft:campfire"));
        final CompoundTag malformed = stale(); final CompoundTag values = new CompoundTag(); values.putInt("test", 1); malformed.put("textureData", values);
        assertSame(malformed, LegacyDecorativeBlockEntityCompat.rewrite(malformed, "minecraft:campfire"));
    }

    @Test public void preservesUnrecognizedOrMalformedTextureMapsForEveryRepairFamily()
    {
        for (final String block : new String[] {"minecraft:campfire", "minecraft:soul_campfire", "minecraft:smoker", "minecraft:furnace", "minecraft:light_gray_bed"})
        {
            for (final String[] pair : new String[][] {
                {"minecraft:block/oak_planks", "invalid identifier!"},
                {"unknown:texture", "minecraft:gray_wool"},
                {"minecraft:block/oak_planks", "minecraft:red_wool"},
                {"minecraft:block/oak_planks", "unknown:nonexistent_material"}})
            {
                final CompoundTag input = stale(); final CompoundTag textures = new CompoundTag();
                textures.putString(pair[0], pair[1]); input.put("textureData", textures);
                final String snapshot = input.toString();
                assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, block));
                assertEquals(snapshot, input.toString());
            }
        }
    }

    @Test public void preservesNonIntegerCoordinatesEvenWithAuditedBedTextures()
    {
        final CompoundTag input = stale(); final CompoundTag textures = new CompoundTag();
        textures.putString("minecraft:block/oak_planks", "minecraft:gray_wool"); input.put("textureData", textures);
        input.putString("x", "6");
        assertSame(input, LegacyDecorativeBlockEntityCompat.rewrite(input, "minecraft:light_gray_bed"));
    }
}
