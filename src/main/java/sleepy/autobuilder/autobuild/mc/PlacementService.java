package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import sleepy.autobuilder.autobuild.PlacementMethod;
import sleepy.autobuilder.manager.PlacementManager;

import java.util.List;

/**
 * One-block placement facade over the existing Printer/PlacementManager systems.
 *
 * <p>The checked variant distinguishes quota/cooldown from a real failure. The engine must not count
 * a rate-limit rejection as a failed placement attempt, otherwise a fast pipeline would exhaust
 * max-place-attempts while it is merely waiting for the next packet window.</p>
 */
public final class PlacementService {
    public enum Outcome {
        PLACED,
        NO_ITEM,
        OUT_OF_REACH,
        DENIED,
        NO_QUOTA,
        COOLDOWN,
        FAILED
    }

    public record Result(Outcome outcome, PlacementManager.PlacementDenyReason denyReason) {
        public Result {
            outcome = outcome == null ? Outcome.FAILED : outcome;
            denyReason = denyReason == null ? PlacementManager.PlacementDenyReason.NONE : denyReason;
        }

        public static Result placed() {
            return new Result(Outcome.PLACED, PlacementManager.PlacementDenyReason.NONE);
        }

        public static Result of(Outcome outcome) {
            return new Result(outcome, PlacementManager.PlacementDenyReason.NONE);
        }

        public boolean success() {
            return outcome == Outcome.PLACED;
        }

        public String reason() {
            return switch (outcome) {
                case PLACED -> "placed";
                case NO_ITEM -> "no item in inventory";
                case OUT_OF_REACH -> "out of reach";
                case NO_QUOTA -> "waiting for packet quota";
                case COOLDOWN -> "per-position cooldown";
                case DENIED -> denyReason.name().toLowerCase(java.util.Locale.ROOT);
                case FAILED -> "placement failed";
            };
        }
    }

    private PlacementService() {
    }

    public static boolean place(BlockPos pos, BlockState expected, int attempt, PlacementMethod method,
                                boolean useAirplace, Direction forcedSide, boolean stateAware) {
        return placeChecked(pos, expected, attempt, method, useAirplace, forcedSide, stateAware).success();
    }

    public static Result placeChecked(BlockPos pos, BlockState expected, int attempt,
                                      PlacementMethod method, boolean useAirplace,
                                      Direction forcedSide, boolean stateAware) {
        if (pos == null || expected == null || expected.isAir()) return Result.of(Outcome.FAILED);
        Block block = expected.getBlock();
        Item item = block.asItem();
        if (item == null || item == Items.AIR) return Result.of(Outcome.NO_ITEM);

        Direction side = forcedSide != null ? forcedSide
                : stateAware ? StatePlacementAdvisor.sideFor(expected, attempt) : null;

        if (method == PlacementMethod.VANILLA || !useAirplace) {
            FindItemResult hotbar = InvUtils.findInHotbar(item);
            if (!hotbar.found()) {
                int slot = InventoryService.ensureHotbar(item);
                if (slot < 0) return Result.of(Outcome.NO_ITEM);
                hotbar = InvUtils.findInHotbar(item);
            }
            if (!hotbar.found()) return Result.of(Outcome.NO_ITEM);
            return BlockUtils.place(pos, hotbar, true, 50, true, true, true)
                    ? Result.placed() : Result.of(Outcome.FAILED);
        }

        if (PlacementManager.get().getRemainingQuota() <= 0) return Result.of(Outcome.NO_QUOTA);
        if (PlacementManager.get().isOnCooldown(pos)) return Result.of(Outcome.COOLDOWN);

        int slot = InventoryService.ensureHotbar(item);
        if (slot < 0) return Result.of(Outcome.NO_ITEM);

        PlacementManager.PlacementCheck check = PlacementManager.get().checkPlacement(pos, block);
        if (!check.placeable()) {
            Outcome outcome = check.reason() == PlacementManager.PlacementDenyReason.OUT_OF_RANGE
                    ? Outcome.OUT_OF_REACH : Outcome.DENIED;
            return new Result(outcome, check.reason());
        }

        List<BlockPos> sent = PlacementManager.get().placeManyFromHotbarSlot(
                List.of(pos.toImmutable()), block, slot, side);
        if (!sent.isEmpty()) return Result.placed();
        if (PlacementManager.get().getRemainingQuota() <= 0) return Result.of(Outcome.NO_QUOTA);
        if (PlacementManager.get().isOnCooldown(pos)) return Result.of(Outcome.COOLDOWN);
        return Result.of(Outcome.FAILED);
    }

    /**
     * Places one block using exactly the given hotbar slot. Unlike {@link #place}, this never falls
     * back to another stack of the same item; callers that selected one specific shulker rely on
     * this so the box with the wanted contents is the one that gets placed.
     */
    public static boolean placeFromHotbarSlot(BlockPos pos, BlockState expected, int hotbarSlot,
                                              boolean useAirplace, Direction forcedSide, boolean stateAware) {
        if (pos == null || expected == null || expected.isAir()) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;
        ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() != expected.getBlock()) {
            return false;
        }
        Direction side = forcedSide != null ? forcedSide
                : stateAware ? StatePlacementAdvisor.sideFor(expected, 0) : null;
        if (!useAirplace) {
            return BlockUtils.place(pos, new FindItemResult(hotbarSlot, stack.getCount()),
                    true, 50, true, true, true);
        }
        if (PlacementManager.get().getRemainingQuota() <= 0) return false;
        if (PlacementManager.get().isOnCooldown(pos)) return false;
        List<BlockPos> sent = PlacementManager.get().placeManyFromHotbarSlot(
                List.of(pos.toImmutable()), expected.getBlock(), hotbarSlot, side);
        return !sent.isEmpty();
    }

    public static boolean canPlace(BlockPos pos, BlockState expected) {
        if (pos == null || expected == null || expected.isAir()) return false;
        return PlacementManager.get().checkPlacement(pos, expected.getBlock()).placeable();
    }

    public static PlacementManager.PlacementDenyReason denyReason(BlockPos pos, BlockState expected) {
        if (pos == null || expected == null) return PlacementManager.PlacementDenyReason.INVALID_INPUT;
        return PlacementManager.get().checkPlacement(pos, expected.getBlock()).reason();
    }
}
