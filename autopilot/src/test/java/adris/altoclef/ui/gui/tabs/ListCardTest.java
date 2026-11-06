package adris.altoclef.ui.gui.tabs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ListCardTest {

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);

    private VirtualList<Integer> list(int n) {
        VirtualList<Integer> l = new VirtualList<>(theme, 36, (c, f, item, i, x, y, w, h, hot) -> { });
        l.setBounds(20, 100, 410, 200);
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(i);
        }
        l.setItems(items);
        return l;
    }

    private List<StubCanvas.Call> strokes() {
        return canvas.calls.stream().filter(c -> c.kind().equals("strokeRoundRect")).toList();
    }

    @Test
    public void theCardIsAsTallAsItsRowsAndAsWideAsTheListMinusItsScrollbarStrip() {
        VirtualList<Integer> l = list(3);
        ListCard.card(theme, canvas, l, 0L, 3 * 36);
        assertEquals(1, strokes().size());
        StubCanvas.Call card = strokes().get(0);
        assertEquals(20f, card.x(), 0f);
        assertEquals(100f, card.y(), 0f);
        assertEquals(400f, card.w(), 0f);
        assertEquals(108f, card.h(), 0f);
    }

    @Test
    public void anEmptyListGetsNoCard() {
        ListCard.card(theme, canvas, list(0), 0L, 0);
        assertTrue(canvas.calls.stream().noneMatch(c -> c.kind().equals("strokeRoundRect") || c.kind().equals("fillRoundRect")));
    }

    @Test
    public void theCardScrollsWithTheRows() {
        VirtualList<Integer> l = list(20);
        l.scroller().snapTo(72f);
        ListCard.card(theme, canvas, l, 0L, 20 * 36);
        assertEquals(100f - 72f, strokes().get(0).y(), 0f);
        assertEquals(720f, strokes().get(0).h(), 0f);
    }

    @Test
    public void separatorsSkipTheFirstRow() {
        int before = canvas.calls.size();
        ListCard.separator(theme, canvas, 0, 0, 400, 0);
        assertEquals(before, canvas.calls.size());
        ListCard.separator(theme, canvas, 0, 36, 400, 1);
        assertEquals(before + 1, canvas.calls.size());
        StubCanvas.Call line = canvas.calls.get(before);
        // starts PAD in, ends one short of the card's right edge
        assertEquals(16f, line.x(), 0f);
        assertEquals(400f - 16f - 1f, line.w(), 0f);
    }
}
