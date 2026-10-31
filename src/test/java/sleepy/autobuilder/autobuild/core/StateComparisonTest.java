package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StateComparisonTest {
    private static RuleContext ctx(String block) {
        return new RuleContext(BlockKey.of(block), 64, PositionCodec.pack(1, 64, 1));
    }

    private static StateSignature state(String block, Map<String, String> props) {
        return StateSignature.of(block, props);
    }

    @Test
    void wrongTypeIsDistinguishedFromWrongState() {
        CorrectionRules rules = CorrectionRules.builder().build();

        MismatchDecision type = rules.classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:dirt", Map.of()),
                ctx("minecraft:stone"));
        assertEquals(MismatchKind.TYPE_MISMATCH, type.kind());
        assertEquals(MismatchAction.REPLACE, type.action());
        assertTrue(type.needsBreak());
        assertTrue(type.needsPlace());

        MismatchDecision wrongState = rules.classify(
                state("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom")),
                state("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom")),
                ctx("minecraft:oak_stairs"));
        assertEquals(MismatchKind.STATE_MISMATCH, wrongState.kind());
        assertEquals(MismatchAction.IGNORE, wrongState.action());
        assertEquals(Set.of("facing"), wrongState.differingProperties());
    }

    @Test
    void missingBlockUsesTypeRulesWithoutBreaking() {
        MismatchDecision decision = CorrectionRules.builder().build().classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:air", Map.of()),
                ctx("minecraft:stone"));
        assertEquals(MismatchKind.MISSING, decision.kind());
        assertEquals(MismatchAction.REPLACE, decision.action());
        assertFalse(decision.needsBreak());
        assertTrue(decision.needsPlace());
    }

    @Test
    void extraBlockRemovalIsOffByDefaultAndNeverOutsideRuleControl() {
        MismatchDecision disabled = CorrectionRules.builder().build().classify(
                state("minecraft:air", Map.of()),
                state("minecraft:dirt", Map.of()),
                ctx("minecraft:air"));
        assertEquals(MismatchKind.EXTRA, disabled.kind());
        assertEquals(MismatchAction.IGNORE, disabled.action());

        MismatchDecision enabled = CorrectionRules.builder()
                .extraRemovalEnabled(true)
                .defaultExtraAction(MismatchAction.REMOVE)
                .build()
                .classify(state("minecraft:air", Map.of()), state("minecraft:dirt", Map.of()), ctx("minecraft:air"));
        assertEquals(MismatchAction.REMOVE, enabled.action());
        assertTrue(enabled.needsBreak());
        assertFalse(enabled.needsPlace());
    }

    @Test
    void protectionAlwaysWins() {
        CorrectionRules rules = CorrectionRules.builder()
                .defaultTypeAction(MismatchAction.REPLACE)
                .alwaysReplaceBlocks("minecraft:dirt")
                .protectedBlocks("minecraft:dirt")
                .build();
        MismatchDecision decision = rules.classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:dirt", Map.of()),
                ctx("minecraft:stone"));
        assertEquals(MismatchAction.IGNORE, decision.action());
        assertTrue(decision.reason().contains("protected"));
    }

    @Test
    void containersAreProtectedByDefault() {
        MismatchDecision decision = CorrectionRules.builder().build().classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:chest", Map.of("facing", "north")),
                ctx("minecraft:stone"));
        assertEquals(MismatchAction.IGNORE, decision.action());

        MismatchDecision allowed = CorrectionRules.builder().protectContainers(false).build().classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:chest", Map.of("facing", "north")),
                ctx("minecraft:stone"));
        assertEquals(MismatchAction.REPLACE, allowed.action());
    }

    @Test
    void ignoredTypeListAndAlwaysReplaceListAreHonoured() {
        MismatchDecision ignored = CorrectionRules.builder()
                .ignoredTypeBlocks("minecraft:dirt")
                .build()
                .classify(state("minecraft:stone", Map.of()), state("minecraft:dirt", Map.of()), ctx("minecraft:stone"));
        assertEquals(MismatchAction.IGNORE, ignored.action());

        MismatchDecision replaced = CorrectionRules.builder()
                .defaultTypeAction(MismatchAction.IGNORE)
                .alwaysReplaceBlocks("minecraft:dirt")
                .build()
                .classify(state("minecraft:stone", Map.of()), state("minecraft:dirt", Map.of()), ctx("minecraft:stone"));
        assertEquals(MismatchAction.REPLACE, replaced.action());
    }

    @Test
    void ignoredPropertiesBecomeTerminalButRequiredPropertiesOverrideIgnore() {
        MismatchDecision ignored = CorrectionRules.builder()
                .defaultStateAction(MismatchAction.CORRECT)
                .globalIgnoredProperties("facing")
                .build()
                .classify(
                        state("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom")),
                        state("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom")),
                        ctx("minecraft:oak_stairs"));
        assertEquals(MismatchAction.IGNORE, ignored.action());
        assertTrue(ignored.isTerminal());
        assertEquals(Set.of("facing"), ignored.ignoredProperties());

        MismatchDecision required = CorrectionRules.builder()
                .defaultStateAction(MismatchAction.CORRECT)
                .globalIgnoredProperties("facing")
                .blockPropertyRule("minecraft:oak_stairs", "facing", PropertyMode.CORRECT)
                .build()
                .classify(
                        state("minecraft:oak_stairs", Map.of("facing", "north")),
                        state("minecraft:oak_stairs", Map.of("facing", "east")),
                        ctx("minecraft:oak_stairs"));
        assertEquals(MismatchAction.CORRECT, required.action());
        assertTrue(required.differingProperties().contains("facing"));
    }

    @Test
    void extraBlockIgnoreListPreventsRemoval() {
        CorrectionRules rules = CorrectionRules.builder()
                .extraRemovalEnabled(true)
                .defaultExtraAction(MismatchAction.REMOVE)
                .ignoredExtraBlocks("minecraft:torch")
                .build();

        MismatchDecision decision = rules.classify(
                state("minecraft:air", Map.of()),
                state("minecraft:torch", Map.of()),
                ctx("minecraft:air"));

        assertEquals(MismatchKind.EXTRA, decision.kind());
        assertEquals(MismatchAction.IGNORE, decision.action());
        assertFalse(decision.needsRemoval());
        assertTrue(decision.reason().contains("ignore"));
    }

    @Test
    void requiredPropertyForcesCorrectionWhenGlobalStateActionIsIgnore() {
        MismatchDecision required = CorrectionRules.builder()
                .defaultStateAction(MismatchAction.IGNORE)
                .blockPropertyRule("minecraft:oak_stairs", "facing", PropertyMode.CORRECT)
                .build()
                .classify(
                        state("minecraft:oak_stairs", Map.of("facing", "north")),
                        state("minecraft:oak_stairs", Map.of("facing", "east")),
                        ctx("minecraft:oak_stairs"));
        assertEquals(MismatchAction.CORRECT, required.action());
        assertTrue(required.needsBreak());
        assertTrue(required.needsPlace());
        assertTrue(required.reason().contains("required"));

        MismatchDecision reported = CorrectionRules.builder()
                .defaultStateAction(MismatchAction.IGNORE)
                .blockPropertyRule("minecraft:oak_stairs", "facing", PropertyMode.REPORT)
                .build()
                .classify(
                        state("minecraft:oak_stairs", Map.of("facing", "north")),
                        state("minecraft:oak_stairs", Map.of("facing", "east")),
                        ctx("minecraft:oak_stairs"));
        assertEquals(MismatchAction.REPORT, reported.action());
        assertFalse(reported.needsBreak());
        assertFalse(reported.needsPlace());
    }

    @Test
    void blockStateRuleCanEnableCorrection() {
        MismatchDecision decision = CorrectionRules.builder()
                .blockRules("minecraft:oak_stairs", ScopeRules.builder().stateAction(MismatchAction.CORRECT).build())
                .build()
                .classify(
                        state("minecraft:oak_stairs", Map.of("facing", "north")),
                        state("minecraft:oak_stairs", Map.of("facing", "east")),
                        ctx("minecraft:oak_stairs"));
        assertEquals(MismatchAction.CORRECT, decision.action());
        assertTrue(decision.needsBreak());
        assertTrue(decision.needsPlace());
    }

    @Test
    void sessionOutranksEveryLowerScope() {
        CorrectionRules rules = CorrectionRules.builder()
                .sessionRules(ScopeRules.builder().typeAction(MismatchAction.REPLACE).stateAction(MismatchAction.CORRECT).build())
                .schematicRules("test", ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .layerRules(64, ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .blockRules("minecraft:stone", ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .categoryRules(BlockCategory.FULL_BLOCK, ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .build();
        RuleContext context = new RuleContext(BlockKey.of("minecraft:stone"), BlockCategory.FULL_BLOCK,
                "test", 64, "session", PositionCodec.pack(0, 64, 0));
        assertEquals(MismatchAction.REPLACE,
                rules.classify(state("minecraft:stone", Map.of()), state("minecraft:dirt", Map.of()), context).action());
    }

    @Test
    void schematicOutranksBlockButBlockOutranksCategory() {
        CorrectionRules schematic = CorrectionRules.builder()
                .schematicRules("castle", ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .blockRules("minecraft:stone", ScopeRules.builder().stateAction(MismatchAction.CORRECT).build())
                .build();
        RuleContext context = new RuleContext(BlockKey.of("minecraft:stone"), BlockCategory.FULL_BLOCK,
                "castle", 64, "", PositionCodec.pack(0, 64, 0));
        assertEquals(MismatchAction.IGNORE,
                schematic.classify(state("minecraft:stone", Map.of()), state("minecraft:dirt", Map.of()), context).action());

        CorrectionRules block = CorrectionRules.builder()
                .blockRules("minecraft:stone", ScopeRules.builder().typeAction(MismatchAction.REPLACE).build())
                .categoryRules(BlockCategory.FULL_BLOCK, ScopeRules.builder().typeAction(MismatchAction.IGNORE).build())
                .build();
        assertEquals(MismatchAction.REPLACE,
                block.classify(state("minecraft:stone", Map.of()), state("minecraft:dirt", Map.of()), context).action());
    }

    @Test
    void identicalStatesProduceNone() {
        MismatchDecision decision = CorrectionRules.builder().build().classify(
                state("minecraft:stone", Map.of()),
                state("minecraft:stone", Map.of()),
                ctx("minecraft:stone"));
        assertEquals(MismatchKind.NONE, decision.kind());
        assertTrue(decision.isTerminal());
    }
}
