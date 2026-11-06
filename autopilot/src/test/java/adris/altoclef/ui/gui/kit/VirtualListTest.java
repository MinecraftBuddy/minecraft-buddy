package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class VirtualListTest {

    private static final long MS = 1_000_000L;
    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);

    private static List<Integer> numbers(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private void draw(Widget w, long ms) {
        w.draw(canvas, new Frame(ms * MS, 0.016f, -1, -1));
    }

    private static final class Probe implements VirtualList.RowRenderer<Integer> {
        final List<Integer> drawn = new ArrayList<>();
        final List<Canvas> canvases = new ArrayList<>();
        final List<Boolean> hot = new ArrayList<>();

        @Override
        public void draw(Canvas c, Frame f, Integer item, int index, int x, int y, int w, int h, boolean hovered) {
            drawn.add(index);
            canvases.add(c);
            hot.add(hovered);
        }

        void reset() {
            drawn.clear();
            canvases.clear();
            hot.clear();
        }
    }

    private VirtualList<Integer> list(Probe p, int count) {
        VirtualList<Integer> l = new VirtualList<>(theme, 36, p);
        l.setBounds(0, 0, 400, 300);
        l.setItems(numbers(count));
        return l;
    }

    @Test
    public void onlyVisibleRowsAreRendered() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 5000);
        draw(l, 1000);
        assertEquals(9, p.drawn.size());
        assertEquals(0, (int) p.drawn.get(0));
        assertEquals(8, (int) p.drawn.get(8));
        assertEquals(0, canvas.clipDepth);
    }

    @Test
    public void aPartlyScrolledListDrawsTheEdgeRowsToo() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 5000);
        l.scroller().snapTo(30);
        p.reset();
        draw(l, 1000);
        assertEquals(0, (int) p.drawn.get(0));
        assertEquals(9, (int) p.drawn.get(p.drawn.size() - 1));
        assertEquals(10, p.drawn.size());
    }

    @Test
    public void wheelScrollsThreeRowsPerNotch() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 5000);
        draw(l, 1000);
        assertTrue(l.scroll(10, 10, -1f));
        draw(l, 2000);
        p.reset();
        draw(l, 2000);
        assertEquals(3, (int) p.drawn.get(0));
        assertEquals(108f, l.scroller().target(), 0f);
        l.scroll(10, 10, 1f);
        assertEquals(0f, l.scroller().target(), 0f);
    }

    @Test
    public void wheelOutsideIsNotTaken() {
        VirtualList<Integer> l = list(new Probe(), 5000);
        assertFalse(l.scroll(900, 900, -1f));
    }

    @Test
    public void aShortListLetsTheWheelPassThrough() {
        VirtualList<Integer> l = list(new Probe(), 3);
        assertFalse(l.scroll(10, 10, -1f));
    }

    @Test
    public void ensureVisibleBringsTheRowIn() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 5000);
        l.ensureVisible(50);
        draw(l, 5000);
        p.reset();
        draw(l, 5000);
        assertTrue(p.drawn.contains(50));
        assertFalse(p.drawn.contains(0));
    }

    @Test
    public void clickingARowFiresOnClickWithTheItem() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 100);
        List<String> clicks = new ArrayList<>();
        l.onClick((item, i) -> clicks.add(item + "@" + i));
        draw(l, 1000);
        assertTrue(l.mouseDown(10, 80, 0, 0));
        assertTrue(l.mouseUp(10, 80, 0, 0));
        assertEquals(List.of("2@2"), clicks);
    }

    @Test
    public void releasingOnAnotherRowIsNotAClick() {
        VirtualList<Integer> l = list(new Probe(), 100);
        List<String> clicks = new ArrayList<>();
        l.onClick((item, i) -> clicks.add(item + "@" + i));
        draw(l, 1000);
        l.mouseDown(10, 80, 0, 0);
        l.mouseUp(10, 200, 0, 0);
        assertTrue(clicks.isEmpty());
    }

    @Test
    public void rowMouseDownCanKeepTheClick() {
        VirtualList<Integer> l = list(new Probe(), 100);
        List<String> clicks = new ArrayList<>();
        List<Integer> downs = new ArrayList<>();
        l.onClick((item, i) -> clicks.add("click"));
        l.onRowMouseDown((item, i, mx, my, b, m) -> {
            downs.add(i);
            return mx > 300;
        });
        draw(l, 1000);
        l.mouseDown(350, 10, 0, 0);
        l.mouseUp(350, 10, 0, 0);
        assertTrue(clicks.isEmpty());
        l.mouseDown(10, 10, 0, 0);
        l.mouseUp(10, 10, 0, 0);
        assertEquals(List.of("click"), clicks);
        assertEquals(List.of(0, 0), downs);
    }

    @Test
    public void aThrowingRendererOrClickDoesNotEscape() {
        VirtualList<Integer> l = new VirtualList<>(theme, 36, (c, f, item, i, x, y, w, h, hot) -> {
            throw new IllegalStateException("bad row");
        });
        l.setBounds(0, 0, 400, 300);
        l.setItems(numbers(10));
        draw(l, 1000);
        assertEquals(0, canvas.clipDepth);
        l.onClick((item, i) -> {
            throw new IllegalStateException("bad click");
        });
        l.mouseDown(10, 10, 0, 0);
        l.mouseUp(10, 10, 0, 0);
    }

    @Test
    public void hoveredRowIsFlagged() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 100);
        draw(l, 1000);
        l.mouseMove(10, 80);
        p.reset();
        l.draw(canvas, new Frame(1100 * MS, 0.016f, 10, 80));
        assertTrue(p.hot.get(2));
        assertFalse(p.hot.get(1));
    }

    @Test
    public void newRowsFadeInAndSettle() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 100);
        draw(l, 1000);
        assertNotSame(canvas, p.canvases.get(0));
        p.reset();
        draw(l, 1000 + 200);
        assertSame(canvas, p.canvases.get(0));
    }

    @Test
    public void rowsThatWereAlreadyThereDoNotFadeAgain() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 100);
        draw(l, 1000);
        draw(l, 1500);
        l.setItems(numbers(100));
        p.reset();
        draw(l, 1600);
        assertSame(canvas, p.canvases.get(0));
        l.setItems(numbers(120));
        p.reset();
        draw(l, 1700);
        assertSame(canvas, p.canvases.get(0));
    }

    @Test
    public void rowsThatScrollIntoViewAfterAFilterDoNotFade() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 500);
        draw(l, 1000);
        draw(l, 2000);
        l.scroller().snapTo(36 * 100);
        p.reset();
        draw(l, 3000);
        assertSame(canvas, p.canvases.get(0));
    }

    @Test
    public void reducedMotionSkipsTheFade() {
        Anim.enabled = false;
        try {
            Probe p = new Probe();
            VirtualList<Integer> l = list(p, 100);
            draw(l, 1000);
            assertSame(canvas, p.canvases.get(0));
        } finally {
            Anim.enabled = true;
        }
    }

    @Test
    public void pageKeysAndHomeEndWhenFocused() {
        VirtualList<Integer> l = list(new Probe(), 1000);
        l.setFocused(true);
        draw(l, 1000);
        assertTrue(l.keyDown(Keys.PAGE_DOWN, 0));
        assertEquals(264f, l.scroller().target(), 0f);
        assertTrue(l.keyDown(Keys.END, 0));
        assertEquals(36000f - 300f, l.scroller().target(), 0f);
        assertTrue(l.keyDown(Keys.HOME, 0));
        assertEquals(0f, l.scroller().target(), 0f);
        l.setFocused(false);
        assertFalse(l.keyDown(Keys.PAGE_DOWN, 0));
    }

    @Test
    public void selectableListMovesWithArrowsAndEnterClicks() {
        VirtualList<Integer> l = list(new Probe(), 100);
        l.setSelectable(true);
        l.setFocused(true);
        List<Integer> clicks = new ArrayList<>();
        l.onClick((item, i) -> clicks.add(i));
        l.keyDown(Keys.DOWN, 0);
        l.keyDown(Keys.DOWN, 0);
        assertEquals(1, l.selected());
        l.keyDown(Keys.ENTER, 0);
        assertEquals(List.of(1), clicks);
        for (int i = 0; i < 20; i++) {
            l.keyDown(Keys.DOWN, 0);
        }
        assertTrue(l.scroller().target() > 0f);
    }

    @Test
    public void draggingTheThumbScrolls() {
        VirtualList<Integer> l = list(new Probe(), 1000);
        draw(l, 1000);
        float barX = 400 - 6;
        assertTrue(l.mouseDown(barX, 10, 0, 0));
        assertTrue(l.mouseDrag(barX, 40, 0, 0, 30));
        assertTrue(l.scroller().target() > 100f);
        l.mouseUp(barX, 40, 0, 0);
        assertFalse(l.mouseDrag(barX, 60, 0, 0, 20));
    }

    @Test
    public void emptyTextShowsWhenThereAreNoItems() {
        Probe p = new Probe();
        VirtualList<Integer> l = list(p, 0);
        l.setEmptyText("Nothing matches");
        draw(l, 1000);
        assertTrue(canvas.texts().contains("Nothing matches"));
    }

    @Test
    public void gridColumnsFollowTheWidthAndOnlyVisibleCellsDraw() {
        List<Integer> drawn = new ArrayList<>();
        VirtualGrid<Integer> g = new VirtualGrid<>(theme, 100, 80, (c, f, item, i, x, y, w, h, hot) -> drawn.add(i));
        g.setBounds(0, 0, 410, 240);
        g.setItems(numbers(5000));
        assertEquals(4, g.columns());
        draw(g, 1000);
        assertEquals(12, drawn.size());
        List<String> clicks = new ArrayList<>();
        g.onClick((item, i, mods, mx, my, cx, cy, cw, ch) -> clicks.add(item + "/" + mods));
        g.mouseDown(150, 100, 0, Keys.MOD_SHIFT);
        g.mouseUp(150, 100, 0, Keys.MOD_SHIFT);
        assertEquals(List.of("5/1"), clicks);
        g.scroll(10, 10, -1f);
        assertEquals(160f, g.scroller().target(), 0f);
    }

    @Test
    public void rowWidgetsKeepASmallPoolAndRebindOnScroll() {
        int[] made = {0};
        List<Integer> bound = new ArrayList<>();
        RowWidgets<Integer> rw = new RowWidgets<>(theme, 36, () -> {
            made[0]++;
            return new RowWidgets.Row<Integer>(theme) {
                @Override
                public void bind(Integer item, int index) {
                    bound.add(index);
                }

                @Override
                public void draw(Canvas c, Frame f) {
                }
            };
        });
        rw.setBounds(0, 0, 400, 300);
        rw.setItems(numbers(1000));
        draw(rw, 1000);
        assertEquals(9, rw.liveRows());
        for (int i = 0; i < 100; i++) {
            rw.scroller().snapTo(i * 40);
            draw(rw, 1000 + i);
        }
        assertTrue("pool grew to " + made[0], made[0] <= 12);
        assertTrue(rw.liveRows() <= 10);
        assertTrue(bound.size() > 100);
    }

    @Test
    public void rowWidgetsRouteClicksToTheRowUnderThePointer() {
        List<String> clicks = new ArrayList<>();
        RowWidgets<Integer> rw = new RowWidgets<>(theme, 36, () -> new RowWidgets.Row<Integer>(theme) {
            private int idx;
            private final Button b = new Button(theme, "go", () -> clicks.add("row " + idx));

            @Override
            public void bind(Integer item, int index) {
                idx = index;
            }

            @Override
            protected void layout() {
                b.setBounds(x, y, 50, h);
            }

            @Override
            public List<Widget> children() {
                return List.of(b);
            }

            @Override
            public void draw(Canvas c, Frame f) {
                b.draw(c, f);
            }
        });
        rw.setBounds(0, 0, 400, 300);
        rw.setItems(numbers(100));
        draw(rw, 1000);
        rw.mouseDown(10, 80, 0, 0);
        rw.mouseUp(10, 80, 0, 0);
        assertEquals(List.of("row 2"), clicks);
        List<Widget> focusable = new ArrayList<>();
        rw.collectFocusable(focusable);
        assertEquals(9, focusable.size());
    }
}
