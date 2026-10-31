package sleepy.autobuilder.autobuild.storage;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.AutoBuilder;

import java.util.Locale;

/**
 * Graphical waypoint-style manager for restock storage. It supports exact container positions and
 * general stash anchors, multiple independent entries, priorities, enable/disable and navigation.
 */
public final class StorageManagerScreen extends WindowScreen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Color ERROR = new Color(255, 90, 90);
    private static final Color OK = new Color(110, 220, 130);

    private final AutoBuilder module;
    private final StorageManager manager = StorageManager.get();

    private WTextBox nameBox;
    private WTextBox xBox;
    private WTextBox yBox;
    private WTextBox zBox;
    private WTextBox dimensionBox;
    private WDropdown<StorageType> typeBox;
    private WCheckbox exactBox;
    private WButton createButton;
    private WButton cancelButton;
    private WLabel messageLabel;
    private WVerticalList listRoot;
    private String editingId;

    public StorageManagerScreen(GuiTheme theme, AutoBuilder module) {
        super(theme, "Storage Waypoints - 7 Loves Ella Langley Auto Builder");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Create and manage the containers the builder visits for restocking. "
                + "Every entry has its own stable id and is saved across restarts.")).expandX();

        WTable form = add(theme.table()).expandX().widget();

        form.add(theme.label("Name:"));
        nameBox = form.add(theme.textBox("")).expandCellX().minWidth(260).widget();
        form.row();

        form.add(theme.label("Position:"));
        WHorizontalList coords = form.add(theme.horizontalList()).widget();
        xBox = coords.add(theme.textBox("")).minWidth(70).widget();
        yBox = coords.add(theme.textBox("")).minWidth(70).widget();
        zBox = coords.add(theme.textBox("")).minWidth(70).widget();
        WHorizontalList setButtons = coords.add(theme.horizontalList()).widget();
        WButton here = setButtons.add(theme.button("Set Here")).widget();
        here.action = this::setHere;
        WButton target = setButtons.add(theme.button("Select Targeted Block")).widget();
        target.action = this::selectTargeted;
        form.row();

        form.add(theme.label("Dimension:"));
        dimensionBox = form.add(theme.textBox("")).expandCellX().minWidth(260).widget();
        form.row();

        form.add(theme.label("Kind:"));
        WHorizontalList kind = form.add(theme.horizontalList()).widget();
        typeBox = kind.add(theme.dropdown(StorageType.values(), StorageType.UNKNOWN)).widget();
        exactBox = kind.add(theme.checkbox(false)).widget();
        kind.add(theme.label("Exact container position (otherwise search around the waypoint)"));
        form.row();

        WHorizontalList actions = add(theme.horizontalList()).widget();
        createButton = actions.add(theme.button("Create")).widget();
        createButton.action = this::createOrSave;
        cancelButton = actions.add(theme.button("Cancel / Reset")).widget();
        cancelButton.action = this::resetForm;

        messageLabel = add(theme.label("")).expandX().widget();
        messageLabel.color = theme.textSecondaryColor();

        add(theme.horizontalSeparator()).expandX();

        listRoot = add(theme.verticalList()).expandX().widget();

        resetForm();
        refreshList();
    }

    private void setHere() {
        if (mc.player == null || mc.world == null) {
            message("Join a world first.", false);
            return;
        }
        BlockPos pos = mc.player.getBlockPos();
        StorageType type = StorageType.detect(mc.world, pos);
        fillPosition(pos, StorageDetector.currentDimension(), type);
        message("Filled the position with your current block position. "
                + (type.isContainer() ? "A " + type.displayName() + " was detected." : "No container is at your feet; this is a general waypoint."), true);
    }

    private void selectTargeted() {
        StorageDetector.Result result = StorageDetector.target();
        if (!result.ok()) {
            message(result.error(), false);
            return;
        }
        fillPosition(result.pos(), result.dimension(), result.type());
        if (nameBox.get().isBlank()) {
            nameBox.set(result.type().displayName() + " " + result.pos().getX() + ", "
                    + result.pos().getY() + ", " + result.pos().getZ());
        }
        message("Selected " + result.type().displayName() + " at " + result.pos().getX() + ", "
                + result.pos().getY() + ", " + result.pos().getZ()
                + " in " + shortDimension(result.dimension()) + ".", true);
    }

    private void fillPosition(BlockPos pos, String dimension, StorageType type) {
        xBox.set(Integer.toString(pos.getX()));
        yBox.set(Integer.toString(pos.getY()));
        zBox.set(Integer.toString(pos.getZ()));
        dimensionBox.set(dimension == null ? "" : dimension);
        typeBox.set(type == null ? StorageType.UNKNOWN : type);
        exactBox.checked = type != null && type.isContainer();
    }

    private void createOrSave() {
        Integer x = parseInt(xBox.get());
        Integer y = parseInt(yBox.get());
        Integer z = parseInt(zBox.get());
        if (x == null || y == null || z == null) {
            message("X, Y and Z must be whole numbers.", false);
            return;
        }
        String name = nameBox.get().trim();
        if (name.isEmpty()) name = manager.nextName("Storage");
        if (name.length() > 64) {
            message("The storage name must be 64 characters or fewer.", false);
            return;
        }
        String dimension = normaliseDimension(dimensionBox.get());
        if (dimension.isEmpty()) {
            message("A dimension is required. Use Set Here or enter e.g. minecraft:overworld.", false);
            return;
        }
        StorageType type = typeBox.get() == null ? StorageType.UNKNOWN : typeBox.get();
        boolean exact = exactBox.checked && type.isContainer();
        if (exactBox.checked && !type.isContainer()) {
            message("Select a container type, or disable the exact-container checkbox for a general waypoint.", false);
            return;
        }

        String current = StorageDetector.currentDimension();
        if (exact && dimension.equalsIgnoreCase(current) && mc.world != null) {
            StorageType detected = StorageType.detect(mc.world, new BlockPos(x, y, z));
            if (!detected.isContainer()) {
                message("No supported container exists at " + x + ", " + y + ", " + z
                        + " in the current dimension.", false);
                return;
            }
            if (type != StorageType.OTHER_CONTAINER && detected != type) {
                message("The block at those coordinates is a " + detected.displayName()
                        + ", not a " + type.displayName() + ".", false);
                return;
            }
            type = detected;
        }

        StorageEntry entry;
        if (editingId == null) {
            entry = new StorageEntry(name, new BlockPos(x, y, z), dimension, type);
            entry.exactContainer = exact;
            entry.enabled = true;
            entry.priority = 0;
            manager.add(entry);
        } else {
            entry = manager.get(editingId);
            if (entry == null) {
                message("The entry being edited no longer exists.", false);
                resetForm();
                refreshList();
                return;
            }
            entry.name = name;
            entry.x = x;
            entry.y = y;
            entry.z = z;
            entry.dimension = dimension;
            entry.type = type;
            entry.exactContainer = exact;
            manager.update(entry);
        }

        String saved = "Saved storage '" + entry.name + "' at " + entry.coords() + " (" + entry.shortDimension() + ").";
        resetForm();
        refreshList();
        message(saved + " The form is ready for the next entry.", true);
    }

    private void beginEdit(StorageEntry entry) {
        editingId = entry.id;
        nameBox.set(entry.name);
        xBox.set(Integer.toString(entry.x));
        yBox.set(Integer.toString(entry.y));
        zBox.set(Integer.toString(entry.z));
        dimensionBox.set(entry.dimension);
        typeBox.set(entry.type);
        exactBox.checked = entry.exactContainer;
        createButton.set("Save Changes");
        cancelButton.set("Cancel Edit");
        message("Editing '" + entry.name + "'. ID " + entry.id.substring(0, 8) + " is stable.", true);
    }

    private void refreshList() {
        listRoot.clear();
        var entries = manager.list();
        if (entries.isEmpty()) {
            WLabel empty = listRoot.add(theme.label("No storage waypoints yet. Use Select Targeted Block or Set Here, then Create.")).expandX().widget();
            empty.color = theme.textSecondaryColor();
            return;
        }

        for (StorageEntry entry : entries) {
            WVerticalList card = listRoot.add(theme.verticalList()).expandX().widget();

            WHorizontalList top = card.add(theme.horizontalList()).expandX().widget();
            WCheckbox enabled = top.add(theme.checkbox(entry.enabled)).widget();
            enabled.tooltip = "Enable or disable this storage entry for automatic restocking.";
            enabled.action = () -> {
                entry.enabled = enabled.checked;
                manager.update(entry);
            };
            WLabel name = top.add(theme.label(entry.name)).expandCellX().widget();
            name.color = theme.textColor();
            top.add(theme.label("priority " + entry.priority)).widget().color = theme.textSecondaryColor();
            top.add(theme.label(entry.type.displayName())).widget().color = theme.textSecondaryColor();
            top.add(theme.label(entry.exactContainer ? "exact" : "general")).widget().color = theme.textSecondaryColor();

            WLabel where = card.add(theme.label(entry.coords() + "  |  " + entry.shortDimension())).widget();
            where.color = theme.textSecondaryColor();
            WLabel status = card.add(theme.label(entry.statusText())).expandX().widget();
            status.color = entry.lastStatus.startsWith("failed") || entry.lastStatus.startsWith("could not")
                    ? ERROR : theme.textSecondaryColor();

            WHorizontalList controls = card.add(theme.horizontalList()).widget();
            WButton up = controls.add(theme.button("Priority +")).widget();
            up.action = () -> {
                manager.movePriority(entry.id, 1);
                refreshList();
            };
            WButton down = controls.add(theme.button("Priority -")).widget();
            down.action = () -> {
                manager.movePriority(entry.id, -1);
                refreshList();
            };
            WButton edit = controls.add(theme.button(GuiRenderer.EDIT)).widget();
            edit.tooltip = "Edit this storage entry.";
            edit.action = () -> beginEdit(entry);
            WButton gotoButton = controls.add(theme.button("Navigate")).widget();
            gotoButton.tooltip = "Travel to this entry with Baritone.";
            gotoButton.action = () -> {
                boolean ok = StorageNavigator.get().navigate(entry);
                message(StorageNavigator.get().message(), ok);
            };
            WCheckbox exact = controls.add(theme.checkbox(entry.exactContainer)).widget();
            exact.tooltip = "Toggle exact container / general waypoint.";
            exact.action = () -> {
                if (exact.checked && !entry.type.isContainer()) {
                    exact.checked = false;
                    message("Set a container type before enabling exact mode; otherwise this is a general waypoint.", false);
                    return;
                }
                entry.exactContainer = exact.checked;
                manager.update(entry);
                refreshList();
            };
            var remove = controls.add(theme.minus()).widget();
            remove.tooltip = "Delete this storage entry.";
            remove.action = () -> {
                manager.remove(entry.id);
                if (editingId != null && editingId.equals(entry.id)) resetForm();
                refreshList();
                message("Removed storage '" + entry.name + "'.", true);
            };

            card.add(theme.horizontalSeparator()).expandX();
        }
    }

    private void resetForm() {
        editingId = null;
        BlockPos pos = StorageDetector.playerBlockPos();
        if (pos == null) pos = BlockPos.ORIGIN;
        nameBox.set(manager.nextName("Storage"));
        xBox.set(Integer.toString(pos.getX()));
        yBox.set(Integer.toString(pos.getY()));
        zBox.set(Integer.toString(pos.getZ()));
        String dimension = StorageDetector.currentDimension();
        dimensionBox.set(dimension.isEmpty() ? "minecraft:overworld" : dimension);
        typeBox.set(StorageType.UNKNOWN);
        exactBox.checked = false;
        createButton.set("Create");
        cancelButton.set("Cancel / Reset");
        messageLabel.set("Fill in a name and position, or use Set Here / Select Targeted Block.");
        messageLabel.color = theme.textSecondaryColor();
    }

    private void message(String text, boolean success) {
        if (messageLabel == null) return;
        messageLabel.set(text == null ? "" : text);
        messageLabel.color = success ? OK : ERROR;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normaliseDimension(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static String shortDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) return "any";
        return dimension.startsWith("minecraft:")
                ? dimension.substring("minecraft:".length()) : dimension;
    }
}
