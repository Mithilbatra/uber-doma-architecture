package com.uber.doma.domain_mobility.routing_engine_service.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record ComputeRouteRequestDto(
    @NotNull(message = "originLat is required")
    @DecimalMin("-90.0") @DecimalMax("90.0")
    Double originLat,

    @NotNull(message = "originLng is required")
    @DecimalMin("-180.0") @DecimalMax("180.0")
    Double originLng,

    @NotNull(message = "destLat is required")
    @DecimalMin("-90.0") @DecimalMax("90.0")
    Double destLat,

    @NotNull(message = "destLng is required")
    @DecimalMin("-180.0") @DecimalMax("180.0")
    Double destLng
) {}
