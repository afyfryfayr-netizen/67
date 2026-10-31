package sleepy.autobuilder.autobuild;

public enum PlacementMethod {
    AUTO("Auto (airplace)"),
    VANILLA("Vanilla interaction");

    private final String title;

    PlacementMethod(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }
}
