package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.function.IntConsumer;

// a pill group: one quiet track, the chosen option a lighter pill that slides to wherever you click. segment widths
// follow the text, so "Get" and "Mine" do not pretend to be the same size
public class Segmented extends Control {

    private final List<String> options;
    private final IntConsumer onChange;
    private final float[] textW;
    private final float[] segX;
    private final float[] segW;
    private final Spring slide;
    private int selected;

    public Segmented(Theme theme, List<String> options, int selected, IntConsumer onChange) {
        super(theme);
        // copied: the arrays below are sized once, a caller adding to their list later must not break draw
        this.options = List.copyOf(options);
        this.onChange = onChange;
        int n = this.options.size();
        textW = new float[n];
        segX = new float[n];
        segW = new float[n];
        for (int i = 0; i < n; i++) {
            textW[i] = theme.measurer.measure(options.get(i), theme.label);
        }
        this.selected = Math.max(0, Math.min(Math.max(0, n - 1), selected));
        this.slide = new Spring(this.selected);
    }

    public int selected() {
        return selected;
    }

    // for code: slides, no callback
    public void setSelected(int i) {
        if (i < 0 || i >= options.size()) {
            return;
        }
        selected = i;
        slide.set(i, now, Theme.ANIM);
    }

    private float inset() {
        return theme.px(2);
    }

    private float baseSum() {
        float sum = 0f;
        for (float t : textW) {
            sum += t + theme.px(24);
        }
        return sum;
    }

    @Override
    public int preferredWidth() {
        return Math.round(baseSum() + 2 * inset());
    }

    @Override
    public int preferredHeight() {
        return theme.px(Theme.CONTROL_H);
    }

    @Override
    protected void layout() {
        float sum = baseSum();
        float inner = Math.max(0f, w - 2 * inset());
        float cx = 0f;
        for (int i = 0; i < segX.length; i++) {
            float base = textW[i] + theme.px(24);
            segW[i] = sum <= 0f ? 0f : base * inner / sum;
            segX[i] = cx;
            cx += segW[i];
        }
    }

    @Override
    public boolean focusable() {
        return visible && !options.isEmpty();
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        int n = options.size();
        if (n == 0) {
            return;
        }
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        theme.field(c, x, y, w, h, r);
        float pos = slide.value(now);
        int i0 = Math.max(0, Math.min(n - 1, (int) Math.floor(pos)));
        int i1 = Math.min(n - 1, i0 + 1);
        float fr = pos - i0;
        float px = segX[i0] + (segX[i1] - segX[i0]) * fr;
        float pw = segW[i0] + (segW[i1] - segW[i0]) * fr;
        float in = inset();
        // the thumb is the one thing here that floats, so it is the one thing with a shadow
        float tr = Math.max(0f, r - in);
        c.shadow(x + in + px, y + in, pw, h - 2 * in, tr, Theme.fade(theme.color(Theme.SHADOW), 0.4f));
        c.fillRoundRect(x + in + px, y + in, pw, h - 2 * in, tr, theme.color(Theme.SELECTED));
        if (focused) {
            focusRing(c, r, 1f);
        }
        for (int i = 0; i < n; i++) {
            float near = 1f - Math.min(1f, Math.abs(pos - i));
            int col = Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.TEXT), near);
            float tx = x + in + segX[i] + (segW[i] - textW[i]) / 2f;
            c.text(options.get(i), tx, baseline(c, theme.label, y, h), theme.label, col);
        }
    }

    private int segmentAt(float mx) {
        float rel = mx - x - inset();
        for (int i = 0; i < segX.length; i++) {
            if (rel < segX[i] + segW[i]) {
                return i;
            }
        }
        return segX.length - 1;
    }

    private void choose(int i) {
        if (i < 0 || i >= options.size() || i == selected) {
            return;
        }
        selected = i;
        slide.set(i, now, Theme.ANIM);
        Safe.run("segmented", () -> onChange.accept(i));
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my) || button != 0 || options.isEmpty()) {
            return false;
        }
        choose(segmentAt(mx));
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (key == Keys.LEFT) {
            choose(selected - 1);
            return true;
        }
        if (key == Keys.RIGHT) {
            choose(selected + 1);
            return true;
        }
        return false;
    }
}
