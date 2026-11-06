package adris.altoclef.ui.gui.kit;

// the item grid: as many columns as fit, cells stretched to fill the width, scrolling by whole cell rows. same
// visible-only drawing as VirtualList, and a click hands back the item plus the shift and ctrl state
public class VirtualGrid<T> extends VirtualView<T> {

    public interface CellRenderer<T> {
        void draw(Canvas c, Frame f, T item, int index, int x, int y, int w, int h, boolean hovered);
    }

    // the click position and the cell's rect go along, so a cell can have a hot zone inside it (the pin button)
    public interface CellClick<T> {
        void click(T item, int index, int mods, float mx, float my, int cellX, int cellY, int cellW, int cellH);
    }

    private final int cellW;
    private final int cellH;
    private final CellRenderer<T> renderer;
    private CellClick<T> onClick;

    public VirtualGrid(Theme theme, int cellW, int cellH, CellRenderer<T> renderer) {
        super(theme);
        this.cellW = Math.max(1, cellW);
        this.cellH = Math.max(1, cellH);
        this.renderer = renderer;
        this.wheelRows = 2;
    }

    public void onClick(CellClick<T> l) {
        onClick = l;
    }

    public int columns() {
        return Math.max(1, (w - gutter()) / cellW);
    }

    private int cellWidth() {
        return Math.max(1, (w - gutter()) / columns());
    }

    @Override
    protected int contentHeight() {
        int cols = columns();
        return ((items.size() + cols - 1) / cols) * cellH;
    }

    public int cellHeight() {
        return cellH;
    }

    @Override
    protected int step() {
        return cellH;
    }

    public int indexAt(float mx, float my) {
        if (!contains(mx, my) || mx >= x + cellWidth() * columns()) {
            return -1;
        }
        int col = (int) ((mx - x) / cellWidth());
        int row = (int) Math.floor((my - y + scroller.offset(now)) / cellH);
        int i = row * columns() + col;
        return row >= 0 && col >= 0 && i < items.size() ? i : -1;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        float off = scroller.offset(now);
        int cols = columns();
        int cw = cellWidth();
        int n = items.size();
        int firstRow = Math.max(0, (int) Math.floor(off / cellH));
        int lastRow = (int) Math.ceil((off + h) / cellH);
        int first = firstRow * cols;
        int last = Math.min(n, lastRow * cols);
        stampVisible(first, last, now);
        int hoverIdx = hovered ? indexAt(f.mouseX(), f.mouseY()) : -1;
        c.pushClip(x, y, w, h);
        for (int i = first; i < last; i++) {
            T item = items.get(i);
            Canvas g = rowCanvas(c, fadeOf(item, now));
            int cx = x + (i % cols) * cw;
            int cy = Math.round(y + (i / cols) * (float) cellH - off);
            try {
                renderer.draw(g, f, item, i, cx, cy, cw, cellH, i == hoverIdx);
            } catch (Throwable t) {
                Safe.report("grid cell renderer", t);
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
        press(button == 0 ? indexAt(mx, my) : -1);
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
        if (hit && onClick != null) {
            T item = items.get(under);
            int cols = columns();
            int cw = cellWidth();
            int cx = x + (under % cols) * cw;
            int cy = Math.round(y + (under / cols) * (float) cellH - scroller.offset(now));
            int finalCx = cx;
            int finalCy = cy;
            Safe.run("grid click", () -> onClick.click(item, under, mods, mx, my, finalCx, finalCy, cw, cellH));
            return true;
        }
        return false;
    }
}
