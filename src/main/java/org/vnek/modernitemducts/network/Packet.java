package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One moving stack inside the ducts plus its routing state (spec §4, §15).
 * <p>
 * Progress runs from 0 (centre of the owning duct) to the duct's segment length (centre of the
 * next block in {@link #dir}). {@link #path} holds the remaining turns; the final entry of a
 * route is always the face of the destination duct that leads into the target inventory.
 */
public class Packet {
    public ItemStack stack;
    public int progress;
    /** Current travel direction; null means "no route, reroute now". */
    @Nullable public Direction dir;
    @Nullable public Direction prevDir;
    public int speed;
    /** Destination duct + face (null when homeless). */
    @Nullable public BlockPos destPos;
    @Nullable public Direction destFace;
    /** Duct the packet was created at; preferred backlog target. */
    @Nullable public BlockPos sourcePos;
    private byte[] path = new byte[0];
    private int pathIndex;
    /** Heading back to a servo/retriever backlog. */
    public boolean toBacklog;
    /** Path ran out away from a destination: reroute at the next midpoint. */
    public boolean needsReroute;

    public Packet(ItemStack stack, int speed) {
        this.stack = stack;
        this.speed = speed;
    }

    /** Replace the remaining route. The first direction becomes the travel direction. */
    public void setRoute(List<Direction> dirs) {
        if (dirs.isEmpty()) {
            dir = null;
            path = new byte[0];
            pathIndex = 0;
            return;
        }
        dir = dirs.get(0);
        path = new byte[dirs.size() - 1];
        for (int i = 1; i < dirs.size(); i++) path[i - 1] = (byte) dirs.get(i).ordinal();
        pathIndex = 0;
        needsReroute = false;
    }

    public boolean pathEmpty() {
        return pathIndex >= path.length;
    }

    @Nullable
    public Direction pollPath() {
        return pathEmpty() ? null : Direction.from3DDataValue(path[pathIndex++]);
    }

    /** Inventory position the packet is heading for (for the in-transit ledger), or null. */
    @Nullable
    public BlockPos targetInventory() {
        if (toBacklog || destPos == null || destFace == null) return null;
        return destPos.relative(destFace);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Item", stack.copyWithCount(1).save(registries));
        tag.putInt("Count", stack.getCount());
        tag.putInt("Progress", progress);
        tag.putByte("Dir", (byte) (dir == null ? -1 : dir.ordinal()));
        tag.putByte("PrevDir", (byte) (prevDir == null ? -1 : prevDir.ordinal()));
        tag.putByte("Speed", (byte) speed);
        if (destPos != null && destFace != null) {
            tag.putLong("Dest", destPos.asLong());
            tag.putByte("DestFace", (byte) destFace.ordinal());
        }
        if (sourcePos != null) tag.putLong("Source", sourcePos.asLong());
        byte[] rest = new byte[Math.max(0, path.length - pathIndex)];
        System.arraycopy(path, pathIndex, rest, 0, rest.length);
        tag.putByteArray("Path", rest);
        tag.putBoolean("ToBacklog", toBacklog);
        tag.putBoolean("Reroute", needsReroute);
        return tag;
    }

    @Nullable
    public static Packet load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack stack = ItemStack.parseOptional(registries, tag.getCompound("Item"));
        if (stack.isEmpty()) return null;
        if (tag.contains("Count")) stack.setCount(tag.getInt("Count"));
        if (stack.isEmpty()) return null;
        int speed = Math.max(1, Math.min(3, tag.getByte("Speed")));
        Packet p = new Packet(stack, speed);
        p.progress = Math.max(0, tag.getInt("Progress"));
        p.dir = dirOrNull(tag.getByte("Dir"));
        p.prevDir = dirOrNull(tag.getByte("PrevDir"));
        if (tag.contains("Dest")) {
            p.destPos = BlockPos.of(tag.getLong("Dest"));
            p.destFace = dirOrNull(tag.getByte("DestFace"));
        }
        if (tag.contains("Source")) p.sourcePos = BlockPos.of(tag.getLong("Source"));
        byte[] raw = tag.getByteArray("Path");
        for (byte b : raw) if (b < 0 || b > 5) { raw = new byte[0]; p.needsReroute = true; break; }
        p.path = raw;
        p.toBacklog = tag.getBoolean("ToBacklog");
        p.needsReroute |= tag.getBoolean("Reroute");
        return p;
    }

    @Nullable
    private static Direction dirOrNull(int v) {
        return v >= 0 && v < 6 ? Direction.from3DDataValue(v) : null;
    }
}
