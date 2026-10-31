package sleepy.autobuilder.autobuild.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WItem;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import sleepy.autobuilder.autobuild.core.BlockCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Registry-driven visual block selector used by every block-list rule in the addon.
 *
 * <p>Blocks are shown as actual item icons in a paged grid. Search and category filters operate on
 * the real block registry, and confirming writes the selected {@link Block} instances back to the
 * caller's setting.</p>
 */
public final class BlockPickerScreen extends WindowScreen {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int COLUMNS = 9;
    private static final int PER_PAGE = COLUMNS * 5;
    private static final Color SELECTED = new Color(90, 200, 130, 170);
    private static final Color HOVER = new Color(255, 255, 255, 45);
    private static List<Block> allBlocks;

    private final String title;
    private final List<Block> initial;
    private final int maxSelection;
    private final Consumer<List<Block>> onConfirm;
    private final Screen parent;
    private final Set<Block> selected = new LinkedHashSet<>();

    private WTextBox filterBox;
    private WDropdown<String> categoryBox;
    private WLabel pageLabel;
    private WLabel selectedCount;
    private WVerticalList gridRoot;
    private WVerticalList selectedRoot;
    private String filterText = "";
    private String category = "All";
    private int page;

    public BlockPickerScreen(GuiTheme theme, String title, List<Block> initial, int maxSelection,
                             Consumer<List<Block>> onConfirm, Screen parent) {
        super(theme, title);
        this.title = title;
        this.initial = initial == null ? List.of() : List.copyOf(initial);
        this.maxSelection = Math.max(0, maxSelection);
        this.onConfirm = onConfirm;
        this.parent = parent;
        this.selected.addAll(this.initial);
    }

    public static void open(GuiTheme theme, String title, List<Block> initial, Screen parent,
                            Consumer<List<Block>> onConfirm) {
        mc.setScreen(new BlockPickerScreen(theme, title, initial, 0, onConfirm, parent));
    }

    public static void pickOne(GuiTheme theme, String title, Block initial, Screen parent,
                               Consumer<Block> onConfirm) {
        mc.setScreen(new BlockPickerScreen(theme, title,
                initial == null ? List.of() : List.of(initial), 1,
                list -> {
                    if (!list.isEmpty()) onConfirm.accept(list.get(0));
                }, parent));
    }

    @Override
    public void initWidgets() {
        add(theme.label(title + ": browse the real block registry, search by name, then confirm.")).expandX();

        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        filterBox = top.add(theme.textBox("", "Search blocks...")).expandCellX().minWidth(220).widget();
        filterBox.action = this::applyFilter;
        WButton search = top.add(theme.button("Search")).widget();
        search.action = this::applyFilter;

        WHorizontalList filters = add(theme.horizontalList()).expandX().widget();
        filters.add(theme.label("Category:"));
        List<String> categories = new ArrayList<>();
        categories.add("All");
        for (BlockCategory category : BlockCategory.values()) categories.add(category.name());
        categoryBox = filters.add(theme.dropdown(categories.toArray(String[]::new), "All")).widget();
        categoryBox.action = () -> {
            category = categoryBox.get();
            page = 0;
            refreshGrid();
        };
        filters.add(theme.label("Page:"));
        WButton previous = filters.add(theme.button("< Prev")).widget();
        previous.action = () -> {
            if (page > 0) {
                page--;
                refreshGrid();
            }
        };
        pageLabel = filters.add(theme.label("1 / 1")).widget();
        WButton next = filters.add(theme.button("Next >")).widget();
        next.action = () -> {
            int pages = Math.max(1, (filtered().size() + PER_PAGE - 1) / PER_PAGE);
            if (page + 1 < pages) {
                page++;
                refreshGrid();
            }
        };

        gridRoot = add(theme.verticalList()).expandX().widget();
        add(theme.horizontalSeparator()).expandX();

        selectedCount = add(theme.label("Selected: 0")).expandX().widget();
        selectedRoot = add(theme.verticalList()).expandX().widget();
        add(theme.horizontalSeparator()).expandX();

        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        WButton confirm = bottom.add(theme.button("Confirm Selection")).widget();
        confirm.action = this::confirm;
        WButton clear = bottom.add(theme.button("Clear Selection")).widget();
        clear.action = () -> {
            selected.clear();
            refreshGrid();
            refreshSelected();
        };
        WButton cancel = bottom.add(theme.button("Cancel")).widget();
        cancel.action = () -> mc.setScreen(parent);
        WLabel hint = bottom.add(theme.label("Click a block icon to add or remove it.")).expandCellX().widget();
        hint.color = theme.textSecondaryColor();

        refreshGrid();
        refreshSelected();
    }

    private void applyFilter() {
        filterText = filterBox.get().trim();
        page = 0;
        refreshGrid();
    }

    private List<Block> filtered() {
        String search = filterText.toLowerCase(Locale.ROOT);
        List<Block> result = new ArrayList<>();
        for (Block block : all()) {
            String id = Registries.BLOCK.getId(block).toString();
            String name = block.getName().getString();
            if (!"All".equals(category)) {
                BlockCategory blockCategory = BlockCategory.fromId(id);
                if (!blockCategory.name().equals(category)) continue;
            }
            if (!search.isEmpty()
                    && !name.toLowerCase(Locale.ROOT).contains(search)
                    && !id.toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            result.add(block);
        }
        return result;
    }

    private void refreshGrid() {
        if (gridRoot == null) return;
        gridRoot.clear();
        List<Block> blocks = filtered();
        int pages = Math.max(1, (blocks.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        WTable grid = gridRoot.add(theme.table()).widget();
        int start = page * PER_PAGE;
        int end = Math.min(blocks.size(), start + PER_PAGE);
        int column = 0;
        for (int i = start; i < end; i++) {
            Block block = blocks.get(i);
            grid.add(new WBlockButton(block)).pad(1);
            if (++column >= COLUMNS) {
                grid.row();
                column = 0;
            }
        }
        if (pageLabel != null) pageLabel.set((page + 1) + " / " + pages + "  (" + blocks.size() + " blocks)");
    }

    private void refreshSelected() {
        if (selectedRoot == null) return;
        selectedRoot.clear();
        selectedCount.set("Selected: " + selected.size()
                + (maxSelection == 1 ? " (single block)" : " (multiple allowed)"));
        if (selected.isEmpty()) {
            WLabel empty = selectedRoot.add(theme.label("No blocks selected yet.")).widget();
            empty.color = theme.textSecondaryColor();
            return;
        }

        WTable table = selectedRoot.add(theme.table()).expandX().widget();
        for (Block block : selected) {
            table.add(new WItem(icon(block)));
            WLabel name = table.add(theme.label(block.getName().getString())).expandCellX().widget();
            name.color = theme.textColor();
            WLabel id = table.add(theme.label(Registries.BLOCK.getId(block).toString())).widget();
            id.color = theme.textSecondaryColor();
            var remove = table.add(theme.minus()).widget();
            remove.action = () -> {
                selected.remove(block);
                refreshGrid();
                refreshSelected();
            };
            table.row();
        }
    }

    private void toggle(Block block) {
        if (selected.contains(block)) {
            selected.remove(block);
        } else {
            if (maxSelection == 1) selected.clear();
            if (maxSelection > 0 && selected.size() >= maxSelection) {
                selected.clear();
            }
            selected.add(block);
        }
        refreshGrid();
        refreshSelected();
    }

    private void confirm() {
        mc.setScreen(parent);
        onConfirm.accept(new ArrayList<>(selected));
    }

    private static ItemStack icon(Block block) {
        if (block == null) return new ItemStack(Items.BARRIER);
        ItemStack stack = block.asItem().getDefaultStack();
        return stack.isEmpty() ? new ItemStack(Items.BARRIER) : stack;
    }

    private static List<Block> all() {
        if (allBlocks == null) {
            List<Block> blocks = new ArrayList<>();
            for (Block block : Registries.BLOCK) {
                if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
                blocks.add(block);
            }
            blocks.sort(Comparator.comparing(block -> block.getName().getString(),
                    String.CASE_INSENSITIVE_ORDER));
            allBlocks = List.copyOf(blocks);
        }
        return allBlocks;
    }

    private final class WBlockButton extends WPressable {
        private final Block block;
        private final ItemStack icon;

        private WBlockButton(Block block) {
            this.block = block;
            this.icon = icon(block);
            tooltip = block.getName().getString() + "\n" + Registries.BLOCK.getId(block);
        }

        @Override
        protected void onCalculateSize() {
            double size = theme.scale(36);
            width = size;
            height = size;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            if (selected.contains(block)) {
                renderer.quad(x + 1, y + 1, width - 2, height - 2, SELECTED);
            } else if (mouseOver) {
                renderer.quad(x + 1, y + 1, width - 2, height - 2, HOVER);
            }
            renderer.post(() -> renderer.item(icon, (int) (x + 2), (int) (y + 2), (float) theme.scale(2), true));
        }

        @Override
        protected void onPressed(int button) {
            toggle(block);
        }
    }
}
