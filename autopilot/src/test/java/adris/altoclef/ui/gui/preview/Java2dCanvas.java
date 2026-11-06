package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.AtlasPage;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TexturedCanvas;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

// the preview backend: the same two primitives the game gives TexturedCanvas, done with plain int math on a
// BufferedImage. no Graphics2D in the hot path so what lands in the png is exactly what the compositing says
public final class Java2dCanvas extends TexturedCanvas {

    private final BufferedImage image;
    private final int[] pixels;
    private final int width;
    private final int height;
    private final List<int[]> clips = new ArrayList<>();
    // x0, y0, x1, y1 of the active clip, exclusive on the far side
    private int cx0;
    private int cy0;
    private int cx1;
    private int cy1;

    public Java2dCanvas(int width, int height, GlyphAtlas glyphs, ShapeAtlas shapes) {
        super(glyphs, shapes);
        this.width = width;
        this.height = height;
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        this.cx1 = width;
        this.cy1 = height;
    }

    public BufferedImage image() {
        return image;
    }

    public void clear(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    public void save(Path png) throws IOException {
        Path dir = png.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        ImageIO.write(image, "png", png.toFile());
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        int a = argb >>> 24;
        if (a == 0) {
            return;
        }
        int x0 = Math.max(cx0, Math.round(x));
        int y0 = Math.max(cy0, Math.round(y));
        int x1 = Math.min(cx1, Math.round(x + w));
        int y1 = Math.min(cy1, Math.round(y + h));
        for (int py = y0; py < y1; py++) {
            int row = py * width;
            for (int px = x0; px < x1; px++) {
                pixels[row + px] = over(pixels[row + px], argb, a);
            }
        }
    }

    @Override
    protected void blitTinted(AtlasPage page, int sx, int sy, int sw, int sh,
                              float dx, float dy, float dw, float dh, int argb) {
        int ta = argb >>> 24;
        if (ta == 0 || sw <= 0 || sh <= 0) {
            return;
        }
        int[] src = ((DataBufferInt) page.image().getRaster().getDataBuffer()).getData();
        int sstride = page.width();
        int x0 = Math.round(dx);
        int y0 = Math.round(dy);
        int x1 = Math.round(dx + dw);
        int y1 = Math.round(dy + dh);
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 0 || h <= 0) {
            return;
        }
        int rgb = argb & 0x00FFFFFF;
        boolean same = w == sw && h == sh;
        for (int py = Math.max(y0, cy0); py < Math.min(y1, cy1); py++) {
            int srow = same ? sy + (py - y0) : sy + Math.min(sh - 1, (int) ((py - y0 + 0.5f) * sh / h));
            for (int px = Math.max(x0, cx0); px < Math.min(x1, cx1); px++) {
                int scol = same ? sx + (px - x0) : sx + Math.min(sw - 1, (int) ((px - x0 + 0.5f) * sw / w));
                int sa = src[srow * sstride + scol] >>> 24;
                if (sa == 0) {
                    continue;
                }
                int a = sa == 255 ? ta : (sa * ta + 127) / 255;
                if (a != 0) {
                    pixels[py * width + px] = over(pixels[py * width + px], (a << 24) | rgb, a);
                }
            }
        }
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        clips.add(new int[]{cx0, cy0, cx1, cy1});
        cx0 = Math.max(cx0, Math.round(x));
        cy0 = Math.max(cy0, Math.round(y));
        cx1 = Math.min(cx1, Math.round(x + w));
        cy1 = Math.min(cy1, Math.round(y + h));
        if (cx1 < cx0) {
            cx1 = cx0;
        }
        if (cy1 < cy0) {
            cy1 = cy0;
        }
    }

    @Override
    public void popClip() {
        int[] c = clips.remove(clips.size() - 1);
        cx0 = c[0];
        cy0 = c[1];
        cx1 = c[2];
        cy1 = c[3];
    }

    // a flat tile with a hairline and a lighter square inside, enough that a slot reads as a slot in a preview
    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        int px = Math.max(1, Math.round(size));
        int dx = Math.round(x);
        int dy = Math.round(y);
        BufferedImage tex = ItemTextures.get(itemId);
        if (tex != null) {
            blitNearest(tex, dx, dy, px);
            return;
        }
        // same thing the game canvas does for an id nothing resolves: a tile with the first letter of the name
        String path = itemId == null ? "" : itemId.substring(itemId.indexOf(':') + 1);
        String letter = path.isEmpty() ? "?" : path.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        fillRoundRect(dx, dy, px, px, px * 0.18f, 0xFF2A2A2F);
        strokeRoundRect(dx, dy, px, px, px * 0.18f, 0xFF3A3A40);
        TextStyle st = new TextStyle(adris.altoclef.ui.gui.kit.Weight.MEDIUM, Math.max(8, Math.round(px * 0.5f)));
        float tw = measure(letter, st);
        float base = dy + (px - (ascent(st) + descent(st))) * 0.5f + ascent(st);
        text(letter, dx + (px - tw) * 0.5f, base, st, 0xFFB0B0B7);
    }

    // the picture keeps its own colours (blitTinted swaps them for the tint, which is what a white atlas wants and
    // what a map does not), nearest neighbour both ways, the tint only scales the alpha and the channels
    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
        int ta = tint >>> 24;
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        int dw = x1 - x0;
        int dh = y1 - y0;
        if (ta == 0 || dw <= 0 || dh <= 0) {
            return;
        }
        int[] src = image.pixels();
        int sw = image.width();
        int sh = image.height();
        boolean white = (tint & 0x00FFFFFF) == 0x00FFFFFF;
        for (int py = Math.max(y0, cy0); py < Math.min(y1, cy1); py++) {
            int srow = (int) ((long) (py - y0) * sh / dh) * sw;
            for (int px = Math.max(x0, cx0); px < Math.min(x1, cx1); px++) {
                int s = src[srow + (int) ((long) (px - x0) * sw / dw)];
                int sa = s >>> 24;
                if (sa == 0) {
                    continue;
                }
                int a = ta == 255 ? sa : (sa * ta + 127) / 255;
                int rgb = white ? s & 0x00FFFFFF : mul(s, tint);
                if (a != 0) {
                    pixels[py * width + px] = over(pixels[py * width + px], (a << 24) | rgb, a);
                }
            }
        }
    }

    private static int mul(int s, int t) {
        int r = ((s >> 16) & 255) * ((t >> 16) & 255) / 255;
        int g = ((s >> 8) & 255) * ((t >> 8) & 255) / 255;
        int b = (s & 255) * (t & 255) / 255;
        return (r << 16) | (g << 8) | b;
    }

    // nearest neighbour so 16 px pixel art stays pixel art at 32 and 48
    private void blitNearest(BufferedImage tex, int dx, int dy, int px) {
        int tw = tex.getWidth();
        int th = tex.getHeight();
        for (int yy = 0; yy < px; yy++) {
            int oy = dy + yy;
            if (oy < cy0 || oy >= cy1) {
                continue;
            }
            int sy = yy * th / px;
            for (int xx = 0; xx < px; xx++) {
                int ox = dx + xx;
                if (ox < cx0 || ox >= cx1) {
                    continue;
                }
                int src = tex.getRGB(xx * tw / px, sy);
                int sa = src >>> 24;
                if (sa == 0) {
                    continue;
                }
                int i = oy * width + ox;
                pixels[i] = sa == 255 ? src : over(pixels[i], src);
            }
        }
    }

    private static int over(int dst, int src) {
        int sa = src >>> 24;
        int da = dst >>> 24;
        int oa = sa + da * (255 - sa) / 255;
        if (oa == 0) {
            return 0;
        }
        int r = ((src >> 16 & 255) * sa + (dst >> 16 & 255) * da * (255 - sa) / 255) / oa;
        int g = ((src >> 8 & 255) * sa + (dst >> 8 & 255) * da * (255 - sa) / 255) / oa;
        int b = ((src & 255) * sa + (dst & 255) * da * (255 - sa) / 255) / oa;
        return (oa << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void drawFallbackGlyph(int codePoint, float x, float baseline, TextStyle style, int argb) {
        Graphics2D g = image.createGraphics();
        try {
            g.setClip(cx0, cy0, cx1 - cx0, cy1 - cy0);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(new Font(Font.DIALOG, Font.PLAIN, style.sizePx()));
            g.setColor(new Color(argb, true));
            g.drawString(new String(Character.toChars(codePoint)), x, baseline);
        } finally {
            g.dispose();
        }
    }

    // asks the same dialog font it draws with, or a cjk name would pile its characters on top of each other
    @Override
    protected float fallbackAdvance(int codePoint, TextStyle style) {
        Graphics2D g = image.createGraphics();
        try {
            Font f = new Font(Font.DIALOG, Font.PLAIN, style.sizePx());
            return g.getFontMetrics(f).stringWidth(new String(Character.toChars(codePoint)));
        } finally {
            g.dispose();
        }
    }

    // source over for non premultiplied argb, with the usual shortcuts because most pixels are opaque or opaque on top
    private static int over(int dst, int src, int sa) {
        if (sa == 255) {
            return src | 0xFF000000;
        }
        int da = dst >>> 24;
        if (da == 255) {
            int inv = 255 - sa;
            int r = (((src >> 16) & 255) * sa + ((dst >> 16) & 255) * inv + 127) / 255;
            int g = (((src >> 8) & 255) * sa + ((dst >> 8) & 255) * inv + 127) / 255;
            int b = ((src & 255) * sa + (dst & 255) * inv + 127) / 255;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        int outA = sa + (da * (255 - sa) + 127) / 255;
        if (outA == 0) {
            return 0;
        }
        int dw = da * (255 - sa) / 255;
        int r = (((src >> 16) & 255) * sa + ((dst >> 16) & 255) * dw) / outA;
        int g = (((src >> 8) & 255) * sa + ((dst >> 8) & 255) * dw) / outA;
        int b = ((src & 255) * sa + (dst & 255) * dw) / outA;
        return (outA << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }
}
