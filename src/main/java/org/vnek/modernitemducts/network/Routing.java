package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.SortMode;
import org.vnek.modernitemducts.block.ConnectionType;
import org.vnek.modernitemducts.block.DuctBlockEntity;
import org.vnek.modernitemducts.network.RouteTable.Destination;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Stateless routing helpers: destination ordering, "how much would fit" simulation with
 * in-transit accounting (spec §5.3, §5.5, §7.4) and delivery.
 */
public final class Routing {
    public static final int MAX_PACKET = 64;
    private static final Random RANDOM = new Random();

    private Routing() {}

    public record Selection(Destination dest, int amount) {}

    /** Same item and same data components (what used to be "same item and NBT"). */
    public static boolean stackable(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    // ------------------------------------------------------------------ ordering (§6.3)

    public static List<Destination> order(List<Destination> base, @Nullable Attachment att) {
        SortMode mode = att == null ? SortMode.NEAREST : att.sortMode();
        if (base.size() <= 1 || mode == SortMode.NEAREST) return base;
        List<Destination> l = new ArrayList<>(base);
        switch (mode) {
            case FURTHEST -> Collections.reverse(l);
            case RANDOM -> Collections.shuffle(l, RANDOM);
            case ROUND_ROBIN -> Collections.rotate(l, -Math.floorMod(att.rrCursor, l.size()));
            default -> {}
        }
        return l;
    }

    /**
     * Round-robin: move the cursor to just past the destination actually used
     * (the arguably expected behaviour; see README).
     */
    public static void advanceCursor(@Nullable Attachment att, List<Destination> base, Destination used) {
        if (att == null || att.sortMode() != SortMode.ROUND_ROBIN || base.isEmpty()) return;
        int idx = base.indexOf(used);
        att.rrCursor = idx < 0 ? (att.rrCursor + 1) % base.size() : (idx + 1) % base.size();
    }

    // ------------------------------------------------------------------ selection (§5.3)

    /**
     * Pick the first destination (in the given order) that would accept at least one item.
     *
     * @param excludeDuct / excludeFace a destination face that must not be used (external pushes)
     * @param excludeInv  an inventory position that must not be used (source loop prevention)
     */
    @Nullable
    public static Selection select(DuctNetwork net, List<Destination> ordered, ItemStack stack,
                                   @Nullable DuctBlockEntity excludeDuct, @Nullable Direction excludeFace,
                                   @Nullable BlockPos excludeInv) {
        for (Destination dest : ordered) {
            if (dest.duct() == excludeDuct && dest.face() == excludeFace) continue;
            if (excludeInv != null && excludeInv.equals(dest.invPos())) continue;
            int n = acceptable(net, dest.duct(), dest.face(), stack, null);
            if (n > 0) return new Selection(dest, Math.min(n, stack.getCount()));
        }
        return null;
    }

    /**
     * How many items of {@code stack} (capped at 64) the inventory behind {@code duct}/{@code face}
     * would accept right now, honouring the face's filter, max stock and in-transit accounting.
     *
     * @param self a packet whose own in-flight items must not count against it (reroutes)
     */
    public static int acceptable(DuctNetwork net, DuctBlockEntity duct, Direction face, ItemStack stack, @Nullable Packet self) {
        if (stack.isEmpty() || duct.connection(face) != ConnectionType.INVENTORY) return 0;
        Inv handler = duct.neighborHandler(face);
        if (handler == null) return 0;
        Attachment att = duct.getAttachment(face);
        Attachment filter = att != null && att.kind == AttachmentKind.FILTER ? att : null;
        if (filter != null && !filter.filter.passes(stack)) return 0;

        int limit = Math.min(MAX_PACKET, stack.getCount());
        boolean countTransit = filter == null || filter.preventOverSend();
        BlockPos invPos = duct.getBlockPos().relative(face);
        List<ItemStack> transit = countTransit ? transitStacks(net, invPos, self) : Collections.emptyList();

        int maxStock = att == null ? 0 : att.maxStock();
        if (maxStock > 0) {
            int present = countIdentical(handler, stack);
            for (ItemStack t : transit) if (Routing.stackable(t, stack)) present += t.getCount();
            limit = Math.min(limit, maxStock - present);
            if (limit <= 0) return 0;
        }

        ItemStack probe = stack.copyWithCount(limit);
        if (transit.isEmpty()) {
            return limit - handler.insert(probe, true).getCount();
        }
        return simulateWithTransit(handler, transit, probe);
    }

    private static List<ItemStack> transitStacks(DuctNetwork net, BlockPos invPos, @Nullable Packet self) {
        List<Packet> packets = net.inTransit(invPos);
        if (packets.isEmpty()) return Collections.emptyList();
        List<ItemStack> out = new ArrayList<>(packets.size());
        for (Packet p : packets) if (p != self) out.add(p.stack);
        return out;
    }

    public static int countIdentical(Inv handler, ItemStack stack) {
        int n = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (Routing.stackable(s, stack)) n += s.getCount();
        }
        return n;
    }

    /**
     * Virtually inserts everything already in flight first, then the new stack, inside a
     * transaction that is always rolled back. If an identical item already in flight would not
     * fit, the destination is rejected outright (spec §5.5).
     */
    private static int simulateWithTransit(Inv handler, List<ItemStack> transit, ItemStack stack) {
        return handler.simulateAfter(transit, stack, stack);
    }

    // ------------------------------------------------------------------ delivery (§4.3, §4.4)

    /**
     * Inserts a packet's stack into the inventory behind a face, re-checking the filter and
     * max stock. @return the remainder (empty if everything fit).
     */
    public static ItemStack deliver(DuctBlockEntity duct, Direction face, ItemStack stack) {
        Inv handler = duct.neighborHandler(face);
        if (handler == null) return stack;
        Attachment att = duct.getAttachment(face);
        if (att != null && att.kind == AttachmentKind.FILTER && !att.filter.passes(stack)) return stack;
        int limit = stack.getCount();
        int maxStock = att == null ? 0 : att.maxStock();
        if (maxStock > 0) limit = Math.min(limit, maxStock - countIdentical(handler, stack));
        if (limit <= 0) return stack;
        ItemStack rem = handler.insert(stack.copyWithCount(limit), false);
        int inserted = limit - rem.getCount();
        return stack.copyWithCount(stack.getCount() - inserted);
    }

    /**
     * Extracts {@code amount} items like {@code template} starting at {@code slot}; servos of
     * multi-slot tiers keep pulling identical items from later slots.
     */
    public static ItemStack extract(Inv handler, int slot, ItemStack template, int amount, boolean multiSlot) {
        ItemStack got = handler.extractItem(slot, amount, false);
        if (got.isEmpty()) return ItemStack.EMPTY;
        if (!Routing.stackable(got, template)) return got;
        for (int j = slot + 1; multiSlot && j < handler.getSlots() && got.getCount() < amount; j++) {
            if (!Routing.stackable(handler.getStackInSlot(j), got)) continue;
            ItemStack more = handler.extractItem(j, amount - got.getCount(), false);
            if (Routing.stackable(more, got)) got.grow(more.getCount());
            else if (!more.isEmpty()) handler.insert(more, false); // should not happen; put it back
        }
        return got;
    }
}
