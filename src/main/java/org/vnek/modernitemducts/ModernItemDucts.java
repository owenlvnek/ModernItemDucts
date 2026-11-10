package org.vnek.modernitemducts;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.vnek.modernitemducts.network.DuctManager;

/**
 * Entry point for Modern Item Ducts (NeoForge).
 * <p>
 * Everything item-logistics related lives in {@code network} (packets, routing, per-level manager),
 * the block / block entity live in {@code block}, attachment logic in {@code attachment}.
 */
@Mod(ModernItemDucts.MODID)
public class ModernItemDucts {
    public static final String MODID = "modernitemducts";

    public ModernItemDucts(IEventBus modBus, ModContainer container) {
        Registration.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modBus.addListener(ModernItemDucts::registerCapabilities);
        NeoForge.EVENT_BUS.register(DuctManager.Events.class);
    }

    /** Ducts expose an item handler on faces where they visually connect (see DuctBlockEntity#handlerFor). */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Registration.DUCT_BE.get(), (be, side) -> be.handlerFor(side));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
