package com.uber.doma.domain_platform.audit_compliance_service.query;
import reactor.core.publisher.Mono;
/** Non-blocking port implemented by the audit-storage gRPC stub adapter. */
@FunctionalInterface public interface ReactiveAuditStorageGrpcClient { Mono<AuditStorageView> query(AuditQuery query); }
