package sleepy.autobuilder.autobuild.mc;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete shulker-box cycle: place above the player (with fallbacks), open, move every content
 * stack into the player inventory, close, break, and verify pickup. A shulker is never broken while
 * it still contains items, because that would spill and potentially destroy the contents.
 */
public final class ShulkerService {
    public enum Phase {
        IDLE, PLACE, WAIT_PLACED, OPEN, WAIT_SCREEN, EMPTY, CLOSE, WAIT_CLOSED, BREAK, WAIT_BREAK, PICKUP, DONE, FAILED
    }

    public record Policy(
            boolean aboveHead,
            boolean fallbackPositions,
            int openTimeoutTicks,
            int maxRetries,
            int pickupTimeoutTicks,
            int reserveSlots,
            int breakTimeoutTicks
    ) {
        public Policy {
            openTimeoutTicks = Math.max(20, openTimeoutTicks);
            maxRetries = Math.max(1, maxRetries);
            pickupTimeoutTicks = Math.max(20, pickupTimeoutTicks);
            reserveSlots = Math.max(0, reserveSlots);
            breakTimeoutTicks = Math.max(20, breakTimeoutTicks);
        }
    }

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final ContainerService container = new ContainerService();
    private final BreakService breaker = BreakService.get();

    private boolean active;
    private boolean finished;
    private boolean success;
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int placementAttempts;
    private int unconfirmedWaits;
    private final List<BlockPos> attemptedPlacements = new ArrayList<>();
    private BlockPos placedPos;
    private String shulkerItemId;
    private int shulkerSlot = -1;
    private Map<String, Integer> chosenContents = Map.of();
    private int countBefore;
    private int countAfterEmpty;
    private String error = "";
    private String wantedItem;

    public boolean request(String wantedItemId, Policy policy) {
        if (active) return false;
        if (!policy.aboveHead && !policy.fallbackPositions) {
            error = "no shulker placement position is enabled";
            return false;
        }
        InventoryService.ShulkerStack chosen = chooseShulker(wantedItemId);
        if (chosen == null) {
            error = wantedItemId == null ? "no carried shulker box found" : "no carried shulker contains " + wantedItemId;
            return false;
        }
        Map<String, Integer> contents = chosen.contents();
        if (contents.isEmpty()) {
            error = "the selected shulker is empty";
            return false;
        }
        int distinct = 0;
        for (int value : contents.values()) if (value > 0) distinct++;
        int freeAfterPlacement = InventoryService.freeSlots() + 1 - policy.reserveSlots();
        if (distinct > freeAfterPlacement) {
            error = "not enough inventory space: shulker needs " + distinct + " slots, " + freeAfterPlacement + " available";
            return false;
        }

        this.wantedItem = wantedItemId;
        this.active = true;
        this.finished = false;
        this.success = false;
        this.error = "";
        this.phase = Phase.PLACE;
        this.phaseTicks = 0;
        this.placementAttempts = 0;
        this.unconfirmedWaits = 0;
        this.attemptedPlacements.clear();
        this.placedPos = null;
        this.shulkerItemId = chosen.itemId();
        this.shulkerSlot = chosen.slot();
        this.chosenContents = contents;
        this.countBefore = InventoryService.countItem(shulkerItemId);
        this.countAfterEmpty = this.countBefore;
        return true;
    }

    public void tick(Policy policy) {
        if (!active) return;
        phaseTicks++;
        if (mc.player == null || mc.world == null) {
            fail("left the world");
            return;
        }
        if (mc.player.isDead()) {
            // Do not keep placing/opening while dead. Leave an already placed box in the world and
            // report its position so it can be recovered after respawning.
            fail("player died during shulker handling"
                    + (placedPos == null ? "" : "; the shulker may remain at " + posText(placedPos)));
            return;
        }

        switch (phase) {
            case PLACE -> tickPlace(policy);
            case WAIT_PLACED -> {
                if (placedPos == null) {
                    fail("internal error: no placed shulker position");
                    return;
                }
                BlockState state = mc.world.getBlockState(placedPos);
                if (isShulkerState(state, shulkerItemId)) {
                    unconfirmedWaits = 0;
                    phase = Phase.OPEN;
                    phaseTicks = 0;
                } else if (phaseTicks > 40 || !state.isAir() && !state.isReplaceable()) {
                    BlockPos late = findAttemptedShulker();
                    if (late != null) {
                        // The previous placement packet arrived late; adopt it instead of placing a
                        // second box.
                        placedPos = late;
                        unconfirmedWaits = 0;
                        phaseTicks = 0;
                        return;
                    }
                    if (InventoryService.countItem(shulkerItemId) < countBefore) {
                        // A box left the inventory but no block is visible yet. Wait a bounded
                        // amount of time for confirmation, then stop rather than placing another.
                        if (unconfirmedWaits < 2) {
                            unconfirmedWaits++;
                            phaseTicks = 0;
                            return;
                        }
                        fail("a shulker was consumed but not confirmed; check around "
                                + posText(placedPos) + " before continuing");
                        return;
                    }
                    placementAttempts++;
                    placedPos = null;
                    phase = Phase.PLACE;
                    phaseTicks = 0;
                }
            }
            case OPEN -> {
                if (placedPos == null || !container.beginOpen(placedPos, policy.openTimeoutTicks())) {
                    fail("could not open shulker: " + container.error());
                    return;
                }
                phase = Phase.WAIT_SCREEN;
                phaseTicks = 0;
            }
            case WAIT_SCREEN -> {
                ContainerService.OpenResult result = container.tickOpen();
                if (result == ContainerService.OpenResult.OPENED) {
                    if (container.containerSlots() != 27) {
                        container.close();
                        fail("opened screen is not a shulker box (slots: " + container.containerSlots() + ")");
                        return;
                    }
                    phase = Phase.EMPTY;
                    phaseTicks = 0;
                } else if (result == ContainerService.OpenResult.FAILED) {
                    fail("could not open shulker: " + container.error());
                }
            }
            case EMPTY -> {
                if (!container.isOpen()) {
                    fail("shulker screen closed before contents were emptied");
                    return;
                }
                if (container.contentsEmpty()) {
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                    return;
                }
                if (InventoryService.freeSlots() <= policy.reserveSlots()) {
                    // Never break a non-empty shulker. Leave it placed, close cleanly, and report.
                    fail("inventory became full while emptying the shulker; it was left at " + posText(placedPos));
                    container.close();
                    return;
                }
                container.quickMoveAllContents(6, policy.reserveSlots());
                if (container.contentsEmpty()) {
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                } else if (phaseTicks > 400) {
                    fail("timed out emptying shulker; it was left at " + posText(placedPos));
                    container.close();
                }
            }
            case CLOSE -> {
                countAfterEmpty = InventoryService.countItem(shulkerItemId);
                if (container.isOpen()) {
                    container.close();
                    phase = Phase.WAIT_CLOSED;
                    phaseTicks = 0;
                } else {
                    phase = Phase.BREAK;
                    phaseTicks = 0;
                }
            }
            case WAIT_CLOSED -> {
                if (mc.currentScreen == null) {
                    phase = Phase.BREAK;
                    phaseTicks = 0;
                } else if (phaseTicks > 60) {
                    fail("shulker screen did not close");
                }
            }
            case BREAK -> {
                if (placedPos == null) {
                    fail("internal error: no shulker position to break");
                    return;
                }
                BlockState state = mc.world.getBlockState(placedPos);
                if (state.isAir()) {
                    phase = Phase.PICKUP;
                    phaseTicks = 0;
                    return;
                }
                if (!isVerifiedEmpty(placedPos)) {
                    fail("refusing to break a shulker that is not verified empty at " + posText(placedPos)
                            + "; recover it manually");
                    return;
                }
                if (!McInteract.withinReach(placedPos, 4.8)) {
                    fail("shulker is out of reach for breaking");
                    return;
                }
                if (!breaker.request(placedPos)) {
                    placementAttempts++;
                    if (placementAttempts > policy.maxRetries()) {
                        fail("could not start breaking the empty shulker at " + posText(placedPos));
                        return;
                    }
                }
                phase = Phase.WAIT_BREAK;
                phaseTicks = 0;
            }
            case WAIT_BREAK -> {
                if (mc.world.getBlockState(placedPos).isAir()) {
                    breaker.stop(false);
                    phase = Phase.PICKUP;
                    phaseTicks = 0;
                } else if (phaseTicks > policy.breakTimeoutTicks()) {
                    breaker.stop(true);
                    fail("timed out breaking the empty shulker at " + posText(placedPos));
                }
            }
            case PICKUP -> {
                int now = InventoryService.countItem(shulkerItemId);
                if (now > countAfterEmpty && now >= countBefore) {
                    finish(true, "");
                } else if (phaseTicks > policy.pickupTimeoutTicks()) {
                    fail("shulker broke but was not picked up; collect it at " + posText(placedPos));
                }
            }
            default -> {
            }
        }
    }

    private void tickPlace(Policy policy) {
        int carried = InventoryService.countItem(shulkerItemId);
        if (carried <= 0) {
            fail("the shulker box is no longer in the inventory");
            return;
        }
        BlockPos late = findAttemptedShulker();
        if (late != null) {
            placedPos = late;
            phase = Phase.WAIT_PLACED;
            phaseTicks = 0;
            return;
        }
        if (carried < countBefore) {
            // A previous placement consumed a box that is not visible yet. Never place a second
            // one; the timeout path above waits for confirmation before giving up safely.
            fail("a previously placed shulker is unconfirmed; check around "
                    + posText(attemptedPlacements.isEmpty() ? null : attemptedPlacements.get(0))
                    + " before continuing");
            return;
        }
        var item = McStates.item(shulkerItemId);
        if (item == null) {
            fail("unknown shulker item: " + shulkerItemId);
            return;
        }
        BlockState state = ((net.minecraft.item.BlockItem) item).getBlock().getDefaultState();
        int hotbar = ensureShulkerHotbar();
        if (hotbar < 0) {
            fail("the selected shulker box is no longer available in the inventory");
            return;
        }
        List<BlockPos> candidates = placementCandidates(policy);
        if (candidates.isEmpty()) {
            placementAttempts++;
            if (placementAttempts > policy.maxRetries()) {
                fail("no safe position to place the shulker");
            }
            return;
        }
        for (BlockPos candidate : candidates) {
            if (!PlacementService.canPlace(candidate, state)) continue;
            if (PlacementService.placeFromHotbarSlot(candidate, state, hotbar, true, null, false)) {
                BlockPos immutable = candidate.toImmutable();
                if (!attemptedPlacements.contains(immutable)) attemptedPlacements.add(immutable);
                placedPos = immutable;
                phase = Phase.WAIT_PLACED;
                phaseTicks = 0;
                return;
            }
        }
        placementAttempts++;
        if (placementAttempts > policy.maxRetries()) {
            fail("could not place the shulker above or beside the player");
        }
    }

    private List<BlockPos> placementCandidates(Policy policy) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos feet = mc.player.getBlockPos();
        if (policy.aboveHead) {
            addCandidate(result, feet.up(2));
            addCandidate(result, feet.up(3));
        }
        if (policy.fallbackPositions) {
            int[][] offsets = {{1, 2, 0}, {-1, 2, 0}, {0, 2, 1}, {0, 2, -1}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}};
            for (int[] offset : offsets) {
                addCandidate(result, feet.add(offset[0], offset[1], offset[2]));
            }
            for (int distance = 2; distance <= 3; distance++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    for (int dz = -distance; dz <= distance; dz++) {
                        if (Math.abs(dx) != distance && Math.abs(dz) != distance) continue;
                        addCandidate(result, feet.add(dx, 2, dz));
                    }
                }
            }
        }
        return result;
    }

    private void addCandidate(List<BlockPos> list, BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        if (!McInteract.withinReach(pos, 4.4)) return;
        if (!mc.world.getBlockState(pos).isAir() && !mc.world.getBlockState(pos).isReplaceable()) return;
        if (list.contains(pos)) return;
        list.add(pos);
    }

    /**
     * Finds the exact chosen box (same item and contents) and ensures it occupies a hotbar slot.
     * If the stack moved since the request, only an identical box is adopted; a box with different
     * contents is never substituted.
     */
    private int ensureShulkerHotbar() {
        if (mc.player == null) return -1;
        if (shulkerSlot >= 0 && shulkerSlot < 36
                && isChosenShulker(mc.player.getInventory().getStack(shulkerSlot))) {
            int hotbar = InventoryService.ensureHotbarSlot(shulkerSlot);
            if (hotbar >= 0) shulkerSlot = hotbar;
            return hotbar;
        }
        for (InventoryService.ShulkerStack candidate : InventoryService.carriedShulkers()) {
            if (!Objects.equals(candidate.itemId(), shulkerItemId)) continue;
            if (!candidate.contents().equals(chosenContents)) continue;
            shulkerSlot = candidate.slot();
            int hotbar = InventoryService.ensureHotbarSlot(shulkerSlot);
            if (hotbar >= 0) shulkerSlot = hotbar;
            return hotbar;
        }
        return -1;
    }

    private boolean isChosenShulker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !McStates.isShulker(stack)) return false;
        if (!Objects.equals(McStates.itemId(stack.getItem()), shulkerItemId)) return false;
        return InventoryService.contents(stack).equals(chosenContents);
    }

    /** A placement packet may arrive after the wait timeout. Adopt that block rather than a second one. */
    private BlockPos findAttemptedShulker() {
        if (mc.world == null) return null;
        for (BlockPos pos : attemptedPlacements) {
            if (isShulkerState(mc.world.getBlockState(pos), shulkerItemId)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private InventoryService.ShulkerStack chooseShulker(String wanted) {
        List<InventoryService.ShulkerStack> shulkers = InventoryService.carriedShulkers();
        InventoryService.ShulkerStack fallback = null;
        for (InventoryService.ShulkerStack shulker : shulkers) {
            if (shulker.contents().isEmpty()) continue;
            if (wanted != null) {
                // A requested item must be in the chosen box; opening an unrelated shulker would
                // waste a placement cycle and could fill the inventory with unwanted items.
                if (shulker.count(wanted) > 0) return shulker;
                continue;
            }
            if (fallback == null) fallback = shulker;
        }
        return wanted == null ? fallback : null;
    }

    private boolean isShulkerState(BlockState state, String itemId) {
        if (state == null || state.isAir()) return false;
        String actual = McStates.itemId(state);
        return actual != null && actual.equals(itemId);
    }

    /**
     * The break phase is only allowed after a real block-entity check proves the box is empty. If
     * the entity is unavailable (unloaded/chunk edge), the safe action is to leave the box alone.
     */
    private boolean isVerifiedEmpty(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory inventory)) return false;
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private void fail(String message) {
        error = message;
        finished = true;
        success = false;
        active = false;
        phase = Phase.FAILED;
        breaker.stop(true);
        if (container.isOpen() || container.isOpening()) container.close();
    }

    private void finish(boolean ok, String message) {
        success = ok;
        error = message;
        finished = true;
        active = false;
        phase = ok ? Phase.DONE : Phase.FAILED;
        breaker.stop(false);
        if (container.isOpen() || container.isOpening()) container.close();
    }

    public boolean isActive() {
        return active;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean wasSuccessful() {
        return success;
    }

    public String error() {
        return error;
    }

    public Phase phase() {
        return phase;
    }

    public BlockPos placedPos() {
        return placedPos;
    }

    public String shulkerItemId() {
        return shulkerItemId;
    }

    public String wantedItem() {
        return wantedItem;
    }

    public boolean ownsScreen() {
        return container.isOpen() || container.isOpening();
    }

    public void clearFinished() {
        finished = false;
        success = false;
        error = "";
        phase = Phase.IDLE;
        placedPos = null;
    }

    /** Closes our screen and stops breaking; a non-empty placed shulker is intentionally left in place. */
    public void abort() {
        if (container.isOpen() || container.isOpening()) container.close();
        breaker.stop(true);
        if (active) {
            error = "shulker operation aborted" + (placedPos == null ? "" : "; shulker may be at " + posText(placedPos));
            finished = true;
            success = false;
            active = false;
            phase = Phase.FAILED;
        }
    }

    private static String posText(BlockPos pos) {
        return pos == null ? "unknown" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
