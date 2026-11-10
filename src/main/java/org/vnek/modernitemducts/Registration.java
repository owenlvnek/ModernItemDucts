package org.vnek.modernitemducts;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.Tier;
import org.vnek.modernitemducts.block.DuctBlock;
import org.vnek.modernitemducts.block.DuctBlockEntity;
import org.vnek.modernitemducts.block.DuctType;
import org.vnek.modernitemducts.block.DuctWeight;
import org.vnek.modernitemducts.item.AttachmentItem;
import org.vnek.modernitemducts.item.DuctBlockItem;
import org.vnek.modernitemducts.item.WrenchItem;
import org.vnek.modernitemducts.menu.AttachmentMenu;

import java.util.EnumMap;
import java.util.Map;

/** All registry objects. */
public final class Registration {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ModernItemDucts.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ModernItemDucts.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModernItemDucts.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ModernItemDucts.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModernItemDucts.MODID);

    public static final Map<DuctType, DeferredHolder<Block, DuctBlock>> DUCTS = new EnumMap<>(DuctType.class);
    public static final Map<DuctType, DeferredHolder<Item, DuctBlockItem>> DUCT_ITEMS = new EnumMap<>(DuctType.class);
    public static final Map<AttachmentKind, Map<Tier, DeferredHolder<Item, AttachmentItem>>> ATTACHMENTS = new EnumMap<>(AttachmentKind.class);

    static {
        for (DuctType type : DuctType.values()) {
            DeferredHolder<Block, DuctBlock> block = BLOCKS.register(type.id, () -> new DuctBlock(type));
            DUCTS.put(type, block);
            DUCT_ITEMS.put(type, ITEMS.register(type.id, () -> new DuctBlockItem(block.get(), new Item.Properties())));
        }
        for (AttachmentKind kind : AttachmentKind.values()) {
            Map<Tier, DeferredHolder<Item, AttachmentItem>> byTier = new EnumMap<>(Tier.class);
            for (Tier tier : Tier.values()) {
                byTier.put(tier, ITEMS.register("item_" + kind.name + "_" + tier.material,
                        () -> new AttachmentItem(kind, tier, new Item.Properties().rarity(tier.rarity))));
            }
            ATTACHMENTS.put(kind, byTier);
        }
    }

    public static final DeferredHolder<Item, WrenchItem> WRENCH = ITEMS.register("duct_wrench", () -> new WrenchItem(new Item.Properties().stacksTo(1)));

    @SuppressWarnings("ConstantConditions")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DuctBlockEntity>> DUCT_BE = BLOCK_ENTITIES.register("item_duct",
            () -> BlockEntityType.Builder.of(DuctBlockEntity::new,
                    DUCTS.values().stream().map(DeferredHolder::get).toArray(Block[]::new)).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<AttachmentMenu>> ATTACHMENT_MENU = MENUS.register("attachment",
            () -> IMenuTypeExtension.create(AttachmentMenu::fromNetwork));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.modernitemducts"))
            .icon(() -> new ItemStack(DUCT_ITEMS.get(DuctType.FAST).get()))
            .displayItems((params, out) -> {
                for (DuctType type : DuctType.values()) {
                    for (DuctWeight w : DuctWeight.values()) out.accept(DuctBlockItem.withWeight(DUCT_ITEMS.get(type).get(), w));
                }
                out.accept(WRENCH.get());
                for (AttachmentKind kind : AttachmentKind.values()) {
                    for (Tier tier : Tier.values()) out.accept(ATTACHMENTS.get(kind).get(tier).get());
                }
            })
            .build());

    private Registration() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
    }

    public static AttachmentItem attachmentItem(AttachmentKind kind, Tier tier) {
        return ATTACHMENTS.get(kind).get(tier).get();
    }
}
