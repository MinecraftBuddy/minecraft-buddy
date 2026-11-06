package adris.altoclef.ui.gui.kit;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// one square of white-with-coverage pixels. glyphs, corner masks and icons all live in these, the game backend
// uploads each page as a texture and re-uploads whenever version() moves. shelf packed, never evicted
public final class AtlasPage {

    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final int START_SIDE = 512;
    // a transparent gap between entries so a stretched or offset sample never grabs the neighbour
    private static final int GAP = 1;

    private final int id = NEXT_ID.getAndIncrement();
    private final int width;
    private final int height;
    private final BufferedImage image;
    private final int[] pixels;
    private int version;

    private int cursorX;
    private int shelfY;
    private int shelfH;

    private AtlasPage(int side) {
        this.width = side;
        this.height = side;
        this.image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    public int id() {
        return id;
    }

    public BufferedImage image() {
        return image;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int version() {
        return version;
    }

    // finds room on one of the newest pages or opens a new one. out gets the x, y of the reserved rect
    static AtlasPage place(List<AtlasPage> pages, int w, int h, int[] out) {
        // older pages are full, shelves do not go back
        for (int i = pages.size() - 1; i >= 0 && i >= pages.size() - 2; i--) {
            AtlasPage p = pages.get(i);
            if (p.fit(w, h, out)) {
                return p;
            }
        }
        int side = START_SIDE;
        while (side < Math.max(w, h) + GAP) {
            side <<= 1;
        }
        AtlasPage p = new AtlasPage(side);
        pages.add(p);
        p.fit(w, h, out);
        return p;
    }

    private boolean fit(int w, int h, int[] out) {
        if (w > width || h > height) {
            return false;
        }
        int x = cursorX;
        int y = shelfY;
        int rowH = shelfH;
        if (x + w > width) {
            y += rowH + GAP;
            x = 0;
            rowH = 0;
        }
        if (y + h > height) {
            return false;
        }
        out[0] = x;
        out[1] = y;
        cursorX = x + w + GAP;
        shelfY = y;
        shelfH = Math.max(rowH, h);
        return true;
    }

    // argb is w * h row major, copied in at x, y
    void write(int x, int y, int w, int h, int[] argb) {
        for (int row = 0; row < h; row++) {
            System.arraycopy(argb, row * w, pixels, (y + row) * width + x, w);
        }
        version++;
    }
}
