package sleepy.autobuilder.autobuild.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import sleepy.autobuilder.autobuild.core.PropertyMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-block state-property editor. It shows the real properties of the selected block and lets the
 * user choose DEFAULT (follow the block/global rule), IGNORE, REPORT or CORRECT for each property.
 */
public final class PropertyRulesScreen extends WindowScreen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final List<String> originalRules;
    private final Consumer<List<String>> onSave;
    private final Screen parent;
    private WLabel selectedLabel;
    private WLabel messageLabel;
    private WTable propertiesTable;
    private final Map<Property<?>, WDropdown<Choice>> dropdowns = new LinkedHashMap<>();
    private Block block;

    private enum Choice {
        DEFAULT,
        IGNORE,
        REPORT,
        CORRECT
    }

    public PropertyRulesScreen(GuiTheme theme, List<String> rules, Screen parent,
                               Consumer<List<String>> onSave) {
        super(theme, "State Property Rules");
        this.originalRules = rules == null ? List.of() : new ArrayList<>(rules);
        this.onSave = onSave;
        this.parent = parent;
    }

    @Override
    public void initWidgets() {
        selectedLabel = theme.label("No block selected.");
        messageLabel = theme.label("");
        propertiesTable = theme.table();

        add(theme.label("State changes are ignored by default. Mark only the properties you want "
                + "ignored, reported or corrected for this block.")).expandX();

        WHorizontalList chooser = add(theme.horizontalList()).expandX().widget();
        chooser.add(selectedLabel).expandCellX();
        WButton choose = chooser.add(theme.button("Choose Block...")).widget();
        choose.action = () -> BlockPickerScreen.pickOne(theme, "Choose Block", block, this,
                picked -> {
                    block = picked;
                    selectedLabel.set(picked.getName().getString() + "  ("
                            + Registries.BLOCK.getId(picked) + ")");
                    rebuildProperties();
                });

        add(theme.horizontalSeparator()).expandX();
        add(propertiesTable).expandX();

        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        WButton save = bottom.add(theme.button("Save Rules")).widget();
        save.action = this::save;
        WButton back = bottom.add(theme.button("Back")).widget();
        back.action = () -> mc.setScreen(parent);

        messageLabel.color = theme.textSecondaryColor();
        add(messageLabel).expandX();

        if (block != null) {
            selectedLabel.set(block.getName().getString() + "  (" + Registries.BLOCK.getId(block) + ")");
            rebuildProperties();
        }
    }

    private void rebuildProperties() {
        propertiesTable.clear();
        dropdowns.clear();
        if (block == null) {
            WLabel empty = propertiesTable.add(theme.label("Choose a block to view its real state properties.")).widget();
            empty.color = theme.textSecondaryColor();
            return;
        }

        String id = Registries.BLOCK.getId(block).toString();
        Map<String, PropertyMode> existing = readModes(id);
        List<Property<?>> properties = new ArrayList<>(block.getStateManager().getProperties());
        properties.sort(Comparator.comparing(Property::getName));

        if (properties.isEmpty()) {
            WLabel none = propertiesTable.add(theme.label("This block has no state properties.")).widget();
            none.color = theme.textSecondaryColor();
            return;
        }

        propertiesTable.add(theme.label("Property")).widget();
        propertiesTable.add(theme.label("Default")).widget();
        propertiesTable.add(theme.label("Mode")).widget();
        propertiesTable.row();

        for (Property<?> property : properties) {
            propertiesTable.add(theme.label(property.getName())).widget();
            String defaultValue = String.valueOf(block.getDefaultState().get(property));
            WLabel value = propertiesTable.add(theme.label(defaultValue)).widget();
            value.color = theme.textSecondaryColor();

            PropertyMode mode = existing.get(property.getName());
            Choice initial = mode == null ? Choice.DEFAULT : Choice.valueOf(mode.name());
            WDropdown<Choice> dropdown = propertiesTable.add(theme.dropdown(Choice.values(), initial)).widget();
            dropdown.tooltip = "DEFAULT follows the block/global state action. CORRECT forces correction, "
                    + "REPORT writes a chat report without changing the block, IGNORE never compares this property.";
            dropdowns.put(property, dropdown);
            propertiesTable.row();
        }
    }

    private Map<String, PropertyMode> readModes(String blockId) {
        Map<String, PropertyMode> modes = new LinkedHashMap<>();
        for (String raw : originalRules) {
            if (raw == null || raw.isBlank()) continue;
            int split = raw.indexOf('|');
            if (split <= 0 || !raw.substring(0, split).trim().equalsIgnoreCase(blockId)) continue;
            for (String part : raw.substring(split + 1).split(",")) {
                int equals = part.indexOf('=');
                if (equals <= 0) continue;
                String name = part.substring(0, equals).trim();
                String modeText = part.substring(equals + 1).trim().toUpperCase(Locale.ROOT);
                try {
                    PropertyMode mode = modeText.equals("REQUIRED")
                            ? PropertyMode.CORRECT : PropertyMode.valueOf(modeText);
                    modes.put(name, mode);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return modes;
    }

    private void save() {
        if (block == null) {
            message("Choose a block first.", true);
            return;
        }
        String id = Registries.BLOCK.getId(block).toString();
        List<String> result = new ArrayList<>();
        for (String raw : originalRules) {
            if (raw == null || raw.isBlank()) continue;
            int split = raw.indexOf('|');
            if (split > 0 && raw.substring(0, split).trim().equalsIgnoreCase(id)) continue;
            result.add(raw);
        }

        List<String> entries = new ArrayList<>();
        for (Map.Entry<Property<?>, WDropdown<Choice>> entry : dropdowns.entrySet()) {
            Choice choice = entry.getValue().get();
            if (choice == Choice.DEFAULT) continue;
            entries.add(entry.getKey().getName() + "=" + choice.name());
        }
        if (!entries.isEmpty()) result.add(id + "|" + String.join(",", entries));
        onSave.accept(result);
        message("Saved " + entries.size() + " property rule(s) for " + block.getName().getString() + ".",
                false);
    }

    private void message(String text, boolean error) {
        messageLabel.set(text);
        messageLabel.color = error ? new meteordevelopment.meteorclient.utils.render.color.Color(255, 90, 90)
                : theme.textSecondaryColor();
    }
}
