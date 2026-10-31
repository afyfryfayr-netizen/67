package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressReconcilerTest {
    private static long pos(int x, int y, int z) {
        return PositionCodec.pack(x, y, z);
    }

    @Test
    void removesStalePositionsAndClampsLayerState() {
        Box3i region = Box3i.of(-5, -64, -5, 5, 70, 5);
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        session.completed.add(pos(-2, 62, -2));
        session.completed.add(pos(0, 300, 0));
        session.completed.add(pos(20, 62, 0));
        session.ignored.add(pos(-1, 61, 1));
        session.ignored.add(pos(0, 64, 0));
        session.pending.add(pos(-1, 61, 2));
        session.pending.add(pos(0, 64, 0));
        session.pending.add(pos(10, 61, 0));
        session.pending.add(pos(0, -65, 0));
        session.failed.add(pos(-3, 60, -3));
        session.failed.add(pos(0, 59, 0));
        session.currentLayer = 64;
        session.layerStart = -100;
        session.layerEnd = 100;
        session.hasAnchor = true;
        session.anchorX = 10_000;
        session.anchorY = 61;
        session.anchorZ = 0;
        session.hasResume = true;
        session.resumeX = 100;
        session.resumeY = 64;
        session.resumeZ = 0;

        ProgressReconciler.Result result = ProgressReconciler.reconcile(session, region,
                LayerAxis.Y, 60, 63, -64, 319);

        assertEquals(7, result.removed());
        assertEquals(1, result.pendingRemaining());
        assertEquals(64, result.layerBefore());
        assertEquals(63, result.layerAfter());
        assertTrue(result.anchorCleared());
        assertTrue(result.resumeCleared());
        assertTrue(result.changed());
        assertEquals(1, session.completed.size());
        assertEquals(1, session.ignored.size());
        assertEquals(1, session.pending.size());
        assertEquals(1, session.failed.size());
        assertEquals(63, session.currentLayer);
        assertEquals(60, session.layerStart);
        assertEquals(63, session.layerEnd);
        assertFalse(session.hasAnchor);
        assertFalse(session.hasResume);
        assertEquals(1, ProgressReconciler.countValidPending(session, region, LayerAxis.Y,
                60, 63, -64, 319));
    }

    @Test
    void validSessionIsLeftUntouched() {
        Box3i region = Box3i.of(0, 0, 0, 3, 3, 3);
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        session.pending.add(pos(1, 1, 1));
        session.currentLayer = 1;
        session.layerStart = 0;
        session.layerEnd = 3;
        session.hasAnchor = true;
        session.anchorX = 1;
        session.anchorY = 1;
        session.anchorZ = 1;
        session.hasResume = true;
        session.resumeX = 2;
        session.resumeY = 2;
        session.resumeZ = 2;

        ProgressReconciler.Result result = ProgressReconciler.reconcile(session, region,
                LayerAxis.Y, 0, 3, -64, 319);

        assertEquals(0, result.removed());
        assertFalse(result.changed());
        assertEquals(1, session.pending.size());
        assertTrue(session.hasAnchor);
        assertTrue(session.hasResume);
    }

    @Test
    void layerFilteringWorksOnXAndZAxes() {
        Box3i region = Box3i.of(-5, -64, -5, 5, 70, 5);
        ProgressStore.SessionProgress xSession = new ProgressStore.SessionProgress();
        xSession.pending.add(pos(-2, 0, -2));
        xSession.pending.add(pos(0, 0, 0));
        ProgressReconciler.Result x = ProgressReconciler.reconcile(xSession, region,
                LayerAxis.X, -3, -1, -64, 319);
        assertEquals(1, x.removed());
        assertEquals(1, x.pendingRemaining());

        ProgressStore.SessionProgress zSession = new ProgressStore.SessionProgress();
        zSession.pending.add(pos(-2, 0, 2));
        zSession.pending.add(pos(0, 0, 0));
        ProgressReconciler.Result z = ProgressReconciler.reconcile(zSession, region,
                LayerAxis.Z, 1, 3, -64, 319);
        assertEquals(1, z.removed());
        assertEquals(1, z.pendingRemaining());
    }

    @Test
    void worldHeightPruningUsesInclusiveBounds() {
        Box3i region = Box3i.of(-2, -70, -2, 2, 400, 2);
        ProgressStore.SessionProgress session = new ProgressStore.SessionProgress();
        session.pending.add(pos(0, -64, 0));
        session.pending.add(pos(0, 319, 0));
        session.pending.add(pos(0, -65, 0));
        session.pending.add(pos(0, 320, 0));

        ProgressReconciler.Result result = ProgressReconciler.reconcile(session, region,
                LayerAxis.Y, -64, 319, -64, 319);

        assertEquals(2, result.removed());
        assertEquals(2, result.pendingRemaining());
    }

    @Test
    void nullSessionIsSafe() {
        ProgressReconciler.Result result = ProgressReconciler.reconcile(null,
                Box3i.of(0, 0, 0, 0, 0, 0), LayerAxis.Y, 0, 0);
        assertEquals(0, result.removed());
        assertFalse(result.changed());
        assertEquals(0, ProgressReconciler.countValidPending(null,
                Box3i.of(0, 0, 0, 0, 0, 0), LayerAxis.Y, 0, 0));
    }
}
