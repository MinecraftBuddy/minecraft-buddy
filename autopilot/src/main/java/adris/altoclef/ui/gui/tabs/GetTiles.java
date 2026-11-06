package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualView;
import adris.altoclef.ui.gui.model.Pinned;

import java.util.HashMap;
import java.util.Map;

// one item tile, shared by the grid and the recent strip so a tile looks the same wherever it shows up. everything is
// drawn inside the cell it is given: the grid clips, but the game's item renderer is the thing that likes to wander
final class GetTiles {

    private final Theme theme;
    private final Map<String, String[]> nameCache = new HashMap<>();
    private float cachedW = -1f;

    GetTiles(Theme theme) {
        this.theme = theme;
    }

    int cellW() {
        return theme.px(88);
    }

    // 88 at full scale. the item icon does not shrink under scale 1 (it stays a whole multiple of 16), so at 0.75 the
    // icon plus two lines of name would not fit in 66 and the cell grows to hold them instead
    int cellH() {
        int lines = 2 * (int) Math.ceil(theme.measurer.lineHeight(theme.small));
        int needed = theme.px(Theme.SPACE_2 + Theme.SPACE_1) + 2 * theme.itemIconPx() + theme.px(Theme.SPACE_1) + lines + theme.px(Theme.SPACE_2);
        return Math.max(theme.px(88), needed);
    }

    // same maths as VirtualGrid, so the recent strip lines up with the columns under it
    int columns(int w) {
        return Math.max(1, (w - theme.px(VirtualView.GUTTER)) / cellW());
    }

    int cellWidth(int w) {
        return Math.max(1, (w - theme.px(VirtualView.GUTTER)) / columns(w));
    }

    // up to two lines, broken at a space, only the second one ellipsized. the label block is always two lines tall
    // whether the name needs them or not, so every row of tiles keeps the same baselines and "Diamond Pickaxe" is
    // still a diamond pickaxe and not "Diamond Pi...". cached per width, the measure was showing up when a screenful
    // of tiles redraws every frame
    private String[] fit(TextMeasurer m, String name, TextStyle style, float maxW) {
        if (maxW != cachedW) {
            nameCache.clear();
            cachedW = maxW;
        }
        String[] hit = nameCache.get(name);
        if (hit != null) {
            return hit;
        }
        String[] out;
        if (m.measure(name, style) <= maxW) {
            out = new String[] {name};
        } else {
            int best = -1;
            for (int i = 0; i < name.length() - 1; i++) {
                if (name.charAt(i) == ' ' && m.measure(name.substring(0, i), style) <= maxW) {
                    best = i;
                }
            }
            out = best < 0
                    ? new String[] {m.ellipsize(name, style, maxW)}
                    : new String[] {name.substring(0, best), m.ellipsize(name.substring(best + 1), style, maxW)};
        }
        if (nameCache.size() > 512) {
            nameCache.clear();
        }
        nameCache.put(name, out);
        return out;
    }

    // count < 0 means not in the basket. count 0 is in the basket with nothing to show (a mine block has no count)
    void draw(Canvas c, ItemEntry e, int count, int x, int y, int w, int h, boolean hovered) {
        int inset = theme.px(Theme.SPACE_1);
        float rx = x + inset;
        float ry = y + inset;
        float rw = w - 2 * inset;
        float rh = h - 2 * inset;
        // no box until there is a reason for one: hover is a quiet fill, in the basket is the tinted fill and a
        // soft blue edge
        float radius = theme.pxf(Theme.CARD_RADIUS);
        if (count >= 0) {
            c.fillRoundRect(rx, ry, rw, rh, radius, theme.color(Theme.ACCENT_SOFT));
            c.strokeRoundRect(rx, ry, rw, rh, radius, Theme.fade(theme.color(Theme.ACCENT), 0.6f));
        } else if (hovered) {
            c.fillRoundRect(rx, ry, rw, rh, radius, theme.color(Theme.HOVER));
        }
        // the item is the star of the tile: two whole icon sizes, still a multiple of 16
        int s = theme.itemIconPx() * 2;
        int iy = y + theme.px(Theme.SPACE_2 + Theme.SPACE_1);
        c.itemIcon(e.iconItemId(), x + (w - s) / 2, iy, s);

        TextStyle small = theme.small;
        String[] lines = fit(c, e.displayName(), small, rw - 2 * theme.px(Theme.SPACE_1));
        float base = iy + s + theme.px(Theme.SPACE_1) + c.ascent(small);
        float step = (float) Math.ceil(c.lineHeight(small));
        int col = hovered || count >= 0 ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2);
        for (int i = 0; i < lines.length; i++) {
            float tw = c.measure(lines[i], small);
            c.text(lines[i], x + (w - tw) / 2f, base + i * step, small, col);
        }

        if (count > 0) {
            TextStyle bs = theme.smallLabel.asTabular();
            String t = count > 999 ? "999+" : String.valueOf(count);
            float ph = theme.px(16);
            float pw = Math.max(ph, c.measure(t, bs) + theme.px(Theme.SPACE_2));
            float px = rx + rw - pw - theme.px(Theme.SPACE_1);
            float py = ry + theme.px(Theme.SPACE_1);
            c.fillRoundRect(px, py, pw, ph, ph / 2f, theme.color(Theme.ACCENT));
            float tb = py + ph / 2f + (c.ascent(bs) - c.descent(bs)) / 2f;
            c.text(t, px + (pw - c.measure(t, bs)) / 2f, tb, bs, theme.color(Theme.ON_ACCENT));
        }
    }

    // the top left corner of the tile: an accent pin when the item is pinned, a ghost pin that appears on hover to
    // pin it otherwise. drawn by the grid only, the recent strip wants none of it
    void drawPin(Canvas c, ItemEntry e, int x, int y, int w, int h, boolean hovered) {
        boolean pinned = Pinned.isItem(e.name());
        if (!pinned && !hovered) {
            return;
        }
        int[] z = pinZone(x, y, w, h);
        float radius = theme.pxf(Theme.CONTROL_RADIUS);
        int is = theme.px(Theme.ICON);
        if (pinned) {
            c.fillRoundRect(z[0], z[1], z[2], z[3], radius, theme.color(Theme.ACCENT));
            c.icon(Icon.PIN, z[0] + (z[2] - is) / 2f, z[1] + (z[3] - is) / 2f, is, theme.color(Theme.ON_ACCENT));
        } else {
            c.fillRoundRect(z[0], z[1], z[2], z[3], radius, theme.color(Theme.RAISED));
            c.strokeRoundRect(z[0], z[1], z[2], z[3], radius, theme.color(Theme.HAIRLINE));
            c.icon(Icon.PIN, z[0] + (z[2] - is) / 2f, z[1] + (z[3] - is) / 2f, is, theme.color(Theme.TEXT_2));
        }
    }

    // the pin button's rect within a cell, for hit testing a click against it. sits inside the tile's rounded corner
    // so the pin never pokes out past the tile fill
    int[] pinZone(int x, int y, int w, int h) {
        int s = theme.px(Theme.ICON + Theme.SPACE_1);
        int off = theme.px(Theme.SPACE_2);
        return new int[] {x + off, y + off, s, s};
    }
}
