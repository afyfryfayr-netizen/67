package sleepy.autobuilder.autobuild;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import sleepy.autobuilder.SevenBuilderAddon;
import sleepy.autobuilder.autobuild.core.BlockCategory;
import sleepy.autobuilder.autobuild.core.BlockKey;
import sleepy.autobuilder.autobuild.core.Box3i;
import sleepy.autobuilder.autobuild.core.BuildMode;
import sleepy.autobuilder.autobuild.core.CorrectionRules;
import sleepy.autobuilder.autobuild.core.LayerAxis;
import sleepy.autobuilder.autobuild.core.LayerOrder;
import sleepy.autobuilder.autobuild.core.LayerPlanner;
import sleepy.autobuilder.autobuild.core.MaterialPlanner;
import sleepy.autobuilder.autobuild.core.MismatchAction;
import sleepy.autobuilder.autobuild.core.MismatchDecision;
import sleepy.autobuilder.autobuild.core.MismatchKind;
import sleepy.autobuilder.autobuild.core.PositionCodec;
import sleepy.autobuilder.autobuild.core.ProgressReconciler;
import sleepy.autobuilder.autobuild.core.ProgressStore;
import sleepy.autobuilder.autobuild.core.PlacementScheduler;
import sleepy.autobuilder.autobuild.core.RuleContext;
import sleepy.autobuilder.autobuild.core.ScopeRules;
import sleepy.autobuilder.autobuild.core.StateSignature;
import sleepy.autobuilder.autobuild.mc.BreakService;
import sleepy.autobuilder.autobuild.mc.InventoryService;
import sleepy.autobuilder.autobuild.mc.LitematicaBridge;
import sleepy.autobuilder.autobuild.mc.McInteract;
import sleepy.autobuilder.autobuild.mc.McStates;
import sleepy.autobuilder.autobuild.mc.PlacementService;
import sleepy.autobuilder.autobuild.mc.RestockService;
import sleepy.autobuilder.autobuild.mc.SchematicAccess;
import sleepy.autobuilder.autobuild.mc.ShulkerService;
import sleepy.autobuilder.autobuild.mc.TravelService;
import sleepy.autobuilder.manager.PlacementManager;
import sleepy.autobuilder.autobuild.rules.CorrectionRuleParser;
import sleepy.autobuilder.autobuild.rules.PlacementRules;
import sleepy.autobuilder.autobuild.storage.StorageEntry;
import sleepy.autobuilder.autobuild.storage.StorageManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runtime state machine for the fully automatic builder.
 *
 * <p>The engine is deliberately conservative: every placement is re-read from the world, every
 * break runs through {@link BreakService}, and every recovery action is bounded. Death, low health,
 * nearby players, screens and disconnects pause instead of continuing blindly.</p>
 */
public final class AutoBuildEngine {
    public enum State {
        IDLE("Ready"),
        SUSPENDED("Disconnected"),
        SCANNING("Scanning"),
        BUILDING("Building"),
        RESTOCKING("Restocking"),
        SHULKER("Shulker unloading"),
        COMPLETE("Complete"),
        FAILED("Failed"),
        STOPPED("Stopped");

        private final String title;

        State(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    private enum Phase {BREAK, PLACE, VERIFY, TRAVEL}

    private static final AutoBuildEngine INSTANCE = new AutoBuildEngine();
    private static final int MAX_REPORT_MESSAGES = 80;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Path progressFile = MeteorClient.FOLDER.toPath()
            .resolve("seven-builder").resolve("progress.json");
    private final BreakService breaker = BreakService.get();
    private final Set<Long> reported = new HashSet<>();
    private final Map<Long, Work> work = new HashMap<>();
    private final Map<Long, Integer> unavailableChecks = new HashMap<>();
    private final Map<Long, Long> unavailableDue = new HashMap<>();
    private final Map<String, Integer> required = new LinkedHashMap<>();
    private final Map<String, Integer> requiredRemaining = new LinkedHashMap<>();
    private final List<BlockPos> extraBlocks = new ArrayList<>();

    private AutoBuilder module;
    private State state = State.IDLE;
    private String statusDetail = "";
    private String lastError = "";
    private String pauseReason = "";
    private boolean userPaused;
    private boolean deathPaused;
    private boolean suspended;
    private boolean resumeOnJoin;
    private int joinResumeTicks;
    private boolean restartRequested;
    private String restartReason = "";
    private boolean rulesDirty = true;
    private long tickCounter;
    private long lastRulesBuildMs;
    private long lastSaveMs;
    private long autoSaveDueTick;

    private SchematicAccess.PlacementSnapshot snapshot;
    private Box3i region;
    private List<Integer> layers = List.of();
    private int layerIndex;
    private List<int[]> positions = List.of();
    private int positionIndex;
    private int extraCursor;
    private String sessionKey = "";
    private ProgressStore store;
    private ProgressStore.SessionProgress progress;
    private CorrectionRules rules;
    private PlacementRules placementRules = PlacementRules.parse(List.of());
    private MaterialPlanner.MaterialPlan materialPlan =
            MaterialPlanner.plan(Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    private int scanLayerIndex;
    private List<int[]> scanPositions;
    private long scannedPositions;
    private long scanVolume;
    private long scanExpected;

    private Target target;
    private final Map<Long, Target> verifying = new LinkedHashMap<>();
    private int actionsThisTick;
    private long lastPlacementTick;
    private final Map<String, Integer> looseCache = new LinkedHashMap<>();
    private long looseCacheTick = Long.MIN_VALUE;
    private long placedThisSession;
    private long sessionStartedMs;
    private boolean lastSaveFailed;
    private String startWarning = "";
    private TravelService siteTravel;
    private boolean siteTravelFailed;

    private RestockService restockService;
    private int restockRuns;
    private boolean restockDisabled;
    private int restockFailureCooldown;

    private ShulkerService shulkerService;
    private String shulkerWanted;
    private final Set<String> shulkerFailedItems = new HashSet<>();
    private String pendingWarning = "";

    private AutoBuildEngine() {
    }

    public static AutoBuildEngine get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    public void onModuleActivated(AutoBuilder module) {
        this.module = module;
        if (state == State.SUSPENDED) {
            resumeOnJoin = true;
            return;
        }
        if (state == State.BUILDING || state == State.SCANNING || state == State.RESTOCKING || state == State.SHULKER) {
            // Already running; a screen close or a reconnect should not start a second job.
            if (userPaused && pauseReason.startsWith("module disabled")) {
                userPaused = false;
                pauseReason = "";
            }
            return;
        }
        if (module.resumeProgress.get() && module.autoResumeSaved.get() && Utils.canUpdate()
                && LitematicaBridge.snapshot() != null && hasSavedPendingSession()) {
            resumeOnJoin = true;
            joinResumeTicks = 0;
            message("Incomplete saved progress found; resuming after the schematic world is ready.");
            return;
        }
        if (module.startOnActivate.get()) {
            start(module);
        }
    }

    public void onModuleDeactivated(AutoBuilder module) {
        this.module = module;
        if (state == State.IDLE || state == State.COMPLETE || state == State.FAILED || state == State.STOPPED) {
            saveProgress(false);
            return;
        }
        // Meteor reports a disconnect through onDeactivate as well. Preserve the job so the same
        // progress can be resumed when the client joins a world again.
        if (mc.player == null || mc.world == null) {
            onGameLeft();
            return;
        }
        if (shulkerService != null && shulkerService.isActive()) {
            // Ticks stop when the module is disabled, so an in-flight shulker cycle can no longer
            // finish safely. Abort leaves the placed shulker in the world and reports its position.
            shulkerService.abort();
            String abortMessage = shulkerService.error();
            shulkerService = null;
            state = State.BUILDING;
            if (abortMessage != null && !abortMessage.isBlank()) {
                lastError = abortMessage;
                message("Module disabled during shulker handling. " + abortMessage);
            }
        }
        pauseInternal("module disabled");
    }

    public void onGameLeft() {
        boolean wasRunning = state == State.BUILDING || state == State.SCANNING
                || state == State.RESTOCKING || state == State.SHULKER;
        if (!wasRunning) {
            saveProgress(false);
            return;
        }
        resumeOnJoin = false;
        if (shulkerService != null && shulkerService.isActive()) {
            shulkerService.abort();
            String note = shulkerService.error();
            shulkerService = null;
            if (note != null && !note.isBlank()) {
                lastError = note;
                pendingWarning = note;
                SevenBuilderAddon.LOG.warn("Auto Builder disconnect during shulker handling: {}", note);
            }
        }
        cancelActiveWork(true);
        saveProgress(true);
        if (lastSaveFailed) {
            pendingWarning = "progress could not be saved before disconnect; check the log";
        }
        suspended = true;
        state = State.SUSPENDED;
        statusDetail = "disconnected; progress saved";
        pauseReason = "disconnected";
    }

    public void onGameJoined() {
        if (module == null || !module.isActive()) return;
        if (suspended) {
            resumeOnJoin = true;
            joinResumeTicks = 0;
            suspended = false;
            return;
        }
        if (state == State.IDLE && module.resumeProgress.get() && module.autoResumeSaved.get()
                && hasSavedPendingSession()) {
            resumeOnJoin = true;
            joinResumeTicks = 0;
        }
    }

    public boolean start(AutoBuilder module) {
        this.module = module;
        if (state == State.BUILDING || state == State.SCANNING || state == State.RESTOCKING || state == State.SHULKER) {
            message("A build is already running. Use pause or stop first.");
            return false;
        }
        resetRuntimeForStart();
        if (!Utils.canUpdate()) {
            failStart("join a world before starting");
            return false;
        }
        if (!LitematicaBridge.isAvailable()) {
            failStart("Litematica is not installed or its API could not be loaded");
            return false;
        }
        snapshot = LitematicaBridge.snapshot();
        if (snapshot == null) {
            failStart("no Litematica placement is selected");
            return false;
        }
        if (!LitematicaBridge.isSchematicWorldReady()) {
            failStart("Litematica's schematic world is not ready; wait for it to load and try again");
            return false;
        }
        startWarning = "";
        region = resolveRegion();
        if (region == null) return false;
        long volume = region.volume();
        if (volume > module.maxScanVolume.get()) {
            failStart("selected box has " + volume + " positions, above max-scan-volume "
                    + module.maxScanVolume.get() + "; use a layer range or selected region");
            return false;
        }

        buildRules(true);
        sessionKey = buildSessionKey();
        loadStore();
        if (!module.resumeProgress.get()) store.remove(sessionKey);
        progress = store.find(sessionKey);
        boolean adopted = false;
        if (progress == null && module.resumeProgress.get()) {
            progress = store.findCompatible(schematicName(), currentDimension(),
                    snapshot.origin().getX(), snapshot.origin().getY(), snapshot.origin().getZ(), region);
            if (progress != null) {
                progress = store.adopt(progress, sessionKey);
                adopted = true;
            }
        }
        if (progress == null) progress = store.session(sessionKey);
        layers = LayerPlanner.layers(region, module.layerAxis.get(), module.layerOrder.get());
        if (layers.isEmpty()) {
            failStart("the selected build region contains no layers");
            return false;
        }
        ProgressReconciler.Result reconciled = ProgressReconciler.reconcile(progress, region,
                module.layerAxis.get(), layers.getFirst(), layers.getLast(), worldBottom(), worldTop());
        if (reconciled.changed()) {
            store.markDirty();
            message("Progress reconciled: removed " + reconciled.removed()
                    + " stale position(s), layer " + reconciled.layerBefore()
                    + " -> " + reconciled.layerAfter() + ".");
        }
        initialiseProgressMetadata();
        ensureBuildAnchor();
        if (adopted) message("Recovered saved progress from a previous session key.");
        if (!startWarning.isBlank()) message(startWarning);

        required.clear();
        requiredRemaining.clear();
        extraBlocks.clear();
        work.clear();
        verifying.clear();
        unavailableChecks.clear();
        unavailableDue.clear();
        reported.clear();
        shulkerFailedItems.clear();
        looseCache.clear();
        looseCacheTick = Long.MIN_VALUE;
        lastPlacementTick = tickCounter - Math.max(0, module.placementDelayTicks.get());
        placedThisSession = 0;
        sessionStartedMs = System.currentTimeMillis();
        lastSaveFailed = false;
        restockRuns = 0;
        restockDisabled = false;
        shulkerWanted = null;

        state = State.SCANNING;
        scanLayerIndex = 0;
        scannedPositions = 0;
        scanExpected = 0;
        scanVolume = volume;
        scanPositions = null;
        statusDetail = "scanning " + snapshot.schematicName();
        userPaused = false;
        pauseReason = "";
        lastError = "";
        autoSaveDueTick = tickCounter + Math.max(5, module.autoSaveSeconds.get()) * 20L;
        lastSaveMs = System.currentTimeMillis();
        message("Started: " + snapshot.schematicName() + " " + region + " ("
                + volume + " positions, " + layers.size() + " layers, "
                + progress.pending.size() + " pending)");
        if (!pendingWarning.isBlank()) {
            message("Previous shutdown note: " + pendingWarning);
            pendingWarning = "";
        }
        return true;
    }

    public void pause(String reason) {
        pauseInternal(reason == null || reason.isBlank() ? "paused" : reason);
    }

    private void pauseInternal(String reason) {
        if (state == State.IDLE || state == State.COMPLETE || state == State.FAILED || state == State.STOPPED) return;
        userPaused = true;
        pauseReason = reason;
        breaker.stop(true);
        if (currentTravel != null) {
            currentTravel.cancel();
            currentTravel = null;
            if (target != null && target.phase == Phase.TRAVEL) target.phase = Phase.PLACE;
        }
        if (siteTravel != null) {
            siteTravel.cancel();
            siteTravel = null;
        }
        if (restockService != null && restockService.isActive()) {
            restockService.cancel();
            restockService = null;
            state = State.BUILDING;
        }
        if (shulkerService != null && shulkerService.isActive()) {
            // Finishing the shulker prevents leaving a placed box behind. The service is ticked
            // even while paused until it reaches DONE or FAILED.
            message("Pause requested; finishing the current shulker cycle safely first.");
        }
        saveProgress(true);
        message("Paused (" + reason + "). " + saveNote());
    }

    /**
     * Stops movement/mining/container work when a safety pause starts. The target itself is kept so
     * a retry can continue from the same position after the pause clears.
     */
    private void holdPausedWork() {
        breaker.stop(true);
        if (currentTravel != null) {
            currentTravel.cancel();
            currentTravel = null;
            if (target != null && target.phase == Phase.TRAVEL) target.phase = Phase.PLACE;
        }
        if (siteTravel != null) {
            siteTravel.cancel();
            siteTravel = null;
        }
        if (restockService != null && restockService.isActive()) {
            restockService.cancel();
            restockService = null;
            state = State.BUILDING;
            statusDetail = "paused during restock; the run will restart after resume";
        }
    }

    public void resume() {
        if (module == null) {
            message("Auto Builder is not available.");
            return;
        }
        userPaused = false;
        pauseReason = "";
        deathPaused = false;
        lastError = "";
        // A manual resume is an explicit request to retry travel once after a failed return trip.
        siteTravelFailed = false;
        if (state == State.SUSPENDED) {
            resumeOnJoin = true;
            joinResumeTicks = 0;
            return;
        }
        if (state == State.IDLE || state == State.STOPPED || state == State.FAILED || state == State.COMPLETE) {
            start(module);
            return;
        }
        if (mc.player == null || mc.world == null) {
            userPaused = true;
            pauseReason = "not in a world";
            message("Cannot resume: join a world first.");
            return;
        }
        if (state == State.SCANNING) {
            message("Resumed.");
            return;
        }

        String blocked = resumeBlockedReason();
        if (blocked != null) {
            userPaused = true;
            pauseReason = "resume blocked";
            message("Cannot resume: " + blocked);
            return;
        }

        SchematicAccess.PlacementSnapshot fresh = LitematicaBridge.snapshot();
        String freshName = fresh == null ? "" : placementName(fresh);
        if (snapshot == null || region == null || progress == null || layers.isEmpty()
                || fresh == null || !freshName.equals(schematicName())
                || !fresh.origin().equals(snapshot.origin())
                || !currentDimension().equals(progress.dimension)) {
            message("Schematic, origin, region or dimension changed; rebuilding the job from saved progress.");
            restartJob();
            return;
        }

        ProgressReconciler.Result reconciled = ProgressReconciler.reconcile(progress, region,
                module.layerAxis.get(), layers.getFirst(), layers.getLast(), worldBottom(), worldTop());
        if (reconciled.changed()) {
            store.markDirty();
            saveProgress(true);
            message("Resume validation removed " + reconciled.removed() + " stale position(s) and clamped layer "
                    + reconciled.layerBefore() + " -> " + reconciled.layerAfter() + ".");
        }
        int bottom = worldBottom();
        int top = worldTop();
        verifying.entrySet().removeIf(entry -> {
            Target current = entry.getValue();
            return current == null || !region.contains(current.pos.getX(), current.pos.getY(),
                    current.pos.getZ()) || current.pos.getY() < bottom || current.pos.getY() > top;
        });
        work.keySet().removeIf(packed -> !progress.pending.contains(packed)
                && !verifying.containsKey(packed));
        unavailableDue.keySet().removeIf(packed -> !work.containsKey(packed));
        unavailableChecks.keySet().removeIf(packed -> !work.containsKey(packed));

        if (progress.pending.isEmpty() && verifying.isEmpty()
                && restockService == null
                && (shulkerService == null || !shulkerService.isActive())) {
            finalizeBuild();
            return;
        }
        if (work.isEmpty() && !progress.pending.isEmpty()) {
            message("Pending work state was lost; rebuilding it from saved progress.");
            restartJob();
            return;
        }
        message("Resumed; " + progress.pending.size() + " pending position(s) validated.");
    }

    /** Returns a reason resume cannot continue, or null when the current job is still valid. */
    private String resumeBlockedReason() {
        if (!LitematicaBridge.isAvailable()) {
            return "Litematica is not installed or its API could not be loaded";
        }
        if (LitematicaBridge.snapshot() == null) {
            return "no Litematica placement is selected";
        }
        if (!LitematicaBridge.isSchematicWorldReady()) {
            return "Litematica's schematic world is not ready";
        }
        if (progress == null || region == null || layers.isEmpty()) {
            return "the job has no saved region/layer state; start it again to rebuild";
        }
        return null;
    }

    public void stop(String reason) {
        String shulkerNote = null;
        if (shulkerService != null) {
            shulkerService.abort();
            shulkerNote = shulkerService.error();
        }
        cancelActiveWork(true);
        saveProgress(true);
        state = State.STOPPED;
        statusDetail = "stopped: " + (reason == null ? "user" : reason);
        userPaused = false;
        pauseReason = "";
        message("Stopped safely. " + saveNote());
        if (shulkerNote != null && !shulkerNote.isBlank()) {
            lastError = shulkerNote;
            message("Stopped during shulker handling. " + shulkerNote);
        }
    }

    /** Called by structural settings. The running job is rebuilt on the next safe tick. */
    public void invalidateJob() {
        if (state == State.BUILDING || state == State.SCANNING || state == State.RESTOCKING || state == State.SHULKER) {
            restartRequested = true;
            restartReason = "settings changed";
        }
    }

    public void invalidateRules() {
        rulesDirty = true;
    }

    public void reloadJob() {
        if (module == null) return;
        restartRequested = true;
        restartReason = "reload requested";
    }

    public void recalculateMaterials() {
        refreshMaterialPlan();
        if (materialPlan.lines().isEmpty()) {
            message("No materials are required yet. Start a build to scan the schematic.");
            return;
        }
        if (materialPlan.complete()) {
            message("Materials OK: " + materialPlan.totalAvailable() + " available / "
                    + materialPlan.totalRequired() + " required.");
            return;
        }
        StringBuilder line = new StringBuilder("Missing materials: ");
        List<MaterialPlanner.MaterialLine> missing = materialPlan.missingLines();
        for (int i = 0; i < Math.min(8, missing.size()); i++) {
            MaterialPlanner.MaterialLine entry = missing.get(i);
            if (i > 0) line.append(", ");
            line.append(shortId(entry.itemId())).append(" ").append(entry.remaining());
        }
        if (missing.size() > 8) line.append(", +").append(missing.size() - 8).append(" more");
        message(line.toString());
    }

    /** Starts material recovery now (shulker first, then configured storage waypoints). */
    public boolean requestRestock() {
        if (module == null) {
            message("Auto Builder is not available.");
            return false;
        }
        if (userPaused || !pauseReason.isEmpty()) {
            message("Resume the build before requesting a restock.");
            return false;
        }
        if (state != State.BUILDING) {
            message("Restock is only available while building (current state: " + state.title() + ").");
            return false;
        }
        if (progress == null) {
            message("Start a build before requesting a restock.");
            return false;
        }
        if (!triggerRecovery(true)) {
            message("Restock did not start: no carried shulker covers a missing material, "
                    + "no enabled storage waypoint is in this dimension, restocking is disabled, "
                    + "or the retry limit was reached.");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    public void tick() {
        if (module == null) return;
        if (resumeOnJoin) {
            tickJoinResume();
            return;
        }
        if (mc.player == null || mc.world == null) return;
        tickCounter++;
        actionsThisTick = 0;

        if (state == State.IDLE || state == State.SUSPENDED || state == State.COMPLETE
                || state == State.FAILED || state == State.STOPPED) {
            return;
        }
        if (!rulesDirty && module.rulesRefreshSeconds.get() > 0
                && System.currentTimeMillis() - lastRulesBuildMs
                >= module.rulesRefreshSeconds.get() * 1000L) {
            rulesDirty = true;
        }
        if (rulesDirty) buildRules(false);

        updateSafetyPause();
        if (userPaused || !pauseReason.isEmpty()) {
            boolean screenBlocked = mc.currentScreen != null && !ownsScreen();
            if (state == State.SHULKER && !screenBlocked) {
                // Finishing the shulker prevents leaving a placed box behind. Never start a new
                // recovery action while paused.
                tickShulker();
            } else if (state == State.SHULKER) {
                statusDetail = "shulker paused while a screen is open";
            } else {
                holdPausedWork();
            }
            return;
        }
        if (module.pauseOnScreen.get() && mc.currentScreen != null && !ownsScreen()) {
            if (!module.autoResumeAfterScreen.get()) {
                userPaused = true;
                saveProgress(true);
            }
            pauseReason = "screen open";
            return;
        }

        if (restartRequested) {
            if (state == State.SHULKER || ownsScreen()) {
                // Finish (or safely leave) the shulker/container cycle before rebuilding.
                return;
            }
            restartRequested = false;
            restartJob();
            return;
        }

        if (tickCounter >= autoSaveDueTick) {
            autoSaveDueTick = tickCounter + Math.max(5, module.autoSaveSeconds.get()) * 20L;
            saveProgress(false);
        }
        if (state == State.BUILDING && tickCounter % 40 == 0) refreshMaterialPlan();

        switch (state) {
            case SCANNING -> tickScan();
            case BUILDING -> tickBuild();
            case RESTOCKING -> tickRestock();
            case SHULKER -> tickShulker();
            default -> {
            }
        }
    }

    private void tickJoinResume() {
        if (mc.player == null || mc.world == null) return;
        joinResumeTicks++;
        if (LitematicaBridge.snapshot() != null && LitematicaBridge.isSchematicWorldReady()) {
            resumeOnJoin = false;
            if (start(module)) {
                message("Resumed after reconnect.");
            }
            return;
        }
        if (joinResumeTicks > 600) {
            resumeOnJoin = false;
            state = State.STOPPED;
            statusDetail = "could not resume: Litematica placement/schematic world was not ready within 30 seconds";
            message(statusDetail);
        }
    }

    private void restartJob() {
        saveProgress(true);
        cancelActiveWork(true);
        message("Rebuilding job: " + restartReason);
        state = State.IDLE;
        start(module);
    }

    private void updateSafetyPause() {
        if (userPaused) return;
        String reason = "";
        if (mc.player.isDead()) {
            if (!module.pauseOnDeath.get()) return;
            if (!deathPaused) saveProgress(true);
            deathPaused = true;
            reason = "player died";
        } else if (deathPaused) {
            if (module.autoResumeAfterDeath.get()) {
                deathPaused = false;
                reason = "";
                if (state == State.BUILDING) travelToBuildSiteIfNeeded();
            } else {
                reason = "death pause - press resume";
            }
        } else if (module.pauseOnLowHealth.get() && mc.player.getHealth() <= module.minHealth.get()) {
            reason = "low health (" + Math.round(mc.player.getHealth()) + ")";
        } else if (module.pauseOnLowAir.get() && mc.player.isSubmergedInWater()
                && mc.player.getAir() < module.minAir.get()) {
            reason = "low air (" + mc.player.getAir() + ")";
        } else if (module.pauseNearPlayers.get()) {
            double radius = module.playerRadius.get();
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || player.isDead()) continue;
                if (player.squaredDistanceTo(mc.player) <= radius * radius) {
                    reason = "player nearby";
                    break;
                }
            }
        }
        pauseReason = reason;
    }

    private boolean ownsScreen() {
        return restockService != null && restockService.ownsScreen()
                || shulkerService != null && shulkerService.ownsScreen();
    }

    // ---------------------------------------------------------------------------------------------
    // Schematic scan
    // ---------------------------------------------------------------------------------------------

    private void tickScan() {
        int budget = Math.max(500, module.scanBudgetPerTick.get());
        int processed = 0;
        while (processed < budget) {
            if (scanLayerIndex >= layers.size()) {
                finishScan();
                return;
            }
            int coordinate = layers.get(scanLayerIndex);
            if (scanPositions == null) {
                scanPositions = scanPositions(coordinate);
                positionIndex = 0;
            }
            while (positionIndex < scanPositions.size() && processed < budget) {
                int[] xyz = scanPositions.get(positionIndex++);
                scanPosition(xyz[0], xyz[1], xyz[2]);
                processed++;
                scannedPositions++;
            }
            if (positionIndex >= scanPositions.size()) {
                scanPositions = null;
                scanLayerIndex++;
            }
        }
        statusDetail = "scanning " + percent(scannedPositions, scanVolume) + " ("
                + scannedPositions + "/" + scanVolume + ")";
    }

    private List<int[]> scanPositions(int coordinate) {
        LayerAxis axis = module.layerAxis.get();
        Box3i layer = LayerPlanner.layerBox(region, axis, coordinate);
        List<int[]> result = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, layer.volume()));
        for (int y = layer.minY(); y <= layer.maxY(); y++) {
            for (int z = layer.minZ(); z <= layer.maxZ(); z++) {
                for (int x = layer.minX(); x <= layer.maxX(); x++) result.add(new int[]{x, y, z});
            }
        }
        return result;
    }

    private void scanPosition(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        long packed = PositionCodec.pack(x, y, z);
        BlockState expected = LitematicaBridge.expectedState(pos);
        if (expected == null) return;
        BlockState actual = mc.world.getBlockState(pos);
        if (expected.isAir()) {
            if (actual.isAir()) {
                if (progress.pending.contains(packed) || progress.failed.contains(packed)) {
                    progress.clearPosition(packed);
                }
                return;
            }
            progress.clearPosition(packed);
            if (!module.removeExtraBlocks.get()) return;
            MismatchDecision decision = classify(expected, actual, packed);
            if (decision.needsRemoval() && !isForbiddenBreak(actual, pos)) {
                progress.markPending(packed);
                extraBlocks.add(pos.toImmutable());
            } else if (decision.action() == MismatchAction.REPORT) {
                progress.markIgnored(packed);
                reportOnce(packed, "Reported extra block at " + pass(pos) + ": " + decision.reason());
            }
            return;
        }
        scanExpected++;
        progress.clearPosition(packed);
        String itemId = McStates.itemId(expected);
        if (itemId == null) {
            progress.markIgnored(packed);
            reportOnce(packed, "Skipping " + shortBlock(expected) + ": it has no placeable item");
            return;
        }
        MismatchDecision decision = classify(expected, actual, packed);
        if (decision.kind() == MismatchKind.NONE) {
            progress.markCompleted(packed);
        } else if (decision.isIgnored() || decision.action() == MismatchAction.REPORT) {
            progress.markIgnored(packed);
            if (decision.action() == MismatchAction.REPORT) {
                reportOnce(packed, "Reported " + pass(pos) + ": " + decision.reason());
            }
        } else {
            progress.markPending(packed);
            required.merge(itemId, 1, Integer::sum);
        }
    }

    private void finishScan() {
        requiredRemaining.clear();
        requiredRemaining.putAll(required);
        refreshMaterialPlan();
        scanPositions = null;
        layerIndex = 0;
        positions = List.of();
        positionIndex = 0;
        extraCursor = 0;
        invalidateLooseCache();
        store.markDirty();
        saveProgress(false);
        state = State.BUILDING;
        statusDetail = "scan complete: " + progress.completed.size() + " correct, "
                + progress.pending.size() + " pending, " + progress.ignored.size() + " ignored";
        message("Scan complete. " + progress.pending.size() + " positions need work; "
                + materialPlan.totalRemaining() + " items missing.");
        if (progress.pending.isEmpty() && extraBlocks.isEmpty()) finalizeBuild();
    }

    // ---------------------------------------------------------------------------------------------
    // Building
    // ---------------------------------------------------------------------------------------------

    private void tickBuild() {
        // Verifications are independent of the serial break/travel target. Check them first so a
        // completed placement is committed even while the next target is being prepared.
        tickVerifications();
        if (target != null) {
            tickTarget();
            return;
        }
        if (!pauseReason.isEmpty()) return;
        if (siteTravel != null && siteTravel.isActive()) {
            tickSiteTravel();
            return;
        }
        if (!siteTravelFailed && travelToBuildSiteIfNeeded()) return;
        if (tryDueUnavailable()) return;
        if (state != State.BUILDING) return;

        PlacementScheduler.Limits limits = placementLimits();
        if (!placementAllowed(limits)) return;

        if (extraCursor < extraBlocks.size()) {
            BlockPos pos = extraBlocks.get(extraCursor++);
            if (startPosition(pos, null, true)) return;
            if (state != State.BUILDING) return;
            if (actionsThisTick >= limits.actionsPerTick()) return;
        }

        if (!module.continueWithAvailable.get() && waitingForMaterials()) {
            statusDetail = "waiting for materials (continue-with-available is off)";
            return;
        }

        int inspected = 0;
        int limit = Math.max(1000, module.scanBudgetPerTick.get());
        while (true) {
            if (!placementAllowed(limits)) return;
            if (inspected++ >= limit) {
                statusDetail = "building; skipping already-correct positions";
                return;
            }
            if (positions.isEmpty()) {
                if (!advanceLayer()) return;
            }
            if (positionIndex >= positions.size()) {
                finishLayer();
                return;
            }
            int[] xyz = positions.get(positionIndex++);
            BlockPos pos = new BlockPos(xyz[0], xyz[1], xyz[2]);
            BlockState expected = LitematicaBridge.expectedState(pos);
            if (expected == null || expected.isAir()) continue;
            long packed = PositionCodec.pack(xyz[0], xyz[1], xyz[2]);
            if (progress.isCompleted(packed) || progress.isIgnored(packed) || progress.failed.contains(packed)) {
                continue;
            }
            if (verifying.containsKey(packed)) continue;
            if (startPosition(pos, expected, false)) return;
            if (state != State.BUILDING) return;
        }
    }

    private PlacementScheduler.Limits placementLimits() {
        return new PlacementScheduler.Limits(module.actionsPerTick.get(),
                module.maxPendingVerifications.get(), module.placementDelayTicks.get(),
                module.retryDelayTicks.get(), module.verifyDelayTicks.get());
    }

    /** Returns false when the packet quota, work budget or verification window is saturated. */
    private boolean placementAllowed(PlacementScheduler.Limits limits) {
        PlacementScheduler.Decision decision = PlacementScheduler.canStart(actionsThisTick,
                verifying.size(), PlacementManager.get().getRemainingQuota(), tickCounter,
                lastPlacementTick, limits);
        if (decision.allowed()) return true;
        switch (decision.reason()) {
            case PACKET_QUOTA -> statusDetail = "waiting for the server packet quota";
            case VERIFICATION_WINDOW -> statusDetail = "waiting for "
                    + verifying.size() + " in-flight placement(s) to verify";
            case PLACEMENT_DELAY -> statusDetail = "pacing placements ("
                    + limits.placementDelayTicks() + " tick delay)";
            case ACTION_BUDGET -> {
                // Expected every tick; do not overwrite a useful status line.
            }
            default -> {
            }
        }
        return false;
    }

    private boolean waitingForMaterials() {
        if (unavailableDue.isEmpty()) return false;
        for (Long packed : unavailableDue.keySet()) {
            Work item = work.get(packed);
            if (item == null || item.itemId == null) continue;
            if (looseCount(item.itemId) <= 0) return true;
        }
        return false;
    }

    /** Cached loose-item counts; refreshed at most once every ten ticks and decremented on use. */
    private int looseCount(String itemId) {
        refreshLooseCacheIfNeeded();
        return looseCache.getOrDefault(itemId, 0);
    }

    private void refreshLooseCacheIfNeeded() {
        long now = tickCounter;
        if (looseCacheTick != Long.MIN_VALUE && now - looseCacheTick < 10) return;
        looseCache.clear();
        looseCache.putAll(InventoryService.looseCounts());
        looseCacheTick = now;
    }

    private void consumeCachedItem(String itemId) {
        if (itemId == null) return;
        refreshLooseCacheIfNeeded();
        Integer count = looseCache.get(itemId);
        if (count == null) return;
        if (count <= 1) looseCache.remove(itemId);
        else looseCache.put(itemId, count - 1);
    }

    private void invalidateLooseCache() {
        looseCacheTick = Long.MIN_VALUE;
    }

    private boolean advanceLayer() {
        while (layerIndex < layers.size()) {
            int coordinate = layers.get(layerIndex);
            BlockPos player = mc.player == null ? BlockPos.ORIGIN : mc.player.getBlockPos();
            positions = LayerPlanner.positions(region, module.layerAxis.get(), coordinate,
                    module.constructionOrder.get(), player.getX(), player.getY(), player.getZ());
            positionIndex = 0;
            progress.currentLayer = coordinate;
            statusDetail = "layer " + coordinate + " (" + (layerIndex + 1) + "/" + layers.size() + ")";
            if (!positions.isEmpty()) return true;
            layerIndex++;
        }
        if (layerIndex >= layers.size()) {
            if (!verifying.isEmpty()) {
                statusDetail = "waiting for " + verifying.size()
                        + " in-flight placement(s) before finishing";
                return false;
            }
            if (waitForRemainingMaterials()) return false;
            finalizeBuild();
            return false;
        }
        return false;
    }

    private void finishLayer() {
        if (!verifying.isEmpty()) {
            statusDetail = "verifying " + verifying.size() + " placement(s) before the next layer";
            return;
        }
        positions = List.of();
        positionIndex = 0;
        layerIndex++;
        store.markDirty();
        saveProgress(false);
        if (layerIndex >= layers.size()) {
            if (waitForRemainingMaterials()) return;
            finalizeBuild();
            return;
        }
        progress.currentLayer = layers.get(layerIndex);
        statusDetail = "layer complete; preparing layer " + layers.get(layerIndex);
        triggerRecovery(true);
    }

    /**
     * Keeps a finished layer set alive while unavailable positions still have a bounded recheck
     * pending. Without this, a temporary material shortage at the last layer would end the job
     * before the player has a chance to restock. Rechecks eventually turn into failures, so this
     * cannot loop forever.
     */
    private boolean waitForRemainingMaterials() {
        if (progress.pending.isEmpty()) return false;
        boolean hasWork = false;
        for (Long packed : progress.pending) {
            Work item = work.get(packed);
            if (item != null && item.itemId != null) {
                hasWork = true;
                break;
            }
        }
        if (!hasWork) return false;
        if (unavailableDue.isEmpty()) reschedulePendingWork(false);
        if (tickCounter % 20 != 0) {
            statusDetail = "waiting for materials: " + progress.pending.size() + " positions pending";
            return true;
        }
        if (tryDueUnavailable()) return true;
        if (triggerRecovery(false)) return true;
        statusDetail = "waiting for materials: " + progress.pending.size()
                + " positions pending; rechecking every "
                + module.unavailableRecheckSeconds.get() + "s";
        return true;
    }

    private boolean startPosition(BlockPos pos, BlockState expectedHint, boolean extra) {
        long packed = PositionCodec.pack(pos.getX(), pos.getY(), pos.getZ());
        // A position that is already waiting for its placement to verify must never be started a
        // second time; that would double-count the item and send duplicate placement packets.
        if (verifying.containsKey(packed)) return false;
        BlockState expected = expectedHint;
        if (expected == null && !extra) expected = LitematicaBridge.expectedState(pos);
        BlockState actual = mc.world.getBlockState(pos);
        if (extra && (actual.isAir() || actual.isReplaceable())) {
            progress.markCompleted(packed);
            return false;
        }
        MismatchDecision decision = extra
                ? classifyAir(actual, pos)
                : classify(expected, actual, packed);
        if (!extra) {
            if (decision.kind() == MismatchKind.NONE) {
                progress.markCompleted(packed);
                decrementRequired(expected);
                return false;
            }
            if (decision.isIgnored() || decision.action() == MismatchAction.REPORT) {
                progress.markIgnored(packed);
                if (decision.action() == MismatchAction.REPORT) {
                    reportOnce(packed, "Reported " + pass(pos) + ": " + decision.reason());
                }
                decrementRequired(expected);
                return false;
            }
        } else if (!decision.needsRemoval()) {
            progress.markIgnored(packed);
            if (decision.action() == MismatchAction.REPORT) {
                reportOnce(packed, "Reported extra block at " + pass(pos) + ": " + decision.reason());
            }
            return false;
        }

        if (decision.needsBreak() && isForbiddenBreak(actual, pos)) {
            progress.markIgnored(packed);
            reportOnce(packed, "Protected block left in place at " + pass(pos));
            decrementRequired(expected);
            return false;
        }

        Target next = new Target(packed, pos.toImmutable(), expected, extra);
        next.requiresItem = !extra && expected != null && !expected.isAir() && McStates.itemId(expected) != null;
        next.method = extra ? PlacementMethod.AUTO : resolvePlacementMethod(expected);
        target = next;
        work.put(packed, new Work(packed, next.pos, expected,
                next.requiresItem ? McStates.itemId(expected) : null));
        actionsThisTick++;
        boolean directlyReplaceable = actual.isReplaceable() || actual.getBlock() instanceof FluidBlock;
        if (decision.needsBreak() && !directlyReplaceable) {
            beginBreak(next);
        } else if (decision.needsPlace()) {
            beginPlace(next);
        } else {
            target = null;
        }
        return target != null;
    }

    private void beginBreak(Target current) {
        if (target == null) return;
        BlockState actual = mc.world.getBlockState(current.pos);
        if (!isForbiddenBreak(actual, current.pos)) {
            current.phase = Phase.BREAK;
            current.breakDeadline = tickCounter + module.breakTimeoutTicks.get();
            current.breakAttempts = 1;
            breaker.request(current.pos);
            statusDetail = "breaking " + pass(current.pos);
            return;
        }
        progress.markIgnored(current.packed);
        reportOnce(current.packed, "Protected block at " + pass(current.pos) + " was not broken");
        decrementRequired(current.expected);
        target = null;
    }

    private void beginPlace(Target current) {
        if (target == null) return;
        if (current.expected == null || current.expected.isAir()) {
            target = null;
            return;
        }
        current.phase = Phase.PLACE;
        current.verifyAt = 0;
        tickPlace(current);
    }

    private void tickTarget() {
        if (target == null) return;
        switch (target.phase) {
            case BREAK -> tickBreak(target);
            case PLACE -> tickPlace(target);
            case VERIFY -> verifyInFlight(target);
            case TRAVEL -> tickTargetTravel(target);
        }
    }

    private void tickBreak(Target current) {
        BlockState actual = mc.world.getBlockState(current.pos);
        if (current.extra) {
            if (actual.isAir() || actual.isReplaceable()) {
                completeTarget(current, false, "extra block removed");
                return;
            }
        } else if (current.expected != null) {
            MismatchDecision decision = classify(current.expected, actual, current.packed);
            if (decision.kind() == MismatchKind.NONE) {
                decrementRequired(current.expected);
                completeTarget(current, false, decision.reason());
                return;
            }
            if (decision.isIgnored() || decision.action() == MismatchAction.REPORT) {
                progress.markIgnored(current.packed);
                decrementRequired(current.expected);
                work.remove(current.packed);
                unavailableChecks.remove(current.packed);
                unavailableDue.remove(current.packed);
                if (decision.action() == MismatchAction.REPORT) {
                    reportOnce(current.packed, "Reported " + pass(current.pos) + ": " + decision.reason());
                }
                statusDetail = "ignored " + pass(current.pos) + ": " + decision.reason();
                target = null;
                breaker.stop(false);
                saveProgress(false);
                return;
            }
        }
        if (isForbiddenBreak(actual, current.pos)) {
            progress.markIgnored(current.packed);
            work.remove(current.packed);
            unavailableChecks.remove(current.packed);
            unavailableDue.remove(current.packed);
            reportOnce(current.packed, "Protected block at " + pass(current.pos) + " was not broken");
            decrementRequired(current.expected);
            target = null;
            breaker.stop(false);
            saveProgress(false);
            return;
        }
        breaker.tick();
        if (mc.world.getBlockState(current.pos).isAir()) {
            if (current.extra) {
                completeTarget(current, false, "extra block removed");
            } else if (current.requiresItem) {
                current.phase = Phase.PLACE;
                tickPlace(current);
            } else {
                completeTarget(current, false, "removed");
            }
            return;
        }
        if (tickCounter >= current.breakDeadline) {
            current.breakAttempts++;
            if (current.breakAttempts > module.maxBreakAttempts.get()) {
                failTarget(current, "break timeout at " + pass(current.pos));
                return;
            }
            breaker.stop(true);
            current.breakDeadline = tickCounter + module.breakTimeoutTicks.get();
            statusDetail = "retrying break at " + pass(current.pos);
            breaker.request(current.pos);
        }
    }

    private void tickPlace(Target current) {
        if (current.expected == null || current.expected.isAir()) {
            clearTarget(current);
            return;
        }
        String itemId = McStates.itemId(current.expected);
        if (itemId == null) {
            progress.markIgnored(current.packed);
            decrementRequired(current.expected);
            clearTarget(current);
            return;
        }
        if (looseCount(itemId) <= 0) {
            markUnavailable(current, "no loose " + shortId(itemId));
            return;
        }
        if (!McInteract.withinReach(current.pos, 4.4)) {
            if (module.autoTravelToTargets.get()) {
                int maxTravelAttempts = Math.max(1, module.targetTravelRetries.get());
                if (current.travelAttempts >= maxTravelAttempts) {
                    failTarget(current, "target remains out of reach at " + pass(current.pos));
                    return;
                }
                current.travelAttempts++;
                TravelService.StartResult started = startTravelTo(current.pos);
                if (started == TravelService.StartResult.STARTED) {
                    current.phase = Phase.TRAVEL;
                    return;
                }
                if (started == TravelService.StartResult.ALREADY_THERE) {
                    // Retry placement on the next tick.
                    return;
                }
                if (!TravelService.isBaritonePresent()) {
                    failTarget(current, "target is out of reach and Baritone is not installed at "
                            + pass(current.pos));
                    return;
                }
                if (current.travelAttempts < maxTravelAttempts) {
                    current.nextAttemptAt = tickCounter + retryDelay();
                    statusDetail = "retrying local travel to " + pass(current.pos)
                            + (travelStartError.isBlank() ? "" : " (" + travelStartError + ")");
                    return;
                }
            }
            failTarget(current, "target is out of reach and travel is unavailable at " + pass(current.pos)
                    + (travelStartError.isBlank() ? "" : " (" + travelStartError + ")"));
            return;
        }
        if (tickCounter < current.nextAttemptAt) return;

        PlacementService.Result result = PlacementService.placeChecked(current.pos, current.expected,
                current.attempt, current.method, module.useAirplace.get(), null,
                module.stateAwarePlacement.get());
        switch (result.outcome()) {
            case PLACED -> {
                current.placed = true;
                current.placedAtTick = tickCounter;
                lastPlacementTick = tickCounter;
                consumeCachedItem(itemId);
                enqueueVerification(current);
            }
            case NO_ITEM -> markUnavailable(current, "no hotbar stack of " + shortId(itemId));
            case NO_QUOTA -> {
                current.nextAttemptAt = tickCounter + 1;
                statusDetail = "waiting for the server packet quota";
            }
            case COOLDOWN -> current.nextAttemptAt = tickCounter + 1;
            case OUT_OF_REACH -> {
                current.attempt++;
                current.nextAttemptAt = tickCounter + retryDelay();
                if (current.attempt >= module.maxPlaceAttempts.get()) {
                    failTarget(current, "placement denied (out of reach) at " + pass(current.pos));
                }
            }
            case DENIED, FAILED -> {
                current.attempt++;
                current.nextAttemptAt = tickCounter + retryDelay();
                if (current.attempt >= module.maxPlaceAttempts.get()) {
                    if (module.fallbackPlacement.get() && !current.fallbackUsed) {
                        current.fallbackUsed = true;
                        current.method = current.method == PlacementMethod.VANILLA
                                ? PlacementMethod.AUTO : PlacementMethod.VANILLA;
                        current.attempt = 0;
                        statusDetail = "placement fallback for " + pass(current.pos);
                    } else {
                        failTarget(current, "placement failed at " + pass(current.pos)
                                + " (" + result.reason() + ")");
                    }
                }
            }
            default -> {
                current.nextAttemptAt = tickCounter + retryDelay();
            }
        }
    }

    private int retryDelay() {
        return Math.max(1, module.retryDelayTicks.get());
    }

    private void enqueueVerification(Target current) {
        current.phase = Phase.VERIFY;
        current.verifyAt = PlacementScheduler.verifyAt(tickCounter, placementLimits());
        verifying.put(current.packed, current);
        if (target == current) target = null;
        statusDetail = "verifying " + pass(current.pos);
    }

    private void clearTarget(Target current) {
        if (target == current) target = null;
    }

    /**
     * Checks every placement whose verification delay elapsed. Verification is independent of the
     * serial break/travel target; a failed check is re-queued rather than blocking the pipeline.
     */
    private void tickVerifications() {
        if (verifying.isEmpty()) return;
        List<Target> due = new ArrayList<>();
        for (Target current : verifying.values()) {
            if (tickCounter >= current.verifyAt) due.add(current);
        }
        for (Target current : due) {
            if (!verifying.containsKey(current.packed)) continue;
            if (target != null) {
                current.verifyAt = tickCounter + 1;
                continue;
            }
            verifyInFlight(current);
        }
    }

    private void verifyInFlight(Target current) {
        verifying.remove(current.packed);
        BlockState actual = mc.world.getBlockState(current.pos);
        MismatchDecision decision = classify(current.expected, actual, current.packed);
        if (decision.kind() == MismatchKind.NONE) {
            placedThisSession++;
            completeTarget(current, true, "placed");
            return;
        }
        if (decision.isIgnored() || decision.action() == MismatchAction.REPORT) {
            progress.markIgnored(current.packed);
            decrementRequired(current.expected);
            work.remove(current.packed);
            unavailableChecks.remove(current.packed);
            unavailableDue.remove(current.packed);
            if (decision.action() == MismatchAction.REPORT) {
                reportOnce(current.packed, "Reported " + pass(current.pos) + ": " + decision.reason());
            } else {
                reportOnce(current.packed, "State mismatch ignored at " + pass(current.pos)
                        + ": " + decision.reason());
            }
            statusDetail = "ignored " + pass(current.pos) + ": " + decision.reason();
            saveProgress(false);
            return;
        }
        // A laggy server can acknowledge the interact packet long after the client sent it. Do not
        // count that as a failed attempt, and do not send a duplicate placement, until the grace
        // period expires. This is especially important for integrated servers running behind.
        if (decision.kind() == MismatchKind.MISSING
                && PlacementScheduler.verificationGraceActive(current.placedAtTick, tickCounter,
                        module.verifyDelayTicks.get(), module.verificationTimeoutTicks.get())) {
            current.phase = Phase.VERIFY;
            current.verifyAt = tickCounter + Math.max(1, module.verifyDelayTicks.get());
            verifying.put(current.packed, current);
            statusDetail = "waiting for server confirmation at " + pass(current.pos);
            return;
        }
        if (decision.kind() == MismatchKind.STATE_MISMATCH
                && current.stateAttempts < module.maxStateAttempts.get()) {
            if (target != null) {
                deferVerification(current);
                return;
            }
            current.stateAttempts++;
            current.attempt = Math.max(current.attempt, current.stateAttempts);
            target = current;
            beginBreak(current);
            return;
        }
        if (current.attempt >= module.maxPlaceAttempts.get() + module.maxStateAttempts.get()) {
            if (target != null) {
                deferVerification(current);
                return;
            }
            if (module.fallbackPlacement.get() && !current.fallbackUsed) {
                current.fallbackUsed = true;
                current.method = current.method == PlacementMethod.VANILLA
                        ? PlacementMethod.AUTO : PlacementMethod.VANILLA;
                current.attempt = 0;
                current.phase = Phase.PLACE;
                current.nextAttemptAt = tickCounter + retryDelay();
                target = current;
                return;
            }
            if (decision.kind() == MismatchKind.STATE_MISMATCH) {
                failTarget(current, "could not reproduce state for " + shortBlock(current.expected)
                        + " at " + pass(current.pos));
            } else {
                failTarget(current, "verification failed at " + pass(current.pos)
                        + " (" + decision.reason() + ")");
            }
            return;
        }
        current.phase = decision.needsBreak() ? Phase.BREAK : Phase.PLACE;
        if (target != null) {
            deferVerification(current);
            return;
        }
        current.attempt++;
        current.nextAttemptAt = tickCounter + retryDelay();
        target = current;
        if (current.phase == Phase.BREAK) {
            beginBreak(current);
        }
    }

    private void deferVerification(Target current) {
        current.phase = Phase.VERIFY;
        current.verifyAt = tickCounter + Math.max(1, module.retryDelayTicks.get());
        verifying.put(current.packed, current);
    }

    private void tickTargetTravel(Target current) {
        TravelService.Status status = siteTravelOfTarget();
        if (status == null) {
            current.phase = Phase.PLACE;
            return;
        }
        switch (status.result()) {
            case ARRIVED -> current.phase = Phase.PLACE;
            case FAILED, IDLE -> {
                if (current.travelAttempts < Math.max(1, module.targetTravelRetries.get())) {
                    current.phase = Phase.PLACE;
                    current.nextAttemptAt = tickCounter + retryDelay();
                    statusDetail = "retrying local travel to " + pass(current.pos)
                            + ": " + status.message();
                } else {
                    failTarget(current, "could not travel to " + pass(current.pos)
                            + ": " + status.message());
                }
            }
            default -> {
            }
        }
    }

    private TravelService.Status siteTravelOfTarget() {
        if (currentTravel == null) return null;
        return currentTravel.tick();
    }

    private TravelService currentTravel;
    private String travelStartError = "";

    private TravelService.StartResult startTravelTo(BlockPos pos) {
        travelStartError = "";
        if (!TravelService.isBaritonePresent()) {
            travelStartError = "Baritone is not installed";
            return TravelService.StartResult.FAILED;
        }
        if (currentTravel != null) currentTravel.cancel();
        currentTravel = new TravelService();
        BlockPos walkTarget = TravelService.standableNear(pos, 2);
        if (walkTarget == null) walkTarget = pos;
        TravelService.StartResult result = currentTravel.start(walkTarget, TravelMode.WALK, 2.5,
                module.targetTravelTimeoutTicks.get(), module.targetTravelRetries.get(),
                module.acceptBaritoneElytraTerms.get(), module.elytraMinDistance.get(),
                module.travelStallGraceTicks.get());
        if (result != TravelService.StartResult.STARTED) {
            travelStartError = currentTravel.error();
            currentTravel = null;
        }
        return result;
    }

    private void completeTarget(Target current, boolean placed, String why) {
        verifying.remove(current.packed);
        progress.markCompleted(current.packed);
        if (placed) {
            decrementRequired(current.expected);
        } else if (current.extra) {
            // No material was consumed for an extra block.
        }
        work.remove(current.packed);
        unavailableChecks.remove(current.packed);
        unavailableDue.remove(current.packed);
        progress.hasResume = true;
        progress.resumeX = current.pos.getX();
        progress.resumeY = current.pos.getY();
        progress.resumeZ = current.pos.getZ();
        statusDetail = (why == null || why.isBlank() ? "completed" : why) + " " + pass(current.pos);
        if (target == current) {
            target = null;
            breaker.stop(false);
            if (currentTravel != null) {
                currentTravel.cancel();
                currentTravel = null;
            }
        }
        saveProgress(false);
    }

    private void failTarget(Target current, String reason) {
        verifying.remove(current.packed);
        progress.markFailed(current.packed);
        decrementRequired(current.expected);
        work.remove(current.packed);
        unavailableChecks.remove(current.packed);
        unavailableDue.remove(current.packed);
        reportOnce(current.packed, "Failed " + pass(current.pos) + ": " + reason);
        if (target == current) {
            target = null;
            breaker.stop(true);
            if (currentTravel != null) {
                currentTravel.cancel();
                currentTravel = null;
            }
        }
        statusDetail = reason;
        saveProgress(false);
    }

    private void markUnavailable(Target current, String reason) {
        verifying.remove(current.packed);
        progress.markPending(current.packed);
        work.put(current.packed, new Work(current.packed, current.pos, current.expected,
                McStates.itemId(current.expected)));
        int checks = unavailableChecks.merge(current.packed, 1, Integer::sum);
        if (checks > module.maxUnavailableRechecks.get()) {
            progress.markFailed(current.packed);
            work.remove(current.packed);
            unavailableChecks.remove(current.packed);
            unavailableDue.remove(current.packed);
            reportOnce(current.packed, "Giving up on " + pass(current.pos) + ": " + reason);
            decrementRequired(current.expected);
        } else {
            unavailableDue.put(current.packed, tickCounter + Math.max(5, module.unavailableRecheckSeconds.get()) * 20L);
            if (module.debug.get()) message("Pending " + pass(current.pos) + ": " + reason);
        }
        if (target == current) {
            target = null;
            breaker.stop(false);
            if (currentTravel != null) {
                currentTravel.cancel();
                currentTravel = null;
            }
        }
        if (!triggerRecovery(false)) {
            statusDetail = "waiting for materials at " + pass(current.pos);
        }
    }

    private boolean tryDueUnavailable() {
        if (unavailableDue.isEmpty() || tickCounter % 20 != 0) return false;
        long now = tickCounter;
        List<Long> due = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : unavailableDue.entrySet()) {
            if (entry.getValue() <= now) due.add(entry.getKey());
        }
        PlacementScheduler.Limits limits = placementLimits();
        for (Long packed : due) {
            Work item = work.get(packed);
            if (item == null) {
                unavailableDue.remove(packed);
                continue;
            }
            if (item.itemId != null && looseCount(item.itemId) <= 0) {
                int checks = unavailableChecks.merge(packed, 1, Integer::sum);
                if (checks > module.maxUnavailableRechecks.get()) {
                    progress.markFailed(packed);
                    work.remove(packed);
                    unavailableChecks.remove(packed);
                    unavailableDue.remove(packed);
                    reportOnce(packed, "Giving up on " + pass(item.pos) + ": material still unavailable");
                    decrementRequired(item.expected);
                } else {
                    unavailableDue.put(packed, tickCounter + Math.max(5, module.unavailableRecheckSeconds.get()) * 20L);
                }
                continue;
            }
            // Keep the due entry until the scheduler actually allows a start. Removing it first
            // would silently drop this pending position when the packet quota or verification
            // window is saturated.
            if (!placementAllowed(limits)) return false;
            unavailableDue.remove(packed);
            if (startPosition(item.pos, item.expected, false)) return true;
            if (state != State.BUILDING) return false;
        }
        return false;
    }

    private void finalizeBuild() {
        target = null;
        breaker.stop(false);
        int pending = progress.pending.size();
        int failed = progress.failed.size();
        saveProgress(true);
        String saved = lastSaveFailed ? " WARNING: progress could not be saved; check the log."
                : " Progress was saved.";
        if (failed > 0) {
            state = State.FAILED;
            lastError = failed + " positions failed";
            statusDetail = lastError + (pending > 0 ? ", " + pending + " still pending" : "");
            message("Build finished with failures: " + statusDetail + "." + saved);
        } else if (pending > 0) {
            state = State.FAILED;
            lastError = pending + " positions still need materials";
            statusDetail = lastError;
            message("Build paused as failed: " + lastError + ". Add materials and resume." + saved);
        } else {
            state = State.COMPLETE;
            statusDetail = "complete: " + progress.completed.size() + " built, "
                    + progress.ignored.size() + " ignored";
            message("Build complete. " + statusDetail + "." + saved);
            if (module.disableOnComplete.get()) {
                module.info("Disabling Auto Builder.");
                module.toggle();
            }
        }
        userPaused = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Travel to the build site
    // ---------------------------------------------------------------------------------------------

    private boolean travelToBuildSiteIfNeeded() {
        if (mc.player == null || region == null) return false;
        double distance = distanceToRegion(mc.player.getBlockPos());
        if (distance <= 24.0) return false;
        if (!module.autoTravelToTargets.get()) return false;
        if (!TravelService.isBaritonePresent()) {
            siteTravelFailed = true;
            message("Build site is " + Math.round(distance) + " blocks away but Baritone is not installed.");
            return false;
        }
        ensureBuildAnchor();
        BlockPos anchor = new BlockPos(progress.anchorX, progress.anchorY, progress.anchorZ);
        siteTravel = new TravelService();
        TravelService.StartResult result = siteTravel.start(anchor, module.travelMode.get(),
                Math.max(2.5, module.arrivalDistance.get()),
                module.travelTimeoutTicks.get(), module.travelRetries.get(),
                module.acceptBaritoneElytraTerms.get(), module.elytraMinDistance.get(),
                module.travelStallGraceTicks.get());
        if (result == TravelService.StartResult.FAILED) {
            siteTravelFailed = true;
            String travelError = siteTravel.error();
            siteTravel = null;
            message("Could not travel to the build site: "
                    + (travelError == null || travelError.isBlank() ? "travel could not start" : travelError));
            return false;
        }
        if (result == TravelService.StartResult.ALREADY_THERE) {
            siteTravel = null;
            return false;
        }
        statusDetail = "travelling to build site";
        return true;
    }

    private void tickSiteTravel() {
        if (siteTravel == null) return;
        TravelService.Status status = siteTravel.tick();
        if (status.result() == TravelService.TickResult.ARRIVED) {
            siteTravel = null;
            message("Arrived at the build site.");
        } else if (status.result() == TravelService.TickResult.FAILED) {
            siteTravel = null;
            siteTravelFailed = true;
            String reason = status.message();
            saveProgress(true);
            message("Travel to build site failed: " + reason
                    + ". Progress is saved; the job is paused so it cannot walk in circles.");
            pauseInternal("travel to build site failed: " + reason);
        } else {
            statusDetail = "travelling: " + Math.round(status.distance()) + " blocks";
        }
    }

    /**
     * Records the player's current working position as the return anchor for stash trips. The guard
     * prevents a position at a distant stash from replacing the real build-site anchor.
     */
    private void rememberBuildAnchor() {
        if (mc.player == null || progress == null || region == null) return;
        BlockPos pos = mc.player.getBlockPos();
        if (distanceToRegion(pos) > 48.0) return;
        setBuildAnchor(pos, true);
    }

    /** Loads a persisted anchor, records the player's start position, or derives a safe fallback. */
    private void ensureBuildAnchor() {
        if (progress == null) return;
        if (progress.hasAnchor) return;
        if (mc.player != null && region != null && distanceToRegion(mc.player.getBlockPos()) <= 64.0) {
            setBuildAnchor(mc.player.getBlockPos(), true);
            return;
        }
        setBuildAnchor(deriveBuildAnchor(), true);
    }

    private void setBuildAnchor(BlockPos pos, boolean save) {
        if (progress == null || pos == null) return;
        progress.anchorX = pos.getX();
        progress.anchorY = pos.getY();
        progress.anchorZ = pos.getZ();
        progress.hasAnchor = true;
        if (save) saveProgress(true);
    }

    /** Finds a standing position near the centre of the build when no player position was saved. */
    private BlockPos deriveBuildAnchor() {
        int x = (region.minX() + region.maxX()) / 2;
        int z = (region.minZ() + region.maxZ()) / 2;
        if (mc.world == null) {
            return new BlockPos(x, MathHelper.clamp(region.maxY() + 2, -62, 318), z);
        }
        int bottom = mc.world.getBottomY();
        int top = Math.min(mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), 319);
        int start = Math.min(top, Math.max(region.maxY() + 2, bottom + 2));
        for (int y = start; y > bottom + 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (mc.world.isAir(pos) && mc.world.isAir(pos.up())
                    && !mc.world.getBlockState(pos.down()).isAir()) {
                return pos;
            }
        }
        return new BlockPos(x, MathHelper.clamp(region.minY(), bottom + 2, 318), z);
    }

    private double distanceToRegion(BlockPos pos) {
        int x = Math.max(region.minX(), Math.min(region.maxX(), pos.getX()));
        int y = Math.max(region.minY(), Math.min(region.maxY(), pos.getY()));
        int z = Math.max(region.minZ(), Math.min(region.maxZ(), pos.getZ()));
        return Math.sqrt(pos.getSquaredDistance(new BlockPos(x, y, z)));
    }

    // ---------------------------------------------------------------------------------------------
    // Restocking and shulkers
    // ---------------------------------------------------------------------------------------------

    private boolean triggerRecovery(boolean layerEnd) {
        if (state != State.BUILDING) return false;
        if (!layerEnd && progress.pending.isEmpty() && extraBlocks.isEmpty()) return false;
        if (restockFailureCooldown > 0) {
            restockFailureCooldown--;
            return false;
        }

        String shulkerItem = findShulkerCandidate();
        if (shulkerItem != null) {
            ShulkerService.Policy policy = shulkerPolicy();
            ShulkerService service = new ShulkerService();
            if (service.request(shulkerItem, policy)) {
                shulkerService = service;
                shulkerWanted = shulkerItem;
                state = State.SHULKER;
                statusDetail = "unloading shulker for " + shortId(shulkerItem);
                message("Unloading a carried shulker above the player for " + shortId(shulkerItem) + ".");
                return true;
            }
            shulkerFailedItems.add(shulkerItem);
            message("Could not start shulker unloading: " + service.error());
        }

        Map<String, Integer> wanted = buildRestockWanted(layerEnd);
        if (wanted.isEmpty()) return false;
        List<StorageEntry> stashes = StorageManager.get().enabledForRestock(currentDimension());
        if (stashes.isEmpty()) {
            if (module.debug.get()) {
                message("Materials are low but no enabled storage waypoint is in " + currentDimension() + ".");
            }
            return false;
        }
        if (!module.restockEnabled.get() || restockDisabled) return false;
        if (restockRuns >= module.maxRestockAttempts.get()) {
            restockDisabled = true;
            message("Restocking disabled for this job: max restock attempts reached.");
            return false;
        }

        // The player is at the build when a stash trip starts. Keep that exact working position so
        // the return trip can bring them back to it instead of an arbitrary point above the region.
        rememberBuildAnchor();

        RestockService service = new RestockService();
        if (service.start(wanted, stashes, restockPolicy())) {
            restockService = service;
            restockRuns++;
            state = State.RESTOCKING;
            statusDetail = "restocking " + wanted.size() + " materials";
            message("Restocking " + wanted.size() + " materials from " + stashes.size() + " storage waypoint(s).");
            return true;
        }
        restockFailureCooldown = 5;
        message("Restock could not start: " + service.error());
        return false;
    }

    private String findShulkerCandidate() {
        if (!module.unloadShulkers.get()) return null;
        for (String id : requiredRemaining.keySet()) {
            if (requiredRemaining.getOrDefault(id, 0) <= 0) continue;
            if (InventoryService.countBlocks(id) >= requiredRemaining.getOrDefault(id, 0)) continue;
            if (shulkerFailedItems.contains(id)) continue;
            for (InventoryService.ShulkerStack shulker : InventoryService.carriedShulkers()) {
                if (shulker.count(id) > 0) return id;
            }
        }
        return null;
    }

    private Map<String, Integer> buildRestockWanted(boolean layerEnd) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        Map<String, Integer> shulkerStock = InventoryService.shulkerContentsCounts();
        int twoStacks = Math.max(1, module.restockTargetStacks.get()) * 64;
        for (Map.Entry<String, Integer> entry : requiredRemaining.entrySet()) {
            int need = entry.getValue();
            if (need <= 0) continue;
            int loose = InventoryService.countBlocks(entry.getKey());
            int inShulkers = shulkerStock.getOrDefault(BlockKey.normalise(entry.getKey()), 0);
            int effectiveNeed = Math.max(0, need - inShulkers);
            if (effectiveNeed <= 0) continue;
            int target = Math.min(effectiveNeed, twoStacks);
            boolean low = loose <= module.emergencyThreshold.get() || loose <= 0;
            boolean belowThreshold = layerEnd && module.restockAtLayerEnd.get()
                    && loose < module.restockThreshold.get();
            if (!low && !belowThreshold) continue;
            int amount = Math.max(0, target - loose);
            if (amount > 0) wanted.put(entry.getKey(), amount);
        }
        return wanted;
    }

    private RestockService.Policy restockPolicy() {
        return new RestockService.Policy(
                module.travelMode.get(),
                module.travelTimeoutTicks.get(),
                module.travelRetries.get(),
                module.travelStallGraceTicks.get(),
                module.arrivalDistance.get(),
                module.acceptBaritoneElytraTerms.get(),
                module.elytraMinDistance.get(),
                module.stashRadius.get(),
                module.containerOpenTimeoutTicks.get(),
                module.withdrawPerTick.get(),
                module.inventoryReserveSlots.get(),
                module.restockShulkers.get(),
                module.maxCarriedShulkers.get(),
                module.twoStackThreshold.get(),
                module.preferredShulkerFill.get(),
                module.preferFullShulkers.get(),
                module.preferPartialShulkers.get(),
                module.exactStackCollection.get(),
                module.preferExactStackCollection.get(),
                module.materialPriority.get()
        );
    }

    private ShulkerService.Policy shulkerPolicy() {
        return new ShulkerService.Policy(
                module.shulkerAboveHead.get(),
                module.shulkerFallback.get(),
                module.containerOpenTimeoutTicks.get(),
                module.shulkerPlaceRetries.get(),
                module.shulkerPickupTimeoutTicks.get(),
                module.inventoryReserveSlots.get(),
                module.breakTimeoutTicks.get()
        );
    }

    private void tickRestock() {
        if (restockService == null) {
            state = State.BUILDING;
            return;
        }
        if (restockService.isActive()) {
            restockService.tick(restockPolicy());
            RestockService.Phase phase = restockService.phase();
            statusDetail = "restock: " + phase.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (restockService.message().isEmpty() ? "" : " - " + restockService.message());
            if (restockService.isActive()) return;
        }
        boolean success = restockService.wasSuccessful();
        String message = restockService.message();
        if (!success && !restockService.error().isEmpty()) message = restockService.error();
        message("Restock finished: " + message);
        restockService = null;
        state = State.BUILDING;
        invalidateLooseCache();
        if (success) {
            // A successful stash run means a previously failed carried-shulker item may be worth
            // retrying later; nothing in that set is authoritative any more.
            shulkerFailedItems.clear();
        }
        refreshMaterialPlan();
        reschedulePendingWork(success);
        if (!success) restockFailureCooldown = 40;
        if (!triggerRecovery(false)) statusDetail = "restock finished";
    }

    /**
     * Pending positions must be retried after a restock or shulker cycle. Without this, the
     * end-of-build wait can see pending work but has no due entries and would wait forever.
     */
    private void reschedulePendingWork(boolean resetChecks) {
        long due = tickCounter + Math.max(5, module.unavailableRecheckSeconds.get()) * 20L;
        unavailableDue.clear();
        if (resetChecks) unavailableChecks.clear();
        for (Long packed : progress.pending) {
            Work item = work.get(packed);
            if (item == null || item.itemId == null) continue;
            unavailableDue.put(packed, due);
        }
    }

    private void tickShulker() {
        if (shulkerService == null) {
            state = State.BUILDING;
            return;
        }
        if (shulkerService.isActive()) {
            shulkerService.tick(shulkerPolicy());
            statusDetail = "shulker: " + shulkerService.phase().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (shulkerService.isActive()) return;
        }
        boolean success = shulkerService.wasSuccessful();
        String error = shulkerService.error();
        if (success) {
            if (shulkerWanted != null) shulkerFailedItems.remove(shulkerWanted);
            message("Shulker unloaded and recovered.");
        } else {
            if (shulkerWanted != null) shulkerFailedItems.add(shulkerWanted);
            message("Shulker unloading failed: " + error);
        }
        shulkerService = null;
        shulkerWanted = null;
        state = State.BUILDING;
        invalidateLooseCache();
        refreshMaterialPlan();
        reschedulePendingWork(success);
        if (!success) restockFailureCooldown = 20;
        if (userPaused || !pauseReason.isEmpty()) {
            statusDetail = "shulker cycle finished; still paused";
            return;
        }
        if (!triggerRecovery(false)) statusDetail = "building";
    }

    // ---------------------------------------------------------------------------------------------
    // Rules and progress
    // ---------------------------------------------------------------------------------------------

    private void buildRules(boolean force) {
        if (!force && !rulesDirty) return;
        if (!force && System.currentTimeMillis() - lastRulesBuildMs < 500L) return;
        CorrectionRules.Builder builder = CorrectionRules.builder()
                .defaultTypeAction(module.defaultTypeAction.get())
                .defaultStateAction(module.defaultStateAction.get())
                .defaultExtraAction(module.defaultExtraAction.get())
                .extraRemovalEnabled(module.removeExtraBlocks.get())
                .protectContainers(module.protectContainers.get())
                .protectedBlocks(blockIds(module.protectedBlocks.get()).toArray(String[]::new))
                .ignoredTypeBlocks(blockIds(module.ignoreTypeBlocks.get()).toArray(String[]::new))
                .alwaysReplaceBlocks(blockIds(module.alwaysReplaceBlocks.get()).toArray(String[]::new))
                .ignoredStateBlocks(blockIds(module.ignoreStateBlocks.get()).toArray(String[]::new))
                .alwaysCorrectStateBlocks(blockIds(module.alwaysCorrectStateBlocks.get()).toArray(String[]::new))
                .ignoredExtraBlocks(blockIds(module.extraBlockIgnore.get()).toArray(String[]::new))
                .globalIgnoredProperties(module.ignoredProperties.get().toArray(new String[0]))
                .globalRequiredProperties(module.requiredProperties.get().toArray(new String[0]))
                .sessionRules(CorrectionRuleParser.parseScope(module.sessionRule.get()));

        for (Map.Entry<String, ScopeRules> entry : CorrectionRuleParser
                .parseSchematicRules(module.schematicRules.get()).entrySet()) {
            builder.schematicRules(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Integer, ScopeRules> entry : CorrectionRuleParser
                .parseLayerRules(module.layerRules.get()).entrySet()) {
            builder.layerRules(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, ScopeRules> entry : CorrectionRuleParser
                .parseBlockRules(module.blockCorrectionRules.get()).entrySet()) {
            builder.blockRules(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<BlockCategory, ScopeRules> entry : CorrectionRuleParser
                .parseCategoryRules(module.categoryCorrectionRules.get()).entrySet()) {
            builder.categoryRules(entry.getKey(), entry.getValue());
        }
        for (CorrectionRuleParser.BlockPropertyRule rule : CorrectionRuleParser
                .parseBlockPropertyRules(module.blockPropertyRules.get())) {
            builder.blockPropertyRule(rule.blockId(), rule.property(), rule.mode());
        }
        builder.protectedPositions(CorrectionRuleParser.parseProtectedPositions(
                module.protectedPositions.get(), currentDimension()));

        rules = builder.build();
        placementRules = PlacementRules.parse(module.placementRules.get());
        rulesDirty = false;
        lastRulesBuildMs = System.currentTimeMillis();
    }

    private MismatchDecision classify(BlockState expected, BlockState actual, long packed) {
        if (rules == null) buildRules(true);
        StateSignature expectedSig = McStates.signature(expected);
        StateSignature actualSig = McStates.signature(actual);
        if (expectedSig == null) return MismatchDecision.none();
        if (actualSig == null) actualSig = StateSignature.of("minecraft:air");
        RuleContext context = new RuleContext(expectedSig.block(),
                BlockCategory.fromId(expectedSig.block().id()), schematicName(),
                layerCoordinate(packed), sessionKey, packed);
        return rules.classify(expectedSig, actualSig, context);
    }

    private MismatchDecision classifyAir(BlockState actual, BlockPos pos) {
        if (rules == null) buildRules(true);
        StateSignature expected = StateSignature.of("minecraft:air");
        StateSignature actualSig = McStates.signature(actual);
        if (actualSig == null) return MismatchDecision.none();
        long packed = PositionCodec.pack(pos.getX(), pos.getY(), pos.getZ());
        RuleContext context = new RuleContext(expected.block(), BlockCategory.fromId(expected.block().id()),
                schematicName(), layerCoordinate(packed), sessionKey, packed);
        return rules.classify(expected, actualSig, context);
    }

    private int layerCoordinate(long packed) {
        return switch (module.layerAxis.get()) {
            case X -> PositionCodec.unpackX(packed);
            case Y -> PositionCodec.unpackY(packed);
            case Z -> PositionCodec.unpackZ(packed);
        };
    }

    private PlacementMethod resolvePlacementMethod(BlockState expected) {
        if (expected == null) return module.placementMethod.get();
        String id = McStates.blockId(expected.getBlock());
        return placementRules.resolve(id, BlockCategory.fromId(id), module.placementMethod.get());
    }

    private boolean isForbiddenBreak(BlockState state, BlockPos pos) {
        if (state == null || state.isAir() || state.isReplaceable()) return false;
        if (state.getHardness(mc.world, pos) < 0) return true;
        // A non-empty shulker must never be broken by the builder, even when the user disables the
        // general container/block-entity protections. The box may only be broken after its contents
        // have been verified empty.
        if (state.getBlock() instanceof ShulkerBoxBlock && !isShulkerEmpty(pos)) return true;
        if (module.protectBlockEntities.get() && state.hasBlockEntity()) return true;
        if (module.protectContainers.get()) {
            BlockCategory category = BlockCategory.fromId(McStates.blockId(state.getBlock()));
            if (category == BlockCategory.CONTAINER || category == BlockCategory.SHULKER) return true;
        }
        return false;
    }

    /** True only when the shulker's real block-entity inventory is present and empty. */
    private boolean isShulkerEmpty(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory inventory)) return false;
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private String currentDimension() {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    private Box3i resolveRegion() {
        Box3i bounds = snapshot.boundingBox();
        BuildMode mode = module.mode.get();
        LayerAxis axis = module.layerAxis.get();
        if (mode == BuildMode.FULL_SCHEMATIC) return bounds;
        if (mode == BuildMode.SELECTED_REGION) {
            String selected = snapshot.selectedSubRegion();
            Box3i selectedBox = selected.isEmpty() ? null : snapshot.subRegions().get(selected);
            if (selectedBox == null) {
                failStart("selected-region mode needs a selected Litematica sub-region (selected: '"
                        + selected + "')");
                return null;
            }
            return selectedBox;
        }
        int min = LayerPlanner.min(bounds, axis);
        int max = LayerPlanner.max(bounds, axis);
        if (mode == BuildMode.SINGLE_LAYER) {
            int requested = module.layerCoordinate.get();
            int coordinate = LayerPlanner.clampCoordinate(bounds, axis, requested);
            if (coordinate != requested) {
                module.layerCoordinate.set(coordinate);
                startWarning = "Layer coordinate " + requested + " is outside the schematic range "
                        + min + ".." + max + " on " + axis + "; using " + coordinate
                        + " instead of blocking the build.";
            }
            return LayerPlanner.layerBox(bounds, axis, coordinate);
        }

        int requestedStart = module.layerStart.get();
        int requestedEnd = module.layerEnd.get();
        LayerPlanner.Range range = LayerPlanner.clampRange(bounds, axis, requestedStart, requestedEnd);
        if (range.adjusted()) {
            module.layerStart.set(range.start());
            module.layerEnd.set(range.end());
            startWarning = "Layer range " + Math.min(requestedStart, requestedEnd) + ".."
                    + Math.max(requestedStart, requestedEnd) + " was clamped to "
                    + range.start() + ".." + range.end() + " (schematic range " + min + ".."
                    + max + " on " + axis + ").";
        }
        return switch (axis) {
            case Y -> new Box3i(bounds.minX(), range.start(), bounds.minZ(),
                    bounds.maxX(), range.end(), bounds.maxZ());
            case X -> new Box3i(range.start(), bounds.minY(), bounds.minZ(),
                    range.end(), bounds.maxY(), bounds.maxZ());
            case Z -> new Box3i(bounds.minX(), bounds.minY(), range.start(),
                    bounds.maxX(), bounds.maxY(), range.end());
        };
    }

    private String buildSessionKey() {
        BlockPos origin = snapshot.origin();
        return schematicName() + "@" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                + "|" + currentDimension() + "|" + region + "|" + module.mode.get().name()
                + "|" + module.layerAxis.get().name()
                + "|" + snapshot.rotation() + "|" + snapshot.mirror();
    }

    private void initialiseProgressMetadata() {
        progress.schematicName = schematicName();
        progress.dimension = currentDimension();
        progress.mode = module.mode.get().name();
        progress.originX = snapshot.origin().getX();
        progress.originY = snapshot.origin().getY();
        progress.originZ = snapshot.origin().getZ();
        progress.minX = region.minX();
        progress.minY = region.minY();
        progress.minZ = region.minZ();
        progress.maxX = region.maxX();
        progress.maxY = region.maxY();
        progress.maxZ = region.maxZ();
        progress.layerStart = layers.isEmpty() ? 0 : layers.getFirst();
        progress.layerEnd = layers.isEmpty() ? 0 : layers.getLast();
        progress.updatedAt = System.currentTimeMillis();
        store.markDirty();
    }

    private void loadStore() {
        if (store != null) return;
        store = new ProgressStore(progressFile);
        try {
            store.load();
        } catch (IOException e) {
            message("Progress file could not be read: " + e.getMessage());
            SevenBuilderAddon.LOG.warn("Could not load auto-builder progress", e);
        }
    }

    /** True when a saved session still has pending positions inside the current schematic/world. */
    private boolean hasSavedPendingSession() {
        loadStore();
        if (store == null || module == null || mc.world == null) return false;
        SchematicAccess.PlacementSnapshot current = LitematicaBridge.snapshot();
        if (current == null) return false;
        String dimension = currentDimension();
        if (dimension.isEmpty()) return false;
        String name = placementName(current);
        Box3i bounds = current.boundingBox();
        LayerAxis axis = module.layerAxis.get();
        int axisMin = LayerPlanner.min(bounds, axis);
        int axisMax = LayerPlanner.max(bounds, axis);
        Box3i preview;
        int layerMin;
        int layerMax;
        BuildMode mode = module.mode.get();
        if (mode == BuildMode.FULL_SCHEMATIC) {
            preview = bounds;
            layerMin = axisMin;
            layerMax = axisMax;
        } else if (mode == BuildMode.SELECTED_REGION) {
            String selected = current.selectedSubRegion();
            Box3i selectedBox = selected.isEmpty() ? null : current.subRegions().get(selected);
            if (selectedBox == null) return false;
            preview = selectedBox;
            layerMin = LayerPlanner.min(selectedBox, axis);
            layerMax = LayerPlanner.max(selectedBox, axis);
        } else if (mode == BuildMode.SINGLE_LAYER) {
            int coordinate = LayerPlanner.clampCoordinate(bounds, axis, module.layerCoordinate.get());
            preview = LayerPlanner.layerBox(bounds, axis, coordinate);
            layerMin = coordinate;
            layerMax = coordinate;
        } else {
            LayerPlanner.Range range = LayerPlanner.clampRange(bounds, axis,
                    module.layerStart.get(), module.layerEnd.get());
            preview = switch (axis) {
                case Y -> new Box3i(bounds.minX(), range.start(), bounds.minZ(),
                        bounds.maxX(), range.end(), bounds.maxZ());
                case X -> new Box3i(range.start(), bounds.minY(), bounds.minZ(),
                        range.end(), bounds.maxY(), bounds.maxZ());
                case Z -> new Box3i(bounds.minX(), bounds.minY(), range.start(),
                        bounds.maxX(), bounds.maxY(), range.end());
            };
            layerMin = range.start();
            layerMax = range.end();
        }
        int bottom = worldBottom();
        int top = worldTop();
        for (ProgressStore.SessionProgress session : store.sessions()) {
            if (session.key.isEmpty() || session.pending.isEmpty()) continue;
            if (!session.dimension.isEmpty() && !session.dimension.equals(dimension)) continue;
            if (!name.isEmpty() && !session.schematicName.isEmpty() && !session.schematicName.equals(name)) {
                continue;
            }
            if (ProgressReconciler.countValidPending(session, preview, axis, layerMin, layerMax,
                    bottom, top) > 0) {
                return true;
            }
        }
        return false;
    }

    private void saveProgress(boolean force) {
        if (store == null || progress == null) return;
        long now = System.currentTimeMillis();
        store.markDirty();
        if (!force && now - lastSaveMs < 1000L) return;
        progress.totalCompleted = progress.completed.size();
        progress.totalIgnored = progress.ignored.size();
        progress.totalFailed = progress.failed.size();
        store.markDirty();
        try {
            store.save();
            lastSaveMs = now;
            lastSaveFailed = false;
        } catch (IOException e) {
            lastSaveFailed = true;
            SevenBuilderAddon.LOG.warn("Could not save auto-builder progress", e);
            if (force) message("Could not save progress: " + e.getMessage());
        }
    }

    /** Human-readable save status for pause/stop messages. */
    private String saveNote() {
        return lastSaveFailed
                ? "WARNING: progress could not be saved; check the log."
                : "Progress saved.";
    }

    private void refreshMaterialPlan() {
        if (module != null && !module.planMaterials.get()) {
            materialPlan = MaterialPlanner.plan(Map.of(), Map.of(), Map.of(), Map.of(), List.of());
            return;
        }
        materialPlan = MaterialPlanner.plan(requiredRemaining, InventoryService.looseCounts(),
                InventoryService.shulkerContentsCounts(), Map.of(), module == null ? List.of() : module.materialPriority.get());
    }

    private void decrementRequired(BlockState expected) {
        if (expected == null) return;
        String id = McStates.itemId(expected);
        if (id == null) return;
        Integer count = requiredRemaining.get(id);
        if (count == null || count <= 0) return;
        if (count == 1) requiredRemaining.remove(id);
        else requiredRemaining.put(id, count - 1);
    }

    private void resetRuntimeForStart() {
        target = null;
        verifying.clear();
        looseCache.clear();
        looseCacheTick = Long.MIN_VALUE;
        breaker.stop(true);
        if (siteTravel != null) {
            siteTravel.cancel();
            siteTravel = null;
        }
        if (restockService != null) {
            restockService.cancel();
            restockService = null;
        }
        if (shulkerService != null) {
            shulkerService.abort();
            shulkerService = null;
        }
        if (currentTravel != null) {
            currentTravel.cancel();
            currentTravel = null;
        }
        siteTravelFailed = false;
        travelStartError = "";
        resumeOnJoin = false;
        suspended = false;
        restartRequested = false;
        deathPaused = false;
    }

    private void cancelActiveWork(boolean abort) {
        target = null;
        verifying.clear();
        looseCache.clear();
        looseCacheTick = Long.MIN_VALUE;
        breaker.stop(abort);
        if (siteTravel != null) {
            siteTravel.cancel();
            siteTravel = null;
        }
        if (currentTravel != null) {
            currentTravel.cancel();
            currentTravel = null;
        }
        if (restockService != null) {
            restockService.cancel();
            restockService = null;
        }
        if (shulkerService != null) {
            if (abort) shulkerService.abort();
            shulkerService = null;
        }
        siteTravelFailed = false;
    }

    private void failStart(String reason) {
        state = State.FAILED;
        lastError = reason;
        statusDetail = reason;
        message("Cannot start: " + reason);
    }

    private void reportOnce(long packed, String text) {
        if (reported.size() >= MAX_REPORT_MESSAGES) return;
        if (reported.add(packed)) message(text);
    }

    private void message(String text) {
        if (module == null || !module.showMessages.get()) return;
        module.info(text);
    }

    private List<String> blockIds(List<Block> blocks) {
        List<String> ids = new ArrayList<>();
        if (blocks == null) return ids;
        for (Block block : blocks) {
            String id = McStates.blockId(block);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static int percent(long value, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(Math.min(100.0, value * 100.0 / total));
    }

    private static String pass(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String shortId(String id) {
        if (id == null) return "air";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String shortBlock(BlockState state) {
        if (state == null) return "air";
        return shortId(McStates.blockId(state.getBlock()));
    }

    // ---------------------------------------------------------------------------------------------
    // Status API (HUD, commands, module info)
    // ---------------------------------------------------------------------------------------------

    public State state() {
        return state;
    }

    public boolean isPaused() {
        return userPaused || !pauseReason.isEmpty();
    }

    public boolean isRunning() {
        return state == State.SCANNING || state == State.BUILDING || state == State.RESTOCKING
                || state == State.SHULKER;
    }

    public String pauseReason() {
        return pauseReason;
    }

    public String statusShort() {
        if (state == State.IDLE) return "Ready";
        if (isPaused()) return "Paused";
        return switch (state) {
            case SCANNING -> "Scanning " + percent(scannedPositions, scanVolume) + "%";
            case BUILDING -> "Building " + progressPercent() + "%";
            case RESTOCKING -> "Restocking";
            case SHULKER -> "Shulker";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case STOPPED -> "Stopped";
            case SUSPENDED -> "Disconnected";
            default -> "Ready";
        };
    }

    public String statusLine() {
        String base = state.title();
        if (isPaused()) {
            base += " (paused: " + pauseReason + ")";
        } else if (state == State.BUILDING || state == State.SCANNING) {
            base += " " + progressPercent() + "%";
        }
        if (!statusDetail.isEmpty()) base += " - " + statusDetail;
        return base;
    }

    public int progressPercent() {
        if (state == State.SCANNING) return percent(scannedPositions, scanVolume);
        return progress == null ? 0 : progress.progressPercent();
    }

    public int totalPositions() {
        if (progress == null) return 0;
        return progress.completed.size() + progress.ignored.size() + progress.pending.size()
                + progress.failed.size();
    }

    public int completedPositions() {
        return progress == null ? 0 : progress.completed.size();
    }

    public int ignoredPositions() {
        return progress == null ? 0 : progress.ignored.size();
    }

    public int pendingPositions() {
        return progress == null ? 0 : progress.pending.size();
    }

    public int failedPositions() {
        return progress == null ? 0 : progress.failed.size();
    }

    public String currentLayerText() {
        if (layers.isEmpty() || layerIndex >= layers.size()) return "layer -";
        return "layer " + layers.get(layerIndex);
    }

    /** Shows the actual schematic layer bounds, not the possibly-stale requested settings. */
    public String layerBoundsText() {
        if (region == null || module == null) return "layer bounds: -";
        LayerAxis axis = module.layerAxis.get();
        return "layer " + axis + " " + LayerPlanner.min(region, axis) + ".."
                + LayerPlanner.max(region, axis) + " (" + layers.size() + " layers)";
    }

    public int inFlightVerifications() {
        return verifying.size();
    }

    public double placementRatePerMinute() {
        if (placedThisSession <= 0 || sessionStartedMs <= 0L) return 0.0;
        long elapsed = System.currentTimeMillis() - sessionStartedMs;
        if (elapsed < 1000L) return 0.0;
        return placedThisSession * 60000.0 / elapsed;
    }

    public String printStatsText() {
        return String.format(Locale.ROOT, "placed %d | %.1f/min | %d verifying",
                placedThisSession, placementRatePerMinute(), verifying.size());
    }

    public String materialSummary() {
        if (materialPlan == null || materialPlan.lines().isEmpty()) return "no material scan";
        if (materialPlan.complete()) return "materials ok";
        List<MaterialPlanner.MaterialLine> missing = materialPlan.missingLines();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, missing.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(shortId(missing.get(i).itemId())).append(" ").append(missing.get(i).remaining());
        }
        if (missing.size() > 3) out.append(", +").append(missing.size() - 3);
        return "missing " + out;
    }

    public String lastError() {
        return lastError;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public String schematicName() {
        return snapshot == null ? "" : placementName(snapshot);
    }

    /**
     * The active job's schematic, or the currently selected Litematica placement while idle. The
     * idle fallback is diagnostic only; it never starts or resumes a job.
     */
    public String liveSchematicName() {
        String active = schematicName();
        if (!active.isBlank()) return active;
        return placementName(LitematicaBridge.snapshot());
    }

    private static String placementName(SchematicAccess.PlacementSnapshot value) {
        if (value == null) return "";
        return value.schematicName().isEmpty() ? value.placementName() : value.schematicName();
    }

    private int worldBottom() {
        return mc.world == null ? -64 : mc.world.getBottomY();
    }

    private int worldTop() {
        return mc.world == null ? 319 : mc.world.getTopY() - 1;
    }

    public boolean isSuspended() {
        return suspended;
    }

    private record Work(long packed, BlockPos pos, BlockState expected, String itemId) {
    }

    private static final class Target {
        final long packed;
        final BlockPos pos;
        final BlockState expected;
        final boolean extra;
        boolean requiresItem;
        boolean fallbackUsed;
        boolean placed;
        int attempt;
        int stateAttempts;
        int breakAttempts;
        int travelAttempts;
        Phase phase = Phase.PLACE;
        long breakDeadline;
        long placedAtTick;
        long verifyAt;
        long nextAttemptAt;
        PlacementMethod method = PlacementMethod.AUTO;

        Target(long packed, BlockPos pos, BlockState expected, boolean extra) {
            this.packed = packed;
            this.pos = pos;
            this.expected = expected;
            this.extra = extra;
        }
    }
}
