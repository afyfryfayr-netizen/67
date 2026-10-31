package sleepy.autobuilder.autobuild.mc;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Determines which face to click for a desired block state. Minecraft derives orientation from the
 * placement context, so this is explicitly best-effort: the engine verifies every placement and
 * cycles through candidate sides before reporting an unsupported state.
 */
public final class StatePlacementAdvisor {
    private static final Set<String> SUPPORTED_PROPERTIES = Set.of(
            "axis", "facing", "horizontal_facing", "half", "type", "rotation", "hinge",
            "waterlogged", "open", "powered", "lit", "shape", "north", "south", "east", "west",
            "up", "down", "in_wall", "snowy", "conditional", "extended", "disarmed",
            "note", "instrument", "age", "stage", "moisture", "level", "distance", "persistent",
            "drag", "attachment", "orientation", "vertical_direction", "thickness", "tilt"
    );

    private StatePlacementAdvisor() {
    }

    public static boolean isDefaultState(BlockState state) {
        return state != null && state.equals(state.getBlock().getDefaultState());
    }

    public static boolean isSupported(BlockState state, boolean stateAware) {
        if (state == null) return false;
        if (isDefaultState(state)) return true;
        if (!stateAware) return false;
        Map<Property<?>, Comparable<?>> entries = state.getEntries();
        Map<Property<?>, Comparable<?>> defaults = state.getBlock().getDefaultState().getEntries();
        for (Map.Entry<Property<?>, Comparable<?>> entry : entries.entrySet()) {
            String name = entry.getKey().getName();
            if (SUPPORTED_PROPERTIES.contains(name)) continue;
            if (!entry.getValue().equals(defaults.get(entry.getKey()))) return false;
        }
        return true;
    }

    public static Direction sideFor(BlockState state, int attempt) {
        List<Direction> sides = candidateSides(state);
        if (sides.isEmpty()) return null;
        int index = Math.floorMod(attempt, sides.size() + 1);
        if (index == 0) return null; // first attempt: let PlacementManager choose its natural support.
        return sides.get(index - 1);
    }

    public static List<Direction> candidateSides(BlockState state) {
        List<Direction> sides = new ArrayList<>();
        if (state == null) return sides;

        if (state.contains(Properties.AXIS)) {
            switch (state.get(Properties.AXIS)) {
                case X -> sides.add(Direction.EAST);
                case Y -> sides.add(Direction.UP);
                case Z -> sides.add(Direction.SOUTH);
            }
        }
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            sides.add(facing.getOpposite());
            sides.add(facing);
        } else if (state.contains(Properties.FACING)) {
            Direction facing = state.get(Properties.FACING);
            sides.add(facing.getOpposite());
            sides.add(facing);
        }
        if (state.contains(Properties.SLAB_TYPE) && state.get(Properties.SLAB_TYPE).name().equals("TOP")) {
            sides.add(Direction.DOWN);
        }
        if (state.contains(Properties.BLOCK_HALF) && state.get(Properties.BLOCK_HALF).name().equals("TOP")) {
            sides.add(Direction.DOWN);
        }
        if (sides.isEmpty()) {
            sides.add(Direction.UP);
            sides.add(Direction.NORTH);
            sides.add(Direction.SOUTH);
            sides.add(Direction.EAST);
            sides.add(Direction.WEST);
        }
        return sides;
    }
}
