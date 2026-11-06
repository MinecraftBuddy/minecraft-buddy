package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;
import java.util.function.ToIntFunction;

// one row of the last things you sent, lined up with the grid's columns so it reads as the top of the grid and not as
// a separate widget. only as many as fit, a half tile hanging off the edge is worse than one fewer
final class GetRecentStrip extends Widget {

    interface Pick {
        void pick(ItemEntry entry, int mods);
    }

    static final int MAX = 8;

    private final GetTiles tiles;
    private final ToIntFunction<String> countOf;
    private final Pick pick;
    private List<ItemEntry> entries = List.of();
    private int pressed = -1;

    GetRecentStrip(Theme theme, GetTiles tiles, ToIntFunction<String> countOf, Pick pick) {
        super(theme);
        this.tiles = tiles;
        this.countOf = countOf;
        this.pick = pick;
    }

    void setEntries(List<ItemEntry> next) {
        entries = List.copyOf(next);
    }

    List<ItemEntry> entries() {
        return entries;
    }

    // the heading's own line plus the room under it that makes it a heading
    int labelH() {
        return (int) Math.ceil(theme.measurer.lineHeight(theme.smallLabel)) + theme.px(Theme.SECTION_BELOW);
    }

    int stripHeight() {
        return labelH() + tiles.cellH();
    }

    int shown() {
        return Math.min(Math.min(MAX, entries.size()), tiles.columns(w));
    }

    private int indexAt(float mx, float my) {
        if (!contains(mx, my) || my < y + labelH()) {
            return -1;
        }
        int cw = tiles.cellWidth(w);
        int i = (int) ((mx - x) / cw);
        return i >= 0 && i < shown() ? i : -1;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        // lines up with the left edge of the tile fill under it, not the cell
        c.text("Recent", x + theme.px(Theme.SPACE_1), y + c.ascent(theme.smallLabel), theme.smallLabel, theme.color(Theme.TEXT_3));
        int cw = tiles.cellWidth(w);
        int hover = hovered ? indexAt(f.mouseX(), f.mouseY()) : -1;
        int n = shown();
        for (int i = 0; i < n; i++) {
            ItemEntry e = entries.get(i);
            tiles.draw(c, e, countOf.applyAsInt(e.name()), x + i * cw, y + labelH(), cw, tiles.cellH(), i == hover);
        }
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        int i = indexAt(mx, my);
        if (i < 0 || button != 0) {
            return false;
        }
        pressed = i;
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        int was = pressed;
        pressed = -1;
        int i = indexAt(mx, my);
        if (was >= 0 && was == i && button == 0) {
            ItemEntry e = entries.get(i);
            Safe.run("recent pick", () -> pick.pick(e, mods));
            return true;
        }
        return was >= 0;
    }
}
