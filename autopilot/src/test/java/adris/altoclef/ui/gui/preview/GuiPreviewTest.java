package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.tabs.Tabs;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

// the whole panel with every real tab, at 1920x1080 (scale 1): the pictures the owner looks at. each tab's
// own preview test covers its states, this is the final look of each one plus the open animation
public class GuiPreviewTest {

    @Test
    public void everyTab() throws Exception {
        FakeFacade f = new FakeFacade();
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        List<Tab> tabs = new ArrayList<>();
        Preview p = new Preview(1920, 1080, f, pv -> {
            tabs.addAll(Tabs.create(pv.facade, pv.theme, pv.ctx));
            return tabs;
        });
        p.tick();
        assertEquals(8, tabs.size());

        // get: a few things in the basket
        float gx = p.shell.contentX() + 40;
        float gy = p.shell.contentY() + 90;
        p.click(gx, gy);
        p.click(gx, gy, Keys.MOD_SHIFT);
        p.click(gx + 84, gy);
        p.click(gx + 168, gy, Keys.MOD_CTRL);
        p.settle();
        p.advance(300);
        p.save("gui_get");
        p.savePanel("gui_get_panel");
        p.savePanelCutout("gui_get_cutout");

        f.world.following = "Steve";
        for (int i = 1; i < tabs.size(); i++) {
            p.shell.switchTab(i);
            p.tick();
            p.settle();
            p.advance(500);
            p.save("gui_" + tabs.get(i).id());
            p.savePanel("gui_" + tabs.get(i).id() + "_panel");
            p.savePanelCutout("gui_" + tabs.get(i).id() + "_cutout");
        }

        // the same tabs in light, with the same basket and the same follow, so the two sets are twins
        p.shell.setDarkMode(false);
        p.settle();
        p.advance(400);
        for (int i = 0; i < tabs.size(); i++) {
            p.shell.switchTab(i);
            p.tick();
            p.settle();
            p.advance(500);
            p.save("gui_" + tabs.get(i).id() + "_light");
            p.savePanel("gui_" + tabs.get(i).id() + "_light_panel");
            p.savePanelCutout("gui_" + tabs.get(i).id() + "_light_cutout");
        }
        p.shell.setDarkMode(true);
        p.settle();
        p.advance(400);

        // the opening frames, for the feel of the fade
        p.shell.switchTab(0);
        p.shell.open(p.now);
        p.advance(60);
        p.save("gui_opening_60ms");
        p.advance(80);
        p.save("gui_opening_140ms");
    }
}
