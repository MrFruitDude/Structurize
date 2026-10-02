package com.ldtteam.structurize.client.rendertask.util;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Structurize's overlay render types, expressed with the current render pipeline API.
 */
public final class RenderTypes
{
    private RenderTypes()
    {
    }

    /*
     * Depth: since 26.1 the game renders with reversed depth (near = 1, cleared = 0); every vanilla world pipeline tests
     * GREATER_THAN_OR_EQUAL. So 1.21's LEQUAL ("in front of blocks") is GREATER_THAN_OR_EQUAL here and its GREATER
     * ("hidden behind blocks") is LESS_THAN.
     */

    /**
     * Every custom pipeline used below. Since 1.21.5 a pipeline must be registered through RegisterRenderPipelinesEvent
     * before it can be drawn, otherwise the first draw crashes the client ("Failed to find or load pipeline").
     * Declared first so it exists before the render type fields below are initialised.
     */
    private static final List<RenderPipeline> PIPELINES = new ArrayList<>();

    /**
     * Iris shader program (IrisProgram enum constant name) each custom pipeline draws with while a shader pack is active.
     * Iris picks the program whose vertex format matches, so these must name a program that has a POSITION_COLOR /
     * POSITION_TEX variant: BASIC (gbuffers_basic) and TEXTURED (gbuffers_textured). Iris' LINES program expects
     * POSITION_COLOR_NORMAL_LINE_WIDTH and does not fit these pipelines.
     */
    private static final Map<RenderPipeline, String> IRIS_PROGRAMS = new LinkedHashMap<>();

    /**
     * @return all custom pipelines of these render types, for RegisterRenderPipelinesEvent.
     */
    public static List<RenderPipeline> pipelines()
    {
        return Collections.unmodifiableList(PIPELINES);
    }

    /**
     * @return every custom pipeline with the name of the Iris program it should be drawn with under a shader pack.
     */
    public static Map<RenderPipeline, String> irisPrograms()
    {
        return Collections.unmodifiableMap(IRIS_PROGRAMS);
    }

    public static RenderType worldEntityIcon(final Identifier texture)
    {
        return WORLD_ENTITY_ICON.apply(texture);
    }

    public static final RenderType LINES_OUTSIDE_BLOCKS = positionColor(
        "structurize:lines_outside_blocks",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.TRANSLUCENT,
        CompareOp.GREATER_THAN_OR_EQUAL,
        false,
        true,
        1024);

    public static final RenderType LINES_INSIDE_BLOCKS = positionColor(
        "structurize:lines_inside_blocks",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.TRANSLUCENT,
        CompareOp.LESS_THAN,
        false,
        true,
        1024);

    public static final RenderType GLINT_LINES = positionColor(
        "structurize_glint_lines",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.DEBUG_LINES,
        BlendFunction.ADDITIVE,
        CompareOp.ALWAYS_PASS,
        false,
        false,
        1 << 12);

    public static final RenderType GLINT_LINES_WITH_WIDTH = positionColor(
        "structurize_glint_lines_with_width",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.ADDITIVE,
        CompareOp.ALWAYS_PASS,
        true,
        true,
        1 << 13);

    public static final RenderType LINES = positionColor(
        "structurize_lines",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.DEBUG_LINES,
        BlendFunction.TRANSLUCENT,
        CompareOp.GREATER_THAN_OR_EQUAL,
        false,
        false,
        1 << 14);

    public static final RenderType LINES_WITH_WIDTH = positionColor(
        "structurize_lines_with_width",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.TRANSLUCENT,
        CompareOp.GREATER_THAN_OR_EQUAL,
        true,
        true,
        1 << 13);

    public static final RenderType COLORED_TRIANGLES = positionColor(
        "structurize_colored_triangles",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.TRANSLUCENT,
        CompareOp.GREATER_THAN_OR_EQUAL,
        true,
        true,
        1 << 13);

    public static final RenderType COLORED_TRIANGLES_NC_ND = positionColor(
        "structurize_colored_triangles_nc_nd",
        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES,
        BlendFunction.TRANSLUCENT,
        CompareOp.ALWAYS_PASS,
        false,
        false,
        1 << 12);

    private static final RenderPipeline ENTITY_ICON_PIPELINE = register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("structurize", "pipeline/entity_icon"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexShader("core/position_tex")
        .withFragmentShader("core/position_tex")
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
        .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build(), "TEXTURED");

    private static final Function<Identifier, RenderType> WORLD_ENTITY_ICON = Util.memoize(texture -> RenderType.create(
        "structurize:entity_icon",
        RenderSetup.builder(ENTITY_ICON_PIPELINE).withTexture("Sampler0", texture).createRenderSetup()));

    private static RenderPipeline register(final RenderPipeline pipeline, final String irisProgram)
    {
        PIPELINES.add(pipeline);
        IRIS_PROGRAMS.put(pipeline, irisProgram);
        return pipeline;
    }

    private static RenderType positionColor(final String name,
        final com.mojang.renderpearl.api.pipeline.PrimitiveTopology mode,
        final BlendFunction blendFunction,
        final CompareOp depthTest,
        final boolean writeDepth,
        final boolean cull,
        final int bufferSize)
    {
        final RenderPipeline pipeline = register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("structurize", "pipeline/" + name.substring(name.indexOf(':') + 1)))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withColorTargetState(new ColorTargetState(blendFunction))
            .withCull(cull)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(mode)
            .withDepthStencilState(new DepthStencilState(depthTest, writeDepth))
            .build(), "BASIC");
        return RenderType.create(name, RenderSetup.builder(pipeline).createRenderSetup());
    }
}
