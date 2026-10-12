package com.uber.doma.domain_platform.service_registry_discovery_service.controller;

import com.uber.doma.domain_platform.service_registry_discovery_service.ServiceRegistryDiscoveryService;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceDeregistrationRequest;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceQueryRequest;
import com.uber.doma.domain_platform.service_registry_discovery_service.dto.ServiceRegistrationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for service registry operations.
 * All endpoints validate incoming DTOs via Jakarta Bean Validation.
 */
@RestController
@RequestMapping("/api/v1/service-registry")
public class ServiceRegistryController {

    private final ServiceRegistryDiscoveryService service;

    public ServiceRegistryController(ServiceRegistryDiscoveryService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody ServiceRegistrationRequest request) {
        service.processDomainWork();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("status", "registered", "serviceName", request.getServiceName()));
    }

    @PostMapping("/deregister")
    public ResponseEntity<Map<String, String>> deregister(
            @Valid @RequestBody ServiceDeregistrationRequest request) {
        service.processDomainWork();
        return ResponseEntity.ok(Map.of("status", "deregistered", "serviceName", request.getServiceName()));
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, String>> query(
            @Valid @RequestBody ServiceQueryRequest request) {
        service.processDomainWork();
        return ResponseEntity.ok(Map.of("status", "found", "serviceName", request.getServiceName()));
    }
}
