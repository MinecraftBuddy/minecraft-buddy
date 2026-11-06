package adris.altoclef.ui.gui.kit;

// a square ghost button with just a glyph. the tooltip is carried for whoever wants to show it, nothing draws it yet
public class IconButton extends Button {

    private final String tooltip;

    public IconButton(Theme theme, Icon icon, String tooltip, Runnable onClick) {
        super(theme, "", Kind.GHOST, icon, onClick);
        this.tooltip = tooltip;
    }

    public String tooltip() {
        return tooltip;
    }

    @Override
    public int preferredWidth() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    protected void drawContent(Canvas c, int col, float hv) {
        if (icon == null) {
            return;
        }
        float is = iconSize();
        c.icon(icon, x + (w - is) / 2f, y + (h - is) / 2f, is, col);
    }
}
