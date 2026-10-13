package com.uber.doma.domain_platform.audit_compliance_service.health;


import com.uber.doma.domain_platform.audit_compliance_service.AuditComplianceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component("auditComplianceHealthIndicator")
public class AuditComplianceHealthIndicator implements HealthIndicator{
    private final DataSource dataSource;
    private final AuditComplianceService auditComplianceService;

    public AuditComplianceHealthIndicator(@Autowired(required = false) DataSource dataSource, AuditComplianceService auditComplianceService) {
        this.dataSource = dataSource;
        this.auditComplianceService = auditComplianceService;
    }

    @Override
    public Health health(){
        boolean databaseAvailable = checkDatabaseAvailability();
        boolean downStreamAvailable = checkDownstreamDependencies();

        if(databaseAvailable && downStreamAvailable){
            return Health.up()
                    .withDetail("service", "audit-compliance-service")
                    .withDetail("database", "UP")
                    .withDetail("downstreamDependencies", "UP")
                    .withDetail("status","READY")
                    .build();
        }
        return Health.down()
                .withDetail("service", "audit-compliance-service")
                .withDetail("database", databaseAvailable ? "UP" : "DOWN")
                .withDetail("downstreamDependencies", downStreamAvailable ? "UP" : "DOWN")
                .withDetail("status","DEGRADED")
                .build();
    }
    protected boolean checkDatabaseAvailability(){
        if(dataSource== null){
            return true;
        }
        try(Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        }catch(SQLException e){
            return false;
        }
    }
    protected boolean checkDownstreamDependencies(){
        return true;
    }
}
