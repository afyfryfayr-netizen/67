package sleepy.autobuilder.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.Block;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.AutoBuildEngine;
import sleepy.autobuilder.autobuild.AutoBuilder;
import sleepy.autobuilder.autobuild.ProfileManager;
import sleepy.autobuilder.autobuild.SettingsLevel;
import sleepy.autobuilder.autobuild.mc.TravelService;
import sleepy.autobuilder.autobuild.storage.StorageDetector;
import sleepy.autobuilder.autobuild.storage.StorageEntry;
import sleepy.autobuilder.autobuild.storage.StorageManager;
import sleepy.autobuilder.autobuild.storage.StorageNavigator;
import sleepy.autobuilder.autobuild.storage.StorageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-game controls for the Auto Builder. The same operations are available from the Meteor GUI;
 * this command exists for keybind/macro users and for servers where opening the GUI is slow.
 */
public class AutoBuilderCommand extends Command {
    public AutoBuilderCommand() {
        super("autobuilder", "Controls the automatic schematic builder.", "autob");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("start").executes(context -> startJob()));
        builder.then(literal("pause").executes(context -> {
            AutoBuildEngine.get().pause("command");
            info("Pause requested.");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("resume").executes(context -> {
            AutoBuildEngine.get().resume();
            info("Resume requested. State: %s", AutoBuildEngine.get().state().title());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("stop").executes(context -> {
            AutoBuildEngine.get().stop("command");
            info("Stop requested. Progress is saved.");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("status").executes(context -> status()));
        builder.then(literal("materials").executes(context -> materials()));
        builder.then(literal("restock").executes(context -> restock()));
        builder.then(literal("baritone").executes(context -> {
            for (String line : TravelService.capabilityReport().split("\\n")) {
                info("%s", line);
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("level")
                .then(argument("level", StringArgumentType.word()).executes(context -> {
                    String raw = StringArgumentType.getString(context, "level").toUpperCase(Locale.ROOT);
                    try {
                        SettingsLevel level = SettingsLevel.valueOf(raw);
                        AutoBuilder module = module();
                        if (module == null) return notReady();
                        module.settingsLevel.set(level);
                        info("Settings visibility set to %s.", level.name().toLowerCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        error("Expected basic, advanced or expert.");
                    }
                    return SINGLE_SUCCESS;
                })));

        builder.then(literal("search")
                .then(argument("query", StringArgumentType.greedyString()).executes(context -> {
                    AutoBuilder module = module();
                    if (module == null) return notReady();
                    String query = StringArgumentType.getString(context, "query").toLowerCase(Locale.ROOT);
                    int shown = 0;
                    for (SettingGroup group : module.settings) {
                        for (Setting<?> setting : group) {
                            if (!matches(setting, query)) continue;
                            if (shown == 0) info("Matching settings (group: value):");
                            info("%s (%s): %s", setting.name, group.name, valueOf(setting));
                            if (++shown >= 15) {
                                info("Showing the first 15 matches.");
                                return SINGLE_SUCCESS;
                            }
                        }
                    }
                    if (shown == 0) warning("No setting matches '%s'.", query);
                    return SINGLE_SUCCESS;
                })));

        builder.then(literal("set")
                .then(argument("setting", StringArgumentType.word())
                        .then(argument("value", StringArgumentType.greedyString()).executes(context -> {
                            AutoBuilder module = module();
                            if (module == null) return notReady();
                            String name = StringArgumentType.getString(context, "setting");
                            String value = StringArgumentType.getString(context, "value");
                            Setting<?> setting = module.settings.get(name);
                            if (setting == null) {
                                error("Unknown setting '%s'. Use .autob search <text> to find names.", name);
                                return SINGLE_SUCCESS;
                            }
                            String before = valueOf(setting);
                            if (!setting.parse(value)) {
                                error("Could not parse '%s' for %s (current value: %s).", value, setting.name, before);
                                return SINGLE_SUCCESS;
                            }
                            AutoBuildEngine.get().invalidateRules();
                            info("%s: %s -> %s", setting.name, before, valueOf(setting));
                            return SINGLE_SUCCESS;
                        }))));

        builder.then(literal("stash")
                .then(literal("add")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(context -> addStash(context, false))
                                                .then(argument("dimension", StringArgumentType.word())
                                                        .executes(context -> addStash(context, true)))))))
                .then(literal("list").executes(context -> listStashes()))
                .then(literal("remove")
                        .then(argument("index", IntegerArgumentType.integer(1)).executes(context -> {
                            List<StorageEntry> entries = StorageManager.get().list();
                            int index = IntegerArgumentType.getInteger(context, "index");
                            if (index > entries.size()) {
                                error("Storage index %d is out of range (1..%d).", index, entries.size());
                                return SINGLE_SUCCESS;
                            }
                            StorageEntry removed = entries.get(index - 1);
                            StorageManager.get().remove(removed.id);
                            info("Removed storage %d ('%s' at %s).", index, removed.name, removed.coords());
                            return SINGLE_SUCCESS;
                        })))
                .then(literal("clear").executes(context -> {
                    int count = StorageManager.get().list().size();
                    StorageManager.get().clear();
                    info("Cleared %d storage waypoint(s).", count);
                    return SINGLE_SUCCESS;
                }))
                .then(literal("goto")
                        .then(argument("index", IntegerArgumentType.integer(1)).executes(context -> {
                            List<StorageEntry> entries = StorageManager.get().list();
                            int index = IntegerArgumentType.getInteger(context, "index");
                            if (index > entries.size()) {
                                error("Storage index %d is out of range (1..%d).", index, entries.size());
                                return SINGLE_SUCCESS;
                            }
                            StorageEntry entry = entries.get(index - 1);
                            boolean ok = StorageNavigator.get().navigate(entry);
                            if (ok) info("%s", StorageNavigator.get().message());
                            else error("%s", StorageNavigator.get().message());
                            return SINGLE_SUCCESS;
                        })))
                .then(literal("toggle")
                        .then(argument("index", IntegerArgumentType.integer(1)).executes(context -> {
                            List<StorageEntry> entries = StorageManager.get().list();
                            int index = IntegerArgumentType.getInteger(context, "index");
                            if (index > entries.size()) {
                                error("Storage index %d is out of range (1..%d).", index, entries.size());
                                return SINGLE_SUCCESS;
                            }
                            StorageEntry entry = entries.get(index - 1);
                            entry.enabled = !entry.enabled;
                            StorageManager.get().update(entry);
                            info("Storage '%s' is now %s.", entry.name, entry.enabled ? "enabled" : "disabled");
                            return SINGLE_SUCCESS;
                        })))
                .then(literal("rename")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                                    List<StorageEntry> entries = StorageManager.get().list();
                                    int index = IntegerArgumentType.getInteger(context, "index");
                                    if (index > entries.size()) {
                                        error("Storage index %d is out of range (1..%d).", index, entries.size());
                                        return SINGLE_SUCCESS;
                                    }
                                    StorageEntry entry = entries.get(index - 1);
                                    String name = StringArgumentType.getString(context, "name").trim();
                                    if (name.isEmpty() || name.length() > 64) {
                                        error("The storage name must be 1-64 characters.");
                                        return SINGLE_SUCCESS;
                                    }
                                    entry.name = name;
                                    StorageManager.get().update(entry);
                                    info("Renamed storage %d to '%s'.", index, name);
                                    return SINGLE_SUCCESS;
                                })))));

        builder.then(literal("protect")
                .then(literal("here").executes(context -> {
                    if (mc.player == null) {
                        error("Join a world first.");
                        return SINGLE_SUCCESS;
                    }
                    BlockPos pos = mc.player.getBlockPos();
                    return addProtectedPosition(pos, "");
                }))
                .then(literal("list").executes(context -> listProtectedPositions()))
                .then(literal("clear").executes(context -> {
                    AutoBuilder module = module();
                    if (module == null) return notReady();
                    int count = module.protectedPositions.get().size();
                    module.protectedPositions.set(new ArrayList<>());
                    AutoBuildEngine.get().invalidateRules();
                    info("Cleared %d protected positions.", count);
                    return SINGLE_SUCCESS;
                }))
                .then(argument("x", IntegerArgumentType.integer())
                        .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer()).executes(context -> {
                                    BlockPos pos = new BlockPos(
                                            IntegerArgumentType.getInteger(context, "x"),
                                            IntegerArgumentType.getInteger(context, "y"),
                                            IntegerArgumentType.getInteger(context, "z"));
                                    return addProtectedPosition(pos, "");
                                })))));

        builder.then(literal("rule")
                .then(literal("protect").then(argument("block", StringArgumentType.word())
                        .executes(context -> addBlockRule(context, RuleKind.PROTECT))))
                .then(literal("ignore").then(argument("block", StringArgumentType.word())
                        .executes(context -> addBlockRule(context, RuleKind.IGNORE))))
                .then(literal("replace").then(argument("block", StringArgumentType.word())
                        .executes(context -> addBlockRule(context, RuleKind.REPLACE))))
                .then(literal("state").then(argument("block", StringArgumentType.word())
                        .executes(context -> addBlockRule(context, RuleKind.STATE))))
                .then(literal("list").executes(context -> listRules()))
                .then(literal("clear").executes(context -> {
                    AutoBuilder module = module();
                    if (module == null) return notReady();
                    module.protectedBlocks.set(new ArrayList<>());
                    module.ignoreTypeBlocks.set(new ArrayList<>());
                    module.alwaysReplaceBlocks.set(new ArrayList<>());
                    module.alwaysCorrectStateBlocks.set(new ArrayList<>());
                    AutoBuildEngine.get().invalidateRules();
                    info("Cleared block correction/protection rules.");
                    return SINGLE_SUCCESS;
                })));

        builder.then(literal("profile")
                .then(literal("list").executes(context -> {
                    List<String> names = ProfileManager.get().list();
                    if (names.isEmpty()) {
                        warning("No Auto Builder profiles exist in %s.", ProfileManager.get().directory());
                        return SINGLE_SUCCESS;
                    }
                    info("Auto Builder profiles (%d): %s", names.size(), String.join(", ", names));
                    return SINGLE_SUCCESS;
                }))
                .then(literal("save").then(argument("name", StringArgumentType.string())
                        .executes(context -> profile(ProfileManager.get().save(
                                StringArgumentType.getString(context, "name"))))))
                .then(literal("load").then(argument("name", StringArgumentType.string())
                        .executes(context -> profile(ProfileManager.get().load(
                                StringArgumentType.getString(context, "name"))))))
                .then(literal("delete").then(argument("name", StringArgumentType.string())
                        .executes(context -> profile(ProfileManager.get().delete(
                                StringArgumentType.getString(context, "name"))))))
                .then(literal("duplicate")
                        .then(argument("from", StringArgumentType.string())
                                .then(argument("to", StringArgumentType.string())
                                        .executes(context -> profile(ProfileManager.get().duplicate(
                                                StringArgumentType.getString(context, "from"),
                                                StringArgumentType.getString(context, "to")))))))
                .then(literal("rename")
                        .then(argument("from", StringArgumentType.string())
                                .then(argument("to", StringArgumentType.string())
                                        .executes(context -> profile(ProfileManager.get().rename(
                                                StringArgumentType.getString(context, "from"),
                                                StringArgumentType.getString(context, "to")))))))
                .then(literal("export")
                        .then(argument("name", StringArgumentType.string())
                                .then(argument("path", StringArgumentType.greedyString())
                                        .executes(context -> profile(ProfileManager.get().exportProfile(
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "path")))))))
                .then(literal("import")
                        .then(argument("path", StringArgumentType.greedyString())
                                .executes(context -> profile(ProfileManager.get().importProfile(
                                        StringArgumentType.getString(context, "path"), "")))))
                .then(literal("import-as")
                        .then(argument("path", StringArgumentType.string())
                                .then(argument("name", StringArgumentType.string())
                                        .executes(context -> profile(ProfileManager.get().importProfile(
                                                StringArgumentType.getString(context, "path"),
                                                StringArgumentType.getString(context, "name")))))))
                .then(literal("reset").executes(context -> profile(ProfileManager.get().reset()))));
    }

    private enum RuleKind {PROTECT, IGNORE, REPLACE, STATE}

    private int startJob() {
        AutoBuilder module = module();
        if (module == null) return notReady();
        AutoBuildEngine engine = AutoBuildEngine.get();
        if (!module.isActive()) module.toggle();
        if (engine.isRunning()) {
            info("Auto Builder is already running (%s).", engine.statusShort());
            return SINGLE_SUCCESS;
        }
        if (engine.start(module)) {
            info("Auto Builder started.");
        } else {
            error("Could not start: %s", engine.lastError());
        }
        return SINGLE_SUCCESS;
    }

    private int status() {
        AutoBuildEngine engine = AutoBuildEngine.get();
        info("State: %s", engine.state().title());
        info("Progress: %d%% (%d done, %d ignored, %d pending, %d failed)", engine.progressPercent(),
                engine.completedPositions(), engine.ignoredPositions(),
                engine.pendingPositions(), engine.failedPositions());
        String schematic = engine.liveSchematicName();
        info("Layer: %s   Schematic: %s", engine.currentLayerText(),
                schematic.isBlank() ? "none" : schematic);
        info("Materials: %s", engine.materialSummary());
        info("Printing: %s", engine.printStatsText());
        if (engine.isPaused()) warning("Paused: %s", engine.pauseReason());
        if (engine.isSuspended()) warning("Disconnected; progress is saved and will resume after joining.");
        if (!engine.lastError().isBlank()) error("Last error: %s", engine.lastError());
        return SINGLE_SUCCESS;
    }

    private int materials() {
        AutoBuildEngine engine = AutoBuildEngine.get();
        engine.recalculateMaterials();
        info("Materials: %s", engine.materialSummary());
        return SINGLE_SUCCESS;
    }

    private int restock() {
        AutoBuildEngine engine = AutoBuildEngine.get();
        if (engine.requestRestock()) {
            info("Restock started: %s", engine.state().title());
        } else {
            warning("Restock did not start. State: %s. Check storage waypoints, shulkers and restock settings.",
                    engine.state().title());
        }
        return SINGLE_SUCCESS;
    }

    private int addStash(CommandContext<CommandSource> context, boolean explicitDimension) {
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        BlockPos pos = new BlockPos(x, y, z);
        String dimension;
        if (explicitDimension) {
            dimension = normaliseDimension(StringArgumentType.getString(context, "dimension"));
        } else {
            dimension = StorageDetector.currentDimension();
            if (dimension.isEmpty()) {
                error("Join a world, or provide a dimension: minecraft:overworld / the_nether / the_end.");
                return SINGLE_SUCCESS;
            }
        }
        StorageType type = StorageType.UNKNOWN;
        if (mc.world != null && dimension.equals(StorageDetector.currentDimension())) {
            type = StorageType.detect(mc.world, pos);
        }
        StorageManager manager = StorageManager.get();
        StorageEntry entry = new StorageEntry(manager.nextName("Storage"), pos, dimension, type);
        entry.exactContainer = type.isContainer();
        manager.add(entry);
        info("Added storage '%s' at %s (%s, %s). Total: %d.", entry.name, entry.coords(),
                entry.shortDimension(), entry.exactContainer ? "exact container" : "general waypoint",
                manager.list().size());
        return SINGLE_SUCCESS;
    }

    private int listStashes() {
        List<StorageEntry> entries = StorageManager.get().list();
        if (entries.isEmpty()) {
            info("No storage waypoints configured. Use .autob stash add or the Storage Waypoints GUI.");
            return SINGLE_SUCCESS;
        }
        info("Storage waypoints (%d, highest priority first):", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            StorageEntry entry = entries.get(i);
            info("%d. %s%s - %s (%s, %s) priority %d | %s", i + 1, entry.name,
                    entry.enabled ? "" : " [disabled]", entry.coords(), entry.shortDimension(),
                    entry.exactContainer ? "exact" : "general", entry.priority, entry.statusText());
        }
        return SINGLE_SUCCESS;
    }

    private int addProtectedPosition(BlockPos pos, String dimension) {
        AutoBuilder module = module();
        if (module == null) return notReady();
        String entry = pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + (dimension == null || dimension.isBlank() ? "" : "," + dimension);
        List<String> positions = new ArrayList<>(module.protectedPositions.get());
        if (!positions.contains(entry)) positions.add(entry);
        module.protectedPositions.set(positions);
        AutoBuildEngine.get().invalidateRules();
        info("Protected position %s. This position will never be broken or removed.", entry);
        return SINGLE_SUCCESS;
    }

    private int listProtectedPositions() {
        AutoBuilder module = module();
        if (module == null) return notReady();
        List<String> positions = module.protectedPositions.get();
        if (positions.isEmpty()) info("No protected positions configured.");
        else info("Protected positions (%d): %s", positions.size(), String.join(" | ", positions));
        return SINGLE_SUCCESS;
    }

    private int addBlockRule(CommandContext<CommandSource> context, RuleKind kind) {
        AutoBuilder module = module();
        if (module == null) return notReady();
        String raw = StringArgumentType.getString(context, "block");
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            error("Invalid block id '%s'. Example: minecraft:chest.", raw);
            return SINGLE_SUCCESS;
        }
        Block block = Registries.BLOCK.getOrEmpty(id).orElse(null);
        if (block == null) {
            error("Unknown block id '%s'.", id);
            return SINGLE_SUCCESS;
        }
        List<Block> list = switch (kind) {
            case PROTECT -> module.protectedBlocks.get();
            case IGNORE -> module.ignoreTypeBlocks.get();
            case REPLACE -> module.alwaysReplaceBlocks.get();
            case STATE -> module.alwaysCorrectStateBlocks.get();
        };
        List<Block> next = new ArrayList<>(list);
        if (next.contains(block)) {
            warning("%s is already in the %s rule list.", id, kind.name().toLowerCase(Locale.ROOT));
            return SINGLE_SUCCESS;
        }
        next.add(block);
        switch (kind) {
            case PROTECT -> module.protectedBlocks.set(next);
            case IGNORE -> module.ignoreTypeBlocks.set(next);
            case REPLACE -> module.alwaysReplaceBlocks.set(next);
            case STATE -> module.alwaysCorrectStateBlocks.set(next);
        }
        AutoBuildEngine.get().invalidateRules();
        info("Added %s to the %s list.", id, kind.name().toLowerCase(Locale.ROOT));
        return SINGLE_SUCCESS;
    }

    private int listRules() {
        AutoBuilder module = module();
        if (module == null) return notReady();
        info("Protected blocks (%d): %s", module.protectedBlocks.get().size(),
                blockNames(module.protectedBlocks.get()));
        info("Ignored types (%d): %s", module.ignoreTypeBlocks.get().size(),
                blockNames(module.ignoreTypeBlocks.get()));
        info("Always replace (%d): %s", module.alwaysReplaceBlocks.get().size(),
                blockNames(module.alwaysReplaceBlocks.get()));
        info("Always correct state (%d): %s", module.alwaysCorrectStateBlocks.get().size(),
                blockNames(module.alwaysCorrectStateBlocks.get()));
        return SINGLE_SUCCESS;
    }

    private static String blockNames(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return "none";
        List<String> names = new ArrayList<>(blocks.size());
        for (Block block : blocks) names.add(Registries.BLOCK.getId(block).toString());
        return String.join(", ", names);
    }

    private int profile(ProfileManager.Result result) {
        if (result.ok()) info("%s", result.message());
        else error("%s", result.message());
        return SINGLE_SUCCESS;
    }

    private AutoBuilder module() {
        return Modules.get().get(AutoBuilder.class);
    }

    private int notReady() {
        error("Auto Builder is not registered.");
        return SINGLE_SUCCESS;
    }

    private static boolean matches(Setting<?> setting, String query) {
        return setting.name.toLowerCase(Locale.ROOT).contains(query)
                || setting.title.toLowerCase(Locale.ROOT).contains(query)
                || (setting.description != null
                && setting.description.toLowerCase(Locale.ROOT).contains(query));
    }

    private static String valueOf(Setting<?> setting) {
        try {
            return String.valueOf(setting.get());
        } catch (RuntimeException e) {
            return "<unavailable>";
        }
    }

    private static String normaliseDimension(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.contains(":") ? raw : "minecraft:" + raw;
    }
}
