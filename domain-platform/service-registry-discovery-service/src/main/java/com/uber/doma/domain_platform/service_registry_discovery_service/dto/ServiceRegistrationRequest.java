package com.uber.doma.domain_platform.service_registry_discovery_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for registering a new service instance in the registry.
 */
public class ServiceRegistrationRequest {

    @NotBlank(message = "Service name must not be blank")
    @Size(min = 1, max = 255, message = "Service name must be between 1 and 255 characters")
    private String serviceName;

    @NotBlank(message = "Host must not be blank")
    private String host;

    @NotNull(message = "Port must not be null")
    @Min(value = 1, message = "Port must be at least 1")
    private Integer port;

    @Size(max = 50, message = "Version must not exceed 50 characters")
    private String version;

    public ServiceRegistrationRequest() {
    }

    public ServiceRegistrationRequest(String serviceName, String host, Integer port, String version) {
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.version = version;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
