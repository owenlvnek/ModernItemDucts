package org.vnek.modernitemducts.block;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** The four duct blocks: standard / fast, each transparent or opaque. */
public enum DuctType implements StringRepresentable {
    STANDARD("item_duct", 40, false),
    STANDARD_OPAQUE("item_duct_opaque", 40, true),
    FAST("fast_item_duct", 10, false),
    FAST_OPAQUE("fast_item_duct_opaque", 10, true);

    public final String id;
    /** Progress units needed to travel one block. */
    public final int segmentLength;
    /** Opaque ducts never render or sync their packets. */
    public final boolean opaque;

    public static final Codec<DuctType> CODEC = StringRepresentable.fromEnum(DuctType::values);

    @Override
    public String getSerializedName() {
        return id;
    }

    DuctType(String id, int segmentLength, boolean opaque) {
        this.id = id;
        this.segmentLength = segmentLength;
        this.opaque = opaque;
    }
}
