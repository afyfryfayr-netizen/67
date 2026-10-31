package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayerPlannerBoundsTest {
    @Test
    void clampsNegativeZeroAndPositiveCoordinatesOnEveryAxis() {
        Box3i region = Box3i.of(-80, -64, -90, -60, 320, -70);

        assertEquals(-80, LayerPlanner.clampCoordinate(region, LayerAxis.X, -999));
        assertEquals(-60, LayerPlanner.clampCoordinate(region, LayerAxis.X, 999));
        assertEquals(-70, LayerPlanner.clampCoordinate(region, LayerAxis.X, -70));
        assertEquals(-64, LayerPlanner.clampCoordinate(region, LayerAxis.Y, -999));
        assertEquals(320, LayerPlanner.clampCoordinate(region, LayerAxis.Y, 999));
        assertEquals(0, LayerPlanner.clampCoordinate(region, LayerAxis.Y, 0));
        assertEquals(-90, LayerPlanner.clampCoordinate(region, LayerAxis.Z, -999));
        assertEquals(-70, LayerPlanner.clampCoordinate(region, LayerAxis.Z, 999));
        assertEquals(-70, LayerPlanner.clampCoordinate(region, LayerAxis.Z, -70));
    }

    @Test
    void zeroSizedRegionStillProducesOneValidLayer() {
        Box3i point = Box3i.of(0, 0, 0, 0, 0, 0);
        for (LayerAxis axis : LayerAxis.values()) {
            assertEquals(0, LayerPlanner.clampCoordinate(point, axis, 64));
            assertEquals(List.of(0), LayerPlanner.layers(point, axis, LayerOrder.ASCENDING));
            assertEquals(1, LayerPlanner.layerBox(point, axis, 0).volume());
        }
    }

    @Test
    void singleLayerSchematicIsNotBlockedByStaleSettings() {
        Box3i single = Box3i.of(10, 63, -4, 12, 63, -2);
        assertEquals(63, LayerPlanner.clampCoordinate(single, LayerAxis.Y, 64));
        assertEquals(List.of(63), LayerPlanner.layers(single, LayerAxis.Y, LayerOrder.ASCENDING));
        assertEquals(1, LayerPlanner.layers(single, LayerAxis.Y, LayerOrder.DESCENDING).size());
    }

    @Test
    void reversedRangeIsNormalisedAfterClamping() {
        Box3i region = Box3i.of(0, 60, 0, 3, 63, 3);
        LayerPlanner.Range range = LayerPlanner.clampRange(region, LayerAxis.Y, 64, 59);
        assertEquals(60, range.start());
        assertEquals(63, range.end());
        assertTrue(range.adjusted());
    }

    @Test
    void rangeEntirelyOutsideBoundsBecomesNearestSingleLayer() {
        Box3i region = Box3i.of(0, -60, 0, 3, 63, 3);
        LayerPlanner.Range above = LayerPlanner.clampRange(region, LayerAxis.Y, 64, 80);
        assertEquals(63, above.start());
        assertEquals(63, above.end());
        assertTrue(above.adjusted());

        LayerPlanner.Range below = LayerPlanner.clampRange(region, LayerAxis.Y, -100, -80);
        assertEquals(-60, below.start());
        assertEquals(-60, below.end());
        assertTrue(below.adjusted());
    }

    @Test
    void alreadyValidRangeIsNotMarkedAdjusted() {
        Box3i region = Box3i.of(0, 60, 0, 3, 63, 3);
        LayerPlanner.Range range = LayerPlanner.clampRange(region, LayerAxis.Y, 61, 62);
        assertEquals(61, range.start());
        assertEquals(62, range.end());
        assertFalse(range.adjusted());
    }

    @Test
    void reversedLayerOrderStillCoversTheWholeRange() {
        Box3i region = Box3i.of(0, 60, 0, 0, 63, 0);
        assertEquals(List.of(63, 62, 61, 60),
                LayerPlanner.layers(region, LayerAxis.Y, LayerOrder.DESCENDING));
        assertEquals(List.of(60, 61, 62, 63),
                LayerPlanner.layers(region, LayerAxis.Y, LayerOrder.ASCENDING));
    }

    @Test
    void layerContainsRequiresBothRegionAndLayerRange() {
        Box3i region = Box3i.of(-10, 60, -10, 10, 70, 10);
        assertTrue(LayerPlanner.contains(region, LayerAxis.Y, 62, 60, 63));
        assertFalse(LayerPlanner.contains(region, LayerAxis.Y, 65, 60, 63));
        assertFalse(LayerPlanner.contains(region, LayerAxis.Y, 59, 60, 63));
        assertFalse(LayerPlanner.contains(region, LayerAxis.Y, 80, 60, 63));
    }
}
