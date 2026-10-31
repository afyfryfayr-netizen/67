package sleepy.autobuilder.autobuild.core;

/**
 * Coherent printing-speed presets. A preset only supplies starting values; every value remains an
 * individual setting so advanced users can tune one dimension without losing the rest.
 */
public enum SpeedPreset {
    SAFE("Safe", 1, 10, 6, 4, 2),
    BALANCED("Balanced", 2, 5, 3, 16, 0),
    FAST("Fast", 4, 3, 2, 32, 0),
    MAXIMUM("Maximum", 9, 2, 1, 64, 0);

    private final String title;
    private final int actionsPerTick;
    private final int verifyDelayTicks;
    private final int retryDelayTicks;
    private final int maxPendingVerifications;
    private final int placementDelayTicks;

    SpeedPreset(String title, int actionsPerTick, int verifyDelayTicks, int retryDelayTicks,
                int maxPendingVerifications, int placementDelayTicks) {
        this.title = title;
        this.actionsPerTick = actionsPerTick;
        this.verifyDelayTicks = verifyDelayTicks;
        this.retryDelayTicks = retryDelayTicks;
        this.maxPendingVerifications = maxPendingVerifications;
        this.placementDelayTicks = placementDelayTicks;
    }

    public String title() {
        return title;
    }

    public int actionsPerTick() {
        return actionsPerTick;
    }

    public int verifyDelayTicks() {
        return verifyDelayTicks;
    }

    public int retryDelayTicks() {
        return retryDelayTicks;
    }

    public int maxPendingVerifications() {
        return maxPendingVerifications;
    }

    public int placementDelayTicks() {
        return placementDelayTicks;
    }

    @Override
    public String toString() {
        return title;
    }
}
