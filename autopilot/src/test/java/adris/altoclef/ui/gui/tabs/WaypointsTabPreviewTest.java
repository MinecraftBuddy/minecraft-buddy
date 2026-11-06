package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertTrue;

// the waypoints tab in every state worth looking at, written to build/gui-preview
public class WaypointsTabPreviewTest {

    private FakeFacade facade;
    private Preview p;
    private WaypointsTab tab;

    private void open(int w, int h) throws Exception {
        facade = new FakeFacade();
        WaypointsTab[] holder = new WaypointsTab[1];
        p = new Preview(w, h, facade, pv -> {
            holder[0] = new WaypointsTab(pv.theme, pv.facade, pv.ctx);
            return List.<Tab>of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
        p.advance(300);
    }

    private float rowMidY(int row) {
        int[] z = tab.zone(row, WaypointRows.GO);
        return z[1] + z[3] / 2f;
    }

    private void search(String q) {
        p.click(p.shell.search().x() + 20, p.shell.search().y() + 10);
        p.type(q);
        p.advance(200);
    }

    @Test
    public void states() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        p.savePanel("waypoints_default");

        // a row under the mouse shows its actions
        p.hover(tab.list().x() + 220, rowMidY(1));
        p.advance(200);
        p.savePanel("waypoints_hover");

        // the go button of that row hot
        int[] go = tab.zone(1, WaypointRows.GO);
        p.hover(go[0] + go[2] / 2f, go[1] + go[3] / 2f);
        p.advance(100);
        p.savePanel("waypoints_hover_go");

        // delete asks in the row
        int[] del = tab.zone(2, WaypointRows.DELETE);
        p.hover(del[0] + del[2] / 2f, del[1] + del[3] / 2f);
        p.advance(100);
        p.savePanel("waypoints_hover_trash");
        p.click(del[0] + del[2] / 2f, del[1] + del[3] / 2f);
        p.advance(200);
        p.savePanel("waypoints_confirm");
        int[] yes = tab.zone(2, WaypointRows.YES);
        p.hover(yes[0] + yes[2] / 2f, yes[1] + yes[3] / 2f);
        p.advance(100);
        p.savePanel("waypoints_confirm_yes_hover");
        p.key(Keys.ESCAPE);
        p.hover(-100, -100);
        p.advance(200);

        // the tag dropdown open
        p.click(tab.tagDrop().x() + 20, tab.tagDrop().y() + 10);
        p.advance(200);
        p.savePanel("waypoints_dropdown");
        p.key(Keys.ESCAPE);

        // add with no name
        p.click(tab.addButton().x() + 10, tab.addButton().y() + 10);
        p.hover(-100, -100);
        p.settle();
        p.advance(250);
        p.savePanel("waypoints_name_error");

        // typing a name
        p.click(tab.nameField().x() + 20, tab.nameField().y() + 10);
        p.type("diamond vein");
        p.hover(-100, -100);
        p.savePanel("waypoints_typing");

        // sorted by name
        p.click(tab.sort().x() + tab.sort().w() - 10, tab.sort().y() + tab.sort().h() / 2f);
        p.hover(-100, -100);
        p.advance(300);
        p.savePanel("waypoints_by_name");

        // search by tag
        search("user");
        p.savePanel("waypoints_search");
        p.type("zzz");
        p.advance(200);
        p.savePanel("waypoints_no_match");
        // the previews swallow draw errors on purpose, so at least prove the files are real
        assertTrue(Files.size(Preview.OUT.resolve("waypoints_no_match.png")) > 1000);
        assertTrue(Files.size(Preview.OUT.resolve("waypoints_default.png")) > 1000);
    }

    @Test
    public void emptyAndBusy() throws Exception {
        open(1600, 900);
        facade.world.waypoints.clear();
        tab.onShown();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("waypoints_empty");

        // plenty of them, scrolled, in the nether and far away
        facade.world.dimension = "the_nether";
        String[] tags = {"user", "home", "death", "bed"};
        for (int i = 0; i < 40; i++) {
            facade.world.waypoints.add(new WaypointEntry("spot " + (i + 1), tags[i % 4], i * 211 - 3000, 60 + i % 30, i * -137 + 900, 0));
        }
        facade.world.waypoints.add(new WaypointEntry("a rather long waypoint name that must not run into the actions on the right", "user", 4, 70, 4, 0));
        tab.onShown();
        p.settle();
        p.advance(400);
        p.savePanel("waypoints_many");
        p.hover(tab.list().x() + 300, rowMidY(0));
        p.advance(200);
        p.savePanel("waypoints_long_name_hover");
        p.scroll(tab.list().x() + 100, tab.list().y() + 100, -3);
        p.advance(400);
        p.hover(tab.list().x() + 300, tab.list().y() + 70);
        p.savePanel("waypoints_many_scrolled");
    }

    @Test
    public void smallWindow() throws Exception {
        open(960, 540);
        p.hover(-100, -100);
        p.save("waypoints_960");
        p.hover(tab.list().x() + 220, rowMidY(1));
        p.advance(200);
        p.save("waypoints_960_hover");
        int[] del = tab.zone(1, WaypointRows.DELETE);
        p.click(del[0] + del[2] / 2f, del[1] + del[3] / 2f);
        p.advance(200);
        p.save("waypoints_960_confirm");
    }
}
