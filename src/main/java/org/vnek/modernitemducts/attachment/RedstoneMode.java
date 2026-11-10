package org.vnek.modernitemducts.attachment;

/** Redstone control for servos and retrievers. */
public enum RedstoneMode {
    /** Always active. */
    IGNORE,
    /** Active only while the duct is NOT powered. */
    LOW,
    /** Active only while the duct is powered (the classic default). */
    HIGH;

    public boolean isActive(boolean powered) {
        return switch (this) {
            case IGNORE -> true;
            case LOW -> !powered;
            case HIGH -> powered;
        };
    }

    public RedstoneMode cycle(boolean forward) {
        RedstoneMode[] v = values();
        return v[(ordinal() + (forward ? 1 : v.length - 1)) % v.length];
    }

    public static RedstoneMode byId(int id) {
        RedstoneMode[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
