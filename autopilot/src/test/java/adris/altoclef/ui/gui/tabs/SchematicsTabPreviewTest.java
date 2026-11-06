package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertTrue;

// the schematics tab in every state worth looking at, written to build/gui-preview
public class SchematicsTabPreviewTest {

    private FakeFacade facade;
    private Preview p;
    private SchematicsTab tab;

    private void open(int w, int h) throws Exception {
        facade = new FakeFacade();
        SchematicsTab[] holder = new SchematicsTab[1];
        p = new Preview(w, h, facade, pv -> {
            holder[0] = new SchematicsTab(pv.theme, pv.facade, pv.ctx);
            return List.<Tab>of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        p.settle();
        p.advance(300);
    }

    private void clickRow(int i) {
        p.click(tab.list().x() + 60, tab.rowMidY(i));
        p.tick();
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
    }

    private void coverEverything() {
        for (String id : List.of("minecraft:stone_bricks", "minecraft:oak_log", "minecraft:glass", "minecraft:oak_stairs",
                "minecraft:torch", "minecraft:farmland", "minecraft:beacon")) {
            facade.schematics.have.put(id, 5000);
        }
    }

    @Test
    public void states() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        p.savePanel("schematics_nothing_selected");

        // the castle with a few stacks in the inventory: the common case
        clickRow(0);
        p.savePanel("schematics_missing");

        // two rows struck from the build, one of them the biggest stack
        var boxes = tab.pane().list();
        p.click(tab.pane().boxMidX(), boxes.rowMidY(0));
        p.settle();
        p.click(tab.pane().boxMidX(), boxes.rowMidY(5));
        p.settle();
        p.advance(300);
        p.hover(tab.pane().boxMidX() + 80, boxes.rowMidY(5));
        p.settle();
        p.advance(200);
        p.savePanel("schematics_unchecked");
        p.click(tab.pane().boxMidX(), boxes.rowMidY(0));
        p.settle();
        p.click(tab.pane().boxMidX(), boxes.rowMidY(5));
        p.settle();
        p.advance(300);
        p.hover(-100, -100);

        // the folded kinds open (they sit past the first page now that the map has the top of the card)
        var mats = tab.pane().list();
        mats.ensureVisible(9);
        p.settle();
        p.advance(600);
        p.settle();
        float sy = mats.rowMidY(7);
        p.click(mats.x() + 40, sy);
        p.settle();
        p.advance(300);
        p.click(mats.x() + 40, sy + 2 * mats.rowHeight());
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_sections_open");

        // the first click arms the button
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
        p.advance(200);
        p.hover(-100, -100);
        p.savePanel("schematics_armed");
        tab.onHidden();
        tab.onShown();

        // everything in hand
        coverEverything();
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_happy");

        // a file naming blocks this version doesn't know
        clickRow(1);
        p.savePanel("schematics_unknown_blocks");

        // a file that didn't parse, and its tooltip
        clickRow(2);
        p.savePanel("schematics_error_row");
        p.hover(tab.list().x() + 60, tab.rowMidY(2));
        for (int i = 0; i < 12; i++) {
            p.tick();
        }
        p.advance(100);
        p.savePanel("schematics_error_tooltip");
        p.hover(-100, -100);

        // the search box narrows the list
        p.click(p.shell.search().x() + 20, p.shell.search().y() + 10);
        p.type("villages");
        p.advance(200);
        p.hover(-100, -100);
        p.savePanel("schematics_search");
        p.type("zzz");
        p.advance(200);
        p.savePanel("schematics_no_match");
        assertTrue(Files.size(Preview.OUT.resolve("schematics_missing.png")) > 1000);
    }

    @Test
    public void loadingAndEmpty() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        // the rows stay up while a refresh runs
        facade.schematics.loadingForever = true;
        p.click(tab.refreshButton().x() + 10, tab.refreshButton().y() + 10);
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_refreshing");

        // the count of a file still running
        facade.schematics.loadingForever = false;
        p.tick();
        clickRow(0);
        facade.schematics.loadingForever = true;
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_counting");

        // the very first scan, nothing to show yet
        facade.schematics.clear();
        tab.onShown();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_loading");

        // an empty folder
        facade.schematics.loadingForever = false;
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_empty");
    }

    @Test
    public void lightAndLongNames() throws Exception {
        open(1600, 900);
        p.shell.setDarkMode(false);
        p.settle();
        p.advance(400);
        clickRow(0);
        p.savePanel("schematics_light");
        facade.schematics.setIncluded("Small Castle.litematic", "minecraft:glass", false);
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_light_unchecked");
        facade.schematics.setIncluded("Small Castle.litematic", "minecraft:glass", true);

        p.shell.setDarkMode(true);
        p.settle();
        p.advance(400);
        facade.schematics.importDropped(List.of(java.nio.file.Path.of("builds/a rather long schematic name that keeps going past the card.litematic")));
        p.tick();
        p.settle();
        p.advance(300);
        clickRow(4);
        p.hover(tab.list().x() + 60, tab.rowMidY(4));
        for (int i = 0; i < 12; i++) {
            p.tick();
        }
        p.advance(100);
        p.savePanel("schematics_long_name_tooltip");
    }

    @Test
    public void smallWindow() throws Exception {
        open(960, 540);
        p.hover(-100, -100);
        clickRow(0);
        p.save("schematics_960");
        facade.world.pos = null;
        facade.schematics.inWorld = false;
        p.tick();
        p.settle();
        p.save("schematics_960_no_world");
    }

    // the map band: where the file goes, in every state it can be in
    @Test
    public void placement() throws Exception {
        open(1600, 900);
        p.hover(-100, -100);
        clickRow(0);
        PlacementMap map = tab.pane().map();
        p.savePanel("schematics_map");

        // the pointer over the footprint, then a drag half way through: the rectangle follows, the map holds still
        p.hover(map.footprintCentreX(), map.footprintCentreY());
        p.settle();
        p.advance(200);
        p.savePanel("schematics_map_hover");
        p.press(map.footprintCentreX(), map.footprintCentreY());
        p.drag(map.footprintCentreX() + 30 * map.pxPerBlock(), map.footprintCentreY() + 6 * map.pxPerBlock());
        p.settle();
        p.savePanel("schematics_map_dragging");
        p.release(p.mouseX, p.mouseY);
        p.hover(-100, -100);
        p.settle();
        p.advance(300);
        p.savePanel("schematics_map_dropped");

        // a quarter turn and a flip: the thick edge and the origin dot move
        facade.schematics.placeAtFeet("Small Castle.litematic");
        facade.schematics.rotate("Small Castle.litematic", true);
        facade.schematics.mirror("Small Castle.litematic");
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_map_turned");

        // zoomed right in, the player off the band
        p.scroll(map.x() + map.w() / 2f, map.y() + map.h() / 2f, 1);
        facade.schematics.move("Small Castle.litematic", -80, 0, 40);
        p.tick();
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_map_zoomed");
        p.scroll(map.x() + map.w() / 2f, map.y() + map.h() / 2f, -1);

        // half on chunks nobody has loaded yet
        facade.schematics.placeAtFeet("Small Castle.litematic");
        facade.schematics.move("Small Castle.litematic", 100, 0, -100);
        p.tick();
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_map_unloaded");

        // the player went to the nether, the placement stayed in the overworld
        facade.schematics.placeAtFeet("Small Castle.litematic");
        facade.schematics.dimension = "minecraft:the_nether";
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_map_elsewhere");
        facade.schematics.dimension = "minecraft:overworld";
        p.tick();
        p.settle();

        // a build request on its way, started, and one that failed after the click. one row is unchecked so the
        // greyed boxes show both states
        facade.schematics.setIncluded("Small Castle.litematic", "minecraft:farmland", false);
        facade.schematics.parsePolls = 1000;
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
        p.click(tab.buildButton().x() + 10, tab.buildButton().y() + 10);
        p.settle();
        p.advance(6000);
        p.settle();
        p.hover(-100, -100);
        p.savePanel("schematics_map_parsing");
        // the fake is still reading (the button is off while it does), so the next requests go straight to it
        facade.schematics.parsePolls = 0;
        facade.schematics.runPolls = -1;
        facade.schematics.build("Small Castle.litematic");
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_map_building");
        facade.schematics.buildFailsLater = "The file changed while reading it";
        facade.schematics.build("Small Castle.litematic");
        p.tick();
        p.settle();
        p.advance(300);
        p.savePanel("schematics_map_build_error");

        // no world at all (the toast from the last build has gone by now)
        facade.world.pos = null;
        facade.schematics.inWorld = false;
        p.tick();
        p.settle();
        p.advance(6000);
        p.settle();
        p.savePanel("schematics_map_no_world");
        assertTrue(Files.size(Preview.OUT.resolve("schematics_map.png")) > 1000);
    }

    @Test
    public void placementLightAndSmall() throws Exception {
        open(1600, 900);
        p.shell.setDarkMode(false);
        p.settle();
        p.advance(400);
        clickRow(0);
        facade.schematics.move("Small Castle.litematic", 100, 0, -100);
        p.tick();
        p.settle();
        p.advance(300);
        p.hover(-100, -100);
        p.savePanel("schematics_map_light");

        // 540p, and the narrowest the panel goes: the controls drop their words, then the arrows
        open(960, 540);
        p.hover(-100, -100);
        clickRow(0);
        facade.schematics.rotate("Small Castle.litematic", true);
        p.tick();
        p.settle();
        p.advance(300);
        p.save("schematics_map_960");
        // 58% of 830 is 481 device px at the 0.75 scale a 540 tall window picks, which is the 640 design px floor
        open(830, 540);
        p.hover(-100, -100);
        clickRow(0);
        p.save("schematics_map_640");
    }
}
