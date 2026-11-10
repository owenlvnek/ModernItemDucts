package org.vnek.modernitemducts.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.Tier;
import org.vnek.modernitemducts.block.DuctBlock;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import javax.annotation.Nullable;
import java.util.List;

/** Item form of a servo / retriever / filter. Right-click a duct (or a block next to one) to attach. */
public class AttachmentItem extends Item {
    public final AttachmentKind kind;
    public final Tier tier;

    public AttachmentItem(AttachmentKind kind, Tier tier, Properties props) {
        super(props);
        this.kind = kind;
        this.tier = tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos clicked = ctx.getClickedPos();
        DuctBlockEntity duct = null;
        Direction face = null;
        if (level.getBlockEntity(clicked) instanceof DuctBlockEntity be) {
            duct = be;
            face = DuctBlock.targetFace(be, ctx.getClickLocation().subtract(clicked.getX(), clicked.getY(), clicked.getZ()),
                    ctx.getClickedFace());
        } else if (level.getBlockEntity(clicked.relative(ctx.getClickedFace())) instanceof DuctBlockEntity be) {
            duct = be;
            face = ctx.getClickedFace().getOpposite();
        }
        if (duct == null || face == null) return InteractionResult.PASS;
        if (duct.getAttachment(face) != null) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            duct.setAttachment(face, new Attachment(kind, tier));
            Player player = ctx.getPlayer();
            if (player == null || !player.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
            level.playSound(null, duct.getBlockPos(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.modernitemducts." + kind.name).withStyle(ChatFormatting.GRAY));
        if (kind.isActive()) {
            tooltip.add(Component.translatable("tooltip.modernitemducts.stats",
                    tier.maxItems, tier.interval / 20.0, tier.speed).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.modernitemducts.slots", tier.filterSlots()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
