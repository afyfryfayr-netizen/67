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
import sleepy.autobuilder.autobuild.PlacementMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Visual per-block placement-method editor. Rules are stored in the same
 * {@code block-method-rules} format the engine already reads.
 */
public final class PlacementRulesScreen extends WindowScreen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final Screen parent;
    private final Consumer<List<String>> onSave;
    private final List<String> rules;
    private WTable rulesTable;
    private WDropdown<PlacementMethod> methodBox;
    private WLabel messageLabel;

    public PlacementRulesScreen(GuiTheme theme, List<String> rules, Screen parent,
                                Consumer<List<String>> onSave) {
        super(theme, "Per-Block Placement Methods");
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
        this.parent = parent;
        this.onSave = onSave;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Choose the low-level placement method used for specific blocks. "
                + "AUTO uses the adapted airplace PlacementManager; VANILLA uses Meteor's normal interaction.")).expandX();

        WHorizontalList add = add(theme.horizontalList()).expandX().widget();
        add.add(theme.label("Method:"));
        methodBox = add.add(theme.dropdown(PlacementMethod.values(), PlacementMethod.AUTO)).widget();
        WButton choose = add.add(theme.button("Choose Blocks and Add")).widget();
        choose.action = () -> BlockPickerScreen.open(theme, "Choose Blocks for Placement Method",
                existingBlocks(), this, this::addBlocks);
        WButton wildcard = add.add(theme.button("Set Fallback (*)")).widget();
        wildcard.action = () -> {
            replaceSelector("*");
            message("Fallback method set to " + methodBox.get().name() + ".");
        };

        add(theme.horizontalSeparator()).expandX();
        rulesTable = add(theme.table()).expandX().widget();

        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        WButton save = bottom.add(theme.button("Save Rules")).widget();
        save.action = () -> {
            onSave.accept(new ArrayList<>(rules));
            message("Saved " + rules.size() + " placement rule(s).");
        };
        WButton back = bottom.add(theme.button("Back")).widget();
        back.action = () -> mc.setScreen(parent);

        messageLabel = add(theme.label("")).expandX().widget();
        messageLabel.color = theme.textSecondaryColor();
        rebuild();
    }

    private List<Block> existingBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (String rule : rules) {
            int split = rule.indexOf('=');
            if (split <= 0) continue;
            String selector = rule.substring(0, split).trim();
            if (selector.startsWith("*") || selector.startsWith("#")
                    || selector.toLowerCase(Locale.ROOT).startsWith("category:")) continue;
            Block block = Registries.BLOCK.getOrEmpty(net.minecraft.util.Identifier.tryParse(selector)).orElse(null);
            if (block != null && !blocks.contains(block)) blocks.add(block);
        }
        return blocks;
    }

    private void addBlocks(List<Block> blocks) {
        PlacementMethod method = methodBox.get();
        for (Block block : blocks) {
            String id = Registries.BLOCK.getId(block).toString();
            replaceSelector(id);
            rules.add(id + "=" + method.name());
        }
        normalize();
        onSave.accept(new ArrayList<>(rules));
        rebuild();
        message("Added " + blocks.size() + " block rule(s) using " + method.name() + ".");
    }

    private void replaceSelector(String selector) {
        rules.removeIf(rule -> {
            int split = rule.indexOf('=');
            return split > 0 && rule.substring(0, split).trim().equalsIgnoreCase(selector);
        });
    }

    private void rebuild() {
        if (rulesTable == null) return;
        rulesTable.clear();
        if (rules.isEmpty()) {
            WLabel empty = rulesTable.add(theme.label("No placement methods configured yet.")).widget();
            empty.color = theme.textSecondaryColor();
            return;
        }

        rulesTable.add(theme.label("Block / selector")).widget();
        rulesTable.add(theme.label("Method")).widget();
        rulesTable.add(theme.label("")).widget();
        rulesTable.row();
        for (String rule : rules) {
            int split = rule.indexOf('=');
            String selector = split > 0 ? rule.substring(0, split) : rule;
            String method = split > 0 ? rule.substring(split + 1) : "";
            rulesTable.add(theme.label(selector)).expandCellX().widget();
            WLabel value = rulesTable.add(theme.label(method)).widget();
            value.color = theme.textSecondaryColor();
            var remove = rulesTable.add(theme.minus()).widget();
            remove.action = () -> {
                rules.remove(rule);
                onSave.accept(new ArrayList<>(rules));
                rebuild();
            };
            rulesTable.row();
        }
    }

    private void normalize() {
        rules.replaceAll(rule -> rule.replace(" ", ""));
    }

    private void message(String text) {
        if (messageLabel != null) messageLabel.set(text);
    }
}
