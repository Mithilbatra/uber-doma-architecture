package com.uber.doma.domain_platform.service_registry_discovery_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for querying service instances by name.
 */
public class ServiceQueryRequest {

    @NotBlank(message = "Service name must not be blank")
    @Size(min = 1, max = 255, message = "Service name must be between 1 and 255 characters")
    private String serviceName;

    @Size(max = 50, message = "Version filter must not exceed 50 characters")
    private String versionFilter;

    public ServiceQueryRequest() {
    }

    public ServiceQueryRequest(String serviceName, String versionFilter) {
        this.serviceName = serviceName;
        this.versionFilter = versionFilter;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getVersionFilter() {
        return versionFilter;
    }

    public void setVersionFilter(String versionFilter) {
        this.versionFilter = versionFilter;
    }
}
