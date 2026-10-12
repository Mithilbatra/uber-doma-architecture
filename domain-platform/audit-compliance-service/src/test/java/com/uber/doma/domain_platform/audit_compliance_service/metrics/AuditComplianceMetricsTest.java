package com.uber.doma.domain_platform.audit_compliance_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuditComplianceMetrics}.
 *
 * <p>Verifies that the timers, counters, and distribution summary are
 * registered with the correct names and percentile configuration, and
 * that the {@code record} / {@code increment} helpers correctly update
 * the underlying Micrometer meters so that p50 / p95 / p99 percentiles
 * can be scraped from {@code /actuator/metrics}.</p>
 */
class AuditComplianceMetricsTest {

    private MeterRegistry registry;
    private AuditComplianceMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AuditComplianceMetrics(registry);
    }

    @Test
    @DisplayName("primary audit duration timer is registered with the canonical name")
    void auditDurationTimerIsRegistered() {
        final Timer timer = registry.find("uber.audit-compliance-service.duration").timer();
        assertNotNull(timer, "audit duration timer must be registered with the canonical name");
        assertSame(metrics.getAuditDurationTimer(), timer);

        // Percentiles are configured via the builder — verify the histogram
        // exposes the snapshot fields used by Prometheus exporters.
        final var histogram = timer.takeSnapshot();
        assertNotNull(histogram, "HistogramSnapshot must be available for percentile scrape");
        assertTrue(histogram.percentileValues().length >= 3,
                "p50/p95/p99 must be enabled on the audit duration timer");
    }

    @Test
    @DisplayName("H3 clustering timer is registered with the canonical name")
    void h3ClusteringTimerIsRegistered() {
        final Timer timer = registry.find("uber.audit-compliance-service.h3.cluster.duration").timer();
        assertNotNull(timer, "H3 clustering timer must be registered");
        assertSame(metrics.getH3ClusteringTimer(), timer);
    }

    @Test
    @DisplayName("audit persisted / deferred / dropped counters are registered")
    void outcomeCountersAreRegistered() {
        final Counter persisted = registry.find("uber.audit-compliance-service.persisted").counter();
        final Counter deferred = registry.find("uber.audit-compliance-service.deferred").counter();
        final Counter dropped = registry.find("uber.audit-compliance-service.dropped").counter();
        assertNotNull(persisted);
        assertNotNull(deferred);
        assertNotNull(dropped);
    }

    @Test
    @DisplayName("H3 cluster size distribution summary is registered with percentiles")
    void h3ClusterSizeSummaryIsRegistered() {
        final DistributionSummary summary = registry
                .find("uber.audit-compliance-service.h3.cluster.size")
                .summary();
        assertNotNull(summary, "cluster size distribution summary must be registered");
        assertSame(metrics.getH3ClusterSizeSummary(), summary);
        // DistributionSummary exposes a histogram snapshot.
        assertNotNull(summary.takeSnapshot());
    }

    @Test
    @DisplayName("recordAuditDuration times the supplied runnable")
    void recordAuditDurationTimesTheOperation() {
        metrics.recordAuditDuration(() -> {
            try {
                Thread.sleep(2L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        final Timer timer = metrics.getAuditDurationTimer();
        assertEquals(1L, timer.count(), "exactly one audit write should be recorded");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0L,
                "recorded time should be > 0");
    }

    @Test
    @DisplayName("recordH3Clustering times the supplied runnable")
    void recordH3ClusteringTimesTheOperation() {
        metrics.recordH3Clustering(() -> {
            // Simulate H3 clustering work.
            long sum = 0L;
            for (int i = 0; i < 1000; i++) {
                sum += i;
            }
            // Reference `sum` to keep the JIT from eliding the loop.
            if (sum < 0) {
                throw new IllegalStateException("unreachable");
            }
        });
        final Timer timer = metrics.getH3ClusteringTimer();
        assertEquals(1L, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0L);
    }

    @Test
    @DisplayName("audit outcome counters increment correctly")
    void outcomeCountersIncrement() {
        metrics.incrementAuditPersisted();
        metrics.incrementAuditPersisted();
        metrics.incrementAuditPersisted();
        metrics.incrementAuditDeferred();
        metrics.incrementAuditDropped();

        assertEquals(3.0, metrics.getAuditPersistedCounter().count(), 0.0001);
        assertEquals(1.0, metrics.getAuditDeferredCounter().count(), 0.0001);
        assertEquals(1.0, metrics.getAuditDroppedCounter().count(), 0.0001);
    }

    @Test
    @DisplayName("H3 cluster size summary records sample values")
    void h3ClusterSizeSummaryRecordsValues() {
        metrics.recordH3ClusterSize(1);
        metrics.recordH3ClusterSize(5);
        metrics.recordH3ClusterSize(10);
        assertEquals(3L, metrics.getH3ClusterSizeSummary().count());
        assertEquals(16.0, metrics.getH3ClusterSizeSummary().totalAmount(), 0.0001);
        assertEquals(10.0, metrics.getH3ClusterSizeSummary().max(), 0.0001);
    }

    @Test
    @DisplayName("/actuator/metrics endpoint exposes the primary timer")
    void metricsEndpointExposesPrimaryTimer() {
        // The SimpleMeterRegistry is what the /actuator/metrics endpoint
        // delegates to. We assert the canonical metric name is present in
        // the registry so the actuator endpoint can find it.
        assertNotNull(registry.find("uber.audit-compliance-service.duration").timer());
        assertNotNull(registry.find("uber.audit-compliance-service.h3.cluster.duration").timer());
        assertNotNull(registry.find("uber.audit-compliance-service.persisted").counter());
        assertNotNull(registry.find("uber.audit-compliance-service.deferred").counter());
        assertNotNull(registry.find("uber.audit-compliance-service.dropped").counter());
        assertNotNull(registry.find("uber.audit-compliance-service.h3.cluster.size").summary());
    }
}
