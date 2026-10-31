package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreCompatibilityTest {
    @TempDir
    Path tempDir;

    private ProgressStore.SessionProgress session(ProgressStore store, String key, String name,
                                                   String dimension, long updatedAt) {
        ProgressStore.SessionProgress session = store.session(key);
        session.schematicName = name;
        session.dimension = dimension;
        session.originX = 100;
        session.originY = 64;
        session.originZ = -50;
        session.minX = 100;
        session.minY = 60;
        session.minZ = -50;
        session.maxX = 120;
        session.maxY = 70;
        session.maxZ = -30;
        session.markPending(PositionCodec.pack(105, 65, -45));
        session.updatedAt = updatedAt;
        return session;
    }

    @Test
    void findsNewestCompatibleSessionWhenTheKeyChanges() {
        ProgressStore store = new ProgressStore(tempDir.resolve("progress.json"));
        session(store, "old", "castle", "minecraft:overworld", 10L);
        ProgressStore.SessionProgress newest = session(store, "newer", "castle",
                "minecraft:overworld", 20L);

        ProgressStore.SessionProgress found = store.findCompatible("castle",
                "minecraft:overworld", 100, 64, -50, Box3i.of(110, 62, -40, 130, 72, -20));

        assertSame(newest, found);
    }

    @Test
    void rejectsWrongSchematicDimensionOriginOrNonOverlappingBox() {
        ProgressStore store = new ProgressStore(tempDir.resolve("progress.json"));
        session(store, "old", "castle", "minecraft:overworld", 10L);
        Box3i overlapping = Box3i.of(110, 62, -40, 130, 72, -20);

        assertNull(store.findCompatible("tower", "minecraft:overworld", 100, 64, -50, overlapping));
        assertNull(store.findCompatible("castle", "minecraft:the_nether", 100, 64, -50, overlapping));
        assertNull(store.findCompatible("castle", "minecraft:overworld", 0, 64, -50, overlapping));
        assertNull(store.findCompatible("castle", "minecraft:overworld", 100, 64, -50,
                Box3i.of(1000, 60, 1000, 1010, 70, 1010)));
    }

    @Test
    void ignoresSessionsWithNoPendingWork() {
        ProgressStore store = new ProgressStore(tempDir.resolve("progress.json"));
        ProgressStore.SessionProgress empty = session(store, "empty", "castle",
                "minecraft:overworld", 10L);
        empty.pending.clear();

        assertNull(store.findCompatible("castle", "minecraft:overworld", 100, 64, -50,
                Box3i.of(110, 62, -40, 130, 72, -20)));
    }

    @Test
    void adoptReKeysTheSessionAndRemovesAnEmptyPlaceholder() {
        ProgressStore store = new ProgressStore(tempDir.resolve("progress.json"));
        ProgressStore.SessionProgress source = session(store, "old", "castle",
                "minecraft:overworld", 10L);
        store.session("new-key");

        ProgressStore.SessionProgress adopted = store.adopt(source, "new-key");

        assertSame(source, adopted);
        assertEquals("new-key", adopted.key);
        assertNull(store.find("old"));
        assertSame(source, store.find("new-key"));
        assertEquals(1, store.sessions().size());
    }
}
