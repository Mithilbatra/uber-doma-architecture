package com.uber.doma.domain_platform.audit_compliance_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Micrometer instrumentation for the audit-compliance-service.
 *
 * <p>Exposes the latency and throughput of the critical domain algorithm
 * (immutable audit-log writes plus H3 spatial clustering) as a Micrometer
 * {@link Timer} and {@link DistributionSummary} so that the
 * {@code p50}, {@code p95}, and {@code p99} percentiles can be scraped
 * from the {@code /actuator/metrics} endpoint and visualized in Grafana.</p>
 *
 * <p>The primary timer is named
 * {@code uber.audit-compliance-service.duration} per the architectural
 * context; an additional counter and distribution summary cover the
 * booking-SLA boundary and the H3 cluster size so SREs can correlate
 * the latency of an audit write with the size of the spatial cluster
 * that triggered it.</p>
 */
@Component
public class AuditComplianceMetrics {

    private final Timer auditDurationTimer;
    private final Timer h3ClusteringTimer;
    private final Counter auditPersistedCounter;
    private final Counter auditDeferredCounter;
    private final Counter auditDroppedCounter;
    private final DistributionSummary h3ClusterSizeSummary;

    public AuditComplianceMetrics(final MeterRegistry registry) {
        // Primary timer for the bounded context: covers the critical path
        // of writing an immutable audit record (the domain algorithm).
        this.auditDurationTimer = Timer.builder("uber.audit-compliance-service.duration")
                .description("Time taken to record an immutable regulatory audit event "
                        + "(p50 / p95 / p99 are exported to Prometheus)")
                .publishPercentiles(0.50, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);

        // H3 spatial clustering timer — the second most expensive
        // algorithmic block in the service.
        this.h3ClusteringTimer = Timer.builder("uber.audit-compliance-service.h3.cluster.duration")
                .description("Time taken to cluster audit events into H3 hexagonal cells")
                .publishPercentiles(0.50, 0.95, 0.99)
                .minimumExpectedValue(Duration.of(100, ChronoUnit.MICROS))
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(registry);

        // Outcome counters — let dashboards break down throughput by the
        // booking-SLA outcome (persisted / deferred / dropped).
        this.auditPersistedCounter = Counter.builder("uber.audit-compliance-service.persisted")
                .description("Number of audit events persisted upstream")
                .register(registry);
        this.auditDeferredCounter = Counter.builder("uber.audit-compliance-service.deferred")
                .description("Number of audit events buffered locally for replay")
                .register(registry);
        this.auditDroppedCounter = Counter.builder("uber.audit-compliance-service.dropped")
                .description("Number of audit events dropped due to buffer overflow")
                .register(registry);

        // H3 cluster size distribution — used by capacity-planning dashboards
        // to understand typical cluster fan-out.
        this.h3ClusterSizeSummary = DistributionSummary
                .builder("uber.audit-compliance-service.h3.cluster.size")
                .description("Number of audit events per H3 cell after clustering")
                .publishPercentiles(0.50, 0.95, 0.99)
                .baseUnit("events")
                .register(registry);
    }

    /**
     * Records a single audit-write execution. The supplied {@link Runnable}
     * is timed and wrapped in the audit-duration timer; any uncaught
     * exception still propagates to the caller so the metric is recorded
     * around the boundary, not inside the caller's logic.
     */
    public void recordAuditDuration(final Runnable operation) {
        auditDurationTimer.record(operation);
    }

    /**
     * Records a single H3 clustering execution. Returns the elapsed nanoseconds
     * via the supplied {@link Runnable}.
     */
    public void recordH3Clustering(final Runnable operation) {
        h3ClusteringTimer.record(operation);
    }

    public void incrementAuditPersisted() {
        auditPersistedCounter.increment();
    }

    public void incrementAuditDeferred() {
        auditDeferredCounter.increment();
    }

    public void incrementAuditDropped() {
        auditDroppedCounter.increment();
    }

    public void recordH3ClusterSize(final int size) {
        h3ClusterSizeSummary.record(size);
    }

    public Timer getAuditDurationTimer() {
        return auditDurationTimer;
    }

    public Timer getH3ClusteringTimer() {
        return h3ClusteringTimer;
    }

    public Counter getAuditPersistedCounter() {
        return auditPersistedCounter;
    }

    public Counter getAuditDeferredCounter() {
        return auditDeferredCounter;
    }

    public Counter getAuditDroppedCounter() {
        return auditDroppedCounter;
    }

    public DistributionSummary getH3ClusterSizeSummary() {
        return h3ClusterSizeSummary;
    }
}
