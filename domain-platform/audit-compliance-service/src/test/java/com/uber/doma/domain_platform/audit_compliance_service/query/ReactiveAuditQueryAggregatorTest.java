package com.uber.doma.domain_platform.audit_compliance_service.query;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ReactiveAuditQueryAggregatorTest {
    private static final AuditQuery QUERY = new AuditQuery("subject-7", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
    @Test void completesAfterMaximumRatherThanSummedLatency() {
        var audit = new AuditStorageView("subject-7", List.of("event-1"), false);
        var report = new RegulatoryReportView("subject-7", List.of("finding-1"), false);
        var target = new ReactiveAuditQueryAggregator(q -> Mono.just(audit).delayElement(Duration.ofMillis(100)),
                q -> Mono.just(report).delayElement(Duration.ofMillis(60)), Duration.ofSeconds(1));
        StepVerifier.withVirtualTime(() -> target.aggregate(QUERY)).expectSubscription()
                .expectNoEvent(Duration.ofMillis(99)).thenAwait(Duration.ofMillis(1))
                .assertNext(result -> { assertEquals(audit, result.auditTrail()); assertEquals(report, result.regulatoryReport()); assertFalse(result.degraded()); })
                .verifyComplete();
    }
    @Test void timesOutAndFallsBackEachCallIndependently() {
        var report = new RegulatoryReportView("subject-7", List.of("finding-1"), false);
        var target = new ReactiveAuditQueryAggregator(q -> Mono.never(),
                q -> Mono.just(report).delayElement(Duration.ofMillis(20)), Duration.ofMillis(50));
        StepVerifier.withVirtualTime(() -> target.aggregate(QUERY)).expectSubscription()
                .expectNoEvent(Duration.ofMillis(49)).thenAwait(Duration.ofMillis(1))
                .assertNext(result -> { assertEquals(AuditStorageView.fallback("subject-7"), result.auditTrail()); assertEquals(report, result.regulatoryReport()); assertTrue(result.degraded()); })
                .verifyComplete();
    }
    @Test void degradesOnlyFailedCall() {
        var audit = new AuditStorageView("subject-7", List.of("event-1"), false);
        var target = new ReactiveAuditQueryAggregator(q -> Mono.just(audit),
                q -> Mono.error(new IllegalStateException("offline")), Duration.ofSeconds(1));
        StepVerifier.create(target.aggregate(QUERY)).assertNext(result -> {
            assertEquals(audit, result.auditTrail());
            assertEquals(RegulatoryReportView.fallback("subject-7"), result.regulatoryReport());
            assertTrue(result.degraded());
        }).verifyComplete();
    }
}
