package com.uber.doma.domain_platform.event_streaming_hub_service.spatial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link H3SpatialIndex} that exercise the
 * spatial-fan-out workflow end-to-end against realistic event-streaming
 * distributions. The tests verify the round-trip and composition
 * behavior the CDC event router relies on to tag CDC events with H3
 * cells and expand them into k-rings for downstream geographic
 * subscriptions.
 */
class H3SpatialIndexIntegrationTest {

    private H3SpatialIndex index;

    @BeforeEach
    void setUp() throws IOException {
        index = new H3SpatialIndex();
    }

    @Test
    @DisplayName("the union of kRing(0) over each event cell trivially covers every event cell")
    void kRingOverEachEventCoversItself() {
        // For each event cell, kRing(0) returns just that cell. The union
        // of those trivial kRings must equal the set of touched cells.
        final List<double[]> events = generateLatLngsAround(40.7580, -73.9855, 0.015, 25);
        final Set<String> touchedCells = new HashSet<>();
        final Set<String> kRingUnion = new HashSet<>();
        for (final double[] latLng : events) {
            final String cell = index.geoToH3Address(latLng[0], latLng[1], 9);
            touchedCells.add(cell);
            kRingUnion.addAll(index.kRing(cell, 0));
        }
        assertEquals(touchedCells, kRingUnion,
                "the union of kRing(0) over every event cell must cover every event cell");
    }

    @Test
    @DisplayName("kRing with a large radius around an anchor covers a 1.5 km scatter of 25 NYC events")
    void kRingCoversNearbyEventCloud() {
        // 25 events scattered within ~1.5 km of Times Square (range
        // 0.015 degrees ≈ 1.67 km in latitude, 1.19 km in longitude).
        // kRing(8) at res 9 is ~7.4 km in diameter and comfortably
        // contains the scatter.
        final List<double[]> events = generateLatLngsAround(40.7580, -73.9855, 0.015, 25);
        final Set<String> touchedCells = new HashSet<>();
        for (final double[] latLng : events) {
            touchedCells.add(index.geoToH3Address(latLng[0], latLng[1], 9));
        }
        assertFalse(touchedCells.isEmpty(), "events should occupy at least one cell");

        // The kRing of the anchor cell at radius 8 must cover every cell
        // that any of the 25 events fall into.
        final String anchor = index.geoToH3Address(40.7580, -73.9855, 9);
        final List<String> coverage = index.kRing(anchor, 8);
        for (final String cell : touchedCells) {
            assertTrue(coverage.contains(cell),
                    "every event cell must be in kRing(8) of the anchor; missing " + cell);
        }
    }

    @Test
    @DisplayName("kRing(5) at resolution 8 covers a 100-event NYC scatter")
    void kRingAtRes8CoversAllEvents() {
        // 100 events scattered within ~1.5 km of the anchor.
        final List<double[]> events = generateLatLngsAround(40.7128, -74.0060, 0.015, 100);
        final String anchor = index.geoToH3Address(40.7128, -74.0060, 8);
        final List<String> coverage = index.kRing(anchor, 5);

        // At res 8, kRing(5) is ~5.6 km in diameter which comfortably
        // contains a 3 km scatter including corner-to-center diagonals.
        final Set<String> coverageSet = new HashSet<>(coverage);
        for (final double[] latLng : events) {
            final String cell = index.geoToH3Address(latLng[0], latLng[1], 8);
            assertTrue(coverageSet.contains(cell),
                    "every res-8 event cell must be in kRing(5) coverage; missing " + cell);
        }
    }

    @Test
    @DisplayName("kRing at res 8 and kRing at res 9 are disjoint for the same anchor")
    void kRingAtDifferentResolutionsAreDisjoint() {
        final String r8 = index.geoToH3Address(40.7128, -74.0060, 8);
        final String r9 = index.geoToH3Address(40.7128, -74.0060, 9);
        final List<String> r8Ring = index.kRing(r8, 2);
        final List<String> r9Ring = index.kRing(r9, 2);
        // Both should yield the closed-form count.
        assertEquals(19, r8Ring.size());
        assertEquals(19, r9Ring.size());
        // But the cell sets are disjoint (different resolutions).
        final Set<String> r8Set = new HashSet<>(r8Ring);
        for (final String cell : r9Ring) {
            assertFalse(r8Set.contains(cell),
                    "res-8 and res-9 cells must be disjoint; collision at " + cell);
        }
    }

    @Test
    @DisplayName("higher-resolution kRing strictly supersedes lower-resolution for the same point")
    void higherResolutionRingCoversFinerGrain() {
        // Two events ~1 km apart: at res 9 (~460 m cells) they fall in
        // distinct cells. The same point projected at res 8 and res 9
        // must produce distinct cells (resolutions are never shared).
        final String nycA = index.geoToH3Address(40.7128, -74.0060, 9);
        final String nycB = index.geoToH3Address(40.7218, -74.0060, 9);
        final String nycA8 = index.geoToH3Address(40.7128, -74.0060, 8);
        // At res 9, ~1 km apart crosses multiple ~460 m cell boundaries.
        assertEquals(false, nycA.equals(nycB),
                "~1 km apart should be distinct res-9 cells");
        // The res-8 and res-9 cell for the same point never coincide.
        assertEquals(false, nycA.substring(0, 2).equals(nycA8.substring(0, 2)),
                "res-8 and res-9 addresses for the same point must have different prefixes");
    }

    @Test
    @DisplayName("every cell in kRing is itself a valid H3 address")
    void kRingCellsAreAllValid() {
        final String origin = index.geoToH3Address(40.7128, -74.0060, 9);
        final List<String> ring = index.kRing(origin, 3);
        for (final String cell : ring) {
            assertEquals(15, cell.length(), "every kRing cell is 15 hex chars");
            assertTrue(cell.matches("[0-9a-f]{15}"),
                    "every kRing cell matches the lowercase hex pattern; got " + cell);
        }
    }

    /**
     * Generates a deterministic set of lat/lng pairs scattered around
     * the given anchor within a {@code +/-} range in both dimensions.
     * The distribution is a uniform {@code side × side} lattice so the
     * test is reproducible.
     */
    private List<double[]> generateLatLngsAround(
            final double lat, final double lng, final double range, final int count) {
        final List<double[]> out = new ArrayList<>(count);
        final int side = (int) Math.ceil(Math.sqrt(count));
        int emitted = 0;
        for (int i = 0; i < side && emitted < count; i++) {
            for (int j = 0; j < side && emitted < count; j++) {
                final double latOut = lat - range + (2.0 * range * i) / side;
                final double lngOut = lng - range + (2.0 * range * j) / side;
                out.add(new double[] { latOut, lngOut });
                emitted++;
            }
        }
        return out;
    }
}
