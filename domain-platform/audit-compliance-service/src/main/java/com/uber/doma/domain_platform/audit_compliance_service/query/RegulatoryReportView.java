package com.uber.doma.domain_platform.audit_compliance_service.query;
import java.util.List;
public record RegulatoryReportView(String subjectId, List<String> findingIds, boolean fallback) {
    public RegulatoryReportView { findingIds = List.copyOf(findingIds); }
    public static RegulatoryReportView fallback(String id) { return new RegulatoryReportView(id, List.of(), true); }
}
