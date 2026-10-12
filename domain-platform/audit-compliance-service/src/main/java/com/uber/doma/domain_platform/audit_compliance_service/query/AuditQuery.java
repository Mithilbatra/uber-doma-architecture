package com.uber.doma.domain_platform.audit_compliance_service.query;
import java.time.Instant;
import java.util.Objects;
public record AuditQuery(String subjectId, Instant from, Instant to) {
    public AuditQuery {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
    }
}
