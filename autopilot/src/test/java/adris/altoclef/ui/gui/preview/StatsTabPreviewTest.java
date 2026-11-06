package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.VirtualGrid;
import adris.altoclef.ui.gui.tabs.StatsTab;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class StatsTabPreviewTest {

    @Test
    public void cardsInBothThemesAndScrollableSmallWindow() throws Exception {
        FakeFacade facade = new FakeFacade();
        facade.stats = () -> List.of(new StatsAccess.Stat("Distance travelled", "12.4 km"),
                new StatsAccess.Stat("Blocks mined", "1 234"), new StatsAccess.Stat("Blocks placed", "680"),
                new StatsAccess.Stat("Mobs killed", "42"), new StatsAccess.Stat("Jumps", "207"),
                new StatsAccess.Stat("Time played", "3h 12m"), new StatsAccess.Stat("Deaths", "2"),
                new StatsAccess.Stat("Items crafted", "360"), new StatsAccess.Stat("Damage dealt", "2 100"));
        for (int[] size : new int[][]{{1920, 1080}, {960, 540}}) {
            Preview p = new Preview(size[0], size[1], facade,
                    pv -> List.of(new StatsTab(pv.theme, pv.facade, pv.ctx)));
            p.settle();
            p.advance(400);
            for (boolean dark : new boolean[]{true, false}) {
                p.shell.setDarkMode(dark);
                p.advance(500);
                p.savePanel("stats_cards_" + (dark ? "dark_" : "light_") + size[0]);
            }
            if (size[0] == 960) {
                VirtualGrid<?> cards = (VirtualGrid<?>) p.shell.children().get(0).children().get(0);
                assertTrue(cards.scroller().scrollable());
                p.scroll(cards.x() + 30, cards.y() + 30, -20);
                p.advance(500);
                p.settle();
                StubCanvas c = new StubCanvas(size[0], size[1]);
                cards.draw(c, p.frame());
                assertNotNull(c.textCall("Damage dealt"));
                assertTrue(c.textCall("Damage dealt").y() <= cards.y() + cards.h());
                p.savePanel("stats_cards_small_scrolled");
            }
        }
    }
}
