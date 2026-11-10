package org.vnek.modernitemducts.attachment;

/** Order in which a servo / retriever tries its candidate destinations. */
public enum SortMode {
    NEAREST, FURTHEST, RANDOM, ROUND_ROBIN;

    public SortMode cycle(boolean forward) {
        SortMode[] v = values();
        return v[(ordinal() + (forward ? 1 : v.length - 1)) % v.length];
    }

    public static SortMode byId(int id) {
        SortMode[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
