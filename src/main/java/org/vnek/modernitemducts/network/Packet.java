package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.core.Direction;
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

    public void save(ValueOutput out) {
        out.store("Item", ItemStack.CODEC, stack.copyWithCount(1));
        out.putInt("Count", stack.getCount());
        out.putInt("Progress", progress);
        out.putByte("Dir", (byte) (dir == null ? -1 : dir.ordinal()));
        out.putByte("PrevDir", (byte) (prevDir == null ? -1 : prevDir.ordinal()));
        out.putByte("Speed", (byte) speed);
        if (destPos != null && destFace != null) {
            out.putLong("Dest", destPos.asLong());
            out.putByte("DestFace", (byte) destFace.ordinal());
        }
        if (sourcePos != null) out.putLong("Source", sourcePos.asLong());
        int[] rest = new int[Math.max(0, path.length - pathIndex)];
        for (int i = 0; i < rest.length; i++) rest[i] = path[pathIndex + i];
        out.putIntArray("Path", rest);
        out.putBoolean("ToBacklog", toBacklog);
        out.putBoolean("Reroute", needsReroute);
    }

    @Nullable
    public static Packet load(ValueInput in) {
        ItemStack stack = in.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) return null;
        stack.setCount(in.getIntOr("Count", stack.getCount()));
        if (stack.isEmpty()) return null;
        int speed = Math.max(1, Math.min(3, in.getByteOr("Speed", (byte) 1)));
        Packet p = new Packet(stack, speed);
        p.progress = Math.max(0, in.getIntOr("Progress", 0));
        p.dir = dirOrNull(in.getByteOr("Dir", (byte) -1));
        p.prevDir = dirOrNull(in.getByteOr("PrevDir", (byte) -1));
        in.getLong("Dest").ifPresent(d -> {
            p.destPos = BlockPos.of(d);
            p.destFace = dirOrNull(in.getByteOr("DestFace", (byte) -1));
        });
        in.getLong("Source").ifPresent(src -> p.sourcePos = BlockPos.of(src));
        int[] raw = in.getIntArray("Path").orElse(new int[0]);
        byte[] path = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] < 0 || raw[i] > 5) { path = new byte[0]; p.needsReroute = true; break; }
            path[i] = (byte) raw[i];
        }
        p.path = path;
        p.toBacklog = in.getBooleanOr("ToBacklog", false);
        p.needsReroute |= in.getBooleanOr("Reroute", false);
        return p;
    }

    @Nullable
    private static Direction dirOrNull(int v) {
        return v >= 0 && v < 6 ? Direction.from3DDataValue(v) : null;
    }
}
