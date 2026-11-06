package adris.altoclef.ui.gui.kit;

// the title screen dock's sums: how big a tile is, where every tile goes and how much one grows under the mouse.
// a straight port of gui-mock/title/title.js (dockRow and friends), rounding included, so a shot of the mock is a
// shot of the game. design px in, device px out, nothing here knows about minecraft
public final class DockLayout {

    public static final int TILE = 64;
    public static final int TILE_MIN = 40;
    public static final int PAD = 12;
    public static final int GAP = 14;
    // either side of the 1 px divider line, on top of the gap
    public static final int DIVIDER_MARGIN = 4;
    public static final int BOTTOM = 72;
    // how far above the pill the mouse still counts as "at the dock"
    public static final int BAND = 40;
    // how far from a tile's centre the mouse still makes it grow
    public static final int REACH = 150;
    public static final float MAGNIFY = 0.35f;
    public static final float FIT = 0.9f;
    public static final int SPRING_MS = 180;

    private DockLayout() {
    }

    // the tile size in design px. a dock that would be wider than 90% of the window shrinks its tiles until it fits,
    // down to 40 and no further (a window that small gets a dock that sticks out, it'll live)
    public static int tileDesign(int n, boolean divider, int screenW, float scale) {
        int fixed = 2 * PAD + Math.max(0, n - 1) * GAP + (divider ? 1 + 2 * DIVIDER_MARGIN + GAP : 0);
        int t = TILE;
        if ((fixed + n * t) * scale > FIT * screenW && n > 0) {
            t = Math.max(TILE_MIN, (int) Math.floor((FIT * screenW / scale - fixed) / n));
        }
        return t;
    }

    // what one tile wants to be scaled to with the mouse at mouseX: 1 + 0.35 at its centre, falling off linearly
    public static float magnify(float mouseX, float centreX, float scale) {
        return 1f + MAGNIFY * Math.max(0f, 1f - Math.abs(mouseX - centreX) / (REACH * scale));
    }

    public record Row(int x0, int width, int[] xs, int dividerX) {
    }

    // one row of tiles centred on the window. divBefore is the tile the divider sits in front of (quit), -1 for none:
    // gap, 4, the 1 px line, 4, gap. dx is the parallax shift
    public static Row row(int[] sizes, int pad, int gap, int margin, int divBefore, int screenW, int dx) {
        int n = sizes.length;
        boolean div = divBefore > 0 && divBefore < n;
        int total = 2 * pad + Math.max(0, n - 1) * gap + (div ? 2 * margin + 1 + gap : 0);
        for (int s : sizes) {
            total += s;
        }
        int x0 = Math.round(screenW / 2f - total / 2f) + dx;
        int[] xs = new int[n];
        int x = x0 + pad;
        int divX = 0;
        for (int i = 0; i < n; i++) {
            if (div && i == divBefore) {
                x += gap + margin;
                divX = x;
                x += 1 + margin + gap;
            }
            xs[i] = x;
            boolean nextIsDivider = div && i + 1 == divBefore;
            x += sizes[i] + (i < n - 1 && !nextIsDivider ? gap : 0);
        }
        return new Row(x0, total, xs, divX);
    }
}
