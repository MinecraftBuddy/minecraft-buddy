package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// what the gui draws with when the atlases would not come up (no awt, a texture that would not upload): flat fills,
// vanilla's font scaled to the size asked for, no shadows, no icons. ugly but every button still works
final class FallbackCanvas implements Canvas {

    private final McDraw draw;
    private int width;
    private int height;
    private Theme theme;

    @Override
    public void setTheme(Theme theme) {
        this.theme = theme;
    }

    @Override
    public int color(int token) {
        return theme == null ? token : theme.color(token);
    }

    FallbackCanvas(McDraw draw) {
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
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        draw.fill(x, y, w, h, argb);
    }

    @Override
    public void strokeRoundRect(float x, float y, float w, float h, float radius, int argb) {
        draw.fill(x, y, w, 1f, argb);
        draw.fill(x, y + h - 1f, w, 1f, argb);
        draw.fill(x, y + 1f, 1f, h - 2f, argb);
        draw.fill(x + w - 1f, y + 1f, 1f, h - 2f, argb);
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, int argb) {
    }

    @Override
    public float text(String s, float x, float baseline, TextStyle style, int argb) {
        draw.vanillaText(s, x, baseline, style.sizePx(), argb);
        return measure(s, style);
    }

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        // a dot where the icon would be, so a button without a label is still something you can find
        float d = Math.max(2f, size * 0.3f);
        draw.fill(x + (size - d) * 0.5f, y + (size - d) * 0.5f, d, d, argb);
    }

    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        if (draw.item(itemId, x, y, size)) {
            return;
        }
        draw.fill(x, y, size, size, color(Theme.RAISED));
        String letter = McDraw.letterOf(itemId);
        float fs = Math.max(4f, size * 0.5f);
        draw.vanillaText(letter, x + (size - draw.vanillaWidth(letter, fs)) * 0.5f,
                y + (size + draw.vanillaAscent(fs) - draw.vanillaDescent(fs)) * 0.5f, fs, color(Theme.TEXT_2));
    }

    @Override
    public void playerFace(String playerName, float x, float y, float size) {
        if (!draw.face(playerName, x, y, size)) {
            draw.fill(x, y, size, size, color(Theme.RAISED));
        }
    }

    // no textures on this canvas at all (that is why it's in use), so a picture is a plain bump where it would be
    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
        draw.fill(x, y, w, h, Theme.fade(color(Theme.RAISED), (tint >>> 24) / 255f));
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
    public float measure(String s, TextStyle style) {
        return draw.vanillaWidth(s, style.sizePx());
    }

    @Override
    public float ascent(TextStyle style) {
        return draw.vanillaAscent(style.sizePx());
    }

    @Override
    public float descent(TextStyle style) {
        return draw.vanillaDescent(style.sizePx());
    }
}
