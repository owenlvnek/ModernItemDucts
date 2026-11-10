package org.vnek.modernitemducts.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import org.vnek.modernitemducts.ModernItemDucts;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The mod's own wrench. Any item in one of the common wrench tags works the same way on ducts
 * (see {@link #isWrench}), so players can keep using a wrench from another mod.
 */
public class WrenchItem extends Item {
    /** Our own tag; contains our wrench and can be extended by modpacks. */
    public static final TagKey<Item> OWN_TAG = TagKey.create(Registries.ITEM, ModernItemDucts.id("wrenches"));
    /** Common community conventions (read-only; we never depend on another mod). */
    private static final List<TagKey<Item>> COMMON_TAGS = List.of(
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "tools/wrench")),
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "wrenches")),
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "wrenches")),
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench")));

    public WrenchItem(Properties props) {
        super(props);
    }

    public static boolean isWrench(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof WrenchItem || stack.is(OWN_TAG)) return true;
        for (TagKey<Item> t : COMMON_TAGS) if (stack.is(t)) return true;
        return false;
    }

    /** Let sneak + right-click reach the duct block so it can be dismantled. */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.modernitemducts.wrench").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
