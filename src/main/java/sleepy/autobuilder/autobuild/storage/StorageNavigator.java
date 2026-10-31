package sleepy.autobuilder.autobuild.storage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import sleepy.autobuilder.autobuild.AutoBuilder;
import sleepy.autobuilder.autobuild.TravelMode;
import sleepy.autobuilder.autobuild.mc.TravelService;

/**
 * One-shot Baritone navigation used by the storage manager's "Goto" control and the
 * {@code .autob stash goto} command. It does not search containers; the restock service does that.
 */
public final class StorageNavigator {
    private static final StorageNavigator INSTANCE = new StorageNavigator();
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final TravelService travel = new TravelService();
    private StorageEntry entry;
    private boolean active;
    private String message = "";

    private StorageNavigator() {
    }

    public static StorageNavigator get() {
        return INSTANCE;
    }

    public boolean navigate(StorageEntry target) {
        if (target == null) {
            message = "No storage entry selected.";
            return false;
        }
        if (mc.player == null || mc.world == null) {
            message = "Join a world first.";
            return false;
        }
        String dimension = mc.world.getRegistryKey().getValue().toString();
        if (!target.dimensionMatches(dimension)) {
            message = "Storage '" + target.name + "' is in " + target.dimension
                    + "; you are in " + dimension + ". Cross-dimension travel is not attempted.";
            return false;
        }
        if (active) travel.cancel();

        AutoBuilder module = AutoBuilderModule.get();
        TravelMode mode = module == null ? TravelMode.AUTO : module.travelMode.get();
        int timeout = module == null ? 1200 : module.travelTimeoutTicks.get();
        int retries = module == null ? 2 : module.travelRetries.get();
        double arrival = module == null ? 3.0 : module.arrivalDistance.get();
        boolean acceptTerms = module != null && module.acceptBaritoneElytraTerms.get();
        double elytraMin = module == null ? 100.0 : module.elytraMinDistance.get();

        BlockPos pos = target.pos();
        double distance = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
        if (distance <= Math.max(2.0, arrival)) {
            StorageManager.get().markVisited(target.id);
            message = "Already at '" + target.name + "' (" + target.coords() + ").";
            active = false;
            this.entry = target;
            return true;
        }

        TravelService.StartResult result = travel.start(pos, mode, arrival, timeout, retries,
                acceptTerms, elytraMin);
        switch (result) {
            case STARTED -> {
                entry = target;
                active = true;
                message = "Travelling to '" + target.name + "' at " + target.coords()
                        + " (" + target.shortDimension() + ") with " + (travel.usingElytra() ? "elytra" : "Baritone") + ".";
                return true;
            }
            case ALREADY_THERE -> {
                entry = target;
                active = false;
                StorageManager.get().markVisited(target.id);
                message = "Already at '" + target.name + "'.";
                return true;
            }
            case FAILED -> {
                entry = target;
                active = false;
                message = "Could not travel to '" + target.name + "': " + travel.error();
                return false;
            }
        }
        message = "Could not start travel.";
        return false;
    }

    public void tick() {
        if (!active) return;
        if (mc.player == null || mc.world == null) {
            active = false;
            message = "Travel stopped: left the world.";
            travel.cancel();
            return;
        }
        TravelService.Status status = travel.tick();
        switch (status.result()) {
            case ARRIVED -> {
                active = false;
                if (entry != null) {
                    StorageManager.get().markVisited(entry.id);
                    message = "Arrived at '" + entry.name + "' (" + entry.coords() + ").";
                }
            }
            case FAILED -> {
                active = false;
                message = "Travel failed: " + status.message();
            }
            default -> message = "Travelling to '" + (entry == null ? "storage" : entry.name) + "'"
                    + (entry == null ? "" : " (" + (int) travel.distance() + " blocks)") + "...";
        }
    }

    public void cancel() {
        if (active) travel.cancel();
        active = false;
        message = "Travel cancelled.";
    }

    public boolean isActive() {
        return active;
    }

    public StorageEntry entry() {
        return entry;
    }

    public String message() {
        return message;
    }

    /** Kept here to avoid exposing a wider dependency from GUI code. */
    private static final class AutoBuilderModule {
        private static AutoBuilder get() {
            return meteordevelopment.meteorclient.systems.modules.Modules.get().get(AutoBuilder.class);
        }
    }
}
