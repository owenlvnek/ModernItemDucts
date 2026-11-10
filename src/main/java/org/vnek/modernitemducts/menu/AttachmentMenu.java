package org.vnek.modernitemducts.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.FilterSettings;
import org.vnek.modernitemducts.attachment.RedstoneMode;
import org.vnek.modernitemducts.attachment.SortMode;
import org.vnek.modernitemducts.attachment.Tier;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import javax.annotation.Nullable;

/**
 * Configuration menu for one attachment: ghost filter slots, flags and numeric settings.
 * Settings travel client -&gt; server as vanilla menu-button clicks (ids below) and
 * server -&gt; client as data slots, so no custom networking is needed.
 */
public class AttachmentMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 204;

    // data slot indices
    public static final int D_FLAGS = 0, D_MAX_OP = 1, D_MAX_STOCK = 2, D_SORT = 3, D_REDSTONE = 4, D_OVERSEND = 5, D_POWERED = 6, D_COUNT = 7;

    // button ids
    public static final int B_FLAG = 0;          // + flag ordinal (0..4)
    public static final int B_SORT_FWD = 10, B_SORT_BACK = 11, B_OVERSEND = 12, B_RS_FWD = 13, B_RS_BACK = 14;
    public static final int B_MAX_OP = 20;       // + step index (see STEPS)
    public static final int B_MAX_STOCK = 30;
    public static final int[] STEPS = {1, -1, 4, -4, 16, -16};

    public final BlockPos pos;
    public final Direction face;
    public final AttachmentKind kind;
    public final Tier tier;
    private final Container filterSlots;
    private final ContainerData data;
    @Nullable private final DuctBlockEntity duct;   // server side only
    @Nullable private final Attachment attachment;  // server side only
    public final int gridLeft, gridTop;

    private AttachmentMenu(int id, Inventory inv, BlockPos pos, Direction face, AttachmentKind kind, Tier tier,
                           Container filterSlots, ContainerData data, @Nullable DuctBlockEntity duct, @Nullable Attachment attachment) {
        super(Registration.ATTACHMENT_MENU.get(), id);
        this.pos = pos;
        this.face = face;
        this.kind = kind;
        this.tier = tier;
        this.filterSlots = filterSlots;
        this.data = data;
        this.duct = duct;
        this.attachment = attachment;

        gridLeft = 88 - tier.cols * 9;
        gridTop = switch (tier.rows) { case 1 -> 38; case 2 -> 29; default -> 20; };
        for (int r = 0; r < tier.rows; r++) {
            for (int c = 0; c < tier.cols; c++) {
                addSlot(new GhostSlot(filterSlots, r * tier.cols + c, gridLeft + 1 + c * 18, gridTop + 1 + r * 18));
            }
        }
        int invTop = HEIGHT - 82;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, 9 + r * 9 + c, 8 + c * 18, invTop + r * 18));
        }
        for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, invTop + 58));
        addDataSlots(data);
    }

    public static void writeOpenData(FriendlyByteBuf buf, BlockPos pos, Direction face, Attachment att) {
        buf.writeBlockPos(pos);
        buf.writeByte(face.ordinal());
        buf.writeByte(att.kind.ordinal());
        buf.writeByte(att.tier.ordinal());
    }

    public static AttachmentMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Direction face = Direction.from3DDataValue(buf.readByte());
        AttachmentKind kind = AttachmentKind.byId(buf.readByte());
        Tier tier = Tier.byId(buf.readByte());
        return new AttachmentMenu(id, inv, pos, face, kind, tier, new SimpleContainer(tier.filterSlots()),
                new SimpleContainerData(D_COUNT), null, null);
    }

    public int get(int dataIndex) {
        return data.get(dataIndex);
    }

    public boolean flag(FilterSettings.Flag f) {
        return (data.get(D_FLAGS) & f.bit()) != 0;
    }

    public int filterSlotCount() {
        return tier.filterSlots();
    }

    // ------------------------------------------------------------------ settings changes (server)

    @Override
    public boolean clickMenuButton(Player player, int id) {
        Attachment a = attachment;
        if (a == null || duct == null) return false;
        if (id >= B_FLAG && id < B_FLAG + FilterSettings.Flag.values().length) {
            FilterSettings.Flag f = FilterSettings.Flag.values()[id - B_FLAG];
            a.filter.set(f, !a.filter.get(f));
        } else if (id == B_SORT_FWD || id == B_SORT_BACK) {
            if (!a.kind.isActive()) return false;
            a.setSortMode(a.sortMode().cycle(id == B_SORT_FWD));
        } else if (id == B_OVERSEND) {
            if (a.kind != AttachmentKind.FILTER) return false;
            a.setPreventOverSend(!a.preventOverSend());
        } else if (id == B_RS_FWD || id == B_RS_BACK) {
            if (!a.kind.isActive()) return false;
            a.setRedstoneMode(a.redstoneMode().cycle(id == B_RS_FWD));
        } else if (id >= B_MAX_OP && id < B_MAX_OP + STEPS.length) {
            if (!a.kind.isActive()) return false;
            a.setMaxPerOp(a.maxPerOp() + STEPS[id - B_MAX_OP]);
        } else if (id >= B_MAX_STOCK && id < B_MAX_STOCK + STEPS.length) {
            if (a.kind == AttachmentKind.SERVO) return false;
            a.setMaxStock(a.maxStock() + STEPS[id - B_MAX_STOCK]);
        } else {
            return false;
        }
        duct.attachmentSettingsChanged();
        return true;
    }

    // ------------------------------------------------------------------ ghost slots

    @Override
    public void clicked(int slotId, int button, ContainerInput type, Player player) {
        if (slotId >= 0 && slotId < filterSlotCount()) {
            Slot slot = slots.get(slotId);
            if (type == ContainerInput.QUICK_MOVE) {
                slot.set(ItemStack.EMPTY);
            } else if (type == ContainerInput.PICKUP) {
                ItemStack carried = getCarried();
                slot.set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            }
            return;
        }
        super.clicked(slotId, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < filterSlotCount()) {
            slots.get(index).set(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        ItemStack stack = slots.get(index).getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int free = -1;
        for (int i = 0; i < filterSlotCount(); i++) {
            ItemStack g = filterSlots.getItem(i);
            if (g.isEmpty()) {
                if (free < 0) free = i;
            } else if (ItemStack.isSameItem(g, stack) && g.getDamageValue() == stack.getDamageValue()) {
                return ItemStack.EMPTY; // already present
            }
        }
        if (free >= 0) filterSlots.setItem(free, stack.copyWithCount(1));
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return !(slot instanceof GhostSlot);
    }

    @Override
    public boolean stillValid(Player player) {
        if (duct == null) return true;
        return !duct.isRemoved() && duct.getAttachment(face) == attachment
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    /** Ghost slot: displays a 1-item copy, never holds real items. */
    public static class GhostSlot extends Slot {
        GhostSlot(Container c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override public boolean mayPickup(Player player) { return false; }

        @Override public boolean mayPlace(ItemStack stack) { return false; }

        @Override public int getMaxStackSize() { return 1; }
    }

    // ------------------------------------------------------------------ server-side provider

    public static class Provider implements MenuProvider {
        private final DuctBlockEntity duct;
        private final Direction face;

        public Provider(DuctBlockEntity duct, Direction face) {
            this.duct = duct;
            this.face = face;
        }

        @Override
        public Component getDisplayName() {
            Attachment a = duct.getAttachment(face);
            return a == null ? Component.empty() : a.toItem().getHoverName();
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
            Attachment a = duct.getAttachment(face);
            if (a == null) return null;
            return new AttachmentMenu(id, inv, duct.getBlockPos(), face, a.kind, a.tier,
                    new FilterContainer(duct, a), new AttachmentData(a), duct, a);
        }
    }

    /** Container view over an attachment's filter slots. */
    private static class FilterContainer extends SimpleContainer {
        private final DuctBlockEntity duct;
        private final Attachment att;

        FilterContainer(DuctBlockEntity duct, Attachment att) {
            super(att.tier.filterSlots());
            this.duct = duct;
            this.att = att;
            for (int i = 0; i < getContainerSize(); i++) super.setItem(i, att.filter.items().get(i).copy());
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            att.filter.items().set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
            duct.attachmentSettingsChanged();
        }
    }

    /** Live server-side values for the data slots. */
    private record AttachmentData(Attachment a) implements ContainerData {
        @Override
        public int get(int i) {
            return switch (i) {
                case D_FLAGS -> a.filter.flagBits();
                case D_MAX_OP -> a.maxPerOp();
                case D_MAX_STOCK -> a.maxStock();
                case D_SORT -> a.sortMode().ordinal();
                case D_REDSTONE -> a.redstoneMode().ordinal();
                case D_OVERSEND -> a.preventOverSend() ? 1 : 0;
                case D_POWERED -> a.powered() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int i, int v) {}

        @Override
        public int getCount() {
            return D_COUNT;
        }
    }

    // helpers for the screen
    public SortMode sortMode() { return SortMode.byId(get(D_SORT)); }

    public RedstoneMode redstoneMode() { return RedstoneMode.byId(get(D_REDSTONE)); }
}
