package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.vnek.modernitemducts.Config;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-level owner of all loaded item ducts and their networks.
 * <p>
 * Ticked once per server level tick (at the end), in this order (spec §3, §6.2):
 * <ol>
 *   <li>duct aging + chunk-boundary watching,</li>
 *   <li>connection re-scans that were requested since last tick,</li>
 *   <li>rebuilding invalidated networks,</li>
 *   <li>packet movement (packets handed to a neighbour start moving next tick),</li>
 *   <li>background route computation within the time budget,</li>
 *   <li>servo / retriever backlog phase, then extraction phase,</li>
 *   <li>client sync for transparent ducts whose packet list changed.</li>
 * </ol>
 */
public final class DuctManager {
    private static final Map<Level, DuctManager> MANAGERS = new HashMap<>();

    private final ServerLevel level;
    private final Set<DuctBlockEntity> ducts = new LinkedHashSet<>();
    private final Set<DuctBlockEntity> scanQueue = new LinkedHashSet<>();
    private final Set<DuctBlockEntity> orphans = new LinkedHashSet<>();
    private final List<DuctNetwork> networks = new ArrayList<>();
    private final ArrayDeque<DuctBlockEntity> routeQueue = new ArrayDeque<>();
    private final Set<DuctBlockEntity> syncQueue = new LinkedHashSet<>();

    private DuctManager(ServerLevel level) {
        this.level = level;
    }

    public static DuctManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, l -> new DuctManager((ServerLevel) l));
    }

    public ServerLevel level() {
        return level;
    }

    // ------------------------------------------------------------------ membership

    public void add(DuctBlockEntity duct) {
        if (ducts.add(duct)) {
            orphans.add(duct);
            scanQueue.add(duct);
        }
    }

    public void remove(DuctBlockEntity duct) {
        if (!ducts.remove(duct)) return;
        invalidate(duct.network());
        orphans.remove(duct);
        scanQueue.remove(duct);
        syncQueue.remove(duct);
        // Neighbours that linked to it must notice it is gone.
        for (Direction d : Direction.values()) {
            BlockPos np = duct.getBlockPos().relative(d);
            if (level.hasChunkAt(np) && level.getBlockEntity(np) instanceof DuctBlockEntity nb && ducts.contains(nb)) {
                requestScan(nb);
            }
        }
    }

    public boolean contains(DuctBlockEntity duct) {
        return ducts.contains(duct);
    }

    public void requestScan(DuctBlockEntity duct) {
        if (ducts.contains(duct)) scanQueue.add(duct);
    }

    public void requestSync(DuctBlockEntity duct) {
        syncQueue.add(duct);
    }

    /** Throws a network away; its ducts are regrouped on the next tick. */
    public void invalidate(DuctNetwork net) {
        if (net == null || !net.valid) return;
        net.valid = false;
        networks.remove(net);
        for (DuctBlockEntity d : net.ducts()) {
            d.setNetwork(null);
            if (ducts.contains(d)) orphans.add(d);
        }
    }

    void queueRoutes(DuctBlockEntity origin) {
        if (!routeQueue.contains(origin)) routeQueue.add(origin);
    }

    public int networkCount() {
        return networks.size();
    }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        // 1. age + chunk-boundary watch
        for (DuctBlockEntity d : ducts) d.preTick();

        // 2. connection scans (may invalidate networks)
        while (!scanQueue.isEmpty()) {
            List<DuctBlockEntity> batch = new ArrayList<>(scanQueue);
            scanQueue.clear();
            for (DuctBlockEntity d : batch) if (ducts.contains(d) && !d.isRemoved()) d.refreshConnections();
        }

        // 3. regroup orphans into networks by flood fill
        if (!orphans.isEmpty()) rebuildNetworks();

        // 4. move packets: first activate everything that arrived last tick, then move
        List<DuctNetwork> nets = new ArrayList<>(networks);
        for (DuctNetwork n : nets) for (DuctBlockEntity d : n.ducts()) d.mergeIncoming();
        for (DuctNetwork n : nets) {
            for (DuctBlockEntity d : new ArrayList<>(n.ducts())) {
                if (d.network() != null) d.tickPackets();
            }
        }

        // 5. background route tables
        long deadline = System.nanoTime() + Config.routeBudgetNanos();
        boolean first = true;
        while (!routeQueue.isEmpty() && (first || System.nanoTime() < deadline)) {
            DuctBlockEntity d = routeQueue.poll();
            DuctNetwork n = d.network();
            if (n != null && n.valid && !n.hasRoutes(d)) {
                n.computeRoutes(d);
                first = false;
            }
        }

        // 6. servo / retriever operations
        long time = level.getGameTime();
        List<DuctBlockEntity> active = new ArrayList<>();
        for (DuctNetwork n : networks) active.addAll(n.activeDucts());
        List<Runnable> extraction = new ArrayList<>();
        for (DuctBlockEntity d : active) d.runBacklogPhase(time, extraction);
        for (Runnable r : extraction) r.run();

        // 7. client sync
        if (!syncQueue.isEmpty()) {
            for (DuctBlockEntity d : syncQueue) if (!d.isRemoved()) d.sendSync();
            syncQueue.clear();
        }
    }

    private void rebuildNetworks() {
        List<DuctBlockEntity> pending = new ArrayList<>(orphans);
        orphans.clear();
        for (DuctBlockEntity seed : pending) {
            if (seed.network() != null || !ducts.contains(seed) || seed.isRemoved()) continue;
            DuctNetwork net = new DuctNetwork(this);
            ArrayDeque<DuctBlockEntity> queue = new ArrayDeque<>();
            seed.setNetwork(net);
            queue.add(seed);
            while (!queue.isEmpty()) {
                DuctBlockEntity d = queue.poll();
                net.add(d);
                for (Direction dir : Direction.values()) {
                    DuctBlockEntity nb = d.linkedDuctUnchecked(dir);
                    if (nb != null && nb.network() == null && ducts.contains(nb) && !nb.isRemoved()) {
                        nb.setNetwork(net);
                        queue.add(nb);
                    }
                }
            }
            networks.add(net);
            // Pre-compute routes for ducts that will want them so extraction resumes quickly.
            for (DuctBlockEntity d : net.activeDucts()) queueRoutes(d);
        }
    }

    /** Forge event hooks (registered on the game bus). */
    public static final class Events {
        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            if (event.getLevel() instanceof ServerLevel sl) {
                DuctManager m = MANAGERS.get(sl);
                if (m != null) m.tick();
            }
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            MANAGERS.remove(event.getLevel());
        }
    }
}
