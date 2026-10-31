package sleepy.autobuilder.autobuild.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Overrides for one rule scope (session, schematic, layer, block or category).
 * A null action means "no opinion, fall through to the next scope".
 */
public record ScopeRules(
        MismatchAction typeAction,
        MismatchAction stateAction,
        MismatchAction extraAction,
        Set<String> ignoredProperties,
        Set<String> requiredProperties
) {
    public ScopeRules {
        ignoredProperties = Collections.unmodifiableSet(new LinkedHashSet<>(ignoredProperties == null ? Set.of() : ignoredProperties));
        requiredProperties = Collections.unmodifiableSet(new LinkedHashSet<>(requiredProperties == null ? Set.of() : requiredProperties));
    }

    public static ScopeRules empty() {
        return new ScopeRules(null, null, null, Set.of(), Set.of());
    }

    public boolean hasAction() {
        return typeAction != null || stateAction != null || extraAction != null;
    }

    public boolean hasPropertyRules() {
        return !ignoredProperties.isEmpty() || !requiredProperties.isEmpty();
    }

    public boolean isEmpty() {
        return !hasAction() && !hasPropertyRules();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private MismatchAction typeAction;
        private MismatchAction stateAction;
        private MismatchAction extraAction;
        private final Set<String> ignoredProperties = new LinkedHashSet<>();
        private final Set<String> requiredProperties = new LinkedHashSet<>();

        public Builder typeAction(MismatchAction action) {
            this.typeAction = action;
            return this;
        }

        public Builder stateAction(MismatchAction action) {
            this.stateAction = action;
            return this;
        }

        public Builder extraAction(MismatchAction action) {
            this.extraAction = action;
            return this;
        }

        public Builder ignoreProperties(String... names) {
            for (String name : names) if (name != null && !name.isBlank()) ignoredProperties.add(name.trim());
            return this;
        }

        public Builder requireProperties(String... names) {
            for (String name : names) if (name != null && !name.isBlank()) requiredProperties.add(name.trim());
            return this;
        }

        public ScopeRules build() {
            return new ScopeRules(typeAction, stateAction, extraAction, ignoredProperties, requiredProperties);
        }
    }

    @Override
    public String toString() {
        return "ScopeRules{" + Objects.toString(typeAction) + "," + Objects.toString(stateAction) + ","
                + Objects.toString(extraAction) + ",ignore=" + ignoredProperties + ",require=" + requiredProperties + "}";
    }
}
