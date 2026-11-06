package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Footprint;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Terrain;
import adris.altoclef.ui.gui.facade.WorldAccess.Position;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.ArrayList;
import java.util.List;

// the band in the schematic pane that shows where the build goes: the terrain around it like a paper map (north up,
// vanilla colours), the footprint as a blue drafting rectangle you can drag, the player as the map's arrow. the map is
// one texture per terrain snapshot, re-uploaded only when the snapshot's stamp moves; everything else is a few fills
final class PlacementMap extends Widget {

    enum Mode {
        // a file that didn't parse: the pane leaves the band out entirely
        HIDDEN,
        NO_WORLD,
        // placed in another dimension than the player is in: no terrain, a line saying where it is
        ELSEWHERE,
        MAP
    }

    interface Listener {
        // the footprint was dragged this many blocks, once per drag on release
        void dragged(int dx, int dz);
    }

    // design px per block at each zoom step, the wheel walks them. the device size is a whole number of pixels so a
    // block stays a crisp square. until the wheel is touched the step is DEFAULT_SCALE, or the first step below it
    // that fits the whole footprint on the band (short windows get a short band, and a footprint that spills off it
    // hides its front bar and origin dot)
    static final int[] SCALES = {1, 2, 4};
    static final int DEFAULT_SCALE = 1;
    // what the auto fit keeps clear around the footprint, so the front bar and the dot are on the band too
    private static final int FIT_MARGIN = 10;
    // a band shorter than this has no room for the coordinates chip without it sitting on the footprint
    private static final int CHIP_MIN_H = 64;
    // a press this close to the footprint's middle grabs it even when the footprint itself is three pixels wide
    private static final int GRAB = 12;
    // what an unseen chunk looks like: a dot every four blocks on the field, a drafting grid with nothing on it
    private static final int DOT = 0x6E888888;

    private final Listener listener;
    private PixelImage image;
    private long uploaded = Long.MIN_VALUE;
    private int uploadedMinX;
    private int uploadedMinZ;
    private Terrain terrain;
    private Footprint fp;
    private Placement pl;
    private boolean hasPlayer;
    private double playerX;
    private double playerZ;
    private float yaw;
    private Mode mode = Mode.HIDDEN;
    private String elsewhere = "";
    private int scaleIx = DEFAULT_SCALE;
    // the wheel was used: the zoom is theirs now, not the auto fit's
    private boolean zoomed;
    private boolean fpHover;
    private boolean dragging;
    private float dragX0;
    private float dragY0;
    private int dragDx;
    private int dragDz;

    PlacementMap(Theme theme, Listener listener) {
        super(theme);
        this.listener = listener;
    }

    // the tab calls this every tick with the latest snapshots. placement and footprint go together, terrain may lag
    // behind (null while the first chunks come in), the player is null without a world
    void show(Mode m, Placement placement, Footprint footprint, Terrain t, Position player, float playerYaw) {
        mode = m;
        pl = placement;
        fp = footprint;
        terrain = t;
        hasPlayer = player != null;
        if (hasPlayer) {
            playerX = player.x();
            playerZ = player.z();
        }
        yaw = playerYaw;
        elsewhere = placement == null ? "" : SchematicRules.dimensionName(placement.dimension());
        if (mode != Mode.MAP) {
            cancelDrag();
        }
        upload();
    }

    // a different file or a vanished one: whatever drag was going on is over, nothing is sent
    void cancelDrag() {
        dragging = false;
        dragDx = 0;
        dragDz = 0;
    }

    // copies the snapshot into the texture, unknown columns drawn as the dot grid. only when the stamp moved: the tab
    // calls show() twenty times a second and the snapshot is the same object nearly every time
    private void upload() {
        if (terrain == null) {
            return;
        }
        int size = terrain.size();
        if (image == null || image.width() != size) {
            // a new size is a new picture and the old one's gpu texture lives on in McAtlases until the screen
            // closes. the radius is a constant so this happens once per screen, but mind it if that ever changes
            image = new PixelImage(size, size);
            uploaded = Long.MIN_VALUE;
        }
        // the stamp covers a moved window too, but checking the corner costs nothing and a stale map is confusing
        if (terrain.stamp() == uploaded && terrain.minX() == uploadedMinX && terrain.minZ() == uploadedMinZ) {
            return;
        }
        int[] px = image.pixels();
        terrain.copyPixels(px);
        int minX = terrain.minX();
        int minZ = terrain.minZ();
        for (int i = 0; i < px.length; i++) {
            if (px[i] == 0) {
                int wx = minX + i % size;
                int wz = minZ + i / size;
                px[i] = (wx & 3) == 0 && (wz & 3) == 0 ? DOT : 0;
            }
        }
        image.bump();
        uploaded = terrain.stamp();
        uploadedMinX = minX;
        uploadedMinZ = minZ;
    }

    // ----- where things are

    int pxPerBlock() {
        return pxAt(scaleIndex());
    }

    private int pxAt(int ix) {
        return Math.max(1, Math.round(theme.pxf(SCALES[ix])));
    }

    // the zoom step in use: the wheel's once it has been used, else the default stepped down until the footprint
    // fits the band with a margin (never below the first step)
    int scaleIndex() {
        if (zoomed || fp == null) {
            return scaleIx;
        }
        int ix = DEFAULT_SCALE;
        float margin = 2 * theme.px(FIT_MARGIN);
        while (ix > 0 && (fp.sizeX() * pxAt(ix) > w - margin || fp.sizeZ() * pxAt(ix) > h - margin)) {
            ix--;
        }
        return ix;
    }

    // the world point in the middle of the band: the footprint's middle, the placement corner when the sizes have
    // not come through yet, the player when there is no placement at all. the drag in progress is left out on
    // purpose: the rectangle follows the pointer over a map that holds still, and the band recentres on it once the
    // drop lands (with the drag in here the terrain slid instead, backwards)
    private double centreX() {
        if (fp != null) {
            return fp.x() + fp.sizeX() / 2.0;
        }
        if (pl != null) {
            return pl.x() + 0.5;
        }
        return hasPlayer ? playerX : 0;
    }

    private double centreZ() {
        if (fp != null) {
            return fp.z() + fp.sizeZ() / 2.0;
        }
        if (pl != null) {
            return pl.z() + 0.5;
        }
        return hasPlayer ? playerZ : 0;
    }

    private float sx(double wx) {
        return x + w / 2f + (float) ((wx - centreX()) * pxPerBlock());
    }

    private float sz(double wz) {
        return y + h / 2f + (float) ((wz - centreZ()) * pxPerBlock());
    }

    private boolean grabs(float mx, float my) {
        if (mode != Mode.MAP || fp == null) {
            return false;
        }
        int s = pxPerBlock();
        float x0 = sx(fp.x() + dragDx);
        float z0 = sz(fp.z() + dragDz);
        float x1 = x0 + fp.sizeX() * s;
        float z1 = z0 + fp.sizeZ() * s;
        float cx = (x0 + x1) / 2f;
        float cz = (z0 + z1) / 2f;
        float g = theme.px(GRAB);
        return mx >= Math.min(x0, cx - g) && mx < Math.max(x1, cx + g) && my >= Math.min(z0, cz - g) && my < Math.max(z1, cz + g);
    }

    // ----- input

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget d = super.mouseMove(mx, my);
        fpHover = hovered && grabs(mx, my);
        return d;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || mode != Mode.MAP) {
            return false;
        }
        if (button == 0 && grabs(mx, my)) {
            dragging = true;
            dragX0 = mx;
            dragY0 = my;
            dragDx = 0;
            dragDz = 0;
        }
        return true;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (!dragging) {
            return false;
        }
        int s = pxPerBlock();
        dragDx = Math.round((mx - dragX0) / s);
        dragDz = Math.round((my - dragY0) / s);
        return true;
    }

    // one move for the whole drag, so a long drag is one save and not fifty
    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (!dragging) {
            return false;
        }
        int ddx = dragDx;
        int ddz = dragDz;
        cancelDrag();
        if (ddx != 0 || ddz != 0) {
            listener.dragged(ddx, ddz);
        }
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my) || mode != Mode.MAP) {
            return false;
        }
        scaleIx = Math.max(0, Math.min(SCALES.length - 1, scaleIndex() + (dy > 0 ? 1 : -1)));
        zoomed = true;
        return true;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        if (w <= 0 || h <= 0) {
            return;
        }
        c.pushClip(x, y, w, h);
        try {
            c.fillRect(x, y, w, h, theme.color(Theme.FIELD));
            switch (mode) {
                case NO_WORLD -> centred(c, "Join a world to place it", "");
                case ELSEWHERE -> centred(c, "Placed in " + elsewhere, "Move to me puts it where you stand.");
                case MAP -> drawMap(c);
                default -> {
                }
            }
        } finally {
            c.popClip();
        }
    }

    private void drawMap(Canvas c) {
        int s = pxPerBlock();
        if (terrain != null && image != null) {
            c.image(image, sx(terrain.minX()), sz(terrain.minZ()), terrain.size() * s, terrain.size() * s, 0xFFFFFFFF);
        }
        if (fp != null && pl != null) {
            drawFootprint(c, s);
        }
        if (hasPlayer) {
            drawPlayer(c);
        }
        drawChips(c);
    }

    // a translucent blue box with a hairline edge. the thick edge is the front: the file's north after the turn. the
    // dot is the file's origin corner, which the flip sends to the other end of that edge, so turn and flip both read
    private void drawFootprint(Canvas c, int s) {
        float x0 = sx(fp.x() + dragDx);
        float z0 = sz(fp.z() + dragDz);
        float fw = fp.sizeX() * s;
        float fh = fp.sizeZ() * s;
        int accent = theme.color(Theme.ACCENT);
        c.fillRect(x0, z0, fw, fh, Theme.alpha(accent, fpHover || dragging ? 0.32f : 0.22f));
        float line = theme.px(1);
        c.fillRect(x0, z0, fw, line, accent);
        c.fillRect(x0, z0 + fh - line, fw, line, accent);
        c.fillRect(x0, z0, line, fh, accent);
        c.fillRect(x0 + fw - line, z0, line, fh, accent);
        float bar = theme.px(3);
        switch (pl.turns() & 3) {
            case 0 -> c.fillRect(x0, z0, fw, bar, accent);
            case 1 -> c.fillRect(x0 + fw - bar, z0, bar, fh, accent);
            case 2 -> c.fillRect(x0, z0 + fh - bar, fw, bar, accent);
            default -> c.fillRect(x0, z0, bar, fh, accent);
        }
        int corner = originCorner(pl.turns(), pl.mirrored());
        float d = theme.px(5);
        float ox = corner == 0 || corner == 3 ? x0 : x0 + fw;
        float oz = corner == 0 || corner == 1 ? z0 : z0 + fh;
        c.fillRect(ox - d / 2f, oz - d / 2f, d, d, accent);
    }

    // which corner of the turned footprint the file's 0,0 landed in: 0 top left, 1 top right, 2 bottom right, 3 bottom
    // left. the flip (east to west, before the turn) moves it from the top left to the top right, every clockwise
    // quarter turn walks it one corner on
    static int originCorner(int turns, boolean mirrored) {
        return (turns + (mirrored ? 1 : 0)) & 3;
    }

    // the vanilla map's arrow: a light triangle on a dark one a shade bigger, turned to where the player looks. off
    // the band it sits on the edge, still turned, so you know which way you are from the build
    private void drawPlayer(Canvas c) {
        // enough that the whole arrow stays on the band when it is pinned to an edge
        float inset = theme.px(10);
        float px = Math.max(x + inset, Math.min(x + w - inset, sx(playerX)));
        float pz = Math.max(y + inset, Math.min(y + h - inset, sz(playerZ)));
        // minecraft yaw: 0 looks south (+z), 90 west (-x). on a north-up map that is (-sin, cos) in screen space
        double rad = Math.toRadians(yaw);
        float dx = (float) -Math.sin(rad);
        float dz = (float) Math.cos(rad);
        arrow(c, px, pz, dx, dz, theme.pxf(8), theme.pxf(4.5f), theme.pxf(5.5f), 0x8C000000);
        arrow(c, px, pz, dx, dz, theme.pxf(6), theme.pxf(3), theme.pxf(4), theme.color(Theme.TEXT));
    }

    private static void arrow(Canvas c, float px, float pz, float dx, float dz, float tip, float back, float half, int argb) {
        float tx = px + dx * tip;
        float tz = pz + dz * tip;
        float bx = px - dx * back;
        float bz = pz - dz * back;
        triangle(c, tx, tz, bx - dz * half, bz + dx * half, bx + dz * half, bz - dx * half, argb);
    }

    // the canvas has no polygons, so a triangle is one pixel row of fill at a time
    static void triangle(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        float[] xs = {x1, x2, x3};
        float[] ys = {y1, y2, y3};
        int top = (int) Math.floor(Math.min(y1, Math.min(y2, y3)));
        int bottom = (int) Math.ceil(Math.max(y1, Math.max(y2, y3)));
        for (int row = top; row < bottom; row++) {
            float yc = row + 0.5f;
            float left = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                float ya = ys[i];
                float yb = ys[(i + 1) % 3];
                if (ya == yb || yc < Math.min(ya, yb) || yc >= Math.max(ya, yb)) {
                    continue;
                }
                float xi = xs[i] + (yc - ya) / (yb - ya) * (xs[(i + 1) % 3] - xs[i]);
                left = Math.min(left, xi);
                right = Math.max(right, xi);
            }
            if (left < right) {
                c.fillRect(left, row, right - left, 1, argb);
            }
        }
    }

    // the coordinates top left, north top right, on surface at 85% so the terrain shows through a little. a short
    // band (the 540p case) keeps only the N: the coordinates would sit on the footprint there
    private void drawChips(Canvas c) {
        float pad = theme.px(8);
        float ch = theme.px(20);
        float inner = theme.px(6);
        TextStyle label = theme.smallLabel;
        float nw = c.measure("N", label) + 2 * inner;
        chip(c, x + w - pad - nw, y + pad, nw, ch, "N", label, inner);
        String coords = coords();
        if (coords.isEmpty() || h < theme.px(CHIP_MIN_H)) {
            return;
        }
        TextStyle small = theme.small.asTabular();
        float room = w - 3 * pad - nw - 2 * theme.px(8);
        if (room < theme.px(40)) {
            return;
        }
        String shown = c.ellipsize(coords, small, room);
        chip(c, x + pad, y + pad, c.measure(shown, small) + 2 * theme.px(8), ch, shown, small, theme.px(8));
    }

    private void chip(Canvas c, float cx, float cy, float cw, float ch, String text, TextStyle style, float inner) {
        c.fillRoundRect(cx, cy, cw, ch, theme.pxf(5), Theme.alpha(theme.color(Theme.SURFACE), 0.85f));
        float base = cy + ch / 2f + (c.ascent(style) - c.descent(style)) / 2f;
        c.text(text, cx + inner, base, style, theme.color(Theme.TEXT_2));
    }

    private void centred(Canvas c, String lead, String rest) {
        float room = Math.max(theme.px(80), w - 2 * theme.px(32));
        List<String> lines = new ArrayList<>(HelpList.wrap(c, lead, theme.small, room));
        int leadLines = lines.size();
        if (!rest.isEmpty()) {
            lines.addAll(HelpList.wrap(c, rest, theme.small, room));
        }
        float lh = c.lineHeight(theme.small) + theme.px(2);
        float top = y + (h - lines.size() * lh) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            float tw = c.measure(s, theme.small);
            c.text(s, x + (w - tw) / 2f, top + i * lh + c.ascent(theme.small), theme.small,
                    theme.color(i < leadLines ? Theme.TEXT_2 : Theme.TEXT_3));
        }
    }

    // ----- what the tests and the pane read

    // "x 100 y 64 z -40 · 90° · mirrored", with the drag in progress added in, "" without a placement
    String coords() {
        return pl == null ? "" : SchematicRules.coords(pl, dragDx, dragDz);
    }

    Mode mode() {
        return mode;
    }

    // the dimension id the placement sits in, "" without one
    String elsewhere() {
        return pl == null ? "" : pl.dimension();
    }

    // whether a coordinates chip is drawn on this band (short bands keep only the N)
    boolean showsCoordsChip() {
        return h >= theme.px(CHIP_MIN_H);
    }

    boolean dragging() {
        return dragging;
    }

    PixelImage image() {
        return image;
    }

    float footprintCentreX() {
        return fp == null ? x + w / 2f : sx(fp.x() + dragDx + fp.sizeX() / 2.0);
    }

    float footprintCentreY() {
        return fp == null ? y + h / 2f : sz(fp.z() + dragDz + fp.sizeZ() / 2.0);
    }
}
