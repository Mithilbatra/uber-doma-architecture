package com.uber.doma.domain_platform.service_registry_discovery_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.doma.domain_platform.service_registry_discovery_service.ServiceRegistryDiscoveryService;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceDeregistrationRequest;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceQueryRequest;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceRegistrationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ServiceRegistryController.class)
class ServiceRegistryControllerValidationTest {

    @MockBean
    private ServiceRegistryDiscoveryService service;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String BASE_URL = "/api/v1/service-registry";

    // ── Registration endpoint ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /register validation")
    class RegisterValidation {

        @Test
        @DisplayName("Valid registration returns 201 Created")
        void validRegistration_returns201() throws Exception {
            var request = new ServiceRegistrationRequest("payment-service", "10.0.0.1", 8080, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("registered"))
                    .andExpect(jsonPath("$.serviceName").value("payment-service"));
        }

        @Test
        @DisplayName("Blank service name returns 400")
        void blankServiceName_returns400() throws Exception {
            var request = new ServiceRegistrationRequest("", "10.0.0.1", 8080, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"))
                    .andExpect(jsonPath("$.fieldErrors").isArray());
        }

        @Test
        @DisplayName("Null service name returns 400")
        void nullServiceName_returns400() throws Exception {
            var request = new ServiceRegistrationRequest(null, "10.0.0.1", 8080, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'serviceName')]").exists());
        }

        @Test
        @DisplayName("Null port returns 400")
        void nullPort_returns400() throws Exception {
            var request = new ServiceRegistrationRequest("payment-service", "10.0.0.1", null, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'port')]").exists());
        }

        @Test
        @DisplayName("Port below minimum returns 400")
        void portBelowMin_returns400() throws Exception {
            var request = new ServiceRegistrationRequest("payment-service", "10.0.0.1", 0, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'port')]").exists());
        }

        @Test
        @DisplayName("Blank host returns 400")
        void blankHost_returns400() throws Exception {
            var request = new ServiceRegistrationRequest("payment-service", "", 8080, "1.0.0");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'host')]").exists());
        }

        @Test
        @DisplayName("Version exceeding max size returns 400")
        void versionTooLong_returns400() throws Exception {
            String longVersion = "v".repeat(51);
            var request = new ServiceRegistrationRequest("payment-service", "10.0.0.1", 8080, longVersion);

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'version')]").exists());
        }
    }

    // ── Deregistration endpoint ────────────────────────────────────────

    @Nested
    @DisplayName("POST /deregister validation")
    class DeregisterValidation {

        @Test
        @DisplayName("Valid deregistration returns 200")
        void validDeregistration_returns200() throws Exception {
            var request = new ServiceDeregistrationRequest("payment-service", "instance-1");

            mockMvc.perform(post(BASE_URL + "/deregister")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("deregistered"));
        }

        @Test
        @DisplayName("Blank instance ID returns 400")
        void blankInstanceId_returns400() throws Exception {
            var request = new ServiceDeregistrationRequest("payment-service", "");

            mockMvc.perform(post(BASE_URL + "/deregister")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'instanceId')]").exists());
        }
    }

    // ── Query endpoint ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /query validation")
    class QueryValidation {

        @Test
        @DisplayName("Valid query returns 200")
        void validQuery_returns200() throws Exception {
            var request = new ServiceQueryRequest("payment-service", null);

            mockMvc.perform(post(BASE_URL + "/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("found"));
        }

        @Test
        @DisplayName("Blank service name returns 400")
        void blankServiceName_returns400() throws Exception {
            var request = new ServiceQueryRequest("", null);

            mockMvc.perform(post(BASE_URL + "/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'serviceName')]").exists());
        }

        @Test
        @DisplayName("Version filter too long returns 400")
        void versionFilterTooLong_returns400() throws Exception {
            var request = new ServiceQueryRequest("payment-service", "v".repeat(51));

            mockMvc.perform(post(BASE_URL + "/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'versionFilter')]").exists());
        }
    }
}
