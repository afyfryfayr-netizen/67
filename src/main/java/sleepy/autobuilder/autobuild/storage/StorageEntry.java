package sleepy.autobuilder.autobuild.storage;

import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One independent storage waypoint. The UUID is stable across restarts and is the identity used by
 * the restock service and the GUI; it is never derived from the display name or coordinates.
 */
public final class StorageEntry {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public int x;
    public int y;
    public int z;
    public String dimension = "";
    public StorageType type = StorageType.UNKNOWN;
    /** When true, the restock service opens this exact block instead of searching around the waypoint. */
    public boolean exactContainer = true;
    public boolean enabled = true;
    public int priority;
    public boolean visited;
    public long lastVisitedEpochMs;
    public long lastSearchedEpochMs;
    public Map<String, Integer> lastFound = new LinkedHashMap<>();
    public String lastStatus = "";

    public StorageEntry() {
    }

    public StorageEntry(String name, BlockPos pos, String dimension, StorageType type) {
        this.name = name == null ? "" : name;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.dimension = dimension == null ? "" : dimension;
        this.type = type == null ? StorageType.UNKNOWN : type;
        this.exactContainer = this.type.isContainer();
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public String coords() {
        return x + ", " + y + ", " + z;
    }

    public String shortDimension() {
        if (dimension == null || dimension.isBlank()) return "any";
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }

    public boolean dimensionMatches(String current) {
        if (dimension == null || dimension.isBlank()) return true;
        return dimension.equalsIgnoreCase(current);
    }

    public String statusText() {
        if (!visited && lastVisitedEpochMs <= 0 && lastSearchedEpochMs <= 0 && lastStatus.isBlank()) {
            return "Not visited";
        }

        StringBuilder out = new StringBuilder();
        if (lastVisitedEpochMs > 0) {
            out.append("Visited ").append(TIME.format(Instant.ofEpochMilli(lastVisitedEpochMs)));
        } else {
            out.append("Not visited");
        }
        if (lastSearchedEpochMs > 0) {
            out.append(" | searched ").append(TIME.format(Instant.ofEpochMilli(lastSearchedEpochMs)));
        }
        if (!lastFound.isEmpty()) {
            out.append(" | found ");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : lastFound.entrySet()) {
                if (shown++ > 0) out.append(", ");
                out.append(shortId(entry.getKey())).append(" x").append(entry.getValue());
                if (shown >= 5) {
                    if (lastFound.size() > shown) out.append(", +").append(lastFound.size() - shown).append(" more");
                    break;
                }
            }
        }
        if (!lastStatus.isBlank() && !"found materials".equals(lastStatus)) {
            out.append(" | ").append(lastStatus);
        }
        return out.toString();
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
