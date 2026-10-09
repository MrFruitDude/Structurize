package com.ldtteam.structurize.blueprints.v1;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the two legacy Forge armor-stand attribute keys that have a safe
 * native 26.3 representation. Called only after the ENTITY DataFixer.
 */
final class LegacyArmorStandAttributeCompat
{
    private static final String ARMOR_STAND = "minecraft:armor_stand";
    private static final String RAW_ATTRIBUTES = "Attributes";
    private static final String CANONICAL_ATTRIBUTES = "attributes";
    private static final String LEGACY_GRAVITY = "forge:entity_gravity";
    private static final String NATIVE_GRAVITY = "minecraft:gravity";
    private static final String LEGACY_STEP_HEIGHT = "forge:step_height_addition";
    private static final String NATIVE_STEP_HEIGHT = "minecraft:step_height";

    private LegacyArmorStandAttributeCompat()
    {
    }

    static Result rewrite(final CompoundTag entity)
    {
        if (!ARMOR_STAND.equals(entity.getStringOr("id", "")))
        {
            return new Result(entity, List.of());
        }

        final List<AttributeList> lists = new ArrayList<>();
        final List<String> diagnostics = new ArrayList<>();
        final boolean hasRaw = entity.contains(RAW_ATTRIBUTES);
        final boolean hasCanonical = entity.contains(CANONICAL_ATTRIBUTES);
        addList(entity, RAW_ATTRIBUTES, true, hasRaw, lists);
        addList(entity, CANONICAL_ATTRIBUTES, false, hasCanonical, lists);

        boolean hasLegacyAttribute = lists.stream().anyMatch(AttributeList::hasLegacyAttribute);
        if (!hasLegacyAttribute)
        {
            if ((hasRaw || hasCanonical) && lists.stream().anyMatch(list -> list.invalid()))
            {
                return new Result(entity, List.of("attribute list is not a compound list; legacy alias skipped"));
            }
            return new Result(entity, List.of());
        }

        if (lists.stream().anyMatch(list -> list.invalid()))
        {
            return new Result(entity, List.of("attribute list is not a compound list; legacy alias skipped"));
        }
        if (lists.isEmpty())
        {
            return new Result(entity, List.of("DataFixer produced no attribute list; legacy alias skipped"));
        }

        final StringBuilder reason = new StringBuilder();
        if (!canRewrite(lists, LEGACY_GRAVITY, NATIVE_GRAVITY, false, reason))
        {
            diagnostics.add(LEGACY_GRAVITY + " left unchanged: " + reason);
        }
        reason.setLength(0);
        if (!canRewrite(lists, LEGACY_STEP_HEIGHT, NATIVE_STEP_HEIGHT, true, reason))
        {
            diagnostics.add(LEGACY_STEP_HEIGHT + " left unchanged: " + reason);
        }

        // Raw and canonical lists represent the same entity state after the DataFixer.
        // If either representation conflicts, leave both copies untouched.
        if (hasRaw && hasCanonical && !diagnostics.isEmpty())
        {
            return new Result(entity, diagnostics);
        }

        final boolean rewriteGravity = hasLegacy(lists, LEGACY_GRAVITY)
                                         && diagnostics.stream().noneMatch(value -> value.startsWith(LEGACY_GRAVITY));
        final boolean rewriteStepHeight = hasLegacy(lists, LEGACY_STEP_HEIGHT)
                                            && diagnostics.stream().noneMatch(value -> value.startsWith(LEGACY_STEP_HEIGHT));
        if (!rewriteGravity && !rewriteStepHeight)
        {
            return new Result(entity, diagnostics);
        }

        final CompoundTag rewrittenEntity = entity.copy();
        for (final AttributeList attributeList : lists)
        {
            final ListTag rewrittenList = attributeList.tag().copy();
            for (int index = 0; index < rewrittenList.size(); index++)
            {
                final CompoundTag attribute = rewrittenList.getCompoundOrEmpty(index);
                final String id = attribute.getStringOr(attributeList.idKey(), "");
                if (rewriteGravity && LEGACY_GRAVITY.equals(id))
                {
                    attribute.putString(attributeList.idKey(), NATIVE_GRAVITY);
                }
                else if (rewriteStepHeight && LEGACY_STEP_HEIGHT.equals(id))
                {
                    attribute.putString(attributeList.idKey(), NATIVE_STEP_HEIGHT);
                }
            }
            rewrittenEntity.put(attributeList.fieldName(), rewrittenList);
        }
        return new Result(rewrittenEntity, diagnostics);
    }

    private static void addList(
      final CompoundTag entity,
      final String fieldName,
      final boolean raw,
      final boolean present,
      final List<AttributeList> lists)
    {
        if (!present)
        {
            return;
        }
        final Tag field = entity.get(fieldName);
        if (!(field instanceof ListTag list) || list.stream().anyMatch(tag -> !(tag instanceof CompoundTag)))
        {
            lists.add(AttributeList.invalid(fieldName));
            return;
        }
        lists.add(new AttributeList(fieldName, raw ? "Name" : "id", raw ? "Base" : "base",
          raw ? "Modifiers" : "modifiers", list, false));
    }

    private static boolean canRewrite(
      final List<AttributeList> lists,
      final String oldId,
      final String newId,
      final boolean requireZeroWithoutModifiers,
      final StringBuilder reason)
    {
        int targetCount = 0;
        int sourceLists = 0;
        Double referenceBase = null;
        int referenceCount = -1;

        for (final AttributeList attributeList : lists)
        {
            int sourceCount = 0;
            for (int index = 0; index < attributeList.tag().size(); index++)
            {
                final CompoundTag attribute = attributeList.tag().getCompoundOrEmpty(index);
                final String id = attribute.getStringOr(attributeList.idKey(), "");
                if (newId.equals(id))
                {
                    targetCount++;
                }
                if (!oldId.equals(id))
                {
                    continue;
                }
                sourceCount++;
                if (!(attribute.get(attributeList.baseKey()) instanceof DoubleTag baseTag))
                {
                    reason.append("base is missing or is not a double");
                    return false;
                }
                final double base = baseTag.value();
                if (!Double.isFinite(base))
                {
                    reason.append("base is not finite");
                    return false;
                }
                if (requireZeroWithoutModifiers)
                {
                    if (base != 0.0D)
                    {
                        reason.append("step-height addition is not exactly zero");
                        return false;
                    }
                    final Tag modifiers = attribute.get(attributeList.modifiersKey());
                    if (modifiers != null && (!(modifiers instanceof ListTag modifierList) || !modifierList.isEmpty()))
                    {
                        reason.append("step-height addition has modifiers");
                        return false;
                    }
                }
                if (referenceBase != null && Double.compare(referenceBase, base) != 0)
                {
                    reason.append("raw and canonical bases differ");
                    return false;
                }
                referenceBase = base;
            }
            if (sourceCount > 0)
            {
                sourceLists++;
            }
            if (referenceCount >= 0 && sourceCount != 0 && sourceCount != referenceCount)
            {
                reason.append("raw and canonical entries differ");
                return false;
            }
            if (sourceCount > 0)
            {
                referenceCount = sourceCount;
            }
        }

        if (targetCount > 0)
        {
            reason.append("native key already exists");
            return false;
        }
        if (sourceLists == 0)
        {
            return true;
        }
        if (referenceCount != 1)
        {
            reason.append("legacy key is duplicated");
            return false;
        }
        return true;
    }

    private static boolean hasLegacy(final List<AttributeList> lists, final String id)
    {
        for (final AttributeList attributeList : lists)
        {
            if (attributeList.invalid())
            {
                continue;
            }
            for (int index = 0; index < attributeList.tag().size(); index++)
            {
                if (id.equals(attributeList.tag().getCompoundOrEmpty(index).getStringOr(attributeList.idKey(), "")))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private record AttributeList(String fieldName, String idKey, String baseKey, String modifiersKey, ListTag tag, boolean invalid)
    {
        private static AttributeList invalid(final String fieldName)
        {
            return new AttributeList(fieldName, "", "", "", new ListTag(), true);
        }

        private boolean hasLegacyAttribute()
        {
            if (invalid)
            {
                return false;
            }
            for (int index = 0; index < tag.size(); index++)
            {
                final String id = tag.getCompoundOrEmpty(index).getStringOr(idKey, "");
                if (LEGACY_GRAVITY.equals(id) || LEGACY_STEP_HEIGHT.equals(id))
                {
                    return true;
                }
            }
            return false;
        }
    }

    record Result(CompoundTag entity, List<String> diagnostics)
    {
    }
}
