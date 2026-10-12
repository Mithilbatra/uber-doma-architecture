package com.uber.doma.domain_platform.event_streaming_hub_service.spatial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * HTTP front-end for {@link H3SpatialIndex}. The controller exposes the
 * two spatial-query primitives — coordinate → H3 cell and H3 cell →
 * k-ring — as plain JSON endpoints so the CDC event router and any
 * external geo-aware Kafka consumer can resolve cells over HTTP without
 * having to embed the H3 native library on the client side.
 *
 * <p>Endpoint summary:</p>
 * <ul>
 *   <li>{@code GET /spatial/h3/address?lat=…&lng=…&resolution=…} — convert
 *       a WGS-84 lat/lng pair to its H3 cell address.</li>
 *   <li>{@code GET /spatial/h3/kring?originH3=…&radius=…} — expand an
 *       H3 cell into the k-ring of cells at most {@code radius} hops
 *       away.</li>
 * </ul>
 */
@RestController
@RequestMapping("/spatial/h3")
public class H3SpatialQueryController {

    private final H3SpatialIndex h3SpatialIndex;

    public H3SpatialQueryController(final H3SpatialIndex h3SpatialIndex) {
        this.h3SpatialIndex = Objects.requireNonNull(h3SpatialIndex, "h3SpatialIndex");
    }

    /**
     * Returns the H3 cell address that contains the supplied WGS-84
     * coordinate at the requested resolution.
     *
     * @param lat        WGS-84 latitude in decimal degrees
     * @param lng        WGS-84 longitude in decimal degrees
     * @param resolution H3 resolution, 0–15
     * @return the canonical 15-character H3 hex address
     */
    @GetMapping("/address")
    public H3AddressResponse geoToH3Address(
            @RequestParam("lat") final double lat,
            @RequestParam("lng") final double lng,
            @RequestParam("resolution") final int resolution) {
        final String address = h3SpatialIndex.geoToH3Address(lat, lng, resolution);
        return new H3AddressResponse(lat, lng, resolution, address);
    }

    /**
     * Returns the k-ring of H3 cells at most {@code radius} hops away
     * from {@code originH3}. The response always contains the origin
     * cell plus every unique neighbor within the requested radius.
     *
     * @param originH3 the origin H3 cell address
     * @param radius   number of concentric rings to include, ≥ 0
     * @return the origin cell and every cell within {@code radius} hops
     */
    @GetMapping("/kring")
    public H3KRingResponse kRing(
            @RequestParam("originH3") final String originH3,
            @RequestParam("radius") final int radius) {
        final List<String> cells = h3SpatialIndex.kRing(originH3, radius);
        return new H3KRingResponse(originH3, radius, cells);
    }

    /** Response body for {@code GET /spatial/h3/address}. */
    public record H3AddressResponse(
            double latitude,
            double longitude,
            int resolution,
            String h3Address) {
    }

    /** Response body for {@code GET /spatial/h3/kring}. */
    public record H3KRingResponse(
            String originH3,
            int radius,
            List<String> cells) {
    }
}
