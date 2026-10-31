package sleepy.autobuilder.autobuild.rules;

import org.junit.jupiter.api.Test;
import sleepy.autobuilder.autobuild.PlacementMethod;
import sleepy.autobuilder.autobuild.core.BlockCategory;
import sleepy.autobuilder.autobuild.core.MismatchAction;
import sleepy.autobuilder.autobuild.core.PropertyMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleParserTest {
    @Test
    void parsesScopeActionsAndProperties() {
        var rules = CorrectionRuleParser.parseScope(
                "type=REPLACE,state=CORRECT,extra=REMOVE,ignore=waterlogged|facing,require=facing|axis");
        assertEquals(MismatchAction.REPLACE, rules.typeAction());
        assertEquals(MismatchAction.CORRECT, rules.stateAction());
        assertEquals(MismatchAction.REMOVE, rules.extraAction());
        assertTrue(rules.ignoredProperties().contains("waterlogged"));
        assertFalse(rules.ignoredProperties().contains("facing"));
        assertTrue(rules.requiredProperties().contains("facing"));
        assertTrue(rules.requiredProperties().contains("axis"));
    }

    @Test
    void invalidScopeEntriesAreIgnored() {
        var rules = CorrectionRuleParser.parseScope("type=BANANA,state=,ignore=,,require");
        assertTrue(rules.isEmpty());
    }

    @Test
    void parsesScopedRulesByFirstPipe() {
        var parsed = CorrectionRuleParser.parseScopedRules(List.of(
                "minecraft:torch|state=IGNORE",
                "LIGHT|state=CORRECT,ignore=lit",
                "broken-entry",
                "|state=IGNORE"
        ));
        assertEquals(2, parsed.size());
        assertEquals("minecraft:torch", parsed.get(0).key());
        assertEquals(MismatchAction.IGNORE, parsed.get(0).rules().stateAction());
    }

    @Test
    void parsesBlockPropertyRules() {
        var rules = CorrectionRuleParser.parseBlockPropertyRules(List.of(
                "minecraft:stairs|facing=REQUIRED,waterlogged=IGNORE",
                "minecraft:door|half=broken"
        ));
        assertEquals(2, rules.size());
        assertEquals("minecraft:stairs", rules.get(0).blockId());
        assertEquals("facing", rules.get(0).property());
        assertEquals(sleepy.autobuilder.autobuild.core.PropertyMode.CORRECT, rules.get(0).mode());
        assertEquals(sleepy.autobuilder.autobuild.core.PropertyMode.IGNORE, rules.get(1).mode());
    }

    @Test
    void parsesModernPerPropertyReportMode() {
        var rules = CorrectionRuleParser.parseBlockPropertyRules(List.of(
                "minecraft:oak_stairs|facing=REPORT,waterlogged=CORRECT"
        ));
        assertEquals(2, rules.size());
        assertEquals(PropertyMode.REPORT, rules.get(0).mode());
        assertEquals(PropertyMode.CORRECT, rules.get(1).mode());
    }

    @Test
    void protectedPositionsRespectDimension() {
        var positions = CorrectionRuleParser.parseProtectedPositions(
                List.of("10,64,-20", "1,2,3,minecraft:the_nether", "bad", "4,5,6,minecraft:overworld"),
                "minecraft:overworld");
        assertEquals(2, positions.size());
    }

    @Test
    void parsesLayerAndCategoryKeys() {
        var layers = CorrectionRuleParser.parseLayerRules(List.of("61|state=CORRECT", "x|state=IGNORE"));
        assertEquals(1, layers.size());
        assertEquals(MismatchAction.CORRECT, layers.get(61).stateAction());

        var categories = CorrectionRuleParser.parseCategoryRules(List.of("light|state=IGNORE", "nope|state=IGNORE"));
        assertEquals(1, categories.size());
        assertEquals(MismatchAction.IGNORE, categories.get(BlockCategory.LIGHT).stateAction());
    }

    @Test
    void placementRulesPreferSpecificThenCategoryThenWildcard() {
        PlacementRules rules = PlacementRules.parse(List.of(
                "*=VANILLA",
                "#stairs=AUTO",
                "minecraft:oak_stairs=VANILLA"
        ));
        assertEquals(PlacementMethod.VANILLA, rules.resolve("minecraft:oak_stairs", BlockCategory.STAIRS, PlacementMethod.AUTO));
        assertEquals(PlacementMethod.AUTO, rules.resolve("minecraft:stone_stairs", BlockCategory.STAIRS, PlacementMethod.VANILLA));
        assertEquals(PlacementMethod.VANILLA, rules.resolve("minecraft:stone", BlockCategory.FULL_BLOCK, PlacementMethod.AUTO));
    }

    @Test
    void placementRulesFallBackToModuleDefault() {
        PlacementRules rules = PlacementRules.parse(List.of());
        assertEquals(PlacementMethod.AUTO, rules.resolve("minecraft:stone", BlockCategory.FULL_BLOCK, PlacementMethod.AUTO));
        assertTrue(rules.isEmpty());
    }

    @Test
    void placementRulesAcceptCategoryPrefix() {
        PlacementRules rules = PlacementRules.parse(List.of("category:redstone=AUTO"));
        assertEquals(PlacementMethod.AUTO, rules.resolve("minecraft:repeater", BlockCategory.REDSTONE, PlacementMethod.VANILLA));
    }
}
