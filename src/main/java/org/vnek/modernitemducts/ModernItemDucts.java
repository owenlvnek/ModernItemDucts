package org.vnek.modernitemducts;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.resources.ResourceLocation;
import org.vnek.modernitemducts.network.DuctManager;

/**
 * Entry point for Modern Item Ducts.
 * <p>
 * Everything item-logistics related lives in {@code network} (packets, routing, per-level manager),
 * the block / block entity live in {@code block}, attachment logic in {@code attachment}.
 */
@Mod(ModernItemDucts.MODID)
public class ModernItemDucts {
    public static final String MODID = "modernitemducts";

    public ModernItemDucts() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Registration.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(DuctManager.Events.class);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
