package adris.altoclef.ui.gui.kit;

// plain text that cuts itself off with an ellipsis instead of spilling out of its box. measured once per change
public class Label extends Widget {

    public enum Align { LEFT, CENTER, RIGHT }

    private String text;
    private final TextStyle style;
    private int colour;
    private Align align = Align.LEFT;
    private float textW;
    private String shown;

    public Label(Theme theme, String text, TextStyle style, int colour) {
        super(theme);
        this.style = style;
        this.colour = colour;
        setText(text);
    }

    public Label setAlign(Align a) {
        align = a;
        return this;
    }

    public void setColour(int argb) {
        colour = argb;
    }

    public void setText(String t) {
        text = t == null ? "" : t;
        textW = text.isEmpty() ? 0f : theme.measurer.measure(text, style);
        layout();
    }

    public String text() {
        return text;
    }

    @Override
    protected void layout() {
        shown = textW <= w || w <= 0 ? text : theme.measurer.ellipsize(text, style, w);
    }

    @Override
    public int preferredWidth() {
        return Math.round(textW);
    }

    @Override
    public int preferredHeight() {
        return Math.round(theme.measurer.lineHeight(style));
    }

    @Override
    public void draw(Canvas c, Frame f) {
        if (shown.isEmpty()) {
            return;
        }
        float tw = shown == text ? textW : c.measure(shown, style);
        float tx = switch (align) {
            case CENTER -> x + (w - tw) / 2f;
            case RIGHT -> x + w - tw;
            default -> x;
        };
        float base = y + h / 2f + (c.ascent(style) - c.descent(style)) / 2f;
        c.text(shown, tx, base, style, theme.color(colour));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        return false;
    }
}
