package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualView;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// one scrollbar for the tiles and the rows below them. the row list still only builds its visible editors
final class SettingsBrowser extends VirtualView<SettingView> {

    private final CommonSettings common;
    private final RowWidgets<SettingView> list;

    SettingsBrowser(Theme theme, CommonSettings common, RowWidgets<SettingView> list) {
        super(theme);
        this.common = common;
        this.list = list;
        list.setScrollbarVisible(false);
        // tile coordinates are relative to the tile block, the scroller counts from the top of everything
        common.setReveal((top, bottom) -> scroller.ensureVisible(top + lead(), bottom + lead(), now));
    }

    // the scrollbar lives in this strip. the owner widens the browser by it so the cards still line up with the toolbar
    int gutterWidth() {
        return gutter();
    }

    private boolean showCommon() {
        return common.visible() && common.size() > 0;
    }

    // with nothing under it a heading would just be a label for a blank. the tiles carry the page on their own then
    private boolean showAdvancedHeading() {
        return showCommon() && !list.items().isEmpty();
    }

    // the content already leaves a GAP under the toolbar, so the first heading tops up to a full SECTION of air
    private int lead() {
        return showCommon() ? theme.px(Theme.SECTION - Theme.GAP) : 0;
    }

    private int headerHeight() {
        if (!showCommon()) {
            return 0;
        }
        int head = lead() + common.heightFor(Math.max(0, w - gutter()));
        return showAdvancedHeading() ? head + theme.px(Theme.SECTION) + CommonSettings.headingHeight(theme) : head;
    }

    @Override
    protected int contentHeight() {
        return headerHeight() + list.items().size() * list.rowHeight();
    }

    @Override
    protected int step() {
        return list.rowHeight();
    }

    @Override
    protected void layout() {
        super.layout();
        sync();
    }

    private void sync() {
        int off = Math.round(scroller.offset(now));
        int header = headerHeight();
        int width = Math.max(0, w - gutter());
        common.setBounds(x, y - off + lead(), width, common.heightFor(width));
        int top = Math.min(h, Math.max(0, header - off));
        // the list takes its own gutter off its rows, so it gets ours back and the rows end up exactly as wide as the tiles
        list.setBounds(x, y + top, w, Math.max(0, h - top));
        list.scroller().snapTo(Math.max(0, off - header));
    }

    void refresh(boolean resetScroll) {
        if (resetScroll) {
            scroller.snapTo(0f);
        }
        layout();
    }

    @Override
    public List<Widget> children() {
        return common.visible() ? List.of(common, list) : List.of(list);
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        sync();
        return super.mouseMove(contains(mx, my) ? mx : -Float.MAX_VALUE, contains(mx, my) ? my : -Float.MAX_VALUE);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            return true;
        }
        sync();
        for (Widget child : children()) {
            if (child.mouseDown(mx, my, button, mods)) {
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean handled = super.mouseUp(mx, my, button, mods);
        if (!contains(mx, my)) {
            mx = -Float.MAX_VALUE;
            my = -Float.MAX_VALUE;
        }
        for (Widget child : children()) {
            handled |= child.mouseUp(mx, my, button, mods);
        }
        return handled;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (super.mouseDrag(mx, my, button, dx, dy)) {
            return true;
        }
        for (Widget child : children()) {
            if (child.mouseDrag(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        sync();
        c.pushClip(x, y, w, h);
        try {
            if (showCommon()) {
                common.draw(c, f);
                if (showAdvancedHeading()) {
                    float top = common.y() + common.h() + theme.px(Theme.SECTION);
                    c.text("Advanced settings", x, top + c.ascent(theme.label), theme.label, theme.color(Theme.TEXT));
                }
            }
            drawList(c, f);
        } finally {
            c.popClip();
        }
        drawScrollbar(c);
    }

    // one card around the rows, as tall as the rows are. it scrolls with them, so the top and bottom edge are only
    // there when you can see the first and last row
    private void drawList(Canvas c, Frame f) {
        int n = list.items().size();
        if (n == 0) {
            // an empty list still says why, but under tiles it would be a stray sentence
            if (!showCommon()) {
                list.draw(c, f);
            }
            return;
        }
        int rowH = list.rowHeight();
        int top = list.y() - Math.round(list.scroller().offset(now));
        theme.card(c, list.x(), top, list.w() - gutter(), n * rowH, theme.pxf(Theme.CARD_RADIUS), false);
        // rows are pooled and do not know how many there are, the corners of the hover fill need to
        for (Widget k : list.children()) {
            if (k instanceof SettingRow row) {
                row.setCount(n);
            }
        }
        list.draw(c, f);
    }
}
