package com.ldtteam.structurize.blockentities;

import com.ldtteam.structurize.api.util.RegistryLookups;
import com.ldtteam.structurize.api.util.ItemStackUtils;
import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.RotationMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import com.ldtteam.structurize.api.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The block entity for BlockTagSubstitution
 */
public class BlockEntityTagSubstitution extends BlockEntity implements IBlueprintDataProviderBE
{
    /**
     * The schematic name of the block.
     */
    private String schematicName = "";

    /**
     * Corner positions of schematic, relative to te pos.
     */
    private BlockPos corner1 = BlockPos.ZERO;
    private BlockPos corner2 = BlockPos.ZERO;

    /**
     * Map of block positions relative to TE pos and string tags
     */
    private Map<BlockPos, List<String>> tagPosMap = new HashMap<>();

    /**
     * Structure pack name.
     */
    private String packName;

    /**
     * Structure pack path.
     */
    private String inPackPath;

    /**
     * Replacement block.
     */
    private ReplacementBlock replacement = new ReplacementBlock(new CompoundTag());

    public BlockEntityTagSubstitution(final BlockPos pos, final BlockState state)
    {
        super( ModBlockEntities.TAG_SUBSTITUTION.get(), pos, state);
    }

    @Override
    public String getSchematicName()
    {
        return schematicName;
    }

    @Override
    public void setSchematicName(final String name)
    {
        schematicName = name;
    }

    @Override
    public Map<BlockPos, List<String>> getPositionedTags()
    {
        return tagPosMap;
    }

    @Override
    public void setPositionedTags(final Map<BlockPos, List<String>> positionedTags)
    {
        tagPosMap = positionedTags;
        setChanged();
    }

    @Override
    public Tuple<BlockPos, BlockPos> getSchematicCorners()
    {
        if (corner1 == BlockPos.ZERO || corner2 == BlockPos.ZERO)
        {
            return new Tuple<>(worldPosition, worldPosition);
        }

        return new Tuple<>(corner1, corner2);
    }

    @Override
    public void setSchematicCorners(final BlockPos pos1, final BlockPos pos2)
    {
        corner1 = pos1;
        corner2 = pos2;
    }

    @Override
    public BlockPos getTilePos()
    {
        return worldPosition;
    }

    /**
     * @return the replacement block details
     */
    @NotNull
    public ReplacementBlock getReplacement()
    {
        return this.replacement;
    }

    @Override
    protected void loadAdditional(@NotNull final ValueInput input)
    {
        super.loadAdditional(input);
        loadSchematicData(input);
        // Single-nested like blueprints and Blueprint#rotateWithMirror; ReplacementBlock(tag) expects the key on the root.
        this.replacement = input.read(ReplacementBlock.TAG_REPLACEMENT, CompoundTag.CODEC)
            .map(data -> {
                if (data.contains(ReplacementBlock.TAG_REPLACEMENT))
                {
                    return new ReplacementBlock(data);
                }
                final CompoundTag root = new CompoundTag();
                root.put(ReplacementBlock.TAG_REPLACEMENT, data);
                return new ReplacementBlock(root);
            })
            .orElse(new ReplacementBlock());
    }

    @Override
    protected void saveAdditional(@NotNull final ValueOutput output)
    {
        super.saveAdditional(output);
        storeSchematicData(output);
        final CompoundTag replacementData = this.replacement.write(new CompoundTag());
        if (replacementData.contains(ReplacementBlock.TAG_REPLACEMENT))
        {
            output.store(ReplacementBlock.TAG_REPLACEMENT, CompoundTag.CODEC, replacementData.getCompoundOrEmpty(ReplacementBlock.TAG_REPLACEMENT));
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setPackName(final String packName)
    {
        this.packName = packName;
    }

    @Override
    public void setBlueprintPath(final String inPackPath)
    {
        this.inPackPath = inPackPath;
    }

    @Override
    public String getPackName()
    {
        return packName;
    }

    @Override
    public String getBlueprintPath()
    {
        return inPackPath;
    }

    @NotNull
    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries)
    {
        return saveWithFullMetadata(registries);
    }

    @Override
    public void onDataPacket(final Connection net, final ValueInput input)
    {
        loadAdditional(input);
    }

    /**
     * Storage for information about the replacement block, if any.
     */
    public static class ReplacementBlock
    {
        private static final String TAG_REPLACEMENT = "replacement";
        private static final String TAG_CAPTURED_BLOCK = "captured_block";

        private final BlockState blockstate;
        private final CompoundTag blockentitytag;
        private final ItemStack itemstack;

        @Nullable private BlockEntity cachedBlockentity;

        private ReplacementBlock()
        {
            this.blockstate = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            this.blockentitytag = new CompoundTag();
            this.itemstack = ItemStack.EMPTY;
        }

        /**
         * Construct
         * @param blockstate the block state
         * @param blockentity the block entity, if any
         * @param itemstack the item stack
         */
        public ReplacementBlock(@NotNull final BlockState blockstate,
                                @Nullable final BlockEntity blockentity,
                                @NotNull final ItemStack itemstack)
        {
            this.blockstate = blockstate;
            this.blockentitytag = blockentity == null
                ? new CompoundTag()
                : blockentity.saveWithFullMetadata(blockentity.getLevel() == null ? RegistryLookups.current() : blockentity.getLevel().registryAccess());
            this.itemstack = itemstack;
        }

        /**
         * Construct
         * @param blockstate the block state
         * @param blockentity the block entity tag, if any
         * @param itemstack the item stack
         */
        public ReplacementBlock(@NotNull final BlockState blockstate,
                                @Nullable final CompoundTag blockentity,
                                @NotNull final ItemStack itemstack)
        {
            this.blockstate = blockstate;
            this.blockentitytag = blockentity == null ? new CompoundTag() : blockentity.copy();
            this.itemstack = itemstack;
        }

        /**
         * Construct from tag
         * @param tag the tag to load
         */
        public ReplacementBlock(@NotNull CompoundTag tag)
        {
            final CompoundTag replacement;
            if (!tag.contains(TAG_REPLACEMENT) && tag.contains(TAG_CAPTURED_BLOCK))
            {
                // Structurize 1.21 (#704) layout: captured_block:{state,entity,item}
                final CompoundTag captured = tag.getCompoundOrEmpty(TAG_CAPTURED_BLOCK);
                replacement = new CompoundTag();
                replacement.put("b", captured.getCompoundOrEmpty("state"));
                replacement.put("e", captured.getCompoundOrEmpty("entity"));
                captured.getCompound("item").ifPresent(item -> replacement.put("i", item));
            }
            else
            {
                replacement = tag.getCompoundOrEmpty(TAG_REPLACEMENT);
            }
            this.blockstate = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, modernStateKeys(replacement.getCompoundOrEmpty("b")));
            this.blockentitytag = replacement.getCompoundOrEmpty("e");
            this.itemstack = replacement.contains("i")
                ? ItemStackUtils.getItemStackFromNbt(replacement.getCompoundOrEmpty("i"))
                : ItemStack.EMPTY;
        }

        /**
         * Data written before 26.x stores block states as {Name,Properties}; 26.x NbtUtils reads {id,properties}. Blueprints are
         * data-fixed on load (BlueprintUtil), this covers replacement data reaching us any other way (items, saved block entities).
         */
        private static CompoundTag modernStateKeys(final CompoundTag state)
        {
            if (state.contains("id") || !state.contains("Name"))
            {
                return state;
            }
            final CompoundTag modern = new CompoundTag();
            modern.putString("id", state.getStringOr("Name", ""));
            state.getCompound("Properties").ifPresent(properties -> modern.put("properties", properties));
            return modern;
        }

        /**
         * @return true if there is no replacement block set (assume air)
         */
        public boolean isEmpty()
        {
            return this.blockstate.isAir();
        }

        /**
         * @return the block state
         */
        @NotNull
        public BlockState getBlockState()
        {
            return this.blockstate;
        }

        /**
         * @return the block entity tag
         */
        @NotNull
        public CompoundTag getBlockEntityTag()
        {
            return this.blockentitytag;
        }

        /**
         * @return the item stack
         */
        @NotNull
        public ItemStack getItemStack()
        {
            return this.itemstack;
        }

        /**
         * Creates and loads (once) the replacement block entity, or returns the preloaded one.
         * @param pos the blockpos to use (ignored if already loaded)
         * @return the new or cached entity, or null if there isn't one
         */
        @Nullable
        public BlockEntity getBlockEntity(final BlockPos pos)
        {
            if (this.cachedBlockentity == null)
            {
                this.cachedBlockentity = createBlockEntity(pos);
            }
            return this.cachedBlockentity;
        }

        /**
         * Always creates and loads a new replacement block entity, if needed.
         * @param pos the blockpos to use
         * @return the new entity, or null if there isn't one
         */
        @Nullable
        public BlockEntity createBlockEntity(final BlockPos pos)
        {
            return this.blockentitytag.isEmpty()
                    ? null
                    : BlockEntity.loadStatic(
                        pos,
                        this.blockstate,
                        this.blockentitytag,
                        RegistryLookups.current());
        }

        /**
         * Serialisation
         * @param tag the target tag
         * @return the target tag, for convenience
         */
        @NotNull
        public CompoundTag write(@NotNull CompoundTag tag)
        {
            if (isEmpty())
            {
                tag.remove(TAG_REPLACEMENT);
            }
            else
            {
                final CompoundTag replacement = new CompoundTag();
                replacement.put("b", NbtUtils.writeBlockState(this.blockstate));
                if (this.blockentitytag.isEmpty())
                {
                    replacement.remove("e");
                }
                else
                {
                    replacement.put("e", this.blockentitytag);
                }
                replacement.put("i", ItemStackUtils.writeToNbt(this.itemstack));

                tag.put(TAG_REPLACEMENT, replacement);
            }
            return tag;
        }

        /**
         * Creates a new single-block {@link Blueprint} for the replacement block.
         * @return the blueprint
         */
        @NotNull
        public Blueprint createBlueprint()
        {
            final Blueprint blueprint = new Blueprint((short) 1, (short) 1, (short) 1);
            blueprint.addBlockState(BlockPos.ZERO, getBlockState());
            blueprint.getTileEntities()[0][0][0] = getBlockEntityTag().isEmpty() ? null : getBlockEntityTag().copy();
            return blueprint;
        }

        @NotNull
        @Deprecated(since="1.20", forRemoval=true)
        public BlockEntityTagSubstitution.ReplacementBlock rotateWithMirror(@NotNull final BlockPos pos,
                                                                            @NotNull final Rotation localRotation,
                                                                            @NotNull final Mirror localMirror,
                                                                            @NotNull final Level world)
        {
            final Blueprint blueprint = createBlueprint();
            blueprint.rotateWithMirror(localRotation, localMirror, world);

            final BlockState newBlockState = blueprint.getBlockState(BlockPos.ZERO);
            final CompoundTag newBlockData = blueprint.getTileEntityData(pos, BlockPos.ZERO);
            return new ReplacementBlock(newBlockState, newBlockData, this.getItemStack());
        }

        /**
         * Rotates and mirrors the replacement data, in response to a blueprint containing this replacement block
         * being rotated or mirrored.
         *
         * @param pos the world location for the replacement block
         * @param rotationMirror the relative rotation/mirror
         * @param level the (actual) world
         * @return the new replacement data
         */
        public ReplacementBlock rotateWithMirror(final BlockPos pos, final RotationMirror rotationMirror, final Level level)
        {
            final Blueprint blueprint = createBlueprint();
            blueprint.setRotationMirrorRelative(rotationMirror, level);

            final BlockState newBlockState = blueprint.getBlockState(BlockPos.ZERO);
            final CompoundTag newBlockData = blueprint.getTileEntityData(pos, BlockPos.ZERO);
            return new ReplacementBlock(newBlockState, newBlockData, this.getItemStack());
        }
    }
}
