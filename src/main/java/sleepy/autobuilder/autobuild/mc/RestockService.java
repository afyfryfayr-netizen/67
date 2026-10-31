package sleepy.autobuilder.autobuild.mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import sleepy.autobuilder.autobuild.TravelMode;
import sleepy.autobuilder.autobuild.core.BlockKey;
import sleepy.autobuilder.autobuild.core.RestockPlanner;
import sleepy.autobuilder.autobuild.storage.StorageEntry;
import sleepy.autobuilder.autobuild.storage.StorageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a real stash run: travel to configured stashes, inspect actual container slots, use the
 * pure {@link RestockPlanner} to choose loose stacks and shulker boxes, withdraw them through the
 * container screen, and report exactly what was collected.
 */
public final class RestockService {
    public enum Phase { IDLE, TRAVEL, OPENING, APPROACH, PLAN, WITHDRAW, CLOSE, WAIT_CLOSED, NEXT, DONE, FAILED }

    public record Policy(
            TravelMode travelMode,
            int travelTimeoutTicks,
            int travelRetries,
            int travelStallGraceTicks,
            double arrivalDistance,
            boolean acceptElytraTerms,
            double elytraMinDistance,
            double stashRadius,
            int openTimeoutTicks,
            int withdrawPerTick,
            int reserveSlots,
            boolean restockShulkers,
            int maxCarriedShulkers,
            int twoStackThreshold,
            int preferredFillPercent,
            boolean preferFullShulkers,
            boolean preferPartialShulkers,
            boolean exactStackCollection,
            boolean preferExactStackCollection,
            List<String> materialPriority
    ) {
        public Policy {
            withdrawPerTick = Math.max(1, withdrawPerTick);
            reserveSlots = Math.max(0, reserveSlots);
            stashRadius = Math.max(1.0, Math.min(32.0, stashRadius));
            travelStallGraceTicks = Math.max(20, travelStallGraceTicks);
            materialPriority = materialPriority == null ? List.of() : List.copyOf(materialPriority);
        }
    }

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final ContainerService container = new ContainerService();
    private final TravelService travel = new TravelService();

    private boolean active;
    private boolean done;
    private boolean success;
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int stashIndex;
    private Map<String, Integer> wanted = new LinkedHashMap<>();
    private Map<String, Integer> remaining = new LinkedHashMap<>();
    private Map<String, Integer> beforeLoose = new LinkedHashMap<>();
    private Map<String, Integer> beforeShulker = new LinkedHashMap<>();
    private Map<String, Integer> collected = new LinkedHashMap<>();
    private Map<String, Integer> shulkerCredit = new LinkedHashMap<>();
    private List<StorageEntry> stashes = List.of();
    private List<Integer> withdrawQueue = List.of();
    private int withdrawIndex;
    private int shulkersWithdrawn;
    private BlockPos approachTarget;
    private String message = "";
    private String error = "";
    private final List<String> skipped = new ArrayList<>();

    public boolean start(Map<String, Integer> wanted, List<StorageEntry> stashEntries, Policy policy) {
        if (active) return false;
        this.wanted = normalise(wanted);
        this.remaining = new LinkedHashMap<>(this.wanted);
        if (this.wanted.isEmpty()) {
            error = "nothing to restock";
            return false;
        }
        if (mc.world == null) {
            error = "not in a world";
            return false;
        }
        String dimension = mc.world.getRegistryKey().getValue().toString();
        List<StorageEntry> usable = new ArrayList<>();
        this.skipped.clear();
        if (stashEntries != null) {
            for (StorageEntry entry : stashEntries) {
                if (entry == null || !entry.enabled) continue;
                if (!entry.dimensionMatches(dimension)) {
                    skipped.add("storage '" + entry.name + "' is in " + entry.shortDimension());
                    StorageManager.get().recordFailure(entry.id, "skipped: wrong dimension");
                    continue;
                }
                if (!mc.world.isInBuildLimit(entry.pos())) {
                    skipped.add("storage '" + entry.name + "' is outside the world build height");
                    StorageManager.get().recordFailure(entry.id, "skipped: outside world build height");
                    continue;
                }
                // If the chunk is loaded, an exact storage entry must still point at a real,
                // supported container. Do not spend a long trip on a stale/hard-coded coordinate.
                if (entry.exactContainer && ContainerService.isChunkLoaded(entry.pos())
                        && !container.isSupportedContainer(entry.pos())) {
                    String reason = "no supported container at " + entry.pos().toShortString()
                            + " (stale or changed storage)";
                    skipped.add("storage '" + entry.name + "': " + reason);
                    StorageManager.get().recordFailure(entry.id, reason);
                    continue;
                }
                usable.add(entry);
            }
        }
        if (usable.isEmpty()) {
            error = stashEntries == null || stashEntries.isEmpty()
                    ? "no storage waypoints configured"
                    : "no enabled storage waypoint is in the current dimension (" + dimension + ")";
            return false;
        }
        this.stashes = usable;
        this.beforeLoose = InventoryService.looseCounts();
        this.beforeShulker = InventoryService.shulkerContentsCounts();
        this.collected = new LinkedHashMap<>();
        this.shulkerCredit = new LinkedHashMap<>();
        this.shulkersWithdrawn = 0;
        this.stashIndex = 0;
        this.withdrawQueue = List.of();
        this.withdrawIndex = 0;
        this.approachTarget = null;
        this.message = "";
        this.error = "";
        this.done = false;
        this.success = false;
        this.active = true;
        this.phase = Phase.TRAVEL;
        this.phaseTicks = 0;
        beginStash(policy);
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
            fail("player died during restocking");
            return;
        }
        switch (phase) {
            case TRAVEL -> {
                TravelService.Status status = travel.tick();
                switch (status.result()) {
                    case ARRIVED -> {
                        StorageEntry entry = currentStash();
                        if (entry == null) {
                            nextStash(policy);
                            return;
                        }
                        if (entry.exactContainer && !container.canReach(entry.pos())) {
                            if (!startApproach(entry, entry.pos(), policy)) nextStash(policy);
                            return;
                        }
                        StorageManager.get().markVisited(entry.id);
                        phase = Phase.OPENING;
                        phaseTicks = 0;
                    }
                    case FAILED -> {
                        StorageEntry entry = currentStash();
                        if (entry != null) {
                            StorageManager.get().recordFailure(entry.id, "travel failed: " + status.message());
                            skipped.add("could not reach storage '" + entry.name + "': " + status.message());
                        }
                        nextStash(policy);
                    }
                    default -> {
                    }
                }
            }
            case OPENING -> {
                StorageEntry stash = currentStash();
                if (stash == null) {
                    nextStash(policy);
                    return;
                }
                if (container.isOpening()) {
                    ContainerService.OpenResult result = container.tickOpen();
                    if (result == ContainerService.OpenResult.OPENED) {
                        StorageManager.get().markVisited(stash.id);
                        phase = Phase.PLAN;
                        phaseTicks = 0;
                    } else if (result == ContainerService.OpenResult.FAILED) {
                        StorageManager.get().recordFailure(stash.id, "could not open: " + container.error());
                        skipped.add("storage '" + stash.name + "': " + container.error());
                        nextStash(policy);
                    }
                } else if (stash.exactContainer) {
                    if (!openAt(stash, stash.pos(), policy)) nextStash(policy);
                } else {
                    // General waypoint: find the real container around the waypoint first. It may be
                    // outside interaction reach, so walk to it (never elytra for a <=32 block hop).
                    BlockPos target = container.findNearestContainer(stash.pos(), policy.stashRadius());
                    if (target == null) {
                        String reason = "no container found within "
                                + String.format("%.1f", policy.stashRadius()) + " blocks";
                        StorageManager.get().recordFailure(stash.id, reason);
                        skipped.add("storage '" + stash.name + "': " + reason);
                        nextStash(policy);
                    } else if (container.canReach(target)) {
                        if (!openAt(stash, target, policy)) nextStash(policy);
                    } else {
                        BlockPos walkTarget = TravelService.standableNear(target, 2);
                        if (walkTarget == null) walkTarget = target;
                        TravelService.StartResult result = travel.start(walkTarget, TravelMode.WALK, 1.5,
                                Math.min(400, Math.max(60, policy.travelTimeoutTicks())), 1,
                                policy.acceptElytraTerms(), policy.elytraMinDistance(),
                                policy.travelStallGraceTicks());
                        switch (result) {
                            case STARTED -> {
                                approachTarget = target;
                                phase = Phase.APPROACH;
                                phaseTicks = 0;
                            }
                            case ALREADY_THERE -> {
                                if (!openAt(stash, target, policy)) nextStash(policy);
                            }
                            case FAILED -> {
                                StorageManager.get().recordFailure(stash.id,
                                        "could not approach container: " + travel.error());
                                skipped.add("storage '" + stash.name + "': could not approach container at "
                                        + target.toShortString() + ": " + travel.error());
                                nextStash(policy);
                            }
                        }
                    }
                }
            }
            case APPROACH -> {
                TravelService.Status status = travel.tick();
                if (status.result() == TravelService.TickResult.ARRIVED) {
                    StorageEntry entry = currentStash();
                    if (entry == null) {
                        nextStash(policy);
                        return;
                    }
                    BlockPos target = approachTarget;
                    approachTarget = null;
                    if (target == null || !container.canReach(target)) {
                        String reason = "reached the waypoint but the container is still out of reach";
                        StorageManager.get().recordFailure(entry.id, reason);
                        skipped.add("storage '" + entry.name + "': " + reason);
                        nextStash(policy);
                    } else if (openAt(entry, target, policy)) {
                        phase = Phase.OPENING;
                        phaseTicks = 0;
                    } else {
                        nextStash(policy);
                    }
                } else if (status.result() == TravelService.TickResult.FAILED) {
                    StorageEntry entry = currentStash();
                    String reason = "could not approach container: " + status.message();
                    if (entry != null) {
                        StorageManager.get().recordFailure(entry.id, reason);
                        skipped.add("storage '" + entry.name + "': " + reason);
                    }
                    approachTarget = null;
                    nextStash(policy);
                }
            }
            case PLAN -> {
                planWithdrawals(policy);
                if (withdrawQueue.isEmpty()) {
                    StorageEntry entry = currentStash();
                    if (entry != null) skipped.add("storage '" + entry.name + "' had nothing useful");
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                } else {
                    phase = Phase.WITHDRAW;
                    phaseTicks = 0;
                }
            }
            case WITHDRAW -> {
                if (!container.isOpen()) {
                    StorageEntry entry = currentStash();
                    if (entry != null) StorageManager.get().recordFailure(entry.id, "container closed during withdrawal");
                    skipped.add("container closed during withdrawal");
                    nextStash(policy);
                    return;
                }
                if (InventoryService.freeSlots() <= policy.reserveSlots()) {
                    skipped.add("inventory full during withdrawal");
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                    return;
                }
                int moved = 0;
                while (withdrawIndex < withdrawQueue.size() && moved < policy.withdrawPerTick()) {
                    int slot = withdrawQueue.get(withdrawIndex++);
                    ItemStack stack = slot < container.handler().slots.size() ? container.handler().getSlot(slot).getStack() : ItemStack.EMPTY;
                    if (stack.isEmpty()) continue;
                    String id = McStates.itemId(stack.getItem());
                    if (policy.restockShulkers() && McStates.isShulker(stack) && id != null) {
                        int credit = 0;
                        for (Map.Entry<String, Integer> entry : InventoryService.contents(stack).entrySet()) {
                            if (remaining.containsKey(entry.getKey())) {
                                credit += entry.getValue();
                            }
                        }
                        if (credit > 0) shulkersWithdrawn++;
                    }
                    if (container.quickMove(slot)) moved++;
                }
                if (withdrawIndex >= withdrawQueue.size()) {
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                } else if (phaseTicks > 1200) {
                    StorageEntry entry = currentStash();
                    if (entry != null) StorageManager.get().recordFailure(entry.id, "withdrawal timed out");
                    skipped.add("timed out withdrawing from storage '" + (entry == null ? "?" : entry.name) + "'");
                    phase = Phase.CLOSE;
                    phaseTicks = 0;
                }
            }
            case CLOSE -> {
                if (container.isOpen()) {
                    container.close();
                    phase = Phase.WAIT_CLOSED;
                    phaseTicks = 0;
                } else {
                    phase = Phase.WAIT_CLOSED;
                    phaseTicks = 0;
                }
            }
            case WAIT_CLOSED -> {
                if (mc.currentScreen == null) {
                    updateCollected();
                    if (satisfied()) {
                        finish(true, "restock complete");
                    } else {
                        nextStash(policy);
                    }
                } else if (phaseTicks > 80) {
                    fail("container screen did not close");
                }
            }
            case NEXT -> {
                if (stashIndex >= stashes.size()) {
                    finish(!remaining.isEmpty(), remaining.isEmpty() ? "restock complete" : "stashes exhausted with items still missing");
                } else {
                    beginStash(policy);
                }
            }
            default -> {
            }
        }
    }

    private void beginStash(Policy policy) {
        if (stashIndex >= stashes.size()) {
            finish(!remaining.isEmpty(), "storage waypoints exhausted");
            return;
        }
        StorageEntry stash = stashes.get(stashIndex);
        if (mc.player == null) {
            fail("not in a world");
            return;
        }
        double distance = mc.player.getPos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(stash.pos()));
        double arrival = Math.max(2.5, policy.arrivalDistance());
        if (distance <= Math.max(3.5, arrival)) {
            if (stash.exactContainer && !container.canReach(stash.pos())) {
                if (!startApproach(stash, stash.pos(), policy)) nextStash(policy);
                return;
            }
            if (!openAt(stash, stash.pos(), policy)) nextStash(policy);
            return;
        }

        BlockPos travelTarget = stash.pos();
        if (stash.exactContainer) {
            BlockPos standable = TravelService.standableNear(stash.pos(), 2);
            if (standable != null) travelTarget = standable;
        }
        TravelService.StartResult result = travel.start(travelTarget, policy.travelMode(), arrival,
                policy.travelTimeoutTicks(), policy.travelRetries(), policy.acceptElytraTerms(),
                policy.elytraMinDistance(), policy.travelStallGraceTicks());
        switch (result) {
            case STARTED -> {
                phase = Phase.TRAVEL;
                phaseTicks = 0;
            }
            case ALREADY_THERE -> {
                StorageManager.get().markVisited(stash.id);
                if (stash.exactContainer && !container.canReach(stash.pos())) {
                    if (!startApproach(stash, stash.pos(), policy)) nextStash(policy);
                } else {
                    phase = Phase.OPENING;
                    phaseTicks = 0;
                }
            }
            case FAILED -> {
                StorageManager.get().recordFailure(stash.id, "travel failed: " + travel.error());
                skipped.add("could not travel to storage '" + stash.name + "': " + travel.error());
                nextStash(policy);
            }
        }
    }

    /**
     * Starts a short local walk to a standable position beside an exact container. This is always
     * walking, never elytra, and never travels to the container block itself.
     */
    private boolean startApproach(StorageEntry stash, BlockPos containerPos, Policy policy) {
        if (container.canReach(containerPos)) return openAt(stash, containerPos, policy);
        BlockPos standable = TravelService.standableNear(containerPos, 2);
        if (standable == null) {
            String reason = "no standable position beside the container at "
                    + containerPos.toShortString();
            StorageManager.get().recordFailure(stash.id, reason);
            skipped.add("storage '" + stash.name + "': " + reason);
            return false;
        }
        TravelService.StartResult result = travel.start(standable, TravelMode.WALK, 1.5,
                Math.min(400, Math.max(60, policy.travelTimeoutTicks())), 1,
                policy.acceptElytraTerms(), policy.elytraMinDistance(),
                policy.travelStallGraceTicks());
        switch (result) {
            case STARTED -> {
                approachTarget = containerPos;
                phase = Phase.APPROACH;
                phaseTicks = 0;
                return true;
            }
            case ALREADY_THERE -> {
                return openAt(stash, containerPos, policy);
            }
            case FAILED -> {
                String reason = "could not approach container: " + travel.error();
                StorageManager.get().recordFailure(stash.id, reason);
                skipped.add("storage '" + stash.name + "': " + reason);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean openAt(StorageEntry stash, BlockPos pos, Policy policy) {
        if (stash == null || pos == null) return false;
        if (!container.beginOpen(pos, policy.openTimeoutTicks())) {
            String reason = "could not open: " + container.error();
            StorageManager.get().recordFailure(stash.id, reason);
            skipped.add("storage '" + stash.name + "': " + reason);
            return false;
        }
        phase = Phase.OPENING;
        phaseTicks = 0;
        return true;
    }

    private void nextStash(Policy policy) {
        if (container.isOpen() || container.isOpening()) container.close();
        travel.cancel();
        stashIndex++;
        phase = Phase.NEXT;
        phaseTicks = 0;
        // NEXT is handled on the following tick so screen close can settle.
    }

    private void planWithdrawals(Policy policy) {
        List<ItemStack> contents = container.contents();
        List<RestockPlanner.StackOffer> stacks = new ArrayList<>();
        List<Integer> stackSlots = new ArrayList<>();
        List<RestockPlanner.ShulkerOffer> shulkers = new ArrayList<>();
        List<Integer> shulkerSlots = new ArrayList<>();
        Map<String, Integer> found = new LinkedHashMap<>();

        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) continue;
            String id = McStates.itemId(stack.getItem());
            if (id == null) continue;
            if (policy.restockShulkers() && McStates.isShulker(stack)) {
                Map<String, Integer> inner = InventoryService.contents(stack);
                if (!inner.isEmpty()) {
                    shulkers.add(new RestockPlanner.ShulkerOffer(id, inner, 27));
                    shulkerSlots.add(i);
                    for (Map.Entry<String, Integer> entry : inner.entrySet()) {
                        if (remaining.containsKey(entry.getKey())) {
                            found.merge(entry.getKey(), entry.getValue(), Integer::sum);
                        }
                    }
                }
            } else {
                stacks.add(new RestockPlanner.StackOffer(id, stack.getCount()));
                stackSlots.add(i);
                String key = BlockKey.normalise(id);
                if (remaining.containsKey(key)) found.merge(key, stack.getCount(), Integer::sum);
            }
        }

        StorageEntry stash = currentStash();
        if (stash != null) {
            StorageManager.get().recordSearch(stash.id, found,
                    found.isEmpty() ? "no needed materials" : "found materials");
        }

        RestockPlanner.RestockOptions options = new RestockPlanner.RestockOptions(
                policy.twoStackThreshold(),
                policy.preferredFillPercent(),
                policy.maxCarriedShulkers(),
                policy.reserveSlots(),
                policy.exactStackCollection(),
                policy.preferExactStackCollection(),
                policy.preferFullShulkers(),
                policy.preferPartialShulkers(),
                true,
                policy.materialPriority()
        );
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(remaining, stacks, shulkers, options,
                InventoryService.carriedShulkerCount(), InventoryService.freeSlots());
        List<Integer> queue = new ArrayList<>();
        for (RestockPlanner.CollectPlan item : plan.items()) {
            for (RestockPlanner.StackTake take : item.stackTakes()) {
                if (take.offerIndex() >= 0 && take.offerIndex() < stackSlots.size()) {
                    int slot = stackSlots.get(take.offerIndex());
                    if (!queue.contains(slot)) queue.add(slot);
                }
            }
            for (RestockPlanner.ShulkerTake take : item.shulkerTakes()) {
                if (take.offerIndex() >= 0 && take.offerIndex() < shulkerSlots.size()) {
                    int slot = shulkerSlots.get(take.offerIndex());
                    if (!queue.contains(slot)) queue.add(slot);
                }
            }
        }
        this.withdrawQueue = queue;
        this.withdrawIndex = 0;
        int planned = 0;
        for (RestockPlanner.CollectPlan item : plan.items()) planned += item.planned();
        this.message = "storage '" + (stash == null ? "?" : stash.name) + "': withdrawing " + planned
                + " items in " + queue.size() + " stacks";
    }

    private void updateCollected() {
        Map<String, Integer> loose = InventoryService.looseCounts();
        Map<String, Integer> shulkerContents = InventoryService.shulkerContentsCounts();
        collected.clear();
        shulkerCredit.clear();
        for (String id : wanted.keySet()) {
            String key = BlockKey.normalise(id);
            int now = loose.getOrDefault(key, 0);
            int before = beforeLoose.getOrDefault(key, 0);
            int gained = Math.max(0, now - before);
            if (gained > 0) collected.put(id, gained);

            // Credit only shulker contents that actually appeared during this run. Crediting a box
            // before the server confirms the withdrawal could mark the restock satisfied without
            // the items ever entering the inventory.
            int shulkerNow = shulkerContents.getOrDefault(key, 0);
            int shulkerBefore = beforeShulker.getOrDefault(key, 0);
            int shulkerGain = Math.max(0, shulkerNow - shulkerBefore);
            if (shulkerGain > 0) shulkerCredit.put(id, shulkerGain);
        }
        remaining = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            int have = collected.getOrDefault(entry.getKey(), 0) + shulkerCredit.getOrDefault(entry.getKey(), 0);
            int left = Math.max(0, entry.getValue() - have);
            if (left > 0) remaining.put(entry.getKey(), left);
        }
    }

    private boolean satisfied() {
        updateCollected();
        return remaining.isEmpty();
    }

    private void finish(boolean ok, String msg) {
        updateCollected();
        done = true;
        active = false;
        success = ok;
        message = msg;
        phase = ok ? Phase.DONE : Phase.FAILED;
        if (container.isOpen() || container.isOpening()) container.close();
        travel.cancel();
    }

    private void fail(String reason) {
        error = reason;
        done = true;
        active = false;
        success = false;
        phase = Phase.FAILED;
        if (container.isOpen() || container.isOpening()) container.close();
        travel.cancel();
    }

    private StorageEntry currentStash() {
        return stashIndex >= 0 && stashIndex < stashes.size() ? stashes.get(stashIndex) : null;
    }

    private static Map<String, Integer> normalise(Map<String, Integer> input) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (Map.Entry<String, Integer> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            result.merge(BlockKey.normalise(entry.getKey()), entry.getValue(), Integer::sum);
        }
        return result;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDone() {
        return done;
    }

    public boolean wasSuccessful() {
        return success;
    }

    public String message() {
        return message;
    }

    public String error() {
        return error;
    }

    public Phase phase() {
        return phase;
    }

    public Map<String, Integer> remaining() {
        return Map.copyOf(remaining);
    }

    public Map<String, Integer> collected() {
        return Map.copyOf(collected);
    }

    public Map<String, Integer> shulkerCredit() {
        return Map.copyOf(shulkerCredit);
    }

    public int shulkersWithdrawn() {
        return shulkersWithdrawn;
    }

    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    public boolean ownsScreen() {
        return container.isOpen() || container.isOpening();
    }

    public void clearDone() {
        done = false;
        success = false;
        error = "";
        message = "";
        phase = Phase.IDLE;
    }

    public void cancel() {
        if (container.isOpen() || container.isOpening()) container.close();
        travel.cancel();
        done = true;
        active = false;
        success = false;
        phase = Phase.IDLE;
        error = "restock cancelled";
    }

    public Set<String> missingItems() {
        return new LinkedHashSet<>(remaining.keySet());
    }
}
