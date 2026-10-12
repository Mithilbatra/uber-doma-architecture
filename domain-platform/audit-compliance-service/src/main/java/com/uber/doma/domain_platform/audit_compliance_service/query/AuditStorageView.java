package com.uber.doma.domain_platform.audit_compliance_service.query;
import java.util.List;
public record AuditStorageView(String subjectId, List<String> eventIds, boolean fallback) {
    public AuditStorageView { eventIds = List.copyOf(eventIds); }
    public static AuditStorageView fallback(String id) { return new AuditStorageView(id, List.of(), true); }
}
