package sleepy.autobuilder.autobuild.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import sleepy.autobuilder.autobuild.ProfileManager;

import java.util.List;

/**
 * Visual profile manager: create, load, duplicate, rename, delete, export, import and reset
 * Auto Builder profiles. All actions use the same {@link ProfileManager} backend as the command.
 */
public final class ProfilesScreen extends WindowScreen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Color OK = new Color(110, 220, 130);
    private static final Color ERROR = new Color(255, 90, 90);

    private final ProfileManager manager = ProfileManager.get();
    private final Screen parent;
    private WTextBox nameBox;
    private WTextBox pathBox;
    private WVerticalList listRoot;
    private WLabel messageLabel;

    public ProfilesScreen(GuiTheme theme, Screen parent) {
        super(theme, "Profiles - 7 Loves Ella Langley Auto Builder");
        this.parent = parent;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Profiles save only this module's settings. They live in "
                + manager.directory() + " and never change other Meteor modules.")).expandX();

        WTable form = add(theme.table()).expandX().widget();
        form.add(theme.label("Profile name:"));
        nameBox = form.add(theme.textBox("", "New or existing profile name...")).expandCellX().minWidth(260).widget();
        form.row();
        form.add(theme.label("Export / import path:"));
        pathBox = form.add(theme.textBox("", "Absolute path or a path inside the Meteor folder..."))
                .expandCellX().minWidth(260).widget();
        form.row();

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton save = actions.add(theme.button("Save Current As")).widget();
        save.tooltip = "Write the current settings to the profile name above.";
        save.action = () -> report(manager.save(nameBox.get()));

        WButton load = actions.add(theme.button("Load Named Profile")).widget();
        load.tooltip = "Load the profile name above (or a profile selected from the list).";
        load.action = () -> report(manager.load(nameBox.get()));

        WButton importButton = actions.add(theme.button("Import From Path")).widget();
        importButton.tooltip = "Import an .nbt profile from the path above, saving it under the profile name.";
        importButton.action = () -> report(manager.importProfile(pathBox.get(), nameBox.get()));

        WButton reset = actions.add(theme.button("Reset All Settings")).widget();
        reset.tooltip = "Reset every Auto Builder setting to its default values.";
        reset.action = () -> {
            report(manager.reset());
            refreshList();
        };

        messageLabel = add(theme.label("")).expandX().widget();
        messageLabel.color = theme.textSecondaryColor();

        add(theme.horizontalSeparator()).expandX();
        listRoot = add(theme.verticalList()).expandX().widget();

        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        WButton back = bottom.add(theme.button("Back")).widget();
        back.action = () -> mc.setScreen(parent);

        refreshList();
    }

    private void refreshList() {
        listRoot.clear();
        List<String> profiles = manager.list();
        if (profiles.isEmpty()) {
            WLabel empty = listRoot.add(theme.label("No profiles yet. Type a name above and press Save Current As."))
                    .expandX().widget();
            empty.color = theme.textSecondaryColor();
            return;
        }

        for (String profile : profiles) {
            WHorizontalList row = listRoot.add(theme.horizontalList()).expandX().widget();
            WLabel name = row.add(theme.label(profile)).expandCellX().widget();
            name.color = theme.textColor();

            WButton load = row.add(theme.button("Load")).widget();
            load.action = () -> {
                nameBox.set(profile);
                report(manager.load(profile));
            };
            WButton duplicate = row.add(theme.button("Duplicate To Name")).widget();
            duplicate.tooltip = "Copy this profile to the name typed in the profile name box.";
            duplicate.action = () -> {
                report(manager.duplicate(profile, nameBox.get()));
                refreshList();
            };
            WButton rename = row.add(theme.button("Rename To Name")).widget();
            rename.tooltip = "Rename this profile to the name typed in the profile name box.";
            rename.action = () -> {
                report(manager.rename(profile, nameBox.get()));
                refreshList();
            };
            WButton export = row.add(theme.button("Export Path")).widget();
            export.tooltip = "Export this profile to the path typed above.";
            export.action = () -> report(manager.exportProfile(profile, pathBox.get()));
            var remove = row.add(theme.minus()).widget();
            remove.tooltip = "Delete this profile file.";
            remove.action = () -> {
                report(manager.delete(profile));
                refreshList();
            };
        }
    }

    private void report(ProfileManager.Result result) {
        messageLabel.set(result.message());
        messageLabel.color = result.ok() ? OK : ERROR;
    }
}
