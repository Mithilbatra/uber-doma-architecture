package com.uber.doma.domain_platform.audit_compliance_service.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a pre-contracted road-network graph.
 *
 * <p>The graph is <em>directed</em>. During Contraction Hierarchy (CH)
 * preprocessing, both base edges and shortcut edges are added. The
 * bidirectional CH query uses:
 * <ul>
 *   <li>{@link #getOutEdges(long)} — forward graph (edge goes from low-rank
 *       node to high-rank node, i.e. "upward" in the hierarchy).</li>
 *   <li>{@link #getInEdges(long)} — reverse/backward graph (edge goes from
 *       high-rank node back to low-rank node, used by the backward search
 *       starting from the destination).</li>
 * </ul>
 *
 * <p>Nodes must be {@link #addNode(RoadNode) registered} before edges that
 * reference them are added. Adding a duplicate node id is a no-op.</p>
 */
public final class ContractionHierarchyGraph {

    /** Forward adjacency list: nodeId → list of outgoing edges. */
    private final Map<Long, List<RoadEdge>> outEdges = new HashMap<>();

    /** Backward adjacency list: nodeId → list of incoming edges (reverse graph). */
    private final Map<Long, List<RoadEdge>> inEdges  = new HashMap<>();

    /** Node metadata indexed by nodeId. */
    private final Map<Long, RoadNode> nodes = new HashMap<>();

    // -----------------------------------------------------------------------
    // Graph construction
    // -----------------------------------------------------------------------

    /**
     * Registers a node. Duplicate nodeIds are silently ignored.
     */
    public void addNode(final RoadNode node) {
        nodes.putIfAbsent(node.getNodeId(), node);
        outEdges.putIfAbsent(node.getNodeId(), new ArrayList<>());
        inEdges .putIfAbsent(node.getNodeId(), new ArrayList<>());
    }

    /**
     * Adds a directed edge. The source and target nodes must have already been
     * {@link #addNode(RoadNode) registered}.
     *
     * @param edge the edge to add (may be a base or shortcut edge)
     * @throws IllegalArgumentException if either endpoint is unknown
     */
    public void addEdge(final RoadEdge edge) {
        final long from = edge.getFromNodeId();
        final long to   = edge.getToNodeId();
        if (!nodes.containsKey(from)) {
            throw new IllegalArgumentException("Unknown source node: " + from);
        }
        if (!nodes.containsKey(to)) {
            throw new IllegalArgumentException("Unknown target node: " + to);
        }
        outEdges.get(from).add(edge);
        inEdges .get(to)  .add(edge);
    }

    // -----------------------------------------------------------------------
    // Query helpers
    // -----------------------------------------------------------------------

    /** Returns outgoing edges from {@code nodeId}, or an empty list if unknown. */
    public List<RoadEdge> getOutEdges(final long nodeId) {
        final List<RoadEdge> edges = outEdges.get(nodeId);
        return edges == null ? Collections.emptyList() : Collections.unmodifiableList(edges);
    }

    /** Returns incoming edges into {@code nodeId} (reverse graph), or an empty list. */
    public List<RoadEdge> getInEdges(final long nodeId) {
        final List<RoadEdge> edges = inEdges.get(nodeId);
        return edges == null ? Collections.emptyList() : Collections.unmodifiableList(edges);
    }

    /** Returns the {@link RoadNode} for {@code nodeId}, or {@code null}. */
    public RoadNode getNode(final long nodeId) {
        return nodes.get(nodeId);
    }

    /** Returns all nodes in insertion order (deterministic in tests). */
    public Collection<RoadNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /** Returns the total number of registered nodes. */
    public int nodeCount() {
        return nodes.size();
    }

    /** Returns the total number of edges (forward direction). */
    public int edgeCount() {
        return outEdges.values().stream().mapToInt(List::size).sum();
    }
}
