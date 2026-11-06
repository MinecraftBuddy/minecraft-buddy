package adris.altoclef.ui.gui.kit;

// a hairline. always one pixel thick, even at 2x scale, because that is what a hairline is
public class Divider extends Widget {

    private final boolean horizontal;

    public Divider(Theme theme, boolean horizontal) {
        super(theme);
        this.horizontal = horizontal;
    }

    @Override
    public int preferredWidth() {
        return horizontal ? -1 : 1;
    }

    @Override
    public int preferredHeight() {
        return horizontal ? 1 : -1;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        if (horizontal) {
            c.fillRect(x, y + h / 2f, w, 1, theme.color(Theme.HAIRLINE));
        } else {
            c.fillRect(x + w / 2f, y, 1, h, theme.color(Theme.HAIRLINE));
        }
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        return false;
    }
}
