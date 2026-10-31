package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Rotation-aware block interaction. Meteor's {@link Rotations} sends the look packet and then runs
 * the callback, so the interaction packet observes the same rotation the server just received.
 */
public final class McInteract {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private McInteract() {
    }

    public static boolean withinReach(BlockPos pos, double range) {
        if (mc.player == null || pos == null) return false;
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    public static Direction visibleFace(BlockPos pos) {
        if (mc.world == null) return Direction.UP;
        Direction[] order = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};
        for (Direction side : order) {
            BlockPos neighbour = pos.offset(side);
            if (mc.world.getBlockState(neighbour).isAir() || mc.world.getBlockState(neighbour).isReplaceable()) {
                return side;
            }
        }
        return Direction.UP;
    }

    public static BlockHitResult hitResult(BlockPos pos, Direction side) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d hit = center.add(side.getOffsetX() * 0.49, side.getOffsetY() * 0.49, side.getOffsetZ() * 0.49);
        return new BlockHitResult(hit, side, pos, false);
    }

    /**
     * Looks at the block and, in the rotation callback, right-clicks the chosen face.
     * The callback runs on the client thread after the look packet is sent.
     */
    public static void lookAndUse(BlockPos pos, Runnable after) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            if (after != null) after.run();
            return;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI));

        Direction side = visibleFace(pos);
        BlockHitResult hit = hitResult(pos, side);
        Rotations.rotate(yaw, pitch, 100, () -> {
            if (mc.player != null && mc.interactionManager != null && mc.world != null && !mc.world.getBlockState(pos).isAir()) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            if (after != null) after.run();
        });
    }

    public static void lookAt(BlockPos pos) {
        if (mc.player == null) return;
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Rotations.rotate((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f,
                (float) (-(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI)), 100);
    }
}
