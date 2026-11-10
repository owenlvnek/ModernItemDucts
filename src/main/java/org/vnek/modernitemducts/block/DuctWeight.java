package org.vnek.modernitemducts.block;

import net.minecraft.util.StringRepresentable;

/**
 * Routing weight of a duct. Only changes destination priority, never the physical path (spec §1.2).
 */
public enum DuctWeight implements StringRepresentable {
    NORMAL("normal", 1),
    DENSE("dense", 1000),
    VACUUM("vacuum", -1000);

    private final String name;
    public final int cost;

    DuctWeight(String name, int cost) {
        this.name = name;
        this.cost = cost;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
