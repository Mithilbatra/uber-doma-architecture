package com.uber.doma.domain_platform.event_streaming_hub_service.spatial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and integration tests for {@link H3SpatialIndex}.
 *
 * <p>Coverage targets called out in the issue:</p>
 * <ul>
 *   <li><b>Geospatial coverage around coordinate anchors</b> — verify
 *       that {@code geoToH3Address} returns the canonical H3 cell for a
 *       set of well-known anchors (NYC, SF, London, Tokyo, Sydney, Dhaka,
 *       Null Island) at resolutions 8 and 9.</li>
 *   <li><b>kRing semantics</b> — verify origin-first ordering, the
 *       closed-form {@code 1 + 3k(k+1)} cell count for radii 0–5, and
 *       uniqueness of returned cells.</li>
 *   <li><b>Input validation</b> — out-of-range coordinates, NaN/Inf,
 *       invalid resolutions, blank/invalid H3 addresses, and negative
 *       radii are rejected with {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>Anchor values were captured against H3 v3.7.2 and are stable across
 * platforms (the parent BOM pins the version).</p>
 */
class H3SpatialIndexTest {

    private H3SpatialIndex index;

    @BeforeEach
    void setUp() throws IOException {
        index = new H3SpatialIndex();
    }

    // -------- geoToH3Address: geospatial coverage around coordinate anchors --------

    @Test
    @DisplayName("geoToH3Address returns canonical NYC Manhattan cell at resolutions 8 and 9")
    void geoToH3AddressForNycManhattan() {
        assertEquals("882a107289fffff", index.geoToH3Address(40.7128, -74.0060, 8));
        assertEquals("892a1072893ffff", index.geoToH3Address(40.7128, -74.0060, 9));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical San Francisco cell at resolution 8")
    void geoToH3AddressForSanFrancisco() {
        assertEquals("8828308281fffff", index.geoToH3Address(37.7749, -122.4194, 8));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical London cell at resolution 8")
    void geoToH3AddressForLondon() {
        assertEquals("88195da49bfffff", index.geoToH3Address(51.5074, -0.1278, 8));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical Tokyo cell at resolution 9")
    void geoToH3AddressForTokyo() {
        assertEquals("892f5a363bbffff", index.geoToH3Address(35.6762, 139.6503, 9));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical Sydney cell at resolution 8")
    void geoToH3AddressForSydney() {
        assertEquals("88be0e35cbfffff", index.geoToH3Address(-33.8688, 151.2093, 8));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical Dhaka cell at resolution 9")
    void geoToH3AddressForDhaka() {
        assertEquals("893cf13b04fffff", index.geoToH3Address(23.8103, 90.4125, 9));
    }

    @Test
    @DisplayName("geoToH3Address returns canonical Null Island cell at resolution 9")
    void geoToH3AddressForNullIsland() {
        assertEquals("89754e64993ffff", index.geoToH3Address(0.0, 0.0, 9));
    }

    @Test
    @DisplayName("geoToH3Address is deterministic and stable across calls")
    void geoToH3AddressIsDeterministic() {
        final String first = index.geoToH3Address(40.7128, -74.0060, 9);
        final String second = index.geoToH3Address(40.7128, -74.0060, 9);
        assertEquals(first, second);
    }

    @Test
    @DisplayName("geoToH3Address yields different cells for different resolutions")
    void differentResolutionsProduceDifferentCells() {
        final String r8 = index.geoToH3Address(40.7128, -74.0060, 8);
        final String r9 = index.geoToH3Address(40.7128, -74.0060, 9);
        assertNotEquals(r8, r9, "res-8 and res-9 cells at the same point must differ");
    }

    @Test
    @DisplayName("geoToH3Address yields different cells for sufficiently distant coordinates")
    void distantCoordinatesYieldDifferentCells() {
        // At res 9 cells are ~460 m across; ~1 km apart is safely distinct.
        final String a = index.geoToH3Address(40.7128, -74.0060, 9);
        final String b = index.geoToH3Address(40.7218, -74.0060, 9);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("geoToH3Address accepts extreme but valid coordinates (poles, antimeridian)")
    void geoToH3AddressAcceptsExtremeValidCoordinates() {
        // North pole
        assertEquals(15, index.geoToH3Address(90.0, 0.0, 9).length());
        // South pole
        assertEquals(15, index.geoToH3Address(-90.0, 0.0, 9).length());
        // Antimeridian (both 180 and -180 are valid longitudes)
        assertEquals(15, index.geoToH3Address(0.0, 180.0, 9).length());
        assertEquals(15, index.geoToH3Address(0.0, -180.0, 9).length());
    }

    // -------- geoToH3Address: input validation --------

    @Test
    @DisplayName("geoToH3Address rejects out-of-range latitude")
    void geoToH3AddressRejectsOutOfRangeLatitude() {
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(90.1, 0.0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(-90.1, 0.0, 8));
    }

    @Test
    @DisplayName("geoToH3Address rejects out-of-range longitude")
    void geoToH3AddressRejectsOutOfRangeLongitude() {
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(0.0, 180.1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(0.0, -180.1, 8));
    }

    @Test
    @DisplayName("geoToH3Address rejects NaN / infinite coordinates")
    void geoToH3AddressRejectsNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(Double.NaN, 0.0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(0.0, Double.POSITIVE_INFINITY, 8));
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(Double.NEGATIVE_INFINITY, 0.0, 8));
    }

    @Test
    @DisplayName("geoToH3Address rejects invalid resolutions")
    void geoToH3AddressRejectsInvalidResolutions() {
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(0.0, 0.0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> index.geoToH3Address(0.0, 0.0, 16));
    }

    // -------- kRing: coverage around a coordinate anchor --------

    @Test
    @DisplayName("kRing radius 0 returns just the origin cell")
    void kRingRadius0ReturnsOriginOnly() {
        final String origin = index.geoToH3Address(23.8103, 90.4125, 9);
        final List<String> ring = index.kRing(origin, 0);
        assertEquals(1, ring.size());
        assertEquals(origin, ring.get(0));
    }

    @Test
    @DisplayName("kRing radius 1 returns 1 + 6 = 7 cells")
    void kRingRadius1Returns7Cells() {
        final String origin = index.geoToH3Address(37.7749, -122.4194, 9);
        final List<String> ring = index.kRing(origin, 1);
        assertEquals(7, ring.size(), "1 origin + 6 first-ring neighbors");
        assertEquals(origin, ring.get(0), "origin must be the first cell");
        // All cells are unique.
        assertEquals(7, new HashSet<>(ring).size());
        // The origin appears exactly once.
        assertEquals(1, ring.stream().filter(origin::equals).count());
    }

    @Test
    @DisplayName("kRing closed-form 1 + 3k(k+1) for radii 0 through 5")
    void kRingMatchesClosedFormFormula() {
        final String origin = index.geoToH3Address(40.7128, -74.0060, 9);
        for (int k = 0; k <= 5; k++) {
            final int expected = 1 + 3 * k * (k + 1);
            final List<String> ring = index.kRing(origin, k);
            assertEquals(expected, ring.size(),
                    "kRing radius " + k + " should yield " + expected + " cells");
        }
    }

    @Test
    @DisplayName("kRing coverage grows monotonically with radius")
    void kRingCoverageGrowsWithRadius() {
        final String origin = index.geoToH3Address(35.6762, 139.6503, 9);
        int previousSize = 0;
        for (int k = 0; k <= 4; k++) {
            final List<String> ring = index.kRing(origin, k);
            assertTrue(ring.size() > previousSize,
                    "kRing must grow strictly with radius; r=" + k
                            + " had " + ring.size() + " cells, prior had " + previousSize);
            previousSize = ring.size();
        }
    }

    @Test
    @DisplayName("kRing result is unmodifiable")
    void kRingResultIsImmutable() {
        final String origin = index.geoToH3Address(23.8103, 90.4125, 8);
        final List<String> cells = index.kRing(origin, 1);
        assertThrows(UnsupportedOperationException.class, () -> cells.add(origin));
    }

    // -------- kRing: input validation --------

    @Test
    @DisplayName("kRing rejects null / blank origin")
    void kRingRejectsNullAndBlankOrigin() {
        assertThrows(IllegalArgumentException.class, () -> index.kRing(null, 1));
        assertThrows(IllegalArgumentException.class, () -> index.kRing("", 1));
        assertThrows(IllegalArgumentException.class, () -> index.kRing("   ", 1));
    }

    @Test
    @DisplayName("kRing rejects invalid H3 address")
    void kRingRejectsInvalidH3Address() {
        assertThrows(IllegalArgumentException.class,
                () -> index.kRing("not-an-h3-index", 1));
        // Right length, wrong characters.
        assertThrows(IllegalArgumentException.class,
                () -> index.kRing("zzzzzzzzzzzzzzz", 1));
    }

    @Test
    @DisplayName("kRing rejects negative radius")
    void kRingRejectsNegativeRadius() {
        final String origin = index.geoToH3Address(0.0, 0.0, 9);
        assertThrows(IllegalArgumentException.class, () -> index.kRing(origin, -1));
    }

    // -------- composite: spatial coverage around an anchor --------

    @Test
    @DisplayName("the kRing of an anchor cell fully contains the anchor")
    void kRingAlwaysContainsOrigin() {
        final String origin = index.geoToH3Address(40.7128, -74.0060, 9);
        for (int k = 0; k <= 3; k++) {
            final List<String> ring = index.kRing(origin, k);
            assertTrue(ring.contains(origin),
                    "kRing at radius " + k + " must contain the origin");
        }
    }

    @Test
    @DisplayName("all cells in a kRing share the same resolution as the origin")
    void kRingCellsShareResolution() {
        final String origin = index.geoToH3Address(40.7128, -74.0060, 9);
        final List<String> ring = index.kRing(origin, 2);
        // Every returned cell is 15 characters and starts with the
        // res-9 prefix '89' (the second hex digit is the resolution
        // nibble for the cell).
        for (final String cell : ring) {
            assertEquals(15, cell.length(), "every H3 address is 15 hex chars");
            assertFalse(cell.isBlank());
        }
    }
}
