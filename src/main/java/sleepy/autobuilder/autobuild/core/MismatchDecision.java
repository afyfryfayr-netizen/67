package sleepy.autobuilder.autobuild.core;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Result of comparing an expected and an actual block state.
 *
 * <p>{@code differingProperties} contains every state property that differs. For a type mismatch it is
 * usually empty because the whole block type is wrong. {@code ignoredProperties} is the subset that the
 * active rules explicitly ignore.</p>
 */
public record MismatchDecision(
        MismatchKind kind,
        MismatchAction action,
        Set<String> differingProperties,
        Set<String> ignoredProperties,
        String reason
) {
    public MismatchDecision {
        differingProperties = Collections.unmodifiableSet(new TreeSet<>(differingProperties == null ? Set.of() : differingProperties));
        ignoredProperties = Collections.unmodifiableSet(new TreeSet<>(ignoredProperties == null ? Set.of() : ignoredProperties));
        reason = reason == null ? "" : reason;
    }

    public static MismatchDecision none() {
        return new MismatchDecision(MismatchKind.NONE, MismatchAction.IGNORE, Set.of(), Set.of(), "correct");
    }

    public boolean needsBreak() {
        if (kind == MismatchKind.MISSING) return false;
        return action == MismatchAction.REPLACE || action == MismatchAction.CORRECT || action == MismatchAction.REMOVE;
    }

    public boolean needsPlace() {
        return action == MismatchAction.REPLACE || action == MismatchAction.CORRECT;
    }

    public boolean needsRemoval() {
        return action == MismatchAction.REMOVE;
    }

    public boolean isTerminal() {
        return kind == MismatchKind.NONE || action.terminal();
    }

    public boolean isIgnored() {
        return action == MismatchAction.IGNORE && kind != MismatchKind.NONE;
    }
}
