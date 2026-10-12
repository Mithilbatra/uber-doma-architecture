package com.uber.doma.domain_platform.experimentation_flipr_service.spatial;

import com.uber.h3core.H3Core;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Converts experiment locations to H3 cells and expands cells into their
 * neighboring search area.
 */
@Service
public class H3SpatialQueryService {

    public static final int MIN_RESOLUTION = 0;
    public static final int MAX_RESOLUTION = 15;

    private final H3Core h3;

    /**
     * Loads the H3 native library once when Spring creates this singleton.
     */
    public H3SpatialQueryService() throws IOException {
        this(H3Core.newInstance());
    }

    H3SpatialQueryService(final H3Core h3) {
        this.h3 = Objects.requireNonNull(h3, "h3");
    }

    /**
     * Returns the canonical H3 address containing the supplied WGS-84 point.
     * Resolutions 8 and 9 are the service defaults, while the full H3 range is
     * accepted so callers can make an explicit precision trade-off.
     */
    public String geoToH3Address(final double lat, final double lng, final int resolution) {
        validateCoordinates(lat, lng);
        validateResolution(resolution);
        return h3.geoToH3Address(lat, lng, resolution);
    }

    /**
     * Returns the origin and every H3 cell at most {@code radius} hops away.
     * The returned snapshot cannot be modified by callers.
     */
    public List<String> kRing(final String originH3, final int radius) {
        if (originH3 == null || originH3.isBlank() || !h3.h3IsValid(originH3)) {
            throw new IllegalArgumentException("originH3 must be a valid H3 address");
        }
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0; got " + radius);
        }
        return List.copyOf(h3.kRing(originH3, radius));
    }

    private static void validateCoordinates(final double lat, final double lng) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("latitude must be finite and in [-90, 90]; got " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("longitude must be finite and in [-180, 180]; got " + lng);
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
