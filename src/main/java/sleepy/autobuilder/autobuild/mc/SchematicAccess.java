package sleepy.autobuilder.autobuild.mc;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.core.Box3i;

import java.util.Map;

/**
 * Narrow interface around Litematica. The implementation class is only loaded when Litematica is
 * present, so the rest of the addon never links against Litematica classes.
 */
public interface SchematicAccess {
    PlacementSnapshot snapshot();

    boolean isWorldReady();

    BlockState expectedState(BlockPos pos);

    record PlacementSnapshot(
            String placementName,
            String schematicName,
            BlockPos origin,
            Box3i boundingBox,
            Map<String, Box3i> subRegions,
            String selectedSubRegion,
            String rotation,
            String mirror
    ) {
        public PlacementSnapshot {
            placementName = placementName == null ? "" : placementName;
            schematicName = schematicName == null ? "" : schematicName;
            subRegions = subRegions == null ? Map.of() : Map.copyOf(subRegions);
            selectedSubRegion = selectedSubRegion == null ? "" : selectedSubRegion;
            rotation = rotation == null ? "" : rotation;
            mirror = mirror == null ? "" : mirror;
        }
    }
}
