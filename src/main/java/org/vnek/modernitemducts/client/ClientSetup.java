package org.vnek.modernitemducts.client;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import org.vnek.modernitemducts.ModernItemDucts;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.AttachmentKind;

/**
 * Client-only registration (mod event bus). The dense / vacuum duct item icons are chosen by the
 * item model definitions in assets/modernitemducts/items (block_state select), so no code is needed.
 */
@EventBusSubscriber(modid = ModernItemDucts.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    /** Attachment cap models drawn by {@link DuctRenderer}: [kind][state] with state 0 idle, 1 active, 2 clogged. */
    @SuppressWarnings("unchecked")
    private static final StandaloneModelKey<BlockStateModelPart>[][] KEYS = new StandaloneModelKey[AttachmentKind.values().length][3];

    static {
        for (AttachmentKind kind : AttachmentKind.values()) {
            for (int s = 0; s < 3; s++) {
                String path = modelPath(kind, s);
                KEYS[kind.ordinal()][s] = new StandaloneModelKey<>(() -> ModernItemDucts.MODID + ":" + path);
            }
        }
    }

    private static String modelPath(AttachmentKind kind, int state) {
        if (kind == AttachmentKind.FILTER) return "block/attachment/item_filter";
        String s = switch (state) { case 1 -> "active"; case 2 -> "clogged"; default -> "idle"; };
        return "block/attachment/item_" + kind.name + "_" + s;
    }

    public static StandaloneModelKey<BlockStateModelPart> attachmentModel(AttachmentKind kind, int state) {
        return KEYS[kind.ordinal()][state];
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
    public static void onRegisterModels(ModelEvent.RegisterStandalone event) {
        for (AttachmentKind kind : AttachmentKind.values()) {
            for (int s = 0; s < 3; s++) {
                // Registering the same model id twice (filters share one model) is fine: distinct keys.
                event.register(KEYS[kind.ordinal()][s],
                        SimpleUnbakedStandaloneModel.simpleModelWrapper(ModernItemDucts.id(modelPath(kind, s))));
            }
        }
    }
}
