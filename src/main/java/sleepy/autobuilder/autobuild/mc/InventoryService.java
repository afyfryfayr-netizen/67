package sleepy.autobuilder.autobuild.mc;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import sleepy.autobuilder.autobuild.core.BlockKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real inventory inspection and hotbar preparation. Container-menu slot ids are handled separately
 * by {@link ContainerService}.
 */
public final class InventoryService {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private InventoryService() {
    }

    public static Map<String, Integer> looseCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (mc.player == null) return counts;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || McStates.isShulker(stack)) continue;
            String id = McStates.itemId(stack.getItem());
            if (id != null) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    public static Map<String, Integer> shulkerContentsCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ShulkerStack shulker : carriedShulkers()) {
            shulker.contents().forEach((id, count) -> counts.merge(id, count, Integer::sum));
        }
        return counts;
    }

    public static Map<String, Integer> totalCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>(looseCounts());
        shulkerContentsCounts().forEach((id, count) -> counts.merge(id, count, Integer::sum));
        return counts;
    }

    public static List<ShulkerStack> carriedShulkers() {
        List<ShulkerStack> result = new ArrayList<>();
        if (mc.player == null) return result;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!McStates.isShulker(stack)) continue;
            result.add(new ShulkerStack(i, McStates.itemId(stack.getItem()), contents(stack), stack.getCount()));
        }
        return result;
    }

    public static int carriedShulkerCount() {
        int count = 0;
        if (mc.player == null) return 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (McStates.isShulker(stack)) count += stack.getCount();
        }
        return count;
    }

    public static Map<String, Integer> contents(ItemStack shulker) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (shulker == null || shulker.isEmpty()) return counts;
        ItemStack[] items = new ItemStack[27];
        try {
            Utils.getItemsInContainerItem(shulker, items);
        } catch (Throwable ignored) {
            return counts;
        }
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;
            String id = McStates.itemId(stack.getItem());
            if (id != null) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    public static int countItem(Item item) {
        if (mc.player == null || item == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    public static int countItem(String itemId) {
        Item item = McStates.item(itemId);
        return item == null ? 0 : countItem(item);
    }

    public static int countBlocks(String itemId) {
        return looseCounts().getOrDefault(BlockKey.normalise(itemId), 0);
    }

    public static int freeSlots() {
        if (mc.player == null) return 0;
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    public static int findSlot(Item item) {
        FindItemResult result = InvUtils.find(item);
        return result.found() ? result.slot() : -1;
    }

    /**
     * Returns a hotbar index 0..8 containing the item, moving it from the main inventory if needed.
     * Returns -1 when the item is not carried or the hotbar is completely full of other items.
     */
    public static int ensureHotbar(Item item) {
        if (mc.player == null || item == null) return -1;
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) return hotbar.slot();

        FindItemResult anywhere = InvUtils.find(item);
        if (!anywhere.found()) return -1;

        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target == -1) target = mc.player.getInventory().selectedSlot;

        int slot = anywhere.slot();
        if (slot >= 9 && slot <= 35) {
            InvUtils.move().from(slot).toHotbar(target);
        } else if (slot == 40) {
            InvUtils.move().fromOffhand().toHotbar(target);
        } else {
            return -1;
        }
        ItemStack moved = mc.player.getInventory().getStack(target);
        return moved.isOf(item) ? target : -1;
    }

    /**
     * Returns a hotbar index 0..8 that holds exactly the stack from {@code inventorySlot}, moving
     * that stack (never a different stack of the same item type) into the hotbar when needed.
     * Returns -1 when the slot is empty, the stack cannot be moved, or a screen is open.
     */
    public static int ensureHotbarSlot(int inventorySlot) {
        if (mc.player == null || inventorySlot < 0 || inventorySlot > 35) return -1;
        ItemStack source = mc.player.getInventory().getStack(inventorySlot);
        if (source.isEmpty()) return -1;
        if (inventorySlot < 9) return inventorySlot;
        if (mc.currentScreen != null) return -1;
        Item sourceItem = source.getItem();

        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target == -1) target = mc.player.getInventory().selectedSlot;

        InvUtils.move().from(inventorySlot).toHotbar(target);
        ItemStack moved = mc.player.getInventory().getStack(target);
        return !moved.isEmpty() && moved.getItem() == sourceItem ? target : -1;
    }

    public static int findBestShulker(Set<String> needed, Set<String> excludedItemIds) {
        ShulkerStack best = bestShulker(needed, excludedItemIds);
        return best == null ? -1 : best.slot();
    }

    public static ShulkerStack bestShulker(Set<String> needed, Set<String> excludedItemIds) {
        ShulkerStack best = null;
        int bestScore = 0;
        for (ShulkerStack shulker : carriedShulkers()) {
            if (excludedItemIds != null && excludedItemIds.contains(shulker.itemId())) continue;
            int score = 0;
            for (String id : needed) score += shulker.count(id);
            if (score > bestScore) {
                best = shulker;
                bestScore = score;
            }
        }
        return best;
    }

    public record ShulkerStack(int slot, String itemId, Map<String, Integer> contents, int stackCount) {
        public ShulkerStack {
            contents = Map.copyOf(contents == null ? Map.of() : contents);
        }

        public int count(String itemId) {
            return contents.getOrDefault(BlockKey.normalise(itemId), 0);
        }

        public int totalItems() {
            int total = 0;
            for (int count : contents.values()) total += count;
            return total;
        }
    }
}
