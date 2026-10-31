package sleepy.autobuilder.autobuild.core;

public enum MismatchAction {
    IGNORE,
    REPORT,
    REPLACE,
    CORRECT,
    REMOVE;

    public boolean terminal() {
        return this == IGNORE || this == REPORT;
    }

    public boolean changesWorld() {
        return this == REPLACE || this == CORRECT || this == REMOVE;
    }
}
