package adris.altoclef.ui.gui.kit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

// a scrolling list whose rows are real widgets (the settings tab puts toggles and text fields in rows). it keeps a
// small pool of row widgets and rebinds them to whatever is on screen, so a thousand settings still means about a
// dozen live Toggles. rows outside the view are hidden and unbound, focus included
public class RowWidgets<T> extends VirtualView<T> {

    public abstract static class Row<T> extends Widget {

        protected Row(Theme theme) {
            super(theme);
        }

        // called when this row widget takes over an item. set text, values, whatever the row shows. snap, do not
        // animate, a row that was just recycled should not slide its toggle
        public abstract void bind(T item, int index);
    }

    private final int rowH;
    private final Supplier<? extends Row<T>> factory;
    private final Map<Integer, Row<T>> bound = new HashMap<>();
    private final Map<Integer, Object> boundKeys = new HashMap<>();
    private final Deque<Row<T>> free = new ArrayDeque<>();
    private final List<Widget> active = new ArrayList<>();

    public RowWidgets(Theme theme, int rowHeight, Supplier<? extends Row<T>> factory) {
        super(theme);
        this.rowH = Math.max(1, rowHeight);
        this.factory = factory;
    }

    public int rowHeight() {
        return rowH;
    }

    public int liveRows() {
        return active.size();
    }

    // new data: rebind in place so a list refreshed every tick does not lose the row you are typing in. a row whose
    // item is the same one (by key) and has focus is left alone, its text is the user's
    @Override
    protected void itemsChanged() {
        var it = bound.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int idx = e.getKey();
            Row<T> r = e.getValue();
            if (idx >= items.size()) {
                release(r);
                boundKeys.remove(idx);
                it.remove();
                continue;
            }
            T item = items.get(idx);
            Object k = keyOf(item);
            boolean same = Objects.equals(k, boundKeys.get(idx));
            if (same && hasFocus(r)) {
                continue;
            }
            if (!same) {
                defocus(r);
            }
            boundKeys.put(idx, k);
            Safe.run("row bind", () -> r.bind(item, idx));
        }
        active.clear();
    }

    private static boolean hasFocus(Widget w) {
        if (w.isFocused()) {
            return true;
        }
        for (Widget k : w.children()) {
            if (hasFocus(k)) {
                return true;
            }
        }
        return false;
    }

    private void release(Row<T> r) {
        defocus(r);
        r.setVisible(false);
        free.push(r);
    }

    // a recycled row must not keep focus anywhere inside it, or enter would commit your text to whatever item the
    // row gets bound to next. the shell notices the flag is gone and drops its reference
    private static void defocus(Widget w) {
        w.setFocused(false);
        for (Widget k : w.children()) {
            defocus(k);
        }
    }

    @Override
    protected int contentHeight() {
        return items.size() * rowH;
    }

    @Override
    protected int step() {
        return rowH;
    }

    @Override
    public boolean focusable() {
        return false;
    }

    // unbinds rows that scrolled away, binds rows that scrolled in, and places everyone
    private void sync(long nowNanos) {
        float off = scroller.offset(nowNanos);
        int n = items.size();
        int first = Math.max(0, (int) Math.floor(off / rowH));
        int last = Math.min(n, (int) Math.ceil((off + h) / rowH));
        stampVisible(first, last, nowNanos);
        boolean changed = false;
        var it = bound.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey() < first || e.getKey() >= last) {
                release(e.getValue());
                boundKeys.remove(e.getKey());
                it.remove();
                changed = true;
            }
        }
        for (int i = first; i < last; i++) {
            Row<T> r = bound.get(i);
            boolean fresh = false;
            if (r == null) {
                fresh = true;
                r = free.isEmpty() ? factory.get() : free.pop();
                if (r == null) {
                    continue;
                }
                final Row<T> row = r;
                final int idx = i;
                Safe.run("row bind", () -> row.bind(items.get(idx), idx));
                r.setVisible(true);
                bound.put(i, r);
                boundKeys.put(i, keyOf(items.get(i)));
                changed = true;
            }
            int ry = Math.round(y + i * (float) rowH - off);
            int rw = w - gutter();
            // setBounds re-lays out the whole row, skip it when nothing moved
            if (fresh || r.x() != x || r.y() != ry || r.w() != rw || r.h() != rowH) {
                r.setBounds(x, ry, rw, rowH);
            }
        }
        if (changed || active.size() != bound.size()) {
            active.clear();
            for (int i = first; i < last; i++) {
                Row<T> r = bound.get(i);
                if (r != null) {
                    active.add(r);
                }
            }
        }
    }

    @Override
    public List<Widget> children() {
        sync(now);
        return active;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        sync(now);
        c.pushClip(x, y, w, h);
        try {
            for (Widget r : active) {
                Canvas g = rowCanvas(c, fadeOfRow(r));
                try {
                    r.draw(g, f);
                } catch (Throwable t) {
                    Safe.report("row widget draw", t);
                }
            }
        } finally {
            c.popClip();
        }
        drawEmpty(c);
        drawScrollbar(c);
    }

    private float fadeOfRow(Widget r) {
        for (Map.Entry<Integer, Row<T>> e : bound.entrySet()) {
            if (e.getValue() == r) {
                return fadeOf(items.get(e.getKey()), now);
            }
        }
        return 1f;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).mouseDown(mx, my, button, mods)) {
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (super.mouseUp(mx, my, button, mods)) {
            return true;
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).mouseUp(mx, my, button, mods)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (super.mouseDrag(mx, my, button, dx, dy)) {
            return true;
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).mouseDrag(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        hovered = contains(mx, my);
        Widget deepest = hovered ? this : null;
        for (int i = active.size() - 1; i >= 0; i--) {
            Widget d = active.get(i).mouseMove(mx, my);
            if (d != null && deepest == this) {
                deepest = d;
            }
        }
        return deepest;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        return false;
    }

    @Override
    public void tick() {
        for (Widget r : active) {
            Safe.run("row tick", r::tick);
        }
    }
}
