package adris.altoclef.ui.gui.kit;

// a picture a widget draws by hand, argb row major, that the canvas puts on screen as a texture. the game backend keeps
// one gpu texture per instance and only re-uploads when version() moved, so write into pixels() and then bump() once,
// never bump per frame. the size is fixed for life: a different size is a new image (same idea as AtlasPage)
public final class PixelImage {

    private final int width;
    private final int height;
    private final int[] argb;
    private int version = 1;
    private boolean smooth;

    public PixelImage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a " + width + " x " + height + " image");
        }
        this.width = width;
        this.height = height;
        this.argb = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    // the pixels themselves, z rows of x. 0 is see-through
    public int[] pixels() {
        return argb;
    }

    public int version() {
        return version;
    }

    // say the pixels changed. the next draw uploads them
    public void bump() {
        version++;
    }

    // sample it linearly instead of nearest when it gets stretched: the baked gradients and the title's brand art,
    // which get drawn a bit bigger or smaller than they were baked. drawn at its own size it's the same pixels either
    // way. set it before the first draw, the game picks the sampler when it uploads
    public PixelImage smooth() {
        smooth = true;
        return this;
    }

    public boolean isSmooth() {
        return smooth;
    }
}
