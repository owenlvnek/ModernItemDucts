package org.vnek.modernitemducts.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.vnek.modernitemducts.ModernItemDucts;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.item.DuctBlockItem;

/** Client-only registration (mod event bus). */
@Mod.EventBusSubscriber(modid = ModernItemDucts.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    /** Attachment cap models drawn by {@link DuctRenderer}: [kind][state] with state 0 idle, 1 active, 2 clogged. */
    public static ResourceLocation attachmentModel(AttachmentKind kind, int state) {
        if (kind == AttachmentKind.FILTER) return ModernItemDucts.id("block/attachment/item_filter");
        String s = switch (state) { case 1 -> "active"; case 2 -> "clogged"; default -> "idle"; };
        return ModernItemDucts.id("block/attachment/item_" + kind.name + "_" + s);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(Registration.ATTACHMENT_MENU.get(), AttachmentScreen::new);
            ResourceLocation weight = ModernItemDucts.id("weight");
            Registration.DUCT_ITEMS.values().forEach(item -> ItemProperties.register(item.get(), weight,
                    (stack, level, entity, seed) -> DuctBlockItem.weightOf(stack).ordinal() * 0.5f));
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Registration.DUCT_BE.get(), DuctRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterModels(ModelEvent.RegisterAdditional event) {
        for (AttachmentKind kind : AttachmentKind.values()) {
            for (int s = 0; s < 3; s++) event.register(attachmentModel(kind, s));
        }
    }
}
