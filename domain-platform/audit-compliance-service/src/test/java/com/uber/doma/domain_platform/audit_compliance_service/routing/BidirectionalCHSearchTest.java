package com.uber.doma.domain_platform.audit_compliance_service.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BidirectionalCHSearch}.
 *
 * <p>Every test is deterministic and requires no Docker, Spring context, or
 * external dependencies — only a JVM and JUnit 5.</p>
 *
 * <p>Test coverage includes:</p>
 * <ul>
 *   <li>Trivial same-node query (0-cost, 0-hop).</li>
 *   <li>Direct single-edge path (base and shortcut edges).</li>
 *   <li>Multi-hop path across a small linear graph.</li>
 *   <li>Shortcut edge traversal (a shortcut connecting node 0 to node 2
 *       via node 1 is recognised as a shortcut and carries the correct weight).</li>
 *   <li>No-path / disconnected nodes returns {@link CHRouteResult#UNREACHABLE}.</li>
 *   <li>Unknown node id throws {@link IllegalArgumentException}.</li>
 *   <li>Correctness on a small fully-connected triangle topology.</li>
 * </ul>
 */
class BidirectionalCHSearchTest {

    private BidirectionalCHSearch search;

    @BeforeEach
    void setUp() {
        search = new BidirectionalCHSearch();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a simple linear graph: 0 → 1 → 2 → 3.
     * Hierarchy ranks match node ids (ascending), so the upward graph is
     * just the forward direction.
     */
    private ContractionHierarchyGraph linearGraph() {
        final ContractionHierarchyGraph g = new ContractionHierarchyGraph();
        for (int i = 0; i < 4; i++) {
            g.addNode(new RoadNode(i, 0, 0, i));
        }
        g.addEdge(new RoadEdge(0, 1, 10.0));
        g.addEdge(new RoadEdge(1, 2, 20.0));
        g.addEdge(new RoadEdge(2, 3, 15.0));
        // Shortcut: 0 → 2 via node 1 (weight 28 < 10+20=30 to distinguish).
        g.addEdge(new RoadEdge(0, 2, 28.0, 1));
        return g;
    }

    // -----------------------------------------------------------------------
    // Trivial case
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("same origin and destination returns 0-cost result")
    void sameOriginAndDestinationReturnsZeroCost() {
        final ContractionHierarchyGraph g = linearGraph();
        final CHRouteResult result = search.findShortestPath(g, 0L, 0L);

        assertTrue(result.isReachable(), "same-node query must be reachable");
        assertEquals(0.0, result.getTotalWeightMs(), 1e-9);
        assertEquals(0L, result.getOriginNodeId());
        assertEquals(0L, result.getDestinationNodeId());
    }

    // -----------------------------------------------------------------------
    // Single-hop base edge
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("direct base-edge path is found with correct weight")
    void directBaseEdgePathCorrect() {
        final ContractionHierarchyGraph g = linearGraph();
        final CHRouteResult result = search.findShortestPath(g, 0L, 1L);

        assertTrue(result.isReachable());
        assertEquals(10.0, result.getTotalWeightMs(), 1e-9,
                "direct hop 0→1 must cost exactly 10 ms");
    }

    // -----------------------------------------------------------------------
    // Shortcut edge traversal
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shortcut edge 0→2 (via 1) is used when it is cheaper than two hops")
    void shortcutEdgeUsedWhenCheaper() {
        final ContractionHierarchyGraph g = linearGraph();
        final CHRouteResult result = search.findShortestPath(g, 0L, 2L);

        assertTrue(result.isReachable());
        // Two-hop cost: 10 + 20 = 30 ms.  Shortcut cost: 28 ms.
        assertEquals(28.0, result.getTotalWeightMs(), 1e-9,
                "bidirectional search must pick the cheaper shortcut (28 ms) over two hops (30 ms)");
    }

    @Test
    @DisplayName("shortcut edge can be identified via RoadEdge.isShortcut()")
    void shortcutEdgeFlagIsCorrect() {
        final ContractionHierarchyGraph g = linearGraph();

        // Verify the fixture: exactly one edge from 0 to 2 should be a shortcut.
        final long shortcutCount = g.getOutEdges(0L).stream()
                .filter(e -> e.getToNodeId() == 2L && e.isShortcut())
                .count();
        assertEquals(1, shortcutCount, "exactly one shortcut from 0 to 2 must be present");

        final RoadEdge shortcut = g.getOutEdges(0L).stream()
                .filter(e -> e.getToNodeId() == 2L && e.isShortcut())
                .findFirst()
                .orElseThrow();
        assertEquals(1L, shortcut.getShortcutViaNode(),
                "shortcut via-node must be 1");
    }

    // -----------------------------------------------------------------------
    // Multi-hop path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("multi-hop path 0→3 picks the optimal combination of shortcuts and base edges")
    void multiHopPathCorrect() {
        final ContractionHierarchyGraph g = linearGraph();
        final CHRouteResult result = search.findShortestPath(g, 0L, 3L);

        assertTrue(result.isReachable());
        // Possible paths:
        // 0→1→2→3  : 10 + 20 + 15 = 45 ms
        // 0→2→3    : 28 + 15 = 43 ms  (uses shortcut 0→2)
        assertEquals(43.0, result.getTotalWeightMs(), 1e-9,
                "optimal path uses shortcut 0→2 then base 2→3 = 43 ms");
    }

    // -----------------------------------------------------------------------
    // Disconnected / unreachable
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("disconnected nodes return UNREACHABLE sentinel")
    void disconnectedNodesReturnUnreachable() {
        final ContractionHierarchyGraph g = new ContractionHierarchyGraph();
        g.addNode(new RoadNode(10L, 0, 0, 0));
        g.addNode(new RoadNode(20L, 0, 0, 1));
        // No edges → 10 and 20 are disconnected.

        final CHRouteResult result = search.findShortestPath(g, 10L, 20L);

        assertFalse(result.isReachable(), "disconnected nodes must return UNREACHABLE");
        assertSame(CHRouteResult.UNREACHABLE, result,
                "must return the UNREACHABLE sentinel instance");
    }

    // -----------------------------------------------------------------------
    // Unknown node validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("unknown origin throws IllegalArgumentException")
    void unknownOriginThrowsIllegalArgument() {
        final ContractionHierarchyGraph g = linearGraph();
        assertThrows(IllegalArgumentException.class,
                () -> search.findShortestPath(g, 999L, 1L));
    }

    @Test
    @DisplayName("unknown destination throws IllegalArgumentException")
    void unknownDestinationThrowsIllegalArgument() {
        final ContractionHierarchyGraph g = linearGraph();
        assertThrows(IllegalArgumentException.class,
                () -> search.findShortestPath(g, 0L, 999L));
    }

    // -----------------------------------------------------------------------
    // Triangle topology
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("triangle topology: lowest-weight path is selected correctly")
    void triangleTopologySelectsLowestWeightPath() {
        // Nodes: A(rank=0), B(rank=1), C(rank=2)
        // Edges: A→B=5, A→C=20, B→C=3  → optimal A→C via B = 8
        final ContractionHierarchyGraph g = new ContractionHierarchyGraph();
        g.addNode(new RoadNode(0L, 0, 0, 0)); // A
        g.addNode(new RoadNode(1L, 0, 0, 1)); // B
        g.addNode(new RoadNode(2L, 0, 0, 2)); // C
        g.addEdge(new RoadEdge(0L, 1L,  5.0));
        g.addEdge(new RoadEdge(1L, 2L,  3.0));
        g.addEdge(new RoadEdge(0L, 2L, 20.0)); // direct but expensive

        final CHRouteResult result = search.findShortestPath(g, 0L, 2L);

        assertTrue(result.isReachable());
        assertEquals(8.0, result.getTotalWeightMs(), 1e-9,
                "A→B→C = 5+3 = 8 ms must beat the direct A→C = 20 ms edge");
    }

    // -----------------------------------------------------------------------
    // Result metadata
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("query duration is positive and meeting node is valid")
    void resultMetadataIsPopulated() {
        final ContractionHierarchyGraph g = linearGraph();
        final CHRouteResult result = search.findShortestPath(g, 0L, 3L);

        assertTrue(result.getQueryDurationNs() > 0,
                "query duration must be positive");
        assertTrue(result.getMeetingNodeId() >= 0,
                "meeting node id must be a valid node id");
        assertEquals(0L, result.getOriginNodeId());
        assertEquals(3L, result.getDestinationNodeId());
    }
}
