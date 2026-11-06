package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.model.Coords;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// one waypoint row: icon, name and tag, coordinates under them, distance on the right. the actions are painted on
// the row (not widgets) and hit tested from the same zones, so a recycled row can never keep a stale button
final class WaypointRows implements VirtualList.RowRenderer<WaypointEntry> {

    static final int GO = 0;
    static final int COPY = 1;
    static final int DELETE = 2;
    static final int YES = 3;
    static final int NO = 4;
    static final int PIN = 5;

    private final Theme theme;
    private final Supplier<WorldAccess.Position> pos;
    private final Supplier<WaypointEntry> confirming;
    // how many rows the list has, so the first and last can follow the corners of the card
    private final IntSupplier count;
    // where each visible row was last painted: x, y, w, h
    private final Map<Integer, int[]> painted = new HashMap<>();

    WaypointRows(Theme theme, Supplier<WorldAccess.Position> pos, Supplier<WaypointEntry> confirming, IntSupplier count) {
        this.theme = theme;
        this.pos = pos;
        this.confirming = confirming;
        this.count = count;
    }

    // x, y, w, h of one action in a row at rx, ry. go copy and delete sit left of the distance, yes and no take the
    // same slot while a delete is being confirmed
    int[] zone(int which, int rx, int ry, int rw, int rh) {
        int bh = theme.px(Theme.CONTROL_H);
        int by = ry + (rh - bh) / 2;
        int gap = theme.px(Theme.SPACE_1);
        int sq = theme.px(Theme.CONTROL_H);
        int goW = theme.px(Theme.ROW_H);
        int right = rx + rw - theme.px(Theme.PAD) - theme.px(64) - theme.px(Theme.SPACE_2);
        int del = right - sq;
        int copy = del - gap - sq;
        int go = copy - gap - goW;
        int pin = go - gap - sq;
        int noW = theme.px(44);
        int yesW = theme.px(48);
        int no = right - noW;
        int yes = no - gap - yesW;
        return switch (which) {
            case GO -> new int[]{go, by, goW, bh};
            case COPY -> new int[]{copy, by, sq, bh};
            case DELETE -> new int[]{del, by, sq, bh};
            case YES -> new int[]{yes, by, yesW, bh};
            case PIN -> new int[]{pin, by, sq, bh};
            default -> new int[]{no, by, noW, bh};
        };
    }

    // which action the point is on in row `index`, or -1. only what the row is showing counts
    int hit(int index, boolean confirm, float mx, float my) {
        int[] g = painted.get(index);
        if (g == null) {
            return -1;
        }
        int[] order = confirm ? new int[]{YES, NO} : new int[]{PIN, GO, COPY, DELETE};
        for (int which : order) {
            int[] z = zone(which, g[0], g[1], g[2], g[3]);
            if (inside(z, mx, my)) {
                return which;
            }
        }
        return -1;
    }

    int[] paintedZone(int index, int which) {
        int[] g = painted.get(index);
        return g == null ? null : zone(which, g[0], g[1], g[2], g[3]);
    }

    private static boolean inside(int[] z, float mx, float my) {
        return mx >= z[0] && mx < z[0] + z[2] && my >= z[1] && my < z[1] + z[3];
    }

    @Override
    public void draw(Canvas c, Frame f, WaypointEntry e, int index, int rx, int ry, int rw, int rh, boolean hot) {
        painted.put(index, new int[]{rx, ry, rw, rh});
        boolean confirm = e.equals(confirming.get());
        int pad = theme.px(Theme.PAD);
        boolean first = index == 0;
        boolean last = index == count.getAsInt() - 1;
        if (confirm) {
            ListCard.fill(theme, c, rx, ry, rw, rh, first, last, Theme.fade(theme.color(Theme.DANGER_FILL), 0.55f));
        } else if (hot) {
            ListCard.fill(theme, c, rx, ry, rw, rh, first, last, theme.color(Theme.HOVER));
        }
        ListCard.separator(theme, c, rx, ry, rw, index);

        int is = theme.px(Theme.ICON);
        c.icon(Icon.WAYPOINTS, rx + pad, ry + (rh - is) / 2f, is, theme.color(Theme.TEXT_3));

        TextStyle title = theme.label;
        TextStyle small = theme.small.asTabular();
        float lhTitle = c.lineHeight(title);
        float lhSmall = c.lineHeight(small);
        float gap = theme.px(2);
        float top = ry + (rh - (lhTitle + gap + lhSmall)) / 2f;
        float base1 = top + c.ascent(title);
        float base2 = top + lhTitle + gap + c.ascent(small);

        float nameX = rx + pad + is + theme.px(Theme.SPACE_3);
        int[] yes = zone(YES, rx, ry, rw, rh);
        int[] pin = zone(PIN, rx, ry, rw, rh);
        // the name stops short of whichever pair of actions reaches further left, so a long one never runs under them
        float sure = c.measure("Delete?", theme.label) + theme.px(Theme.SPACE_2);
        float limit = Math.min(pin[0], yes[0] - sure - theme.px(Theme.SPACE_2)) - theme.px(Theme.SPACE_3);
        String tag = e.tag() == null ? "" : e.tag();
        float chipPad = theme.px(Theme.SPACE_2);
        float chipW = tag.isEmpty() ? 0f : c.measure(tag, theme.smallLabel) + 2 * chipPad;
        float chipGap = tag.isEmpty() ? 0f : theme.px(Theme.SPACE_2);
        float nameRoom = Math.max(theme.px(40), limit - nameX - chipW - chipGap);
        String name = c.ellipsize(e.name() == null ? "" : e.name(), title, nameRoom);
        c.text(name, nameX, base1, title, theme.color(Theme.TEXT));
        if (!tag.isEmpty()) {
            float px = nameX + c.measure(name, title) + chipGap;
            float ph = theme.px(18);
            float py = top + (lhTitle - ph) / 2f;
            c.fillRoundRect(px, py, chipW, ph, ph / 2f, theme.color(Theme.RAISED));
            c.strokeRoundRect(px, py, chipW, ph, ph / 2f, theme.color(hot || confirm ? Theme.HAIRLINE_STRONG : Theme.HAIRLINE));
            c.text(tag, px + chipPad, py + ph / 2f + (c.ascent(theme.smallLabel) - c.descent(theme.smallLabel)) / 2f,
                    theme.smallLabel, theme.color(Theme.TEXT_2));
        }
        c.text(WaypointRules.coords(e), nameX, base2, small, theme.color(Theme.TEXT_3));

        String dist = Coords.formatDistance(WaypointRules.distance(e, pos.get()));
        boolean pinned = !confirm && WaypointRules.isPinned(e);
        TextStyle d = theme.body.asTabular();
        if (!dist.isEmpty()) {
            c.text(dist, rx + rw - pad - c.measure(dist, d), mid(c, d, ry, rh), d, theme.color(Theme.TEXT_2));
        }
        // a pinned waypoint wears a small pin to the left of its distance even at rest, so it reads as a favourite
        if (pinned) {
            float dx = rx + rw - pad - (dist.isEmpty() ? 0f : c.measure(dist, d)) - theme.px(Theme.SPACE_2) - is;
            c.icon(Icon.PIN, dx, ry + (rh - is) / 2f, is, theme.color(Theme.ACCENT));
        }

        if (confirm) {
            confirmPair(c, f, rx, ry, rw, rh);
        } else if (hot) {
            actions(c, f, rx, ry, rw, rh);
        }
    }

    // ghost buttons: nothing until the mouse is on one. the row under them is already HOVER, so the step is PRESSED
    private void actions(Canvas c, Frame f, int rx, int ry, int rw, int rh) {
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int is = theme.px(Theme.ICON);

        int[] pin = zone(PIN, rx, ry, rw, rh);
        boolean over = inside(pin, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(pin[0], pin[1], pin[2], pin[3], r, theme.color(Theme.PRESSED));
        }
        c.icon(Icon.PIN, pin[0] + (pin[2] - is) / 2f, pin[1] + (pin[3] - is) / 2f, is, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] go = zone(GO, rx, ry, rw, rh);
        over = inside(go, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(go[0], go[1], go[2], go[3], r, theme.color(Theme.PRESSED));
        }
        float tw = c.measure("Go", theme.label);
        c.text("Go", go[0] + (go[2] - tw) / 2f, mid(c, theme.label, go[1], go[3]), theme.label, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] copy = zone(COPY, rx, ry, rw, rh);
        over = inside(copy, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(copy[0], copy[1], copy[2], copy[3], r, theme.color(Theme.PRESSED));
        }
        c.icon(Icon.COPY, copy[0] + (copy[2] - is) / 2f, copy[1] + (copy[3] - is) / 2f, is, over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2));

        int[] del = zone(DELETE, rx, ry, rw, rh);
        over = inside(del, f.mouseX(), f.mouseY());
        if (over) {
            c.fillRoundRect(del[0], del[1], del[2], del[3], r, theme.color(Theme.DANGER_FILL));
        }
        c.icon(Icon.TRASH, del[0] + (del[2] - is) / 2f, del[1] + (del[3] - is) / 2f, is, theme.color(Theme.DANGER));
    }

    // the row goes a faint red, Yes is a fuller red that steps darker on hover, and No is the
    // ordinary secondary look
    private void confirmPair(Canvas c, Frame f, int rx, int ry, int rw, int rh) {
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int[] yes = zone(YES, rx, ry, rw, rh);
        int[] no = zone(NO, rx, ry, rw, rh);
        String q = "Delete?";
        c.text(q, yes[0] - theme.px(Theme.SPACE_2) - c.measure(q, theme.label), mid(c, theme.label, yes[1], yes[3]), theme.label, theme.color(Theme.TEXT));

        boolean overYes = inside(yes, f.mouseX(), f.mouseY());
        c.fillRoundRect(yes[0], yes[1], yes[2], yes[3], r, overYes ? theme.color(Theme.DANGER_PRESSED) : theme.color(Theme.DANGER_FILL));
        c.strokeRoundRect(yes[0], yes[1], yes[2], yes[3], r, Theme.fade(theme.color(Theme.DANGER), 0.5f));
        float yw = c.measure("Yes", theme.label);
        c.text("Yes", yes[0] + (yes[2] - yw) / 2f, mid(c, theme.label, yes[1], yes[3]), theme.label, theme.color(Theme.DANGER));

        boolean overNo = inside(no, f.mouseX(), f.mouseY());
        c.fillRoundRect(no[0], no[1], no[2], no[3], r, overNo ? theme.color(Theme.PRESSED) : theme.color(Theme.RAISED));
        c.strokeRoundRect(no[0], no[1], no[2], no[3], r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
        float nw = c.measure("No", theme.label);
        c.text("No", no[0] + (no[2] - nw) / 2f, mid(c, theme.label, no[1], no[3]), theme.label, theme.color(Theme.TEXT));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }
}
