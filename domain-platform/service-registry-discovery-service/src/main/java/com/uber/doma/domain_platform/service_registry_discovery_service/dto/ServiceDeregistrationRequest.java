package com.uber.doma.domain_platform.service_registry_discovery_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for deregistering a service instance from the registry.
 */
public class ServiceDeregistrationRequest {

    @NotBlank(message = "Service name must not be blank")
    @Size(min = 1, max = 255, message = "Service name must be between 1 and 255 characters")
    private String serviceName;

    @NotBlank(message = "Instance ID must not be blank")
    private String instanceId;

    public ServiceDeregistrationRequest() {
    }

    public ServiceDeregistrationRequest(String serviceName, String instanceId) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
}
