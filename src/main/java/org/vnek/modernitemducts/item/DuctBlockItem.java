package org.vnek.modernitemducts.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BlockItemStateProperties;
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
import java.util.function.Consumer;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Duct block item. The routing weight travels in the vanilla {@code minecraft:block_state} component, so vanilla
 * placement applies it and the loot table's {@code copy_state} function puts it back on the drop.
 */
public class DuctBlockItem extends BlockItem {
    public DuctBlockItem(Block block, Properties props) {
        super(block, props);
    }

    public static ItemStack withWeight(Item item, DuctWeight weight) {
        ItemStack stack = new ItemStack(item);
        if (weight != DuctWeight.NORMAL) {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(DuctBlock.WEIGHT, weight));
        }
        return stack;
    }

    public static DuctWeight weightOf(ItemStack stack) {
        BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
        if (props == null) return DuctWeight.NORMAL;
        DuctWeight w = props.get(DuctBlock.WEIGHT);
        return w == null ? DuctWeight.NORMAL : w;
    }

    @Override
    public Component getName(ItemStack stack) {
        DuctWeight w = weightOf(stack);
        if (w == DuctWeight.NORMAL) return super.getName(stack);
        return Component.translatable("item.modernitemducts.weighted." + w.getSerializedName(), super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.modernitemducts.duct." + weightOf(stack).getSerializedName())
                .withStyle(ChatFormatting.GRAY));
    }
}
