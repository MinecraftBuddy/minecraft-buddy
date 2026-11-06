package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

// looks like a text field with a chevron. opening it hands a Menu to the shell, which draws it above everything and
// closes it on an outside click. the menu goes below the control, or above when the window runs out of room
public class Dropdown extends Control implements FocusOnClick {

    private static final int MAX_ROWS = 7;

    private List<String> options;
    private int selected;
    private final IntConsumer onChange;
    private final ShellContext ctx;
    private final Anim hoverA = new Anim(0f);
    private Menu menu;
    private int viewH = Integer.MAX_VALUE;
    private int viewW = Integer.MAX_VALUE;

    public Dropdown(Theme theme, List<String> options, int selected, IntConsumer onChange, ShellContext ctx) {
        super(theme);
        this.options = List.copyOf(options);
        this.selected = selected;
        this.onChange = onChange;
        this.ctx = ctx;
    }

    public int selected() {
        return selected;
    }

    public void setSelected(int i) {
        selected = i;
    }

    public void setOptions(List<String> next, int selected) {
        this.selected = selected;
        // a tab that refreshes its options every tick must not slam the menu shut each time
        if (next.equals(options)) {
            return;
        }
        // an open menu was built for the old list
        if (isOpen()) {
            ctx.closeOverlay();
        }
        options = List.copyOf(next);
    }

    public boolean isOpen() {
        return menu != null && menu.visible();
    }

    @Override
    public int preferredHeight() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        viewH = c.height();
        viewW = c.width();
        hoverTo(hoverA, hovered);
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        boolean open = isOpen();
        // dressed like a secondary button, since that is what it is until you open it
        int fill = Theme.lerp(theme.color(Theme.RAISED), theme.color(Theme.HOVER), hoverA.value(now));
        c.fillRoundRect(x, y, w, h, r, fill);
        c.strokeRoundRect(x, y, w, h, r, focused || open ? theme.color(Theme.ACCENT) : Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
        int is = theme.px(Theme.ICON);
        int pad = theme.px(10);
        float room = w - pad * 2 - is - theme.px(6);
        String t = selected >= 0 && selected < options.size() ? options.get(selected) : "";
        String shown = theme.measurer.ellipsize(t, theme.body, Math.max(0f, room));
        c.text(shown, x + pad, baseline(c, theme.body, y, h), theme.body, theme.color(Theme.TEXT));
        c.icon(Icon.CHEVRON_DOWN, x + w - pad - is + theme.px(2), y + (h - is) / 2f, is, theme.color(Theme.TEXT_2));
    }

    private void toggle() {
        if (isOpen()) {
            ctx.closeOverlay();
            return;
        }
        if (options.isEmpty()) {
            return;
        }
        menu = new Menu();
        ctx.openOverlay(menu);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || button != 0) {
            return false;
        }
        toggle();
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (Keys.isEnter(key) || key == Keys.SPACE || key == Keys.DOWN || key == Keys.UP) {
            toggle();
            return true;
        }
        return false;
    }

    private void choose(int i) {
        selected = i;
        ctx.closeOverlay();
        Safe.run("dropdown", () -> onChange.accept(i));
    }

    // the popup list. it is its own widget so the shell can route keys and clicks to it first
    private final class Menu extends Control {

        private final Spring reveal = new Spring(0f);
        private final FadeCanvas fade = new FadeCanvas();
        private boolean down;
        private boolean started;
        private int hot;
        private int first;
        private final int rows;
        private StringBuilder buffer = new StringBuilder();
        private long lastTypeNanos;

        Menu() {
            super(Dropdown.this.theme);
            hot = Math.max(0, Math.min(options.size() - 1, selected));
            int rowH = theme.px(Theme.ROW_H);
            int pad = theme.px(4);
            int want = Math.min(options.size(), MAX_ROWS);
            int below = (viewH == Integer.MAX_VALUE ? Integer.MAX_VALUE : viewH - (Dropdown.this.y + Dropdown.this.h) - theme.px(12));
            int above = Dropdown.this.y - theme.px(12);
            down = below >= want * rowH + 2 * pad || below >= above;
            int room = (down ? below : above) - 2 * pad;
            rows = Math.max(1, Math.min(want, room == Integer.MAX_VALUE ? want : room / rowH));
            int mh = rows * rowH + 2 * pad;
            int mw = Math.max(Dropdown.this.w, theme.px(160));
            int my = down ? Dropdown.this.y + Dropdown.this.h + theme.px(4) : Dropdown.this.y - theme.px(4) - mh;
            int mx = viewW == Integer.MAX_VALUE ? Dropdown.this.x : Math.max(0, Math.min(Dropdown.this.x, viewW - mw - theme.px(8)));
            setBounds(mx, my, mw, mh);
            ensureHot();
            visible = true;
        }

        private void ensureHot() {
            if (hot < first) {
                first = hot;
            } else if (hot >= first + rows) {
                first = hot - rows + 1;
            }
            first = Math.max(0, Math.min(options.size() - rows, first));
        }

        @Override
        public void draw(Canvas c, Frame f) {
            stamp(f);
            if (!started) {
                started = true;
                reveal.set(1f, now, Theme.ANIM);
            }
            float a = reveal.value(now);
            c = fade.reset(c, a, (1f - a) * theme.px(6) * (down ? -1 : 1));
            float r = theme.pxf(Theme.CARD_RADIUS);
            theme.floating(c, x, y, w, h, r);
            int rowH = theme.px(Theme.ROW_H);
            int pad = theme.px(4);
            int is = theme.px(Theme.ICON);
            c.pushClip(x, y + 1, w, h - 2);
            for (int i = 0; i < rows && first + i < options.size(); i++) {
                int idx = first + i;
                float ry = y + pad + i * rowH;
                if (idx == hot) {
                    c.fillRoundRect(x + pad, ry, w - 2 * pad, rowH, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.ACCENT_SOFT));
                }
                float tw = w - 2 * pad - theme.px(20) - is;
                String s = theme.measurer.ellipsize(options.get(idx), theme.body, Math.max(0f, tw));
                c.text(s, x + pad + theme.px(10), baseline(c, theme.body, ry, rowH), theme.body, theme.color(Theme.TEXT));
                if (idx == selected) {
                    c.icon(Icon.CHECK, x + w - pad - theme.px(10) - is, ry + (rowH - is) / 2f, is, theme.color(Theme.TEXT_2));
                }
            }
            c.popClip();
            if (options.size() > rows) {
                float track = h - 2 * pad;
                float thumb = Math.max(theme.px(18), track * rows / options.size());
                float top = y + pad + (track - thumb) * first / (options.size() - rows);
                int width = theme.px(3);
                c.fillRoundRect(x + w - pad - width, top, width, thumb, width / 2f, theme.color(Theme.HAIRLINE_STRONG));
            }
        }

        private int rowAt(float mx, float my) {
            if (!contains(mx, my)) {
                return -1;
            }
            int i = (int) ((my - y - theme.px(4)) / theme.px(Theme.ROW_H));
            int idx = first + i;
            return i < 0 || i >= rows || idx >= options.size() ? -1 : idx;
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            Widget d = super.mouseMove(mx, my);
            int idx = rowAt(mx, my);
            if (idx >= 0) {
                hot = idx;
            }
            return d;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            if (!contains(mx, my)) {
                return false;
            }
            int idx = rowAt(mx, my);
            if (idx >= 0 && button == 0) {
                choose(idx);
            }
            return true;
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            if (!contains(mx, my)) {
                return false;
            }
            first = Math.max(0, Math.min(options.size() - rows, first - Math.round(Math.signum(dy))));
            return true;
        }

        @Override
        public boolean keyDown(int key, int mods) {
            int n = options.size();
            switch (key) {
                case Keys.DOWN -> hot = Math.min(n - 1, hot + 1);
                case Keys.UP -> hot = Math.max(0, hot - 1);
                case Keys.HOME -> hot = 0;
                case Keys.END -> hot = n - 1;
                case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE -> choose(hot);
                case Keys.ESCAPE -> ctx.closeOverlay();
                default -> {
                    return false;
                }
            }
            ensureHot();
            return true;
        }

        // type a few letters to jump, the buffer forgets itself after a second
        @Override
        public boolean charTyped(char ch) {
            if (ch < 32) {
                return false;
            }
            if (now - lastTypeNanos > Anim.ms(1000)) {
                buffer = new StringBuilder();
            }
            lastTypeNanos = now;
            buffer.append(Character.toLowerCase(ch));
            String want = buffer.toString();
            int n = options.size();
            for (int k = 0; k < n; k++) {
                int idx = (hot + (want.length() == 1 ? 1 : 0) + k) % n;
                if (options.get(idx).toLowerCase(Locale.ROOT).startsWith(want)) {
                    hot = idx;
                    ensureHot();
                    break;
                }
            }
            return true;
        }
    }
}
