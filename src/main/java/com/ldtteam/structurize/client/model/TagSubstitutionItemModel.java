package com.ldtteam.structurize.client.model;

import com.google.common.base.Suppliers;
import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.ldtteam.structurize.client.CapturedBlockGeometry;
import com.ldtteam.structurize.items.ItemTagSubstitution;
import com.mojang.math.Transformation;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Item model of the tag substitution anchor: the anchor's own model plus, like 1.21's item renderer, the block it has absorbed
 * drawn just inside it (block model with the absorbed block entity's model data and tints).
 */
public final class TagSubstitutionItemModel implements ItemModel
{
    /** 1.21: scale 0.995 and move 0.0025 so the absorbed block sits just inside the anchor frame. */
    private static final Matrix4fc INSET = new Matrix4f().translate(0.0025F, 0.0025F, 0.0025F).scale(0.995F);

    private final ItemQuads anchorQuads;
    private final Supplier<Vector3fc[]> extents;
    private final ModelRenderProperties properties;
    private final Matrix4fc transformation;
    private final Matrix4fc insetTransformation;
    private final boolean animated;

    private TagSubstitutionItemModel(final QuadCollection quads, final ModelRenderProperties properties, final Matrix4fc transformation)
    {
        this.anchorQuads = ItemQuads.split(quads.getAll());
        this.extents = Suppliers.memoize(() -> CuboidItemModelWrapper.computeExtents(quads.getAll()));
        this.properties = properties;
        this.transformation = transformation;
        this.insetTransformation = new Matrix4f(transformation).mul(INSET);
        this.animated = quads.hasMaterialFlag(BakedQuad.FLAG_ANIMATED);
    }

    @Override
    public void update(final ItemStackRenderState output,
        final ItemStack item,
        final ItemModelResolver resolver,
        final ItemDisplayContext displayContext,
        @Nullable final ClientLevel level,
        @Nullable final ItemOwner owner,
        final int seed)
    {
        output.appendModelIdentityElement(this);
        final ItemStackRenderState.LayerRenderState anchor = output.newLayer();
        anchor.setExtents(this.extents);
        anchor.setLocalTransform(this.transformation);
        this.properties.applyToLayer(anchor, displayContext);
        anchor.setQuads(this.anchorQuads);
        if (this.animated)
        {
            output.setAnimated();
        }

        if (!(item.getItem() instanceof ItemTagSubstitution anchorItem))
        {
            return;
        }
        final BlockEntityTagSubstitution.ReplacementBlock replacement = anchorItem.getAbsorbedBlock(item);
        final CapturedBlockGeometry geometry = CapturedBlockGeometry.collect(replacement,
            level,
            owner == null ? BlockPos.ZERO : BlockPos.containing(owner.position()),
            false);
        if (geometry.parts().isEmpty())
        {
            return;
        }

        final List<BakedQuad> quads = new ArrayList<>();
        for (final BlockStateModelPart part : geometry.parts())
        {
            for (final Direction direction : Direction.values())
            {
                quads.addAll(part.getQuads(direction));
            }
            quads.addAll(part.getQuads(null));
        }
        // GUI item rendering is cached per identity: the absorbed block and its data decide what is drawn.
        output.appendModelIdentityElement(replacement.getBlockState());
        output.appendModelIdentityElement(replacement.getBlockEntityTag());
        final ItemStackRenderState.LayerRenderState captured = output.newLayer();
        for (final int tint : geometry.tints())
        {
            captured.tintLayers().add(tint);
            output.appendModelIdentityElement(tint);
        }
        captured.setExtents(this.extents);
        captured.setLocalTransform(this.insetTransformation);
        this.properties.applyToLayer(captured, displayContext);
        captured.setQuads(ItemQuads.split(quads));
        for (final BakedQuad quad : quads)
        {
            if (quad.materialInfo().sprite().contents().isAnimated())
            {
                output.setAnimated();
                break;
            }
        }
    }

    public record Unbaked(Identifier model, Optional<Transformation> transformation) implements ItemModel.Unbaked
    {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model),
                    Transformation.EXTENDED_CODEC.optionalFieldOf("transformation").forGetter(Unbaked::transformation))
                .apply(i, Unbaked::new));

        @Override
        public void resolveDependencies(final ResolvableModel.Resolver resolver)
        {
            resolver.markDependency(this.model);
        }

        @Override
        public ItemModel bake(final ItemModel.BakingContext context, final Matrix4fc transformation)
        {
            final ModelBaker baker = context.blockModelBaker();
            final ResolvedModel resolvedModel = baker.getModel(this.model);
            final TextureSlots textureSlots = resolvedModel.getTopTextureSlots();
            final QuadCollection quads = resolvedModel.bakeTopGeometry(textureSlots, baker, BlockModelRotation.IDENTITY);
            final ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, resolvedModel, textureSlots);
            return new TagSubstitutionItemModel(quads, properties, Transformation.compose(transformation, this.transformation));
        }

        @Override
        public MapCodec<Unbaked> type()
        {
            return MAP_CODEC;
        }
    }
}
