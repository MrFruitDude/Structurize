package com.ldtteam.structurize.event;

import com.ldtteam.structurize.blockentities.ModBlockEntities;
import com.ldtteam.structurize.client.*;
import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.client.model.OverlaidModelLoader;
import com.ldtteam.structurize.client.model.TagSubstitutionItemModel;
import com.ldtteam.structurize.items.ItemStackTooltip;
import com.ldtteam.structurize.storage.ClientStructurePackLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.ldtteam.structurize.api.util.constant.Constants;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

public class ClientLifecycleSubscriber
{
    /**
     * Registers Structurize's overlay pipelines; unregistered pipelines crash the client on their first draw.
     *
     * @param event event
     */
    @SubscribeEvent
    public static void onRegisterRenderPipelines(final RegisterRenderPipelinesEvent event)
    {
        com.ldtteam.structurize.client.rendertask.util.RenderTypes.pipelines().forEach(event::registerPipeline);
    }

    /**
     * Called when client app is initialized.
     *
     * @param event event
     */
    @SubscribeEvent
    public static void onClientInit(final FMLClientSetupEvent event)
    {
        // Minecraft is initialized by FMLClientSetupEvent; starting discovery from
        // the mod constructor can observe a null client and permanently skip all
        // local structure packs.
        ClientStructurePackLoader.onClientLoading();
    }

    /**
     * Registers the blueprint renderer cache reset with the sorted client reload listener list
     * (upstream #855; registering on the resource manager directly is deprecated in NeoForge).
     *
     * @param event event
     */
    @SubscribeEvent
    public static void onAddReloadListeners(final AddClientReloadListenersEvent event)
    {
        event.addListener(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "blueprint_render_cache"), new SimplePreparableReloadListener<>()
        {
            @Override
            protected Object prepare(final ResourceManager manager, final ProfilerFiller profiler)
            {
                return new Object();
            }

            @Override
            protected void apply(final Object source, final ResourceManager manager, final ProfilerFiller profiler)
            {
                Log.getLogger().debug("Clearing blueprint renderer cache.");
                BlueprintHandler.getInstance().clearCache();
            }
        });
    }

    @SubscribeEvent
    public static void registerModelLoaders(final ModelEvent.RegisterLoaders event)
    {
        event.register(Identifier.fromNamespaceAndPath("structurize", "overlaid"), new OverlaidModelLoader());
    }

    /**
     * Item model type of the tag substitution anchor, which also draws the absorbed block (1.21 drew it from the BEWLR).
     *
     * @param event event
     */
    @SubscribeEvent
    public static void registerItemModels(final RegisterItemModelsEvent event)
    {
        event.register(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tag_substitution"), TagSubstitutionItemModel.Unbaked.MAP_CODEC);
    }

    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(ModBlockEntities.TAG_SUBSTITUTION.get(), TagSubstitutionRenderer::new);
    }

    @SubscribeEvent
    public static void registerTooltips(final RegisterClientTooltipComponentFactoriesEvent event)
    {
        event.register(ItemStackTooltip.class, ClientItemStackTooltip::new);
    }

    @SubscribeEvent
    public static void registerKeys(final RegisterKeyMappingsEvent event)
    {
        ModKeyMappings.register(event);
    }

}
