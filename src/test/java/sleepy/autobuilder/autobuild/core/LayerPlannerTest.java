package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayerPlannerTest {
    private final Box3i region = Box3i.of(0, 60, 0, 3, 62, 3);

    @Test
    void yLayersAreSlicedAtOneYLevel() {
        Box3i layer = LayerPlanner.layerBox(region, LayerAxis.Y, 61);
        assertEquals(61, layer.minY());
        assertEquals(61, layer.maxY());
        assertEquals(4, layer.sizeX());
        assertEquals(4, layer.sizeZ());
        assertEquals(16, layer.volume());
    }

    @Test
    void xAndZLayersAreVerticalSlices() {
        Box3i xLayer = LayerPlanner.layerBox(region, LayerAxis.X, 2);
        assertEquals(1, xLayer.sizeX());
        assertEquals(3, xLayer.sizeY());
        assertEquals(12, xLayer.volume());

        Box3i zLayer = LayerPlanner.layerBox(region, LayerAxis.Z, 1);
        assertEquals(1, zLayer.sizeZ());
        assertEquals(3, zLayer.sizeY());
        assertEquals(12, zLayer.volume());
    }

    @Test
    void layerOrderIsRespected() {
        assertEquals(List.of(60, 61, 62), LayerPlanner.layers(region, LayerAxis.Y, LayerOrder.ASCENDING));
        assertEquals(List.of(62, 61, 60), LayerPlanner.layers(region, LayerAxis.Y, LayerOrder.DESCENDING));
        assertEquals(List.of(1, 2, 3), LayerPlanner.layers(3, 1, LayerOrder.ASCENDING));
    }

    @Test
    void nearestOrderStartsAtCenter() {
        Box3i flat = Box3i.of(0, 0, 0, 2, 0, 2);
        List<int[]> positions = LayerPlanner.positions(flat, LayerAxis.Y, 0, ConstructionOrder.NEAREST, 1, 0, 1);
        assertEquals(9, positions.size());
        assertArrayEquals(new int[]{1, 0, 1}, positions.get(0));
    }

    @Test
    void farthestOrderStartsAtCorner() {
        Box3i flat = Box3i.of(0, 0, 0, 2, 0, 2);
        List<int[]> positions = LayerPlanner.positions(flat, LayerAxis.Y, 0, ConstructionOrder.FARTHEST, 1, 0, 1);
        assertEquals(9, positions.size());
        long firstDistance = distance(positions.get(0), 1, 1);
        assertEquals(2, firstDistance);
    }

    @Test
    void playerConstructionOrderIsDeterministic() {
        Box3i flat = Box3i.of(0, 0, 0, 1, 0, 1);
        List<int[]> positions = LayerPlanner.positions(flat, LayerAxis.Y, 0, ConstructionOrder.X_THEN_Z, 0, 0, 0);
        assertArrayEquals(new int[]{0, 0, 0}, positions.get(0));
        assertArrayEquals(new int[]{0, 0, 1}, positions.get(1));
        assertArrayEquals(new int[]{1, 0, 0}, positions.get(2));
        assertArrayEquals(new int[]{1, 0, 1}, positions.get(3));
    }

    private static long distance(int[] p, int cx, int cz) {
        long dx = p[0] - cx;
        long dz = p[2] - cz;
        return dx * dx + dz * dz;
    }
}
