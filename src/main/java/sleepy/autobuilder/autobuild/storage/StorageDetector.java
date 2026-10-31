package sleepy.autobuilder.autobuild.storage;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Raycasts the block the player is looking at and records a real, inspectable container. */
public final class StorageDetector {
    public static final double MAX_RAYCAST = 6.0;

    public record Result(boolean ok, String error, String name, BlockPos pos, String dimension, StorageType type) {
        public static Result failure(String message) {
            return new Result(false, message, "", null, "", StorageType.UNKNOWN);
        }

        public static Result success(BlockPos pos, String dimension, StorageType type) {
            return new Result(true, "", type.displayName(), pos, dimension, type);
        }
    }

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private StorageDetector() {
    }

    public static Result target() {
        if (mc.player == null || mc.world == null) return Result.failure("Join a world first.");

        HitResult hit = mc.player.raycast(MAX_RAYCAST, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() == HitResult.Type.MISS) {
            return Result.failure("No block is targeted. Look directly at a chest, barrel, shulker box or other container.");
        }

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        StorageType type = StorageType.detect(mc.world, pos);
        if (!type.isContainer()) {
            return Result.failure("Targeted block is " + state.getBlock().getName().getString()
                    + ", which is not a supported storage container.");
        }

        double range = mc.player.getBlockInteractionRange();
        double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        if (distance > range + 0.05) {
            return Result.failure("The targeted container is out of interaction range ("
                    + String.format("%.2f", distance) + " > " + String.format("%.2f", range) + ").");
        }

        String dimension = mc.world.getRegistryKey().getValue().toString();
        return Result.success(pos, dimension, type);
    }

    public static String currentDimension() {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    public static BlockPos playerBlockPos() {
        return mc.player == null ? null : mc.player.getBlockPos();
    }
}
