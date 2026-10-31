package sleepy.autobuilder.autobuild.core;

/**
 * Pure placement scheduling decisions. Keeping this out of the Minecraft adapter makes the
 * interaction between work budget, in-flight verification and the packet quota testable without a
 * client.
 */
public final class PlacementScheduler {
    public record Limits(int actionsPerTick, int maxPendingVerifications, int placementDelayTicks,
                         int retryDelayTicks, int verifyDelayTicks) {
        public Limits {
            actionsPerTick = Math.max(1, Math.min(9, actionsPerTick));
            maxPendingVerifications = Math.max(1, Math.min(256, maxPendingVerifications));
            placementDelayTicks = Math.max(0, Math.min(100, placementDelayTicks));
            retryDelayTicks = Math.max(1, Math.min(200, retryDelayTicks));
            verifyDelayTicks = Math.max(1, Math.min(200, verifyDelayTicks));
        }

        public static Limits from(SpeedPreset preset) {
            return new Limits(preset.actionsPerTick(), preset.maxPendingVerifications(),
                    preset.placementDelayTicks(), preset.retryDelayTicks(), preset.verifyDelayTicks());
        }
    }

    public enum BlockReason {
        READY,
        ACTION_BUDGET,
        VERIFICATION_WINDOW,
        PACKET_QUOTA,
        PLACEMENT_DELAY
    }

    public record Decision(boolean allowed, BlockReason reason) {
        public static Decision allow() {
            return new Decision(true, BlockReason.READY);
        }

        public static Decision block(BlockReason reason) {
            return new Decision(false, reason == null ? BlockReason.ACTION_BUDGET : reason);
        }
    }

    private PlacementScheduler() {
    }

    /**
     * Returns whether another placement may be started now. The packet quota is checked first so a
     * saturated quota never consumes the local work budget or verification window.
     */
    public static Decision canStart(int actionsThisTick, int inFlightVerifications, int remainingQuota,
                                    long now, long lastPlacementTick, Limits limits) {
        if (remainingQuota <= 0) return Decision.block(BlockReason.PACKET_QUOTA);
        if (actionsThisTick >= limits.actionsPerTick()) return Decision.block(BlockReason.ACTION_BUDGET);
        if (inFlightVerifications >= limits.maxPendingVerifications()) {
            return Decision.block(BlockReason.VERIFICATION_WINDOW);
        }
        if (limits.placementDelayTicks() > 0
                && now - lastPlacementTick < limits.placementDelayTicks()) {
            return Decision.block(BlockReason.PLACEMENT_DELAY);
        }
        return Decision.allow();
    }

    public static long nextPlacementTick(long now, Limits limits) {
        return now + limits.placementDelayTicks();
    }

    public static long verifyAt(long now, Limits limits) {
        return now + limits.verifyDelayTicks();
    }

    public static long retryAt(long now, Limits limits) {
        return now + limits.retryDelayTicks();
    }

    public static boolean isDue(long now, long dueTick) {
        return now >= dueTick;
    }

    /**
     * True while a sent placement is still allowed to be missing because the server has not applied
     * it yet. The effective grace is never shorter than the configured verification delay, so a long
     * verify delay cannot accidentally disable the grace period.
     */
    public static boolean verificationGraceActive(long placedAtTick, long nowTick,
                                                  int verifyDelayTicks, int timeoutTicks) {
        long elapsed = Math.max(0L, nowTick - placedAtTick);
        int effective = Math.max(Math.max(1, verifyDelayTicks), Math.max(1, timeoutTicks));
        return elapsed < effective;
    }
}
