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
        final Class<?> programClass;
        try
        {
            final Class<?> apiClass = Class.forName(IRIS_API);
            programClass = Class.forName(IRIS_PROGRAM);
            api = apiClass.getMethod("getInstance").invoke(null);
            assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
        }
        catch (final ReflectiveOperationException | LinkageError e)
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
}
