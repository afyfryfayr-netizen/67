package sleepy.autobuilder.autobuild.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure material arithmetic. The engine supplies requirements after scanning the world; ignored or
 * already-correct positions are intentionally not included in {@code required}.
 */
public final class MaterialPlanner {
    private MaterialPlanner() {
    }

    public static MaterialPlan plan(Map<String, Integer> required,
                                    Map<String, Integer> loose,
                                    Map<String, Integer> inShulkers,
                                    Map<String, Integer> reserved,
                                    Collection<String> priority) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(required.keySet());
        ids.addAll(loose.keySet());
        ids.addAll(inShulkers.keySet());
        ids.addAll(reserved.keySet());

        List<String> ordered = new ArrayList<>(ids);
        if (priority != null && !priority.isEmpty()) {
            Map<String, Integer> rank = new LinkedHashMap<>();
            int i = 0;
            for (String id : priority) rank.putIfAbsent(BlockKey.normalise(id), i++);
            ordered.sort(Comparator
                    .comparingInt((String id) -> rank.getOrDefault(id, Integer.MAX_VALUE))
                    .thenComparing(String::compareTo));
        } else {
            ordered.sort(String::compareTo);
        }

        Map<String, MaterialLine> lines = new LinkedHashMap<>();
        int totalRequired = 0;
        int totalAvailable = 0;
        int totalRemaining = 0;
        for (String id : ordered) {
            int req = Math.max(0, value(required, id));
            int looseCount = Math.max(0, value(loose, id));
            int shulkerCount = Math.max(0, value(inShulkers, id));
            int reservedCount = Math.max(0, value(reserved, id));
            int available = Math.max(0, looseCount + shulkerCount - reservedCount);
            int remaining = Math.max(0, req - available);
            MaterialLine line = new MaterialLine(id, req, looseCount, shulkerCount, reservedCount, remaining);
            lines.put(id, line);
            totalRequired += req;
            totalAvailable += available;
            totalRemaining += remaining;
        }
        return new MaterialPlan(lines, totalRequired, totalAvailable, totalRemaining);
    }

    private static int value(Map<String, Integer> map, String id) {
        Integer value = map.get(id);
        if (value != null) return value;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (BlockKey.normalise(entry.getKey()).equals(id)) return entry.getValue();
        }
        return 0;
    }

    public record MaterialLine(String itemId, int required, int looseAvailable, int inShulkers, int reserved, int remaining) {
        public int available() {
            return Math.max(0, looseAvailable + inShulkers - reserved);
        }

        public boolean satisfied() {
            return remaining <= 0;
        }
    }

    public record MaterialPlan(Map<String, MaterialLine> lines, int totalRequired, int totalAvailable, int totalRemaining) {
        public boolean complete() {
            return totalRemaining <= 0;
        }

        public List<MaterialLine> missingLines() {
            List<MaterialLine> missing = new ArrayList<>();
            for (MaterialLine line : lines.values()) if (!line.satisfied()) missing.add(line);
            return missing;
        }

        public Set<String> missingItemIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (MaterialLine line : lines.values()) if (!line.satisfied()) ids.add(line.itemId());
            return ids;
        }
    }
}
