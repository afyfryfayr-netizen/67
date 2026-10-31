package sleepy.autobuilder.autobuild.core;

/**
 * Per-property mismatch handling.
 *
 * <p>{@link #IGNORE} removes the property from comparison. {@link #CORRECT} forces correction for
 * the property (even when the global state action is IGNORE). {@link #REPORT} records the mismatch
 * without changing the world.</p>
 */
public enum PropertyMode {
    IGNORE,
    REPORT,
    CORRECT;

    public boolean corrects() {
        return this == CORRECT;
    }
}
