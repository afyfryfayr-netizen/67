package sleepy.autobuilder.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import sleepy.autobuilder.SevenBuilderAddon;
import sleepy.autobuilder.autobuild.AutoBuildEngine;
import sleepy.autobuilder.autobuild.AutoBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact status HUD for the automatic builder. It only occupies space while the module is active
 * or when a previous job still has status to show.
 */
public class AutoBuilderHud extends HudElement {
    public static final HudElementInfo<AutoBuilderHud> INFO = new HudElementInfo<>(
            SevenBuilderAddon.HUD_GROUP, "auto-builder", SevenBuilderAddon.DISPLAY_NAME,
            "Shows automatic builder progress, layer and material status.",
            AutoBuilderHud::new);

    private static final Color TITLE = new Color(120, 200, 255);
    private static final Color WARN = new Color(255, 190, 80);
    private static final Color ERROR = new Color(255, 100, 100);

    public AutoBuilderHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        AutoBuildEngine engine = AutoBuildEngine.get();
        AutoBuilder module = Modules.get().get(AutoBuilder.class);
        if (module == null || (!module.isActive() && !engine.isRunning()
                && engine.state() == AutoBuildEngine.State.IDLE)) {
            setSize(0, 0);
            return;
        }

        List<Line> lines = new ArrayList<>(4);
        Color stateColor = switch (engine.state()) {
            case FAILED, SUSPENDED -> ERROR;
            case COMPLETE -> Color.GREEN;
            case STOPPED, IDLE -> Color.LIGHT_GRAY;
            default -> engine.isPaused() ? WARN : TITLE;
        };

        String title = SevenBuilderAddon.DISPLAY_NAME + ": " + engine.statusShort();
        if (engine.isPaused() && !engine.pauseReason().isBlank()) {
            title += " (" + engine.pauseReason() + ")";
        }
        lines.add(new Line(title, stateColor));

        if (engine.totalPositions() > 0) {
            lines.add(new Line(engine.progressPercent() + "%  " + engine.currentLayerText()
                    + "  done " + engine.completedPositions()
                    + "  pending " + engine.pendingPositions()
                    + "  failed " + engine.failedPositions(), Color.WHITE));
        }
        if (engine.state() == AutoBuildEngine.State.BUILDING) {
            lines.add(new Line(engine.printStatsText(), Color.LIGHT_GRAY));
        }
        if (module.planMaterials.get()) {
            lines.add(new Line(engine.materialSummary(), Color.LIGHT_GRAY));
        }
        if (!engine.lastError().isBlank() && !engine.isRunning()) {
            lines.add(new Line(engine.lastError(), ERROR));
        }

        double lineHeight = renderer.textHeight(true) + 2;
        double width = 0;
        for (Line line : lines) {
            width = Math.max(width, renderer.textWidth(line.text, true));
        }
        setSize(width, lineHeight * lines.size());

        double y = this.y;
        for (Line line : lines) {
            renderer.text(line.text, x, y, line.color, true);
            y += lineHeight;
        }
    }

    private record Line(String text, Color color) {
    }
}
