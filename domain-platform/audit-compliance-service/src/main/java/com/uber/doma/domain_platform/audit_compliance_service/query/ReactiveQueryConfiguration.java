package com.uber.doma.domain_platform.audit_compliance_service.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the aggregator when both generated reactive gRPC client adapters are available. */
@Configuration
public class ReactiveQueryConfiguration {

    @Bean
    @ConditionalOnBean({ReactiveAuditStorageGrpcClient.class, ReactiveRegulatoryReportGrpcClient.class})
    ReactiveAuditQueryAggregator reactiveAuditQueryAggregator(
            ReactiveAuditStorageGrpcClient auditClient,
            ReactiveRegulatoryReportGrpcClient reportClient,
            @Value("${audit.scatter-gather.downstream-timeout:2s}") Duration timeout) {
        return new ReactiveAuditQueryAggregator(auditClient, reportClient, timeout);
    }
}
