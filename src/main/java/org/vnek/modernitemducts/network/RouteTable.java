package org.vnek.modernitemducts.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.vnek.modernitemducts.block.ConnectionType;
import org.vnek.modernitemducts.block.DuctBlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes from one origin duct to every endpoint duct of its network (spec §5.2).
 * Built by lock-step breadth-first expansion, so every physical path is a minimum-hop path;
 * dense / vacuum ducts only change the sort weight.
 */
public final class RouteTable {
    /** A path from the origin to an endpoint duct. */
    public record Route(DuctBlockEntity duct, List<Direction> path, int weight) {}

    /** One inventory-linked face of an endpoint duct. */
    public record Destination(Route route, Direction face) {
        public DuctBlockEntity duct() { return route.duct(); }

        public BlockPos invPos() { return route.duct().getBlockPos().relative(face); }

        /** Full turn list from the origin: path to the duct, then into the inventory. */
        public List<Direction> fullPath() {
            List<Direction> l = new ArrayList<>(route.path().size() + 1);
            l.addAll(route.path());
            l.add(face);
            return l;
        }
    }

    public final List<Route> endpoints;
    public final List<Destination> destinations;
    /** Ducts carrying a servo / retriever (can receive backlog), in discovery order. */
    public final List<Route> backlogRoutes;
    private final Map<BlockPos, Route> byPos = new HashMap<>();

    private RouteTable(List<Route> endpoints, List<Route> backlogRoutes) {
        this.endpoints = endpoints;
        this.backlogRoutes = backlogRoutes;
        List<Destination> dests = new ArrayList<>();
        for (Route r : endpoints) {
            byPos.put(r.duct().getBlockPos(), r);
            for (Direction d : Direction.values()) {
                if (r.duct().connection(d) == ConnectionType.INVENTORY) dests.add(new Destination(r, d));
            }
        }
        this.destinations = List.copyOf(dests);
    }

    public Route routeTo(BlockPos ductPos) {
        return byPos.get(ductPos);
    }

    private record Node(DuctBlockEntity duct, Node parent, Direction via, int weight, int length) {
        List<Direction> path() {
            Direction[] arr = new Direction[length];
            Node n = this;
            for (int i = length - 1; i >= 0; i--) {
                arr[i] = n.via;
                n = n.parent;
            }
            return List.of(arr);
        }
    }

    public static RouteTable compute(DuctBlockEntity origin, DuctNetwork net) {
        List<Route> endpoints = new ArrayList<>();
        List<Route> backlog = new ArrayList<>();
        Set<BlockPos> claimed = new HashSet<>();
        List<Node> frontier = new ArrayList<>();
        Node start = new Node(origin, null, null, 0, 0);
        claimed.add(origin.getBlockPos());
        frontier.add(start);
        record(start, endpoints, backlog);
        while (!frontier.isEmpty()) {
            List<Node> next = new ArrayList<>();
            for (Node n : frontier) {
                for (Direction d : Direction.values()) { // side order: down, up, north, south, west, east
                    DuctBlockEntity nb = n.duct.linkedDuct(d, net);
                    if (nb == null || !claimed.add(nb.getBlockPos())) continue;
                    Node child = new Node(nb, n, d, n.weight + nb.weight().cost, n.length + 1);
                    record(child, endpoints, backlog);
                    next.add(child);
                }
            }
            frontier = next;
        }
        // Stable sort keeps discovery order for ties.
        endpoints.sort(Comparator.comparingInt(Route::weight).thenComparingInt(r -> r.path().size()));
        return new RouteTable(endpoints, backlog);
    }

    private static void record(Node n, List<Route> endpoints, List<Route> backlog) {
        if (!n.duct.isEndpoint() && !n.duct.hasBacklogCapableFace()) return;
        Route r = new Route(n.duct, n.path(), n.weight);
        if (n.duct.isEndpoint()) endpoints.add(r);
        if (n.duct.hasBacklogCapableFace()) backlog.add(r);
    }
}
