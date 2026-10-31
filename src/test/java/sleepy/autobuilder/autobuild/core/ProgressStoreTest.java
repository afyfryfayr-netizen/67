package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsAllPositionSetsExactly() throws Exception {
        Path file = tempDir.resolve("progress.json");
        ProgressStore store = new ProgressStore(file);
        ProgressStore.SessionProgress session = store.session("castle");
        session.schematicName = "castle";
        session.dimension = "minecraft:overworld";
        session.currentLayer = 70;
        long a = PositionCodec.pack(1, 64, -2);
        long b = PositionCodec.pack(-30_000_000, 255, 30_000_000);
        long c = PositionCodec.pack(0, -64, 0);
        session.markCompleted(a);
        session.markCompleted(b);
        session.markIgnored(c);
        session.markPending(PositionCodec.pack(3, 65, 4));
        session.markFailed(PositionCodec.pack(5, 66, 6));
        store.save();
        assertTrue(Files.exists(file));

        ProgressStore loaded = new ProgressStore(file);
        loaded.load();
        ProgressStore.SessionProgress restored = loaded.find("castle");
        assertNotNull(restored);
        assertEquals("castle", restored.schematicName);
        assertTrue(restored.completed.contains(a));
        assertTrue(restored.completed.contains(b));
        assertTrue(restored.ignored.contains(c));
        assertEquals(1, restored.pending.size());
        assertEquals(1, restored.failed.size());
        assertEquals(70, restored.currentLayer);
    }

    @Test
    void savedBuildAnchorSurvivesReload() throws Exception {
        Path file = tempDir.resolve("anchor.json");
        ProgressStore store = new ProgressStore(file);
        ProgressStore.SessionProgress session = store.session("tower");
        session.anchorX = 1200000;
        session.anchorY = 71;
        session.anchorZ = -450;
        session.hasAnchor = true;
        store.save();

        ProgressStore loaded = new ProgressStore(file);
        loaded.load();
        ProgressStore.SessionProgress restored = loaded.find("tower");
        assertNotNull(restored);
        assertTrue(restored.hasAnchor);
        assertEquals(1200000, restored.anchorX);
        assertEquals(71, restored.anchorY);
        assertEquals(-450, restored.anchorZ);
    }

    @Test
    void pendingIsClearedWhenPositionCompletesOrIsIgnored() {
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        long pos = PositionCodec.pack(4, 4, 4);
        session.markPending(pos);
        assertTrue(session.pending.contains(pos));
        session.markCompleted(pos);
        assertFalse(session.pending.contains(pos));
        assertTrue(session.completed.contains(pos));

        long ignored = PositionCodec.pack(5, 5, 5);
        session.markPending(ignored);
        session.markIgnored(ignored);
        assertFalse(session.pending.contains(ignored));
        assertTrue(session.ignored.contains(ignored));
    }

    @Test
    void completedPositionsAreNotReaddedAsPending() {
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        long pos = PositionCodec.pack(1, 2, 3);
        session.markCompleted(pos);
        session.markPending(pos);
        assertFalse(session.pending.contains(pos));
    }

    @Test
    void progressPercentCountsIgnoredAndFailedAsActioned() {
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        for (int i = 0; i < 4; i++) session.markCompleted(PositionCodec.pack(i, 0, 0));
        session.markIgnored(PositionCodec.pack(4, 0, 0));
        session.markPending(PositionCodec.pack(5, 0, 0));
        assertEquals(83, session.progressPercent());
    }

    @Test
    void removingSessionPersists() throws Exception {
        Path file = tempDir.resolve("progress.json");
        ProgressStore store = new ProgressStore(file);
        store.session("a");
        store.session("b");
        store.save();
        store.remove("a");
        store.save();
        ProgressStore loaded = new ProgressStore(file);
        loaded.load();
        assertNull(loaded.find("a"));
        assertNotNull(loaded.find("b"));
    }
}
