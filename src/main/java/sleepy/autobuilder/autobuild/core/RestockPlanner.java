package sleepy.autobuilder.autobuild.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic restock decision logic. Minecraft-facing code executes the returned plan.
 */
public final class RestockPlanner {
    private RestockPlanner() {
    }

    public static RestockPlan plan(Map<String, Integer> needed,
                                   List<StackOffer> looseStacks,
                                   List<ShulkerOffer> shulkers,
                                   RestockOptions options,
                                   int carriedShulkers,
                                   int freeInventorySlots) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        needed.forEach((id, count) -> {
            if (count != null && count > 0) remaining.put(BlockKey.normalise(id), count);
        });

        List<String> order = new ArrayList<>(remaining.keySet());
        if (options.materialPriority() != null && !options.materialPriority().isEmpty()) {
            Map<String, Integer> rank = new LinkedHashMap<>();
            int i = 0;
            for (String id : options.materialPriority()) rank.putIfAbsent(BlockKey.normalise(id), i++);
            order.sort(Comparator.comparingInt((String id) -> rank.getOrDefault(id, Integer.MAX_VALUE)).thenComparing(String::compareTo));
        } else {
            order.sort(String::compareTo);
        }

        List<CollectPlan> plans = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<Integer> usedStacks = new HashSet<>();
        Set<Integer> usedShulkers = new HashSet<>();
        int shulkerBudget = Math.max(0, options.maxCarriedShulkers() - carriedShulkers);
        int freeSlots = Math.max(0, freeInventorySlots - options.inventoryReserveSlots());

        List<StackOffer> stacks = looseStacks == null ? List.of() : looseStacks;
        List<ShulkerOffer> boxes = shulkers == null ? List.of() : shulkers;
        long exactStackLimit = (long) Math.max(0, options.twoStackThreshold()) * 64L;

        for (String itemId : order) {
            int want = remaining.get(itemId);
            int collected = 0;
            List<StackTake> stackTakes = new ArrayList<>();
            List<ShulkerTake> shulkerTakes = new ArrayList<>();

            // Loose stacks are always useful and preserve shulkers for larger jobs.
            if (options.preferExactStackCollection()) {
                for (int i = 0; i < stacks.size() && collected < want; i++) {
                    StackOffer offer = stacks.get(i);
                    if (usedStacks.contains(i) || !offer.itemId().equals(itemId) || offer.count() <= 0) continue;
                    int take = options.exactStackCollection() ? Math.min(offer.count(), want - collected) : offer.count();
                    stackTakes.add(new StackTake(i, itemId, take));
                    usedStacks.add(i);
                    collected += take;
                }
            }

            if (collected < want) {
                boolean largeJob = want > exactStackLimit;
                List<Integer> candidates = new ArrayList<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (usedShulkers.contains(i)) continue;
                    ShulkerOffer offer = boxes.get(i);
                    if (offer.count(itemId) <= 0) continue;
                    candidates.add(i);
                }

                if (largeJob && options.preferFullShulkers() && !candidates.isEmpty()) {
                    // Prefer shulkers at or above the configured fill level. If none qualify, use the
                    // fullest shulkers available (by item count) instead of refusing a build.
                    List<Integer> aboveThreshold = new ArrayList<>();
                    for (int index : candidates) {
                        if (boxes.get(index).fillPercent() >= options.preferredFillPercent()) aboveThreshold.add(index);
                    }
                    if (!aboveThreshold.isEmpty()) {
                        candidates = aboveThreshold;
                    } else {
                        int maxCount = 0;
                        for (int index : candidates) maxCount = Math.max(maxCount, boxes.get(index).count(itemId));
                        List<Integer> fullest = new ArrayList<>();
                        for (int index : candidates) {
                            if (boxes.get(index).count(itemId) >= maxCount) fullest.add(index);
                        }
                        candidates = fullest;
                    }
                }

                candidates.sort((a, b) -> {
                    ShulkerOffer oa = boxes.get(a);
                    ShulkerOffer ob = boxes.get(b);
                    if (largeJob && options.preferFullShulkers()) {
                        int cmp = Double.compare(ob.fillPercent(), oa.fillPercent());
                        if (cmp != 0) return cmp;
                    }
                    int cmp = Integer.compare(ob.count(itemId), oa.count(itemId));
                    if (cmp != 0) return cmp;
                    cmp = Double.compare(ob.fillPercent(), oa.fillPercent());
                    if (cmp != 0) return cmp;
                    return Integer.compare(a, b);
                });

                for (int index : candidates) {
                    if (collected >= want || shulkerBudget <= 0 || freeSlots <= 0) break;
                    if (!largeJob && !options.preferPartialShulkers()) break;
                    ShulkerOffer offer = boxes.get(index);
                    int take = Math.min(offer.count(itemId), want - collected);
                    shulkerTakes.add(new ShulkerTake(index, offer.shulkerItemId(), itemId, take));
                    usedShulkers.add(index);
                    shulkerBudget--;
                    freeSlots--;
                    collected += take;
                }
            }

            // If loose stacks were not preferred, fall back to them before reporting a shortage.
            if (collected < want && !options.preferExactStackCollection()) {
                for (int i = 0; i < stacks.size() && collected < want; i++) {
                    StackOffer offer = stacks.get(i);
                    if (usedStacks.contains(i) || !offer.itemId().equals(itemId) || offer.count() <= 0) continue;
                    int take = Math.min(offer.count(), want - collected);
                    stackTakes.add(new StackTake(i, itemId, take));
                    usedStacks.add(i);
                    collected += take;
                }
            }

            CollectPlan plan = new CollectPlan(itemId, want, collected, stackTakes, shulkerTakes);
            plans.add(plan);
            if (!plan.satisfied()) missing.add(itemId);
        }

        return new RestockPlan(plans, missing);
    }

    public record StackOffer(String itemId, int count) {
        public StackOffer {
            itemId = BlockKey.normalise(itemId);
            count = Math.max(0, count);
        }
    }

    public record ShulkerOffer(String shulkerItemId, Map<String, Integer> contents, int capacity) {
        public ShulkerOffer {
            shulkerItemId = BlockKey.normalise(shulkerItemId);
            capacity = Math.max(1, capacity);
            Map<String, Integer> normalised = new LinkedHashMap<>();
            if (contents != null) {
                contents.forEach((id, count) -> {
                    if (id != null && count != null && count > 0) normalised.merge(BlockKey.normalise(id), count, Integer::sum);
                });
            }
            contents = Map.copyOf(normalised);
        }

        public int count(String itemId) {
            return contents.getOrDefault(BlockKey.normalise(itemId), 0);
        }

        public int usedSlots() {
            return contents.size();
        }

        public double fillPercent() {
            int total = 0;
            for (int count : contents.values()) total += count;
            return Math.min(100.0, total * 100.0 / (capacity * 64.0));
        }
    }

    public record StackTake(int offerIndex, String itemId, int count) {
    }

    public record ShulkerTake(int offerIndex, String shulkerItemId, String itemId, int expectedCount) {
    }

    public record CollectPlan(String itemId, int needed, int planned, List<StackTake> stackTakes, List<ShulkerTake> shulkerTakes) {
        public CollectPlan {
            stackTakes = List.copyOf(stackTakes == null ? List.of() : stackTakes);
            shulkerTakes = List.copyOf(shulkerTakes == null ? List.of() : shulkerTakes);
        }

        public boolean satisfied() {
            return planned >= needed;
        }
    }

    public record RestockPlan(List<CollectPlan> items, Set<String> missing) {
        public RestockPlan {
            items = List.copyOf(items == null ? List.of() : items);
            missing = Set.copyOf(missing == null ? Set.of() : missing);
        }

        public boolean canSatisfyEverything() {
            return missing.isEmpty();
        }

        public int plannedShulkers() {
            int count = 0;
            for (CollectPlan item : items) count += item.shulkerTakes().size();
            return count;
        }
    }

    public record RestockOptions(
            int twoStackThreshold,
            int preferredFillPercent,
            int maxCarriedShulkers,
            int inventoryReserveSlots,
            boolean exactStackCollection,
            boolean preferExactStackCollection,
            boolean preferFullShulkers,
            boolean preferPartialShulkers,
            boolean preferMixedShulkers,
            Collection<String> materialPriority
    ) {
        public RestockOptions {
            twoStackThreshold = Math.max(0, twoStackThreshold);
            preferredFillPercent = Math.max(0, Math.min(100, preferredFillPercent));
            maxCarriedShulkers = Math.max(0, maxCarriedShulkers);
            inventoryReserveSlots = Math.max(0, inventoryReserveSlots);
            materialPriority = materialPriority == null ? List.of() : List.copyOf(materialPriority);
        }
    }
}
