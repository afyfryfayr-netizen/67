package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import sleepy.autobuilder.autobuild.TravelMode;
import sleepy.autobuilder.autobuild.core.TravelSupervisor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Baritone travel adapter with explicit capability diagnostics.
 *
 * <p>Walking uses Baritone's custom-goal process. Elytra travel uses
 * {@code IBaritone.getElytraProcess().pathTo(BlockPos)} from an Overworld-capable Baritone build.
 * The legacy {@code baritone-meteor} fork bundled by Meteor 0.5.8 only supports elytra in the
 * Nether; it is detected and reported rather than silently replaced with walking.</p>
 *
 * <p>Baritone is optional at runtime and is accessed reflectively so the addon can load without it.
 * A long AUTO trip never falls back to walking: it either flies or reports a precise failure.</p>
 */
public final class TravelService {
    public enum StartResult { STARTED, ALREADY_THERE, FAILED }
    public enum TickResult { IDLE, TRAVELING, ARRIVED, RETRYING, FAILED }

    public record Status(TickResult result, TravelSupervisor.Phase phase, double distance, int attempt,
                         int maxAttempts, String message) {
    }

    /** Snapshot of the installed Baritone build and the elytra preconditions. */
    public record Capabilities(
            boolean baritonePresent,
            String modId,
            String modVersion,
            boolean elytraApiPresent,
            boolean overworldElytra,
            boolean elytraLoaded,
            boolean termsAccepted,
            boolean elytraEquipped,
            int fireworks,
            String dimension,
            String detail
    ) {
        public boolean legacyNetherOnly() {
            return elytraApiPresent && !overworldElytra;
        }
    }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static volatile Bridge cachedBridge;
    private static volatile String lastBridgeError = "";

    private boolean active;
    private BlockPos target;
    private boolean elytra;
    private double arrivalDistance = 3.0;
    private int ticks;
    private String error = "";
    private TravelSupervisor supervisor;
    private TravelSupervisor.Status lastStatus;
    private Bridge bridge;
    private Object elytraProcess;
    private boolean autoJumpChanged;
    private boolean previousAutoJump;
    private int stallGraceTicks = 60;
    private Vec3d lastProgressPos;
    private int lastProgressTick;

    public StartResult start(BlockPos target, TravelMode mode, double arrivalDistance, int timeoutTicks,
                             int retries, boolean acceptElytraTerms, double elytraMinDistance) {
        return start(target, mode, arrivalDistance, timeoutTicks, retries, acceptElytraTerms,
                elytraMinDistance, 60);
    }

    public StartResult start(BlockPos target, TravelMode mode, double arrivalDistance, int timeoutTicks,
                             int retries, boolean acceptElytraTerms, double elytraMinDistance,
                             int stallGraceTicks) {
        cancel();
        error = "";
        if (mc.player == null || mc.world == null) {
            error = "not in a world";
            return StartResult.FAILED;
        }
        if (target == null) {
            error = "travel target is missing";
            return StartResult.FAILED;
        }

        this.target = target.toImmutable();
        // Baritone's GoalNear uses an integer radius. Use the same ceiling for the supervisor's
        // arrival test; otherwise Baritone stops just outside a fractional tolerance and the trip is
        // falsely reported as stalled.
        this.arrivalDistance = Math.max(1.0, Math.ceil(Math.max(0.5, arrivalDistance)));
        this.stallGraceTicks = Math.max(20, stallGraceTicks);
        this.ticks = 0;
        this.lastProgressPos = mc.player.getPos();
        this.lastProgressTick = 0;

        double distance = distance();
        if (distance <= this.arrivalDistance) return StartResult.ALREADY_THERE;

        Bridge api = bridge();
        if (api == null) {
            error = lastBridgeError.isBlank()
                    ? "Baritone is not installed (baritone.api.BaritoneAPI was not found)"
                    : "Baritone is installed but its API is not available: " + lastBridgeError;
            return StartResult.FAILED;
        }
        this.bridge = api;

        boolean wantElytra = switch (mode) {
            case ELYTRA -> true;
            case WALK -> false;
            case AUTO -> distance >= Math.max(1.0, elytraMinDistance);
        };

        if (wantElytra) {
            String unavailable = elytraUnavailableReason(api, acceptElytraTerms);
            if (unavailable != null) {
                error = unavailable;
                return StartResult.FAILED;
            }
            if (!startElytra(api)) return StartResult.FAILED;
            elytra = true;
        } else {
            if (!startWalk(api)) return StartResult.FAILED;
            elytra = false;
        }

        supervisor = new TravelSupervisor(timeoutTicks, retries, this.arrivalDistance, this.stallGraceTicks);
        supervisor.begin(0);
        lastStatus = supervisor.status(0);
        active = true;
        return StartResult.STARTED;
    }

    public Status tick() {
        if (!active) {
            if (lastStatus != null && lastStatus.phase() == TravelSupervisor.Phase.FAILED) {
                return new Status(TickResult.FAILED, lastStatus.phase(), 0, lastStatus.attempt(),
                        lastStatus.maxAttempts(), lastStatus.message());
            }
            return new Status(TickResult.IDLE, TravelSupervisor.Phase.IDLE, 0, 0, 0, "");
        }

        ticks++;
        if (mc.player == null || mc.world == null) {
            return failNow("left the world");
        }

        double distance = distance();
        boolean moved = updateProgress();
        boolean processActive;
        try {
            processActive = elytra ? elytraActive() : pathingActive();
        } catch (Throwable throwable) {
            return failNow("Baritone status check failed: " + rootMessage(throwable));
        }

        boolean calculating = false;
        if (!elytra) {
            try {
                calculating = bridge != null && (bridge.hasGoal() || bridge.customGoalActive());
            } catch (Throwable ignored) {
                calculating = false;
            }
        }

        // The elytra process needs time to load chunks and the native pathfinder before isActive()
        // becomes true. Walking can spend several seconds calculating a long path without reporting
        // isPathing(). Movement, an active goal/path, or this bounded grace keeps the trip alive.
        int graceTicks = elytra ? Math.max(stallGraceTicks, 100) : stallGraceTicks;
        boolean withinGrace = ticks <= graceTicks;
        boolean stillCalculating = calculating && (ticks - lastProgressTick) <= graceTicks;
        boolean travelActive = processActive || moved || withinGrace || stillCalculating;

        // Never treat an airborne elytra flight as "arrived": cancelling Baritone while the player
        // is still gliding would drop them out of the sky. Baritone clears its process after it has
        // landed, so the real distance is used again once the player is back on the ground.
        boolean airborne = elytra && mc.player.isFallFlying();
        if (airborne) travelActive = true;
        lastStatus = supervisor.tick(ticks, airborne ? Double.MAX_VALUE : distance, travelActive, false);

        switch (lastStatus.phase()) {
            case ARRIVED -> {
                stopProcess(false);
                active = false;
                return new Status(TickResult.ARRIVED, lastStatus.phase(), distance, lastStatus.attempt(),
                        lastStatus.maxAttempts(), lastStatus.message());
            }
            case RETRY -> {
                supervisor.retry(ticks);
                if (!elytra) stopProcess(true);
                boolean restarted = elytra ? startElytra(bridge) : startWalk(bridge);
                if (!restarted) {
                    supervisor.fail(error);
                    lastStatus = supervisor.status(ticks);
                    active = false;
                    stopProcess(true);
                    return new Status(TickResult.FAILED, lastStatus.phase(), distance, lastStatus.attempt(),
                            lastStatus.maxAttempts(), lastStatus.message());
                }
                return new Status(TickResult.RETRYING, TravelSupervisor.Phase.TRAVELING, distance,
                        supervisor.status(ticks).attempt(), supervisor.status(ticks).maxAttempts(),
                        "retrying travel (" + supervisor.status(ticks).attempt() + "/"
                                + supervisor.status(ticks).maxAttempts() + ")");
            }
            case FAILED -> {
                active = false;
                stopProcess(true);
                return new Status(TickResult.FAILED, lastStatus.phase(), distance, lastStatus.attempt(),
                        lastStatus.maxAttempts(), lastStatus.message());
            }
            default -> {
                return new Status(TickResult.TRAVELING, lastStatus.phase(), distance, lastStatus.attempt(),
                        lastStatus.maxAttempts(), lastStatus.message());
            }
        }
    }

    private Status failNow(String message) {
        error = message;
        if (supervisor != null) supervisor.fail(message);
        lastStatus = supervisor == null
                ? new TravelSupervisor.Status(TravelSupervisor.Phase.FAILED, 0, 0, ticks, message)
                : supervisor.status(ticks);
        active = false;
        stopProcess(true);
        return new Status(TickResult.FAILED, TravelSupervisor.Phase.FAILED, distance(), lastStatus.attempt(),
                lastStatus.maxAttempts(), message);
    }

    public void cancel() {
        if (active) stopProcess(true);
        active = false;
        supervisor = null;
        lastStatus = null;
        ticks = 0;
        elytra = false;
        elytraProcess = null;
        lastProgressPos = null;
        lastProgressTick = 0;
    }

    /** Tracks real player movement so a path calculation pause is not mistaken for a stall. */
    private boolean updateProgress() {
        if (mc.player == null) return false;
        Vec3d now = mc.player.getPos();
        if (lastProgressPos == null || now.squaredDistanceTo(lastProgressPos) >= 0.04) {
            lastProgressPos = now;
            lastProgressTick = ticks;
            return true;
        }
        return false;
    }

    public boolean isActive() {
        return active;
    }

    public BlockPos target() {
        return target;
    }

    public String error() {
        return error;
    }

    public double distance() {
        if (target == null || mc.player == null) return 0;
        return mc.player.getPos().distanceTo(Vec3d.ofCenter(target));
    }

    public boolean usingElytra() {
        return elytra;
    }

    public static boolean isBaritonePresent() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ── Capability diagnostics ───────────────────────────────────────────── */

    public static Capabilities capabilities() {
        Bridge api = bridge();
        String modId = "";
        String modVersion = "";
        Optional<ModContainer> official = FabricLoader.getInstance().getModContainer("baritone");
        Optional<ModContainer> meteor = FabricLoader.getInstance().getModContainer("baritone-meteor");
        Optional<ModContainer> found = official.isPresent() ? official : meteor;
        if (found.isPresent()) {
            modId = found.get().getMetadata().getId();
            modVersion = found.get().getMetadata().getVersion().getFriendlyString();
        }

        boolean elytraApi = classExists("baritone.api.process.IElytraProcess");
        // The legacy baritone-meteor fork exposes the same elytra interface but only flies in the
        // Nether. Distinguish it by its Fabric mod id instead of guessing from interface methods.
        boolean legacyBuild = "baritone-meteor".equalsIgnoreCase(modId);
        boolean overworld = elytraApi && !legacyBuild;
        boolean loaded = false;
        try {
            if (api != null && api.hasElytra()) loaded = api.elytraLoaded();
        } catch (Throwable ignored) {
        }

        boolean terms = false;
        try {
            terms = readBooleanSetting("elytraTermsAccepted");
        } catch (Throwable ignored) {
        }

        boolean equipped = mc.player != null
                && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        int fireworks = mc.player == null ? 0 : InvUtils.find(Items.FIREWORK_ROCKET).count();
        String dimension = mc.world == null ? "none" : mc.world.getRegistryKey().getValue().toString();

        String detail;
        if (api == null) {
            detail = lastBridgeError.isBlank()
                    ? "Baritone is not installed. Travel and restocking require Baritone."
                    : "Baritone API is not ready: " + lastBridgeError;
        } else if (!elytraApi) {
            detail = "Baritone is installed but this build has no elytra process.";
        } else if (!overworld) {
            detail = legacyElytraMessage(modId, modVersion);
        } else {
            detail = "Overworld/End elytra API present. Native elytra loaded: " + loaded + ".";
        }

        return new Capabilities(api != null, modId, modVersion, elytraApi, overworld, loaded, terms,
                equipped, fireworks, dimension, detail);
    }

    public static String capabilityReport() {
        Capabilities c = capabilities();
        StringBuilder out = new StringBuilder();
        out.append("Baritone: ").append(c.baritonePresent() ? "installed" : "not installed").append('\n');
        out.append("Mod id/version: ")
                .append(c.modId().isBlank() ? "unknown" : c.modId())
                .append(c.modVersion().isBlank() ? "" : " " + c.modVersion()).append('\n');
        out.append("Elytra API: ").append(c.elytraApiPresent() ? "present" : "missing")
                .append("; Overworld/End capable: ").append(c.overworldElytra() ? "yes" : "no").append('\n');
        out.append("Elytra loaded: ").append(c.elytraLoaded() ? "yes" : "no")
                .append("; terms accepted: ").append(c.termsAccepted() ? "yes" : "no").append('\n');
        out.append("Elytra equipped: ").append(c.elytraEquipped() ? "yes" : "no")
                .append("; fireworks: ").append(c.fireworks()).append('\n');
        out.append("Dimension: ").append(c.dimension()).append('\n');
        out.append("Detail: ").append(c.detail());
        return out.toString();
    }

    private static String legacyElytraMessage(String modId, String modVersion) {
        String build = (modId.isBlank() ? "unknown Baritone build" : modId)
                + (modVersion.isBlank() ? "" : " " + modVersion);
        return "This is a legacy Nether-only Baritone elytra build (" + build
                + "). It cannot fly in the Overworld or End. Install the official Baritone 1.21.1 build"
                + " from the upstream 1.21.1 branch (contains Overworld/End elytra support, PR #5015).";
    }

    /* ── Start helpers ────────────────────────────────────────────────────── */

    private String elytraUnavailableReason(Bridge api, boolean acceptTerms) {
        if (!api.hasElytra()) {
            return "This Baritone build has no getElytraProcess() API. Install the official Baritone"
                    + " 1.21.1 build with Overworld/End elytra support.";
        }
        Capabilities caps = capabilities();
        if (caps.legacyNetherOnly() && !isNether()) {
            return legacyElytraMessage(caps.modId(), caps.modVersion());
        }
        if (mc.player == null) return "not in a world";
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            return "no elytra equipped in the chest slot";
        }
        if (!InvUtils.find(Items.FIREWORK_ROCKET).found()) {
            return "no firework rockets in the inventory";
        }

        boolean terms;
        try {
            terms = readBooleanSetting("elytraTermsAccepted");
        } catch (Throwable throwable) {
            return "could not read Baritone's elytraTermsAccepted setting: " + rootMessage(throwable);
        }
        if (!terms) {
            if (!acceptTerms) {
                return "Baritone elytra terms are not accepted (enable 'accept-baritone-elytra-terms'"
                        + " or accept them in Baritone)";
            }
            try {
                writeSetting("elytraTermsAccepted", true);
            } catch (Throwable throwable) {
                return "could not set Baritone's elytraTermsAccepted setting: " + rootMessage(throwable);
            }
        }

        boolean loaded;
        try {
            loaded = api.elytraLoaded();
        } catch (Throwable throwable) {
            return "could not query Baritone's elytra process: " + rootMessage(throwable);
        }
        if (!loaded) {
            return "Baritone's elytra process is not loaded/ready (native NetherPathfinder unavailable)";
        }
        return null;
    }

    private boolean startElytra(Bridge api) {
        try {
            elytraProcess = api.elytraProcess();
            if (elytraProcess == null) {
                error = "Baritone returned no elytra process";
                return false;
            }
            if (!autoJumpChanged) {
                previousAutoJump = readBooleanSetting("elytraAutoJump");
                if (!previousAutoJump) {
                    writeSetting("elytraAutoJump", true);
                    autoJumpChanged = true;
                }
            }
            api.elytraPathTo(target);
            Object destination = api.elytraCurrentDestination();
            if (destination == null) {
                error = "Baritone accepted pathTo but created no elytra destination. This usually means"
                        + " a legacy Nether-only elytra build or an unloaded elytra process.";
                stopProcess(true);
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            error = "Baritone elytra start failed: " + rootMessage(throwable);
            stopProcess(true);
            return false;
        }
    }

    private boolean startWalk(Bridge api) {
        try {
            int range = Math.max(1, (int) Math.round(arrivalDistance));
            Object goal = api.goalNear(target, range);
            api.setGoalAndPath(goal);
            return true;
        } catch (Throwable throwable) {
            error = "Baritone walking start failed: " + rootMessage(throwable);
            return false;
        }
    }

    private boolean elytraActive() throws Exception {
        if (elytraProcess == null) return false;
        if (bridge.elytraIsActive()) return true;
        return bridge.elytraCurrentDestination() != null;
    }

    private boolean pathingActive() throws Exception {
        return bridge.isPathing() || bridge.hasCurrentPath() || bridge.customGoalActive();
    }

    /**
     * Finds a standable block near a target, used for short local walks to a build position or a
     * container. Never returns the target block itself, so Baritone does not try to stand inside the
     * block the builder is about to place.
     */
    public static BlockPos standableNear(BlockPos target, int radius) {
        if (mc.world == null || target == null) return null;
        int r = Math.max(1, Math.min(6, radius));
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos candidate = target.add(dx, dy, dz);
                    if (!isStandable(candidate)) continue;
                    double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double score = horizontal + Math.abs(dy) * 0.75
                            + candidate.getSquaredDistance(target) * 0.01;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isStandable(BlockPos pos) {
        if (mc.world == null || !mc.world.isInBuildLimit(pos)) return false;
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState feet = mc.world.getBlockState(pos);
        BlockState head = mc.world.getBlockState(pos.up());
        if (!(feet.isAir() || feet.isReplaceable())) return false;
        if (!(head.isAir() || head.isReplaceable())) return false;
        BlockState below = mc.world.getBlockState(pos.down());
        if (below.isAir() || below.isReplaceable()) return false;
        return below.getFluidState().isEmpty();
    }

    private void stopProcess(boolean abort) {
        if (bridge != null) {
            try {
                bridge.cancelEverything();
            } catch (Throwable ignored) {
            }
            if (elytraProcess != null) {
                try {
                    bridge.elytraOnLostControl();
                } catch (Throwable ignored) {
                }
            }
        }
        if (autoJumpChanged) {
            try {
                writeSetting("elytraAutoJump", previousAutoJump);
            } catch (Throwable ignored) {
            }
            autoJumpChanged = false;
        }
        elytraProcess = null;
    }

    private boolean isNether() {
        return mc.world != null
                && mc.world.getRegistryKey().getValue().getPath().equals("the_nether");
    }

    /* ── Baritone reflection bridge ───────────────────────────────────────── */

    private static Bridge bridge() {
        Bridge cached = cachedBridge;
        if (cached != null) return cached;
        synchronized (TravelService.class) {
            if (cachedBridge != null) return cachedBridge;
            try {
                cachedBridge = Bridge.open();
                lastBridgeError = "";
                return cachedBridge;
            } catch (Throwable throwable) {
                lastBridgeError = rootMessage(throwable);
                return null;
            }
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, TravelService.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasMethod(String className, String methodName) {
        try {
            Class.forName(className, false, TravelService.class.getClassLoader()).getMethod(methodName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean readBooleanSetting(String name) throws Exception {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        Object setting = settings.getClass().getField(name).get(settings);
        Object value = setting.getClass().getField("value").get(setting);
        return Boolean.TRUE.equals(value);
    }

    private static void writeSetting(String name, Object value) throws Exception {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        Object setting = settings.getClass().getField(name).get(settings);
        setting.getClass().getField("value").set(setting, value);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class Bridge {
        private final Object baritone;
        private final Method getCustomGoalProcess;
        private final Method getPathingBehavior;
        private final Method getElytraProcess;
        private final Method setGoalAndPath;
        private final Method isPathing;
        private final Method getGoal;
        private final Method getCurrentPath;
        private final Method customGoalIsActive;
        private final Method cancelEverything;
        private final Method elytraPathTo;
        private final Method elytraIsActive;
        private final Method elytraIsLoaded;
        private final Method elytraCurrentDestination;
        private final Method elytraOnLostControl;
        private final Constructor<?> goalNearConstructor;

        private Bridge(Object baritone, Method getCustomGoalProcess, Method getPathingBehavior,
                       Method getElytraProcess, Method setGoalAndPath, Method isPathing,
                       Method getGoal, Method getCurrentPath, Method customGoalIsActive,
                       Method cancelEverything, Method elytraPathTo, Method elytraIsActive,
                       Method elytraIsLoaded, Method elytraCurrentDestination,
                       Method elytraOnLostControl, Constructor<?> goalNearConstructor) {
            this.baritone = baritone;
            this.getCustomGoalProcess = getCustomGoalProcess;
            this.getPathingBehavior = getPathingBehavior;
            this.getElytraProcess = getElytraProcess;
            this.setGoalAndPath = setGoalAndPath;
            this.isPathing = isPathing;
            this.getGoal = getGoal;
            this.getCurrentPath = getCurrentPath;
            this.customGoalIsActive = customGoalIsActive;
            this.cancelEverything = cancelEverything;
            this.elytraPathTo = elytraPathTo;
            this.elytraIsActive = elytraIsActive;
            this.elytraIsLoaded = elytraIsLoaded;
            this.elytraCurrentDestination = elytraCurrentDestination;
            this.elytraOnLostControl = elytraOnLostControl;
            this.goalNearConstructor = goalNearConstructor;
        }

        static Bridge open() throws Exception {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            if (baritone == null) throw new IllegalStateException("Baritone provider returned no primary instance");

            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> customGoalClass = Class.forName("baritone.api.process.ICustomGoalProcess");
            Class<?> pathingClass = Class.forName("baritone.api.behavior.IPathingBehavior");
            Class<?> processClass = Class.forName("baritone.api.process.IBaritoneProcess");

            // Walking must remain usable even when the installed Baritone build has no elytra API.
            Class<?> elytraClass = optionalClass("baritone.api.process.IElytraProcess");
            Method getElytra = optionalMethod(baritoneClass, "getElytraProcess");
            Method elytraPathTo = elytraClass == null ? null : optionalMethod(elytraClass, "pathTo", BlockPos.class);
            Method elytraCurrent = elytraClass == null ? null : optionalMethod(elytraClass, "currentDestination");
            Method elytraActive = elytraClass == null ? null : optionalMethod(elytraClass, "isActive");
            Method elytraLoaded = elytraClass == null ? null : optionalMethod(elytraClass, "isLoaded");
            Method elytraLost = optionalMethod(processClass, "onLostControl");

            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Constructor<?> goalNear = goalNearClass.getConstructor(BlockPos.class, int.class);

            return new Bridge(baritone,
                    baritoneClass.getMethod("getCustomGoalProcess"),
                    baritoneClass.getMethod("getPathingBehavior"),
                    getElytra,
                    customGoalClass.getMethod("setGoalAndPath", goalClass),
                    pathingClass.getMethod("isPathing"),
                    optionalMethod(pathingClass, "getGoal"),
                    optionalMethod(pathingClass, "getCurrentPath"),
                    optionalMethod(customGoalClass, "isActive"),
                    pathingClass.getMethod("cancelEverything"),
                    elytraPathTo,
                    elytraActive,
                    elytraLoaded,
                    elytraCurrent,
                    elytraLost,
                    goalNear);
        }

        private static Class<?> optionalClass(String name) {
            try {
                return Class.forName(name, false, TravelService.class.getClassLoader());
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Method optionalMethod(Class<?> owner, String name, Class<?>... parameters) {
            try {
                return owner.getMethod(name, parameters);
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean hasElytra() {
            return getElytraProcess != null && elytraPathTo != null
                    && elytraCurrentDestination != null && elytraIsActive != null
                    && elytraIsLoaded != null && elytraOnLostControl != null;
        }

        Object elytraProcess() throws Exception {
            if (getElytraProcess == null) throw new IllegalStateException("no elytra process API");
            return invoke(getElytraProcess, baritone);
        }

        void elytraPathTo(BlockPos destination) throws Exception {
            if (elytraPathTo == null) throw new IllegalStateException("no elytra pathTo API");
            invoke(elytraPathTo, elytraProcess(), destination);
        }

        boolean elytraIsActive() throws Exception {
            if (elytraIsActive == null) return false;
            return Boolean.TRUE.equals(invoke(elytraIsActive, elytraProcess()));
        }

        boolean elytraLoaded() throws Exception {
            if (elytraIsLoaded == null) return false;
            return Boolean.TRUE.equals(invoke(elytraIsLoaded, elytraProcess()));
        }

        Object elytraCurrentDestination() throws Exception {
            if (elytraCurrentDestination == null) return null;
            return invoke(elytraCurrentDestination, elytraProcess());
        }

        void elytraOnLostControl() throws Exception {
            if (elytraOnLostControl == null) return;
            invoke(elytraOnLostControl, elytraProcess());
        }

        Object goalNear(BlockPos destination, int range) throws Exception {
            return goalNearConstructor.newInstance(destination, range);
        }

        void setGoalAndPath(Object goal) throws Exception {
            Object process = invoke(getCustomGoalProcess, baritone);
            invoke(setGoalAndPath, process, goal);
        }

        boolean isPathing() throws Exception {
            Object behavior = invoke(getPathingBehavior, baritone);
            return Boolean.TRUE.equals(invoke(isPathing, behavior));
        }

        boolean hasGoal() throws Exception {
            if (getGoal == null) return false;
            Object behavior = invoke(getPathingBehavior, baritone);
            Object value = invoke(getGoal, behavior);
            return value instanceof Optional<?> optional && optional.isPresent();
        }

        boolean hasCurrentPath() throws Exception {
            if (getCurrentPath == null) return false;
            Object behavior = invoke(getPathingBehavior, baritone);
            Object value = invoke(getCurrentPath, behavior);
            return value instanceof Optional<?> optional && optional.isPresent();
        }

        boolean customGoalActive() throws Exception {
            if (customGoalIsActive == null) return false;
            Object process = invoke(getCustomGoalProcess, baritone);
            return Boolean.TRUE.equals(invoke(customGoalIsActive, process));
        }

        void cancelEverything() throws Exception {
            Object behavior = invoke(getPathingBehavior, baritone);
            invoke(cancelEverything, behavior);
        }

        private static Object invoke(Method method, Object target, Object... args) throws Exception {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception checked) throw checked;
                if (cause instanceof Error fatal) throw fatal;
                throw exception;
            }
        }
    }
}
