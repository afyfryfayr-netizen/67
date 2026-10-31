package sleepy.autobuilder.autobuild;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import sleepy.autobuilder.SevenBuilderAddon;
import sleepy.autobuilder.autobuild.core.BuildMode;
import sleepy.autobuilder.autobuild.core.ConstructionOrder;
import sleepy.autobuilder.autobuild.core.LayerAxis;
import sleepy.autobuilder.autobuild.core.LayerOrder;
import sleepy.autobuilder.autobuild.core.MismatchAction;
import sleepy.autobuilder.autobuild.core.SpeedPreset;
import sleepy.autobuilder.autobuild.gui.BlockPickerScreen;
import sleepy.autobuilder.autobuild.gui.PlacementRulesScreen;
import sleepy.autobuilder.autobuild.gui.ProfilesScreen;
import sleepy.autobuilder.autobuild.gui.PropertyRulesScreen;
import sleepy.autobuilder.autobuild.storage.StorageManagerScreen;

import java.util.List;

/**
 * All auto-builder configuration lives here so Meteor's settings system can serialise, search and
 * profile it. The engine reads these settings live; structural changes call {@code invalidateJob()}.
 */
public class AutoBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgCorrection = settings.createGroup("Mismatch Correction");
    private final SettingGroup sgBreaking = settings.createGroup("Breaking & Safety");
    private final SettingGroup sgMaterials = settings.createGroup("Materials");
    private final SettingGroup sgStashes = settings.createGroup("Storage & Restocking");
    private final SettingGroup sgShulkers = settings.createGroup("Shulker Handling");
    private final SettingGroup sgTravel = settings.createGroup("Travel");
    private final SettingGroup sgKeys = settings.createGroup("Keybinds");

    // General ------------------------------------------------------------------------------------

    public final Setting<SettingsLevel> settingsLevel = sgGeneral.add(new EnumSetting.Builder<SettingsLevel>()
            .name("settings-level")
            .description("Hides advanced or expert settings in this module's GUI. Search still finds every setting.")
            .defaultValue(SettingsLevel.BASIC)
            .build()
    );

    public final Setting<BuildMode> mode = sgGeneral.add(new EnumSetting.Builder<BuildMode>()
            .name("mode")
            .description("Full schematic, one layer, a layer range, or Litematica's selected sub-region.")
            .defaultValue(BuildMode.FULL_SCHEMATIC)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<LayerAxis> layerAxis = sgGeneral.add(new EnumSetting.Builder<LayerAxis>()
            .name("layer-axis")
            .description("Axis used for layer slicing.")
            .defaultValue(LayerAxis.Y)
            .visible(this::showAdvanced)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<Integer> layerCoordinate = sgGeneral.add(new IntSetting.Builder()
            .name("layer-coordinate")
            .description("Absolute coordinate of the single layer on the chosen axis.")
            .defaultValue(64)
            .range(-2048, 2048)
            .visible(() -> showAdvanced() && mode.get() == BuildMode.SINGLE_LAYER)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<Integer> layerStart = sgGeneral.add(new IntSetting.Builder()
            .name("layer-start")
            .description("First absolute coordinate of the layer range (inclusive).")
            .defaultValue(0)
            .range(-2048, 2048)
            .visible(() -> showAdvanced() && mode.get() == BuildMode.LAYER_RANGE)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<Integer> layerEnd = sgGeneral.add(new IntSetting.Builder()
            .name("layer-end")
            .description("Last absolute coordinate of the layer range (inclusive).")
            .defaultValue(0)
            .range(-2048, 2048)
            .visible(() -> showAdvanced() && mode.get() == BuildMode.LAYER_RANGE)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<LayerOrder> layerOrder = sgGeneral.add(new EnumSetting.Builder<LayerOrder>()
            .name("layer-order")
            .description("Build layers from low to high or high to low.")
            .defaultValue(LayerOrder.ASCENDING)
            .visible(this::showAdvanced)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<ConstructionOrder> constructionOrder = sgGeneral.add(new EnumSetting.Builder<ConstructionOrder>()
            .name("construction-order")
            .description("Order of blocks inside each layer.")
            .defaultValue(ConstructionOrder.NEAREST)
            .onChanged(value -> structuralChange())
            .build()
    );

    public final Setting<Integer> actionsPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("actions-per-tick")
            .description("Maximum placement/break starts per client tick. PlacementManager's packet quota still applies.")
            .defaultValue(2)
            .range(1, 9)
            .build()
    );

    public final Setting<Boolean> startOnActivate = sgGeneral.add(new BoolSetting.Builder()
            .name("start-on-activate")
            .description("Start building when the module is enabled. The module keybind is therefore the start keybind.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> resumeProgress = sgGeneral.add(new BoolSetting.Builder()
            .name("resume-progress")
            .description("Resume the stored progress for this schematic. Disable to recheck every position from scratch.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> autoResumeSaved = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-resume-saved")
            .description("Automatically resume an incomplete saved job when the module is enabled, after reconnecting, or after restarting the client. Requires resume-progress.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> autoSaveSeconds = sgGeneral.add(new IntSetting.Builder()
            .name("auto-save-seconds")
            .description("How often progress is written to disk while working.")
            .defaultValue(15)
            .range(5, 300)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> pauseOnScreen = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-screen")
            .description("Pause while any screen other than the builder's own container is open.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> autoResumeAfterScreen = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-resume-after-screen")
            .description("Resume automatically when the user's screen closes.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> pauseOnDeath = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-death")
            .description("Pause and save progress when the player dies.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> autoResumeAfterDeath = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-resume-after-death")
            .description("After respawning, travel back to the build and resume automatically.")
            .defaultValue(false)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> pauseOnLowHealth = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-low-health")
            .description("Pause while health is below the threshold.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Double> minHealth = sgGeneral.add(new DoubleSetting.Builder()
            .name("min-health")
            .description("Health threshold for the low-health pause.")
            .defaultValue(8.0)
            .range(1.0, 20.0)
            .sliderRange(1.0, 20.0)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> pauseNearPlayers = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-near-players")
            .description("Pause when another player is within the configured radius.")
            .defaultValue(false)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Double> playerRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("player-radius")
            .description("Radius for the nearby-player pause.")
            .defaultValue(16.0)
            .range(2.0, 128.0)
            .sliderRange(2.0, 64.0)
            .visible(() -> showAdvanced() && pauseNearPlayers.get())
            .build()
    );

    public final Setting<Boolean> pauseOnLowAir = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-low-air")
            .description("Pause while underwater with less than the configured air.")
            .defaultValue(false)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<Integer> minAir = sgGeneral.add(new IntSetting.Builder()
            .name("min-air")
            .description("Air threshold for the low-air pause.")
            .defaultValue(80)
            .range(0, 300)
            .visible(() -> showExpert() && pauseOnLowAir.get())
            .build()
    );

    public final Setting<Boolean> disableOnComplete = sgGeneral.add(new BoolSetting.Builder()
            .name("disable-on-complete")
            .description("Also disable the module when the job completes.")
            .defaultValue(false)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> showMessages = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-messages")
            .description("Send state changes, shortages and failures to chat.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug-messages")
            .description("Log rule decisions and retry reasons. Can be noisy.")
            .defaultValue(false)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<Integer> rulesRefreshSeconds = sgGeneral.add(new IntSetting.Builder()
            .name("rules-refresh-seconds")
            .description("How often correction and placement rules are rebuilt from settings.")
            .defaultValue(20)
            .range(5, 300)
            .visible(this::showExpert)
            .build()
    );

    // Placement ----------------------------------------------------------------------------------

    public final Setting<PlacementMethod> placementMethod = sgPlacement.add(new EnumSetting.Builder<PlacementMethod>()
            .name("placement-method")
            .description("AUTO uses the existing airplace PlacementManager; VANILLA uses Meteor BlockUtils.")
            .defaultValue(PlacementMethod.AUTO)
            .build()
    );

    public final Setting<Boolean> useAirplace = sgPlacement.add(new BoolSetting.Builder()
            .name("use-airplace")
            .description("Allow the printer's airplace path. When off, AUTO behaves like VANILLA.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> stateAwarePlacement = sgPlacement.add(new BoolSetting.Builder()
            .name("state-aware-placement")
            .description("Try placement sides to reproduce non-default states. Verification reports states that cannot be reproduced.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> fallbackPlacement = sgPlacement.add(new BoolSetting.Builder()
            .name("fallback-placement")
            .description("If one placement method fails, retry once with the other method.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> maxPlaceAttempts = sgPlacement.add(new IntSetting.Builder()
            .name("max-place-attempts")
            .description("Placement attempts before a position is failed or fallback is used.")
            .defaultValue(6)
            .range(1, 20)
            .build()
    );

    public final Setting<Integer> maxStateAttempts = sgPlacement.add(new IntSetting.Builder()
            .name("max-state-attempts")
            .description("Extra attempts used when the block type is right but the state is wrong.")
            .defaultValue(4)
            .range(0, 12)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> verifyDelayTicks = sgPlacement.add(new IntSetting.Builder()
            .name("verify-delay-ticks")
            .description("Ticks to wait before checking that a placement actually became the expected block. Placements are verified concurrently, so this no longer serialises printing.")
            .defaultValue(5)
            .range(1, 100)
            .build()
    );

    public final Setting<Integer> verificationTimeoutTicks = sgPlacement.add(new IntSetting.Builder()
            .name("verification-timeout-ticks")
            .description("How long a sent placement may take to appear before it is counted as a failed attempt. Covers server lag without sending duplicate packets.")
            .defaultValue(60)
            .range(5, 400)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> retryDelayTicks = sgPlacement.add(new IntSetting.Builder()
            .name("retry-delay-ticks")
            .description("Ticks between placement/state retries for the same position. Lower is faster but can spam a rejecting server.")
            .defaultValue(3)
            .range(1, 40)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> placementDelayTicks = sgPlacement.add(new IntSetting.Builder()
            .name("placement-delay-ticks")
            .description("Minimum ticks between starting placements. 0 uses the packet quota as the only limiter.")
            .defaultValue(0)
            .range(0, 20)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> maxPendingVerifications = sgPlacement.add(new IntSetting.Builder()
            .name("max-pending-verifications")
            .description("Maximum placements allowed to await verification at once. Bounds memory and recovery work while still allowing a fast pipeline.")
            .defaultValue(16)
            .range(1, 128)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<SpeedPreset> speedPreset = sgPlacement.add(new EnumSetting.Builder<SpeedPreset>()
            .name("speed-preset")
            .description("Applies a coherent set of action budget, verification, retry and placement-delay values. Individual settings remain editable afterwards.")
            .defaultValue(SpeedPreset.BALANCED)
            .onChanged(this::applySpeedPreset)
            .build()
    );

    public final Setting<List<String>> placementRules = sgPlacement.add(new StringListSetting.Builder()
            .name("block-method-rules")
            .description("Per-block placement method: block=method, #category=method, *=method.")
            .defaultValue(List.of(
                    "minecraft:torch=VANILLA",
                    "minecraft:ladder=VANILLA",
                    "minecraft:redstone_wire=VANILLA",
                    "minecraft:rail=VANILLA",
                    "minecraft:powered_rail=VANILLA",
                    "minecraft:detector_rail=VANILLA",
                    "minecraft:activator_rail=VANILLA"
            ))
            .visible(this::showAdvanced)
            .onChanged(value -> AutoBuildEngine.get().invalidateRules())
            .build()
    );

    public final Setting<Boolean> autoTravelToTargets = sgPlacement.add(new BoolSetting.Builder()
            .name("auto-travel-to-targets")
            .description("Use Baritone to move within reach when a target is too far away.")
            .defaultValue(true)
            .build()
    );

    // Correction ---------------------------------------------------------------------------------

    public final Setting<MismatchAction> defaultTypeAction = sgCorrection.add(new EnumSetting.Builder<MismatchAction>()
            .name("default-type-action")
            .description("What to do when the block type differs: replace, ignore or report.")
            .defaultValue(MismatchAction.REPLACE)
            .build()
    );

    public final Setting<MismatchAction> defaultStateAction = sgCorrection.add(new EnumSetting.Builder<MismatchAction>()
            .name("default-state-action")
            .description("What to do when only properties differ. Ignore by default; correct is best-effort.")
            .defaultValue(MismatchAction.IGNORE)
            .build()
    );

    public final Setting<MismatchAction> defaultExtraAction = sgCorrection.add(new EnumSetting.Builder<MismatchAction>()
            .name("default-extra-action")
            .description("Action for extra blocks when extra removal is enabled.")
            .defaultValue(MismatchAction.REMOVE)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> removeExtraBlocks = sgCorrection.add(new BoolSetting.Builder()
            .name("remove-extra-blocks")
            .description("Remove non-schematic blocks inside the build box only. Never touches terrain outside the box.")
            .defaultValue(false)
            .build()
    );

    public final Setting<Boolean> protectContainers = sgCorrection.add(new BoolSetting.Builder()
            .name("protect-containers")
            .description("Never break chests, barrels, shulkers, hoppers or other container blocks.")
            .defaultValue(true)
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> protectedBlocks = sgCorrection.add(new BlockListSetting.Builder()
            .name("protected-blocks")
            .description("Block types that must never be broken by mismatch correction.")
            .defaultValue(List.of(
                    Blocks.BEDROCK,
                    Blocks.BARRIER,
                    Blocks.COMMAND_BLOCK,
                    Blocks.CHAIN_COMMAND_BLOCK,
                    Blocks.REPEATING_COMMAND_BLOCK,
                    Blocks.STRUCTURE_BLOCK,
                    Blocks.JIGSAW,
                    Blocks.END_PORTAL_FRAME,
                    Blocks.REINFORCED_DEEPSLATE
            ))
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> ignoreTypeBlocks = sgCorrection.add(new BlockListSetting.Builder()
            .name("ignore-type-blocks")
            .description("If the existing block is one of these, a type mismatch is ignored.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> alwaysReplaceBlocks = sgCorrection.add(new BlockListSetting.Builder()
            .name("always-replace-blocks")
            .description("If the existing block is one of these, a type mismatch is always replaced (unless protected).")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> ignoreStateBlocks = sgCorrection.add(new BlockListSetting.Builder()
            .name("ignore-state-blocks")
            .description("Never correct property mismatches on these block types.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> extraBlockIgnore = sgCorrection.add(new BlockListSetting.Builder()
            .name("extra-block-ignore")
            .description("Extra blocks of these types are never removed, even when extra-block removal is enabled.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<net.minecraft.block.Block>> alwaysCorrectStateBlocks = sgCorrection.add(new BlockListSetting.Builder()
            .name("always-correct-state-blocks")
            .description("Always try to correct property mismatches on these block types.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<String>> ignoredProperties = sgCorrection.add(new StringListSetting.Builder()
            .name("ignored-properties")
            .description("Property names ignored during state comparison unless marked required.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<String>> requiredProperties = sgCorrection.add(new StringListSetting.Builder()
            .name("required-properties")
            .description("Property names that always matter, overriding ignore rules.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<List<String>> blockPropertyRules = sgCorrection.add(new StringListSetting.Builder()
            .name("block-property-rules")
            .description("Per-block property mode: blockId|property=IGNORE, REPORT or CORRECT.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<String> sessionRule = sgCorrection.add(new StringSetting.Builder()
            .name("session-rule")
            .description("Highest-priority scope: type=REPLACE,state=IGNORE,extra=REMOVE,ignore=a|b,require=b.")
            .defaultValue("")
            .visible(this::showExpert)
            .wide()
            .build()
    );

    public final Setting<List<String>> schematicRules = sgCorrection.add(new StringListSetting.Builder()
            .name("schematic-rules")
            .description("Schematic scope: schematicName|type=...,state=...,ignore=... .")
            .defaultValue(List.of())
            .visible(this::showExpert)
            .build()
    );

    public final Setting<List<String>> layerRules = sgCorrection.add(new StringListSetting.Builder()
            .name("layer-rules")
            .description("Layer scope: layerCoordinate|type=...,state=...,ignore=... .")
            .defaultValue(List.of())
            .visible(this::showExpert)
            .build()
    );

    public final Setting<List<String>> blockCorrectionRules = sgCorrection.add(new StringListSetting.Builder()
            .name("block-correction-rules")
            .description("Block scope: blockId|type=...,state=...,ignore=... .")
            .defaultValue(List.of())
            .visible(this::showExpert)
            .build()
    );

    public final Setting<List<String>> categoryCorrectionRules = sgCorrection.add(new StringListSetting.Builder()
            .name("category-correction-rules")
            .description("Category scope: categoryName|type=...,state=... . Categories include STAIRS, LIGHT, CONTAINER, REDSTONE.")
            .defaultValue(List.of())
            .visible(this::showExpert)
            .build()
    );

    public final Setting<List<String>> protectedPositions = sgCorrection.add(new StringListSetting.Builder()
            .name("protected-positions")
            .description("Positions that must never be broken: x,y,z or x,y,z,dimension.")
            .defaultValue(List.of())
            .visible(this::showAdvanced)
            .build()
    );

    // Breaking -----------------------------------------------------------------------------------

    public final Setting<Integer> breakTimeoutTicks = sgBreaking.add(new IntSetting.Builder()
            .name("break-timeout-ticks")
            .description("Maximum ticks spent breaking one block before it is retried or failed.")
            .defaultValue(400)
            .range(20, 2400)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> maxBreakAttempts = sgBreaking.add(new IntSetting.Builder()
            .name("max-break-attempts")
            .description("Break timeout retries before a block is failed.")
            .defaultValue(3)
            .range(1, 10)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> protectBlockEntities = sgBreaking.add(new BoolSetting.Builder()
            .name("protect-block-entities")
            .description("Never break signs, beds, banners, spawners and other block entities, even when they are not containers.")
            .defaultValue(true)
            .build()
    );

    // Materials ----------------------------------------------------------------------------------

    public final Setting<Boolean> planMaterials = sgMaterials.add(new BoolSetting.Builder()
            .name("plan-materials")
            .description("Scan the job first to calculate required materials and shortages. Disable for huge jobs where reactive restocking is preferred.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> restockEnabled = sgMaterials.add(new BoolSetting.Builder()
            .name("restock")
            .description("Travel to storage waypoints and withdraw missing materials from real containers.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Integer> restockThreshold = sgMaterials.add(new IntSetting.Builder()
            .name("restock-threshold")
            .description("Loose blocks below this amount trigger a restock at the end of a layer. Default 64 (one stack).")
            .defaultValue(64)
            .range(0, 2304)
            .build()
    );

    public final Setting<Integer> restockTargetStacks = sgMaterials.add(new IntSetting.Builder()
            .name("restock-target-stacks")
            .description("Loose stock target in stacks when restocking (2 stacks = 128).")
            .defaultValue(2)
            .range(1, 27)
            .build()
    );

    public final Setting<Integer> emergencyThreshold = sgMaterials.add(new IntSetting.Builder()
            .name("emergency-threshold")
            .description("Restock immediately when a needed material falls to this loose amount.")
            .defaultValue(8)
            .range(0, 256)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> continueWithAvailable = sgMaterials.add(new BoolSetting.Builder()
            .name("continue-with-available")
            .description("Keep building positions whose materials are available instead of pausing on the first shortage.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Integer> unavailableRecheckSeconds = sgMaterials.add(new IntSetting.Builder()
            .name("unavailable-recheck-seconds")
            .description("How often positions skipped for missing materials are rechecked.")
            .defaultValue(30)
            .range(5, 600)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> maxUnavailableRechecks = sgMaterials.add(new IntSetting.Builder()
            .name("max-unavailable-rechecks")
            .description("Bounded rechecks before an unavailable position is marked failed. Prevents infinite loops.")
            .defaultValue(3)
            .range(0, 20)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> maxRestockAttempts = sgMaterials.add(new IntSetting.Builder()
            .name("max-restock-attempts")
            .description("Maximum restock runs per job before automatic restocking is disabled.")
            .defaultValue(4)
            .range(1, 20)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> restockAtLayerEnd = sgMaterials.add(new BoolSetting.Builder()
            .name("restock-at-layer-end")
            .description("Proactively refill materials that are below the restock threshold before the next layer.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> scanBudgetPerTick = sgMaterials.add(new IntSetting.Builder()
            .name("scan-budget-per-tick")
            .description("Positions inspected per tick during material and layer scanning.")
            .defaultValue(20000)
            .range(500, 200000)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<Integer> maxScanVolume = sgMaterials.add(new IntSetting.Builder()
            .name("max-scan-volume")
            .description("Refuse jobs whose box is larger than this many positions. Select a region or layer range for huge schematics.")
            .defaultValue(2_000_000)
            .range(10_000, 50_000_000)
            .visible(this::showExpert)
            .build()
    );

    // Stashes ------------------------------------------------------------------------------------

    public final Setting<Double> stashRadius = sgStashes.add(new DoubleSetting.Builder()
            .name("stash-radius")
            .description("How far around a general storage waypoint to search for a real container.")
            .defaultValue(8.0)
            .range(1.0, 32.0)
            .sliderRange(1.0, 16.0)
            .build()
    );

    public final Setting<Integer> containerOpenTimeoutTicks = sgStashes.add(new IntSetting.Builder()
            .name("container-open-timeout-ticks")
            .description("Ticks to wait for a container screen after interacting.")
            .defaultValue(80)
            .range(20, 400)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> withdrawPerTick = sgStashes.add(new IntSetting.Builder()
            .name("withdraw-per-tick")
            .description("Container quick-move clicks per tick.")
            .defaultValue(2)
            .range(1, 10)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> inventoryReserveSlots = sgStashes.add(new IntSetting.Builder()
            .name("inventory-reserve-slots")
            .description("Inventory slots kept free for shulker placement and recovery.")
            .defaultValue(2)
            .range(0, 16)
            .build()
    );

    public final Setting<Boolean> restockShulkers = sgStashes.add(new BoolSetting.Builder()
            .name("restock-shulkers")
            .description("Withdraw shulker boxes that contain needed materials and unload them above the player.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Integer> maxCarriedShulkers = sgStashes.add(new IntSetting.Builder()
            .name("max-carried-shulkers")
            .description("Maximum shulker boxes withdrawn per restock run and carried for unloading.")
            .defaultValue(4)
            .range(1, 27)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> preferredShulkerFill = sgStashes.add(new IntSetting.Builder()
            .name("preferred-shulker-fill")
            .description("Prefer shulkers at or above this fill percentage for large jobs.")
            .defaultValue(75)
            .range(0, 100)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> preferFullShulkers = sgStashes.add(new BoolSetting.Builder()
            .name("prefer-full-shulkers")
            .description("Sort shulker offers by fill percentage for large jobs.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> preferPartialShulkers = sgStashes.add(new BoolSetting.Builder()
            .name("prefer-partial-shulkers")
            .description("Allow small jobs to use partially filled shulkers when loose stock is short.")
            .defaultValue(true)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> preferExactStackCollection = sgStashes.add(new BoolSetting.Builder()
            .name("prefer-exact-stack-collection")
            .description("Use loose stacks before opening shulkers, preserving shulkers for larger jobs.")
            .defaultValue(true)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<Boolean> exactStackCollection = sgStashes.add(new BoolSetting.Builder()
            .name("exact-stack-collection")
            .description("Planner targets only the missing amount. Withdrawal works in whole stacks, so the last stack can overshoot.")
            .defaultValue(true)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<Integer> twoStackThreshold = sgStashes.add(new IntSetting.Builder()
            .name("two-stack-threshold")
            .description("Jobs needing more than this many stacks prefer full shulkers.")
            .defaultValue(2)
            .range(1, 27)
            .visible(this::showExpert)
            .build()
    );

    public final Setting<List<String>> materialPriority = sgStashes.add(new StringListSetting.Builder()
            .name("material-priority")
            .description("Item ids to restock first, in order.")
            .defaultValue(List.of())
            .visible(this::showExpert)
            .build()
    );

    // Shulkers -----------------------------------------------------------------------------------

    public final Setting<Boolean> unloadShulkers = sgShulkers.add(new BoolSetting.Builder()
            .name("unload-shulkers")
            .description("Place carried shulkers above the player, empty them and recover the box.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> shulkerAboveHead = sgShulkers.add(new BoolSetting.Builder()
            .name("shulker-above-head")
            .description("Prefer placing the shulker two blocks above the player.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> shulkerFallback = sgShulkers.add(new BoolSetting.Builder()
            .name("shulker-fallback")
            .description("Try adjacent reachable positions when the position above the head is blocked.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Integer> shulkerPlaceRetries = sgShulkers.add(new IntSetting.Builder()
            .name("shulker-place-retries")
            .description("Placement retries before the shulker operation fails.")
            .defaultValue(3)
            .range(1, 10)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> shulkerPickupTimeoutTicks = sgShulkers.add(new IntSetting.Builder()
            .name("shulker-pickup-timeout-ticks")
            .description("Ticks to wait for the recovered shulker item to enter the inventory.")
            .defaultValue(120)
            .range(20, 600)
            .visible(this::showAdvanced)
            .build()
    );

    // Travel -------------------------------------------------------------------------------------

    public final Setting<TravelMode> travelMode = sgTravel.add(new EnumSetting.Builder<TravelMode>()
            .name("travel-mode")
            .description("AUTO walks short trips and uses Baritone elytra for trips at least elytra-min-distance. A long trip never silently falls back to walking.")
            .defaultValue(TravelMode.AUTO)
            .build()
    );

    public final Setting<Integer> travelTimeoutTicks = sgTravel.add(new IntSetting.Builder()
            .name("travel-timeout-ticks")
            .description("Timeout per Baritone travel attempt before a retry.")
            .defaultValue(1200)
            .range(100, 12000)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> travelRetries = sgTravel.add(new IntSetting.Builder()
            .name("travel-retries")
            .description("Baritone travel retries before the target is skipped.")
            .defaultValue(2)
            .range(1, 8)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> travelStallGraceTicks = sgTravel.add(new IntSetting.Builder()
            .name("travel-stall-grace-ticks")
            .description("Ticks Baritone may spend calculating or paused before a trip with no movement is treated as stalled.")
            .defaultValue(60)
            .range(20, 600)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> targetTravelTimeoutTicks = sgTravel.add(new IntSetting.Builder()
            .name("target-travel-timeout-ticks")
            .description("Timeout for a short local walk to a build target or nearby container. Never uses elytra.")
            .defaultValue(240)
            .range(40, 2400)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Integer> targetTravelRetries = sgTravel.add(new IntSetting.Builder()
            .name("target-travel-retries")
            .description("Retries for a short local walk to a build target before that position is failed.")
            .defaultValue(2)
            .range(1, 5)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Double> arrivalDistance = sgTravel.add(new DoubleSetting.Builder()
            .name("arrival-distance")
            .description("Distance considered close enough when travelling to a stash or target.")
            .defaultValue(3.0)
            .range(1.0, 12.0)
            .sliderRange(1.0, 8.0)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Double> elytraMinDistance = sgTravel.add(new DoubleSetting.Builder()
            .name("elytra-min-distance")
            .description("Trips at least this long use elytra in AUTO mode. Requires an Overworld-capable Baritone build, a worn elytra and firework rockets.")
            .defaultValue(100.0)
            .range(16.0, 4096.0)
            .sliderRange(16.0, 1024.0)
            .visible(this::showAdvanced)
            .build()
    );

    public final Setting<Boolean> acceptBaritoneElytraTerms = sgTravel.add(new BoolSetting.Builder()
            .name("accept-baritone-elytra-terms")
            .description("Set Baritone's elytraTermsAccepted flag at runtime when elytra travel is requested. Read Baritone's terms first.")
            .defaultValue(false)
            .visible(this::showExpert)
            .build()
    );

    // Keybinds -----------------------------------------------------------------------------------

    public final Setting<Keybind> startKey = sgKeys.add(new KeybindSetting.Builder()
            .name("start-key")
            .description("Start a build with the selected Litematica placement.")
            .defaultValue(Keybind.none())
            .action(() -> {
                if (!isActive()) toggle();
                if (isActive() && !AutoBuildEngine.get().isRunning()) {
                    AutoBuildEngine.get().start(this);
                }
            })
            .build()
    );

    public final Setting<Keybind> pauseKey = sgKeys.add(new KeybindSetting.Builder()
            .name("pause-key")
            .description("Pause the current job.")
            .defaultValue(Keybind.none())
            .action(() -> AutoBuildEngine.get().pause("pause keybind"))
            .build()
    );

    public final Setting<Keybind> resumeKey = sgKeys.add(new KeybindSetting.Builder()
            .name("resume-key")
            .description("Resume the current job.")
            .defaultValue(Keybind.none())
            .action(() -> AutoBuildEngine.get().resume())
            .build()
    );

    public final Setting<Keybind> stopKey = sgKeys.add(new KeybindSetting.Builder()
            .name("stop-key")
            .description("Stop safely, save progress and cancel travel/restocking.")
            .defaultValue(Keybind.none())
            .action(() -> AutoBuildEngine.get().stop("stop keybind"))
            .build()
    );

    public final Setting<Keybind> reloadKey = sgKeys.add(new KeybindSetting.Builder()
            .name("reload-key")
            .description("Reload the Litematica selection and rebuild the job.")
            .defaultValue(Keybind.none())
            .action(() -> AutoBuildEngine.get().reloadJob())
            .build()
    );

    public final Setting<Keybind> materialsKey = sgKeys.add(new KeybindSetting.Builder()
            .name("materials-key")
            .description("Recalculate materials and print the shortage report.")
            .defaultValue(Keybind.none())
            .action(() -> AutoBuildEngine.get().recalculateMaterials())
            .build()
    );

    public AutoBuilder() {
        super(SevenBuilderAddon.CATEGORY, "7-loves-ella-langley-auto-builder", "Fully automatic Litematica schematic builder with storage waypoints, materials, restocking and Baritone travel.");
    }

    @Override
    public void onActivate() {
        AutoBuildEngine.get().onModuleActivated(this);
    }

    @Override
    public void onDeactivate() {
        AutoBuildEngine.get().onModuleDeactivated(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        AutoBuildEngine.get().tick();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        AutoBuildEngine.get().onGameJoined();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        AutoBuildEngine.get().onGameLeft();
    }

    @Override
    public String getInfoString() {
        return AutoBuildEngine.get().statusShort();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        AutoBuildEngine engine = AutoBuildEngine.get();
        WVerticalList root = theme.verticalList();

        root.add(theme.label(SevenBuilderAddon.DISPLAY_NAME, true)).expandX();
        WLabel state = root.add(theme.label("State: " + engine.state().title()
                + (engine.isPaused() ? " (paused)" : ""))).expandX().widget();
        state.color = theme.textSecondaryColor();
        WLabel layerBounds = root.add(theme.label(engine.layerBoundsText())).expandX().widget();
        layerBounds.color = theme.textSecondaryColor();
        WLabel printing = root.add(theme.label(engine.printStatsText())).expandX().widget();
        printing.color = theme.textSecondaryColor();

        WHorizontalList actions = root.add(theme.horizontalList()).expandX().widget();
        WButton start = actions.add(theme.button("Start")).widget();
        start.tooltip = "Enable the module and start building from the current Litematica placement.";
        start.action = () -> {
            if (!isActive()) toggle();
            AutoBuildEngine.get().start(this);
        };
        WButton pause = actions.add(theme.button("Pause")).widget();
        pause.action = () -> AutoBuildEngine.get().pause("module GUI");
        WButton resume = actions.add(theme.button("Resume")).widget();
        resume.action = () -> AutoBuildEngine.get().resume();
        WButton stop = actions.add(theme.button("Stop")).widget();
        stop.action = () -> AutoBuildEngine.get().stop("module GUI");
        WButton restock = actions.add(theme.button("Restock Now")).widget();
        restock.tooltip = "Travel to enabled storage waypoints and withdraw missing materials.";
        restock.action = () -> AutoBuildEngine.get().requestRestock();

        root.add(theme.horizontalSeparator("Build tools")).expandX();
        WHorizontalList tools = root.add(theme.horizontalList()).expandX().widget();
        WButton storage = tools.add(theme.button("Storage Waypoints...")).widget();
        storage.tooltip = "Create, edit, navigate and prioritize the containers used for restocking.";
        storage.action = () -> mc.setScreen(new StorageManagerScreen(theme, this));
        WButton profiles = tools.add(theme.button("Profiles...")).widget();
        profiles.tooltip = "Save, load, duplicate, rename, export, import or reset profiles.";
        profiles.action = () -> mc.setScreen(new ProfilesScreen(theme, mc.currentScreen));

        WHorizontalList ruleTools = root.add(theme.horizontalList()).expandX().widget();
        WButton propertyRules = ruleTools.add(theme.button("State Property Rules...")).widget();
        propertyRules.tooltip = "Choose a block and configure IGNORE / REPORT / CORRECT per state property.";
        propertyRules.action = () -> mc.setScreen(new PropertyRulesScreen(theme, blockPropertyRules.get(),
                mc.currentScreen, value -> {
                    blockPropertyRules.set(value);
                    AutoBuildEngine.get().invalidateRules();
                }));
        WButton placementRulesButton = ruleTools.add(theme.button("Placement Rules...")).widget();
        placementRulesButton.tooltip = "Choose the placement method used for specific blocks.";
        placementRulesButton.action = () -> mc.setScreen(new PlacementRulesScreen(theme, placementRules.get(),
                mc.currentScreen, value -> {
                    placementRules.set(value);
                    AutoBuildEngine.get().invalidateRules();
                }));

        root.add(theme.horizontalSeparator("Visual block lists")).expandX();
        WTable lists = root.add(theme.table()).expandX().widget();
        addBlockListRow(theme, lists, "Protected blocks", protectedBlocks);
        addBlockListRow(theme, lists, "Ignore type blocks", ignoreTypeBlocks);
        addBlockListRow(theme, lists, "Always replace blocks", alwaysReplaceBlocks);
        addBlockListRow(theme, lists, "Ignore state blocks", ignoreStateBlocks);
        addBlockListRow(theme, lists, "Always correct state blocks", alwaysCorrectStateBlocks);
        addBlockListRow(theme, lists, "Extra block ignore", extraBlockIgnore);

        return root;
    }

    private void addBlockListRow(GuiTheme theme, WTable table, String label,
                                 Setting<List<Block>> setting) {
        table.add(theme.label(label)).widget();
        WLabel count = table.add(theme.label(setting.get().size() + " block(s)")).widget();
        count.color = theme.textSecondaryColor();
        WButton edit = table.add(theme.button("Pick Blocks...")).widget();
        edit.action = () -> BlockPickerScreen.open(theme, label, setting.get(), mc.currentScreen,
                value -> {
                    setting.set(value);
                    AutoBuildEngine.get().invalidateRules();
                    count.set(value.size() + " block(s)");
                });
        table.row();
    }

    private boolean showAdvanced() {
        return settingsLevel.get().atLeast(SettingsLevel.ADVANCED);
    }

    private boolean applyingSpeedPreset;

    private void applySpeedPreset(SpeedPreset preset) {
        if (preset == null || applyingSpeedPreset) return;
        applyingSpeedPreset = true;
        try {
            actionsPerTick.set(preset.actionsPerTick());
            verifyDelayTicks.set(preset.verifyDelayTicks());
            retryDelayTicks.set(preset.retryDelayTicks());
            maxPendingVerifications.set(preset.maxPendingVerifications());
            placementDelayTicks.set(preset.placementDelayTicks());
        } finally {
            applyingSpeedPreset = false;
        }
        AutoBuildEngine.get().invalidateJob();
    }

    private boolean showExpert() {
        return settingsLevel.get().atLeast(SettingsLevel.EXPERT);
    }

    private void structuralChange() {
        AutoBuildEngine.get().invalidateJob();
    }
}
