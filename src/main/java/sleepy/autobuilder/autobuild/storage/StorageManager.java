package sleepy.autobuilder.autobuild.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import sleepy.autobuilder.SevenBuilderAddon;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, independent storage waypoints. Entries are written to
 * {@code meteor-client/seven-builder/storage.json}. The manager never shares state with Meteor's
 * own waypoint system and never requires another mod to create a stash location.
 */
public final class StorageManager {
    private static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final StorageManager INSTANCE = new StorageManager();

    private final Path file = MeteorClient.FOLDER.toPath()
            .resolve("seven-builder").resolve("storage.json");
    private final List<StorageEntry> entries = new ArrayList<>();
    private boolean loaded;

    private StorageManager() {
    }

    public static StorageManager get() {
        return INSTANCE;
    }

    public Path file() {
        return file;
    }

    public synchronized List<StorageEntry> list() {
        ensureLoaded();
        List<StorageEntry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingInt((StorageEntry e) -> e.priority).reversed()
                .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return copy;
    }

    public synchronized List<StorageEntry> enabledForRestock(String currentDimension) {
        ensureLoaded();
        List<StorageEntry> result = new ArrayList<>();
        for (StorageEntry entry : entries) {
            if (!entry.enabled) continue;
            if (!entry.dimensionMatches(currentDimension)) continue;
            result.add(entry);
        }
        result.sort(Comparator.comparingInt((StorageEntry e) -> e.priority).reversed()
                .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized StorageEntry get(String id) {
        ensureLoaded();
        if (id == null) return null;
        for (StorageEntry entry : entries) {
            if (id.equals(entry.id)) return entry;
        }
        return null;
    }

    public synchronized boolean add(StorageEntry entry) {
        ensureLoaded();
        if (entry == null) return false;
        sanitise(entry);
        if (entry.id.isBlank() || get(entry.id) != null) entry.id = UUID.randomUUID().toString();
        entries.add(entry);
        save();
        return true;
    }

    public synchronized boolean update(StorageEntry entry) {
        ensureLoaded();
        if (entry == null || entry.id == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            if (entry.id.equals(entries.get(i).id)) {
                sanitise(entry);
                entries.set(i, entry);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean remove(String id) {
        ensureLoaded();
        boolean removed = entries.removeIf(entry -> entry.id.equals(id));
        if (removed) save();
        return removed;
    }

    public synchronized void clear() {
        ensureLoaded();
        entries.clear();
        save();
    }

    public synchronized boolean movePriority(String id, int delta) {
        StorageEntry entry = get(id);
        if (entry == null || delta == 0) return false;
        entry.priority += delta;
        save();
        return true;
    }

    public synchronized String nextName(String prefix) {
        ensureLoaded();
        String base = prefix == null || prefix.isBlank() ? "Storage" : prefix;
        int number = 1;
        while (hasName(base + " " + number)) number++;
        return base + " " + number;
    }

    private boolean hasName(String name) {
        for (StorageEntry entry : entries) {
            if (entry.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public synchronized void markVisited(String id) {
        StorageEntry entry = get(id);
        if (entry == null) return;
        entry.visited = true;
        entry.lastVisitedEpochMs = System.currentTimeMillis();
        save();
    }

    public synchronized void recordSearch(String id, Map<String, Integer> found, String status) {
        StorageEntry entry = get(id);
        if (entry == null) return;
        entry.visited = true;
        if (entry.lastVisitedEpochMs <= 0) entry.lastVisitedEpochMs = System.currentTimeMillis();
        entry.lastSearchedEpochMs = System.currentTimeMillis();
        entry.lastFound = new LinkedHashMap<>();
        if (found != null) {
            found.forEach((key, value) -> {
                if (key != null && value != null && value > 0) entry.lastFound.put(key, value);
            });
        }
        entry.lastStatus = status == null ? "" : status;
        save();
    }

    public synchronized void recordFailure(String id, String status) {
        StorageEntry entry = get(id);
        if (entry == null) return;
        entry.lastStatus = status == null ? "" : status;
        save();
    }

    public synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            FileData data = new FileData();
            data.format = FORMAT;
            data.entries = new ArrayList<>(entries);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            SevenBuilderAddon.LOG.warn("Could not save storage waypoints to {}", file, e);
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            FileData data = GSON.fromJson(reader, FileData.class);
            if (data == null || data.entries == null) return;
            for (StorageEntry entry : data.entries) {
                if (entry == null) continue;
                sanitise(entry);
                boolean duplicate = false;
                for (StorageEntry existing : entries) {
                    if (existing.id.equals(entry.id)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) entries.add(entry);
            }
        } catch (IOException | RuntimeException e) {
            SevenBuilderAddon.LOG.warn("Could not read storage waypoints from {}", file, e);
        }
    }

    private static void sanitise(StorageEntry entry) {
        if (entry.id == null || entry.id.isBlank()) entry.id = UUID.randomUUID().toString();
        if (entry.name == null || entry.name.isBlank()) entry.name = "Storage";
        entry.name = entry.name.trim();
        if (entry.name.length() > 64) entry.name = entry.name.substring(0, 64);
        if (entry.dimension == null) entry.dimension = "";
        entry.dimension = entry.dimension.trim().toLowerCase(Locale.ROOT);
        if (!entry.dimension.isEmpty() && !entry.dimension.contains(":")) {
            entry.dimension = "minecraft:" + entry.dimension;
        }
        if (entry.type == null) entry.type = StorageType.UNKNOWN;
        if (!entry.type.isContainer()) entry.exactContainer = false;
        if (entry.lastFound == null) entry.lastFound = new LinkedHashMap<>();
        if (entry.lastStatus == null) entry.lastStatus = "";
        entry.priority = Math.max(-999, Math.min(999, entry.priority));
    }

    private static final class FileData {
        int format = FORMAT;
        List<StorageEntry> entries = new ArrayList<>();
    }
}
