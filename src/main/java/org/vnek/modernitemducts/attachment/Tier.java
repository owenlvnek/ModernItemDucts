package org.vnek.modernitemducts.attachment;

import net.minecraft.world.item.Rarity;

/**
 * Attachment quality tiers. Numbers follow the behaviour specification (§6.1, §7.4).
 */
public enum Tier {
    //      interval maxItems multiSlot speed cols rows maxStock tint      rarity
    T1("copper",    60,  8, false, 1, 3, 1,   0, 0xC87F4F, Rarity.COMMON),
    T2("iron",      40, 16, false, 1, 3, 2,   0, 0xD8D8D8, Rarity.COMMON),
    T3("gold",      20, 32, false, 1, 3, 3,  64, 0xF2CF3C, Rarity.UNCOMMON),
    T4("diamond",   10, 64, true,  2, 4, 3, 128, 0x52DCD0, Rarity.UNCOMMON),
    T5("netherite", 10, 64, true,  3, 5, 3, 320, 0x6E5A66, Rarity.RARE);

    public final String material;
    public final int interval;
    public final int maxItems;
    public final boolean multiSlot;
    public final int speed;
    public final int cols;
    public final int rows;
    public final int maxStockLimit;
    public final int tint;
    public final Rarity rarity;

    Tier(String material, int interval, int maxItems, boolean multiSlot, int speed, int cols, int rows,
         int maxStockLimit, int tint, Rarity rarity) {
        this.material = material;
        this.interval = interval;
        this.maxItems = maxItems;
        this.multiSlot = multiSlot;
        this.speed = speed;
        this.cols = cols;
        this.rows = rows;
        this.maxStockLimit = maxStockLimit;
        this.tint = tint;
        this.rarity = rarity;
    }

    public int filterSlots() {
        return cols * rows;
    }

    public int number() {
        return ordinal() + 1;
    }

    /** Sort modes other than nearest-first need T3+. */
    public boolean hasSortModes() {
        return ordinal() >= 2;
    }

    public boolean hasMaxStock() {
        return maxStockLimit > 0;
    }

    /** Whether a given filter flag may be changed on this tier. */
    public boolean canChange(FilterSettings.Flag flag) {
        return switch (flag) {
            case EXCLUDE -> true;
            case IGNORE_DAMAGE -> ordinal() >= 1;
            default -> ordinal() >= 2;
        };
    }

    public static Tier byId(int id) {
        Tier[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, id))];
    }
}
