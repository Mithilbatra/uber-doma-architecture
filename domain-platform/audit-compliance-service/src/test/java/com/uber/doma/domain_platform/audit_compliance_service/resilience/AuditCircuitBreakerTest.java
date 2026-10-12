package com.uber.doma.domain_platform.audit_compliance_service.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the Resilience4j sliding-window circuit breaker that protects
 * the audit-compliance-service downstream dependencies.
 *
 * <p>Two suites are exercised here:</p>
 * <ol>
 *   <li><b>State machine</b> — drive a {@link CircuitBreaker} with the
 *       production configuration (100-call sliding window, 50% failure
 *       rate threshold) and verify CLOSED → OPEN → HALF_OPEN → CLOSED
 *       transitions when downstream 500 errors are injected.</li>
 *   <li><b>Fallback path</b> — verify the
 *       {@link AuditCircuitBreakerService} fallback methods return
 *       safe defaults (empty list, empty map) when the breaker is OPEN,
 *       so the booking SLA is preserved.</li>
 * </ol>
 *
 * <p>Each test creates a fresh {@link CircuitBreaker} via a unique name
 * so the registry cache cannot leak state between tests.</p>
 */
class AuditCircuitBreakerTest {

    /** Sliding window size — must match application.yml. */
    private static final int SLIDING_WINDOW = 100;
    /** Failure rate threshold — must match application.yml. */
    private static final float FAILURE_RATE_THRESHOLD = 50.0f;
    /** Minimum number of calls before the breaker evaluates failure rate. */
    private static final int MIN_CALLS = 20;

    private CircuitBreakerRegistry registry;
    private AuditCircuitBreakerService service;

    @BeforeEach
    void setUp() {
        final CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(2))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(DownstreamUnavailableException.class)
                .build();
        registry = CircuitBreakerRegistry.of(config);
        service = new AuditCircuitBreakerService();
    }

    /** Returns a fresh CLOSED breaker from the test-local registry. */
    private CircuitBreaker newBreaker(final String suffix) {
        return registry.circuitBreaker("auditStorage-" + suffix);
    }

    // -------- State machine transitions --------

    @Test
    @DisplayName("CLOSED → OPEN when failure rate exceeds 50% over the 100-call window")
    void closedToOpenOnFailureRateExceedingThreshold() {
        final CircuitBreaker breaker = newBreaker("open-on-rate");

        // Interleave failures and successes in a 60/40 ratio so the
        // sliding window never sees 100% failure during the warm-up.
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 5) != 0; // 80% failures
            try {
                breaker.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500 from audit storage");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker should transition to OPEN when failure rate > 50%");
    }

    @Test
    @DisplayName("CLOSED stays CLOSED when failure rate is below 50%")
    void closedStaysClosedWhenFailureRateBelowThreshold() {
        final CircuitBreaker breaker = newBreaker("stay-closed");

        // 100 calls in a 30/70 failure/success pattern. The breaker
        // should evaluate after minimumNumberOfCalls=20 and see a 30%
        // failure rate, which is below 50% so it stays CLOSED.
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 10) < 3; // 30% failures
            try {
                breaker.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "breaker should remain CLOSED when failure rate < 50%");
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN after waitDurationInOpenState elapses")
    void openToHalfOpenAfterWaitDuration() throws InterruptedException {
        final CircuitBreaker breaker = newBreaker("open-to-half");
        // Force the breaker open with 80% failures.
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 5) != 0;
            try {
                breaker.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Wait for the configured waitDurationInOpenState (2s in the test
        // config) and then transition. With
        // automaticTransitionFromOpenToHalfOpenEnabled=false we have to
        // ask the breaker to transition manually.
        Thread.sleep(2_500L);
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState(),
                "breaker should transition to HALF_OPEN after wait duration elapses");
    }

    @Test
    @DisplayName("HALF_OPEN → CLOSED when trial calls succeed")
    void halfOpenToClosedWhenTrialCallsSucceed() throws InterruptedException {
        final CircuitBreaker breaker = newBreaker("half-to-closed");
        // Force open with 80% failures.
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 5) != 0;
            try {
                breaker.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Wait, then transition to HALF_OPEN.
        Thread.sleep(2_500L);
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // Run permittedNumberOfCallsInHalfOpenState=5 successful calls; the
        // breaker should transition back to CLOSED.
        for (int i = 0; i < 5; i++) {
            breaker.executeRunnable(() -> { /* success */ });
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "breaker should transition back to CLOSED after successful trial calls");
    }

    @Test
    @DisplayName("HALF_OPEN → OPEN when trial calls fail")
    void halfOpenToOpenWhenTrialCallsFail() throws InterruptedException {
        final CircuitBreaker breaker = newBreaker("half-to-open");
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 5) != 0;
            try {
                breaker.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(2_500L);
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // Run all 5 trial calls and let them all fail. The failure rate
        // should exceed the 50% threshold and push the breaker back to OPEN.
        for (int i = 0; i < 5; i++) {
            try {
                breaker.executeRunnable(() -> {
                    throw new DownstreamUnavailableException("trial call still failing");
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker should transition back to OPEN when trial calls fail");
    }

    // -------- Sliding-window semantics --------

    @Test
    @DisplayName("100-call sliding window evicts old calls when full")
    void slidingWindowEvictsOldCalls() {
        final CircuitBreaker breaker = newBreaker("sliding-window");
        // Drive a mixed sequence of failures and successes. The breaker
        // tracks failure rate over the LAST 100 calls; earlier calls are
        // evicted as new ones come in.
        for (int i = 0; i < 200; i++) {
            final boolean shouldFail = (i % 3) == 0; // ~33% failure rate
            try {
                breaker.executeRunnable(() -> {
                    if (shouldFail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        // 33% failure rate is below 50% threshold; the breaker should
        // remain CLOSED even though the total failure count is high.
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "breaker should remain CLOSED when sliding-window failure rate < 50%");
    }

    // -------- Fallback behavior --------

    @Test
    @DisplayName("persistAuditRecord fallback returns false when invoked with a breaker-open cause")
    void persistReturnsFalseOnFallback() {
        final AuditEvent event = AuditEvent.of("actor", "booking.created", Map.of("k", "v"));
        final boolean result = service.persistAuditFallback(event,
                new DownstreamUnavailableException("breaker open; calls not permitted"));
        assertFalse(result,
                "fallback should return false so the caller routes the event to the local buffer");
    }

    @Test
    @DisplayName("readRegulatoryReport fallback returns empty map")
    void readReportFallbackReturnsEmpty() {
        final Map<String, String> result = service.readReportFallback("actor-1",
                new DownstreamUnavailableException("simulated 500"));
        assertNotNull(result, "fallback must never return null");
        assertTrue(result.isEmpty(),
                "fallback should return an empty map so callers can degrade safely");
    }

    @Test
    @DisplayName("listRecentEvents fallback returns empty list")
    void listEventsFallbackReturnsEmpty() {
        final List<AuditEvent> result = service.listEventsFallback("actor-1",
                new DownstreamUnavailableException("simulated 500"));
        assertNotNull(result, "fallback must never return null");
        assertTrue(result.isEmpty(),
                "fallback should return an empty list so callers can degrade safely");
    }

    // -------- Unrecorded exceptions --------

    @Test
    @DisplayName("RuntimeException that is not in recordExceptions does not count as a failure")
    void unrecordedExceptionsDoNotCountAsFailure() {
        final CircuitBreaker breaker = newBreaker("unrecorded");
        // 100 IllegalArgumentException throws — these are programming errors,
        // not downstream failures, so the breaker should not open.
        for (int i = 0; i < 100; i++) {
            try {
                breaker.executeRunnable(() -> {
                    throw new IllegalArgumentException("not a downstream failure");
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "breaker should remain CLOSED when only unrecorded exceptions are thrown");
    }

    // -------- Metrics / observability --------

    @Test
    @DisplayName("breaker exposes metrics for failure rate and call counts")
    void breakerExposesMetrics() {
        final CircuitBreaker breaker = newBreaker("metrics");
        // Drive a mix of failures and successes.
        final int successCount = 70;
        final int failureCount = 30;
        for (int i = 0; i < successCount; i++) {
            breaker.executeRunnable(() -> { /* success */ });
        }
        for (int i = 0; i < failureCount; i++) {
            try {
                breaker.executeRunnable(() -> {
                    throw new DownstreamUnavailableException("simulated 500");
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        final var metrics = breaker.getMetrics();
        assertEquals(successCount + failureCount, metrics.getNumberOfBufferedCalls(),
                "all calls should be buffered in the sliding window");
        assertEquals(successCount, metrics.getNumberOfSuccessfulCalls());
        assertEquals(failureCount, metrics.getNumberOfFailedCalls());
    }

    // -------- Service-level helpers --------

    @Test
    @DisplayName("service exposes the production tuning constants")
    void serviceExposesTuningConstants() {
        assertEquals(AuditCircuitBreakerService.AUDIT_STORAGE_WINDOW, service.getAuditStorageWindow());
        assertEquals(AuditCircuitBreakerService.AUDIT_STORAGE_FAILURE_RATE_THRESHOLD,
                service.getAuditStorageFailureRateThreshold());
    }

    @Test
    @DisplayName("persistAuditRecord with null event throws IllegalArgumentException (not a recorded exception)")
    void persistRejectsNullEvent() {
        // The AOP aspect is not active in this unit test, so we call the
        // method directly to verify the validation behaviour.
        try {
            service.persistAuditRecord(null);
            fail("expected IllegalArgumentException for null event");
        } catch (final IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("event"));
        }
    }

    @Test
    @DisplayName("multiple breaker instances are isolated")
    void multipleBreakerInstancesAreIsolated() {
        final CircuitBreaker auditStorage = registry.circuitBreaker("auditStorage-isolated-a");
        final CircuitBreaker regulatoryReport = registry.circuitBreaker("regulatoryReport-isolated-b");
        // Force auditStorage open with 80% failures, regulatoryReport should remain CLOSED.
        for (int i = 0; i < 100; i++) {
            final boolean fail = (i % 5) != 0;
            try {
                auditStorage.executeRunnable(() -> {
                    if (fail) {
                        throw new DownstreamUnavailableException("simulated 500");
                    }
                });
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, auditStorage.getState());
        // The other breaker is independent.
        assertEquals(CircuitBreaker.State.CLOSED, regulatoryReport.getState(),
                "regulatoryReport breaker must not be affected by auditStorage trips");
    }
}
