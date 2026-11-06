package adris.altoclef.ui.gui.kit;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// rasterises inter one glyph at a time into white-with-coverage pages, and lays strings out against them. render
// thread only, nothing in here is locked. the cache lookups are all array or record-hash based so drawing a cached
// string allocates nothing
public final class GlyphAtlas implements TextMeasurer {

    // bearing is the offset from the pen position on the baseline to the bitmap's top left, so y is usually negative
    public record Glyph(AtlasPage page, int sx, int sy, int sw, int sh, int bearingX, int bearingY, float advance) {
    }

    // one laid out string. cp -1 means "draws nothing" (a control char, the tail of a surrogate pair). a cp with a
    // null glyph is something the font cannot show and the canvas has to fake
    public static final class Shaped {
        static final Shaped EMPTY = new Shaped(new int[0], new Glyph[0], new float[0], 0f);

        private final int[] cps;
        private final Glyph[] glyphs;
        private final float[] xs;
        private final float advance;

        private Shaped(int[] cps, Glyph[] glyphs, float[] xs, float advance) {
            this.cps = cps;
            this.glyphs = glyphs;
            this.xs = xs;
            this.advance = advance;
        }

        public int count() {
            return cps.length;
        }

        public int codePoint(int i) {
            return cps[i];
        }

        public Glyph glyph(int i) {
            return glyphs[i];
        }

        // pen x of the glyph, fractional, relative to the start of the string
        public float x(int i) {
            return xs[i];
        }

        public float advance() {
            return advance;
        }
    }

    // awt wants these three to match what we shipped: gray coverage, no lcd fringes, so a tint can multiply cleanly
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final Glyph MISSING = new Glyph(null, 0, 0, 0, 0, 0, 0, 0f);
    private static final int[] EXTRA_WARM = {0x2026, 0x00B7, 0x2013, 0x2019, 0x201C, 0x201D, 0x00D7};
    private static final int MAX_SIZE = 511;
    private static final int MAX_ICON = 256;
    private static final int ICON_COUNT = Icon.values().length;
    // a screen of text is a few dozen strings per style, this is the "scrolled a long list" headroom
    private static final int SHAPE_CACHE = 384;
    // the glyph bitmap gets a margin while drawing so a fringe the bounds missed does not get sliced off
    private static final int MARGIN = 2;

    private final AwtFonts fonts;
    private final List<AtlasPage> pages = new ArrayList<>();
    private final List<AtlasPage> pagesView = Collections.unmodifiableList(pages);
    private final Face[][] faces = new Face[Weight.values().length][MAX_SIZE + 1];
    private final Map<TextStyle, StyleCache> styles = new HashMap<>();
    private final Glyph[][] icons = new Glyph[MAX_ICON + 1][];
    private final int[] placeOut = new int[2];
    private MissingWidth missingWidth = (cp, style) -> missingAdvance(style);

    public GlyphAtlas(AwtFonts fonts) {
        this.fonts = fonts;
    }

    public List<AtlasPage> pages() {
        return pagesView;
    }

    @Override
    public float measure(String s, TextStyle style) {
        return shape(s, style).advance;
    }

    @Override
    public float ascent(TextStyle style) {
        return face(style).ascent;
    }

    @Override
    public float descent(TextStyle style) {
        return face(style).descent;
    }

    // what a glyph the font lacks takes up unless a backend says otherwise. 0.6 em is a decent "w" and keeps the line
    // from collapsing
    public float missingAdvance(TextStyle style) {
        return 0.6f * style.sizePx();
    }

    // lets the backend that draws the stand-in glyphs say how wide they really are, so measure() and the ink agree.
    // shaped strings are cached with the old widths in them, hence the wipe
    public interface MissingWidth {
        float of(int codePoint, TextStyle style);
    }

    public void setMissingWidth(MissingWidth source) {
        this.missingWidth = source;
        styles.clear();
    }

    // null when the font cannot show it
    public Glyph glyph(TextStyle style, int codePoint) {
        Glyph g = face(style).glyph(codePoint);
        return g == MISSING ? null : g;
    }

    public Shaped shape(String s, TextStyle style) {
        if (s == null || s.isEmpty()) {
            return Shaped.EMPTY;
        }
        StyleCache sc = styles.get(style);
        if (sc == null) {
            sc = new StyleCache(face(style));
            styles.put(style, sc);
        }
        Shaped sh = sc.shaped.get(s);
        if (sh == null) {
            sh = layout(s, sc.face, style);
            sc.shaped.put(s, sh);
        }
        return sh;
    }

    // the icon rasterised once at this pixel size, then it is just another sprite
    public Glyph icon(Icon icon, int size) {
        int sz = Math.max(4, Math.min(MAX_ICON, size));
        Glyph[] row = icons[sz];
        if (row == null) {
            row = new Glyph[ICON_COUNT];
            icons[sz] = row;
        }
        Glyph g = row[icon.ordinal()];
        if (g == null) {
            BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            try {
                Icons.draw(icon, g2, sz);
            } finally {
                g2.dispose();
            }
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            whiten(px);
            AtlasPage page = AtlasPage.place(pages, sz, sz, placeOut);
            page.write(placeOut[0], placeOut[1], sz, sz, px);
            g = new Glyph(page, placeOut[0], placeOut[1], sz, sz, 0, 0, sz);
            row[icon.ordinal()] = g;
        }
        return g;
    }

    private Face face(TextStyle style) {
        int size = Math.max(1, Math.min(MAX_SIZE, style.sizePx()));
        Face[] bySize = faces[style.weight().ordinal()];
        Face f = bySize[size];
        if (f == null) {
            f = new Face(fonts.font(style.weight(), size));
            bySize[size] = f;
        }
        return f;
    }

    private Shaped layout(String s, Face face, TextStyle style) {
        int n = s.length();
        char[] chars = s.toCharArray();
        boolean simple = true;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (invisible(c)) {
                // these get a space for the layout and zero width afterwards
                chars[i] = ' ';
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(chars[i + 1])) {
                simple = false;
                i++;
            } else if (Character.isSurrogate(c)) {
                chars[i] = ' ';
            }
        }
        // the real layout engine is what applies kerning. only usable while glyphs and chars line up one to one
        GlyphVector gv = null;
        if (simple) {
            try {
                gv = face.font.layoutGlyphVector(FRC, chars, 0, n, Font.LAYOUT_LEFT_TO_RIGHT);
            } catch (RuntimeException e) {
                gv = null;
            }
            if (gv != null && gv.getNumGlyphs() != n) {
                gv = null;
            }
        }

        int[] cps = new int[n];
        Glyph[] glyphs = new Glyph[n];
        float[] xs = new float[n];
        float cell = style.tabular() ? face.digitCell() : 0f;
        float pen = 0f;
        float shift = 0f;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int cp;
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = s.codePointAt(i);
            } else if (invisible(c) || Character.isSurrogate(c)) {
                // the tail of a pair, a lone half, a control: nothing to draw and no room taken
                cp = -1;
            } else {
                cp = c;
            }
            Glyph g = cp >= 0 ? face.glyph(cp) : MISSING;

            float gx;
            float natural;
            if (gv != null) {
                gx = (float) gv.getGlyphPosition(i).getX();
                natural = (float) gv.getGlyphPosition(i + 1).getX() - gx;
            } else {
                gx = pen;
                natural = cp < 0 ? 0f : g == MISSING ? missingWidth.of(cp, style) : g.advance();
                pen += natural;
            }

            float extra = 0f;
            float off = 0f;
            if (cp < 0) {
                extra = -natural;
            } else if (g == MISSING) {
                extra = missingWidth.of(cp, style) - natural;
            } else if (cell > 0f && c >= '0' && c <= '9') {
                // every digit gets the widest digit's cell, glyph centred in it
                extra = cell - natural;
                off = extra * 0.5f;
            }
            cps[i] = cp;
            glyphs[i] = cp >= 0 && g != MISSING ? g : null;
            xs[i] = gx + shift + off;
            shift += extra;
        }
        float end = gv != null ? (float) gv.getGlyphPosition(n).getX() : pen;
        return new Shaped(cps, glyphs, xs, end + shift);
    }

    // controls, c1 controls, the soft hyphen and the zero width joiners and selectors. a stand-in for something nobody
    // can see would leave a hole in the line
    private static boolean invisible(char c) {
        return c < 32 || (c >= 127 && c <= 159) || c == 0xAD
                || (c >= 0x200B && c <= 0x200F) || c == 0x2060 || c == 0xFEFF || (c >= 0xFE00 && c <= 0xFE0F);
    }

    // coverage stays in alpha, colour goes to white, so tint * texel is the colour we want
    private static void whiten(int[] px) {
        for (int i = 0; i < px.length; i++) {
            px[i] |= 0x00FFFFFF;
        }
    }

    private static final class StyleCache {
        final Face face;
        final LinkedHashMap<String, Shaped> shaped = new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Shaped> eldest) {
                return size() > SHAPE_CACHE;
            }
        };

        StyleCache(Face face) {
            this.face = face;
        }
    }

    // one weight at one pixel size: the font, its metrics, and every glyph rasterised so far
    private final class Face {
        final Font font;
        final int ascent;
        final int descent;
        // bmp glyphs in 256 wide blocks so lookups are two array reads and an empty block costs one null
        private final Glyph[][] blocks = new Glyph[256][];
        private Map<Integer, Glyph> astral;
        private float digitCell = -1f;
        private boolean warmed;

        Face(Font font) {
            this.font = font;
            LineMetrics lm = font.getLineMetrics("", FRC);
            // whole pixels, or baselines wander by a pixel depending on which line you ask
            this.ascent = Math.max(1, Math.round(lm.getAscent()));
            this.descent = Math.max(1, Math.round(lm.getDescent()));
        }

        float digitCell() {
            if (digitCell < 0f) {
                float widest = 0f;
                for (int d = '0'; d <= '9'; d++) {
                    widest = Math.max(widest, glyph(d).advance());
                }
                digitCell = widest;
            }
            return digitCell;
        }

        Glyph glyph(int cp) {
            Glyph g = peek(cp);
            if (g != null) {
                return g;
            }
            if (!warmed) {
                warmed = true;
                for (int c = 32; c <= 126; c++) {
                    rasterise(c);
                }
                for (int c = 160; c <= 255; c++) {
                    rasterise(c);
                }
                for (int c : EXTRA_WARM) {
                    rasterise(c);
                }
                g = peek(cp);
                if (g != null) {
                    return g;
                }
            }
            return rasterise(cp);
        }

        private Glyph peek(int cp) {
            if (cp >= 0 && cp < 0x10000) {
                Glyph[] block = blocks[cp >> 8];
                return block == null ? null : block[cp & 255];
            }
            return astral == null ? null : astral.get(cp);
        }

        private void store(int cp, Glyph g) {
            if (cp >= 0 && cp < 0x10000) {
                Glyph[] block = blocks[cp >> 8];
                if (block == null) {
                    block = new Glyph[256];
                    blocks[cp >> 8] = block;
                }
                block[cp & 255] = g;
            } else if (cp >= 0) {
                if (astral == null) {
                    astral = new HashMap<>();
                }
                astral.put(cp, g);
            }
        }

        private Glyph rasterise(int cp) {
            Glyph done = peek(cp);
            if (done != null) {
                return done;
            }
            if (cp < 0 || cp > Character.MAX_CODE_POINT || !font.canDisplay(cp)) {
                store(cp, MISSING);
                return MISSING;
            }
            GlyphVector gv = font.createGlyphVector(FRC, new String(Character.toChars(cp)));
            float adv = (float) gv.getGlyphMetrics(0).getAdvanceX();
            Rectangle b = gv.getPixelBounds(FRC, 0f, 0f);
            Glyph g = new Glyph(null, 0, 0, 0, 0, 0, 0, adv);
            if (b.width > 0 && b.height > 0) {
                int w = b.width + MARGIN * 2;
                int h = b.height + MARGIN * 2;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = img.createGraphics();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    g2.setColor(Color.WHITE);
                    g2.drawGlyphVector(gv, MARGIN - b.x, MARGIN - b.y);
                } finally {
                    g2.dispose();
                }
                int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                int minX = w;
                int minY = h;
                int maxX = -1;
                int maxY = -1;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if ((px[y * w + x] >>> 24) != 0) {
                            minX = Math.min(minX, x);
                            maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }
                if (maxX >= minX) {
                    int tw = maxX - minX + 1;
                    int th = maxY - minY + 1;
                    int[] out = new int[tw * th];
                    for (int y = 0; y < th; y++) {
                        System.arraycopy(px, (minY + y) * w + minX, out, y * tw, tw);
                    }
                    whiten(out);
                    AtlasPage page = AtlasPage.place(pages, tw, th, placeOut);
                    page.write(placeOut[0], placeOut[1], tw, th, out);
                    g = new Glyph(page, placeOut[0], placeOut[1], tw, th, minX - MARGIN + b.x, minY - MARGIN + b.y, adv);
                }
            }
            store(cp, g);
            return g;
        }
    }
}
