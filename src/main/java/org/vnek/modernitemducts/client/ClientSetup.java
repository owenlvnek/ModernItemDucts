package org.vnek.modernitemducts.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.vnek.modernitemducts.ModernItemDucts;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.item.DuctBlockItem;

/** Client-only registration (mod event bus). */
@EventBusSubscriber(modid = ModernItemDucts.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    /** Attachment cap models drawn by {@link DuctRenderer}: [kind][state] with state 0 idle, 1 active, 2 clogged. */
    public static ModelResourceLocation attachmentModel(AttachmentKind kind, int state) {
        String path;
        if (kind == AttachmentKind.FILTER) {
            path = "block/attachment/item_filter";
        } else {
            String s = switch (state) { case 1 -> "active"; case 2 -> "clogged"; default -> "idle"; };
            path = "block/attachment/item_" + kind.name + "_" + s;
        }
        return ModelResourceLocation.standalone(ModernItemDucts.id(path));
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation weight = ModernItemDucts.id("weight");
            Registration.DUCT_ITEMS.values().forEach(item -> ItemProperties.register(item.get(), weight,
                    (stack, level, entity, seed) -> DuctBlockItem.weightOf(stack).ordinal() * 0.5f));
        });
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.ATTACHMENT_MENU.get(), AttachmentScreen::new);
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
