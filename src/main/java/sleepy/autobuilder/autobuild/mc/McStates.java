package sleepy.autobuilder.autobuild.mc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import sleepy.autobuilder.autobuild.core.BlockKey;
import sleepy.autobuilder.autobuild.core.StateSignature;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversions between Minecraft states/items and the pure core model.
 */
public final class McStates {
    private McStates() {
    }

    public static String itemId(Item item) {
        if (item == null || item == Items.AIR) return null;
        return Registries.ITEM.getId(item).toString();
    }

    public static String itemId(BlockState state) {
        if (state == null) return null;
        return itemId(state.getBlock().asItem());
    }

    public static String blockId(Block block) {
        if (block == null) return null;
        return Registries.BLOCK.getId(block).toString();
    }

    public static StateSignature signature(BlockState state) {
        if (state == null) return null;
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            properties.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
        }
        return new StateSignature(BlockKey.of(blockId(state.getBlock())), properties);
    }

    public static Block block(String id) {
        if (id == null) return null;
        try {
            Identifier identifier = id.contains(":") ? Identifier.of(id) : Identifier.of("minecraft", id);
            Block block = Registries.BLOCK.get(identifier);
            return block == Blocks.AIR ? null : block;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Item item(String id) {
        if (id == null) return null;
        try {
            Identifier identifier = id.contains(":") ? Identifier.of(id) : Identifier.of("minecraft", id);
            Item item = Registries.ITEM.get(identifier);
            return item == Items.AIR ? null : item;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static BlockState defaultState(String id) {
        Block block = block(id);
        return block == null ? null : block.getDefaultState();
    }

    public static boolean isShulker(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean isShulkerItem(Item item) {
        return item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean isAir(BlockState state) {
        return state == null || state.isAir();
    }

    public static boolean sameBlockType(BlockState expected, BlockState actual) {
        return expected != null && actual != null && expected.getBlock() == actual.getBlock();
    }
}
