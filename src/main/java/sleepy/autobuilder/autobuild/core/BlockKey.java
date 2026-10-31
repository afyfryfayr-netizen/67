package sleepy.autobuilder.autobuild.core;

import java.util.Locale;
import java.util.Set;

/**
 * Normalised block identifier independent of Minecraft classes.
 */
public record BlockKey(String id) implements Comparable<BlockKey> {
    private static final Set<String> AIR_IDS = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
    );

    public BlockKey {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("block id is blank");
        id = normalise(id);
    }

    public static BlockKey of(String id) {
        return new BlockKey(id);
    }

    public static String normalise(String id) {
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) value = "minecraft:" + value;
        return value;
    }

    public boolean isAir() {
        return AIR_IDS.contains(id);
    }

    @Override
    public int compareTo(BlockKey o) {
        return id.compareTo(o.id);
    }
}
