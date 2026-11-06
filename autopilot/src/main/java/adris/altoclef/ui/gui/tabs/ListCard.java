package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualView;

// the one list treatment, the same thing settings does: a single grouped card behind the rows, a hairline between
// them, and a hover fill that follows the card's corners. every list outside settings borrows it from here
final class ListCard {

    private ListCard() {
    }

    // the scrollbar lives in this strip at the right of a list. owners widen the list by it so the card is as wide as
    // the controls above it
    static int gutter(Theme theme) {
        return theme.px(VirtualView.GUTTER);
    }

    // one card around all the rows, as tall as they are. it is drawn in the list's scroll space and clipped to the
    // viewport, so the top and bottom edge only exist while the first and last row do. call before the list draws
    static void card(Theme theme, Canvas c, VirtualView<?> list, long now, int rowsHeight) {
        if (list.items().isEmpty() || rowsHeight <= 0) {
            return;
        }
        int top = Math.round(list.y() - list.scroller().offset(now));
        c.pushClip(list.x(), list.y(), list.w(), list.h());
        try {
            theme.card(c, list.x(), top, list.w() - gutter(theme), rowsHeight, theme.pxf(Theme.CARD_RADIUS), false);
        } finally {
            c.popClip();
        }
    }

    // a hairline at the top of every row but the first, from the content column out to the card's right edge
    static void separator(Theme theme, Canvas c, int x, int y, int w, int index) {
        if (index > 0) {
            c.fillRect(x + theme.px(Theme.PAD), y, w - theme.px(Theme.PAD) - 1, 1, theme.color(Theme.HAIRLINE));
        }
    }

    // a row fill that stays inside the card's stroke, and the first and last row take the card's corners. a round rect
    // taller than the row, clipped to the row, gives rounded corners on one side and square on the other
    static void fill(Theme theme, Canvas c, int x, int y, int w, int h, boolean first, boolean last, int color) {
        float left = x + 1;
        float width = w - 2;
        if (!first && !last) {
            c.fillRect(left, y, width, h, color);
            return;
        }
        float top = first ? y + 1 : y;
        float bottom = last ? y + h - 1 : y + h;
        c.pushClip(left, top, width, bottom - top);
        try {
            c.fillRoundRect(left, first ? top : y - h, width, (last ? bottom : y + 2 * h) - (first ? top : y - h),
                    theme.pxf(Theme.CARD_RADIUS) - 1, color);
        } finally {
            c.popClip();
        }
    }
}
