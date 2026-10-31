package sleepy.autobuilder.autobuild.mc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.SevenBuilderAddon;

/**
 * Optional Litematica bridge. The direct implementation lives in {@link LitematicaBridgeImpl} and
 * is only touched when the Litematica mod is loaded.
 */
public final class LitematicaBridge {
    private static boolean checked;
    private static SchematicAccess access;

    private LitematicaBridge() {
    }

    public static boolean isAvailable() {
        return access() != null;
    }

    public static SchematicAccess.PlacementSnapshot snapshot() {
        SchematicAccess instance = access();
        if (instance == null) return null;
        try {
            return instance.snapshot();
        } catch (Throwable throwable) {
            SevenBuilderAddon.LOG.warn("Could not read Litematica placement", throwable);
            return null;
        }
    }

    public static BlockState expectedState(BlockPos pos) {
        SchematicAccess instance = access();
        if (instance == null || pos == null) return null;
        try {
            return instance.expectedState(pos);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static boolean isSchematicWorldReady() {
        SchematicAccess instance = access();
        if (instance == null) return false;
        try {
            return instance.isWorldReady();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static SchematicAccess access() {
        if (checked) return access;
        checked = true;
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return null;
        try {
            Class<?> type = Class.forName("sleepy.autobuilder.autobuild.mc.LitematicaBridgeImpl");
            Object instance = type.getDeclaredConstructor().newInstance();
            access = (SchematicAccess) instance;
        } catch (Throwable throwable) {
            SevenBuilderAddon.LOG.warn("Litematica is installed but its API could not be accessed", throwable);
            access = null;
        }
        return access;
    }
}
