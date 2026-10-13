package com.uber.doma.domain_platform.audit_compliance_service.health;

import com.uber.doma.domain_platform.audit_compliance_service.AuditComplianceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AuditComplianceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // disable external db requirement for the standalone actuator testing
        "spring.autoconfigure.exclude=" +
                "io.github.resilience4j.springboot3.micrometer.autoconfigure.TimerAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false"
})
public class AuditComplianceHealthIndicatorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditComplianceHealthIndicator healthIndicator;

    @Test
    @DisplayName("Unit test: health indicator returns UP with required metadata")
    public void healthIndicatorDirectCheck() {
        Health health = healthIndicator.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals("audit-compliance-service", health.getDetails().get("service"));
        assertEquals("UP", health.getDetails().get("database"));
        assertEquals("UP", health.getDetails().get("downstreamDependencies"));
    }
    @Test
    @DisplayName("Integration test: /actuator/health returns 200 OK and UP status")
    public void shouldReturnHealthStatusUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.auditCompliance.status").value("UP"))
                .andExpect(jsonPath("$.components.auditCompliance.details.service").value("audit-compliance-service"));
    }
    @Test
    @DisplayName("Readiness probe returns UP for Kubernetes lifecycle")
    public void shouldReadinessProbeUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
    @Test
    @DisplayName("Liveness probe returns UP for Kubernetes lifecycle")
    public void shouldReturnLivenessProbeUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
