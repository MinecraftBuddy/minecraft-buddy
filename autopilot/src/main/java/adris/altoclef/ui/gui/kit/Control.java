package adris.altoclef.ui.gui.kit;

// shared bits for widgets that animate: the last frame time (input handlers have no frame, so they borrow this one),
// text centring and the focus ring
public abstract class Control extends Widget {

    protected long now;

    protected Control(Theme theme) {
        super(theme);
    }

    protected final void stamp(Frame f) {
        now = f.nowNanos();
    }

    // baseline that puts the cap height in the middle of a box. the real ascent and descent differ per font, this
    // keeps light headings and the smaller labels aligned using each face's own metrics
    protected static float baseline(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    protected final void focusRing(Canvas c, float radius, float fade) {
        c.strokeRoundRect(x - 1, y - 1, w + 2, h + 2, radius + 1, Theme.fade(theme.color(Theme.ACCENT), fade));
    }

    protected final void hoverTo(Anim a, boolean on) {
        a.set(on ? 1f : 0f, now, Theme.ANIM_FAST);
    }
}
