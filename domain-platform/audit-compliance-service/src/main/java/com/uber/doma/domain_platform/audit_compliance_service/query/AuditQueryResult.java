package com.uber.doma.domain_platform.audit_compliance_service.query;
import java.util.Objects;
public record AuditQueryResult(AuditStorageView auditTrail, RegulatoryReportView regulatoryReport) {
    public AuditQueryResult {
        Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        Objects.requireNonNull(regulatoryReport, "regulatoryReport must not be null");
    }
    public boolean degraded() { return auditTrail.fallback() || regulatoryReport.fallback(); }
}
