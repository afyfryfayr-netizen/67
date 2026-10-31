package sleepy.autobuilder.autobuild.core;

import java.util.Locale;

/**
 * Coarse block categories used for category-level configuration rules.
 * The mapping is deliberately deterministic and based only on the registry id.
 */
public enum BlockCategory {
    SHULKER,
    CONTAINER,
    STAIRS,
    SLAB,
    DOOR,
    TRAPDOOR,
    FENCE_GATE,
    FENCE,
    WALL,
    LOG,
    LEAVES,
    PLANT,
    RAIL,
    REDSTONE,
    LIGHT,
    GLASS,
    WOOL,
    CONCRETE,
    FLUID,
    FULL_BLOCK,
    OTHER;

    public static BlockCategory fromId(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;

        if (path.contains("shulker_box")) return SHULKER;
        if (path.endsWith("chest") || path.endsWith("barrel") || path.endsWith("hopper")
                || path.endsWith("furnace") || path.endsWith("smoker") || path.endsWith("blast_furnace")
                || path.endsWith("dispenser") || path.endsWith("dropper") || path.endsWith("brewing_stand")
                || path.endsWith("crafter") || path.endsWith("decorated_pot")) return CONTAINER;

        if (path.endsWith("_stairs")) return STAIRS;
        if (path.endsWith("_slab")) return SLAB;
        if (path.endsWith("_trapdoor")) return TRAPDOOR;
        if (path.endsWith("_door")) return DOOR;
        if (path.endsWith("_fence_gate")) return FENCE_GATE;
        if (path.endsWith("_fence")) return FENCE;
        if (path.endsWith("_wall")) return WALL;
        if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                || path.endsWith("_hyphae") || path.equals("bamboo_block")) return LOG;
        if (path.endsWith("_leaves")) return LEAVES;
        if (path.endsWith("_rail")) return RAIL;

        if (path.contains("redstone") || path.contains("repeater") || path.contains("comparator")
                || path.contains("observer") || path.contains("piston") || path.contains("dispenser")
                || path.contains("dropper") || path.contains("target") || path.contains("lever")
                || path.contains("button") || path.contains("pressure_plate")) return REDSTONE;

        if (path.contains("torch") || path.contains("lantern") || path.contains("lamp")
                || path.contains("glowstone") || path.contains("shroomlight")
                || path.contains("campfire") || path.contains("candle")) return LIGHT;

        if (path.contains("glass")) return GLASS;
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return WOOL;
        if (path.contains("concrete")) return CONCRETE;

        if (path.equals("water") || path.equals("lava")) return FLUID;

        if (path.contains("sapling") || path.contains("flower") || path.contains("tulip")
                || path.contains("grass") || path.contains("fern") || path.contains("mushroom")
                || path.contains("wheat") || path.contains("carrots") || path.contains("potatoes")
                || path.contains("beetroots") || path.contains("vine") || path.contains("lily")
                || path.contains("cactus") || path.contains("bamboo") || path.contains("sugar_cane")
                || path.contains("kelp") || path.contains("seagrass")) return PLANT;

        return FULL_BLOCK;
    }

    public static BlockCategory from(BlockKey key) {
        return fromId(key.id());
    }
}
