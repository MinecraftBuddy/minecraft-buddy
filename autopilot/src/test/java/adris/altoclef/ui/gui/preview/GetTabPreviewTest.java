package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.kit.VirtualGrid;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.tabs.GetTab;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the get tab inside the real shell, rendered to pngs. child order of the content is seg, category, strip, grid, panel
// and the panel's is list, clear, go, limit minus, limit box, limit plus: that is public api, so the test can find things without reaching into the tab
public class GetTabPreviewTest {

    private static final int SHIFT = 1;
    private static final int CTRL = 2;

    static final class Rig {
        final Preview p;
        final FakeFacade f;
        GetTab tab;

        Rig(int w, int h) throws Exception {
            GetTab.resetSession();
            f = new FakeFacade();
            p = new Preview(w, h, f, pv -> {
                tab = new GetTab(pv.theme, pv.facade, pv.ctx);
                return List.<Tab>of(tab);
            });
            p.settle();
        }

        Widget seg() {
            return tab.content().children().get(0);
        }

        Widget strip() {
            return tab.content().children().get(2);
        }

        Widget grid() {
            return tab.content().children().get(3);
        }

        Widget panel() {
            return tab.content().children().get(4);
        }

        Widget list() {
            return panel().children().get(0);
        }

        Widget go() {
            return panel().children().get(2);
        }

        Widget limitBox() {
            return panel().children().get(4);
        }

        void tile(int i, int mods) {
            Widget g = grid();
            int cols = ((VirtualGrid<?>) g).columns();
            int cwReal = (g.w() - p.theme.px(10)) / cols;
            int ch = ((VirtualGrid<?>) g).cellHeight();
            p.click(g.x() + (i % cols) * cwReal + cwReal / 2f, g.y() + (i / cols) * ch + ch / 2f, mods);
        }

        void clickAt(Widget w) {
            p.click(w.x() + w.w() / 2f, w.y() + w.h() / 2f);
        }

        Widget row(int i) {
            p.settle();
            return list().children().get(i);
        }

        void hoverRow(int i) {
            Widget r = row(i);
            p.hover(r.x() + r.w() / 3f, r.y() + r.h() / 2f);
            // the hover fade starts on the first frame that sees it
            p.settle();
            p.advance(200);
        }

        void mine() {
            Widget s = seg();
            p.click(s.x() + s.w() - p.theme.px(14), s.y() + s.h() / 2f);
            p.settle();
            p.advance(300);
        }

        void fill() {
            tile(13, SHIFT);
            tile(0, SHIFT);
            tile(2, 0);
            tile(2, 0);
            tile(2, 0);
            tile(3, CTRL);
            tile(5, 0);
            // new basket rows fade in from their first drawn frame
            p.settle();
            p.advance(300);
        }
    }

    @Test
    public void emptyBasketDefault() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.p.advance(300);
        r.p.savePanel("get_default");
    }

    @Test
    public void basketWithFourLinesAndAHoveredRow() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.tile(13, SHIFT);
        r.tile(0, SHIFT);
        r.tile(2, 0);
        r.tile(2, 0);
        r.tile(2, 0);
        r.tile(3, CTRL);
        r.p.settle();
        r.p.advance(300);
        r.hoverRow(1);
        r.p.savePanel("get_basket_hover");
        assertEquals(4, ((RowWidgets<?>) r.list()).items().size());
    }

    @Test
    public void typingACountInARow() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.fill();
        Widget row = r.row(1);
        Widget count = row.children().get(1);
        r.p.click(count.x() + count.w() / 2f, count.y() + count.h() / 2f);
        r.p.shell.keyDown(Keys.A, CTRL);
        r.p.type("12");
        r.p.advance(200);
        r.p.savePanel("get_basket_editing");
        r.p.key(Keys.ENTER);
        r.p.advance(200);
        r.p.savePanel("get_basket_committed");
    }

    @Test
    public void searchWithResults() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.fill();
        r.p.type("iron");
        r.p.advance(300);
        r.p.savePanel("get_search_iron");
        assertTrue(r.tab.id().equals("get"));
    }

    @Test
    public void searchWithNoResults() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.p.type("zzqx");
        r.p.advance(300);
        r.p.savePanel("get_search_none");
    }

    @Test
    public void mineMode() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.mine();
        r.p.savePanel("get_mine_empty");
        r.tile(3, 0);
        r.tile(0, 0);
        r.tile(6, 0);
        r.tile(8, 0);
        Widget box = r.limitBox();
        r.p.click(box.x() + box.w() / 2f, box.y() + box.h() / 2f);
        r.p.type("200");
        r.p.key(Keys.ENTER);
        r.p.settle();
        r.p.advance(300);
        r.hoverRow(0);
        r.p.savePanel("get_mine");
        r.p.click(r.go().x() + 10, r.go().y() + 10);
        r.p.settle();
        r.p.advance(400);
        r.p.savePanel("get_mine_sent");
        assertEquals(1, r.f.actions.size());
        assertTrue(r.f.actions.get(0).startsWith("mine "));
    }

    @Test
    public void aLongBasketScrollsAndFourDigitsFit() throws Exception {
        Rig r = new Rig(1920, 1080);
        for (int i = 0; i < 22; i++) {
            r.tile(i, i % 3 == 0 ? CTRL : 0);
        }
        r.p.settle();
        r.p.advance(300);
        Widget row = r.row(0);
        Widget count = row.children().get(1);
        r.p.click(count.x() + count.w() / 2f, count.y() + count.h() / 2f);
        r.p.shell.keyDown(Keys.A, CTRL);
        r.p.type("9999");
        r.p.key(Keys.ENTER);
        r.p.settle();
        r.p.advance(300);
        r.p.savePanel("get_long_basket_9999");
        Widget list = r.list();
        r.p.scroll(list.x() + 40, list.y() + 40, -6);
        r.p.advance(300);
        r.hoverRow(1);
        r.p.savePanel("get_long_basket_scrolled");
    }

    @Test
    public void typingThenClickingAnotherLineGoesThroughTheRealShell() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.tile(0, 0);
        r.tile(1, 0);
        r.p.settle();
        r.p.advance(300);
        Widget row0 = r.row(0);
        Widget count = row0.children().get(1);
        r.p.click(count.x() + count.w() / 2f, count.y() + count.h() / 2f);
        r.p.shell.keyDown(Keys.A, CTRL);
        r.p.type("30");
        // clicking a plus on the other line blurs the box first, so the 30 lands before the step
        Widget row1 = r.row(1);
        r.p.hover(row1.x() + 20, row1.y() + row1.h() / 2f);
        Widget plus = row1.children().get(2);
        r.p.click(plus.x() + plus.w() / 2f, plus.y() + plus.h() / 2f);
        r.p.tick();
        r.p.settle();
        r.p.advance(300);
        r.p.savePanel("get_edit_then_step");
        // both lines are in the basket with the typed 30 and the stepped 2
        r.clickAt(r.go());
        assertEquals(1, r.f.actions.size());
        assertTrue(r.f.actions.get(0), r.f.actions.get(0).matches("get \\S+ 30 \\S+ 2"));
    }

    @Test
    public void mineModeAtTheSmallAndBigSizes() throws Exception {
        for (int[] size : new int[][] {{960, 540}, {3840, 2160}}) {
            Rig r = new Rig(size[0], size[1]);
            r.mine();
            r.tile(1, 0);
            r.tile(3, 0);
            Widget box = r.limitBox();
            r.p.click(box.x() + box.w() / 2f, box.y() + box.h() / 2f);
            r.p.type("64");
            r.p.key(Keys.ENTER);
            r.p.settle();
            r.p.advance(300);
            r.hoverRow(0);
            r.p.savePanel("get_mine_" + size[0] + "x" + size[1]);
        }
    }

    @Test
    public void bigWindow() throws Exception {
        Rig r = new Rig(3840, 2160);
        r.fill();
        r.p.advance(300);
        r.hoverRow(0);
        r.p.savePanel("get_3840x2160");
    }

    @Test
    public void afterSendingTheToastShowsAndRecentAppears() throws Exception {
        Rig r = new Rig(1920, 1080);
        r.fill();
        r.p.advance(200);
        r.clickAt(r.go());
        r.p.settle();
        r.p.advance(400);
        r.p.savePanel("get_after_send");
        assertEquals(1, r.f.actions.size());
        assertTrue(r.f.actions.get(0).startsWith("get "));
    }

    @Test
    public void smallWindow() throws Exception {
        Rig r = new Rig(960, 540);
        r.fill();
        r.p.advance(300);
        r.hoverRow(0);
        r.p.savePanel("get_small_960x540");
    }

    @Test
    public void midSizeWindow() throws Exception {
        Rig r = new Rig(1600, 900);
        r.fill();
        r.p.advance(300);
        r.p.savePanel("get_1600x900");
    }
}
