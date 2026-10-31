package sleepy.autobuilder.autobuild.core;

/**
 * Explicit travel state machine with bounded retries. It deliberately contains no pathfinding code;
 * the Minecraft adapter starts/stops Baritone based on the returned phase.
 */
public final class TravelSupervisor {
    public enum Phase {
        IDLE,
        TRAVELING,
        RETRY,
        ARRIVED,
        FAILED,
        STOPPED
    }

    public record Status(Phase phase, int attempt, int maxAttempts, long elapsedTicks, String message) {
    }

    private final int timeoutTicks;
    private final int maxAttempts;
    private final double arrivalTolerance;
    private final long stallGraceTicks;

    private Phase phase = Phase.IDLE;
    private int attempt;
    private long startedAt;
    private String message = "";

    public TravelSupervisor(int timeoutTicks, int maxAttempts, double arrivalTolerance) {
        this(timeoutTicks, maxAttempts, arrivalTolerance, 20);
    }

    public TravelSupervisor(int timeoutTicks, int maxAttempts, double arrivalTolerance,
                            int stallGraceTicks) {
        this.timeoutTicks = Math.max(1, timeoutTicks);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.arrivalTolerance = Math.max(0.5, arrivalTolerance);
        this.stallGraceTicks = Math.max(1, stallGraceTicks);
    }

    public void begin(long now) {
        phase = Phase.TRAVELING;
        attempt = 1;
        startedAt = now;
        message = "travel started";
    }

    public void retry(long now) {
        if (phase == Phase.RETRY) {
            phase = Phase.TRAVELING;
            startedAt = now;
            message = "travel retry " + attempt;
        }
    }

    public Status tick(long now, double distance, boolean travelActive, boolean travelFailed) {
        if (phase != Phase.TRAVELING) return status(now);
        long elapsed = Math.max(0, now - startedAt);
        if (distance <= arrivalTolerance) {
            phase = Phase.ARRIVED;
            message = "arrived";
            return status(now);
        }
        if (travelFailed) return retryOrFail(now, "travel failed");
        if (elapsed >= timeoutTicks) return retryOrFail(now, "travel timeout");
        if (!travelActive && elapsed > stallGraceTicks) {
            // Baritone may not report pathing while it calculates a path, and a goal may legitimately
            // pause. The adapter reports movement/goal state as active; only a genuinely idle trip
            // that also stopped making progress reaches this branch.
            return retryOrFail(now, "travel stalled");
        }
        return new Status(phase, attempt, maxAttempts, elapsed, message);
    }

    public void arrive() {
        phase = Phase.ARRIVED;
        message = "arrived";
    }

    public void stop() {
        phase = Phase.STOPPED;
        message = "travel stopped";
    }

    public void fail(String reason) {
        phase = Phase.FAILED;
        message = reason == null ? "travel failed" : reason;
    }

    public Status status(long now) {
        long elapsed = (phase == Phase.TRAVELING || phase == Phase.RETRY) ? Math.max(0, now - startedAt) : 0;
        return new Status(phase, attempt, maxAttempts, elapsed, message);
    }

    public double arrivalTolerance() {
        return arrivalTolerance;
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }

    private Status retryOrFail(long now, String reason) {
        if (attempt < maxAttempts) {
            attempt++;
            phase = Phase.RETRY;
            message = reason;
        } else {
            phase = Phase.FAILED;
            message = reason + " (retries exhausted)";
        }
        return status(now);
    }
}
