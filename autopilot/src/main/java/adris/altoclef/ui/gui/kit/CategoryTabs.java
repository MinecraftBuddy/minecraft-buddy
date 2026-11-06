package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.function.IntConsumer;

// the narrow shell scrolls its tabs instead of stealing another row from the item grid
public final class CategoryTabs extends Control {

    private final IntConsumer onChange;
    private List<String> options = List.of();
    private int[] left = new int[0];
    private int[] widths = new int[0];
    private Anim[] active = new Anim[0];
    private Anim[] hover = new Anim[0];
    private int selected;
    private int hot = -1;
    private final Spring offset = new Spring(0f);

    public CategoryTabs(Theme theme, IntConsumer onChange) {
        super(theme);
        this.onChange = onChange;
    }

    public List<String> options() {
        return options;
    }

    public int selected() {
        return selected;
    }

    public void setOptions(List<String> options, int selected) {
        if (!this.options.equals(options)) {
            this.options = List.copyOf(options);
            int n = options.size();
            left = new int[n];
            widths = new int[n];
            active = new Anim[n];
            hover = new Anim[n];
            hot = -1;
            for (int i = 0; i < n; i++) {
                widths[i] = (int) Math.ceil(theme.measurer.measure(options.get(i), theme.label)) + theme.px(24);
                active[i] = new Anim(i == selected ? 1f : 0f);
                hover[i] = new Anim(0f);
            }
        }
        this.selected = Math.max(0, Math.min(options.size() - 1, selected));
        layout();
    }

    @Override
    public int preferredWidth() {
        int sum = 0;
        for (int width : widths) {
            sum += width + theme.px(4);
        }
        return Math.max(0, sum - theme.px(4));
    }

    private int arrowWidth() {
        return preferredWidth() > w ? theme.px(22) : 0;
    }

    @Override
    protected void layout() {
        int cx = 0;
        for (int i = 0; i < widths.length; i++) {
            left[i] = cx;
            cx += widths[i] + theme.px(4);
        }
        revealSelected();
    }

    private void revealSelected() {
        if (options.isEmpty() || w <= 0) {
            return;
        }
        int viewport = Math.max(1, w - 2 * arrowWidth());
        float target = offset.target();
        if (left[selected] < target) {
            target = left[selected];
        } else if (left[selected] + widths[selected] > target + viewport) {
            target = left[selected] + widths[selected] - viewport;
        }
        offset.set(Math.max(0, Math.min(Math.max(0, preferredWidth() - viewport), target)), now, Theme.ANIM);
    }

    @Override
    public boolean focusable() {
        return visible && !options.isEmpty();
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        int rowH = theme.px(Theme.CONTROL_H);
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int arrow = arrowWidth();
        c.pushClip(x + arrow, y - theme.px(3), Math.max(0, w - 2 * arrow), h + theme.px(6));
        for (int i = 0; i < options.size(); i++) {
            active[i].set(i == selected ? 1f : 0f, now, Theme.ANIM_FAST);
            hoverTo(hover[i], hot == i);
            float a = active[i].value(now);
            float hv = hover[i].value(now);
            float tx = x + arrow + left[i] - offset.value(now);
            int ty = y;
            int tw = widths[i];
            // the chosen category is a quiet pill, the rest are plain words until hovered
            c.fillRoundRect(tx, ty, tw, rowH, r, Theme.fade(theme.color(Theme.HOVER), hv * (1f - a)));
            c.fillRoundRect(tx, ty, tw, rowH, r, Theme.fade(theme.color(Theme.RAISED), a));
            if (focused && i == selected) {
                c.strokeRoundRect(tx - 1, ty - 1, tw + 2, rowH + 2, r + 1, theme.color(Theme.ACCENT));
            }
            String label = c.ellipsize(options.get(i), theme.label, Math.max(0, tw - theme.px(16)));
            int col = Theme.lerp(Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.TEXT), hv), theme.color(Theme.TEXT), a);
            c.text(label, tx + (tw - c.measure(label, theme.label)) / 2f,
                    baseline(c, theme.label, ty, rowH), theme.label, col);
        }
        c.popClip();
        if (arrow > 0) {
            drawArrow(c, "<", x, arrow, selected > 0);
            drawArrow(c, ">", x + w - arrow, arrow, selected + 1 < options.size());
        }
    }

    private void drawArrow(Canvas c, String label, int left, int width, boolean enabled) {
        int color = theme.color(enabled ? Theme.TEXT_2 : Theme.TEXT_3);
        c.text(label, left + (width - c.measure(label, theme.label)) / 2f,
                baseline(c, theme.label, y, h), theme.label, color);
    }

    private int at(float mx, float my) {
        if (!contains(mx, my)) {
            return -1;
        }
        int arrow = arrowWidth();
        if (mx < x + arrow || mx >= x + w - arrow) {
            return -1;
        }
        float rel = mx - x - arrow + offset.value(now);
        for (int i = 0; i < options.size(); i++) {
            if (rel >= left[i] && rel < left[i] + widths[i]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        hot = at(mx, my);
        return super.mouseMove(mx, my);
    }

    private void choose(int i) {
        if (i < 0 || i >= options.size() || i == selected) {
            return;
        }
        selected = i;
        revealSelected();
        Safe.run("category tabs", () -> onChange.accept(i));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (contains(mx, my) && button == 0 && arrowWidth() > 0) {
            if (mx < x + arrowWidth()) {
                choose(selected - 1);
                return true;
            }
            if (mx >= x + w - arrowWidth()) {
                choose(selected + 1);
                return true;
            }
        }
        int i = at(mx, my);
        if (button != 0 || i < 0) {
            return false;
        }
        choose(i);
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused || options.isEmpty()) {
            return false;
        }
        switch (key) {
            case Keys.LEFT -> choose(selected - 1);
            case Keys.RIGHT -> choose(selected + 1);
            case Keys.HOME -> choose(0);
            case Keys.END -> choose(options.size() - 1);
            default -> { return false; }
        }
        return true;
    }
}
