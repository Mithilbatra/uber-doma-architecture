package com.uber.doma.domain_platform.audit_compliance_service.routing;

import java.util.Random;

/**
 * Utility that builds synthetic {@link ContractionHierarchyGraph} instances
 * for testing and benchmarking.
 *
 * <h2>Graph topology</h2>
 * <p>The fixture generates a <em>two-level hierarchical graph</em> modelling
 * a contracted road network:
 * <ol>
 *   <li><strong>Level-0 nodes</strong> (ids 0 through {@code lowCount-1}) —
 *       "street-level" nodes with low hierarchy ranks. Each level-0 node has
 *       a mandatory edge to its successor and an edge to a randomly chosen
 *       level-1 node (its "on-ramp" into the highway layer).</li>
 *   <li><strong>Level-1 nodes</strong> (ids {@code lowCount} through
 *       {@code nodeCount-1}) — "highway" nodes with high hierarchy ranks.
 *       They are fully connected in a chain, with shortcut edges skipping
 *       every other node.</li>
 * </ol>
 *
 * <p>This topology mimics what a real CH preprocessing step produces: most
 * routing queries are answered by a very short upward search to the highway
 * layer followed by a very short backward search from the highway to the
 * destination — typically settling only O(log n) nodes in each direction.</p>
 *
 * <h2>Reachability</h2>
 * <p>Every level-0 node reaches every other node via its on-ramp edge into the
 * level-1 chain. All edges satisfy the CH upward-graph invariant: rank strictly
 * increases along every edge.</p>
 */
public final class CHGraphFixture {

    private CHGraphFixture() { /* utility class */ }

    /**
     * Builds a two-level hierarchical CH graph with exactly {@code nodeCount} nodes.
     *
     * @param nodeCount number of nodes; must be >= 4
     * @param fanOut    maximum additional out-degree per level-0 node (ignored in
     *                  this implementation; kept for API compatibility)
     * @param seed      random seed for reproducibility
     * @return a fully populated {@link ContractionHierarchyGraph}
     */
    public static ContractionHierarchyGraph buildLayered(final int nodeCount,
                                                         final int fanOut,
                                                         final long seed) {
        if (nodeCount < 4) {
            throw new IllegalArgumentException("nodeCount must be >= 4, got " + nodeCount);
        }
        final ContractionHierarchyGraph graph = new ContractionHierarchyGraph();
        final Random rng = new Random(seed);

        // ---- Partition into two levels ----------------------------------------
        // The "highway" layer is 1% of nodes (at least 2) at the top of the
        // hierarchy. The remainder are street-level nodes.
        final int highwayCount = Math.max(2, nodeCount / 100);
        final int streetCount  = nodeCount - highwayCount;

        // Street-level ids:  [0,          streetCount)
        // Highway-level ids: [streetCount, nodeCount)
        //
        // Hierarchy rank = nodeId — so all highway nodes have higher ranks than
        // all street nodes, satisfying the CH upward constraint.

        // ---- Register all nodes -----------------------------------------------
        for (int i = 0; i < nodeCount; i++) {
            final double lat = 40.7128 + (i / (double) nodeCount) * 0.5;
            final double lng = -74.006  + (i / (double) nodeCount) * 0.5;
            graph.addNode(new RoadNode(i, lat, lng, i));
        }

        // ---- Street-level chain (level 0) -------------------------------------
        // Each street node i must have:
        //   a) a mandatory edge to i+1 (stays within street level), AND
        //   b) a direct "on-ramp" edge to a random highway node
        //      (rank strictly higher than i, which is guaranteed since
        //       all highway ids > streetCount > i).
        for (int i = 0; i < streetCount - 1; i++) {
            // a) mandatory local edge i -> i+1 (1-5 ms)
            final double localWeight = 1.0 + rng.nextDouble() * 4.0;
            graph.addEdge(new RoadEdge(i, i + 1, localWeight));

            // b) on-ramp edge to a random highway node (5-20 ms)
            final int highwayTarget = streetCount + rng.nextInt(highwayCount);
            final double rampWeight = 5.0 + rng.nextDouble() * 15.0;
            graph.addEdge(new RoadEdge(i, highwayTarget, rampWeight));
        }
        // Last street node: only the on-ramp (no i+1 within street level).
        {
            final int lastStreet   = streetCount - 1;
            final int highwayTarget = streetCount + rng.nextInt(highwayCount);
            graph.addEdge(new RoadEdge(lastStreet, highwayTarget, 5.0 + rng.nextDouble() * 15.0));
        }

        // ---- Highway chain (level 1) ------------------------------------------
        // Highway nodes are chained: hw[k] -> hw[k+1] with a cheap edge (1-3 ms).
        // Shortcut edges: hw[k] -> hw[k+2] via hw[k+1] (even cheaper, 1-2 ms).
        for (int k = 0; k < highwayCount - 1; k++) {
            final long hwFrom = streetCount + k;
            final long hwTo   = streetCount + k + 1;
            final double chainWeight = 1.0 + rng.nextDouble() * 2.0;
            graph.addEdge(new RoadEdge(hwFrom, hwTo, chainWeight));
        }
        for (int k = 0; k < highwayCount - 2; k++) {
            final long hwFrom = streetCount + k;
            final long hwVia  = streetCount + k + 1;
            final long hwTo   = streetCount + k + 2;
            final double shortcutWeight = 1.0 + rng.nextDouble() * 1.5;
            graph.addEdge(new RoadEdge(hwFrom, hwTo, shortcutWeight, hwVia));
        }

        return graph;
    }
}
