package sleepy.autobuilder.autobuild.core;

import java.util.Iterator;
import java.util.Set;

/**
 * Reconciles a saved session with the schematic, region, layer range and world height that are valid
 * right now.
 *
 * <p>Saved progress is not trusted merely because the JSON file exists. Positions outside the
 * current box, layer range or world height are removed, a stale current-layer value is clamped to
 * the real bounds, and an anchor/resume point that no longer makes sense is cleared. This is a pure
 * operation so it can be tested without Minecraft or a world.</p>
 */
public final class ProgressReconciler {
    public record Result(int removed, int pendingRemaining, int layerBefore, int layerAfter,
                         boolean anchorCleared, boolean resumeCleared) {
        public boolean changed() {
            return removed > 0 || layerBefore != layerAfter || anchorCleared || resumeCleared;
        }
    }

    private static final int ANCHOR_MARGIN = 128;

    private ProgressReconciler() {
    }

    public static Result reconcile(ProgressStore.SessionProgress session, Box3i region,
                                   LayerAxis axis, int layerMin, int layerMax) {
        return reconcile(session, region, axis, layerMin, layerMax, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * @param worldBottom inclusive minimum build height (for example {@code world.getBottomY()})
     * @param worldTop    inclusive maximum build height (for example {@code world.getTopY() - 1})
     */
    public static Result reconcile(ProgressStore.SessionProgress session, Box3i region,
                                   LayerAxis axis, int layerMin, int layerMax,
                                   int worldBottom, int worldTop) {
        if (session == null) return new Result(0, 0, 0, 0, false, false);
        int low = Math.min(layerMin, layerMax);
        int high = Math.max(layerMin, layerMax);
        int removed = 0;
        removed += prune(session.completed, region, axis, low, high, worldBottom, worldTop);
        removed += prune(session.ignored, region, axis, low, high, worldBottom, worldTop);
        removed += prune(session.pending, region, axis, low, high, worldBottom, worldTop);
        removed += prune(session.failed, region, axis, low, high, worldBottom, worldTop);

        int before = session.currentLayer;
        int after = Math.max(low, Math.min(high, before));
        session.currentLayer = after;
        session.layerStart = Math.max(low, Math.min(high, session.layerStart));
        session.layerEnd = Math.max(low, Math.min(high, session.layerEnd));
        if (session.layerStart > session.layerEnd) {
            int swap = session.layerStart;
            session.layerStart = session.layerEnd;
            session.layerEnd = swap;
        }

        boolean anchorCleared = false;
        if (session.hasAnchor) {
            boolean insideWorld = session.anchorY >= worldBottom && session.anchorY <= worldTop;
            boolean nearRegion = withinExpanded(region, session.anchorX, session.anchorY,
                    session.anchorZ, ANCHOR_MARGIN);
            if (!insideWorld || !nearRegion) {
                session.hasAnchor = false;
                anchorCleared = true;
            }
        }

        boolean resumeCleared = false;
        if (session.hasResume) {
            boolean insideWorld = session.resumeY >= worldBottom && session.resumeY <= worldTop;
            if (!region.contains(session.resumeX, session.resumeY, session.resumeZ) || !insideWorld) {
                session.hasResume = false;
                resumeCleared = true;
            }
        }

        return new Result(removed, session.pending.size(), before, after, anchorCleared, resumeCleared);
    }

    /** Counts pending positions that are inside the current region and layer range. */
    public static int countValidPending(ProgressStore.SessionProgress session, Box3i region,
                                        LayerAxis axis, int layerMin, int layerMax) {
        return countValidPending(session, region, axis, layerMin, layerMax,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static int countValidPending(ProgressStore.SessionProgress session, Box3i region,
                                        LayerAxis axis, int layerMin, int layerMax,
                                        int worldBottom, int worldTop) {
        if (session == null) return 0;
        int low = Math.min(layerMin, layerMax);
        int high = Math.max(layerMin, layerMax);
        int count = 0;
        for (long packed : session.pending) {
            if (inside(packed, region, axis, low, high, worldBottom, worldTop)) count++;
        }
        return count;
    }

    private static int prune(Set<Long> positions, Box3i region, LayerAxis axis, int low, int high,
                             int worldBottom, int worldTop) {
        int removed = 0;
        for (Iterator<Long> iterator = positions.iterator(); iterator.hasNext(); ) {
            long packed = iterator.next();
            if (!inside(packed, region, axis, low, high, worldBottom, worldTop)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean inside(long packed, Box3i region, LayerAxis axis, int low, int high,
                                  int worldBottom, int worldTop) {
        int x = PositionCodec.unpackX(packed);
        int y = PositionCodec.unpackY(packed);
        int z = PositionCodec.unpackZ(packed);
        if (y < worldBottom || y > worldTop) return false;
        if (!region.contains(x, y, z)) return false;
        int coordinate = switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        return coordinate >= low && coordinate <= high;
    }

    private static boolean withinExpanded(Box3i region, int x, int y, int z, int margin) {
        return x >= region.minX() - margin && x <= region.maxX() + margin
                && y >= region.minY() - margin && y <= region.maxY() + margin
                && z >= region.minZ() - margin && z <= region.maxZ() + margin;
    }
}
