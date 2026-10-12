package com.uber.doma.domain_platform.audit_compliance_service.routing;

import java.util.Objects;

/**
 * Immutable result produced by
 * {@link BidirectionalCHSearch#findShortestPath(ContractionHierarchyGraph, long, long)}.
 *
 * <p>When no path is reachable the special constant
 * {@link #UNREACHABLE} is returned instead of {@code null}.</p>
 */
public final class CHRouteResult {

    /** Sentinel returned when the two endpoints are not connected. */
    public static final CHRouteResult UNREACHABLE =
            new CHRouteResult(-1L, -1L, Double.MAX_VALUE, -1L, 0L);

    private final long   originNodeId;
    private final long   destinationNodeId;
    private final double totalWeightMs;
    private final long   meetingNodeId;     // node where forward and backward searches met
    private final long   queryDurationNs;   // wall-clock cost of the search (nanoseconds)

    public CHRouteResult(final long   originNodeId,
                         final long   destinationNodeId,
                         final double totalWeightMs,
                         final long   meetingNodeId,
                         final long   queryDurationNs) {
        this.originNodeId      = originNodeId;
        this.destinationNodeId = destinationNodeId;
        this.totalWeightMs     = totalWeightMs;
        this.meetingNodeId     = meetingNodeId;
        this.queryDurationNs   = queryDurationNs;
    }

    /** Whether a path was found. */
    public boolean isReachable() {
        return totalWeightMs < Double.MAX_VALUE;
    }

    public long   getOriginNodeId()      { return originNodeId; }
    public long   getDestinationNodeId() { return destinationNodeId; }
    public double getTotalWeightMs()     { return totalWeightMs; }
    public long   getMeetingNodeId()     { return meetingNodeId; }
    public long   getQueryDurationNs()   { return queryDurationNs; }

    /** Query latency in microseconds (convenience accessor for log lines). */
    public long getQueryDurationUs() {
        return queryDurationNs / 1_000L;
    }

    @Override
    public String toString() {
        if (!isReachable()) {
            return "CHRouteResult{UNREACHABLE " + originNodeId + " -> " + destinationNodeId + '}';
        }
        return "CHRouteResult{" + originNodeId
                + " -> " + destinationNodeId
                + ", weight=" + totalWeightMs + "ms"
                + ", meeting=" + meetingNodeId
                + ", latency=" + getQueryDurationUs() + "µs"
                + '}';
    }
}
