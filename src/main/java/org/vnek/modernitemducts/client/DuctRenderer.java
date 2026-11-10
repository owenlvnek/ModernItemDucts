package org.vnek.modernitemducts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.block.DuctBlockEntity;

/**
 * Draws attachment caps (tinted per tier) on every duct, and moving packets inside transparent
 * ducts: 0.7x scale, shared slow spin, at most 16 per duct, positions interpolated locally from
 * the last server update (spec §14).
 */
public class DuctRenderer implements BlockEntityRenderer<DuctBlockEntity> {
    private final ItemRenderer items;

    public DuctRenderer(BlockEntityRendererProvider.Context ctx) {
        this.items = ctx.getItemRenderer();
    }

    @Override
    public void render(DuctBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        renderAttachments(be, pose, buffers, light, overlay);
        if (!be.type().opaque && be.getLevel() != null) renderPackets(be, partial, pose, buffers, light);
    }

    private void renderAttachments(DuctBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        for (Direction d : Direction.values()) {
            Attachment a = be.getAttachment(d);
            if (a == null) continue;
            int state = be.isClogged(d) ? 2 : be.isAttachmentActive(d) ? 1 : 0;
            BakedModel model = mc.getModelManager().getModel(ClientSetup.attachmentModel(a.kind, state));
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5);
            switch (d) {
                case SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180));
                case WEST -> pose.mulPose(Axis.YP.rotationDegrees(90));
                case EAST -> pose.mulPose(Axis.YP.rotationDegrees(-90));
                case UP -> pose.mulPose(Axis.XP.rotationDegrees(90));
                case DOWN -> pose.mulPose(Axis.XP.rotationDegrees(-90));
                default -> {}
            }
            pose.translate(-0.5, -0.5, -0.5);
            int c = a.tier.tint;
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()),
                    null, model, ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, light, overlay);
            pose.popPose();
        }
    }

    private void renderPackets(DuctBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (be.clientPackets.isEmpty()) return;
        long gameTime = be.getLevel().getGameTime();
        float seg = be.type().segmentLength;
        float spin = ((gameTime % 240) + partial) * 1.5f;
        int drawn = 0;
        for (DuctBlockEntity.ClientPacket p : be.clientPackets) {
            if (drawn++ >= DuctBlockEntity.CLIENT_PACKET_CAP) break;
            float prog = p.progress() + (gameTime - p.syncTime() + partial) * p.speed();
            if (prog > seg) continue; // the neighbouring duct takes over drawing it
            float f = prog / seg;
            pose.pushPose();
            pose.translate(0.5 + p.dir().getStepX() * f, 0.45 + p.dir().getStepY() * f, 0.5 + p.dir().getStepZ() * f);
            pose.scale(0.7f, 0.7f, 0.7f);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.translate(0, -0.125, 0);
            items.renderStatic(p.stack(), ItemDisplayContext.GROUND, light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(DuctBlockEntity be) {
        return false;
    }

    /** Packets render up to one block beyond the duct. */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(DuctBlockEntity be) {
        return be.renderBounds();
    }
}
