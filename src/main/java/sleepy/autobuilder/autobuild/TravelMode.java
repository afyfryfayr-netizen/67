package sleepy.autobuilder.autobuild;

public enum TravelMode {
    AUTO("Auto (elytra for long trips, walk for short)"),
    WALK("Walk / Baritone path"),
    ELYTRA("Elytra only");

    private final String title;

    TravelMode(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }
}
