package sleepy.autobuilder.autobuild.storage;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlastFurnaceBlock;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.FurnaceBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SmokerBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * Container types that can be recorded by the storage waypoint manager.
 *
 * <p>{@link #OTHER_CONTAINER} is used for modded or otherwise unknown blocks that expose a real
 * inventory block entity. It is still opened through the normal vanilla container screen, so the
 * restock service can inspect the actual slots.</p>
 */
public enum StorageType {
    CHEST("Chest"),
    TRAPPED_CHEST("Trapped chest"),
    BARREL("Barrel"),
    SHULKER_BOX("Shulker box"),
    ENDER_CHEST("Ender chest"),
    HOPPER("Hopper"),
    DROPPER("Dropper"),
    DISPENSER("Dispenser"),
    FURNACE("Furnace"),
    BLAST_FURNACE("Blast furnace"),
    SMOKER("Smoker"),
    BREWING_STAND("Brewing stand"),
    CRAFTER("Crafter"),
    LECTERN("Lectern"),
    OTHER_CONTAINER("Other container"),
    UNKNOWN("General waypoint / unknown");

    private final String displayName;

    StorageType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isContainer() {
        return this != UNKNOWN;
    }

    /** Best-effort type from a block alone. Inventory checks are done by {@link #detect(World, BlockPos)}. */
    public static StorageType fromBlock(Block block) {
        if (block instanceof TrappedChestBlock) return TRAPPED_CHEST;
        if (block instanceof ChestBlock) return CHEST;
        if (block instanceof BarrelBlock) return BARREL;
        if (block instanceof ShulkerBoxBlock) return SHULKER_BOX;
        if (block instanceof EnderChestBlock) return ENDER_CHEST;
        if (block instanceof HopperBlock) return HOPPER;
        if (block instanceof DropperBlock) return DROPPER;
        if (block instanceof DispenserBlock) return DISPENSER;
        if (block instanceof BlastFurnaceBlock) return BLAST_FURNACE;
        if (block instanceof SmokerBlock) return SMOKER;
        if (block instanceof FurnaceBlock) return FURNACE;
        if (block instanceof BrewingStandBlock) return BREWING_STAND;
        if (block instanceof CrafterBlock) return CRAFTER;
        if (block instanceof LecternBlock) return LECTERN;
        return UNKNOWN;
    }

    /** Detects a recordable container at a real world position, including unknown modded inventories. */
    public static StorageType detect(World world, BlockPos pos) {
        if (world == null || pos == null) return UNKNOWN;
        BlockState state = world.getBlockState(pos);
        StorageType known = fromBlock(state.getBlock());
        if (known.isContainer()) return known;

        BlockEntity entity = world.getBlockEntity(pos);
        if (entity instanceof Inventory) return OTHER_CONTAINER;
        return UNKNOWN;
    }

    public boolean matches(World world, BlockPos pos) {
        if (!isContainer()) return false;
        if (this == OTHER_CONTAINER) {
            BlockEntity entity = world == null ? null : world.getBlockEntity(pos);
            return entity instanceof Inventory;
        }
        return fromBlock(world.getBlockState(pos).getBlock()) == this;
    }

    public static StorageType parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
