package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlacementSchedulerTest {
    private final PlacementScheduler.Limits limits = new PlacementScheduler.Limits(2, 4, 3, 5, 6);

    @Test
    void quotaIsCheckedBeforeTheLocalBudget() {
        PlacementScheduler.Decision decision = PlacementScheduler.canStart(0, 0, 0, 100, 0, limits);
        assertFalse(decision.allowed());
        assertEquals(PlacementScheduler.BlockReason.PACKET_QUOTA, decision.reason());
    }

    @Test
    void actionBudgetAndVerificationWindowBlockNewStarts() {
        assertEquals(PlacementScheduler.BlockReason.ACTION_BUDGET,
                PlacementScheduler.canStart(2, 0, 9, 100, 0, limits).reason());
        assertEquals(PlacementScheduler.BlockReason.VERIFICATION_WINDOW,
                PlacementScheduler.canStart(0, 4, 9, 100, 0, limits).reason());
    }

    @Test
    void placementDelayUsesTickTime() {
        assertEquals(PlacementScheduler.BlockReason.PLACEMENT_DELAY,
                PlacementScheduler.canStart(0, 0, 9, 102, 100, limits).reason());
        assertTrue(PlacementScheduler.canStart(0, 0, 9, 103, 100, limits).allowed());
    }

    @Test
    void zeroDelayNeverBlocks() {
        PlacementScheduler.Limits noDelay = new PlacementScheduler.Limits(2, 4, 0, 5, 6);
        assertTrue(PlacementScheduler.canStart(0, 0, 9, 100, 100, noDelay).allowed());
    }

    @Test
    void dueTimesUseConfiguredDelays() {
        assertEquals(106, PlacementScheduler.verifyAt(100, limits));
        assertEquals(105, PlacementScheduler.retryAt(100, limits));
        assertEquals(103, PlacementScheduler.nextPlacementTick(100, limits));
        assertFalse(PlacementScheduler.isDue(105, 106));
        assertTrue(PlacementScheduler.isDue(106, 106));
    }

    @Test
    void limitsClampUnsafeInputs() {
        PlacementScheduler.Limits clamped = new PlacementScheduler.Limits(0, 0, -5, 0, 0);
        assertEquals(1, clamped.actionsPerTick());
        assertEquals(1, clamped.maxPendingVerifications());
        assertEquals(0, clamped.placementDelayTicks());
        assertEquals(1, clamped.retryDelayTicks());
        assertEquals(1, clamped.verifyDelayTicks());

        PlacementScheduler.Limits huge = new PlacementScheduler.Limits(100, 1000, 500, 500, 500);
        assertEquals(9, huge.actionsPerTick());
        assertEquals(256, huge.maxPendingVerifications());
        assertEquals(100, huge.placementDelayTicks());
        assertEquals(200, huge.retryDelayTicks());
        assertEquals(200, huge.verifyDelayTicks());
    }

    @Test
    void verificationGraceCoversLagThenExpires() {
        assertTrue(PlacementScheduler.verificationGraceActive(100, 105, 5, 60));
        assertTrue(PlacementScheduler.verificationGraceActive(100, 159, 5, 60));
        assertFalse(PlacementScheduler.verificationGraceActive(100, 160, 5, 60));
        assertTrue(PlacementScheduler.verificationGraceActive(100, 104, 10, 5),
                "grace must never be shorter than the configured verify delay");
        assertTrue(PlacementScheduler.verificationGraceActive(100, 99, 5, 60),
                "a clock that has not advanced yet must not expire the grace");
    }

    @Test
    void speedPresetsProduceCoherentLimits() {
        PlacementScheduler.Limits safe = PlacementScheduler.Limits.from(SpeedPreset.SAFE);
        PlacementScheduler.Limits maximum = PlacementScheduler.Limits.from(SpeedPreset.MAXIMUM);
        assertTrue(safe.actionsPerTick() <= maximum.actionsPerTick());
        assertTrue(safe.verifyDelayTicks() >= maximum.verifyDelayTicks());
        assertTrue(safe.maxPendingVerifications() <= maximum.maxPendingVerifications());
        assertTrue(maximum.placementDelayTicks() <= safe.placementDelayTicks());
    }
}
