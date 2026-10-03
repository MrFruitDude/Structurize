package com.ldtteam.structurize.client.compat;

import com.ldtteam.structurize.api.util.Log;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Soft Iris integration: tells Iris which shader-pack program each of Structurize's custom render pipelines uses.
 * <p>
 * Without an assignment Iris logs "Missing program ... in override list" and draws the pipeline with the vanilla
 * shader, which a deferred shader pack overwrites or fogs away, so the colony border and other overlays vanish at
 * distance. Iris is optional: this class only touches Iris through reflection and does nothing unless the "iris" mod
 * is loaded, so no Iris class is ever linked when it is absent.
 */
public final class IrisPipelineCompat
{
    private static final String IRIS_MOD_ID = "iris";
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_PROGRAM = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final String IRIS_SHADOW_PROGRAM = "net.irisshaders.iris.api.v0.IrisShadowProgram";

    /**
     * Iris refuses a second assignment of the same pipeline, so only ever assign once.
     */
    private static boolean assigned = false;

    private IrisPipelineCompat()
    {
    }

    /**
     * Assigns each pipeline its Iris program, if Iris is installed.
     *
     * @param programs pipeline to IrisProgram constant name (e.g. "BASIC")
     */
    public static synchronized void assignPipelines(final Map<RenderPipeline, String> programs)
    {
        if (assigned || !ModList.get().isLoaded(IRIS_MOD_ID))
        {
            return;
        }
        assigned = true;

        final Object api;
        final Method assign;
        final Method assignShadow;
        final Class<?> programClass;
        final Object shadowProgram;
        try
        {
            final Class<?> apiClass = Class.forName(IRIS_API);
            programClass = Class.forName(IRIS_PROGRAM);
            final Class<?> shadowProgramClass = Class.forName(IRIS_SHADOW_PROGRAM);
            api = apiClass.getMethod("getInstance").invoke(null);
            assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
            assignShadow = apiClass.getMethod("assignPipelineShadow", RenderPipeline.class, shadowProgramClass);
            shadowProgram = programConstant(shadowProgramClass, "SHADOW");
        }
        catch (final ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            Log.getLogger().warn("Iris is loaded but has no pipeline assignment API; Structurize overlays keep Iris' default handling", e);
            return;
        }

        int done = 0;
        for (final Map.Entry<RenderPipeline, String> entry : programs.entrySet())
        {
            try
            {
                assign.invoke(api, entry.getKey(), programConstant(programClass, entry.getValue()));
                // Entity renderers (the citizen status icon) also draw during Iris' shadow pass, which has its own
                // override list; without an entry there Iris again reports the pipeline as missing.
                assignShadow.invoke(api, entry.getKey(), shadowProgram);
                done++;
            }
            catch (final ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                Log.getLogger().warn("Could not assign Iris program {} to pipeline {}", entry.getValue(), entry.getKey().getLocation(), e);
            }
        }
        Log.getLogger().info("Assigned Iris programs to {} of {} Structurize render pipelines", done, programs.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object programConstant(final Class<?> programClass, final String name)
    {
        return Enum.valueOf((Class) programClass, name);
    }

    /**
     * FX1: Iris' API instance and its isShaderPackInUse method, resolved once; null when Iris is absent or unusable.
     */
    private static volatile Object shaderApi;
    private static volatile Method shaderPackInUse;
    private static volatile boolean shaderApiResolved = false;

    /**
     * FX1: whether an Iris shader pack is active right now. False when Iris is not installed. Cheap enough to call per
     * frame (one reflective call once resolved); any Iris failure turns it permanently false.
     */
    public static boolean isShaderPackInUse()
    {
        if (!shaderApiResolved)
        {
            resolveShaderApi();
        }
        final Method method = shaderPackInUse;
        if (method == null)
        {
            return false;
        }
        try
        {
            return (Boolean) method.invoke(shaderApi);
        }
        catch (final ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            Log.getLogger().warn("Iris isShaderPackInUse failed; the blueprint ghost assumes no shader pack from now on", e);
            shaderPackInUse = null;
            return false;
        }
    }

    private static synchronized void resolveShaderApi()
    {
        if (shaderApiResolved)
        {
            return;
        }
        try
        {
            if (ModList.get() != null && ModList.get().isLoaded(IRIS_MOD_ID))
            {
                final Class<?> apiClass = Class.forName(IRIS_API);
                shaderApi = apiClass.getMethod("getInstance").invoke(null);
                shaderPackInUse = apiClass.getMethod("isShaderPackInUse");
            }
        }
        catch (final ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            Log.getLogger().warn("Iris is loaded but its shader-pack state is unreadable; the blueprint ghost assumes no shader pack", e);
            shaderPackInUse = null;
        }
        shaderApiResolved = true;
    }
}
