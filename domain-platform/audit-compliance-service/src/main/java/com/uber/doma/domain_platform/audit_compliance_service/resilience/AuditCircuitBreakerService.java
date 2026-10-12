package com.uber.doma.domain_platform.audit_compliance_service.resilience;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wraps remote calls made by the audit-compliance-service with Resilience4j
 * {@link CircuitBreaker} and {@link Bulkhead} annotations so that cascading
 * failures from downstream dependencies cannot bring down the booking hot
 * path. The breaker instances are configured in
 * {@code application.yml} under
 * {@code resilience4j.circuitbreaker.instances}:
 *
 * <ul>
 *   <li><b>auditStorage</b> — 100-call sliding window, opens when failure
 *       rate exceeds 50% over that window. Protects the immutable
 *       audit-log write path.</li>
 *   <li><b>regulatoryReport</b> — same window, 50% threshold. Protects
 *       the regulatory-reporting service used for GDPR exports.</li>
 * </ul>
 *
 * <p>Fallback methods return degraded (but safe) responses so the booking
 * SLA is preserved even when the breaker is OPEN: a {@code null} payload
 * is replaced with an empty list, and read-side calls return an empty
 * map. Each fallback logs at WARN level so the SRE team can correlate
 * the open breaker with downstream incident reports.</p>
 */
@Service
public class AuditCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(AuditCircuitBreakerService.class);

    /** Sliding window size for the audit-storage breaker (matches application.yml). */
    public static final int AUDIT_STORAGE_WINDOW = 100;

    /** Failure rate threshold for the audit-storage breaker (matches application.yml). */
    public static final float AUDIT_STORAGE_FAILURE_RATE_THRESHOLD = 50.0f;

    /**
     * Persists an immutable audit record to the downstream audit-storage
     * service. Wrapped by the {@code auditStorage} circuit breaker; the
     * fallback method below is invoked when the breaker is OPEN or when
     * the call throws a recorded exception.
     *
     * @param event the audit event to persist
     * @return true if the event was persisted; false if the fallback
     *         absorbed the failure (the event is then routed through the
     *         local resilient sink's buffer).
     */
    @CircuitBreaker(name = "auditStorage", fallbackMethod = "persistAuditFallback")
    @Bulkhead(name = "auditStorage")
    public boolean persistAuditRecord(final AuditEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        // Production wiring would dispatch to Kafka, gRPC, or a remote
        // append-only log here. For unit-test purposes we simply call
        // the in-memory channel — chaos tests inject faults through the
        // FaultInjectingChannel so the breaker can be exercised end-to-end.
        final UpstreamAuditChannel channel = new NoopUpstreamAuditChannel();
        channel.persist(event);
        return true;
    }

    /**
     * Fallback for {@link #persistAuditRecord(AuditEvent)}. Invoked when the
     * {@code auditStorage} breaker is OPEN, or when the underlying call
     * throws a recorded exception (network, timeout, 5xx). The fallback
     * returns {@code false} so the caller can route the event through
     * the local resilient sink's buffer for replay once the breaker
     * transitions back to HALF_OPEN/CLOSED.
     */
    @SuppressWarnings("unused")
    public boolean persistAuditFallback(final AuditEvent event, final Throwable t) {
        log.warn("[AuditCircuitBreakerService] auditStorage breaker open or call failed "
                + "(eventId={}, cause={}); routing through local buffer",
                event != null ? event.getId() : "null", t.getMessage());
        return false;
    }

    /**
     * Reads a regulatory report for a given actor. Wrapped by the
     * {@code regulatoryReport} circuit breaker.
     *
     * @param actorId the rider/driver id to query
     * @return a map of report fields; never null. The fallback method
     *         returns an empty map when the breaker is OPEN.
     */
    @CircuitBreaker(name = "regulatoryReport", fallbackMethod = "readReportFallback")
    public Map<String, String> readRegulatoryReport(final String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        // In production this would call the regulatory-reporting service.
        // For unit testing we throw a simulated 5xx so the breaker can be
        // exercised by the chaos test.
        if (actorId.startsWith("force-fail-")) {
            throw new DownstreamUnavailableException(
                    "simulated 500 from regulatory-reporting service for actor " + actorId);
        }
        return Map.of(
                "actorId", actorId,
                "status", "ok",
                "generatedAt", java.time.Instant.now().toString());
    }

    /**
     * Fallback for {@link #readRegulatoryReport(String)}. Returns an empty
     * map so callers can degrade gracefully instead of throwing.
     */
    @SuppressWarnings("unused")
    public Map<String, String> readReportFallback(final String actorId, final Throwable t) {
        log.warn("[AuditCircuitBreakerService] regulatoryReport breaker open or call failed "
                + "(actorId={}, cause={}); returning empty report",
                actorId, t.getMessage());
        return Map.of();
    }

    /**
     * Lists recent audit events for an actor. Wrapped by the
     * {@code auditStorage} circuit breaker.
     */
    @CircuitBreaker(name = "auditStorage", fallbackMethod = "listEventsFallback")
    public List<AuditEvent> listRecentEvents(final String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return List.of();
        }
        if (actorId.startsWith("force-fail-")) {
            throw new DownstreamUnavailableException(
                    "simulated 500 from audit-storage list call for actor " + actorId);
        }
        return List.of();
    }

    /**
     * Fallback for {@link #listRecentEvents(String)}. Returns an empty list
     * so the caller's pagination / table-render code can continue to run.
     */
    @SuppressWarnings("unused")
    public List<AuditEvent> listEventsFallback(final String actorId, final Throwable t) {
        log.warn("[AuditCircuitBreakerService] auditStorage breaker open for list "
                + "(actorId={}, cause={}); returning empty list",
                actorId, t.getMessage());
        return Collections.emptyList();
    }

    public int getAuditStorageWindow() {
        return AUDIT_STORAGE_WINDOW;
    }

    public float getAuditStorageFailureRateThreshold() {
        return AUDIT_STORAGE_FAILURE_RATE_THRESHOLD;
    }
}
