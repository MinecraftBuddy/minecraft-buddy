package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;

// the go to tab in every state worth looking at, written to build/gui-preview. same package as the tab so the test
// hooks are reachable
public class GotoTabPreviewTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final AtomicLong CLOCK = new AtomicLong(NOW);

    private Preview p;
    private GotoTab tab;

    private void open(int w, int h) throws Exception {
        GotoRecent.clear();
        GotoRecent.clock = CLOCK::get;
        GotoTab[] holder = new GotoTab[1];
        p = new Preview(w, h, new FakeFacade(), pv -> {
            holder[0] = new GotoTab(pv.theme, pv.facade, pv.ctx);
            return List.<Tab>of(holder[0]);
        });
        tab = holder[0];
    }

    @After
    public void tearDown() {
        GotoRecent.clear();
        GotoRecent.clock = System::currentTimeMillis;
    }

    private static void push(Integer x, Integer y, Integer z, String dim, long agoMillis) {
        CLOCK.set(NOW - agoMillis);
        GotoRecent.push(x, y, z, dim);
        CLOCK.set(NOW);
    }

    private void focusBox(int i) {
        TextField f = tab.fieldAt(i).field();
        p.click(f.x() + 10, f.y() + f.h() / 2f);
    }

    private void clear() {
        p.click(tab.clearButton().x() + 10, tab.clearButton().y() + 10);
        p.hover(-100, -100);
        // a frame in between, or the press flash starts fading at the screenshot
        p.settle();
        p.advance(250);
    }

    private void seedRecent() {
        // oldest first, the list shows the newest on top
        push(402, 23, 1190, "overworld", 26 * 3_600_000L);
        push(88, 12, -64, "the_nether", 3 * 3_600_000L);
        push(-1205, null, 388, "overworld", 14 * 60_000);
        push(120, 64, -30, "overworld", 2 * 60_000);
        push(null, 70, null, "overworld", 40_000);
    }

    @Test
    public void states() throws Exception {
        open(1600, 900);
        tab.onShown();
        p.settle();

        // the empty state: the path draws itself once, then rests
        p.savePanel("goto_motif_start");
        p.advance(110);
        p.savePanel("goto_motif_mid");
        p.advance(500);
        p.savePanel("goto_default");

        // a relative x, a y, a z: the line says how far
        focusBox(0);
        p.type("~10");
        focusBox(1);
        p.type("64");
        focusBox(2);
        p.type("-30");
        p.tick();
        p.hover(-100, -100);
        p.savePanel("goto_filled_relative");

        // focus in y with its placeholder
        clear();
        focusBox(1);
        p.savePanel("goto_focus_y");

        // a mistake
        tab.fieldAt(0).setValue("120");
        tab.fieldAt(1).setValue("abc");
        tab.fieldAt(2).setValue("-30");
        p.tick();
        p.hover(-100, -100);
        p.advance(250);
        p.savePanel("goto_error");

        // half an answer after pressing enter
        clear();
        tab.fieldAt(0).setValue("120");
        p.tick();
        focusBox(0);
        p.key(Keys.ENTER);
        p.savePanel("goto_missing_z");

        // recent list, one row hovered, then the row's own go
        clear();
        seedRecent();
        tab.onShown();
        p.settle();
        p.advance(300);
        p.savePanel("goto_recent");
        p.hover(tab.recentList().x() + 120, tab.recentList().y() + p.theme.px(36) + 18);
        p.advance(200);
        p.savePanel("goto_recent_hover");
        int[] z = tab.goZone(1);
        p.hover(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        p.advance(200);
        p.savePanel("goto_recent_go_hover");

        // filled from a row
        p.click(tab.recentList().x() + 120, tab.recentList().y() + p.theme.px(18));
        p.hover(-100, -100);
        p.advance(200);
        p.savePanel("goto_filled_from_recent");
        // the previews swallow draw errors on purpose, so at least prove the files are real
        assertTrue(Files.size(Preview.OUT.resolve("goto_filled_from_recent.png")) > 1000);
        assertTrue(Files.size(Preview.OUT.resolve("goto_default.png")) > 1000);
    }

    @Test
    public void smallWindow() throws Exception {
        open(960, 540);
        seedRecent();
        tab.onShown();
        p.settle();
        p.advance(400);
        p.save("goto_960");
        clear();
        tab.fieldAt(0).setValue("~10");
        tab.fieldAt(2).setValue("-30");
        p.tick();
        p.advance(200);
        p.save("goto_960_filled");
        GotoRecent.clear();
        tab.onShown();
        p.tick();
        p.advance(500);
        p.save("goto_960_empty");
    }
}
