package org.vnek.modernitemducts.network;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import javax.annotation.Nullable;

/**
 * Slot-style view of a NeoForge item {@link ResourceHandler}, so the routing code keeps its
 * simple "simulate / insert / extract a stack" vocabulary.
 * <p>
 * Every operation runs in its own nested transaction under whatever transaction is already open
 * on this thread (for example an external block pushing into a duct), or a root transaction if
 * none is. Simulations are always rolled back; real operations commit into their parent, so they
 * only take effect if the outer transaction commits too.
 */
public final class Inv {
    private final ResourceHandler<ItemResource> handler;

    public Inv(ResourceHandler<ItemResource> handler) {
        this.handler = handler;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static TransactionContext outer() {
        return Transaction.getCurrentOpenedTransaction();
    }

    public int getSlots() {
        return handler.size();
    }

    public ItemStack getStackInSlot(int slot) {
        return ItemUtil.getStack(handler, slot);
    }

    /** Capacity of a slot for this kind of item (vanilla containers: min(slot limit, max stack size)). */
    public int getSlotLimit(int slot, ItemStack stack) {
        return stack.isEmpty() ? 64 : handler.getCapacityAsInt(slot, ItemResource.of(stack));
    }

    /** Inserts into the handler in its own slot order. @return the remainder. */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        return ItemUtil.insertItemReturnRemaining(handler, stack, simulate, outer());
    }

    /** How many of {@code stack} would be accepted, after first (virtually) inserting {@code first}. */
    public int simulateAfter(Iterable<ItemStack> first, ItemStack stack, ItemStack rejectIfStuck) {
        try (Transaction tx = Transaction.open(outer())) {
            for (ItemStack t : first) {
                if (t.isEmpty()) continue;
                int in = handler.insert(ItemResource.of(t), t.getCount(), tx);
                if (in < t.getCount() && Routing.stackable(t, rejectIfStuck)) return 0;
            }
            return stack.isEmpty() ? 0 : handler.insert(ItemResource.of(stack), stack.getCount(), tx);
        } // never committed: everything above is rolled back
    }

    /** Extracts up to {@code amount} of whatever is in {@code slot}. */
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemResource resource = handler.getResource(slot);
        if (resource.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        try (Transaction tx = Transaction.open(outer())) {
            int got = handler.extract(slot, resource, amount, tx);
            if (!simulate) tx.commit();
            return got <= 0 ? ItemStack.EMPTY : resource.toStack(got);
        }
    }
}
