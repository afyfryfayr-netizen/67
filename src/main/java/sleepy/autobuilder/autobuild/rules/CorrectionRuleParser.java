package sleepy.autobuilder.autobuild.rules;

import sleepy.autobuilder.autobuild.core.BlockCategory;
import sleepy.autobuilder.autobuild.core.BlockKey;
import sleepy.autobuilder.autobuild.core.MismatchAction;
import sleepy.autobuilder.autobuild.core.PositionCodec;
import sleepy.autobuilder.autobuild.core.PropertyMode;
import sleepy.autobuilder.autobuild.core.ScopeRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsers for the correction-rule settings. Parsing is deliberately tolerant: one malformed entry
 * must not disable an entire live build.
 */
public final class CorrectionRuleParser {
    public record ScopedRule(String key, ScopeRules rules) {
    }

    public record BlockPropertyRule(String blockId, String property, PropertyMode mode) {
    }

    private CorrectionRuleParser() {
    }

    /**
     * Example: {@code type=REPLACE,state=CORRECT,extra=REMOVE,ignore=waterlogged|facing,require=facing}
     */
    public static ScopeRules parseScope(String spec) {
        MismatchAction type = null;
        MismatchAction state = null;
        MismatchAction extra = null;
        Set<String> ignored = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        if (spec == null) return ScopeRules.empty();

        for (String part : spec.split(",")) {
            if (part == null || part.isBlank()) continue;
            int split = part.indexOf('=');
            if (split <= 0 || split >= part.length() - 1) continue;
            String key = part.substring(0, split).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(split + 1).trim();
            switch (key) {
                case "type" -> type = parseType(value);
                case "state" -> state = parseState(value);
                case "extra" -> extra = parseExtra(value);
                case "ignore", "ignored" -> splitNames(value, ignored);
                case "require", "required" -> splitNames(value, required);
                default -> {
                }
            }
        }
        ignored.removeAll(required);
        return new ScopeRules(type, state, extra, ignored, required);
    }

    /** Entries are {@code key|scope-spec}; the first pipe separates the key. */
    public static List<ScopedRule> parseScopedRules(List<String> entries) {
        List<ScopedRule> result = new ArrayList<>();
        if (entries == null) return result;
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            int split = raw.indexOf('|');
            if (split <= 0) continue;
            String key = raw.substring(0, split).trim();
            String spec = raw.substring(split + 1).trim();
            if (key.isEmpty() || spec.isEmpty()) continue;
            ScopeRules rules = parseScope(spec);
            if (!rules.isEmpty()) result.add(new ScopedRule(key, rules));
        }
        return result;
    }

    /** Entries are {@code blockId|property=IGNORE,other=REQUIRED}. */
    public static List<BlockPropertyRule> parseBlockPropertyRules(List<String> entries) {
        List<BlockPropertyRule> result = new ArrayList<>();
        if (entries == null) return result;
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            int split = raw.indexOf('|');
            if (split <= 0) continue;
            String block = BlockKey.normalise(raw.substring(0, split).trim());
            String rest = raw.substring(split + 1);
            for (String part : rest.split(",")) {
                int equals = part.indexOf('=');
                if (equals <= 0) continue;
                String property = part.substring(0, equals).trim();
                String modeText = part.substring(equals + 1).trim().toUpperCase(Locale.ROOT);
                PropertyMode mode;
                try {
                    mode = modeText.equals("REQUIRED")
                            ? PropertyMode.CORRECT
                            : PropertyMode.valueOf(modeText);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!property.isEmpty()) result.add(new BlockPropertyRule(block, property, mode));
            }
        }
        return result;
    }

    /**
     * Entries are {@code x,y,z} or {@code x,y,z,dimension}. Positions from other dimensions are
     * skipped because the core store has no dimension in its packed position key.
     */
    public static Set<Long> parseProtectedPositions(List<String> entries, String currentDimension) {
        Set<Long> result = new LinkedHashSet<>();
        if (entries == null) return result;
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            String[] parts = raw.trim().split("\\s*,\\s*");
            if (parts.length < 3) continue;
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (parts.length >= 4 && currentDimension != null && !parts[3].isBlank()
                        && !parts[3].trim().equals(currentDimension)) continue;
                result.add(PositionCodec.pack(x, y, z));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    /** Parses layer keys into integers, ignoring anything invalid. */
    public static Map<Integer, ScopeRules> parseLayerRules(List<String> entries) {
        Map<Integer, ScopeRules> result = new LinkedHashMap<>();
        for (ScopedRule rule : parseScopedRules(entries)) {
            try {
                result.put(Integer.parseInt(rule.key()), rule.rules());
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    public static Map<String, ScopeRules> parseSchematicRules(List<String> entries) {
        Map<String, ScopeRules> result = new LinkedHashMap<>();
        for (ScopedRule rule : parseScopedRules(entries)) {
            result.put(rule.key(), rule.rules());
        }
        return result;
    }

    public static Map<String, ScopeRules> parseBlockRules(List<String> entries) {
        Map<String, ScopeRules> result = new LinkedHashMap<>();
        for (ScopedRule rule : parseScopedRules(entries)) {
            result.put(BlockKey.normalise(rule.key()), rule.rules());
        }
        return result;
    }

    public static Map<BlockCategory, ScopeRules> parseCategoryRules(List<String> entries) {
        Map<BlockCategory, ScopeRules> result = new LinkedHashMap<>();
        for (ScopedRule rule : parseScopedRules(entries)) {
            try {
                result.put(BlockCategory.valueOf(rule.key().trim().toUpperCase(Locale.ROOT)), rule.rules());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    private static void splitNames(String value, Set<String> target) {
        for (String name : value.split("[|;]")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }

    private static MismatchAction parseType(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "REPLACE" -> MismatchAction.REPLACE;
            case "IGNORE" -> MismatchAction.IGNORE;
            case "REPORT" -> MismatchAction.REPORT;
            default -> null;
        };
    }

    private static MismatchAction parseState(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CORRECT" -> MismatchAction.CORRECT;
            case "IGNORE" -> MismatchAction.IGNORE;
            case "REPORT" -> MismatchAction.REPORT;
            default -> null;
        };
    }

    private static MismatchAction parseExtra(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "REMOVE" -> MismatchAction.REMOVE;
            case "IGNORE" -> MismatchAction.IGNORE;
            case "REPORT" -> MismatchAction.REPORT;
            default -> null;
        };
    }
}
