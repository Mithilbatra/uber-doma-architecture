package com.uber.doma.domain_platform.event_streaming_hub_service.spatial;

import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * H3-backed hexagonal spatial index for the event-streaming-hub-service
 * (Kafka Real-Time CDC Message Bus).
 *
 * <p>The event-streaming-hub bridges domain CDC events onto Kafka. Many of
 * those events carry a WGS-84 lat/lng (GPS pings, pickup coordinates,
 * dropoff coordinates, geofence alerts, …). Tagging each event with the
 * H3 cell that contains it lets downstream consumers run O(1) "give me
 * every event from the last hour that fell in this 1-km hex bucket"
 * queries against the Kafka log without re-projecting coordinates on
 * every read.</p>
 *
 * <p>The H3 native library is loaded once when Spring instantiates this
 * singleton via {@link H3Core#newInstance()} (which unpacks the bundled
 * native binary from the h3 jar into a temp directory and dlopens it).
 * Resolution 8 and resolution 9 are the production defaults called out
 * in the architectural context; the int overloads accept the full
 * 0–15 range so callers can opt for a different precision trade-off.</p>
 *
 * <h2>Complexity</h2>
 * <ul>
 *   <li>{@link #geoToH3Address(double, double, int)} — O(1).</li>
 *   <li>{@link #kRing(String, int)} — O(k²); returns
 *       {@code 1 + 3k(k+1)} cells for a non-pentagon origin.</li>
 * </ul>
 */
@Service
public class H3SpatialIndex {

    private static final Logger log = LoggerFactory.getLogger(H3SpatialIndex.class);

    /** Inclusive lower bound of valid H3 resolutions. */
    public static final int MIN_RESOLUTION = 0;

    /** Inclusive upper bound of valid H3 resolutions. */
    public static final int MAX_RESOLUTION = 15;

    private final H3Core h3;

    /**
     * Spring entry point. Loads the H3 native library once on bean
     * construction; the resulting {@link H3Core} instance is reused for
     * the lifetime of the JVM.
     */
    public H3SpatialIndex() throws IOException {
        this(H3Core.newInstance());
        log.info("[H3SpatialIndex] loaded H3 native library; res0 cell count={}",
                h3.numHexagons(0));
    }

    /** Visible-for-testing constructor that injects a pre-built H3Core. */
    H3SpatialIndex(final H3Core h3) {
        this.h3 = Objects.requireNonNull(h3, "h3");
    }

    /**
     * Converts a WGS-84 (lat, lng) pair into the canonical 15-character
     * H3 cell address at the requested resolution.
     *
     * <p>The conversion is deterministic: the same coordinate at the
     * same resolution always returns the same cell. Latitudes outside
     * [-90, 90] or longitudes outside [-180, 180] are rejected with
     * {@link IllegalArgumentException}. Resolutions outside
     * [0, 15] are rejected with {@link IllegalArgumentException}.</p>
     *
     * @param lat        WGS-84 latitude in decimal degrees, ∈ [-90, 90]
     * @param lng        WGS-84 longitude in decimal degrees, ∈ [-180, 180]
     * @param resolution H3 resolution, ∈ [0, 15]
     * @return canonical 15-character lowercase hex H3 address
     * @throws IllegalArgumentException for out-of-range coordinates or
     *                                  resolutions
     */
    public String geoToH3Address(final double lat, final double lng, final int resolution) {
        validateCoordinates(lat, lng);
        validateResolution(resolution);
        return h3.geoToH3Address(lat, lng, resolution);
    }

    /**
     * Returns the H3 cell address of the supplied {@code originH3} and
     * every cell at most {@code radius} hops away from it (the
     * "k-ring"). The returned list is unmodifiable and ordered with the
     * origin first, followed by the surrounding cells in H3's internal
     * order. The cardinality for a non-pentagon origin is
     * {@code 1 + 3k(k+1)}; for example:
     *
     * <pre>
     *   kRing(origin, 0) → 1 cell   (the origin)
     *   kRing(origin, 1) → 7 cells  (origin + 6 first-ring neighbors)
     *   kRing(origin, 2) → 19 cells (origin + 6 + 12)
     *   kRing(origin, 3) → 37 cells (origin + 6 + 12 + 18)
     * </pre>
     *
     * <p>Duplicate cell addresses that the H3 library may emit across
     * pentagon distortion at the icosahedron's 12 pentagonal cells are
     * deduplicated while preserving the origin-first ordering.</p>
     *
     * <p>This is the canonical "spatial fan-out" call used by the CDC
     * event router: given a CDC event tagged with an H3 cell, expand
     * it to all the cells within {@code radius} hops so consumers can
     * subscribe to coarse-grained geographic windows of the event
     * stream without a full-table spatial join.</p>
     *
     * @param originH3 the origin H3 cell address (must be a valid
     *                 15-character H3 hex string)
     * @param radius   number of concentric rings to include, must be ≥ 0
     * @return unmodifiable, deduplicated, origin-first list of H3 cell
     *         addresses within {@code radius} hops of {@code originH3}
     * @throws IllegalArgumentException for null/blank/invalid origin or
     *                                  negative radius
     */
    public List<String> kRing(final String originH3, final int radius) {
        if (originH3 == null || originH3.isBlank() || !h3.h3IsValid(originH3)) {
            throw new IllegalArgumentException("originH3 must be a valid H3 address");
        }
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0; got " + radius);
        }
        final List<String> raw = h3.kRing(originH3, radius);
        // Deduplicate while preserving order (origin first).
        final Set<String> deduped = new LinkedHashSet<>(raw);
        return List.copyOf(deduped);
    }

    private static void validateCoordinates(final double lat, final double lng) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(
                    "latitude must be finite and in [-90, 90]; got " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException(
                    "longitude must be finite and in [-180, 180]; got " + lng);
        }
    }

    private static void validateResolution(final int resolution) {
        if (resolution < MIN_RESOLUTION || resolution > MAX_RESOLUTION) {
            throw new IllegalArgumentException(
                    "resolution must be in [" + MIN_RESOLUTION + ", " + MAX_RESOLUTION
                            + "]; got " + resolution);
        }
    }
}
