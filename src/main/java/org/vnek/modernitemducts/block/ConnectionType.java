package org.vnek.modernitemducts.block;

import net.minecraft.util.StringRepresentable;

/** What a duct face is connected to. */
public enum ConnectionType implements StringRepresentable {
    NONE("none"),
    /** Linked to another item duct (same network). */
    DUCT("duct"),
    /** Linked to an inventory; items can be delivered through this face. */
    INVENTORY("inventory"),
    /** A servo sits here, facing an inventory. Never a destination. */
    INPUT("input");

    private final String name;

    ConnectionType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Visual / blockstate form: inputs draw like inventory arms. */
    public ConnectionType visual() {
        return this == INPUT ? INVENTORY : this;
    }

    public boolean isInventoryLike() {
        return this == INVENTORY || this == INPUT;
    }
}
