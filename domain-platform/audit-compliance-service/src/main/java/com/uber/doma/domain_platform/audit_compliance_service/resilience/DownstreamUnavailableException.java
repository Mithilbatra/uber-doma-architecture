package com.uber.doma.domain_platform.audit_compliance_service.resilience;

/**
 * Exception thrown by the audit-compliance-service when a downstream
 * dependency (regulatory-reporting service, audit storage) returns a
 * 5xx / network error. This exception type is whitelisted in
 * {@code application.yml} via the {@code recordExceptions} block of
 * the Resilience4j circuit breaker configuration, so it contributes
 * to the failure-rate calculation that drives the state machine
 * (CLOSED → OPEN → HALF_OPEN).
 */
public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(final String message) {
        super(message);
    }

    public DownstreamUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
