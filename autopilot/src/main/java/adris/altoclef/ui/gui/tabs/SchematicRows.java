package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// one file in the library: the wall glyph, the path and under it the format and size, a warning mark on the right
// when the file names blocks this version doesn't know. the selected one wears the same soft blue a followed row gets
final class SchematicRows implements VirtualList.RowRenderer<Row> {

    private final Theme theme;
    private final Supplier<String> selected;
    private final IntSupplier count;
    // rows whose path didn't fit, so the tab knows which ones get a tooltip
    private final Map<Integer, Boolean> clipped = new HashMap<>();

    SchematicRows(Theme theme, Supplier<String> selected, IntSupplier count) {
        this.theme = theme;
        this.selected = selected;
        this.count = count;
    }

    boolean clipped(int index) {
        return Boolean.TRUE.equals(clipped.get(index));
    }

    // the warning mark: the file names blocks this version doesn't know, so some of it will be air
    static boolean warns(Row r) {
        return r.unknownSummary() != null;
    }

    @Override
    public void draw(Canvas c, Frame f, Row r, int index, int rx, int ry, int rw, int rh, boolean hot) {
        boolean sel = r.relativePath().equals(selected.get());
        boolean first = index == 0;
        boolean last = index == count.getAsInt() - 1;
        if (sel) {
            ListCard.fill(theme, c, rx, ry, rw, rh, first, last, theme.color(Theme.ACCENT_SOFT));
        } else if (hot) {
            ListCard.fill(theme, c, rx, ry, rw, rh, first, last, theme.color(Theme.HOVER));
        }
        ListCard.separator(theme, c, rx, ry, rw, index);

        int pad = theme.px(Theme.PAD);
        int is = theme.px(Theme.ICON);
        c.icon(Icon.SCHEMATICS, rx + pad, ry + (rh - is) / 2f, is, theme.color(sel ? Theme.ACCENT_TEXT : Theme.TEXT_3));

        boolean warn = warns(r);
        if (warn) {
            c.icon(Icon.WARNING, rx + rw - pad - is, ry + (rh - is) / 2f, is, theme.color(Theme.TEXT_2));
        }

        TextStyle title = theme.label;
        TextStyle small = theme.small.asTabular();
        float lhTitle = c.lineHeight(title);
        float lhSmall = c.lineHeight(small);
        float gap = theme.px(2);
        float top = ry + (rh - (lhTitle + gap + lhSmall)) / 2f;
        float nameX = rx + pad + is + theme.px(Theme.SPACE_3);
        float room = rx + rw - pad - nameX - (warn ? is + theme.px(Theme.SPACE_2) : 0);
        String name = c.ellipsize(r.relativePath(), title, Math.max(theme.px(40), room));
        clipped.put(index, !name.equals(r.relativePath()));
        c.text(name, nameX, top + c.ascent(title), title, theme.color(Theme.TEXT));
        String sub = c.ellipsize(SchematicRules.sub(r), small, Math.max(theme.px(40), room));
        c.text(sub, nameX, top + lhTitle + gap + c.ascent(small), small, theme.color(r.ok() ? Theme.TEXT_3 : Theme.DANGER));
    }
}
