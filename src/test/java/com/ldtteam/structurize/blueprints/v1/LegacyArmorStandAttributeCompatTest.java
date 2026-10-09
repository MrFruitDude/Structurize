package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LegacyArmorStandAttributeCompatTest
{
    private static final String LEGACY_GRAVITY = "forge:entity_gravity";
    private static final String NATIVE_GRAVITY = "minecraft:gravity";
    private static final String LEGACY_STEP_HEIGHT = "forge:step_height_addition";
    private static final String NATIVE_STEP_HEIGHT = "minecraft:step_height";

    @Test
    public void rewritesRawAndCanonicalFormsWithoutChangingValuesOrModifiers()
    {
        final CompoundTag entity = armorStand();
        final ListTag raw = new ListTag();
        final ListTag canonical = new ListTag();
        raw.add(rawAttribute(LEGACY_GRAVITY, 0.08D, modifier("gravity_marker")));
        raw.add(rawAttribute(LEGACY_STEP_HEIGHT, 0.0D));
        canonical.add(canonicalAttribute(LEGACY_GRAVITY, 0.08D, modifier("gravity_marker")));
        canonical.add(canonicalAttribute(LEGACY_STEP_HEIGHT, 0.0D));
        entity.put("Attributes", raw);
        entity.put("attributes", canonical);
        entity.putString("CustomName", "kept");
        final String inputSnapshot = entity.toString();

        final LegacyArmorStandAttributeCompat.Result result = LegacyArmorStandAttributeCompat.rewrite(entity);

        assertTrue(result.diagnostics().isEmpty());
        assertNotSame(entity, result.entity());
        assertEquals(inputSnapshot, entity.toString());
        assertEquals("kept", result.entity().getStringOr("CustomName", ""));
        assertEquals(NATIVE_GRAVITY, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals(NATIVE_STEP_HEIGHT, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(1).getStringOr("id", ""));
        assertEquals(NATIVE_GRAVITY, result.entity().getListOrEmpty("Attributes").getCompoundOrEmpty(0).getStringOr("Name", ""));
        assertEquals(NATIVE_STEP_HEIGHT, result.entity().getListOrEmpty("Attributes").getCompoundOrEmpty(1).getStringOr("Name", ""));
        assertEquals(0.08D, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(0).getDoubleOr("base", -1), 0.0D);
        assertEquals(0.0D, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(1).getDoubleOr("base", -1), 0.0D);
        assertEquals("gravity_marker", result.entity().getListOrEmpty("attributes")
          .getCompoundOrEmpty(0).getListOrEmpty("modifiers").getCompoundOrEmpty(0).getStringOr("marker", ""));
    }

    @Test
    public void leavesExistingNativeConflictUntouchedButConvertsIndependentSafeKey()
    {
        final CompoundTag entity = armorStand();
        final ListTag attributes = new ListTag();
        attributes.add(canonicalAttribute(LEGACY_GRAVITY, 0.08D));
        attributes.add(canonicalAttribute(NATIVE_GRAVITY, 0.08D));
        attributes.add(canonicalAttribute(LEGACY_STEP_HEIGHT, 0.0D));
        entity.put("attributes", attributes);

        final LegacyArmorStandAttributeCompat.Result result = LegacyArmorStandAttributeCompat.rewrite(entity);

        assertEquals(LEGACY_GRAVITY, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals(NATIVE_GRAVITY, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(1).getStringOr("id", ""));
        assertEquals(NATIVE_STEP_HEIGHT, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(2).getStringOr("id", ""));
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("native key already exists"));
    }

    @Test
    public void leavesNonzeroOrModifiedStepAdditionUntouched()
    {
        for (final CompoundTag step : new CompoundTag[] {canonicalAttribute(LEGACY_STEP_HEIGHT, 0.5D),
          canonicalAttribute(LEGACY_STEP_HEIGHT, 0.0D, modifier("must_not_convert"))})
        {
            final CompoundTag entity = armorStand();
            final ListTag attributes = new ListTag();
            attributes.add(canonicalAttribute(LEGACY_GRAVITY, 0.08D));
            attributes.add(step);
            entity.put("attributes", attributes);

            final LegacyArmorStandAttributeCompat.Result result = LegacyArmorStandAttributeCompat.rewrite(entity);

            assertEquals(NATIVE_GRAVITY, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(0).getStringOr("id", ""));
            assertEquals(LEGACY_STEP_HEIGHT, result.entity().getListOrEmpty("attributes").getCompoundOrEmpty(1).getStringOr("id", ""));
            assertEquals(1, result.diagnostics().size());
            assertTrue(result.diagnostics().get(0).contains("left unchanged"));
        }
    }

    @Test
    public void leavesInvalidOrInconsistentPayloadUntouched()
    {
        final CompoundTag invalidBase = armorStand();
        final ListTag invalidAttributes = new ListTag();
        final CompoundTag invalidGravity = new CompoundTag();
        invalidGravity.putString("id", LEGACY_GRAVITY);
        invalidGravity.putString("base", "0.08");
        invalidAttributes.add(invalidGravity);
        invalidBase.put("attributes", invalidAttributes);
        final LegacyArmorStandAttributeCompat.Result invalidResult = LegacyArmorStandAttributeCompat.rewrite(invalidBase);
        assertSame(invalidBase, invalidResult.entity());
        assertTrue(invalidResult.diagnostics().get(0).contains("base is missing or is not a double"));

        final CompoundTag inconsistent = armorStand();
        final ListTag raw = new ListTag();
        raw.add(rawAttribute(LEGACY_GRAVITY, 0.08D));
        final ListTag canonical = new ListTag();
        canonical.add(canonicalAttribute(LEGACY_GRAVITY, 0.09D));
        inconsistent.put("Attributes", raw);
        inconsistent.put("attributes", canonical);
        final LegacyArmorStandAttributeCompat.Result inconsistentResult = LegacyArmorStandAttributeCompat.rewrite(inconsistent);
        assertSame(inconsistent, inconsistentResult.entity());
        assertTrue(inconsistentResult.diagnostics().get(0).contains("raw and canonical bases differ"));
    }

    @Test
    public void leavesNonFiniteGravityBasesUntouched()
    {
        for (final double base : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        {
            final CompoundTag entity = armorStand();
            final ListTag attributes = new ListTag();
            attributes.add(canonicalAttribute(LEGACY_GRAVITY, base));
            entity.put("attributes", attributes);

            final LegacyArmorStandAttributeCompat.Result result = LegacyArmorStandAttributeCompat.rewrite(entity);

            assertSame(entity, result.entity());
            assertEquals(1, result.diagnostics().size());
            assertTrue(result.diagnostics().get(0).contains("base is not finite"));
        }
    }

    @Test
    public void nativeConflictInEitherRepresentationBlocksBothCopies()
    {
        final CompoundTag entity = armorStand();
        final ListTag raw = new ListTag();
        raw.add(rawAttribute(LEGACY_GRAVITY, 0.08D));
        raw.add(rawAttribute(LEGACY_STEP_HEIGHT, 0.0D));
        final ListTag canonical = new ListTag();
        canonical.add(canonicalAttribute(NATIVE_GRAVITY, 0.08D));
        canonical.add(canonicalAttribute(LEGACY_GRAVITY, 0.08D));
        canonical.add(canonicalAttribute(LEGACY_STEP_HEIGHT, 0.0D));
        entity.put("Attributes", raw);
        entity.put("attributes", canonical);
        final String original = entity.toString();

        final LegacyArmorStandAttributeCompat.Result result = LegacyArmorStandAttributeCompat.rewrite(entity);

        assertEquals(original, result.entity().toString());
        assertSame(entity, result.entity());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("native key already exists"));
    }

    @Test
    public void rewritesAreIdempotentAndOtherEntityTypesAreUntouched()
    {
        final CompoundTag entity = armorStand();
        final ListTag attributes = new ListTag();
        attributes.add(canonicalAttribute(LEGACY_GRAVITY, 0.08D));
        entity.put("attributes", attributes);
        final CompoundTag rewritten = LegacyArmorStandAttributeCompat.rewrite(entity).entity();
        final LegacyArmorStandAttributeCompat.Result repeated = LegacyArmorStandAttributeCompat.rewrite(rewritten);
        assertSame(rewritten, repeated.entity());
        assertTrue(repeated.diagnostics().isEmpty());

        final CompoundTag zombie = new CompoundTag();
        zombie.putString("id", "minecraft:zombie");
        zombie.put("attributes", attributes.copy());
        final LegacyArmorStandAttributeCompat.Result otherEntity = LegacyArmorStandAttributeCompat.rewrite(zombie);
        assertSame(zombie, otherEntity.entity());
        assertTrue(otherEntity.diagnostics().isEmpty());
        assertFalse(otherEntity.entity().getListOrEmpty("attributes").isEmpty());
    }

    private static CompoundTag armorStand()
    {
        final CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        return entity;
    }

    private static CompoundTag canonicalAttribute(final String id, final double base, final CompoundTag... modifiers)
    {
        final CompoundTag attribute = new CompoundTag();
        attribute.putString("id", id);
        attribute.putDouble("base", base);
        if (modifiers.length > 0)
        {
            final ListTag list = new ListTag();
            for (final CompoundTag modifier : modifiers)
            {
                list.add(modifier);
            }
            attribute.put("modifiers", list);
        }
        return attribute;
    }

    private static CompoundTag rawAttribute(final String id, final double base, final CompoundTag... modifiers)
    {
        final CompoundTag attribute = new CompoundTag();
        attribute.putString("Name", id);
        attribute.putDouble("Base", base);
        if (modifiers.length > 0)
        {
            final ListTag list = new ListTag();
            for (final CompoundTag modifier : modifiers)
            {
                list.add(modifier);
            }
            attribute.put("Modifiers", list);
        }
        return attribute;
    }

    private static CompoundTag modifier(final String marker)
    {
        final CompoundTag modifier = new CompoundTag();
        modifier.putString("marker", marker);
        return modifier;
    }
}
