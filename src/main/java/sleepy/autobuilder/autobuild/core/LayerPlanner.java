package sleepy.autobuilder.autobuild.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure layer slicing and construction-order planning.
 */
public final class LayerPlanner {
    /**
     * Result of clamping a requested layer range to the real schematic bounds. {@code adjusted} is
     * true when at least one endpoint had to move, so callers can tell the user instead of silently
     * accepting a stale saved value.
     */
    public record Range(int start, int end, boolean adjusted) {
        public Range {
            if (start > end) throw new IllegalArgumentException("start must be <= end");
        }

        public int size() {
            return end - start + 1;
        }
    }

    private LayerPlanner() {
    }

    public static Box3i layerBox(Box3i region, LayerAxis axis, int coordinate) {
        return switch (axis) {
            case Y -> new Box3i(region.minX(), coordinate, region.minZ(), region.maxX(), coordinate, region.maxZ());
            case X -> new Box3i(coordinate, region.minY(), region.minZ(), coordinate, region.maxY(), region.maxZ());
            case Z -> new Box3i(region.minX(), region.minY(), coordinate, region.maxX(), region.maxY(), coordinate);
        };
    }

    public static List<Integer> layers(Box3i region, LayerAxis axis, LayerOrder order) {
        int min = min(region, axis);
        int max = max(region, axis);
        return layers(min, max, order);
    }

    public static List<Integer> layers(int start, int end, LayerOrder order) {
        int min = Math.min(start, end);
        int max = Math.max(start, end);
        List<Integer> result = new ArrayList<>(Math.max(0, max - min + 1));
        if (order == LayerOrder.DESCENDING) {
            for (int i = max; i >= min; i--) result.add(i);
        } else {
            for (int i = min; i <= max; i++) result.add(i);
        }
        return result;
    }

    public static List<int[]> positions(Box3i region, LayerAxis axis, int coordinate,
                                        ConstructionOrder order, int centerX, int centerY, int centerZ) {
        Box3i layer = layerBox(region, axis, coordinate);
        List<int[]> positions = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, layer.volume()));
        for (int y = layer.minY(); y <= layer.maxY(); y++) {
            for (int z = layer.minZ(); z <= layer.maxZ(); z++) {
                for (int x = layer.minX(); x <= layer.maxX(); x++) {
                    positions.add(new int[]{x, y, z});
                }
            }
        }

        Comparator<int[]> comparator = switch (order) {
            case X_THEN_Z -> Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[2]).thenComparingInt(p -> p[1]);
            case Z_THEN_X -> Comparator.<int[]>comparingInt(p -> p[2]).thenComparingInt(p -> p[0]).thenComparingInt(p -> p[1]);
            case NEAREST, FARTHEST -> Comparator.comparingLong(p -> distanceSq(p, centerX, centerY, centerZ));
        };
        if (order == ConstructionOrder.FARTHEST) comparator = comparator.reversed();
        positions.sort(comparator.thenComparingInt(p -> p[1]).thenComparingInt(p -> p[0]).thenComparingInt(p -> p[2]));
        return positions;
    }

    public static int min(Box3i region, LayerAxis axis) {
        return switch (axis) {
            case Y -> region.minY();
            case X -> region.minX();
            case Z -> region.minZ();
        };
    }

    public static int max(Box3i region, LayerAxis axis) {
        return switch (axis) {
            case Y -> region.maxY();
            case X -> region.maxX();
            case Z -> region.maxZ();
        };
    }

    /**
     * Layer settings store absolute block coordinates, never zero-based layer indices. Clamping is
     * therefore done against the real min/max of the selected schematic on the selected axis.
     */
    public static int clampCoordinate(Box3i region, LayerAxis axis, int coordinate) {
        int min = min(region, axis);
        int max = max(region, axis);
        return Math.max(min, Math.min(max, coordinate));
    }

    /**
     * Clamps both endpoints, then normalises reversed input. A range completely outside the
     * schematic becomes the nearest single valid layer instead of blocking the whole job.
     */
    public static Range clampRange(Box3i region, LayerAxis axis, int start, int end) {
        int min = min(region, axis);
        int max = max(region, axis);
        int clampedStart = Math.max(min, Math.min(max, start));
        int clampedEnd = Math.max(min, Math.min(max, end));
        boolean adjusted = clampedStart != start || clampedEnd != end;
        if (clampedStart > clampedEnd) {
            int swap = clampedStart;
            clampedStart = clampedEnd;
            clampedEnd = swap;
            adjusted = true;
        }
        return new Range(clampedStart, clampedEnd, adjusted);
    }

    public static boolean contains(Box3i region, LayerAxis axis, int coordinate) {
        return coordinate >= min(region, axis) && coordinate <= max(region, axis);
    }

    public static boolean contains(Box3i region, LayerAxis axis, int coordinate, int minLayer, int maxLayer) {
        int low = Math.min(minLayer, maxLayer);
        int high = Math.max(minLayer, maxLayer);
        return coordinate >= low && coordinate <= high && contains(region, axis, coordinate);
    }

    private static long distanceSq(int[] p, int cx, int cy, int cz) {
        long dx = (long) p[0] - cx;
        long dy = (long) p[1] - cy;
        long dz = (long) p[2] - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
