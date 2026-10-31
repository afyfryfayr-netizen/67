package sleepy.autobuilder;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;
import sleepy.autobuilder.autobuild.AutoBuilder;
import sleepy.autobuilder.autobuild.ProfileManager;
import sleepy.autobuilder.autobuild.storage.StorageNavigator;
import sleepy.autobuilder.commands.AutoBuilderCommand;
import sleepy.autobuilder.hud.AutoBuilderHud;

/**
 * Standalone entry point for the 7 Loves Ella Langley Auto Builder.
 *
 * <p>Only the Auto Builder module, its command and its HUD element are registered. The upstream
 * SleepyAddon modules (Printer, Airplace, SilentMine, Moss tools and the rest) are not bundled and
 * are not required at runtime.</p>
 */
public final class SevenBuilderAddon extends MeteorAddon {
    public static final String DISPLAY_NAME = "7 Loves Ella Langley Auto Builder";
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category(DISPLAY_NAME);
    public static final HudGroup HUD_GROUP = new HudGroup(DISPLAY_NAME);

    @Override
    public void onInitialize() {
        LOG.info("Initializing {}", DISPLAY_NAME);

        AutoBuilder autoBuilder = new AutoBuilder();
        Modules.get().add(autoBuilder);
        ProfileManager.get().init(autoBuilder);

        Commands.add(new AutoBuilderCommand());
        Hud.get().register(AutoBuilderHud.INFO);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        StorageNavigator.get().tick();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "sleepy.autobuilder";
    }

    @Override
    public GithubRepo getRepo() {
        // Kept as an attribution link to the placement foundation this standalone build adapts.
        return new GithubRepo("Sleepy", "SleepyAddon");
    }
}
