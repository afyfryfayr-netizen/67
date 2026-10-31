package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.storage.StorageType;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens real containers and operates their slots. The service only ever closes a screen it opened
 * itself; user screens are never touched.
 */
public final class ContainerService {
    public enum OpenResult { OPENING, OPENED, FAILED, IDLE }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double OPEN_RANGE = 4.4;

    private BlockPos containerPos;
    private HandledScreen<?> screen;
    private boolean opening;
    private boolean openedByUs;
    private int ticks;
    private int timeoutTicks = 80;
    private String error = "";
    private int previousHotbar = -1;
    private boolean swapped;

    public boolean beginOpen(BlockPos pos, int timeoutTicks) {
        if (pos == null || mc.player == null || mc.world == null) return false;
        if (mc.currentScreen != null) {
            error = "another screen is already open";
            return false;
        }
        if (!isSupportedContainer(pos)) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.getBlock() instanceof EnderChestBlock) {
                error = "ender chests are not used as storage containers";
            } else if (state.isAir()) {
                error = "container block is missing at " + pos.toShortString();
            } else {
                error = "block at " + pos.toShortString() + " is not a supported container";
            }
            return false;
        }
        if (!McInteract.withinReach(pos, OPEN_RANGE)) {
            error = "container is out of reach";
            return false;
        }
        this.containerPos = pos.toImmutable();
        this.timeoutTicks = Math.max(20, timeoutTicks);
        this.ticks = 0;
        this.error = "";
        this.opening = true;
        this.openedByUs = false;
        this.screen = null;
        selectNonBlockHand();
        McInteract.lookAndUse(containerPos, () -> {
        });
        return true;
    }

    /** Starts opening a container and lets the caller tick it. */
    public boolean beginOpenNearest(BlockPos anchor, double radius, int timeoutTicks) {
        BlockPos found = findNearest(anchor, radius);
        if (found == null) {
            error = "no container found within " + String.format("%.1f", radius) + " blocks";
            return false;
        }
        return beginOpen(found, timeoutTicks);
    }

    public OpenResult tickOpen() {
        if (!opening) return screen != null ? OpenResult.OPENED : OpenResult.IDLE;
        ticks++;
        if (mc.currentScreen instanceof HandledScreen<?> handled) {
            ScreenHandler handler = handled.getScreenHandler();
            if (handler.slots.size() <= 36) {
                error = "opened screen has no container slots";
                close();
                opening = false;
                return OpenResult.FAILED;
            }
            screen = handled;
            opening = false;
            openedByUs = true;
            return OpenResult.OPENED;
        }
        if (mc.currentScreen != null) {
            error = "another screen opened instead of the container";
            opening = false;
            restoreHand();
            return OpenResult.FAILED;
        }
        if (ticks >= timeoutTicks) {
            error = "container did not open";
            opening = false;
            restoreHand();
            return OpenResult.FAILED;
        }
        return OpenResult.OPENING;
    }

    public boolean isOpening() {
        return opening;
    }

    public boolean isOpen() {
        if (!openedByUs || screen == null) return false;
        if (mc.currentScreen != screen) {
            openedByUs = false;
            screen = null;
            restoreHand();
            return false;
        }
        return true;
    }

    public ScreenHandler handler() {
        return screen == null ? null : screen.getScreenHandler();
    }

    public int containerSlots() {
        ScreenHandler handler = handler();
        return handler == null ? 0 : Math.max(0, handler.slots.size() - 36);
    }

    public List<ItemStack> contents() {
        ScreenHandler handler = handler();
        if (handler == null) return List.of();
        List<ItemStack> stacks = new ArrayList<>();
        int slots = containerSlots();
        for (int i = 0; i < slots; i++) stacks.add(handler.getSlot(i).getStack().copy());
        return stacks;
    }

    public boolean quickMove(int slotIndex) {
        ScreenHandler handler = handler();
        if (handler == null || mc.player == null || mc.interactionManager == null) return false;
        if (slotIndex < 0 || slotIndex >= handler.slots.size()) return false;
        mc.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, mc.player);
        return true;
    }

    public boolean quickMoveAllContents(int slotsPerTick, int reserveSlots) {
        ScreenHandler handler = handler();
        if (handler == null) return false;
        if (InventoryService.freeSlots() <= reserveSlots) return false;
        int moved = 0;
        int containerSlots = containerSlots();
        for (int i = 0; i < containerSlots && moved < slotsPerTick; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) {
                if (quickMove(i)) moved++;
            }
        }
        return moved > 0;
    }

    public boolean contentsEmpty() {
        ScreenHandler handler = handler();
        if (handler == null) return false;
        int containerSlots = containerSlots();
        for (int i = 0; i < containerSlots; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    public void close() {
        if (openedByUs && mc.player != null) {
            mc.player.closeHandledScreen();
        }
        openedByUs = false;
        screen = null;
        opening = false;
        restoreHand();
    }

    public BlockPos containerPos() {
        return containerPos;
    }

    public String error() {
        return error;
    }

    public int freeSlots() {
        return InventoryService.freeSlots();
    }

    /** Nearest supported container within reach of the player, used by the legacy nearest-open path. */
    public BlockPos findNearest(BlockPos anchor, double radius) {
        return findNearest(anchor, radius, true);
    }

    /**
     * Nearest supported container within {@code radius} of the waypoint, regardless of the player's
     * current reach. The restock service uses this to find the real container around a general
     * waypoint, walks to it, and only then opens it.
     */
    public BlockPos findNearestContainer(BlockPos anchor, double radius) {
        return findNearest(anchor, radius, false);
    }

    public boolean canReach(BlockPos pos) {
        return McInteract.withinReach(pos, OPEN_RANGE);
    }

    /** True when the chunk containing this position is loaded on the client. */
    public static boolean isChunkLoaded(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        return mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** True for a real container block that this service is willing to open. */
    public boolean isSupportedContainer(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getBlock() instanceof EnderChestBlock) return false;
        if (StorageType.fromBlock(state.getBlock()).isContainer()) return true;
        return mc.world.getBlockEntity(pos) instanceof Inventory;
    }

    private BlockPos findNearest(BlockPos anchor, double radius, boolean requireReach) {
        if (mc.world == null || anchor == null) return null;
        int r = (int) Math.ceil(Math.max(1.0, Math.min(32.0, radius)));
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    BlockPos pos = anchor.add(dx, dy, dz);
                    double distance = pos.getSquaredDistance(anchor);
                    if (distance > radius * radius || distance >= bestDistance) continue;
                    if (!isSupportedContainer(pos)) continue;
                    if (requireReach && !McInteract.withinReach(pos, OPEN_RANGE)) continue;
                    best = pos;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void selectNonBlockHand() {
        if (mc.player == null) return;
        previousHotbar = mc.player.getInventory().selectedSlot;
        swapped = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                if (i != previousHotbar && InvUtils.swap(i, true)) swapped = true;
                return;
            }
        }
        // Everything in the hotbar is a block. Try moving a non-block item from the main inventory.
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                int target = previousHotbar;
                InvUtils.move().from(i).toHotbar(target);
                return;
            }
        }
    }

    private void restoreHand() {
        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }
        previousHotbar = -1;
    }
}
