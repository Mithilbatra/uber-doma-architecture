package com.uber.doma.domain_platform.experimentation_flipr_service.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps feature evaluation off the booking critical path when Flipr is slow
 * or unavailable. A successful response becomes the last-known value; a
 * failed response uses that value or the flag's safe default.
 */
public final class ResilientFlagEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ResilientFlagEvaluator.class);

    private final FlagUpstream upstream;
    private final Map<String, String> defaults;
    private final Map<CacheKey, String> lastKnown = new ConcurrentHashMap<>();

    public ResilientFlagEvaluator(final FlagUpstream upstream, final Map<String, String> defaults) {
        this.upstream = Objects.requireNonNull(upstream, "upstream must not be null");
        this.defaults = Map.copyOf(Objects.requireNonNull(defaults, "defaults must not be null"));
    }

    public FlagDecision evaluate(final String flagKey, final String subjectId) {
        requireText(flagKey, "flagKey");
        requireText(subjectId, "subjectId");
        final CacheKey key = new CacheKey(flagKey, subjectId);

        try {
            final String variant = upstream.evaluate(flagKey, subjectId);
            requireText(variant, "upstream variant");
            lastKnown.put(key, variant);
            return new FlagDecision(variant, FlagDecision.Source.UPSTREAM);
        } catch (final RuntimeException failure) {
            final String cached = lastKnown.get(key);
            if (cached != null) {
                log.warn("Flipr unavailable for flag {} and subject {}; using last-known value: {}",
                        flagKey, subjectId, failure.toString());
                return new FlagDecision(cached, FlagDecision.Source.LAST_KNOWN);
            }

            final String safeDefault = defaults.get(flagKey);
            if (safeDefault == null || safeDefault.isBlank()) {
                throw new IllegalStateException("No safe default configured for flag " + flagKey, failure);
            }
            log.warn("Flipr unavailable for flag {} and subject {}; using safe default: {}",
                    flagKey, subjectId, failure.toString());
            return new FlagDecision(safeDefault, FlagDecision.Source.DEFAULT);
        }
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record CacheKey(String flagKey, String subjectId) {
    }
}
