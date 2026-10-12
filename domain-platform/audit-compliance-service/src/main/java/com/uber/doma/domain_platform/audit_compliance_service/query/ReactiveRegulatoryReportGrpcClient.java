package com.uber.doma.domain_platform.audit_compliance_service.query;
import reactor.core.publisher.Mono;
/** Non-blocking port implemented by the regulatory-reporting gRPC stub adapter. */
@FunctionalInterface public interface ReactiveRegulatoryReportGrpcClient { Mono<RegulatoryReportView> query(AuditQuery query); }
