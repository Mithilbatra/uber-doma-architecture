package com.uber.doma.domain_platform.audit_compliance_service.routing;

import java.util.Objects;

/**
 * Immutable value object representing a single node (intersection) in a
 * pre-contracted road-network graph used by the Contraction Hierarchy (CH)
 * router.
 *
 * <p>Every node carries a {@code hierarchyRank} that controls the contraction
 * order: nodes with a <em>higher</em> rank were contracted later and appear in
 * the "upper" layers of the hierarchy. The bidirectional search only relaxes
 * edges that go <em>up</em> the hierarchy (rank strictly increasing), which is
 * what makes CH queries sub-millisecond even on city-scale graphs.</p>
 *
 * <p>The lat/lng coordinates are stored for debugging and display purposes only;
 * all routing arithmetic uses the numeric {@code nodeId}.</p>
 */
public final class RoadNode {

    private final long   nodeId;
    private final double latitude;
    private final double longitude;
    private final int    hierarchyRank;

    public RoadNode(final long nodeId,
                    final double latitude,
                    final double longitude,
                    final int hierarchyRank) {
        this.nodeId        = nodeId;
        this.latitude      = latitude;
        this.longitude     = longitude;
        this.hierarchyRank = hierarchyRank;
    }

    public long   getNodeId()        { return nodeId; }
    public double getLatitude()      { return latitude; }
    public double getLongitude()     { return longitude; }
    public int    getHierarchyRank() { return hierarchyRank; }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof RoadNode)) return false;
        return nodeId == ((RoadNode) other).nodeId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(nodeId);
    }

    @Override
    public String toString() {
        return "RoadNode{id=" + nodeId + ", rank=" + hierarchyRank + '}';
    }
}
