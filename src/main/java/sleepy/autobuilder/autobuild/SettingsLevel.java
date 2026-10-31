package sleepy.autobuilder.autobuild;

public enum SettingsLevel {
    BASIC("Basic"),
    ADVANCED("Advanced"),
    EXPERT("Expert");

    private final String title;

    SettingsLevel(String title) {
        this.title = title;
    }

    public boolean atLeast(SettingsLevel required) {
        return ordinal() >= required.ordinal();
    }

    @Override
    public String toString() {
        return title;
    }
}
