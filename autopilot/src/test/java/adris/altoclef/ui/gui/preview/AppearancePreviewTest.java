package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.tabs.Tabs;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AppearancePreviewTest {

    @Test
    public void everyTabInBothAppearances() throws Exception {
        List<Tab> tabs = new ArrayList<>();
        Preview p = new Preview(1920, 1080, new FakeFacade(), pv -> {
            tabs.addAll(Tabs.create(pv.facade, pv.theme, pv.ctx));
            return tabs;
        });
        assertTrue(p.theme.dark());
        assertEquals(1, p.shell.appearance().selected());
        for (boolean dark : new boolean[]{true, false}) {
            p.shell.setDarkMode(dark);
            for (int i = 0; i < tabs.size(); i++) {
                p.shell.switchTab(i);
                p.tick();
                p.settle();
                p.advance(500);
                p.savePanel("gui_" + (dark ? "dark_" : "light_") + tabs.get(i).id());
            }
        }
    }

    @Test
    public void appearanceControlPreservesTheActiveSearchAndReportsTheChoice() throws Exception {
        Preview p = new Preview(new FakeFacade(), pv -> Tabs.create(pv.facade, pv.theme, pv.ctx));
        List<Boolean> saved = new ArrayList<>();
        p.shell.setOnAppearanceChanged(saved::add);
        p.shell.focus(p.shell.search());
        p.type("iron");
        Segmented appearance = p.shell.appearance();
        p.click(appearance.x() + appearance.w() / 4f, appearance.y() + appearance.h() / 2f);
        assertFalse(p.theme.dark());
        assertEquals(List.of(false), saved);
        assertEquals("iron", p.shell.search().text());
        assertEquals(0, p.shell.activeIndex());
        // the segmented control is in the keyboard focus order too
        p.shell.focus(appearance);
        p.key(Keys.RIGHT);
        assertTrue(p.theme.dark());
        assertEquals(List.of(false, true), saved);
    }

    @Test
    public void smallWindowKeepsAppearanceBelowTheTabs() throws Exception {
        Preview p = new Preview(960, 540, new FakeFacade(), pv -> Tabs.create(pv.facade, pv.theme, pv.ctx));
        int tabsBottom = p.shell.tabRowY(6) + p.shell.tabRowH();
        assertTrue(tabsBottom < p.shell.appearance().y() - p.theme.px(20));
        assertTrue(p.shell.appearance().y() + p.shell.appearance().h() < p.shell.keyCapture().y());
        p.settle();
        p.savePanel("gui_dark_small");
        p.shell.setDarkMode(false);
        p.advance(500);
        p.savePanel("gui_light_small");
    }

    @Test
    public void minimumResizeKeepsNavigationClearOfAppearance() throws Exception {
        Preview p = new Preview(1920, 1080, new FakeFacade(), pv -> Tabs.create(pv.facade, pv.theme, pv.ctx));
        p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(360));
        int bottom = p.shell.tabRowY(6) + p.shell.tabRowH();
        assertTrue(bottom < p.shell.appearance().y() - p.theme.px(20));
        p.settle();
        p.savePanel("gui_minimum_size");
    }

    @Test
    public void appearanceAndTabMotionFrames() throws Exception {
        Preview p = new Preview(new FakeFacade(), pv -> Tabs.create(pv.facade, pv.theme, pv.ctx));
        // settings, the last tab
        p.shell.switchTab(7);
        p.settle();
        p.advance(500);
        p.settle();
        p.shell.setDarkMode(false);
        for (int i = 0; i < 20; i++) {
            p.savePanel(String.format("appearance_motion_%02d", i));
            p.advance(20);
        }
        p.shell.switchTab(0);
        for (int i = 20; i < 40; i++) {
            p.savePanel(String.format("appearance_motion_%02d", i));
            p.advance(20);
        }
        assertFalse(p.theme.dark());
    }
}
