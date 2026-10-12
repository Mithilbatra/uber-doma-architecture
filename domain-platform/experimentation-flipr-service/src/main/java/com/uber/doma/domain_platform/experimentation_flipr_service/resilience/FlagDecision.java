package com.uber.doma.domain_platform.experimentation_flipr_service.resilience;

import java.util.Objects;

/** A feature-flag decision together with the source used to serve it. */
public record FlagDecision(String variant, Source source) {

    public FlagDecision {
        if (variant == null || variant.isBlank()) {
            throw new IllegalArgumentException("variant must not be blank");
        }
        Objects.requireNonNull(source, "source must not be null");
    }

    public boolean degraded() {
        return source != Source.UPSTREAM;
    }

    public enum Source {
        UPSTREAM,
        LAST_KNOWN,
        DEFAULT
    }
}
