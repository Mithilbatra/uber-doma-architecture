package com.uber.doma.domain_platform.audit_compliance_service.query;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Objects;
/** Fans out audit queries and gathers their independently protected results. */
public class ReactiveAuditQueryAggregator {
    private final ReactiveAuditStorageGrpcClient auditClient;
    private final ReactiveRegulatoryReportGrpcClient reportClient;
    private final Duration timeout;
    public ReactiveAuditQueryAggregator(ReactiveAuditStorageGrpcClient auditClient,
            ReactiveRegulatoryReportGrpcClient reportClient, Duration timeout) {
        this.auditClient = Objects.requireNonNull(auditClient);
        this.reportClient = Objects.requireNonNull(reportClient);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }
    public Mono<AuditQueryResult> aggregate(AuditQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Mono<AuditStorageView> audit = auditClient.query(query).timeout(timeout)
                .onErrorReturn(AuditStorageView.fallback(query.subjectId()));
        Mono<RegulatoryReportView> report = reportClient.query(query).timeout(timeout)
                .onErrorReturn(RegulatoryReportView.fallback(query.subjectId()));
        return Mono.zip(audit, report).map(tuple -> new AuditQueryResult(tuple.getT1(), tuple.getT2()));
    }
}
