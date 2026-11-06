package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Kind;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;

// the rows of a material list. an ITEM row is a check box, the icon, the name, "have / needed" on the right and a two
// pixel bar under the name that fills with the accent as the player's stack grows, so the whole list reads as a
// checklist. an unchecked row is one the player struck from the build: everything on it goes to the caption grey and
// the bar loses its blue, so a glance down the list shows what is still in by where the blue is. the folded kinds
// (can't place, not counted) are one SECTION row each with a chevron, their lines only exist while open and get no
// box (nothing to leave out, they were never going in), and the facade's notes sit at the bottom as quiet small text
final class MaterialRows implements VirtualList.RowRenderer<MaterialRows.Entry> {

    enum Type { ITEM, SECTION, SUB, NOTE }

    // key tells rows apart across refreshes: the item id, the section name, or the note text. checked only means
    // anything on ITEM rows
    record Entry(Type type, String key, Line line, String label, int count, boolean open, boolean checked) {

        static Entry item(Line l, boolean checked) {
            return new Entry(Type.ITEM, "item:" + l.itemId(), l, l.displayName(), 0, false, checked);
        }

        static Entry sub(Line l) {
            return new Entry(Type.SUB, "sub:" + l.itemId(), l, l.displayName(), 0, false, false);
        }

        static Entry section(String name, int count, boolean open) {
            return new Entry(Type.SECTION, "section:" + name, null, name, count, open, false);
        }

        static Entry note(String text) {
            return new Entry(Type.NOTE, "note:" + text, null, text, 0, false, false);
        }
    }

    // the box is icon sized, its corners a touch tighter than a control's since it is half the height
    static final int BOX = Theme.ICON;
    static final int BOX_RADIUS = 4;
    // how far the box and the check glyph fade while a build of this file runs: they still work, they just don't
    // reach the build that is going
    private static final float DISABLED = 0.45f;
    // how much of the row's background goes over an unchecked sprite. a sprite has no colour to swap for a greyer one
    private static final float ICON_SCRIM = 0.6f;

    private final Theme theme;
    private boolean disabled;

    MaterialRows(Theme theme) {
        this.theme = theme;
    }

    // greyed boxes while this file is being built
    void setDisabled(boolean on) {
        disabled = on;
    }

    boolean disabled() {
        return disabled;
    }

    @Override
    public void draw(Canvas c, Frame f, Entry e, int index, int rx, int ry, int rw, int rh, boolean hot) {
        int side = theme.px(Theme.SPACE_2);
        if (hot && e.type() != Type.NOTE) {
            c.fillRoundRect(rx + side, ry + theme.px(1), rw - 2 * side, rh - theme.px(2), theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.HOVER));
        }
        switch (e.type()) {
            case ITEM, SUB -> line(c, e, rx, ry, rw, rh, hot);
            case SECTION -> section(c, e, rx, ry, rw, rh, hot);
            default -> {
                float tx = rx + 2 * side;
                String s = c.ellipsize(e.label(), theme.small, Math.max(0, rw - 4 * side));
                c.text(s, tx, mid(c, theme.small, ry, rh), theme.small, theme.color(Theme.TEXT_3));
            }
        }
    }

    private void line(Canvas c, Entry e, int rx, int ry, int rw, int rh, boolean hot) {
        Line l = e.line();
        boolean item = l.kind() == Kind.ITEM;
        boolean off = item && !e.checked();
        int side = theme.px(Theme.SPACE_2);
        int is = theme.itemIconPx();
        float bx = rx + 2 * side;
        if (item) {
            box(c, bx, ry + (rh - theme.px(BOX)) / 2f, e.checked());
        }
        // the sub rows keep the icon column so every name in the list starts on the same line
        float ix = bx + theme.px(BOX) + side;
        drawIcon(c, l, ix, ry, rh, off, hot);

        TextStyle num = theme.small.asTabular();
        String counts = SchematicRules.counts(l);
        float numW = c.measure(counts, num);
        float right = rx + rw - 2 * side;
        int numCol = off || !item ? Theme.TEXT_3 : l.covered() ? Theme.TEXT_2 : Theme.DANGER;
        c.text(counts, right - numW, mid(c, num, ry, rh), num, theme.color(numCol));

        float tx = ix + is + theme.px(Theme.SPACE_3);
        float room = Math.max(theme.px(40), right - numW - theme.px(Theme.SPACE_3) - tx);
        float nameH = c.lineHeight(theme.body);
        float barH = item ? theme.px(2) : 0;
        float barGap = item ? theme.px(3) : 0;
        float top = ry + (rh - nameH - barGap - barH) / 2f;
        String name = c.ellipsize(e.label(), theme.body, room);
        float base = top + c.ascent(theme.body);
        float adv = c.text(name, tx, base, theme.body, theme.color(off ? Theme.TEXT_3 : Theme.TEXT));
        if (name.equals(e.label())) {
            drawNote(c, l.note(), tx + adv, base, room - adv);
        }
        if (item) {
            float by = top + nameH + barGap;
            c.fillRoundRect(tx, by, room, barH, barH / 2f, theme.color(Theme.HAIRLINE));
            float fill = room * SchematicRules.coverage(l);
            if (fill >= 1f) {
                // an unchecked row keeps its bar so the layout holds still, it just goes grey
                int col = off ? Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f) : theme.color(Theme.ACCENT);
                c.fillRoundRect(tx, by, fill, barH, barH / 2f, col);
            }
        }
    }

    // checked is the accent with a white tick, like a switch that is on. empty is a field with the strong hairline
    // round it (the plain one is a shade off the card, a box that faint doesn't look like something to click)
    private void box(Canvas c, float bx, float by, boolean checked) {
        float bs = theme.px(BOX);
        float r = theme.pxf(BOX_RADIUS);
        float a = disabled ? DISABLED : 1f;
        if (checked) {
            c.fillRoundRect(bx, by, bs, bs, r, Theme.fade(theme.color(Theme.ACCENT), a));
            int gs = theme.px(14);
            c.icon(Icon.CHECK, bx + (bs - gs) / 2f, by + (bs - gs) / 2f, gs, Theme.fade(theme.color(Theme.ON_ACCENT), a));
        } else {
            c.fillRoundRect(bx, by, bs, bs, r, Theme.fade(theme.color(Theme.FIELD), a));
            c.strokeRoundRect(bx, by, bs, bs, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), a));
        }
    }

    // a block with no item to stand for it gets the cube glyph, never an empty square. an unchecked sprite gets the
    // row's own background laid over it at 60%, since the canvas can't tint an item
    private void drawIcon(Canvas c, Line l, float ix, int ry, int rh, boolean off, boolean hot) {
        int is = theme.itemIconPx();
        float iy = ry + (rh - is) / 2f;
        if (l.iconItemId() == null || l.iconItemId().isEmpty()) {
            int gs = theme.px(Theme.ICON);
            c.icon(Icon.BLOCK, ix + (is - gs) / 2f, ry + (rh - gs) / 2f, gs, theme.color(Theme.TEXT_3));
            return;
        }
        c.itemIcon(l.iconItemId(), ix, iy, is);
        if (off) {
            c.fillRect(ix, iy, is, is, Theme.alpha(theme.color(hot ? Theme.HOVER : Theme.SURFACE), ICON_SCRIM));
        }
    }

    // the note rides after the name in small text, and is the first thing to go when the row is narrow
    private void drawNote(Canvas c, String note, float afterName, float base, float left) {
        if (note == null || note.isEmpty()) {
            return;
        }
        float gap = theme.px(6);
        if (left - gap > theme.px(30)) {
            c.text(c.ellipsize(note, theme.small, left - gap), afterName + gap, base, theme.small, theme.color(Theme.TEXT_3));
        }
    }

    private void section(Canvas c, Entry e, int rx, int ry, int rw, int rh, boolean hot) {
        int side = theme.px(Theme.SPACE_2);
        int is = theme.px(Theme.ICON);
        float ix = rx + 2 * side;
        c.icon(e.open() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, ix, ry + (rh - is) / 2f, is, theme.color(hot ? Theme.TEXT : Theme.TEXT_2));
        float tx = ix + is + theme.px(Theme.SPACE_2);
        float base = mid(c, theme.label, ry, rh);
        float adv = c.text(e.label(), tx, base, theme.label, theme.color(hot ? Theme.TEXT : Theme.TEXT_2));
        c.text(String.valueOf(e.count()), tx + adv + theme.px(6), base, theme.body.asTabular(), theme.color(Theme.TEXT_3));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }
}
