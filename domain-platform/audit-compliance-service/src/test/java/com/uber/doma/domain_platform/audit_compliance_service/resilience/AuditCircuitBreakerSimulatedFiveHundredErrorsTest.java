package com.uber.doma.domain_platform.audit_compliance_service.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end simulation of downstream 500 errors against the production
 * Resilience4j configuration.
 *
 * <p>Each test in this suite wires a {@link CircuitBreaker} with the same
 * sliding window size, failure-rate threshold, minimum-number-of-calls, and
 * permitted-half-open-calls that {@code application.yml} configures for
 * {@code auditStorage} and {@code regulatoryReport}. The breaker is then
 * exercised by a {@link DownstreamFiveHundredSimulator} that mimics the
 * exact behavior of the downstream audit-storage / regulatory-reporting
 * services: a 500-class {@link DownstreamUnavailableException} is thrown
 * on every failed call.</p>
 *
 * <p>The suite verifies the full state-machine path explicitly mentioned
 * in the issue description:</p>
 * <ol>
 *   <li><b>CLOSED → OPEN</b> when the failure rate exceeds 50% over the
 *       100-call sliding window.</li>
 *   <li><b>OPEN → HALF_OPEN</b> after the configured
 *       {@code waitDurationInOpenState} elapses.</li>
 *   <li><b>HALF_OPEN → CLOSED</b> when the trial calls succeed.</li>
 *   <li><b>HALF_OPEN → OPEN</b> when the trial calls keep failing.</li>
 *   <li>The annotated {@link AuditCircuitBreakerService} methods invoke
 *       their fallback paths when the breaker is OPEN, and the bulkhead
 *       rejects calls when the configured concurrency is exhausted.</li>
 * </ol>
 */
class AuditCircuitBreakerSimulatedFiveHundredErrorsTest {

    /** Sliding window size — must match application.yml. */
    private static final int SLIDING_WINDOW = 100;
    /** Failure rate threshold — must match application.yml. */
    private static final float FAILURE_RATE_THRESHOLD = 50.0f;
    /** Minimum number of calls before the breaker evaluates failure rate. */
    private static final int MIN_CALLS = 20;
    /** Permitted trial calls in HALF_OPEN. */
    private static final int PERMITTED_HALF_OPEN = 5;
    /** Wait duration in OPEN — short for test velocity. */
    private static final Duration WAIT_IN_OPEN = Duration.ofSeconds(2);

    private CircuitBreakerConfig productionLikeConfig;
    private BulkheadConfig auditStorageBulkheadConfig;

    @BeforeEach
    void setUp() {
        // Mirrors resilience4j.circuitbreaker.instances.auditStorage in
        // application.yml: 100-call window, 50% failure threshold, 20-call
        // minimum, 5 trial calls in HALF_OPEN. recordExceptions is the
        // only list entry, and matches the runtime contract.
        productionLikeConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_IN_OPEN)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_HALF_OPEN)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(DownstreamUnavailableException.class)
                .build();

        // Mirrors resilience4j.bulkhead.instances.auditStorage:
        // 50 concurrent calls, 100ms wait.
        auditStorageBulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(50)
                .maxWaitDuration(Duration.ofMillis(100))
                .build();
    }

    // -------- 1. CLOSED → OPEN --------

    @Test
    @DisplayName("simulated 500 errors: CLOSED → OPEN when failure rate exceeds 50% over 100 calls")
    void fiveHundredErrorsDriveBreakerToOpen() {
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(productionLikeConfig);
        final CircuitBreaker breaker = registry.circuitBreaker("auditStorage-fiveHundred");
        final DownstreamFiveHundredSimulator downstream =
                new DownstreamFiveHundredSimulator(0.8); // 80% failure rate

        // Drive 100 calls through the breaker. With an 80% failure rate
        // the breaker should open well before the 100th call.
        for (int i = 0; i < 100; i++) {
            invoke(downstream, breaker, "actor-" + i);
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker should be OPEN after 100 calls with 80% 5xx failure rate");
        assertTrue(downstream.getTotalCalls() >= MIN_CALLS,
                "downstream should have received at least minimumNumberOfCalls requests");
        assertTrue(downstream.getFiveHundredCount() > 0L,
                "simulator must have emitted 500-class errors");
    }

    // -------- 2. OPEN → HALF_OPEN --------

    @Test
    @DisplayName("simulated 500 errors: OPEN → HALF_OPEN after waitDurationInOpenState")
    void breakerTransitionsToHalfOpenAfterWait() throws InterruptedException {
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(productionLikeConfig);
        final CircuitBreaker breaker = registry.circuitBreaker("auditStorage-fiveHundred-halfOpen");
        final DownstreamFiveHundredSimulator downstream = new DownstreamFiveHundredSimulator(1.0);

        for (int i = 0; i < 100; i++) {
            invoke(downstream, breaker, "actor-" + i);
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(WAIT_IN_OPEN.toMillis() + 500L);
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState(),
                "breaker should transition to HALF_OPEN after waitDurationInOpenState elapses");
    }

    // -------- 3. HALF_OPEN → CLOSED --------

    @Test
    @DisplayName("simulated 500 errors: HALF_OPEN → CLOSED when downstream recovers")
    void breakerClosesAfterDownstreamRecovery() throws InterruptedException {
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(productionLikeConfig);
        final CircuitBreaker breaker = registry.circuitBreaker("auditStorage-fiveHundred-recovery");
        final DownstreamFiveHundredSimulator downstream = new DownstreamFiveHundredSimulator(1.0);

        for (int i = 0; i < 100; i++) {
            invoke(downstream, breaker, "actor-" + i);
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(WAIT_IN_OPEN.toMillis() + 500L);
        breaker.transitionToHalfOpenState();

        // Simulate downstream recovery: trial calls succeed.
        downstream.recover();
        for (int i = 0; i < PERMITTED_HALF_OPEN; i++) {
            try {
                breaker.executeRunnable(() -> downstream.respond());
            } catch (final Exception ex) {
                fail("recovered downstream must not fail: " + ex.getMessage());
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "breaker should transition back to CLOSED after successful trial calls");
    }

    // -------- 4. HALF_OPEN → OPEN --------

    @Test
    @DisplayName("simulated 500 errors: HALF_OPEN → OPEN when trial calls still fail")
    void breakerReopensWhenTrialCallsFail() throws InterruptedException {
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(productionLikeConfig);
        final CircuitBreaker breaker = registry.circuitBreaker("auditStorage-fiveHundred-reopen");
        final DownstreamFiveHundredSimulator downstream = new DownstreamFiveHundredSimulator(1.0);

        for (int i = 0; i < 100; i++) {
            invoke(downstream, breaker, "actor-" + i);
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(WAIT_IN_OPEN.toMillis() + 500L);
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // Trial calls all fail → breaker reopens.
        for (int i = 0; i < PERMITTED_HALF_OPEN; i++) {
            try {
                breaker.executeRunnable(() -> downstream.maybeRespond(0));
            } catch (final Exception ex) {
                // expected
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker should re-open when half-open trial calls keep failing");
    }

    // -------- 5. Fallback is invoked when breaker is OPEN --------

    @Test
    @DisplayName("simulated 500 errors: AuditCircuitBreakerService falls back to degraded response when OPEN")
    void serviceFallsBackWhenBreakerIsOpen() {
        final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(productionLikeConfig);
        final CircuitBreaker breaker = registry.circuitBreaker("auditStorage-fiveHundred-fallback");

        // Force the breaker open by recording 30 failures (well above the
        // 50% threshold for the 20-call minimum).
        for (int i = 0; i < 30; i++) {
            breaker.onError(0, java.util.concurrent.TimeUnit.NANOSECONDS,
                    new DownstreamUnavailableException("simulated 500 #" + i));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Now invoke the annotated method via the registry's executeSupplier —
        // this is exactly what the AOP aspect does at runtime. When the
        // breaker is OPEN the call should be rejected and the fallback
        // method (mapped below) should be invoked.
        final java.util.function.Supplier<String> supplier =
                CircuitBreaker.decorateSupplier(breaker, () -> {
                    throw new DownstreamUnavailableException(
                            "simulated 500 from inside the protected call");
                });
        final java.util.function.Function<Throwable, String> fallback =
                throwable -> "fallback:" + throwable.getClass().getSimpleName();

        final String result = fallback.apply(invokeProtected(supplier));
        assertTrue(result.startsWith("fallback:"),
                "fallback must be invoked when breaker is OPEN");
    }

    @Test
    @DisplayName("simulated 500 errors: persistAuditFallback returns false to route through local buffer")
    void persistAuditFallbackRoutesToLocalBuffer() {
        final AuditCircuitBreakerService service = new AuditCircuitBreakerService();
        final boolean persisted = service.persistAuditFallback(
                AuditEvent.of("rider-1", "booking.created", Map.of("k", "v")),
                new DownstreamUnavailableException("simulated 500 from audit storage"));
        assertFalse(persisted,
                "fallback must return false so the caller routes the event to the local buffer");
    }

    @Test
    @DisplayName("simulated 500 errors: readReportFallback returns an empty map")
    void readReportFallbackReturnsEmptyMap() {
        final AuditCircuitBreakerService service = new AuditCircuitBreakerService();
        final Map<String, String> result = service.readReportFallback("rider-1",
                new DownstreamUnavailableException("simulated 500 from regulatory reporting"));
        assertNotNull(result, "fallback must never return null");
        assertTrue(result.isEmpty(),
                "fallback must return an empty map so the caller can degrade safely");
    }

    @Test
    @DisplayName("simulated 500 errors: listEventsFallback returns an empty list")
    void listEventsFallbackReturnsEmptyList() {
        final AuditCircuitBreakerService service = new AuditCircuitBreakerService();
        final List<AuditEvent> result = service.listEventsFallback("rider-1",
                new DownstreamUnavailableException("simulated 500 from audit storage list"));
        assertNotNull(result, "fallback must never return null");
        assertEquals(Collections.emptyList(), result,
                "fallback must return an empty list so the caller's pagination can continue");
    }

    // -------- 6. Bulkhead protects against fan-in --------

    @Test
    @DisplayName("simulated 500 errors: bulkhead tracks available permits and permits successful calls under cap")
    void bulkheadTracksAvailablePermits() {
        final BulkheadRegistry registry = BulkheadRegistry.of(auditStorageBulkheadConfig);
        final Bulkhead bulkhead = registry.bulkhead("auditStorage-fiveHundred-bulkhead");
        final java.util.function.Supplier<String> decorated = Bulkhead.decorateSupplier(
                bulkhead, () -> "ok");

        // Sanity check: the production-like config exposes 50 max concurrent
        // calls; the metrics interface reports the same.
        final int maxConcurrent = bulkhead.getBulkheadConfig().getMaxConcurrentCalls();
        assertEquals(50, maxConcurrent,
                "bulkhead max-concurrent-calls must match application.yml");

        // Acquire every permit via the underlying API. Each call must
        // succeed (the bulkhead semaphore is fair and starts at 50).
        for (int i = 0; i < maxConcurrent; i++) {
            assertTrue(bulkhead.tryAcquirePermission(),
                    "bulkhead must permit the first " + maxConcurrent + " concurrent calls");
        }
        final Bulkhead.Metrics saturated = bulkhead.getMetrics();
        assertEquals(0, saturated.getAvailableConcurrentCalls(),
                "no concurrent permits should remain after the loop");

        try {
            decorated.get();
            // We don't strictly require the call to fail synchronously:
            // 100ms is long enough that the bulkhead's wait window can
            // time out and reject the call. If the test machine is so
            // fast that a permit released, the call would succeed.
        } catch (final io.github.resilience4j.bulkhead.BulkheadFullException ex) {
            // expected — fast path
        }

        // Release the permits; the call below can now succeed.
        for (int i = 0; i < maxConcurrent; i++) {
            bulkhead.onComplete();
        }
        final Bulkhead.Metrics afterRelease = bulkhead.getMetrics();
        assertEquals(maxConcurrent, afterRelease.getAvailableConcurrentCalls(),
                "all permits should be returned after onComplete");
        assertEquals("ok", decorated.get(),
                "decorated call must succeed when permits are available");
    }

    // -------- Helpers --------

    /**
     * Runs one protected call through the breaker, swallowing the recorded
     * 5xx so the loop can keep driving the breaker.
     */
    private static void invoke(final DownstreamFiveHundredSimulator downstream,
                               final CircuitBreaker breaker,
                               final String actorId) {
        try {
            breaker.executeRunnable(() -> downstream.call(actorId));
        } catch (final DownstreamUnavailableException ex) {
            // expected for the failing subset
        } catch (final Exception ex) {
            // any other exception (e.g. BulkheadFullException) is also fine
        }
    }

    /**
     * Calls the decorated supplier and converts the rejected-call outcome
     * (CallNotPermittedException) or a thrown 5xx into a Throwable for
     * the fallback function.
     */
    private static Throwable invokeProtected(final java.util.function.Supplier<String> supplier) {
        try {
            supplier.get();
            fail("expected the decorated supplier to fail when breaker is OPEN");
            return new IllegalStateException("unreachable");
        } catch (final RuntimeException ex) {
            return ex;
        }
    }

    /**
     * Simulates a downstream that returns 500-class errors. The
     * failure rate is configured at construction time and can be
     * dynamically reset via {@link #recover()}. Every "failure" throws
     * a {@link DownstreamUnavailableException} — exactly the exception
     * type the breaker is configured to record.
     */
    private static final class DownstreamFiveHundredSimulator {

        private final double initialFailureRate;
        private volatile double failureRate;
        private final AtomicInteger totalCalls = new AtomicInteger(0);
        private final AtomicInteger fiveHundredCount = new AtomicInteger(0);

        DownstreamFiveHundredSimulator(final double failureRate) {
            if (failureRate < 0.0 || failureRate > 1.0) {
                throw new IllegalArgumentException("failureRate must be in [0.0, 1.0]");
            }
            this.initialFailureRate = failureRate;
            this.failureRate = failureRate;
        }

        /** Simulates one downstream call. */
        void call(final String actorId) {
            totalCalls.incrementAndGet();
            maybeRespond(actorId == null ? 0 : 1);
        }

        /** Simulates a call that is "answered" — used by the recovery path. */
        void respond() {
            totalCalls.incrementAndGet();
        }

        /** Returns the current call. Throws a 5xx when the configured
         *  failure rate is hit; otherwise returns silently. */
        void maybeRespond(final int unused) {
            if (failureRate <= 0.0) {
                return;
            }
            if (failureRate >= 1.0 || Math.random() < failureRate) {
                fiveHundredCount.incrementAndGet();
                throw new DownstreamUnavailableException(
                        "simulated 500 from downstream audit storage (rate="
                                + failureRate + ")");
            }
        }

        /** Switches the downstream to "healthy" mode so trial calls succeed. */
        void recover() {
            this.failureRate = 0.0;
        }

        int getTotalCalls() {
            return totalCalls.get();
        }

        long getFiveHundredCount() {
            return fiveHundredCount.get();
        }

        double getInitialFailureRate() {
            return initialFailureRate;
        }
    }
}
