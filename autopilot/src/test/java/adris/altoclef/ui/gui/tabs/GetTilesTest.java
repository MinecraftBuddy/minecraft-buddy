package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the name under a tile: one line when it fits, two broken at a space when it does not, and only the second line
// ever gets the ellipsis. the stub measures 0.6 em a character, so at 12px small an 88 cell holds about 11
public class GetTilesTest {

    private final Theme theme = StubCanvas.theme();
    private final GetTiles tiles = new GetTiles(theme);

    private List<String> draw(String name) {
        StubCanvas c = new StubCanvas();
        tiles.draw(c, new ItemEntry(name.toLowerCase().replace(' ', '_'), name, "minecraft:x", 64), -1, 0, 0, tiles.cellW(), tiles.cellH(), false);
        return c.texts();
    }

    @Test
    public void shortNamesStayOnOneLine() {
        assertEquals(List.of("Stick"), draw("Stick"));
    }

    @Test
    public void longNamesBreakAtASpaceAndOnlyTheSecondLineIsCut() {
        assertEquals(List.of("Diamond", "Pickaxe"), draw("Diamond Pickaxe"));
        List<String> three = draw("Deepslate Diamond Ore");
        assertEquals(2, three.size());
        assertEquals("Deepslate", three.get(0));
        assertTrue(three.get(1).startsWith("Diamond"));
        assertTrue(three.get(1).endsWith("…"));
    }

    @Test
    public void aNameWithNoSpaceToBreakAtIsCutOnItsOnlyLine() {
        List<String> one = draw("Supercalifragilistic");
        assertEquals(1, one.size());
        assertTrue(one.get(0).endsWith("…"));
    }

    @Test
    public void theSecondLineSitsOneLineUnderTheFirstAndBothAreCentred() {
        StubCanvas c = new StubCanvas();
        int w = tiles.cellW();
        tiles.draw(c, new ItemEntry("iron_leggings", "Iron Leggings", "minecraft:x", 1), -1, 0, 0, w, tiles.cellH(), false);
        StubCanvas.Call a = c.textCall("Iron");
        StubCanvas.Call b = c.textCall("Leggings");
        assertEquals(Math.ceil(c.lineHeight(theme.small)), b.y() - a.y(), 0.01f);
        assertEquals(w / 2f, a.x() + c.measure("Iron", theme.small) / 2f, 0.6f);
        assertEquals(w / 2f, b.x() + c.measure("Leggings", theme.small) / 2f, 0.6f);
    }

    @Test
    public void theCellGrowsWhenTheIconWillNotShrinkWithTheScale() {
        // at 0.75 the icon is still 32 px, so 66 would not hold it and two lines of name
        Theme small = new Theme(0.75f, new StubCanvas());
        GetTiles t = new GetTiles(small);
        int lines = 2 * (int) Math.ceil(small.measurer.lineHeight(small.small));
        assertTrue(t.cellH() >= 2 * small.itemIconPx() + lines + small.px(Theme.SPACE_2 + Theme.SPACE_1 + Theme.SPACE_1 + Theme.SPACE_2));
        assertEquals(theme.px(88), tiles.cellH());
    }
}
