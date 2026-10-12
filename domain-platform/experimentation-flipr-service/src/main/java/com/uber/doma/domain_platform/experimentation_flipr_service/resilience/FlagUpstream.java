package com.uber.doma.domain_platform.experimentation_flipr_service.resilience;

/** Boundary to the remote feature-flag store. */
@FunctionalInterface
public interface FlagUpstream {

    String evaluate(String flagKey, String subjectId);
}
