package adris.altoclef.ui.gui.kit;

import java.util.function.BiConsumer;

// a scrolling list of fixed height rows where only the rows on screen are drawn or hit tested, so a few thousand items
// cost the same as ten. the renderer draws a row; for rows that hold real controls use RowWidgets instead
public class VirtualList<T> extends VirtualView<T> {

    public interface RowRenderer<T> {
        void draw(Canvas c, Frame f, T item, int index, int x, int y, int w, int h, boolean hovered);
    }

    // first look at a mouse press on a row. return true to keep it, the plain click callback is skipped
    public interface RowMouse<T> {
        boolean down(T item, int index, float mx, float my, int button, int mods);
    }

    private final int rowH;
    private final RowRenderer<T> renderer;
    private BiConsumer<T, Integer> onClick;
    private RowMouse<T> onRowMouseDown;
    private boolean selectable;
    private int selected = -1;

    public VirtualList(Theme theme, int rowHeight, RowRenderer<T> renderer) {
        super(theme);
        this.rowH = Math.max(1, rowHeight);
        this.renderer = renderer;
    }

    public void onClick(BiConsumer<T, Integer> l) {
        onClick = l;
    }

    public void onRowMouseDown(RowMouse<T> l) {
        onRowMouseDown = l;
    }

    // up and down move a selection, enter clicks it
    public void setSelectable(boolean s) {
        selectable = s;
        if (!s) {
            selected = -1;
        }
    }

    public int selected() {
        return selected;
    }

    // the cursor is an index, so an owner that keys its selection by something stabler points it back at the right
    // row after a refresh or a search moved things. out of range means no cursor
    public void setSelected(int index) {
        selected = selectable && index >= 0 && index < items.size() ? index : -1;
    }

    public int rowHeight() {
        return rowH;
    }

    @Override
    protected void itemsChanged() {
        if (selected >= items.size()) {
            selected = items.size() - 1;
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

    // which row the point is over, or -1
    public int indexAt(float mx, float my) {
        if (!contains(mx, my)) {
            return -1;
        }
        int i = (int) Math.floor((my - y + scroller.offset(now)) / rowH);
        return i >= 0 && i < items.size() ? i : -1;
    }

    public void ensureVisible(int index) {
        scroller.ensureVisible(index * (float) rowH, (index + 1) * (float) rowH, now);
    }

    // the middle of a row on screen, scroll taken off: where to click a row that isn't on the first page
    public float rowMidY(int index) {
        return y + (index + 0.5f) * rowH - scroller.offset(now);
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        float off = scroller.offset(now);
        int n = items.size();
        int first = Math.max(0, (int) Math.floor(off / rowH));
        int last = Math.min(n, (int) Math.ceil((off + h) / rowH));
        stampVisible(first, last, now);
        int hoverIdx = hovered && !(scroller.scrollable() && f.mouseX() >= x + w - theme.px(14)) ? indexAt(f.mouseX(), f.mouseY()) : -1;
        int rowW = w - gutter();
        c.pushClip(x, y, w, h);
        for (int i = first; i < last; i++) {
            T item = items.get(i);
            float fade = fadeOf(item, now);
            Canvas g = rowCanvas(c, fade);
            int ry = Math.round(y + i * (float) rowH - off);
            boolean hot = i == hoverIdx || (selectable && focused && i == selected);
            try {
                renderer.draw(g, f, item, i, x, ry, rowW, rowH, hot);
            } catch (Throwable t) {
                Safe.report("list row renderer", t);
            }
        }
        c.popClip();
        drawEmpty(c);
        drawScrollbar(c);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        int i = indexAt(mx, my);
        pressedIndex = -1;
        if (i < 0) {
            return true;
        }
        if (selectable) {
            selected = i;
        }
        if (onRowMouseDown != null) {
            T item = items.get(i);
            boolean taken = Safe.test("list row mouse", () -> onRowMouseDown.down(item, i, mx, my, button, mods));
            if (taken) {
                return true;
            }
        }
        if (button == 0) {
            press(i);
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (super.mouseUp(mx, my, button, mods)) {
            return true;
        }
        int under = indexAt(mx, my);
        boolean hit = button == 0 && releasedOn(under);
        pressedIndex = -1;
        if (hit) {
            fireClick(under);
            return true;
        }
        return false;
    }

    private void fireClick(int i) {
        if (onClick != null && i >= 0 && i < items.size()) {
            T item = items.get(i);
            Safe.run("list click", () -> onClick.accept(item, i));
        }
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        if (selectable && !items.isEmpty()) {
            int n = items.size();
            switch (key) {
                case Keys.DOWN -> selected = Math.min(n - 1, selected + 1);
                case Keys.UP -> selected = Math.max(0, selected < 0 ? 0 : selected - 1);
                case Keys.ENTER, Keys.KP_ENTER -> {
                    fireClick(selected);
                    return true;
                }
                default -> {
                    return super.keyDown(key, mods);
                }
            }
            ensureVisible(selected);
            return true;
        }
        if (key == Keys.DOWN) {
            scroller.scrollBy(rowH, now);
            return true;
        }
        if (key == Keys.UP) {
            scroller.scrollBy(-rowH, now);
            return true;
        }
        return super.keyDown(key, mods);
    }
}
