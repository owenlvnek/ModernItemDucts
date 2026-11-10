package org.vnek.modernitemducts.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.vnek.modernitemducts.block.DuctBlock;
import org.vnek.modernitemducts.block.DuctWeight;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Duct block item. The routing weight travels in the vanilla {@code BlockStateTag}, so vanilla
 * placement applies it and the loot table's {@code copy_state} function puts it back on the drop.
 */
public class DuctBlockItem extends BlockItem {
    public DuctBlockItem(Block block, Properties props) {
        super(block, props);
    }

    public static ItemStack withWeight(Item item, DuctWeight weight) {
        ItemStack stack = new ItemStack(item);
        if (weight != DuctWeight.NORMAL) {
            CompoundTag state = new CompoundTag();
            state.putString(DuctBlock.WEIGHT.getName(), weight.getSerializedName());
            stack.getOrCreateTag().put(BLOCK_STATE_TAG, state);
        }
        return stack;
    }

    public static DuctWeight weightOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return DuctWeight.NORMAL;
        String s = tag.getCompound(BLOCK_STATE_TAG).getString(DuctBlock.WEIGHT.getName());
        for (DuctWeight w : DuctWeight.values()) if (w.getSerializedName().equals(s)) return w;
        return DuctWeight.NORMAL;
    }

    @Override
    public Component getName(ItemStack stack) {
        DuctWeight w = weightOf(stack);
        if (w == DuctWeight.NORMAL) return super.getName(stack);
        return Component.translatable("item.modernitemducts.weighted." + w.getSerializedName(), super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.modernitemducts.duct." + weightOf(stack).getSerializedName())
                .withStyle(ChatFormatting.GRAY));
    }
}
