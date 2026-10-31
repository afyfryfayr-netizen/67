package sleepy.autobuilder.autobuild.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable progress storage. The on-disk format is JSON; position sets are Base64-encoded packed longs
 * so large coordinates do not lose precision through JSON number conversion.
 */
public final class ProgressStore {
    public static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private StoreData data = new StoreData();
    private boolean dirty;

    public ProgressStore(Path file) {
        this.file = file;
    }

    public synchronized SessionProgress session(String key) {
        for (SessionProgress session : data.sessions) {
            if (session.key.equals(key)) return session;
        }
        SessionProgress session = new SessionProgress();
        session.key = key;
        data.sessions.add(session);
        dirty = true;
        return session;
    }

    public synchronized SessionProgress find(String key) {
        for (SessionProgress session : data.sessions) {
            if (session.key.equals(key)) return session;
        }
        return null;
    }

    /**
     * Finds the newest saved session for the same schematic/origin/dimension whose saved box still
     * overlaps the current region. Used when a clamped layer setting changes the session key.
     */
    public synchronized SessionProgress findCompatible(String schematicName, String dimension,
                                                        int originX, int originY, int originZ,
                                                        Box3i region) {
        SessionProgress best = null;
        for (SessionProgress session : data.sessions) {
            if (session.key.isEmpty() || session.pending.isEmpty()) continue;
            if (schematicName != null && !schematicName.isEmpty()
                    && !session.schematicName.isEmpty()
                    && !session.schematicName.equals(schematicName)) {
                continue;
            }
            if (dimension != null && !dimension.isEmpty() && !session.dimension.isEmpty()
                    && !session.dimension.equals(dimension)) {
                continue;
            }
            if (session.originX != originX || session.originY != originY || session.originZ != originZ) {
                continue;
            }
            if (!overlaps(session, region)) continue;
            if (best == null || session.updatedAt > best.updatedAt) best = session;
        }
        return best;
    }

    /** Re-keys an adopted session, replacing any empty session that was created for the new key. */
    public synchronized SessionProgress adopt(SessionProgress source, String key) {
        if (source == null) return session(key);
        for (SessionProgress existing : data.sessions) {
            if (existing != source && existing.key.equals(key)) {
                data.sessions.remove(existing);
                break;
            }
        }
        source.key = key;
        dirty = true;
        return source;
    }

    private static boolean overlaps(SessionProgress session, Box3i region) {
        if (region == null) return false;
        try {
            Box3i saved = new Box3i(session.minX, session.minY, session.minZ,
                    session.maxX, session.maxY, session.maxZ);
            return saved.minX() <= region.maxX() && saved.maxX() >= region.minX()
                    && saved.minY() <= region.maxY() && saved.maxY() >= region.minY()
                    && saved.minZ() <= region.maxZ() && saved.maxZ() >= region.minZ();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public synchronized void remove(String key) {
        if (data.sessions.removeIf(session -> session.key.equals(key))) dirty = true;
    }

    public synchronized List<SessionProgress> sessions() {
        return List.copyOf(data.sessions);
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void save() throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        DiskData out = new DiskData();
        out.version = FORMAT_VERSION;
        for (SessionProgress session : data.sessions) out.sessions.add(SerializedSession.from(session));
        String json = gson.toJson(out);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    public synchronized void load() throws IOException {
        data = new StoreData();
        if (!Files.exists(file)) {
            dirty = false;
            return;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            DiskData loaded = gson.fromJson(json, DiskData.class);
            if (loaded == null) throw new JsonParseException("empty progress file");
            if (loaded.sessions == null) loaded.sessions = new ArrayList<>();
            for (SerializedSession serialized : loaded.sessions) data.sessions.add(serialized.toSession());
        } catch (RuntimeException e) {
            // Preserve the bad file for inspection instead of silently destroying it.
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            throw new IOException("Could not parse progress file; moved to " + backup.getFileName(), e);
        }
        dirty = false;
    }

    public Path file() {
        return file;
    }

    public static final class SessionProgress {
        public String key = "";
        public String schematicName = "";
        public String dimension = "";
        public String mode = "";
        public int originX, originY, originZ;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public int layerStart, layerEnd, currentLayer;
        public int totalCompleted, totalIgnored, totalFailed;
        public int resumeX, resumeY, resumeZ;
        public boolean hasResume;
        public int anchorX, anchorY, anchorZ;
        public boolean hasAnchor;
        public long updatedAt;
        public final Set<Long> completed = new LinkedHashSet<>();
        public final Set<Long> ignored = new LinkedHashSet<>();
        public final Set<Long> pending = new LinkedHashSet<>();
        public final Set<Long> failed = new LinkedHashSet<>();

        public void markCompleted(long position) {
            completed.add(position);
            pending.remove(position);
            failed.remove(position);
            updatedAt = System.currentTimeMillis();
        }

        public void markIgnored(long position) {
            ignored.add(position);
            pending.remove(position);
            updatedAt = System.currentTimeMillis();
        }

        public void markPending(long position) {
            pending.remove(position);
            if (!completed.contains(position) && !ignored.contains(position)) pending.add(position);
            updatedAt = System.currentTimeMillis();
        }

        public void markFailed(long position) {
            failed.add(position);
            pending.remove(position);
            updatedAt = System.currentTimeMillis();
        }

        public void clearPosition(long position) {
            completed.remove(position);
            ignored.remove(position);
            pending.remove(position);
            failed.remove(position);
            updatedAt = System.currentTimeMillis();
        }

        public boolean isCompleted(long position) {
            return completed.contains(position);
        }

        public boolean isIgnored(long position) {
            return ignored.contains(position);
        }

        public int progressPercent() {
            int actioned = completed.size() + ignored.size() + failed.size();
            int total = actioned + pending.size();
            if (total <= 0) return 0;
            return (int) Math.round(Math.min(100.0, actioned * 100.0 / total));
        }
    }

    /** In-memory store data. Never serialised directly; {@link DiskData} is the on-disk shape. */
    private static final class StoreData {
        int version = FORMAT_VERSION;
        final List<SessionProgress> sessions = new ArrayList<>();
    }

    /** On-disk representation with Base64 position payloads and no Minecraft types. */
    private static final class DiskData {
        int version = FORMAT_VERSION;
        List<SerializedSession> sessions = new ArrayList<>();
    }

    private static final class SerializedSession {
        String key;
        String schematicName;
        String dimension;
        String mode;
        int originX, originY, originZ;
        int minX, minY, minZ, maxX, maxY, maxZ;
        int layerStart, layerEnd, currentLayer;
        int totalCompleted, totalIgnored, totalFailed;
        int resumeX, resumeY, resumeZ;
        boolean hasResume;
        int anchorX, anchorY, anchorZ;
        boolean hasAnchor;
        long updatedAt;
        String completed = "";
        String ignored = "";
        String pending = "";
        String failed = "";

        static SerializedSession from(SessionProgress session) {
            SerializedSession out = new SerializedSession();
            out.key = session.key;
            out.schematicName = session.schematicName;
            out.dimension = session.dimension;
            out.mode = session.mode;
            out.originX = session.originX;
            out.originY = session.originY;
            out.originZ = session.originZ;
            out.minX = session.minX;
            out.minY = session.minY;
            out.minZ = session.minZ;
            out.maxX = session.maxX;
            out.maxY = session.maxY;
            out.maxZ = session.maxZ;
            out.layerStart = session.layerStart;
            out.layerEnd = session.layerEnd;
            out.currentLayer = session.currentLayer;
            out.totalCompleted = session.totalCompleted;
            out.totalIgnored = session.totalIgnored;
            out.totalFailed = session.totalFailed;
            out.resumeX = session.resumeX;
            out.resumeY = session.resumeY;
            out.resumeZ = session.resumeZ;
            out.hasResume = session.hasResume;
            out.anchorX = session.anchorX;
            out.anchorY = session.anchorY;
            out.anchorZ = session.anchorZ;
            out.hasAnchor = session.hasAnchor;
            out.updatedAt = session.updatedAt;
            out.completed = PositionCodec.encode(session.completed);
            out.ignored = PositionCodec.encode(session.ignored);
            out.pending = PositionCodec.encode(session.pending);
            out.failed = PositionCodec.encode(session.failed);
            return out;
        }

        SessionProgress toSession() {
            SessionProgress out = new SessionProgress();
            out.key = key == null ? "" : key;
            out.schematicName = schematicName == null ? "" : schematicName;
            out.dimension = dimension == null ? "" : dimension;
            out.mode = mode == null ? "" : mode;
            out.originX = originX;
            out.originY = originY;
            out.originZ = originZ;
            out.minX = minX;
            out.minY = minY;
            out.minZ = minZ;
            out.maxX = maxX;
            out.maxY = maxY;
            out.maxZ = maxZ;
            out.layerStart = layerStart;
            out.layerEnd = layerEnd;
            out.currentLayer = currentLayer;
            out.totalCompleted = totalCompleted;
            out.totalIgnored = totalIgnored;
            out.totalFailed = totalFailed;
            out.resumeX = resumeX;
            out.resumeY = resumeY;
            out.resumeZ = resumeZ;
            out.hasResume = hasResume;
            out.anchorX = anchorX;
            out.anchorY = anchorY;
            out.anchorZ = anchorZ;
            out.hasAnchor = hasAnchor;
            out.updatedAt = updatedAt;
            try {
                out.completed.addAll(PositionCodec.decode(completed));
                out.ignored.addAll(PositionCodec.decode(ignored));
                out.pending.addAll(PositionCodec.decode(pending));
                out.failed.addAll(PositionCodec.decode(failed));
            } catch (IllegalArgumentException e) {
                throw new JsonParseException("Invalid position payload", e);
            }
            return out;
        }
    }
}
