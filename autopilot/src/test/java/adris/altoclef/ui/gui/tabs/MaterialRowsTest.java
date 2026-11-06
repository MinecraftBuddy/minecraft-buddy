package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Kind;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.Theme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// what one row puts on the canvas: the box, the tick, and which greys an unchecked row goes to
public class MaterialRowsTest {

    private final Theme theme = StubCanvas.theme();
    private final MaterialRows rows = new MaterialRows(theme);
    private static final int W = 400;
    private static final int H = 40;

    private static Line line(Kind kind, int needed, int have) {
        return new Line("minecraft:oak_log", "minecraft:oak_log", "Oak Log", needed, have, kind, "", true);
    }

    private StubCanvas draw(MaterialRows.Entry e, boolean hot) {
        StubCanvas c = new StubCanvas();
        rows.draw(c, null, e, 0, 0, 0, W, H, hot);
        return c;
    }

    private static StubCanvas.Call icon(StubCanvas c, Icon icon) {
        for (StubCanvas.Call call : c.calls) {
            if (call.kind().equals("icon") && call.text().equals(icon.name())) {
                return call;
            }
        }
        return null;
    }

    private static StubCanvas.Call fill(StubCanvas c, int argb) {
        for (StubCanvas.Call call : c.calls) {
            if (call.kind().equals("fillRoundRect") && call.argb() == argb) {
                return call;
            }
        }
        return null;
    }

    @Test
    public void aCheckedRowIsAnAccentBoxWithATick() {
        StubCanvas c = draw(MaterialRows.Entry.item(line(Kind.ITEM, 212, 40), true), false);
        StubCanvas.Call box = fill(c, theme.color(Theme.ACCENT));
        assertNotNull(box);
        assertEquals(theme.px(16), box.x(), 0.01f);
        assertEquals(theme.px(12), box.y(), 0.01f);
        assertEquals(theme.px(16), box.w(), 0.01f);
        StubCanvas.Call tick = icon(c, Icon.CHECK);
        assertNotNull(tick);
        assertEquals(theme.color(Theme.ON_ACCENT), tick.argb());
        assertEquals(0, c.count("strokeRoundRect"));
        // the icon sits after the box, the name after the icon
        StubCanvas.Call sprite = c.calls.stream().filter(k -> k.kind().equals("itemIcon")).findFirst().orElseThrow();
        assertEquals(theme.px(16 + 16 + 8), sprite.x(), 0.01f);
        assertEquals(theme.color(Theme.TEXT), c.textCall("Oak Log").argb());
        assertEquals(theme.color(Theme.DANGER), c.textCall("40 / 212").argb());
    }

    @Test
    public void anUncheckedRowIsAnEmptyBoxAndEverythingGoesQuiet() {
        StubCanvas c = draw(MaterialRows.Entry.item(line(Kind.ITEM, 212, 40), false), false);
        assertNull(icon(c, Icon.CHECK));
        assertNull(fill(c, theme.color(Theme.ACCENT)));
        assertNotNull(fill(c, theme.color(Theme.FIELD)));
        assertEquals(1, c.count("strokeRoundRect"));
        assertEquals(theme.color(Theme.TEXT_3), c.textCall("Oak Log").argb());
        assertEquals(theme.color(Theme.TEXT_3), c.textCall("40 / 212").argb());
        // the sprite is still drawn, then the row's background goes over it
        assertEquals(1, c.count("itemIcon"));
        StubCanvas.Call scrim = c.calls.stream().filter(k -> k.kind().equals("fillRect")).findFirst().orElseThrow();
        assertEquals(theme.px(40), scrim.x(), 0.01f);
        assertEquals(theme.itemIconPx(), scrim.w(), 0.01f);
        assertEquals((theme.color(Theme.SURFACE) & 0xFFFFFF), scrim.argb() & 0xFFFFFF);
        assertTrue((scrim.argb() >>> 24) < 255);
    }

    @Test
    public void aHotUncheckedRowScrimsWithTheHoverColour() {
        StubCanvas c = draw(MaterialRows.Entry.item(line(Kind.ITEM, 212, 40), false), true);
        StubCanvas.Call scrim = c.calls.stream().filter(k -> k.kind().equals("fillRect")).findFirst().orElseThrow();
        assertEquals((theme.color(Theme.HOVER) & 0xFFFFFF), scrim.argb() & 0xFFFFFF);
    }

    @Test
    public void subRowsHaveNoBoxButKeepTheIconColumn() {
        StubCanvas c = draw(MaterialRows.Entry.sub(line(Kind.CANT_PLACE, 3, 0)), false);
        assertNull(icon(c, Icon.CHECK));
        assertNull(fill(c, theme.color(Theme.ACCENT)));
        assertNull(fill(c, theme.color(Theme.FIELD)));
        assertEquals(0, c.count("strokeRoundRect"));
        assertEquals(0, c.count("fillRect"));
        StubCanvas.Call sprite = c.calls.stream().filter(k -> k.kind().equals("itemIcon")).findFirst().orElseThrow();
        assertEquals(theme.px(40), sprite.x(), 0.01f);
        assertEquals(theme.color(Theme.TEXT_3), c.textCall("3").argb());
    }

    @Test
    public void disabledFadesTheBoxAndNothingElse() {
        rows.setDisabled(true);
        // nothing in hand, so the only accent on the row would be the box
        StubCanvas c = draw(MaterialRows.Entry.item(line(Kind.ITEM, 212, 0), true), false);
        assertNull(fill(c, theme.color(Theme.ACCENT)));
        StubCanvas.Call box = c.calls.stream().filter(k -> k.kind().equals("fillRoundRect") && (k.argb() & 0xFFFFFF) == (theme.color(Theme.ACCENT) & 0xFFFFFF)).findFirst().orElseThrow();
        assertTrue((box.argb() >>> 24) < 255);
        StubCanvas.Call tick = icon(c, Icon.CHECK);
        assertTrue((tick.argb() >>> 24) < 255);
        assertEquals(theme.color(Theme.TEXT), c.textCall("Oak Log").argb());
        rows.setDisabled(false);
        assertFalse(rows.disabled());
    }
}
