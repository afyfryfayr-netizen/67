package sleepy.autobuilder.autobuild.rules;

import sleepy.autobuilder.autobuild.PlacementMethod;
import sleepy.autobuilder.autobuild.core.BlockCategory;
import sleepy.autobuilder.autobuild.core.BlockKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses per-block placement method rules.
 *
 * <p>Accepted forms: {@code minecraft:torch=VANILLA}, {@code #stairs=VANILLA},
 * {@code category:stairs=VANILLA}, and {@code *=AUTO}. A block-specific rule wins over a category
 * rule, which wins over the wildcard rule, which wins over the module default.</p>
 */
public final class PlacementRules {
    private final Map<String, PlacementMethod> blocks = new LinkedHashMap<>();
    private final Map<BlockCategory, PlacementMethod> categories = new LinkedHashMap<>();
    private PlacementMethod wildcard;

    private PlacementRules() {
    }

    public static PlacementRules parse(List<String> entries) {
        PlacementRules rules = new PlacementRules();
        if (entries == null) return rules;
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            int split = raw.indexOf('=');
            if (split <= 0 || split >= raw.length() - 1) continue;
            String selector = raw.substring(0, split).trim();
            String value = raw.substring(split + 1).trim().toLowerCase(Locale.ROOT);
            PlacementMethod method;
            try {
                method = PlacementMethod.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (selector.equals("*")) {
                rules.wildcard = method;
            } else if (selector.startsWith("#")) {
                category(rules, selector.substring(1), method);
            } else if (selector.toLowerCase(Locale.ROOT).startsWith("category:")) {
                category(rules, selector.substring("category:".length()), method);
            } else {
                rules.blocks.put(BlockKey.normalise(selector), method);
            }
        }
        return rules;
    }

    private static void category(PlacementRules rules, String name, PlacementMethod method) {
        try {
            rules.categories.put(BlockCategory.valueOf(name.trim().toUpperCase(Locale.ROOT)), method);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public PlacementMethod resolve(String blockId, BlockCategory category, PlacementMethod fallback) {
        String id = blockId == null ? "" : BlockKey.normalise(blockId);
        PlacementMethod method = blocks.get(id);
        if (method != null) return method;
        method = categories.get(category);
        if (method != null) return method;
        return wildcard != null ? wildcard : fallback;
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && categories.isEmpty() && wildcard == null;
    }
}
