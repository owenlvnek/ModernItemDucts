package org.vnek.modernitemducts.block;

/** The four duct blocks: standard / fast, each transparent or opaque. */
public enum DuctType {
    STANDARD("item_duct", 40, false),
    STANDARD_OPAQUE("item_duct_opaque", 40, true),
    FAST("fast_item_duct", 10, false),
    FAST_OPAQUE("fast_item_duct_opaque", 10, true);

    public final String id;
    /** Progress units needed to travel one block. */
    public final int segmentLength;
    /** Opaque ducts never render or sync their packets. */
    public final boolean opaque;

    DuctType(String id, int segmentLength, boolean opaque) {
        this.id = id;
        this.segmentLength = segmentLength;
        this.opaque = opaque;
    }
}
