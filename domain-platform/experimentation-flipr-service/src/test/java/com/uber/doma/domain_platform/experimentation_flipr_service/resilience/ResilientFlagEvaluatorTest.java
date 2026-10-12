package com.uber.doma.domain_platform.experimentation_flipr_service.resilience;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientFlagEvaluatorTest {

    @Test
    void successfulEvaluationIsCachedAndUsedDuringPartition() {
        final AtomicBoolean partitioned = new AtomicBoolean();
        final FlagUpstream upstream = (flag, subject) -> {
            if (partitioned.get()) {
                throw new IllegalStateException("network partition");
            }
            return "treatment";
        };
        final ResilientFlagEvaluator evaluator =
                new ResilientFlagEvaluator(upstream, Map.of("booking-flow", "control"));

        final FlagDecision live = evaluator.evaluate("booking-flow", "rider-7");
        partitioned.set(true);
        final FlagDecision fallback = assertDoesNotThrow(
                () -> evaluator.evaluate("booking-flow", "rider-7"));

        assertEquals(new FlagDecision("treatment", FlagDecision.Source.UPSTREAM), live);
        assertFalse(live.degraded());
        assertEquals(new FlagDecision("treatment", FlagDecision.Source.LAST_KNOWN), fallback);
        assertTrue(fallback.degraded());
    }

    @Test
    void coldCacheUsesSafeDefaultWithoutLeakingUpstreamFailure() {
        final ResilientFlagEvaluator evaluator = new ResilientFlagEvaluator(
                (flag, subject) -> { throw new IllegalStateException("connection reset"); },
                Map.of("booking-flow", "control"));

        final FlagDecision result = assertDoesNotThrow(
                () -> evaluator.evaluate("booking-flow", "rider-8"));

        assertEquals("control", result.variant());
        assertEquals(FlagDecision.Source.DEFAULT, result.source());
        assertTrue(result.degraded());
    }

    @Test
    void recoveryRefreshesLastKnownValue() {
        final AtomicBoolean recovered = new AtomicBoolean();
        final ResilientFlagEvaluator evaluator = new ResilientFlagEvaluator(
                (flag, subject) -> {
                    if (!recovered.get()) {
                        throw new IllegalStateException("packet dropped");
                    }
                    return "recovered-treatment";
                }, Map.of("booking-flow", "control"));

        assertEquals(FlagDecision.Source.DEFAULT,
                evaluator.evaluate("booking-flow", "rider-9").source());
        recovered.set(true);
        final FlagDecision recoveredDecision = evaluator.evaluate("booking-flow", "rider-9");

        assertEquals("recovered-treatment", recoveredDecision.variant());
        assertEquals(FlagDecision.Source.UPSTREAM, recoveredDecision.source());
    }

    @Test
    void thirtyPercentPacketLossPreservesBookingCriticalFlagSla() {
        final AtomicInteger calls = new AtomicInteger();
        final ResilientFlagEvaluator evaluator = new ResilientFlagEvaluator(
                (flag, subject) -> {
                    if (calls.getAndIncrement() % 10 < 3) {
                        throw new IllegalStateException("synthetic packet loss");
                    }
                    return "treatment";
                }, Map.of("booking-flow", "control"));

        final long started = System.nanoTime();
        int degraded = 0;
        for (int i = 0; i < 100; i++) {
            final int rider = i;
            final FlagDecision decision = assertDoesNotThrow(
                    () -> evaluator.evaluate("booking-flow", "rider-" + rider));
            assertTrue(decision.variant().equals("control")
                    || decision.variant().equals("treatment"));
            if (decision.degraded()) {
                degraded++;
            }
        }
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(30, degraded, "all dropped requests must use the safe fallback");
        assertTrue(elapsedMillis < 1_000,
                "fallback evaluation must remain off the one-second booking SLA path");
    }

    @Test
    void rejectsInvalidConfigurationAndRequests() {
        assertThrows(NullPointerException.class,
                () -> new ResilientFlagEvaluator(null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> new ResilientFlagEvaluator((flag, subject) -> "on", null));
        final ResilientFlagEvaluator evaluator =
                new ResilientFlagEvaluator((flag, subject) -> "on", Map.of());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(" ", "rider"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("flag", null));
        assertThrows(IllegalStateException.class,
                () -> new ResilientFlagEvaluator((flag, subject) -> " ", Map.of())
                        .evaluate("unknown", "rider"));
    }
}
