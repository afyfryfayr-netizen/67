package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaterialPlannerTest {
    @Test
    void combinesLooseAndShulkerStock() {
        MaterialPlanner.MaterialPlan plan = MaterialPlanner.plan(
                Map.of("minecraft:stone", 100),
                Map.of("minecraft:stone", 64),
                Map.of("minecraft:stone", 20),
                Map.of(),
                List.of());
        MaterialPlanner.MaterialLine line = plan.lines().get("minecraft:stone");
        assertEquals(100, line.required());
        assertEquals(84, line.available());
        assertEquals(16, line.remaining());
        assertFalse(plan.complete());
    }

    @Test
    void reservationsReduceAvailableStock() {
        MaterialPlanner.MaterialPlan plan = MaterialPlanner.plan(
                Map.of("minecraft:stone", 64),
                Map.of("minecraft:stone", 64),
                Map.of(),
                Map.of("minecraft:stone", 10),
                List.of());
        assertEquals(54, plan.lines().get("minecraft:stone").available());
        assertEquals(10, plan.totalRemaining());
    }

    @Test
    void ignoredOrAlreadyCorrectPositionsAreNotCounted() {
        MaterialPlanner.MaterialPlan plan = MaterialPlanner.plan(
                Map.of(),
                Map.of("minecraft:stone", 128),
                Map.of(),
                Map.of(),
                List.of());
        assertEquals(0, plan.totalRequired());
        assertTrue(plan.complete());
    }

    @Test
    void missingLinesAreReportedInPriorityOrder() {
        MaterialPlanner.MaterialPlan plan = MaterialPlanner.plan(
                Map.of("minecraft:dirt", 10, "minecraft:stone", 10, "minecraft:glass", 10),
                Map.of("minecraft:stone", 4),
                Map.of(),
                Map.of(),
                List.of("minecraft:glass", "minecraft:dirt"));
        assertEquals(List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone"),
                plan.lines().keySet().stream().toList());
        assertEquals(26, plan.totalRemaining());
        assertEquals(3, plan.missingLines().size());
    }

    @Test
    void satisfiesExactlyAtBoundary() {
        MaterialPlanner.MaterialPlan plan = MaterialPlanner.plan(
                Map.of("minecraft:stone", 64),
                Map.of("minecraft:stone", 32),
                Map.of("minecraft:stone", 32),
                Map.of(),
                List.of());
        assertTrue(plan.complete());
        assertEquals(0, plan.lines().get("minecraft:stone").remaining());
    }
}
