package sleepy.autobuilder.autobuild.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A block id plus all of its block-state properties.
 */
public record StateSignature(BlockKey block, Map<String, String> properties) {
    public StateSignature {
        Objects.requireNonNull(block, "block");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties == null ? Map.of() : properties));
    }

    public static StateSignature of(String blockId) {
        return new StateSignature(BlockKey.of(blockId), Map.of());
    }

    public static StateSignature of(String blockId, Map<String, String> properties) {
        return new StateSignature(BlockKey.of(blockId), properties);
    }

    public boolean isAir() {
        return block.isAir();
    }

    public boolean sameProperties(StateSignature other) {
        return properties.equals(other.properties);
    }

    /**
     * All properties whose value differs, including properties present on only one side.
     */
    public Set<String> differingProperties(StateSignature other) {
        Set<String> differing = new TreeSet<>();
        Set<String> names = new LinkedHashSet<>(properties.keySet());
        names.addAll(other.properties.keySet());
        for (String name : names) {
            if (!Objects.equals(properties.get(name), other.properties.get(name))) differing.add(name);
        }
        return differing;
    }

    public String property(String name) {
        return properties.get(name);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return block.id();
        return block.id() + properties;
    }
}
