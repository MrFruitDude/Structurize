package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyCampfireBlockEntityCompatTest
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
        final CompoundTag result = LegacyCampfireBlockEntityCompat.rewrite(input, "minecraft:campfire");
        assertNotSame(input, result); assertEquals(snapshot, input.toString());
        assertEquals("minecraft:campfire", result.getStringOr("id", ""));
        final CompoundTag restored = result.copy(); restored.putString("id", input.getStringOr("id", ""));
        assertEquals(input, restored);
    }

    @Test public void preservesValidDomumAndUnrelatedBlocks()
    {
        final CompoundTag input = stale();
        for (final String block : new String[] {"domum_ornamentum:plain", "minecraft:soul_campfire", "minecraft:chest", "minecraft:air"})
            assertSame(input, LegacyCampfireBlockEntityCompat.rewrite(input, block));
    }

    @Test public void preservesNativeCampfireCookingContents()
    {
        final CompoundTag input = stale(); input.putString("id", "minecraft:campfire");
        input.put("Items", new ListTag()); input.putIntArray("CookingTimes", new int[] {1, 2, 3, 4});
        assertSame(input, LegacyCampfireBlockEntityCompat.rewrite(input, "minecraft:campfire"));
    }

    @Test public void refusesToReinterpretUnknownOrInventoryPayloads()
    {
        for (final String key : new String[] {"Items", "components", "CookingTimes", "unknown_extension"})
        {
            final CompoundTag input = stale(); input.putString(key, "must be preserved");
            assertSame(input, LegacyCampfireBlockEntityCompat.rewrite(input, "minecraft:campfire"));
        }
    }

    @Test public void preservesMalformedOrIncompleteDecorationData()
    {
        final CompoundTag missing = stale(); missing.remove("textureData");
        assertSame(missing, LegacyCampfireBlockEntityCompat.rewrite(missing, "minecraft:campfire"));
        final CompoundTag invalid = stale(); invalid.putString("textureData", "invalid");
        assertSame(invalid, LegacyCampfireBlockEntityCompat.rewrite(invalid, "minecraft:campfire"));
        final CompoundTag empty = stale(); empty.put("textureData", new CompoundTag());
        assertSame(empty, LegacyCampfireBlockEntityCompat.rewrite(empty, "minecraft:campfire"));
        final CompoundTag malformed = stale(); final CompoundTag values = new CompoundTag(); values.putInt("test", 1); malformed.put("textureData", values);
        assertSame(malformed, LegacyCampfireBlockEntityCompat.rewrite(malformed, "minecraft:campfire"));
    }
}
