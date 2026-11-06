package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the product shots: every tab in both appearances, the panel alone on a transparent surround with its shadow kept
// in the alpha, at 2x so they survive being tilted on a page. populated wherever the fake data allows, nobody
// photographs an empty basket. same package as the tabs so the go to fields are reachable
public class SitePreviewTest {

    @After
    public void tearDown() {
        GotoRecent.clear();
    }

    @Test
    public void everyTabInBothAppearances() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            shoot(dark);
        }
    }

    private void shoot(boolean dark) throws Exception {
        FakeFacade f = new FakeFacade();
        f.status.activity = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", null);
        f.stats = () -> List.of(new StatsAccess.Stat("Distance travelled", "12.4 km"),
                new StatsAccess.Stat("Blocks mined", "1 234"), new StatsAccess.Stat("Blocks placed", "680"),
                new StatsAccess.Stat("Mobs killed", "42"), new StatsAccess.Stat("Jumps", "207"),
                new StatsAccess.Stat("Time played", "3h 12m"), new StatsAccess.Stat("Deaths", "2"),
                new StatsAccess.Stat("Items crafted", "360"), new StatsAccess.Stat("Damage dealt", "2 100"));
        f.world.following = "Steve";
        // three players so the players card closes above the entities instead of being cut off, and a position a
        // real walk away from the typed destination so the hint under it is not "1 block"
        while (f.world.players.size() > 3) {
            f.world.players.remove(f.world.players.size() - 1);
        }
        f.world.pos = new WorldAccess.Position(-212.5, 70.0, 418.5);
        GotoRecent.clear();
        GotoRecent.push(402, 23, 1190, "overworld");
        GotoRecent.push(-1205, null, 388, "overworld");

        List<Tab> tabs = new ArrayList<>();
        // 2x: the window is 4k and the scale is forced to 2, so the panel is exactly twice the 1080p one
        Preview p = new Preview(3840, 2160, 2f, f, pv -> {
            tabs.addAll(Tabs.create(pv.facade, pv.theme, pv.ctx));
            return tabs;
        });
        assertEquals(8, tabs.size());
        p.shell.setDarkMode(dark);
        p.tick();
        p.settle();
        p.advance(400);
        String theme = dark ? "dark" : "light";
        int pad = 48;

        // get: a few things in the basket
        float gx = p.shell.contentX() + p.theme.px(40);
        float gy = p.shell.contentY() + p.theme.px(90);
        p.click(gx, gy);
        p.click(gx, gy, Keys.MOD_SHIFT);
        p.click(gx + p.theme.px(84), gy);
        p.click(gx + p.theme.px(168), gy, Keys.MOD_CTRL);
        p.hover(-100, -100);
        p.settle();
        p.advance(400);
        // a product shot of an empty basket would be a green test and a wrong picture
        assertTrue("basket filled", ((GetTab) tabs.get(0)).panel().goButton().enabled());
        p.savePanelCutout("site/get_" + theme, pad);

        // go to: a destination typed in, recent trips under it
        p.shell.switchTab(1);
        p.tick();
        GotoTab goTo = (GotoTab) tabs.get(1);
        TextField xBox = goTo.fieldAt(0).field();
        p.click(xBox.x() + p.theme.px(10), xBox.y() + xBox.h() / 2f);
        p.type("120");
        TextField zBox = goTo.fieldAt(2).field();
        p.click(zBox.x() + p.theme.px(10), zBox.y() + zBox.h() / 2f);
        p.type("-30");
        p.shell.blur();
        p.hover(-100, -100);
        p.settle();
        p.advance(400);
        assertEquals("120", xBox.text());
        assertEquals("-30", zBox.text());
        p.savePanelCutout("site/goto_" + theme, pad);

        for (int i = 2; i < tabs.size(); i++) {
            p.shell.switchTab(i);
            p.tick();
            p.settle();
            p.advance(500);
            p.savePanelCutout("site/" + tabs.get(i).id() + "_" + theme, pad);
        }
    }
}
