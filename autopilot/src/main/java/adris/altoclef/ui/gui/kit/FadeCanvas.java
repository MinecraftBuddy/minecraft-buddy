package adris.altoclef.ui.gui.kit;

// wraps a canvas and fades every colour by a factor, optionally pushed down by dy. this is how the panel opens and how
// rows slide in, since the canvas has no transform stack and no global alpha. one instance gets reused, reset() per use
public final class FadeCanvas implements Canvas {

    private Canvas base;
    private float alpha = 1f;
    private float dy;

    public FadeCanvas reset(Canvas base, float alpha, float dy) {
        this.base = base;
        this.alpha = alpha;
        this.dy = dy;
        return this;
    }

    private int f(int argb) {
        return Theme.fade(argb, alpha);
    }

    @Override
    public int color(int token) {
        return base.color(token);
    }

    @Override
    public int width() {
        return base.width();
    }

    @Override
    public int height() {
        return base.height();
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        base.fillRect(x, y + dy, w, h, f(argb));
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        base.fillRoundRect(x, y + dy, w, h, radius, f(argb));
    }

    @Override
    public void strokeRoundRect(float x, float y, float w, float h, float radius, int argb) {
        base.strokeRoundRect(x, y + dy, w, h, radius, f(argb));
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, int argb) {
        base.shadow(x, y + dy, w, h, radius, f(argb));
    }

    @Override
    public float text(String s, float x, float baseline, TextStyle style, int argb) {
        return base.text(s, x, baseline + dy, style, f(argb));
    }

    @Override
    public void icon(Icon icon, float x, float y, float size, int argb) {
        base.icon(icon, x, y + dy, size, f(argb));
    }

    // an item sprite has no colour to fade, so it just waits until the rest is mostly there
    @Override
    public void itemIcon(String itemId, float x, float y, float size) {
        if (alpha >= 0.5f) {
            base.itemIcon(itemId, x, y + dy, size);
        }
    }

    @Override
    public void playerFace(String playerName, float x, float y, float size) {
        if (alpha >= 0.5f) {
            base.playerFace(playerName, x, y + dy, size);
        }
    }

    // unlike an item sprite the tint fades a picture properly, so it rides the animation like a fill does
    @Override
    public void image(PixelImage image, float x, float y, float w, float h, int tint) {
        base.image(image, x, y + dy, w, h, f(tint));
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        base.pushClip(x, y + dy, w, h);
    }

    @Override
    public void popClip() {
        base.popClip();
    }

    @Override
    public float measure(String s, TextStyle style) {
        return base.measure(s, style);
    }

    @Override
    public float ascent(TextStyle style) {
        return base.ascent(style);
    }

    @Override
    public float descent(TextStyle style) {
        return base.descent(style);
    }
}
