package org.vnek.modernitemducts.attachment;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.item.ItemStack;
import org.vnek.modernitemducts.network.Routing;
import org.vnek.modernitemducts.Config;
import org.vnek.modernitemducts.Registration;

import java.util.ArrayList;
import java.util.List;

/**
 * State of one attachment (servo, retriever or filter) on one duct face.
 * All kinds share this class; settings that do not apply to a kind are simply unused.
 */
public class Attachment {
    public final AttachmentKind kind;
    public final Tier tier;
    public final FilterSettings filter;

    private int maxPerOp;
    private int maxStock;          // 0 = unlimited
    private SortMode sortMode = SortMode.NEAREST;
    private boolean preventOverSend = true;
    private RedstoneMode redstoneMode;
    private boolean powered;
    /** Round-robin position (not persisted, harmless to reset). */
    public int rrCursor;

    /** Items returned to this servo / retriever because nothing else could take them. */
    private final List<BacklogEntry> backlog = new ArrayList<>();

    public Attachment(AttachmentKind kind, Tier tier) {
        this.kind = kind;
        this.tier = tier;
        this.filter = new FilterSettings(tier);
        this.maxPerOp = tier.maxItems;
        this.redstoneMode = Config.defaultRedstone();
    }

    // ------------------------------------------------------------------ settings

    public int maxPerOp() { return maxPerOp; }

    public void setMaxPerOp(int v) { maxPerOp = Math.max(1, Math.min(tier.maxItems, v)); }

    public int maxStock() { return tier.hasMaxStock() ? maxStock : 0; }

    public void setMaxStock(int v) { maxStock = tier.hasMaxStock() ? Math.max(0, Math.min(tier.maxStockLimit, v)) : 0; }

    public SortMode sortMode() { return tier.hasSortModes() ? sortMode : SortMode.NEAREST; }

    public void setSortMode(SortMode m) { sortMode = tier.hasSortModes() ? m : SortMode.NEAREST; }

    public boolean preventOverSend() { return preventOverSend; }

    public void setPreventOverSend(boolean v) { preventOverSend = v; }

    public RedstoneMode redstoneMode() { return redstoneMode; }

    public void setRedstoneMode(RedstoneMode m) { redstoneMode = m; }

    public boolean powered() { return powered; }

    /** @return true if the stored value changed. */
    public boolean setPowered(boolean p) {
        boolean changed = p != powered;
        powered = p;
        return changed;
    }

    public boolean isActive() {
        return kind.isActive() && redstoneMode.isActive(powered);
    }

    // ------------------------------------------------------------------ backlog

    public boolean canHoldBacklog() {
        return kind.isActive();
    }

    public boolean hasBacklog() {
        return !backlog.isEmpty();
    }

    public List<BacklogEntry> backlog() {
        return backlog;
    }

    public void addBacklog(ItemStack stack) {
        if (stack.isEmpty()) return;
        for (BacklogEntry e : backlog) {
            if (Routing.stackable(e.template, stack)) {
                e.count = (int) Math.min(Integer.MAX_VALUE, (long) e.count + stack.getCount());
                return;
            }
        }
        backlog.add(new BacklogEntry(stack.copyWithCount(1), stack.getCount()));
    }

    /** Splits the whole backlog into normal-sized stacks (for spilling / dropping) and clears it. */
    public List<ItemStack> drainBacklog() {
        List<ItemStack> out = new ArrayList<>();
        for (BacklogEntry e : backlog) {
            int left = e.count;
            int max = e.template.getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                out.add(e.template.copyWithCount(n));
                left -= n;
            }
        }
        backlog.clear();
        return out;
    }

    public ItemStack toItem() {
        return new ItemStack(Registration.attachmentItem(kind, tier));
    }

    // ------------------------------------------------------------------ persistence

    public void save(ValueOutput out) {
        out.putByte("Kind", (byte) kind.ordinal());
        out.putByte("Tier", (byte) tier.ordinal());
        filter.save(out.child("Filter"));
        out.putInt("MaxPerOp", maxPerOp);
        out.putInt("MaxStock", maxStock);
        out.putByte("Sort", (byte) sortMode.ordinal());
        out.putBoolean("PreventOverSend", preventOverSend);
        out.putByte("Redstone", (byte) redstoneMode.ordinal());
        out.putBoolean("Powered", powered);
        out.putInt("RR", rrCursor);
        if (!backlog.isEmpty()) {
            ValueOutput.ValueOutputList list = out.childrenList("Backlog");
            for (BacklogEntry e : backlog) {
                ValueOutput t = list.addChild();
                t.store("Item", ItemStack.CODEC, e.template.copyWithCount(1));
                t.putInt("Amount", e.count);
            }
        }
    }

    public static Attachment load(ValueInput in) {
        Attachment a = new Attachment(AttachmentKind.byId(in.getByteOr("Kind", (byte) 0)), Tier.byId(in.getByteOr("Tier", (byte) 0)));
        a.filter.load(in.childOrEmpty("Filter"));
        a.setMaxPerOp(in.getIntOr("MaxPerOp", a.tier.maxItems));
        a.setMaxStock(in.getIntOr("MaxStock", 0));
        a.setSortMode(SortMode.byId(in.getByteOr("Sort", (byte) 0)));
        a.preventOverSend = in.getBooleanOr("PreventOverSend", true);
        byte rs = in.getByteOr("Redstone", (byte) -1);
        if (rs >= 0) a.redstoneMode = RedstoneMode.byId(rs);
        a.powered = in.getBooleanOr("Powered", false);
        a.rrCursor = Math.max(0, in.getIntOr("RR", 0));
        in.childrenListOrEmpty("Backlog").forEach(t -> {
            ItemStack s = t.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            int amount = t.getIntOr("Amount", 0);
            if (!s.isEmpty() && amount > 0) a.backlog.add(new BacklogEntry(s.copyWithCount(1), amount));
        });
        return a;
    }

    /** One merged backlog line: an item type and an (unbounded) count. */
    public static final class BacklogEntry {
        public final ItemStack template;
        public int count;

        BacklogEntry(ItemStack template, int count) {
            this.template = template;
            this.count = count;
        }
    }
}
