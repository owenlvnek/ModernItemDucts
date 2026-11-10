package org.vnek.modernitemducts.attachment;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Ghost-slot item list plus match flags, shared by all attachment kinds (spec §7).
 */
public class FilterSettings {
    public static final int MAX_SLOTS = 15;

    /** Flags in GUI / bit order. */
    public enum Flag {
        /** ON = exclude list (blacklist). OFF = include list (whitelist). */
        EXCLUDE(true),
        IGNORE_DAMAGE(false),
        IGNORE_NBT(false),
        /** ON = tag matching disabled. */
        IGNORE_TAGS(true),
        /** ON = mod-namespace matching disabled. */
        IGNORE_MOD(true);

        public final boolean def;

        Flag(boolean def) {
            this.def = def;
        }

        public int bit() {
            return 1 << ordinal();
        }
    }

    private final Tier tier;
    private final NonNullList<ItemStack> items = NonNullList.withSize(MAX_SLOTS, ItemStack.EMPTY);
    private int flags;

    public FilterSettings(Tier tier) {
        this.tier = tier;
        for (Flag f : Flag.values()) set(f, f.def);
    }

    public NonNullList<ItemStack> items() {
        return items;
    }

    public int slotCount() {
        return tier.filterSlots();
    }

    public boolean get(Flag f) {
        return (flags & f.bit()) != 0;
    }

    public void set(Flag f, boolean value) {
        if (!tier.canChange(f)) value = f.def; // locked flags are forced to defaults
        flags = value ? flags | f.bit() : flags & ~f.bit();
    }

    public int flagBits() {
        return flags;
    }

    public void setFlagBits(int bits) {
        for (Flag f : Flag.values()) set(f, (bits & f.bit()) != 0);
    }

    public boolean isEmpty() {
        for (int i = 0; i < slotCount(); i++) if (!items.get(i).isEmpty()) return false;
        return true;
    }

    /**
     * @return true if the stack may pass this filter.
     */
    public boolean passes(ItemStack stack) {
        if (stack.isEmpty()) return false;
        boolean matched = matches(stack);
        return get(Flag.EXCLUDE) != matched;
    }

    private boolean matches(ItemStack stack) {
        boolean checkMod = !get(Flag.IGNORE_MOD);
        boolean checkTags = !get(Flag.IGNORE_TAGS);
        String ns = checkMod ? BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace() : null;
        Set<TagKey<Item>> tags = null;
        for (int i = 0; i < slotCount(); i++) {
            ItemStack f = items.get(i);
            if (f.isEmpty()) continue;
            if (checkMod && ns.equals(BuiltInRegistries.ITEM.getKey(f.getItem()).getNamespace())) return true;
            if (checkTags) {
                if (tags == null) {
                    tags = new HashSet<>();
                    stack.getTags().forEach(tags::add);
                }
                if (f.getTags().anyMatch(tags::contains)) return true;
            }
            if (sameType(f, stack, get(Flag.IGNORE_DAMAGE), get(Flag.IGNORE_NBT))) return true;
        }
        return false;
    }

    /**
     * Item identity check honouring the damage / data ignore flags. "Data" means the stack's data
     * components (1.20.5+); durability is excluded from that comparison because it has its own flag.
     */
    public static boolean sameType(ItemStack a, ItemStack b, boolean ignoreDamage, boolean ignoreNbt) {
        if (a.getItem() != b.getItem()) return false;
        if (!ignoreDamage && a.isDamageableItem() && a.getDamageValue() != b.getDamageValue()) return false;
        if (ignoreNbt) return true;
        ItemStack x = a.copyWithCount(1), y = b.copyWithCount(1);
        if (x.isDamageableItem()) {
            x.setDamageValue(0);
            y.setDamageValue(0);
        }
        return ItemStack.isSameItemSameComponents(x, y);
    }

    public void sanitize() {
        for (Flag f : Flag.values()) set(f, get(f));
        for (int i = slotCount(); i < MAX_SLOTS; i++) items.set(i, ItemStack.EMPTY);
        for (int i = 0; i < MAX_SLOTS; i++) if (items.get(i).getCount() > 1) items.get(i).setCount(1);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags);
        ListTag list = new ListTag();
        for (int i = 0; i < slotCount(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            CompoundTag e = (CompoundTag) s.copyWithCount(1).save(registries);
            e.putByte("Slot", (byte) i);
            list.add(e);
        }
        tag.put("Items", list);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        flags = tag.getInt("Flags");
        for (int i = 0; i < MAX_SLOTS; i++) items.set(i, ItemStack.EMPTY);
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int slot = e.getByte("Slot");
            if (slot >= 0 && slot < MAX_SLOTS) items.set(slot, ItemStack.parseOptional(registries, e));
        }
        sanitize();
    }
}
