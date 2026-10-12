package com.uber.doma.domain_platform.experimentation_flipr_service.spatial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H3SpatialQueryServiceTest {

    private H3SpatialQueryService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new H3SpatialQueryService();
    }

    @Test
    void convertsGlobalCoordinateAnchorsAtResolutionsEightAndNine() {
        assertEquals("882a107289fffff", service.geoToH3Address(40.7128, -74.0060, 8));
        assertEquals("892a1072893ffff", service.geoToH3Address(40.7128, -74.0060, 9));
        assertEquals("88195da49bfffff", service.geoToH3Address(51.5074, -0.1278, 8));
        assertEquals("892f5a363bbffff", service.geoToH3Address(35.6762, 139.6503, 9));
        assertEquals("88be0e35cbfffff", service.geoToH3Address(-33.8688, 151.2093, 8));
    }

    @Test
    void conversionIsStableAndPrecisionChangesTheCellAddress() {
        final String first = service.geoToH3Address(23.8103, 90.4125, 9);

        assertEquals(first, service.geoToH3Address(23.8103, 90.4125, 9));
        assertNotEquals(first, service.geoToH3Address(23.8103, 90.4125, 8));
    }

    @Test
    void kRingZeroContainsOnlyTheOrigin() {
        final String origin = service.geoToH3Address(23.8103, 90.4125, 9);

        assertEquals(List.of(origin), service.kRing(origin, 0));
    }

    @Test
    void kRingCoversTheExpectedHexagonalNeighborhoods() {
        final String origin = service.geoToH3Address(37.7749, -122.4194, 9);

        for (int radius = 1; radius <= 3; radius++) {
            final List<String> cells = service.kRing(origin, radius);
            assertEquals(1 + 3 * radius * (radius + 1), cells.size());
            assertEquals(cells.size(), new HashSet<>(cells).size());
            assertTrue(cells.contains(origin));
        }
    }

    @Test
    void kRingResultIsImmutable() {
        final String origin = service.geoToH3Address(23.8103, 90.4125, 8);
        final List<String> cells = service.kRing(origin, 1);

        assertThrows(UnsupportedOperationException.class, () -> cells.add(origin));
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(90.1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(-90.1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(0, 180.1, 8));
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(0, -180.1, 8));
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(Double.NaN, 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> service.geoToH3Address(0, Double.POSITIVE_INFINITY, 8));
    }

    @Test
    void rejectsInvalidResolutions() {
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> service.geoToH3Address(0, 0, 16));
    }

    @Test
    void rejectsInvalidRingArguments() {
        final String origin = service.geoToH3Address(23.8103, 90.4125, 9);

        assertThrows(IllegalArgumentException.class, () -> service.kRing(null, 1));
        assertThrows(IllegalArgumentException.class, () -> service.kRing(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> service.kRing("not-an-h3-index", 1));
        assertThrows(IllegalArgumentException.class, () -> service.kRing(origin, -1));
    }
}
