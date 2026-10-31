package sleepy.autobuilder.autobuild.mc;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.core.Box3i;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Direct Litematica 0.19.61 / Minecraft 1.21.1 adapter. Loaded reflectively by
 * {@link LitematicaBridge}; never referenced when Litematica is absent.
 */
public final class LitematicaBridgeImpl implements SchematicAccess {
    @Override
    public PlacementSnapshot snapshot() {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (placement == null) return null;

        Map<String, Box3i> regions = new LinkedHashMap<>();
        try {
            placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).forEach((name, box) -> {
                Box3i converted = convert(box);
                if (converted != null) regions.put(name, converted);
            });
        } catch (Throwable ignored) {
            // Older or malformed placements can lack sub-region boxes; the enclosing box is enough.
        }

        Box3i bounds = convert(placement.getEclosingBox());
        if (bounds == null) {
            bounds = union(regions);
        }
        if (bounds == null) return null;

        LitematicaSchematic schematic = placement.getSchematic();
        String schematicName = "";
        if (schematic != null && schematic.getFile() != null) {
            schematicName = schematic.getFile().getName();
        }
        if (schematicName.isEmpty()) schematicName = placement.getName();
        String selected = placement.getSelectedSubRegionName();
        if (selected == null) selected = "";

        return new PlacementSnapshot(
                placement.getName(),
                schematicName,
                placement.getOrigin(),
                bounds,
                regions,
                selected,
                placement.getRotation().name(),
                placement.getMirror().name()
        );
    }

    @Override
    public boolean isWorldReady() {
        return SchematicWorldHandler.getSchematicWorld() != null;
    }

    @Override
    public BlockState expectedState(BlockPos pos) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null || pos == null) return null;
        return world.getBlockState(pos);
    }

    private static Box3i convert(Box box) {
        if (box == null || box.getPos1() == null || box.getPos2() == null) return null;
        BlockPos a = box.getPos1();
        BlockPos b = box.getPos2();
        return Box3i.of(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    private static Box3i union(Map<String, Box3i> regions) {
        if (regions.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Box3i box : regions.values()) {
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        return new Box3i(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
