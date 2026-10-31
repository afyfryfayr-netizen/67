package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * One-target vanilla mining service for the Auto Builder. It starts a break, keeps the vanilla
 * block-breaking progress alive each client tick, selects the fastest hotbar tool, and releases the
 * selected slot when the target is finished or the job is stopped.
 */
public final class BreakService {
    private static final BreakService INSTANCE = new BreakService();
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private BlockPos target;
    private int toolSlot = -1;
    private boolean swapped;

    private BreakService() {
    }

    public static BreakService get() {
        return INSTANCE;
    }

    public boolean request(BlockPos pos) {
        if (mc.world == null || mc.player == null || mc.interactionManager == null || pos == null) return false;
        BlockPos immutable = pos.toImmutable();
        if (target != null && !target.equals(immutable)) stop(false);
        target = immutable;

        BlockState state = mc.world.getBlockState(immutable);
        if (state.isAir() || state.isReplaceable()) {
            target = null;
            return false;
        }

        selectTool(state);
        Direction side = Direction.UP;
        mc.interactionManager.attackBlock(immutable, side);
        mc.interactionManager.updateBlockBreakingProgress(immutable, side);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public void tick() {
        if (target == null || mc.world == null || mc.player == null || mc.interactionManager == null) return;
        BlockState state = mc.world.getBlockState(target);
        if (state.isAir() || state.isReplaceable()) {
            stop(false);
            return;
        }

        Direction side = Direction.UP;
        mc.interactionManager.updateBlockBreakingProgress(target, side);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void selectTool(BlockState state) {
        if (mc.player == null) return;
        FindItemResult tool = InvUtils.findFastestTool(state);
        if (!tool.found() || tool.slot() < 0 || tool.slot() > 8 || tool.slot() == toolSlot) return;
        InvUtils.swap(tool.slot(), true);
        toolSlot = tool.slot();
        swapped = true;
    }

    public boolean isBusy() {
        if (target == null || mc.world == null) return false;
        BlockState state = mc.world.getBlockState(target);
        return !state.isAir() && !state.isReplaceable();
    }

    public BlockPos target() {
        return target;
    }

    public void stop(boolean abort) {
        if (abort && target != null && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        target = null;
        toolSlot = -1;
        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }
    }
}
