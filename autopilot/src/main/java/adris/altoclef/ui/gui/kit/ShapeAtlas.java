package adris.altoclef.ui.gui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the little masks every rounded thing is built from: filled corners, 1px ring corners, and soft shadow tiles. all
// white with coverage in alpha, made with java2d once per integer parameter set and never again
public final class ShapeAtlas {

    public record Slice(AtlasPage page, int sx, int sy, int sw, int sh) {
    }

    // order of the four corner slices from disc() and ring()
    public static final int TL = 0;
    public static final int TR = 1;
    public static final int BL = 2;
    public static final int BR = 3;

    // shadow() hands back a 3x3 grid, row major: TL T TR / L C R / BL B BR. edge and centre slices are one texel
    // thick in the stretched direction
    public static final int SH_TL = 0;
    public static final int SH_T = 1;
    public static final int SH_TR = 2;
    public static final int SH_L = 3;
    public static final int SH_C = 4;
    public static final int SH_R = 5;
    public static final int SH_BL = 6;
    public static final int SH_B = 7;
    public static final int SH_BR = 8;

    // radii up to here are array lookups, past it they go through a map (nobody should, but a big circle might)
    private static final int FAST_R = 128;

    private final List<AtlasPage> pages = new ArrayList<>();
    private final List<AtlasPage> pagesView = Collections.unmodifiableList(pages);
    private final Slice[][] discs = new Slice[FAST_R + 1][];
    private final Slice[][] rings = new Slice[FAST_R + 1][];
    private final Map<Long, Slice[]> rare = new HashMap<>();
    private final int[] placeOut = new int[2];

    private final Slice[][] shadows = new Slice[FAST_R + 1][];
    private final int[] shadowBlurs = new int[FAST_R + 1];

    public ShapeAtlas() {
    }

    public List<AtlasPage> pages() {
        return pagesView;
    }

    // quarter discs of radius r, each r x r, filled where the corner of a rounded rect is
    public Slice[] disc(int r) {
        r = Math.max(1, r);
        Slice[] s = r <= FAST_R ? discs[r] : rare.get(key(0, r, 0));
        if (s == null) {
            BufferedImage img = new BufferedImage(2 * r, 2 * r, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = begin(img);
            g.fill(new Ellipse2D.Double(0, 0, 2 * r, 2 * r));
            g.dispose();
            s = quarters(img, r);
            if (r <= FAST_R) {
                discs[r] = s;
            } else {
                rare.put(key(0, r, 0), s);
            }
        }
        return s;
    }

    // the same four corners but only the one pixel ring that sits inside the disc's edge
    public Slice[] ring(int r) {
        r = Math.max(1, r);
        Slice[] s = r <= FAST_R ? rings[r] : rare.get(key(1, r, 0));
        if (s == null) {
            BufferedImage img = new BufferedImage(2 * r, 2 * r, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = begin(img);
            g.setStroke(new BasicStroke(1f));
            // half a pixel in so the stroke's outer edge is exactly the disc's edge
            g.draw(new Ellipse2D.Double(0.5, 0.5, 2 * r - 1, 2 * r - 1));
            g.dispose();
            s = quarters(img, r);
            if (r <= FAST_R) {
                rings[r] = s;
            } else {
                rare.put(key(1, r, 0), s);
            }
        }
        return s;
    }

    // how far the three box passes actually spread the edge. that is the margin the tile needs and the margin the
    // canvas has to draw, and it can be a hair over the blur that was asked for (three passes of 1 is 3, not 2)
    public static int shadowReach(int blur) {
        return blur <= 0 ? 0 : 3 * Math.max(1, Math.round(blur / 3f));
    }

    // corner size of the 9 slice, so the canvas knows how big the thing it is stretching is
    public static int shadowCorner(int r, int blur) {
        return r + 2 * shadowReach(blur);
    }

    public Slice[] shadow(int r, int blur) {
        r = Math.max(0, r);
        blur = Math.max(0, blur);
        // the canvas derives blur from r, so one slot per radius covers it and nothing boxes every frame
        if (r <= FAST_R && shadows[r] != null && shadowBlurs[r] == blur) {
            return shadows[r];
        }
        Long k = key(2, r, blur);
        Slice[] s = rare.get(k);
        if (s == null) {
            s = buildShadow(r, blur);
            rare.put(k, s);
        }
        if (r <= FAST_R && shadows[r] == null) {
            shadows[r] = s;
            shadowBlurs[r] = blur;
        }
        return s;
    }

    private Slice[] buildShadow(int r, int blur) {
        // box side is picked so the middle column is further than the blur reaches from both edges, which is what
        // makes it safe to stretch one texel of it across any real box
        int reach = shadowReach(blur);
        int cs = shadowCorner(r, blur);
        int side = 2 * cs + 1;
        int boxSide = side - 2 * reach;
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = begin(img);
        g.fill(new RoundRectangle2D.Double(reach, reach, boxSide, boxSide, 2 * r, 2 * r));
        g.dispose();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

        if (blur > 0) {
            float[] a = new float[side * side];
            for (int i = 0; i < a.length; i++) {
                a[i] = (px[i] >>> 24) / 255f;
            }
            // three box passes each way is close enough to a gaussian that nobody has ever pointed at one and said
            // "that is a box blur"
            int k = reach / 3;
            float[] tmp = new float[side];
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < side; y++) {
                    boxLine(a, y * side, 1, side, k, tmp);
                }
                for (int x = 0; x < side; x++) {
                    boxLine(a, x, side, side, k, tmp);
                }
            }
            for (int i = 0; i < a.length; i++) {
                px[i] = (Math.round(Math.max(0f, Math.min(1f, a[i])) * 255f) << 24) | 0x00FFFFFF;
            }
        } else {
            whiten(px);
        }

        return grid(px, side, cs);
    }

    private Slice[] grid(int[] px, int side, int cs) {
        AtlasPage page = AtlasPage.place(pages, side, side, placeOut);
        page.write(placeOut[0], placeOut[1], side, side, px);
        int ox = placeOut[0];
        int oy = placeOut[1];
        int far = cs + 1;
        Slice[] s = new Slice[9];
        s[SH_TL] = new Slice(page, ox, oy, cs, cs);
        s[SH_T] = new Slice(page, ox + cs, oy, 1, cs);
        s[SH_TR] = new Slice(page, ox + far, oy, cs, cs);
        s[SH_L] = new Slice(page, ox, oy + cs, cs, 1);
        s[SH_C] = new Slice(page, ox + cs, oy + cs, 1, 1);
        s[SH_R] = new Slice(page, ox + far, oy + cs, cs, 1);
        s[SH_BL] = new Slice(page, ox, oy + far, cs, cs);
        s[SH_B] = new Slice(page, ox + cs, oy + far, 1, cs);
        s[SH_BR] = new Slice(page, ox + far, oy + far, cs, cs);
        return s;
    }

    // moving average of radius k over one row or column, zero outside the tile
    private static void boxLine(float[] a, int start, int stride, int n, int k, float[] tmp) {
        float inv = 1f / (2 * k + 1);
        float sum = 0f;
        for (int i = 0; i <= k && i < n; i++) {
            sum += a[start + i * stride];
        }
        for (int i = 0; i < n; i++) {
            tmp[i] = sum * inv;
            int add = i + k + 1;
            int drop = i - k;
            if (add < n) {
                sum += a[start + add * stride];
            }
            if (drop >= 0) {
                sum -= a[start + drop * stride];
            }
        }
        for (int i = 0; i < n; i++) {
            a[start + i * stride] = tmp[i];
        }
    }

    private Slice[] quarters(BufferedImage img, int r) {
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        whiten(px);
        AtlasPage page = AtlasPage.place(pages, 2 * r, 2 * r, placeOut);
        page.write(placeOut[0], placeOut[1], 2 * r, 2 * r, px);
        int ox = placeOut[0];
        int oy = placeOut[1];
        Slice[] s = new Slice[4];
        s[TL] = new Slice(page, ox, oy, r, r);
        s[TR] = new Slice(page, ox + r, oy, r, r);
        s[BL] = new Slice(page, ox, oy + r, r, r);
        s[BR] = new Slice(page, ox + r, oy + r, r, r);
        return s;
    }

    private static Graphics2D begin(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        return g;
    }

    private static void whiten(int[] px) {
        for (int i = 0; i < px.length; i++) {
            px[i] |= 0x00FFFFFF;
        }
    }

    private static Long key(int kind, int r, int blur) {
        return ((long) kind << 40) | ((long) r << 20) | blur;
    }
}
