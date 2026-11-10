package org.vnek.modernitemducts.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.vnek.modernitemducts.Config;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.attachment.AttachmentKind;
import org.vnek.modernitemducts.attachment.Tier;
import org.vnek.modernitemducts.network.DuctManager;
import org.vnek.modernitemducts.network.DuctNetwork;
import org.vnek.modernitemducts.network.RouteTable;
import org.vnek.modernitemducts.network.RouteTable.Destination;
import org.vnek.modernitemducts.network.RouteTable.Route;
import org.vnek.modernitemducts.network.Routing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * State and behaviour of one item duct: per-face connections and attachments, the packets it
 * currently holds, and (on the client) the packets to draw.
 */
public class DuctBlockEntity extends BlockEntity {
    /** Age cap; a duct loaded from disk starts at 0 (grace period, spec §3.1). */
    public static final int MATURE_AGE = 10;
    /** Packets included per client update / drawn per duct. */
    public static final int CLIENT_PACKET_CAP = 16;

    private final Attachment[] attachments = new Attachment[6];
    private final ConnectionType[] conn = new ConnectionType[6];
    private int blockedMask;

    private final List<org.vnek.modernitemducts.network.Packet> packets = new ArrayList<>();
    private final List<org.vnek.modernitemducts.network.Packet> incoming = new ArrayList<>();
    private int age = MATURE_AGE;

    @Nullable private DuctNetwork network;
    @Nullable private DuctManager manager;
    private int crossChunkMask = -1;
    private int loadedMask;
    private boolean pushGuard;
    @Nullable private VoxelShape attachmentShape;

    /** One external-push handler per face; only exposed on visually connected faces (handlerFor). */
    private final FaceHandler[] handlers = new FaceHandler[6];

    // client-only view
    public final List<ClientPacket> clientPackets = new ArrayList<>();
    private final boolean[] clientActive = new boolean[6];
    private final boolean[] clientClogged = new boolean[6];

    public DuctBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.DUCT_BE.get(), pos, state);
        Arrays.fill(conn, ConnectionType.NONE);
        for (Direction d : Direction.values()) handlers[d.ordinal()] = new FaceHandler(d);
    }

    // ================================================================== basic accessors

    public DuctType type() {
        return getBlockState().getBlock() instanceof DuctBlock b ? b.type : DuctType.STANDARD;
    }

    public DuctWeight weight() {
        BlockState s = getBlockState();
        return s.hasProperty(DuctBlock.WEIGHT) ? s.getValue(DuctBlock.WEIGHT) : DuctWeight.NORMAL;
    }

    public ConnectionType connection(Direction d) {
        return conn[d.ordinal()];
    }

    public boolean isBlocked(Direction d) {
        return (blockedMask & (1 << d.ordinal())) != 0;
    }

    @Nullable
    public Attachment getAttachment(Direction d) {
        return attachments[d.ordinal()];
    }

    @Nullable
    public DuctNetwork network() {
        return network;
    }

    public void setNetwork(@Nullable DuctNetwork network) {
        this.network = network;
    }

    public int age() {
        return age;
    }

    public List<org.vnek.modernitemducts.network.Packet> packetsView() {
        List<org.vnek.modernitemducts.network.Packet> l = new ArrayList<>(packets);
        l.addAll(incoming);
        return l;
    }

    public void forEachPacket(Consumer<org.vnek.modernitemducts.network.Packet> c) {
        packets.forEach(c);
        incoming.forEach(c);
    }

    /** Endpoint: at least one inventory link or servo input face. */
    public boolean isEndpoint() {
        for (ConnectionType c : conn) if (c.isInventoryLike()) return true;
        return false;
    }

    public boolean hasActiveAttachment() {
        for (Attachment a : attachments) if (a != null && a.kind.isActive()) return true;
        return false;
    }

    /** First face (side order) whose servo/retriever can take backlog. Any such attachment counts (quirk 7 fixed). */
    @Nullable
    public Direction backlogFace() {
        for (Direction d : Direction.values()) {
            Attachment a = attachments[d.ordinal()];
            if (a != null && a.canHoldBacklog() && conn[d.ordinal()].isInventoryLike()) return d;
        }
        return null;
    }

    public boolean hasBacklogCapableFace() {
        return backlogFace() != null;
    }

    public boolean isClogged(Direction d) {
        if (level != null && level.isClientSide) return clientClogged[d.ordinal()];
        Attachment a = attachments[d.ordinal()];
        return a != null && a.hasBacklog();
    }

    public boolean isAttachmentActive(Direction d) {
        if (level != null && level.isClientSide) return clientActive[d.ordinal()];
        Attachment a = attachments[d.ordinal()];
        return a != null && a.isActive();
    }

    private BlockPos neighborPos(Direction d) {
        return worldPosition.relative(d);
    }

    /** The item handler of the block on face {@code d}, never force-loading chunks. */
    @Nullable
    public IItemHandler neighborHandler(Direction d) {
        if (level == null) return null;
        BlockPos np = neighborPos(d);
        if (!level.hasChunkAt(np)) return null;
        if (level.getBlockEntity(np) instanceof DuctBlockEntity) return null;
        return level.getCapability(Capabilities.ItemHandler.BLOCK, np, d.getOpposite());
    }

    /** Neighbour duct linked on face d that belongs to {@code net}. */
    @Nullable
    public DuctBlockEntity linkedDuct(Direction d, DuctNetwork net) {
        if (conn[d.ordinal()] != ConnectionType.DUCT) return null;
        DuctBlockEntity nb = net.get(neighborPos(d));
        return nb != null && nb.conn[d.getOpposite().ordinal()] == ConnectionType.DUCT ? nb : null;
    }

    /** Neighbour duct linked on face d, looked up in the world (used while building networks). */
    @Nullable
    public DuctBlockEntity linkedDuctUnchecked(Direction d) {
        if (conn[d.ordinal()] != ConnectionType.DUCT || level == null) return null;
        BlockPos np = neighborPos(d);
        if (!level.hasChunkAt(np)) return null;
        return level.getBlockEntity(np) instanceof DuctBlockEntity nb
                && nb.conn[d.getOpposite().ordinal()] == ConnectionType.DUCT ? nb : null;
    }

    private boolean isServo(Direction d) {
        Attachment a = attachments[d.ordinal()];
        return a != null && a.kind == AttachmentKind.SERVO;
    }

    // ================================================================== lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        ensureRegistered();
    }

    public void ensureRegistered() {
        if (level instanceof ServerLevel sl && !isRemoved()) {
            if (manager == null) manager = DuctManager.get(sl);
            if (!manager.contains(this)) manager.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (manager != null) manager.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (manager != null) manager.remove(this);
    }

    public void requestScan() {
        ensureRegistered();
        if (manager != null) manager.requestScan(this);
    }

    private void invalidateNetwork() {
        if (manager != null) manager.invalidate(network);
    }

    private void requestSync() {
        if (manager != null) manager.requestSync(this);
    }

    private void markPacketsChanged() {
        if (!type().opaque) requestSync();
    }

    private void markLedgerDirty() {
        if (network != null) network.markLedgerDirty();
    }

    // ================================================================== connections (spec §2)

    /** Called by the manager once per tick before anything else (age + chunk watch, spec §3.1). */
    public void preTick() {
        if (age < MATURE_AGE) age++;
        if (level == null) return;
        if (crossChunkMask < 0) {
            crossChunkMask = 0;
            int cx = SectionPos.blockToSectionCoord(worldPosition.getX());
            int cz = SectionPos.blockToSectionCoord(worldPosition.getZ());
            for (Direction d : Direction.values()) {
                BlockPos np = neighborPos(d);
                if (SectionPos.blockToSectionCoord(np.getX()) != cx || SectionPos.blockToSectionCoord(np.getZ()) != cz) {
                    crossChunkMask |= 1 << d.ordinal();
                }
            }
        }
        if (crossChunkMask == 0) return;
        for (Direction d : Direction.values()) {
            int bit = 1 << d.ordinal();
            if ((crossChunkMask & bit) == 0) continue;
            boolean loaded = level.hasChunkAt(neighborPos(d));
            if (loaded != ((loadedMask & bit) != 0)) {
                requestScan();
                return;
            }
        }
    }

    /** Re-evaluates all six faces; invalidates the network if anything changed. */
    public void refreshConnections() {
        if (level == null || level.isClientSide) return;
        ConnectionType[] next = new ConnectionType[6];
        int loaded = 0;
        for (Direction d : Direction.values()) {
            int i = d.ordinal();
            next[i] = ConnectionType.NONE;
            BlockPos np = neighborPos(d);
            if (!level.hasChunkAt(np)) continue;
            loaded |= 1 << i;
            if (isBlocked(d)) continue; // blocked faces stay blocked whatever the neighbour (quirk 9 fixed)
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof DuctBlockEntity other) {
                Direction o = d.getOpposite();
                if (!other.isRemoved() && !other.isBlocked(o) && !isServo(d) && !other.isServo(o)) next[i] = ConnectionType.DUCT;
            } else if (level.getCapability(Capabilities.ItemHandler.BLOCK, np, d.getOpposite()) != null) {
                next[i] = isServo(d) ? ConnectionType.INPUT : ConnectionType.INVENTORY;
            }
        }
        loadedMask = loaded;

        boolean powered = level.hasNeighborSignal(worldPosition);
        boolean poweredChanged = false;
        for (Attachment a : attachments) if (a != null && a.setPowered(powered)) poweredChanged = true;
        if (poweredChanged) {
            setChanged();
            requestSync();
        }

        if (!Arrays.equals(next, conn)) {
            System.arraycopy(next, 0, conn, 0, 6);
            invalidateNetwork();
            level.invalidateCapabilities(worldPosition); // our exposed faces changed
            for (Direction d : Direction.values()) {
                if (level.hasChunkAt(neighborPos(d)) && level.getBlockEntity(neighborPos(d)) instanceof DuctBlockEntity nb) nb.requestScan();
            }
            updateBlockState();
        }
    }

    private void updateBlockState() {
        BlockState s = getBlockState();
        if (!(s.getBlock() instanceof DuctBlock)) return;
        BlockState n = s;
        for (Direction d : Direction.values()) n = n.setValue(DuctBlock.SIDES[d.ordinal()], conn[d.ordinal()].visual());
        if (n != s && level != null) level.setBlock(worldPosition, n, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** Wrench toggle Normal &lt;-&gt; Blocked; a touching duct's facing side follows (spec §2.3). */
    public void toggleBlocked(Direction d, @Nullable Player player) {
        boolean block = !isBlocked(d);
        setBlockedBit(d, block);
        if (level != null && level.getBlockEntity(neighborPos(d)) instanceof DuctBlockEntity nb) {
            nb.setBlockedBit(d.getOpposite(), block);
            nb.requestScan();
        }
        requestScan();
        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.6f, block ? 0.7f : 1.1f);
        }
        if (player != null) {
            player.displayClientMessage(Component.translatable(block ? "message.modernitemducts.blocked" : "message.modernitemducts.unblocked",
                    Component.translatable("direction.modernitemducts." + d.getSerializedName())).withStyle(ChatFormatting.GRAY), true);
        }
    }

    private void setBlockedBit(Direction d, boolean block) {
        int bit = 1 << d.ordinal();
        blockedMask = block ? blockedMask | bit : blockedMask & ~bit;
        setChanged();
    }

    // ================================================================== attachments

    public void setAttachment(Direction d, @Nullable Attachment a) {
        attachments[d.ordinal()] = a;
        attachmentShape = null;
        if (a != null && level != null) a.setPowered(level.hasNeighborSignal(worldPosition));
        setChanged();
        invalidateNetwork();
        requestScan();
        if (level != null && level.getBlockEntity(neighborPos(d)) instanceof DuctBlockEntity nb) nb.requestScan();
        requestSync();
    }

    /** Called when settings change through the GUI. */
    public void attachmentSettingsChanged() {
        setChanged();
        requestSync();
    }

    /** Wrench on an attachment: spill a backlog first, otherwise remove it (spec §6.4). */
    public void wrenchAttachment(Direction d) {
        Attachment a = attachments[d.ordinal()];
        if (a == null || level == null) return;
        if (a.hasBacklog()) {
            for (ItemStack s : a.drainBacklog()) Block.popResource(level, worldPosition, s);
            setChanged();
            requestSync();
            return;
        }
        setAttachment(d, null);
        Block.popResource(level, worldPosition, a.toItem());
    }

    public VoxelShape attachmentShape() {
        VoxelShape s = attachmentShape;
        if (s == null) {
            s = Shapes.empty();
            for (Direction d : Direction.values()) {
                if (attachments[d.ordinal()] != null) s = Shapes.or(s, Shapes.create(DuctBlock.attachmentBox(d)));
            }
            attachmentShape = s;
        }
        return s;
    }

    /** Everything inside goes on the ground when the duct is broken (no 30-stack cap, quirk 12 fixed). */
    public void dropContents() {
        if (level == null) return;
        for (org.vnek.modernitemducts.network.Packet p : packetsView()) Block.popResource(level, worldPosition, p.stack);
        packets.clear();
        incoming.clear();
        for (int i = 0; i < 6; i++) {
            Attachment a = attachments[i];
            if (a == null) continue;
            for (ItemStack s : a.drainBacklog()) Block.popResource(level, worldPosition, s);
            Block.popResource(level, worldPosition, a.toItem());
            attachments[i] = null;
        }
    }

    // ================================================================== packet movement (spec §4)

    public void mergeIncoming() {
        if (!incoming.isEmpty()) {
            packets.addAll(incoming);
            incoming.clear();
        }
    }

    private enum Reroute { KEPT, ROUTED, REMOVED }

    public void tickPackets() {
        if (packets.isEmpty()) return;
        DuctNetwork net = network;
        if (net == null) return;
        final int seg = type().segmentLength;
        final int half = seg / 2;
        boolean changed = false;
        Iterator<org.vnek.modernitemducts.network.Packet> it = packets.iterator();
        while (it.hasNext()) {
            org.vnek.modernitemducts.network.Packet p = it.next();
            int before = p.progress;
            p.progress += p.speed;

            if (p.dir == null) {
                if (p.progress >= seg) p.progress %= seg;
                if (reroute(p, net) == Reroute.REMOVED) { it.remove(); changed = true; }
                continue;
            }

            if (p.progress >= seg) {
                p.progress %= seg;
                Direction d = p.dir;
                int i = d.ordinal();
                Attachment att = attachments[i];
                if (p.toBacklog && p.pathEmpty() && att != null && att.canHoldBacklog()) {
                    depositBacklog(att, p);
                    it.remove();
                    changed = true;
                    continue;
                }
                if (conn[i] == ConnectionType.DUCT) {
                    DuctBlockEntity nb = linkedDuct(d, net);
                    if (nb != null) {
                        it.remove();
                        changed = true;
                        p.prevDir = d;
                        p.dir = p.pollPath();
                        if (p.dir == null) p.needsReroute = true;
                        nb.incoming.add(p);   // starts moving next tick (deterministic)
                        nb.markPacketsChanged();
                        nb.setChanged();
                        continue;
                    }
                } else if (conn[i] == ConnectionType.INVENTORY && !p.toBacklog) {
                    ItemStack rem = Routing.deliver(this, d, p.stack);
                    net.markLedgerDirty();
                    if (rem.isEmpty()) {
                        it.remove();
                        changed = true;
                        continue;
                    }
                    p.stack = rem;
                }
                // destination full / gone, or the next duct no longer links back (quirk 13 fixed)
                if (reroute(p, net) == Reroute.REMOVED) { it.remove(); changed = true; }
            } else if (before < half && p.progress >= half) {
                if (p.needsReroute || conn[p.dir.ordinal()] == ConnectionType.NONE) {
                    if (reroute(p, net) == Reroute.REMOVED) { it.remove(); changed = true; }
                }
            }
        }
        if (changed) {
            markPacketsChanged();
            setChanged();
        }
    }

    private void depositBacklog(Attachment att, org.vnek.modernitemducts.network.Packet p) {
        boolean wasEmpty = !att.hasBacklog();
        att.addBacklog(p.stack);
        markLedgerDirty();
        setChanged();
        if (wasEmpty) requestSync(); // clogged look
    }

    /** The bounce algorithm of spec §5.6. */
    private Reroute reroute(org.vnek.modernitemducts.network.Packet p, DuctNetwork net) {
        net.markLedgerDirty();
        RouteTable table = net.routes(this, true);
        if (table == null) return Reroute.KEPT;

        // 1. original destination duct, any face that now accepts
        if (p.destPos != null && !p.toBacklog) {
            Route r = table.routeTo(p.destPos);
            if (r != null) {
                for (Direction f : Direction.values()) {
                    if (r.duct().connection(f) == ConnectionType.INVENTORY
                            && Routing.acceptable(net, r.duct(), f, p.stack, p) > 0) {
                        applyRoute(p, r, f, false);
                        return Reroute.ROUTED;
                    }
                }
            }
        }
        // 2. grace period
        if (age < 2) return Reroute.KEPT;
        // 3. any destination, nearest first
        for (Destination dest : table.destinations) {
            if (Routing.acceptable(net, dest.duct(), dest.face(), p.stack, p) > 0) {
                applyRoute(p, dest.route(), dest.face(), false);
                return Reroute.ROUTED;
            }
        }
        // 4. second grace period
        if (age <= 6) return Reroute.KEPT;
        // 5. back to a servo / retriever backlog
        Direction own = backlogFace();
        if (own != null) {
            if (p.dir == own) {
                depositBacklog(attachments[own.ordinal()], p);
                return Reroute.REMOVED;
            }
            p.toBacklog = true;
            p.destPos = worldPosition;
            p.destFace = own;
            p.setRoute(List.of(own));
            return Reroute.ROUTED;
        }
        Route target = null;
        for (Route r : table.backlogRoutes) {
            if (r.duct().backlogFace() == null) continue;
            if (r.duct().getBlockPos().equals(p.sourcePos)) { target = r; break; }
            if (target == null) target = r;
        }
        if (target != null) {
            applyRoute(p, target, target.duct().backlogFace(), true);
            return Reroute.ROUTED;
        }
        // 6. give up (only once mature, so freshly loaded areas never spill)
        if (age >= MATURE_AGE) {
            if (Config.homeless() == Config.HomelessBehavior.DROP && level != null) {
                ItemEntity e = new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5, p.stack.copy());
                e.setDeltaMovement(0, 0.1, 0);
                level.addFreshEntity(e);
            }
            return Reroute.REMOVED;
        }
        return Reroute.KEPT;
    }

    private static void applyRoute(org.vnek.modernitemducts.network.Packet p, Route r, Direction face, boolean backlog) {
        List<Direction> path = new ArrayList<>(r.path());
        path.add(face);
        p.setRoute(path);
        p.destPos = r.duct().getBlockPos();
        p.destFace = face;
        p.toBacklog = backlog;
    }

    private void spawnPacket(ItemStack stack, int speed, List<Direction> path, DuctBlockEntity destDuct, Direction destFace,
                             BlockPos source) {
        org.vnek.modernitemducts.network.Packet p = new org.vnek.modernitemducts.network.Packet(stack, speed);
        p.setRoute(path);
        p.destPos = destDuct.getBlockPos();
        p.destFace = destFace;
        p.sourcePos = source;
        incoming.add(p);
        markLedgerDirty();
        markPacketsChanged();
        setChanged();
    }

    // ================================================================== servos & retrievers (spec §6, §8.2)

    /**
     * Backlog phase for every attachment due this tick; attachments without a backlog queue
     * their extraction for the manager's following extraction phase.
     */
    public void runBacklogPhase(long gameTime, List<Runnable> extraction) {
        if (network == null) return;
        for (Direction d : Direction.values()) {
            Attachment a = attachments[d.ordinal()];
            if (a == null || !a.kind.isActive()) continue;
            if (gameTime % a.tier.interval != 0 || !a.isActive()) continue;
            if (a.hasBacklog()) {
                runBacklog(d, a);
            } else if (a.kind == AttachmentKind.SERVO) {
                extraction.add(() -> extract(d, a));
            } else {
                extraction.add(() -> retrieve(d, a));
            }
        }
    }

    private void runBacklog(Direction face, Attachment a) {
        DuctNetwork net = network;
        if (net == null) return;
        if (a.kind == AttachmentKind.RETRIEVER) {
            IItemHandler own = conn[face.ordinal()] == ConnectionType.INVENTORY ? neighborHandler(face) : null;
            if (own != null) {
                for (Iterator<Attachment.BacklogEntry> it = a.backlog().iterator(); it.hasNext(); ) {
                    Attachment.BacklogEntry e = it.next();
                    if (!a.filter.passes(e.template)) continue;
                    while (e.count > 0) {
                        int n = Math.min(e.count, e.template.getMaxStackSize());
                        ItemStack rem = ItemHandlerHelper.insertItem(own, e.template.copyWithCount(n), false);
                        e.count -= n - rem.getCount();
                        if (!rem.isEmpty()) break;
                    }
                    if (e.count <= 0) it.remove();
                }
            }
        }
        if (a.hasBacklog()) {
            RouteTable table = net.routes(this, false);
            if (table != null && !table.destinations.isEmpty()) {
                Attachment.BacklogEntry e = a.backlog().get(0);
                ItemStack cand = e.template.copyWithCount(Math.min(e.count, e.template.getMaxStackSize()));
                List<Destination> ordered = Routing.order(table.destinations, a);
                Routing.Selection sel = Routing.select(net, ordered, cand, this, face, null);
                if (sel != null) {
                    spawnPacket(cand.copyWithCount(sel.amount()), a.tier.speed, sel.dest().fullPath(), sel.dest().duct(),
                            sel.dest().face(), worldPosition);
                    e.count -= sel.amount();
                    if (e.count <= 0) a.backlog().remove(0);
                    Routing.advanceCursor(a, table.destinations, sel.dest());
                }
            }
        }
        setChanged();
        if (!a.hasBacklog()) requestSync();
    }

    private void extract(Direction face, Attachment a) {
        DuctNetwork net = network;
        if (net == null || conn[face.ordinal()] != ConnectionType.INPUT) return;
        IItemHandler source = neighborHandler(face);
        if (source == null) return;
        RouteTable table = net.routes(this, false);
        if (table == null || table.destinations.isEmpty()) return;
        List<Destination> ordered = Routing.order(table.destinations, a);
        BlockPos excludeInv = Config.preventSourceLoop() ? neighborPos(face) : null;
        for (int slot = 0; slot < source.getSlots(); slot++) {
            ItemStack cand = candidate(source, slot, a);
            if (cand.isEmpty() || !a.filter.passes(cand)) continue;
            Routing.Selection sel = Routing.select(net, ordered, cand, null, null, excludeInv);
            if (sel == null) continue;
            ItemStack got = Routing.extract(source, slot, cand, sel.amount(), a.tier.multiSlot);
            if (got.isEmpty()) continue;
            spawnPacket(got, a.tier.speed, sel.dest().fullPath(), sel.dest().duct(), sel.dest().face(), worldPosition);
            Routing.advanceCursor(a, table.destinations, sel.dest());
            return; // one packet per operation
        }
    }

    /** Candidate stack from a slot: the slot's count (T1-T3) or a full stack (T4/T5), capped by the setting. */
    private static ItemStack candidate(IItemHandler handler, int slot, Attachment a) {
        ItemStack in = handler.getStackInSlot(slot);
        if (in.isEmpty()) return ItemStack.EMPTY;
        ItemStack avail = handler.extractItem(slot, in.getCount(), true);
        if (avail.isEmpty()) return ItemStack.EMPTY;
        int want = a.tier.multiSlot ? avail.getMaxStackSize() : avail.getCount();
        return avail.copyWithCount(Math.max(1, Math.min(want, a.maxPerOp())));
    }

    private void retrieve(Direction face, Attachment a) {
        DuctNetwork net = network;
        if (net == null || conn[face.ordinal()] != ConnectionType.INVENTORY) return;
        if (neighborHandler(face) == null) return;
        RouteTable table = net.routes(this, false);
        if (table == null || table.destinations.isEmpty()) return;
        BlockPos ownInv = neighborPos(face);
        List<Destination> ordered = Routing.order(table.destinations, a);
        for (Destination src : ordered) {
            if (src.invPos().equals(ownInv)) continue;
            Attachment srcAtt = src.duct().getAttachment(src.face());
            if (srcAtt != null && srcAtt.kind == AttachmentKind.RETRIEVER) continue;
            IItemHandler handler = src.duct().neighborHandler(src.face());
            if (handler == null) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack cand = candidate(handler, slot, a);
                if (cand.isEmpty() || !a.filter.passes(cand)) continue;
                if (srcAtt != null && srcAtt.kind == AttachmentKind.FILTER && !srcAtt.filter.passes(cand)) continue;
                // Counts items already flowing to us as well (quirk 11 fixed).
                int n = Routing.acceptable(net, this, face, cand, null);
                if (n <= 0) continue;
                ItemStack got = Routing.extract(handler, slot, cand, Math.min(n, cand.getCount()), a.tier.multiSlot);
                if (got.isEmpty()) continue;
                // Reverse of our path to the source duct, then into our own inventory.
                List<Direction> there = src.route().path();
                List<Direction> back = new ArrayList<>(there.size() + 1);
                for (int i = there.size() - 1; i >= 0; i--) back.add(there.get(i).getOpposite());
                back.add(face);
                src.duct().spawnPacket(got, a.tier.speed, back, this, face, src.duct().getBlockPos());
                Routing.advanceCursor(a, table.destinations, src);
                return;
            }
        }
    }

    // ================================================================== external pushes (spec §2.4, §6.6, §6.7)

    private class FaceHandler implements IItemHandler {
        private final Direction face;

        FaceHandler(Direction face) {
            this.face = face;
        }

        @Override public int getSlots() { return 1; }

        @Override public @Nonnull ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }

        @Override public @Nonnull ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }

        @Override public int getSlotLimit(int slot) { return 64; }

        @Override public boolean isItemValid(int slot, @Nonnull ItemStack stack) { return true; }

        @Override
        public @Nonnull ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || pushGuard || network == null || level == null || level.isClientSide) return stack;
            pushGuard = true;
            try {
                return push(face, stack, simulate);
            } finally {
                pushGuard = false;
            }
        }
    }

    private ItemStack push(Direction face, ItemStack stack, boolean simulate) {
        DuctNetwork net = network;
        RouteTable table = net == null ? null : net.routes(this, false);
        if (table == null) return stack;
        ConnectionType c = conn[face.ordinal()];
        Attachment a = attachments[face.ordinal()];
        ItemStack cand;
        Routing.Selection sel;
        int speed;
        if (c == ConnectionType.INPUT && a != null && a.kind == AttachmentKind.SERVO) {
            cand = stack.copyWithCount(Math.min(stack.getCount(), a.maxPerOp()));
            if (!a.filter.passes(cand)) return stack;
            List<Destination> ordered = Routing.order(table.destinations, a);
            sel = Routing.select(net, ordered, cand, null, null, Config.preventSourceLoop() ? neighborPos(face) : null);
            speed = a.tier.speed;
        } else if (c == ConnectionType.INVENTORY) {
            cand = stack.copyWithCount(Math.min(stack.getCount(), 16));
            sel = Routing.select(net, table.destinations, cand, this, face, null);
            speed = 1;
        } else {
            return stack;
        }
        if (sel == null) return stack;
        if (!simulate) {
            spawnPacket(stack.copyWithCount(sel.amount()), speed, sel.dest().fullPath(), sel.dest().duct(), sel.dest().face(), worldPosition);
            if (a != null) Routing.advanceCursor(a, table.destinations, sel.dest());
        }
        return stack.copyWithCount(stack.getCount() - sel.amount());
    }

    /** Item handler capability (registered in ModernItemDucts#registerCapabilities). */
    @Nullable
    public IItemHandler handlerFor(@Nullable Direction side) {
        if (side == null || isRemoved() || !conn[side.ordinal()].isInventoryLike()) return null;
        return handlers[side.ordinal()];
    }

    // ================================================================== persistence (spec §15)

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("Blocked", (byte) blockedMask);
        ListTag atts = new ListTag();
        for (Direction d : Direction.values()) {
            Attachment a = attachments[d.ordinal()];
            if (a == null) continue;
            CompoundTag t = a.save(registries);
            t.putByte("Face", (byte) d.ordinal());
            atts.add(t);
        }
        tag.put("Attachments", atts);
        ListTag ps = new ListTag();
        forEachPacket(p -> ps.add(p.save(registries)));
        tag.put("Packets", ps);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        blockedMask = tag.getByte("Blocked") & 0x3F;
        Arrays.fill(attachments, null);
        ListTag atts = tag.getList("Attachments", Tag.TAG_COMPOUND);
        for (int i = 0; i < atts.size(); i++) {
            CompoundTag t = atts.getCompound(i);
            int face = t.getByte("Face");
            if (face >= 0 && face < 6) attachments[face] = Attachment.load(t, registries);
        }
        attachmentShape = null;
        packets.clear();
        incoming.clear();
        ListTag ps = tag.getList("Packets", Tag.TAG_COMPOUND);
        for (int i = 0; i < ps.size(); i++) {
            org.vnek.modernitemducts.network.Packet p = org.vnek.modernitemducts.network.Packet.load(ps.getCompound(i), registries);
            if (p != null) packets.add(p);
        }
        age = 0; // loaded from disk: grace period before giving up on homeless packets
    }

    // ================================================================== client sync (spec §14)

    public void sendSync() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag atts = new ListTag();
        for (Direction d : Direction.values()) {
            Attachment a = attachments[d.ordinal()];
            if (a == null) continue;
            CompoundTag t = new CompoundTag();
            t.putByte("Face", (byte) d.ordinal());
            t.putByte("Kind", (byte) a.kind.ordinal());
            t.putByte("Tier", (byte) a.tier.ordinal());
            t.putBoolean("Active", a.isActive());
            t.putBoolean("Clogged", a.hasBacklog());
            atts.add(t);
        }
        tag.put("A", atts);
        if (!type().opaque) {
            ListTag ps = new ListTag();
            for (org.vnek.modernitemducts.network.Packet p : packetsView()) {
                if (ps.size() >= CLIENT_PACKET_CAP) break;
                if (p.dir == null) continue;
                CompoundTag t = new CompoundTag();
                t.put("I", p.stack.copyWithCount(1).save(registries));
                t.putShort("P", (short) p.progress);
                t.putByte("D", (byte) p.dir.ordinal());
                t.putByte("S", (byte) p.speed);
                ps.add(t);
            }
            tag.put("P", ps);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readClient(tag, registries);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        readClient(pkt.getTag(), registries);
    }

    private void readClient(CompoundTag tag, HolderLookup.Provider registries) {
        Arrays.fill(attachments, null);
        Arrays.fill(clientActive, false);
        Arrays.fill(clientClogged, false);
        ListTag atts = tag.getList("A", Tag.TAG_COMPOUND);
        for (int i = 0; i < atts.size(); i++) {
            CompoundTag t = atts.getCompound(i);
            int f = t.getByte("Face");
            if (f < 0 || f >= 6) continue;
            attachments[f] = new Attachment(AttachmentKind.byId(t.getByte("Kind")), Tier.byId(t.getByte("Tier")));
            clientActive[f] = t.getBoolean("Active");
            clientClogged[f] = t.getBoolean("Clogged");
        }
        attachmentShape = null;
        clientPackets.clear();
        long now = level == null ? 0 : level.getGameTime();
        ListTag ps = tag.getList("P", Tag.TAG_COMPOUND);
        for (int i = 0; i < ps.size(); i++) {
            CompoundTag t = ps.getCompound(i);
            ItemStack s = ItemStack.parseOptional(registries, t.getCompound("I"));
            if (s.isEmpty()) continue;
            clientPackets.add(new ClientPacket(s, t.getShort("P"), Direction.from3DDataValue(t.getByte("D")), t.getByte("S"), now));
        }
    }

    /** Packets are drawn up to one block beyond the duct (used by DuctRenderer#getRenderBoundingBox). */
    public AABB renderBounds() {
        return new AABB(worldPosition).inflate(1);
    }

    /** What the client draws: a packet that left {@code progress} at {@code syncTime}. */
    public record ClientPacket(ItemStack stack, int progress, Direction dir, int speed, long syncTime) {}
}
