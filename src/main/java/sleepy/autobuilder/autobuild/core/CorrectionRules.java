package sleepy.autobuilder.autobuild.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves mismatch actions and property rules.
 *
 * <p>Priority, highest first: safety/protection, explicit block safety lists, session, schematic,
 * layer, block, category, global. Within a state comparison, a property marked CORRECT (including
 * the legacy REQUIRED spelling) forces correction even when the scope or global state action is
 * IGNORE. A property marked REPORT records the mismatch without changing the world.</p>
 */
public final class CorrectionRules {
    private final MismatchAction defaultTypeAction;
    private final MismatchAction defaultStateAction;
    private final MismatchAction defaultExtraAction;
    private final boolean extraRemovalEnabled;
    private final boolean protectContainers;

    private final Set<String> protectedBlocks;
    private final Set<Long> protectedPositions;
    private final Set<String> ignoredTypeBlocks;
    private final Set<String> alwaysReplaceBlocks;
    private final Set<String> ignoredStateBlocks;
    private final Set<String> alwaysCorrectStateBlocks;
    private final Set<String> ignoredExtraBlocks;

    private final ScopeRules sessionRules;
    private final Map<String, ScopeRules> schematicRules;
    private final Map<Integer, ScopeRules> layerRules;
    private final Map<String, ScopeRules> blockRules;
    private final Map<BlockCategory, ScopeRules> categoryRules;

    private final Set<String> globalIgnoredProperties;
    private final Set<String> globalRequiredProperties;
    private final Map<String, Map<String, PropertyMode>> blockPropertyRules;

    private CorrectionRules(Builder b) {
        this.defaultTypeAction = normaliseType(b.defaultTypeAction == null ? MismatchAction.REPLACE : b.defaultTypeAction);
        this.defaultStateAction = normaliseState(b.defaultStateAction == null ? MismatchAction.IGNORE : b.defaultStateAction);
        this.defaultExtraAction = normaliseExtra(b.defaultExtraAction == null ? MismatchAction.IGNORE : b.defaultExtraAction);
        this.extraRemovalEnabled = b.extraRemovalEnabled;
        this.protectContainers = b.protectContainers;
        this.protectedBlocks = Set.copyOf(b.protectedBlocks);
        this.protectedPositions = Set.copyOf(b.protectedPositions);
        this.ignoredTypeBlocks = Set.copyOf(b.ignoredTypeBlocks);
        this.alwaysReplaceBlocks = Set.copyOf(b.alwaysReplaceBlocks);
        this.ignoredStateBlocks = Set.copyOf(b.ignoredStateBlocks);
        this.alwaysCorrectStateBlocks = Set.copyOf(b.alwaysCorrectStateBlocks);
        this.ignoredExtraBlocks = Set.copyOf(b.ignoredExtraBlocks);
        this.sessionRules = b.sessionRules == null ? ScopeRules.empty() : b.sessionRules;
        this.schematicRules = Map.copyOf(b.schematicRules);
        this.layerRules = Map.copyOf(b.layerRules);
        this.blockRules = Map.copyOf(b.blockRules);
        this.categoryRules = Map.copyOf(b.categoryRules);
        this.globalIgnoredProperties = Set.copyOf(b.globalIgnoredProperties);
        this.globalRequiredProperties = Set.copyOf(b.globalRequiredProperties);

        Map<String, Map<String, PropertyMode>> properties = new LinkedHashMap<>();
        b.blockPropertyRules.forEach((block, rules) -> properties.put(block, Map.copyOf(rules)));
        this.blockPropertyRules = Collections.unmodifiableMap(properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public MismatchDecision classify(StateSignature expected, StateSignature actual, RuleContext ctx) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(ctx, "ctx");

        if (expected.equals(actual)) return MismatchDecision.none();

        MismatchKind kind;
        if (expected.isAir() && actual.isAir()) return MismatchDecision.none();
        if (expected.isAir()) kind = MismatchKind.EXTRA;
        else if (actual.isAir()) kind = MismatchKind.MISSING;
        else if (!expected.block().equals(actual.block())) kind = MismatchKind.TYPE_MISMATCH;
        else kind = MismatchKind.STATE_MISMATCH;

        String protection = protectionReason(actual, ctx);
        if (protection != null) {
            return decision(kind, MismatchAction.IGNORE, Set.of(), Set.of(), protection);
        }

        if (kind == MismatchKind.EXTRA) {
            if (ignoredExtraBlocks.contains(actual.block().id())) {
                return decision(kind, MismatchAction.IGNORE, Set.of(), Set.of(), "extra block is in ignore list");
            }
            if (!extraRemovalEnabled) {
                return decision(kind, MismatchAction.IGNORE, Set.of(), Set.of(), "extra block removal disabled");
            }
            return decision(kind, resolveExtraAction(ctx), Set.of(), Set.of(), "extra block");
        }

        if (kind == MismatchKind.TYPE_MISMATCH || kind == MismatchKind.MISSING) {
            if (ignoredTypeBlocks.contains(actual.block().id())) {
                return decision(kind, MismatchAction.IGNORE, Set.of(), Set.of(), "actual block is in ignored-type list");
            }
            if (alwaysReplaceBlocks.contains(actual.block().id())) {
                return decision(kind, MismatchAction.REPLACE, Set.of(), Set.of(), "actual block is in always-replace list");
            }
            return decision(kind, resolveTypeAction(ctx), Set.of(), Set.of(), "wrong block type");
        }

        // Same block id, different properties.
        Set<String> differing = expected.differingProperties(actual);
        if (differing.isEmpty()) return MismatchDecision.none();

        Set<String> ignored = effectiveIgnoredProperties(ctx);
        Set<String> required = effectiveRequiredProperties(ctx);
        Set<String> reported = effectiveReportedProperties(ctx);
        Set<String> relevant = new LinkedHashSet<>();
        for (String property : differing) {
            if (required.contains(property)) relevant.add(property);
            else if (!ignored.contains(property)) relevant.add(property);
        }

        if (relevant.isEmpty()) {
            return new MismatchDecision(MismatchKind.STATE_MISMATCH, MismatchAction.IGNORE,
                    differing, ignored, "all differing properties are ignored");
        }

        if (ignoredStateBlocks.contains(expected.block().id())) {
            return new MismatchDecision(MismatchKind.STATE_MISMATCH, MismatchAction.IGNORE,
                    differing, ignored, "block is in ignored-state list");
        }
        if (alwaysCorrectStateBlocks.contains(expected.block().id())) {
            return new MismatchDecision(MismatchKind.STATE_MISMATCH, MismatchAction.CORRECT,
                    differing, ignored, "block is in always-correct-state list");
        }

        MismatchAction action = resolveStateAction(ctx);
        String reason = action == MismatchAction.CORRECT
                ? "state mismatch correction enabled"
                : action == MismatchAction.REPORT ? "state mismatch reporting enabled" : "state mismatch";

        // A property explicitly marked CORRECT must correct even when the scope/global state action
        // is IGNORE. This is what makes the visual per-property REQUIRED editor meaningful.
        boolean requiredDiffers = relevant.stream().anyMatch(required::contains);
        boolean reportedDiffers = relevant.stream().anyMatch(reported::contains);
        if (requiredDiffers) {
            action = MismatchAction.CORRECT;
            reason = "required state property differs";
        } else if (action == MismatchAction.IGNORE && reportedDiffers) {
            action = MismatchAction.REPORT;
            reason = "state property is configured to report";
        }

        return new MismatchDecision(MismatchKind.STATE_MISMATCH, action, differing, ignored, reason);
    }

    private MismatchDecision decision(MismatchKind kind, MismatchAction rawAction,
                                      Set<String> differing, Set<String> ignored, String reason) {
        MismatchAction action = kind == MismatchKind.EXTRA
                ? normaliseExtra(rawAction)
                : kind == MismatchKind.STATE_MISMATCH ? normaliseState(rawAction) : normaliseType(rawAction);
        return new MismatchDecision(kind, action, differing, ignored, reason);
    }

    private String protectionReason(StateSignature actual, RuleContext ctx) {
        if (protectedPositions.contains(ctx.packedPosition())) return "position is protected";
        if (protectedBlocks.contains(actual.block().id())) return "block type is protected";
        if (protectContainers) {
            BlockCategory actualCategory = BlockCategory.from(actual.block());
            if (actualCategory == BlockCategory.CONTAINER || actualCategory == BlockCategory.SHULKER) {
                return "container protection is enabled";
            }
        }
        return null;
    }

    private MismatchAction resolveTypeAction(RuleContext ctx) {
        MismatchAction action = actionFromScopes(ctx, ScopeKind.TYPE);
        return normaliseType(action == null ? defaultTypeAction : action);
    }

    private MismatchAction resolveStateAction(RuleContext ctx) {
        MismatchAction action = actionFromScopes(ctx, ScopeKind.STATE);
        return normaliseState(action == null ? defaultStateAction : action);
    }

    private MismatchAction resolveExtraAction(RuleContext ctx) {
        MismatchAction action = actionFromScopes(ctx, ScopeKind.EXTRA);
        return normaliseExtra(action == null ? defaultExtraAction : action);
    }

    private MismatchAction actionFromScopes(RuleContext ctx, ScopeKind kind) {
        MismatchAction action = action(sessionRules, kind);
        if (action != null) return action;

        action = action(schematicRules.get(ctx.schematicName()), kind);
        if (action != null) return action;

        action = action(layerRules.get(ctx.layer()), kind);
        if (action != null) return action;

        action = action(blockRules.get(ctx.block().id()), kind);
        if (action != null) return action;

        action = action(categoryRules.get(ctx.category()), kind);
        return action;
    }

    private static MismatchAction action(ScopeRules rules, ScopeKind kind) {
        if (rules == null) return null;
        return switch (kind) {
            case TYPE -> rules.typeAction();
            case STATE -> rules.stateAction();
            case EXTRA -> rules.extraAction();
        };
    }

    private Set<String> effectiveIgnoredProperties(RuleContext ctx) {
        Set<String> ignored = new LinkedHashSet<>(globalIgnoredProperties);
        Set<String> required = new LinkedHashSet<>(globalRequiredProperties);
        mergeScopeProperties(categoryRules.get(ctx.category()), ignored, required);
        mergeScopeProperties(blockRules.get(ctx.block().id()), ignored, required);
        Map<String, PropertyMode> exact = blockPropertyRules.get(ctx.block().id());
        if (exact != null) {
            exact.forEach((name, mode) -> {
                switch (mode) {
                    case IGNORE -> {
                        ignored.add(name);
                        required.remove(name);
                    }
                    case CORRECT -> {
                        ignored.remove(name);
                        required.add(name);
                    }
                    case REPORT -> ignored.remove(name);
                }
            });
        }
        mergeScopeProperties(layerRules.get(ctx.layer()), ignored, required);
        mergeScopeProperties(schematicRules.get(ctx.schematicName()), ignored, required);
        mergeScopeProperties(sessionRules, ignored, required);
        ignored.removeAll(required);
        return ignored;
    }

    private Set<String> effectiveRequiredProperties(RuleContext ctx) {
        Set<String> required = new LinkedHashSet<>(globalRequiredProperties);
        mergeScopeProperties(categoryRules.get(ctx.category()), new LinkedHashSet<>(), required);
        mergeScopeProperties(blockRules.get(ctx.block().id()), new LinkedHashSet<>(), required);
        Map<String, PropertyMode> exact = blockPropertyRules.get(ctx.block().id());
        if (exact != null) {
            exact.forEach((name, mode) -> {
                if (mode == PropertyMode.CORRECT) required.add(name);
                else if (mode == PropertyMode.IGNORE) required.remove(name);
            });
        }
        mergeScopeProperties(layerRules.get(ctx.layer()), new LinkedHashSet<>(), required);
        mergeScopeProperties(schematicRules.get(ctx.schematicName()), new LinkedHashSet<>(), required);
        mergeScopeProperties(sessionRules, new LinkedHashSet<>(), required);
        return required;
    }

    private Set<String> effectiveReportedProperties(RuleContext ctx) {
        Set<String> reported = new LinkedHashSet<>();
        Map<String, PropertyMode> exact = blockPropertyRules.get(ctx.block().id());
        if (exact != null) {
            exact.forEach((name, mode) -> {
                if (mode == PropertyMode.REPORT) reported.add(name);
            });
        }
        return reported;
    }

    private static void mergeScopeProperties(ScopeRules rules, Set<String> ignored, Set<String> required) {
        if (rules == null) return;
        ignored.addAll(rules.ignoredProperties());
        required.addAll(rules.requiredProperties());
    }

    private static MismatchAction normaliseType(MismatchAction action) {
        if (action == MismatchAction.REPLACE || action == MismatchAction.IGNORE || action == MismatchAction.REPORT) return action;
        return MismatchAction.REPLACE;
    }

    private static MismatchAction normaliseState(MismatchAction action) {
        if (action == MismatchAction.CORRECT || action == MismatchAction.IGNORE || action == MismatchAction.REPORT) return action;
        return MismatchAction.CORRECT;
    }

    private static MismatchAction normaliseExtra(MismatchAction action) {
        if (action == MismatchAction.REMOVE || action == MismatchAction.IGNORE || action == MismatchAction.REPORT) return action;
        return MismatchAction.REMOVE;
    }

    private enum ScopeKind {TYPE, STATE, EXTRA}

    public static final class Builder {
        private MismatchAction defaultTypeAction = MismatchAction.REPLACE;
        private MismatchAction defaultStateAction = MismatchAction.IGNORE;
        private MismatchAction defaultExtraAction = MismatchAction.IGNORE;
        private boolean extraRemovalEnabled = false;
        private boolean protectContainers = true;

        private final Set<String> protectedBlocks = new LinkedHashSet<>();
        private final Set<Long> protectedPositions = new LinkedHashSet<>();
        private final Set<String> ignoredTypeBlocks = new LinkedHashSet<>();
        private final Set<String> alwaysReplaceBlocks = new LinkedHashSet<>();
        private final Set<String> ignoredStateBlocks = new LinkedHashSet<>();
        private final Set<String> alwaysCorrectStateBlocks = new LinkedHashSet<>();
        private final Set<String> ignoredExtraBlocks = new LinkedHashSet<>();

        private ScopeRules sessionRules = ScopeRules.empty();
        private final Map<String, ScopeRules> schematicRules = new LinkedHashMap<>();
        private final Map<Integer, ScopeRules> layerRules = new LinkedHashMap<>();
        private final Map<String, ScopeRules> blockRules = new LinkedHashMap<>();
        private final Map<BlockCategory, ScopeRules> categoryRules = new LinkedHashMap<>();

        private final Set<String> globalIgnoredProperties = new LinkedHashSet<>();
        private final Set<String> globalRequiredProperties = new LinkedHashSet<>();
        private final Map<String, Map<String, PropertyMode>> blockPropertyRules = new LinkedHashMap<>();

        public Builder defaultTypeAction(MismatchAction action) {
            this.defaultTypeAction = action;
            return this;
        }

        public Builder defaultStateAction(MismatchAction action) {
            this.defaultStateAction = action;
            return this;
        }

        public Builder defaultExtraAction(MismatchAction action) {
            this.defaultExtraAction = action;
            return this;
        }

        public Builder extraRemovalEnabled(boolean value) {
            this.extraRemovalEnabled = value;
            return this;
        }

        public Builder protectContainers(boolean value) {
            this.protectContainers = value;
            return this;
        }

        public Builder protectedBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) protectedBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder protectedPositions(Set<Long> positions) {
            if (positions != null) protectedPositions.addAll(positions);
            return this;
        }

        public Builder ignoredTypeBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) ignoredTypeBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder alwaysReplaceBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) alwaysReplaceBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder ignoredStateBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) ignoredStateBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder alwaysCorrectStateBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) alwaysCorrectStateBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder ignoredExtraBlocks(String... blockIds) {
            for (String id : blockIds) if (id != null && !id.isBlank()) ignoredExtraBlocks.add(BlockKey.normalise(id));
            return this;
        }

        public Builder sessionRules(ScopeRules rules) {
            this.sessionRules = rules == null ? ScopeRules.empty() : rules;
            return this;
        }

        public Builder schematicRules(String schematic, ScopeRules rules) {
            if (schematic != null && rules != null) schematicRules.put(schematic, rules);
            return this;
        }

        public Builder layerRules(int layer, ScopeRules rules) {
            if (rules != null) layerRules.put(layer, rules);
            return this;
        }

        public Builder blockRules(String blockId, ScopeRules rules) {
            if (blockId != null && rules != null) blockRules.put(BlockKey.normalise(blockId), rules);
            return this;
        }

        public Builder categoryRules(BlockCategory category, ScopeRules rules) {
            if (category != null && rules != null) categoryRules.put(category, rules);
            return this;
        }

        public Builder globalIgnoredProperties(String... names) {
            for (String name : names) if (name != null && !name.isBlank()) globalIgnoredProperties.add(name.trim());
            return this;
        }

        public Builder globalRequiredProperties(String... names) {
            for (String name : names) if (name != null && !name.isBlank()) globalRequiredProperties.add(name.trim());
            return this;
        }

        public Builder blockPropertyRule(String blockId, String property, PropertyMode mode) {
            if (blockId == null || property == null || property.isBlank() || mode == null) return this;
            blockPropertyRules
                    .computeIfAbsent(BlockKey.normalise(blockId), key -> new LinkedHashMap<>())
                    .put(property.trim(), mode);
            return this;
        }

        public CorrectionRules build() {
            return new CorrectionRules(this);
        }
    }
}
