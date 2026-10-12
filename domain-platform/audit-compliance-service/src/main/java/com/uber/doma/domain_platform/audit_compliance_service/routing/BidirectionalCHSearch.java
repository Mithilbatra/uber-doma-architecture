package com.uber.doma.domain_platform.audit_compliance_service.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Contraction Hierarchy (CH) bidirectional Dijkstra search engine.
 *
 * <h2>Algorithm overview</h2>
 * <ol>
 *   <li><strong>Forward search</strong> — runs standard Dijkstra from the
 *       <em>origin</em> on the <em>upward</em> graph (only relaxes edges
 *       whose target node has a strictly higher {@link RoadNode#getHierarchyRank()
 *       hierarchy rank} than the current node).</li>
 *   <li><strong>Backward search</strong> — runs standard Dijkstra from the
 *       <em>destination</em> on the <em>downward</em> graph, i.e. it traverses
 *       edges in reverse and only relaxes them when the source node (in the
 *       original graph direction) has a strictly higher rank than the current
 *       backward-search frontier node.</li>
 *   <li><strong>Meeting node</strong> — after every relaxation step the
 *       algorithm checks whether the newly settled node is also reached by
 *       the opposite search. The best combined distance across all such
 *       candidate meeting nodes is the shortest-path weight.</li>
 * </ol>
 *
 * <h2>Shortcut edge traversal</h2>
 * <p>Shortcut edges are treated identically to base edges during the priority-
 * queue relaxation: they carry a pre-computed weight that encodes the cost of
 * the entire path through the bypassed node. The {@link RoadEdge#isShortcut()}
 * flag is exposed for callers that need to unpack shortcuts into base-edge
 * sequences for turn-by-turn display (path unpacking is intentionally out of
 * scope for this component — it belongs in the presentation layer).</p>
 *
 * <h2>Complexity</h2>
 * <p>On a CH-preprocessed city-scale graph the search typically settles only
 * O(√n) nodes rather than the full O(n log n) of unidirectional Dijkstra,
 * yielding sub-millisecond to single-digit millisecond query times even on
 * graphs with tens of thousands of nodes.</p>
 */
@Component
public class BidirectionalCHSearch {

    private static final Logger log = LoggerFactory.getLogger(BidirectionalCHSearch.class);

    // -----------------------------------------------------------------------
    // Internal priority-queue entry
    // -----------------------------------------------------------------------

    private static final class Entry implements Comparable<Entry> {
        final long   nodeId;
        final double distMs;

        Entry(final long nodeId, final double distMs) {
            this.nodeId = nodeId;
            this.distMs = distMs;
        }

        @Override
        public int compareTo(final Entry other) {
            return Double.compare(this.distMs, other.distMs);
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Finds the shortest path from {@code originId} to {@code destId} in the
     * pre-contracted graph {@code graph} using bidirectional Dijkstra.
     *
     * @param graph    a fully pre-contracted {@link ContractionHierarchyGraph}
     * @param originId source node id (must be present in {@code graph})
     * @param destId   target node id (must be present in {@code graph})
     * @return the route result; {@link CHRouteResult#UNREACHABLE} if no path exists
     * @throws IllegalArgumentException if either node is unknown
     */
    public CHRouteResult findShortestPath(final ContractionHierarchyGraph graph,
                                          final long originId,
                                          final long destId) {
        validateNode(graph, originId);
        validateNode(graph, destId);

        final long startNs = System.nanoTime();

        // ---- trivial case: origin == destination ---------------------------
        if (originId == destId) {
            final long durationNs = System.nanoTime() - startNs;
            return new CHRouteResult(originId, destId, 0.0, originId, durationNs);
        }

        // ---- distance maps -------------------------------------------------
        // fwdDist[v] = best known distance from origin to v in the forward search
        final Map<Long, Double> fwdDist = new HashMap<>();
        // bwdDist[v] = best known distance from dest   to v in the backward search
        final Map<Long, Double> bwdDist = new HashMap<>();

        fwdDist.put(originId, 0.0);
        bwdDist.put(destId,   0.0);

        // ---- priority queues ----------------------------------------------
        final PriorityQueue<Entry> fwdQueue = new PriorityQueue<>();
        final PriorityQueue<Entry> bwdQueue = new PriorityQueue<>();
        fwdQueue.add(new Entry(originId, 0.0));
        bwdQueue.add(new Entry(destId,   0.0));

        // ---- settled sets -------------------------------------------------
        final Map<Long, Boolean> fwdSettled = new HashMap<>();
        final Map<Long, Boolean> bwdSettled = new HashMap<>();

        // ---- best combined distance across all meeting nodes ---------------
        double bestDist     = Double.MAX_VALUE;
        long   meetingNode  = -1L;

        // ---- main loop ----------------------------------------------------
        // Alternate between forward and backward steps to keep the two
        // frontiers balanced and detect the meeting node early.
        while (!fwdQueue.isEmpty() || !bwdQueue.isEmpty()) {

            // Forward step
            if (!fwdQueue.isEmpty()) {
                final Entry curr = fwdQueue.poll();
                if (fwdSettled.containsKey(curr.nodeId)) {
                    continue;
                }
                fwdSettled.put(curr.nodeId, Boolean.TRUE);

                // Pruning: if the current forward distance already exceeds
                // the best combined distance found so far, skip expansion.
                if (curr.distMs >= bestDist) {
                    break;
                }

                // Check whether the backward search has reached this node.
                final Double bwdReach = bwdDist.get(curr.nodeId);
                if (bwdReach != null) {
                    final double combined = curr.distMs + bwdReach;
                    if (combined < bestDist) {
                        bestDist    = combined;
                        meetingNode = curr.nodeId;
                    }
                }

                // Relax outgoing edges — upward graph only (rank must increase).
                final int currRank = graph.getNode(curr.nodeId).getHierarchyRank();
                for (final RoadEdge edge : graph.getOutEdges(curr.nodeId)) {
                    final RoadNode neighbour = graph.getNode(edge.getToNodeId());
                    if (neighbour == null) continue;
                    // CH upward constraint: only traverse to higher-rank nodes
                    if (neighbour.getHierarchyRank() <= currRank) continue;

                    final double newDist = curr.distMs + edge.getWeightMs();
                    final Double existing = fwdDist.get(edge.getToNodeId());
                    if (existing == null || newDist < existing) {
                        fwdDist.put(edge.getToNodeId(), newDist);
                        fwdQueue.add(new Entry(edge.getToNodeId(), newDist));
                    }
                }
            }

            // Backward step
            if (!bwdQueue.isEmpty()) {
                final Entry curr = bwdQueue.poll();
                if (bwdSettled.containsKey(curr.nodeId)) {
                    continue;
                }
                bwdSettled.put(curr.nodeId, Boolean.TRUE);

                if (curr.distMs >= bestDist) {
                    break;
                }

                // Check whether the forward search has reached this node.
                final Double fwdReach = fwdDist.get(curr.nodeId);
                if (fwdReach != null) {
                    final double combined = fwdReach + curr.distMs;
                    if (combined < bestDist) {
                        bestDist    = combined;
                        meetingNode = curr.nodeId;
                    }
                }

                // Relax incoming edges in the reverse direction — the backward
                // search traverses edges from high-rank to low-rank endpoints
                // (i.e. the reversed upward graph), so we iterate in-edges and
                // require that the source node (original direction) has a higher
                // rank than the current backward-frontier node.
                final int currRank = graph.getNode(curr.nodeId).getHierarchyRank();
                for (final RoadEdge edge : graph.getInEdges(curr.nodeId)) {
                    final RoadNode source = graph.getNode(edge.getFromNodeId());
                    if (source == null) continue;
                    // CH downward constraint for backward search
                    if (source.getHierarchyRank() <= currRank) continue;

                    final double newDist = curr.distMs + edge.getWeightMs();
                    final Double existing = bwdDist.get(edge.getFromNodeId());
                    if (existing == null || newDist < existing) {
                        bwdDist.put(edge.getFromNodeId(), newDist);
                        bwdQueue.add(new Entry(edge.getFromNodeId(), newDist));
                    }
                }
            }
        }

        final long durationNs = System.nanoTime() - startNs;

        if (bestDist == Double.MAX_VALUE) {
            log.debug("[BidirectionalCHSearch] no path {} -> {} ({}µs)",
                    originId, destId, durationNs / 1_000);
            return CHRouteResult.UNREACHABLE;
        }

        final CHRouteResult result =
                new CHRouteResult(originId, destId, bestDist, meetingNode, durationNs);
        log.debug("[BidirectionalCHSearch] found {} -> {} weight={}ms meeting={} latency={}µs",
                originId, destId, bestDist, meetingNode, result.getQueryDurationUs());
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void validateNode(final ContractionHierarchyGraph graph, final long nodeId) {
        if (graph.getNode(nodeId) == null) {
            throw new IllegalArgumentException("Node not found in graph: " + nodeId);
        }
    }
}
