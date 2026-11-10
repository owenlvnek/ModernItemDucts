package org.vnek.modernitemducts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws attachment caps (tinted per tier) on every duct, and moving packets inside transparent
 * ducts: 0.7x scale, shared slow spin, at most 16 per duct, positions interpolated locally from
 * the last server update (spec §14).
 * <p>
 * Uses the extract / submit render-state split: everything read from the block entity is copied
 * into a {@link State} on extraction, and {@link #submit} only draws from that copy.
 */
public class DuctRenderer implements BlockEntityRenderer<DuctBlockEntity, DuctRenderer.State> {
    /** Block-atlas cutout with back-face culling (what Sheets#cutoutBlockSheet returned up to 26.1). */
    private static final RenderType CAP_RENDER_TYPE = RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS);

    private final ItemModelResolver itemModels;

    public DuctRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModels = ctx.itemModelResolver();
    }

    /** One cap to draw: which face, which baked model part, which tier colour. */
    record Cap(Direction face, BlockStateModelPart part, int tint) {}

    /** One packet to draw: its interpolated offset from the duct centre and its item model. */
    record Item(float x, float y, float z, ItemStackRenderState model) {}

    public static class State extends BlockEntityRenderState {
        final List<Cap> caps = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        float spin;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DuctBlockEntity be, State state, float partial, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partial, camera, breakProgress);
        state.caps.clear();
        state.items.clear();
        var models = Minecraft.getInstance().getModelManager();
        for (Direction d : Direction.values()) {
            Attachment a = be.getAttachment(d);
            if (a == null) continue;
            int s = be.isClogged(d) ? 2 : be.isAttachmentActive(d) ? 1 : 0;
            BlockStateModelPart part = models.getStandaloneModel(ClientSetup.attachmentModel(a.kind, s));
            if (part != null) state.caps.add(new Cap(d, part, 0xFF000000 | a.tier.tint));
        }
        if (be.type().opaque || be.getLevel() == null || be.clientPackets.isEmpty()) return;

        long gameTime = be.getLevel().getGameTime();
        float seg = be.type().segmentLength;
        state.spin = ((gameTime % 240) + partial) * 1.5f;
        int drawn = 0;
        int seed = (int) be.getBlockPos().asLong();
        for (DuctBlockEntity.ClientPacket p : be.clientPackets) {
            if (drawn++ >= DuctBlockEntity.CLIENT_PACKET_CAP) break;
            float prog = p.progress() + (gameTime - p.syncTime() + partial) * p.speed();
            if (prog > seg) continue; // the neighbouring duct takes over drawing it
            float f = prog / seg;
            ItemStackRenderState model = new ItemStackRenderState();
            itemModels.updateForTopItem(model, p.stack(), ItemDisplayContext.GROUND, be.getLevel(), null, seed + drawn);
            state.items.add(new Item(p.dir().getStepX() * f, p.dir().getStepY() * f, p.dir().getStepZ() * f, model));
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        for (Cap cap : state.caps) {
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5);
            switch (cap.face()) {
                case SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180));
                case WEST -> pose.mulPose(Axis.YP.rotationDegrees(90));
                case EAST -> pose.mulPose(Axis.YP.rotationDegrees(-90));
                case UP -> pose.mulPose(Axis.XP.rotationDegrees(90));
                case DOWN -> pose.mulPose(Axis.XP.rotationDegrees(-90));
                default -> {}
            }
            pose.translate(-0.5, -0.5, -0.5);
            // tintindex 0 faces take the tier colour, untinted faces keep their texture colours
            out.submitBlockModel(pose, CAP_RENDER_TYPE, List.of(cap.part()), new int[]{cap.tint()},
                    state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        for (Item item : state.items) {
            if (item.model().isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.5 + item.x(), 0.45 + item.y(), 0.5 + item.z());
            pose.scale(0.7f, 0.7f, 0.7f);
            pose.mulPose(Axis.YP.rotationDegrees(state.spin));
            pose.translate(0, -0.125, 0);
            item.model().submit(pose, out, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    /** Packets render up to one block beyond the duct. */
    @Override
    public AABB getRenderBoundingBox(DuctBlockEntity be) {
        return be.renderBounds();
    }
}
