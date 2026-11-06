package adris.altoclef.ui.gui.kit;

// the one button. four looks, an optional leading icon, and a press flash that outlives the mouse so a fast click is
// still visible. also reachable by keyboard: enter or space when focused
public class Button extends Control {

    public enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    protected Kind kind;
    protected String text;
    protected Icon icon;
    protected final Runnable onClick;
    protected boolean enabled = true;
    protected final Anim hoverA = new Anim(0f);
    protected final Anim pressA = new Anim(0f);
    private boolean focusableFlag = true;
    private boolean held;
    private boolean flash;
    private int lastMods;
    private float textW;
    private String shown;

    public Button(Theme theme, String text, Runnable onClick) {
        this(theme, text, Kind.SECONDARY, null, onClick);
    }

    public Button(Theme theme, String text, Kind kind, Icon icon, Runnable onClick) {
        super(theme);
        this.kind = kind;
        this.icon = icon;
        this.onClick = onClick;
        setText(text);
    }

    public void setText(String t) {
        text = t == null ? "" : t;
        textW = text.isEmpty() ? 0f : theme.measurer.measure(text, theme.label);
        shown = text;
        layout();
    }

    public String text() {
        return text;
    }

    public void setIcon(Icon i) {
        icon = i;
        layout();
    }

    // the stop button is a ghost while nothing runs and goes red once there is something to stop
    public void setKind(Kind k) {
        kind = k;
    }

    public Kind kind() {
        return kind;
    }

    public void setEnabled(boolean e) {
        enabled = e;
        if (!e) {
            held = false;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    // the +/- nudges in a number field are mouse things, tabbing through them is just noise
    public void setFocusable(boolean f) {
        focusableFlag = f;
    }

    // mods of the click or key that last fired, for buttons that care about shift and ctrl
    public int lastMods() {
        return lastMods;
    }

    protected int iconSize() {
        return theme.px(Theme.ICON);
    }

    @Override
    public int preferredWidth() {
        // ceil, not round: a 62.4 wide label rounded to 62 would ellipsize itself in layout()
        int w = theme.px(24) + (int) Math.ceil(textW);
        if (icon != null) {
            w += iconSize() + (text.isEmpty() ? 0 : theme.px(6));
        }
        return w;
    }

    @Override
    public int preferredHeight() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    protected void layout() {
        float avail = w - theme.px(24) - (icon != null ? iconSize() + theme.px(6) : 0);
        shown = text.isEmpty() || w <= 0 || textW <= avail ? text :theme.measurer.ellipsize(text, theme.label, Math.max(0, avail));
    }

    @Override
    public boolean focusable() {
        return visible && enabled && focusableFlag;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        hoverTo(hoverA, hovered && enabled);
        if (flash) {
            flash = false;
            pressA.snap(1f);
        }
        pressA.set(held ? 1f : 0f, now, Theme.ANIM_FAST);
        float hv = hoverA.value(now);
        float pv = pressA.value(now);
        float dim = enabled ? 1f : 0.4f;
        float r = theme.pxf(Theme.CONTROL_RADIUS);

        // flat fills. primary is the one blue thing, secondary and danger are a quiet bump with a hairline, ghost is
        // nothing until hovered. pressed just goes a step darker, no emboss
        int base;
        int hover;
        int pressed;
        int textCol;
        boolean edged = true;
        switch (kind) {
            case PRIMARY -> {
                base = theme.color(Theme.ACCENT);
                hover = theme.color(Theme.ACCENT_HOVER);
                pressed = theme.color(Theme.ACCENT_PRESSED);
                textCol = theme.color(Theme.ON_ACCENT);
                edged = false;
            }
            case GHOST -> {
                base = Theme.alpha(theme.color(Theme.HOVER), 0f);
                hover = theme.color(Theme.HOVER);
                pressed = theme.color(Theme.PRESSED);
                textCol = Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.TEXT), hv);
                edged = false;
            }
            case DANGER -> {
                base = theme.color(Theme.RAISED);
                hover = theme.color(Theme.DANGER_FILL);
                pressed = theme.color(Theme.DANGER_PRESSED);
                textCol = theme.color(Theme.DANGER);
            }
            default -> {
                base = theme.color(Theme.RAISED);
                hover = theme.color(Theme.HOVER);
                pressed = theme.color(Theme.PRESSED);
                textCol = theme.color(Theme.TEXT);
            }
        }
        // a disabled primary is not a faded blue (white on 40% blue is unreadable in light), it is a plain grey
        // button that says the action is not available yet
        if (!enabled && kind == Kind.PRIMARY) {
            base = theme.color(Theme.RAISED);
            hover = base;
            pressed = base;
            textCol = theme.color(Theme.TEXT_3);
            edged = true;
            dim = 1f;
        }
        int fill = Theme.lerp(Theme.lerp(base, hover, hv), pressed, pv);
        if ((fill >>> 24) != 0) {
            c.fillRoundRect(x, y, w, h, r, Theme.fade(fill, dim));
        }
        if (edged) {
            c.strokeRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), dim * 0.6f));
        }
        if (focused) {
            focusRing(c, r, dim);
        }
        drawContent(c, Theme.fade(textCol, dim), hv);
    }

    protected void drawContent(Canvas c, int col, float hv) {
        float is = iconSize();
        float gap = icon != null && !shown.isEmpty() ? theme.px(6) : 0f;
        float tw = shown.isEmpty() ? 0f : c.measure(shown, theme.label);
        float cw = (icon != null ? is : 0f) + gap + tw;
        float cx = x + (w - cw) / 2f;
        if (icon != null) {
            c.icon(icon, cx, y + (h - is) / 2f, is, col);
            cx += is + gap;
        }
        if (!shown.isEmpty()) {
            c.text(shown, cx, baseline(c, theme.label, y, h), theme.label, col);
        }
    }

    protected void fire(int mods) {
        lastMods = mods;
        flash = true;
        if (onClick != null) {
            Safe.run("button " + text, onClick);
        }
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || !enabled || button != 0) {
            return false;
        }
        held = true;
        pressA.snap(1f);
        lastMods = mods;
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean was = held;
        held = false;
        if (was && enabled && button == 0 && contains(mx, my)) {
            fire(mods);
        }
        return was;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused || !enabled) {
            return false;
        }
        if (Keys.isEnter(key) || key == Keys.SPACE) {
            fire(mods);
            return true;
        }
        return false;
    }
}
