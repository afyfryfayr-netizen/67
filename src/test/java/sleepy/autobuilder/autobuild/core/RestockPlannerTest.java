package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RestockPlannerTest {
    private static final RestockPlanner.RestockOptions OPTIONS = new RestockPlanner.RestockOptions(
            2, 80, 4, 2, true, true, true, true, true, List.of());

    @Test
    void smallRequirementTakesOnlyExactLooseCount() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 10),
                List.of(new RestockPlanner.StackOffer("minecraft:stone", 64)),
                List.of(),
                OPTIONS, 0, 20);
        RestockPlanner.CollectPlan item = plan.items().get(0);
        assertEquals(10, item.planned());
        assertEquals(1, item.stackTakes().size());
        assertEquals(10, item.stackTakes().get(0).count());
        assertTrue(item.satisfied());
        assertTrue(plan.canSatisfyEverything());
    }

    @Test
    void smallRequirementUsesPartialShulkerWhenLooseStockIsShort() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 10),
                List.of(),
                List.of(new RestockPlanner.ShulkerOffer("minecraft:shulker_box",
                        Map.of("minecraft:stone", 30), 27)),
                OPTIONS, 0, 20);
        RestockPlanner.CollectPlan item = plan.items().get(0);
        assertEquals(10, item.planned());
        assertEquals(1, item.shulkerTakes().size());
        assertEquals(10, item.shulkerTakes().get(0).expectedCount());
        assertTrue(item.satisfied());
    }

    @Test
    void largeRequirementPrefersFullShulkersAndReportsShortage() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 200),
                List.of(),
                List.of(
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 64), 27),
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 64), 27),
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 20), 27)),
                OPTIONS, 0, 20);
        RestockPlanner.CollectPlan item = plan.items().get(0);
        assertEquals(128, item.planned());
        assertEquals(2, item.shulkerTakes().size());
        assertFalse(plan.canSatisfyEverything());
        assertTrue(plan.missing().contains("minecraft:stone"));
    }

    @Test
    void multiplePartialShulkersAreCombined() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 10),
                List.of(),
                List.of(
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 5), 27),
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 3), 27)),
                OPTIONS, 0, 20);
        RestockPlanner.CollectPlan item = plan.items().get(0);
        assertEquals(8, item.planned());
        assertEquals(2, item.shulkerTakes().size());
        assertFalse(plan.canSatisfyEverything());
        assertTrue(plan.missing().contains("minecraft:stone"));
    }

    @Test
    void carriedShulkerBudgetIsRespected() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 200),
                List.of(),
                List.of(
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 64), 27),
                        new RestockPlanner.ShulkerOffer("minecraft:shulker_box", Map.of("minecraft:stone", 64), 27)),
                new RestockPlanner.RestockOptions(2, 80, 1, 0, true, true, true, true, true, List.of()),
                0, 20);
        assertEquals(1, plan.plannedShulkers());
        assertFalse(plan.canSatisfyEverything());
    }

    @Test
    void wholeStackModeIsAllowedWhenConfigured() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 10),
                List.of(new RestockPlanner.StackOffer("minecraft:stone", 64)),
                List.of(),
                new RestockPlanner.RestockOptions(2, 80, 4, 0, false, true, true, true, true, List.of()),
                0, 20);
        assertEquals(64, plan.items().get(0).planned());
    }

    @Test
    void fillPercentageIsReportedFromActualContents() {
        RestockPlanner.ShulkerOffer full = new RestockPlanner.ShulkerOffer(
                "minecraft:shulker_box", Map.of("minecraft:stone", 27 * 64), 27);
        RestockPlanner.ShulkerOffer partial = new RestockPlanner.ShulkerOffer(
                "minecraft:shulker_box", Map.of("minecraft:stone", 640), 27);
        assertEquals(100.0, full.fillPercent(), 0.001);
        assertEquals(37.037, partial.fillPercent(), 0.01);
    }

    @Test
    void noSupplyProducesMissingEntry() {
        RestockPlanner.RestockPlan plan = RestockPlanner.plan(
                Map.of("minecraft:stone", 10), List.of(), List.of(), OPTIONS, 0, 20);
        assertFalse(plan.canSatisfyEverything());
        assertEquals(0, plan.items().get(0).planned());
        assertEquals(Set.of("minecraft:stone"), plan.missing());
    }
}
