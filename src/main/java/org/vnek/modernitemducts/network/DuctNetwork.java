package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A maximal set of linked item ducts (spec §3). Networks are throw-away: any topology change
 * invalidates the whole network and the manager rebuilds it on the next tick, which also discards
 * every cached route table.
 */
public final class DuctNetwork {
    final DuctManager manager;
    private final Map<BlockPos, DuctBlockEntity> ducts = new LinkedHashMap<>();
    private final List<DuctBlockEntity> activeDucts = new ArrayList<>();
    private final Map<BlockPos, RouteTable> routes = new HashMap<>();
    private final Map<BlockPos, List<Packet>> ledger = new HashMap<>();
    private boolean ledgerDirty = true;
    boolean valid = true;

    DuctNetwork(DuctManager manager) {
        this.manager = manager;
    }

    void add(DuctBlockEntity duct) {
        ducts.put(duct.getBlockPos(), duct);
        if (duct.hasActiveAttachment()) activeDucts.add(duct);
    }

    @Nullable
    public DuctBlockEntity get(BlockPos pos) {
        return ducts.get(pos);
    }

    public Collection<DuctBlockEntity> ducts() {
        return ducts.values();
    }

    public List<DuctBlockEntity> activeDucts() {
        return activeDucts;
    }

    public int size() {
        return ducts.size();
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * Route table for an origin duct.
     *
     * @param urgent compute synchronously if missing (reroutes); otherwise queue it for the
     *               time-budgeted background pass and return null until it is ready.
     */
    @Nullable
    public RouteTable routes(DuctBlockEntity origin, boolean urgent) {
        RouteTable t = routes.get(origin.getBlockPos());
        if (t != null) return t;
        if (urgent) return computeRoutes(origin);
        manager.queueRoutes(origin);
        return null;
    }

    RouteTable computeRoutes(DuctBlockEntity origin) {
        RouteTable t = RouteTable.compute(origin, this);
        routes.put(origin.getBlockPos(), t);
        return t;
    }

    boolean hasRoutes(DuctBlockEntity origin) {
        return routes.containsKey(origin.getBlockPos());
    }

    // ------------------------------------------------------------------ in-transit ledger (§5.5)

    public void markLedgerDirty() {
        ledgerDirty = true;
    }

    /** Packets currently heading for the inventory at {@code invPos} (whatever face they arrive on). */
    public List<Packet> inTransit(BlockPos invPos) {
        if (ledgerDirty) rebuildLedger();
        List<Packet> l = ledger.get(invPos);
        return l == null ? Collections.emptyList() : l;
    }

    private void rebuildLedger() {
        ledger.clear();
        for (DuctBlockEntity d : ducts.values()) {
            d.forEachPacket(p -> {
                BlockPos target = p.targetInventory();
                if (target != null) ledger.computeIfAbsent(target, k -> new ArrayList<>()).add(p);
            });
        }
        ledgerDirty = false;
    }
}
