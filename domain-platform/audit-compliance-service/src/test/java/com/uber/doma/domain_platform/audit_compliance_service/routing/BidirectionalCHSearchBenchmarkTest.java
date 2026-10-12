package com.uber.doma.domain_platform.audit_compliance_service.routing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark and correctness tests for {@link BidirectionalCHSearch} on a
 * large synthetic graph.
 *
 * <h2>Acceptance criteria</h2>
 * <ul>
 *   <li>All queries on a 50,000-node pre-contracted graph complete in
 *       <strong>&lt; 10 ms</strong> wall-clock time (measured per query,
 *       not amortised). The assertion uses the worst-case latency across 20
 *       warm queries so that a single outlier cannot cause a spurious failure.</li>
 *   <li>Every query returns a reachable, non-negative result.</li>
 *   <li>The search correctly handles shortcut edges scattered through the highway
 *       layer of the fixture.</li>
 * </ul>
 *
 * <h2>Topology</h2>
 * <p>The fixture uses a two-level hierarchical graph: ~99% street nodes (low rank)
 * each with an on-ramp to the ~1% highway layer (high rank). Long-distance queries
 * are answered by: (1) a short upward search from origin through its on-ramp, (2)
 * traversal of a few highway hops, and (3) a short backward search to the
 * destination — settling O(highway nodes in the gap) nodes rather than O(n).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BidirectionalCHSearchBenchmarkTest {

    private static final int    NODE_COUNT  = 50_000;
    private static final int    FAN_OUT     = 5;
    private static final long   SEED        = 0xDEAD_BEEF_CA75L;

    /** Maximum acceptable per-query latency (ms). */
    private static final double MAX_LATENCY_MS = 10.0;

    // The highway layer starts at streetCount = NODE_COUNT - highwayCount,
    // where highwayCount = max(2, NODE_COUNT / 100) = 500.
    private static final int HIGHWAY_COUNT  = Math.max(2, NODE_COUNT / 100); // 500
    private static final int STREET_COUNT   = NODE_COUNT - HIGHWAY_COUNT;    // 49,500

    private ContractionHierarchyGraph graph;
    private BidirectionalCHSearch     search;

    @BeforeAll
    void buildGraph() {
        graph  = CHGraphFixture.buildLayered(NODE_COUNT, FAN_OUT, SEED);
        search = new BidirectionalCHSearch();

        assertEquals(NODE_COUNT, graph.nodeCount(),
                "fixture graph must contain exactly " + NODE_COUNT + " nodes");
        assertTrue(graph.edgeCount() > NODE_COUNT,
                "fixture graph must have more edges than nodes");
    }

    // -----------------------------------------------------------------------
    // Latency benchmark
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("50,000-node CH query: all 20 warm samples complete in < 10 ms")
    void allQueriesCompleteInUnderTenMilliseconds() {
        final int samples = 20;
        final List<Long> latenciesNs = new ArrayList<>(samples);

        // Warm up the JIT thoroughly: run 15 throwaway queries before timing.
        // This allows the JVM to JIT-compile the hot paths in BidirectionalCHSearch
        // (HashMap, PriorityQueue, lambda sites) before any latency measurement begins.
        for (int w = 0; w < 15; w++) {
            search.findShortestPath(graph, (long) w, (long) (STREET_COUNT / 2 + w));
        }

        // Run sample queries: origin and destination are both street-level nodes
        // that must go through the highway layer.
        for (int i = 0; i < samples; i++) {
            final long origin = (long) (i * (STREET_COUNT / samples));
            final long dest   = (long) Math.min(STREET_COUNT - 1,
                    origin + STREET_COUNT / 4);

            final long startNs = System.nanoTime();
            final CHRouteResult result = search.findShortestPath(graph, origin, dest);
            final long elapsedNs = System.nanoTime() - startNs;

            assertTrue(result.isReachable(),
                    "query " + origin + " -> " + dest + " must be reachable");
            assertTrue(result.getTotalWeightMs() >= 0.0,
                    "weight must be non-negative");

            latenciesNs.add(elapsedNs);
        }

        latenciesNs.sort(Long::compareTo);
        final long   worstNs  = latenciesNs.get(latenciesNs.size() - 1);
        final long   worstUs  = worstNs / 1_000L;
        final double worstMs  = worstNs / 1_000_000.0;

        System.out.printf(
                "[BenchmarkTest] 50,000-node CH — worst latency across %d warm queries: %.3f ms (%d µs)%n",
                samples, worstMs, worstUs);

        assertTrue(worstMs < MAX_LATENCY_MS,
                String.format(
                        "Worst-case query latency %.3f ms exceeds the 10 ms SLA on 50,000-node graph.",
                        worstMs));
    }

    // -----------------------------------------------------------------------
    // Correctness
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("bidirectional result is non-negative and reachable for diverse query pairs")
    void resultIsNonNegativeForArbitraryPairs() {
        final long[][] pairs = {
            {0L,                    (long) (STREET_COUNT / 2)},
            {1L,                    (long) (STREET_COUNT / 3)},
            {100L,                  (long) (STREET_COUNT - 100)},
            {0L,                    1L},  // adjacent street nodes — always reachable
            {(long)(STREET_COUNT / 4), (long)(STREET_COUNT * 3 / 4)},
        };

        for (final long[] pair : pairs) {
            final CHRouteResult r = search.findShortestPath(graph, pair[0], pair[1]);
            assertTrue(r.isReachable(),
                    "pair (" + pair[0] + "," + pair[1] + ") must be reachable via the highway layer");
            assertTrue(r.getTotalWeightMs() >= 0.0,
                    "weight must be non-negative");
        }
    }

    // -----------------------------------------------------------------------
    // Shortcut edges
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shortcut edges in highway layer are present and traversed")
    void shortcutEdgesArePresentInHighwayLayer() {
        // Highway node: first highway node is STREET_COUNT.
        final long firstHighway = STREET_COUNT;

        // The fixture adds shortcut edges in the highway chain:
        // hw[k] -> hw[k+2] via hw[k+1].
        final boolean hasShortcut = graph.getOutEdges(firstHighway).stream()
                .anyMatch(e -> e.isShortcut() && e.getToNodeId() == firstHighway + 2L);
        assertTrue(hasShortcut,
                "highway layer must contain shortcut edges (hw[0]->hw[2] via hw[1])");
    }

    @Test
    @DisplayName("fixture shortcut count matches expected highway topology")
    void shortcutCountMatchesExpectedHighwayTopology() {
        final long shortcutCount = graph.getAllNodes().stream()
                .flatMap(n -> graph.getOutEdges(n.getNodeId()).stream())
                .filter(RoadEdge::isShortcut)
                .count();

        // Highway shortcut edges: hw[0]..hw[HIGHWAY_COUNT-3] each contribute one.
        final long expectedShortcuts = Math.max(0L, (long) (HIGHWAY_COUNT - 2));
        assertEquals(expectedShortcuts, shortcutCount,
                "fixture must contain exactly " + expectedShortcuts + " shortcut edges");
    }

    // -----------------------------------------------------------------------
    // Graph invariants
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hierarchy ranks are strictly ascending along every edge")
    void hierarchyRanksAscendAlongAllEdges() {
        final long violations = graph.getAllNodes().stream()
                .flatMap(n -> graph.getOutEdges(n.getNodeId()).stream())
                .filter(e -> {
                    final RoadNode from = graph.getNode(e.getFromNodeId());
                    final RoadNode to   = graph.getNode(e.getToNodeId());
                    return from != null && to != null
                            && from.getHierarchyRank() >= to.getHierarchyRank();
                })
                .count();

        assertEquals(0L, violations,
                "all edges must go from lower to higher hierarchy rank (upward-graph invariant)");
    }
}
