package adris.altoclef.ui.gui.kit;

// rows and columns for the boring cases. a widget with a preferred size keeps it, one that says -1 is a flex widget
// and splits whatever is left. most tabs place things by hand, this is for the toolbar style stuff
public final class Layout {

    private Layout() {
    }

    // places widgets left to right in [x, x + w) at y with height h. returns the x after the last one
    public static int row(int x, int y, int w, int h, int gap, Widget... widgets) {
        int shown = 0;
        int fixed = 0;
        int flex = 0;
        for (Widget k : widgets) {
            if (!k.visible()) {
                continue;
            }
            shown++;
            int pw = k.preferredWidth();
            if (pw < 0) {
                flex++;
            } else {
                fixed += pw;
            }
        }
        int left = Math.max(0, w - fixed - Math.max(0, shown - 1) * gap);
        int flexW = flex == 0 ? 0 : left / flex;
        int extra = flex == 0 ? 0 : left - flexW * flex;
        int cx = x;
        for (Widget k : widgets) {
            if (!k.visible()) {
                continue;
            }
            int pw = k.preferredWidth();
            int kw = pw >= 0 ? pw : flexW + (extra-- > 0 ? 1 : 0);
            k.setBounds(cx, y, kw, h);
            cx += kw + gap;
        }
        return cx - (shown > 0 ? gap : 0);
    }

    // places widgets top to bottom in [y, y + h) at x with width w. returns the y after the last one
    public static int column(int x, int y, int w, int h, int gap, Widget... widgets) {
        int shown = 0;
        int fixed = 0;
        int flex = 0;
        for (Widget k : widgets) {
            if (!k.visible()) {
                continue;
            }
            shown++;
            int ph = k.preferredHeight();
            if (ph < 0) {
                flex++;
            } else {
                fixed += ph;
            }
        }
        int left = Math.max(0, h - fixed - Math.max(0, shown - 1) * gap);
        int flexH = flex == 0 ? 0 : left / flex;
        int extra = flex == 0 ? 0 : left - flexH * flex;
        int cy = y;
        for (Widget k : widgets) {
            if (!k.visible()) {
                continue;
            }
            int ph = k.preferredHeight();
            int kh = ph >= 0 ? ph : flexH + (extra-- > 0 ? 1 : 0);
            k.setBounds(x, cy, w, kh);
            cy += kh + gap;
        }
        return cy - (shown > 0 ? gap : 0);
    }
}
