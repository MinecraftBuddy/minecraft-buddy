package adris.altoclef.ui.gui.kit;

// static faces keep awt and the game's atlas on the same font metrics. inter, because it stays open at 12px through
// the java2d rasteriser where roboto's counters start to clog
public enum Weight {
    REGULAR("Inter-Regular.ttf"),
    MEDIUM("Inter-Medium.ttf"),
    SEMIBOLD("Inter-SemiBold.ttf");

    public final String file;

    Weight(String file) {
        this.file = file;
    }

    public static final String RESOURCE_DIR = "/adris/altoclef/ui/gui/font/";
}
