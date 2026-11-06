package adris.altoclef.ui.gui.kit;

// rounded rects, hairlines, shadows, text and icons, all built from two things a backend can do in its sleep: fill a
// flat rect and copy a tinted sub rect of an atlas page. the game and the preview share every pixel decision in here,
// so a preview png is what the game draws, minus the item models
public abstract class TexturedCanvas implements Canvas {

    protected final GlyphAtlas glyphs;
    protected final ShapeAtlas shapes;
    private Theme theme;

    @Override
    public void setTheme(Theme theme) {
        this.theme = theme;
    }

    @Override
    public int color(int token) {
        return theme == null ? token : theme.color(token);
    }

    protected TexturedCanvas(GlyphAtlas glyphs, ShapeAtlas shapes) {
        this.glyphs = glyphs;
        this.shapes = shapes;
        // the atlas lays strings out, so it has to hear about the width of whatever we draw for missing glyphs. this
        // only stores a reference, it is not called until a subclass is fully built. last canvas wins and every new
        // one wipes the shape cache, so make one per atlas, before any layout, and keep it
        glyphs.setMissingWidth(this::fallbackAdvance);
    }

    // a copy of page[sx, sy, sw, sh] into the box at dx, dy, dw, dh. rgb comes from the tint, alpha is texel alpha
    // times tint alpha. dw == sw and dh == sh nearly always, the shadow stretches and that is about it
    protected abstract void blitTinted(AtlasPage page, int sx, int sy, int sw, int sh,
                                       float dx, float dy, float dw, float dh, int argb);

    // for code points the font cannot show. default draws nothing, which beats a tofu box
    protected void drawFallbackGlyph(int codePoint, float x, float baseline, TextStyle style, int argb) {
    }

    // how much room a fallback glyph takes. measure() uses it too, so keep it a pure function of its arguments
    protected float fallbackAdvance(int codePoint, TextStyle style) {
        return glyphs.missingAdvance(style);
    }

    @Override
    public float measure(String s, TextStyle style) {
        return glyphs.measure(s, style);
    }

    @Override
    public float ascent(TextStyle style) {
        return glyphs.ascent(style);
    }

    @Override
    public float descent(TextStyle style) {
        return glyphs.descent(style);
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        // snap the edges, not the size, so neighbours that share an edge keep sharing it
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        int iw = x1 - x0;
        int ih = y1 - y0;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        int r = Math.min(Math.round(radius), Math.min(iw, ih) / 2);
        if (r <= 0) {
            fillRect(x0, y0, iw, ih, argb);
            return;
        }
        ShapeAtlas.Slice[] c = shapes.disc(r);
        corner(c[ShapeAtlas.TL], x0, y0, argb);
        corner(c[ShapeAtlas.TR], x1 - r, y0, argb);
        corner(c[ShapeAtlas.BL], x0, y1 - r, argb);
        corner(c[ShapeAtlas.BR], x1 - r, y1 - r, argb);
        // three rects tile the rest without touching, so a translucent fill does not double up anywhere
        if (iw > 2 * r) {
            fillRect(x0 + r, y0, iw - 2 * r, r, argb);
            fillRect(x0 + r, y1 - r, iw - 2 * r, r, argb);
        }
        if (ih > 2 * r) {
            fillRect(x0, y0 + r, iw, ih - 2 * r, argb);
        }
    }

    @Override
    public void strokeRoundRect(float x, float y, float w, float h, float radius, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        int iw = x1 - x0;
        int ih = y1 - y0;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        if (iw < 3 || ih < 3) {
            fillRect(x0, y0, iw, ih, argb);
            return;
        }
        int r = Math.min(Math.round(radius), Math.min(iw, ih) / 2);
        if (r <= 0) {
            fillRect(x0, y0, iw, 1, argb);
            fillRect(x0, y1 - 1, iw, 1, argb);
            fillRect(x0, y0 + 1, 1, ih - 2, argb);
            fillRect(x1 - 1, y0 + 1, 1, ih - 2, argb);
            return;
        }
        ShapeAtlas.Slice[] c = shapes.ring(r);
        corner(c[ShapeAtlas.TL], x0, y0, argb);
        corner(c[ShapeAtlas.TR], x1 - r, y0, argb);
        corner(c[ShapeAtlas.BL], x0, y1 - r, argb);
        corner(c[ShapeAtlas.BR], x1 - r, y1 - r, argb);
        if (iw > 2 * r) {
            fillRect(x0 + r, y0, iw - 2 * r, 1, argb);
            fillRect(x0 + r, y1 - 1, iw - 2 * r, 1, argb);
        }
        if (ih > 2 * r) {
            fillRect(x0, y0 + r, 1, ih - 2 * r, argb);
            fillRect(x1 - 1, y0 + r, 1, ih - 2 * r, argb);
        }
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int iw = Math.round(x + w) - x0;
        int ih = Math.round(y + h) - y0;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(Math.round(radius), Math.min(iw, ih) / 2));
        int blur = Math.max(2, Math.round(r * 1.5f));
        ShapeAtlas.Slice[] s = shapes.shadow(r, blur);
        int reach = ShapeAtlas.shadowReach(blur);
        int bx = x0 - reach;
        int by = y0 - reach + r / 2;
        int bw = iw + 2 * reach;
        int bh = ih + 2 * reach;
        nineSlice(s, bx, by, bw, bh, argb);
    }

    private void nineSlice(ShapeAtlas.Slice[] s, int bx, int by, int bw, int bh, int argb) {
        int cs = s[ShapeAtlas.SH_TL].sw();
        // a box too small for two whole corners shares what it has between them, so the shadow never double darkens
        int lw = Math.min(cs, bw / 2);
        int rw = Math.min(cs, bw - lw);
        int th = Math.min(cs, bh / 2);
        int bt = Math.min(cs, bh - th);
        int midW = bw - lw - rw;
        int midH = bh - th - bt;

        ShapeAtlas.Slice tl = s[ShapeAtlas.SH_TL];
        ShapeAtlas.Slice tr = s[ShapeAtlas.SH_TR];
        ShapeAtlas.Slice bl = s[ShapeAtlas.SH_BL];
        ShapeAtlas.Slice br = s[ShapeAtlas.SH_BR];
        AtlasPage page = tl.page();
        blitTinted(page, tl.sx(), tl.sy(), lw, th, bx, by, lw, th, argb);
        blitTinted(page, tr.sx() + cs - rw, tr.sy(), rw, th, bx + bw - rw, by, rw, th, argb);
        blitTinted(page, bl.sx(), bl.sy() + cs - bt, lw, bt, bx, by + bh - bt, lw, bt, argb);
        blitTinted(page, br.sx() + cs - rw, br.sy() + cs - bt, rw, bt, bx + bw - rw, by + bh - bt, rw, bt, argb);
        if (midW > 0) {
            ShapeAtlas.Slice t = s[ShapeAtlas.SH_T];
            ShapeAtlas.Slice b = s[ShapeAtlas.SH_B];
            blitTinted(page, t.sx(), t.sy(), 1, th, bx + lw, by, midW, th, argb);
            blitTinted(page, b.sx(), b.sy() + cs - bt, 1, bt, bx + lw, by + bh - bt, midW, bt, argb);
        }
        if (midH > 0) {
            ShapeAtlas.Slice l = s[ShapeAtlas.SH_L];
            ShapeAtlas.Slice rr = s[ShapeAtlas.SH_R];
            blitTinted(page, l.sx(), l.sy(), lw, 1, bx, by + th, lw, midH, argb);
            blitTinted(page, rr.sx() + cs - rw, rr.sy(), rw, 1, bx + bw - rw, by + th, rw, midH, argb);
        }
        if (midW > 0 && midH > 0) {
            ShapeAtlas.Slice c = s[ShapeAtlas.SH_C];
            blitTinted(page, c.sx(), c.sy(), 1, 1, bx + lw, by + th, midW, midH, argb);
        }
    }

    @Override
    public float text(String s, float x, float baseline, TextStyle style, int argb) {
        GlyphAtlas.Shaped sh = glyphs.shape(s, style);
        int n = sh.count();
        if (n == 0) {
            return 0f;
        }
        if ((argb >>> 24) == 0) {
            return sh.advance();
        }
        int by = Math.round(baseline);
        for (int i = 0; i < n; i++) {
            int cp = sh.codePoint(i);
            if (cp < 0) {
                continue;
            }
            GlyphAtlas.Glyph g = sh.glyph(i);
            int gx = Math.round(x + sh.x(i));
            if (g == null) {
                // same snapped baseline as the atlas glyphs, or a mixed line steps up and down half a pixel
                drawFallbackGlyph(cp, gx, by, style, argb);
            } else if (g.sw() > 0) {
                blitTinted(g.page(), g.sx(), g.sy(), g.sw(), g.sh(),
                        gx + g.bearingX(), by + g.bearingY(), g.sw(), g.sh(), argb);
            }
        }
        return sh.advance();
    }

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        int px = Math.max(1, Math.round(size));
        GlyphAtlas.Glyph g = glyphs.icon(icon, px);
        // size is rounded to whole pixels, the leftover half pixel goes into centring
        float dx = Math.round(x + (size - px) * 0.5f);
        float dy = Math.round(y + (size - px) * 0.5f);
        blitTinted(g.page(), g.sx(), g.sy(), g.sw(), g.sh(), dx, dy, px, px, argb);
        if (icon == Icon.GOTO) {
            pathDots(dx, dy, px, argb);
        }
    }

    // the one coloured thing in the sidebar: the start and end of the go to path. the dots follow the tint's
    // brightness and alpha so an inactive tab dims them a little and a fade-out takes them along, but they never
    // turn grey. they go through the same grid transform as the stroke, so they land on the path ends (5,19) and
    // (19,5) at every size instead of drifting off as the icon grows
    private void pathDots(float dx, float dy, int px, int tint) {
        float lum = ((tint >> 16 & 255) * 0.299f + (tint >> 8 & 255) * 0.587f + (tint & 255) * 0.114f) / 255f;
        float a = (tint >>> 24) / 255f * (0.6f + 0.4f * lum);
        float d = Math.max(3f, Math.round(Icons.strokePx(px) * 2.5f));
        float sx = dx + Icons.toPx(px, 5) - d / 2f;
        float sy = dy + Icons.toPx(px, 19) - d / 2f;
        float ex = dx + Icons.toPx(px, 19) - d / 2f;
        float ey = dy + Icons.toPx(px, 5) - d / 2f;
        // on a blue fill (the selected sidebar row, a lit tile) the green dot would sink into the blue, so there each
        // dot gets a ring in the glyph's own white. the tint is the tell: a go to glyph only ever gets ON_ACCENT when
        // it is sitting on the accent. the sidebar lerps its tint, so the ring lands on the last frame of the slide
        if ((tint & 0x00FFFFFF) == (color(Theme.ON_ACCENT) & 0x00FFFFFF)) {
            float ring = Math.max(1f, Math.round(Icons.strokePx(px) * 0.75f));
            fillRoundRect(sx - ring, sy - ring, d + 2 * ring, d + 2 * ring, d / 2f + ring, tint);
            fillRoundRect(ex - ring, ey - ring, d + 2 * ring, d + 2 * ring, d / 2f + ring, tint);
        }
        fillRoundRect(sx, sy, d, d, d / 2f, Theme.alpha(color(Theme.PATH_START), a));
        fillRoundRect(ex, ey, d, d, d / 2f, Theme.alpha(color(Theme.PATH_END), a));
    }

    private void corner(ShapeAtlas.Slice s, int dx, int dy, int argb) {
        blitTinted(s.page(), s.sx(), s.sy(), s.sw(), s.sh(), dx, dy, s.sw(), s.sh(), argb);
    }
}
