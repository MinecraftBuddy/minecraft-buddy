package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.AtlasPage;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TexturedCanvas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// the two primitives TexturedCanvas wants, on top of GuiGraphicsExtractor. no layout and no decisions in here. one of these
// lives as long as its screen (making a TexturedCanvas wipes the atlas caches) and gets the frame's graphics via begin
final class McCanvas extends TexturedCanvas {

    private final McAtlases atlases;
    private final McDraw draw;
    private int width;
    private int height;

    // set when a page would not upload, the screen swaps to the plain canvas instead of drawing without text forever
    boolean broken;

    McCanvas(McAtlases atlases, McDraw draw) {
        super(atlases.glyphs, atlases.shapes);
        this.atlases = atlases;
        this.draw = draw;
    }

    void begin(GuiGraphicsExtractor g, int widthPx, int heightPx) {
        draw.begin(g);
        this.width = widthPx;
        this.height = heightPx;
    }

    void end() {
        draw.end();
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
        draw.fill(x, y, w, h, argb);
    }

    @Override
    protected void blitTinted(AtlasPage page, int sx, int sy, int sw, int sh,
                              float dx, float dy, float dw, float dh, int argb) {
        if (broken) {
            return;
        }
        int x0 = Math.round(dx);
        int y0 = Math.round(dy);
        int w = Math.round(dx + dw) - x0;
        int h = Math.round(dy + dh) - y0;
        if (w <= 0 || h <= 0) {
            return;
        }
        try {
            draw.blit(atlases.setup(page), page.width(), page.height(), sx, sy, sw, sh, x0, y0, w, h, argb);
        } catch (Throwable t) {
            // the page would not upload, the screen swaps to the plain canvas
            Safe.report("gui atlas blit", t);
            broken = true;
        }
    }

    // same path as a glyph, with the picture's own texture bound and the tint left white so the colours come through
    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
        if (broken) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int dw = Math.round(x + w) - x0;
        int dh = Math.round(y + h) - y0;
        if (dw <= 0 || dh <= 0) {
            return;
        }
        try {
            draw.blit(atlases.setup(image), image.width(), image.height(), 0, 0, image.width(), image.height(), x0, y0, dw, dh, tint);
        } catch (Throwable t) {
            Safe.report("gui image blit", t);
            broken = true;
        }
    }

    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        if (draw.item(itemId, x, y, size)) {
            return;
        }
        // nothing registered under that id: a tile with its first letter beats the missing texture
        fillRoundRect(x, y, size, size, size / 6f, color(Theme.RAISED));
        TextStyle style = new TextStyle(Weight.MEDIUM, Math.max(4, Math.round(size * 0.5f)));
        String letter = McDraw.letterOf(itemId);
        float w = measure(letter, style);
        float baseline = y + (size + ascent(style) - descent(style)) * 0.5f;
        text(letter, x + (size - w) * 0.5f, baseline, style, color(Theme.TEXT_2));
    }

    @Override
    public void playerFace(String playerName, float x, float y, float size) {
        if (!draw.face(playerName, x, y, size)) {
            super.playerFace(playerName, x, y, size);
        }
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        draw.pushClip(x, y, w, h);
    }

    @Override
    public void popClip() {
        draw.popClip();
    }

    @Override
    protected void drawFallbackGlyph(int codePoint, float x, float baseline, TextStyle style, int argb) {
        draw.vanillaText(new String(Character.toChars(codePoint)), x, baseline, style.sizePx(), argb);
    }

    @Override
    protected float fallbackAdvance(int codePoint, TextStyle style) {
        return draw.vanillaWidth(new String(Character.toChars(codePoint)), style.sizePx());
    }
}
