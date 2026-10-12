package com.uber.doma.domain_platform.audit_compliance_service.routing;

/**
 * A directed, weighted edge in the pre-contracted road-network graph.
 *
 * <p>An edge may be either a <em>base edge</em> (corresponds to an actual
 * road segment) or a <em>shortcut edge</em> introduced during Contraction
 * Hierarchy preprocessing. Shortcut edges replace paths through a contracted
 * node with a single hop so that the bidirectional query never has to expand
 * intermediate nodes.
 *
 * <p>The flag {@link #isShortcut()} lets the unpacking phase distinguish the
 * two types: base edges are returned verbatim in the final turn-by-turn path,
 * while shortcut edges are recursively unpacked into the sequence of base
 * edges they represent.</p>
 */
public final class RoadEdge {

    private final long   fromNodeId;
    private final long   toNodeId;
    private final double weightMs;        // edge traversal cost in milliseconds
    private final boolean shortcut;       // true ↔ CH shortcut (not a real road segment)
    private final long   shortcutViaNode; // intermediate node bypassed (only valid when shortcut==true)

    /** Constructs a base (non-shortcut) edge. */
    public RoadEdge(final long fromNodeId, final long toNodeId, final double weightMs) {
        this(fromNodeId, toNodeId, weightMs, false, -1L);
    }

    /** Constructs a shortcut edge that bypasses {@code viaNodeId}. */
    public RoadEdge(final long fromNodeId,
                    final long toNodeId,
                    final double weightMs,
                    final long viaNodeId) {
        this(fromNodeId, toNodeId, weightMs, true, viaNodeId);
    }

    private RoadEdge(final long fromNodeId,
                     final long toNodeId,
                     final double weightMs,
                     final boolean shortcut,
                     final long shortcutViaNode) {
        this.fromNodeId       = fromNodeId;
        this.toNodeId         = toNodeId;
        this.weightMs         = weightMs;
        this.shortcut         = shortcut;
        this.shortcutViaNode  = shortcutViaNode;
    }

    public long    getFromNodeId()      { return fromNodeId; }
    public long    getToNodeId()        { return toNodeId; }
    public double  getWeightMs()        { return weightMs; }
    public boolean isShortcut()         { return shortcut; }
    public long    getShortcutViaNode() { return shortcutViaNode; }

    @Override
    public String toString() {
        if (shortcut) {
            return "RoadEdge{" + fromNodeId + " --[" + weightMs + "ms,via=" + shortcutViaNode + "]--> " + toNodeId + '}';
        }
        return "RoadEdge{" + fromNodeId + " --[" + weightMs + "ms]--> " + toNodeId + '}';
    }
}
