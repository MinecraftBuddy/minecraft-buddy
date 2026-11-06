package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.FadeCanvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;

import java.util.List;

// one line of the basket: icon, the name with its id under it, and in a get basket [-] count [+] bin on the right. a mine
// row is just icon, name and bin, the mine limit lives under the list. the count box and the bin are always there, the
// [-] [+] fade in when the row is hovered or you are typing in it, so a long basket stays a quiet list
final class GetBasketRow extends RowWidgets.Row<GetBasketRow.Item> {

    // the bin sits in TEXT_3 and only goes red when you are actually on it
    private static final class BinButton extends IconButton {

        BinButton(Theme theme, Runnable onClick) {
            super(theme, Icon.TRASH, "Remove", onClick);
        }

        @Override
        protected void drawContent(Canvas c, int col, float hv) {
            float is = iconSize();
            int tint = Theme.lerp(theme.color(Theme.TEXT_3), theme.color(Theme.DANGER), hv);
            c.icon(icon, x + (w - is) / 2f, y + (h - is) / 2f, is, tint);
        }
    }

    record Item(ItemEntry entry, int count, boolean mine) {
    }

    interface Actions {
        void step(String name, int delta);

        void set(String name, int count);

        void remove(String name);

        // -1 when the line is gone
        int countOf(String name);

        void blur();

        // the shared mine limit, 0 is no limit
        void setLimit(int n);

        int limit();
    }

    private final Actions actions;
    private final IconButton minus;
    private final IconButton plus;
    private final IconButton remove;
    private final GetCountField count;
    private final List<Widget> getKids;
    private final List<Widget> mineKids;
    private final Anim hotA = new Anim(0f);
    private final FadeCanvas fade = new FadeCanvas();
    private Item item;
    private String shownName = "";
    private String shownId = "";
    private int iconX;
    private int index;

    GetBasketRow(Theme theme, Actions actions) {
        super(theme);
        this.actions = actions;
        minus = new IconButton(theme, Icon.MINUS, "Less", () -> nudge(-1));
        plus = new IconButton(theme, Icon.PLUS, "More", () -> nudge(1));
        remove = new BinButton(theme, () -> {
            if (item != null) {
                actions.remove(item.entry().name());
            }
        });
        minus.setFocusable(false);
        plus.setFocusable(false);
        remove.setFocusable(false);
        count = new GetCountField(theme, new GetCountField.Host() {
            @Override
            public void commit(int n) {
                if (item != null) {
                    actions.set(item.entry().name(), n);
                    syncText();
                }
            }

            @Override
            public void step(int dir, int mods) {
                if (item != null) {
                    actions.step(item.entry().name(), dir * Basket.stepFor(mods, item.entry().maxStack()));
                    syncText();
                }
            }

            @Override
            public void done() {
                actions.blur();
            }
        });
        getKids = List.of(minus, count, plus, remove);
        mineKids = List.of(remove);
        setControlsVisible(false);
    }

    IconButton minus() {
        return minus;
    }

    IconButton plus() {
        return plus;
    }

    IconButton removeButton() {
        return remove;
    }

    TextField countField() {
        return count;
    }

    String name() {
        return item == null ? null : item.entry().name();
    }

    private boolean mine() {
        return item != null && item.mine();
    }

    private void nudge(int dir) {
        IconButton b = dir < 0 ? minus : plus;
        if (item == null) {
            return;
        }
        actions.step(item.entry().name(), dir * Basket.stepFor(b.lastMods(), item.entry().maxStack()));
    }

    // pulls the number back from the basket, for when this row is focused and the list skipped rebinding it
    private void syncText() {
        if (item == null) {
            return;
        }
        int c = actions.countOf(item.entry().name());
        if (c >= 0) {
            count.sync(c);
        }
    }

    @Override
    public void bind(Item next, int idx) {
        item = next;
        index = idx;
        count.bind(next.mine() ? 0 : next.count(), "0");
        hotA.snap(hot() ? 1f : 0f);
        setControlsVisible(hot());
        refreshName();
    }

    // a narrow basket gives the name the room instead of the bigger icon, and a squashed window does not get a
    // two size icon that is taller than its own row
    private int iconPx() {
        int big = theme.itemIconPx() * 2;
        return w >= theme.px(270) && big + 2 * theme.px(Theme.SPACE_1) <= h ? big : theme.itemIconPx();
    }

    private int btn() {
        return GetCountField.stepperPx(theme);
    }

    @Override
    protected void layout() {
        int b = btn();
        int gap = theme.px(Theme.SPACE_1);
        int cw = GetCountField.boxW(theme);
        int ch = theme.px(Theme.CONTROL_H);
        int by = y + (h - b) / 2;
        // the list gives rows a SPACE_2 margin, this one makes the content line up with the card's PAD
        int right = x + w - theme.px(Theme.SPACE_2);
        remove.setBounds(right - b, by, b, b);
        plus.setBounds(remove.x() - gap - b, by, b, b);
        count.setBounds(plus.x() - gap - cw, y + (h - ch) / 2, cw, ch);
        minus.setBounds(count.x() - gap - b, by, b, b);
        iconX = x + theme.px(Theme.SPACE_2);
        refreshName();
    }

    private void refreshName() {
        if (item == null || w <= 0) {
            shownName = "";
            shownId = "";
            return;
        }
        // a mine row only has the bin on the right
        int stop = mine() ? remove.x() : minus.x();
        float room = Math.max(0, stop - theme.px(Theme.GAP) - (iconX + iconPx() + theme.px(Theme.SPACE_3)));
        shownName = theme.measurer.ellipsize(item.entry().displayName(), theme.body, room);
        shownId = theme.measurer.ellipsize(item.entry().name(), theme.small, room);
    }

    private void setControlsVisible(boolean on) {
        minus.setVisible(on && !mine());
        plus.setVisible(on && !mine());
    }

    private boolean hot() {
        return hovered || count.isFocused();
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        Widget d = super.mouseMove(mx, my);
        boolean on = hot();
        if (plus.visible() != (on && !mine())) {
            setControlsVisible(on);
            d = super.mouseMove(mx, my);
        }
        return d;
    }

    @Override
    public List<Widget> children() {
        return mine() ? mineKids : getKids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        if (item == null) {
            return;
        }
        long now = f.nowNanos();
        boolean hot = hot();
        if (plus.visible() != (hot && !mine())) {
            setControlsVisible(hot);
        }
        hotA.set(hot ? 1f : 0f, now, Theme.ANIM_FAST);
        float v = hotA.value(now);
        // a hairline between rows and no boxes, the hover fill is the only thing that ever sits behind one
        if (index > 0) {
            c.fillRect(x + theme.px(Theme.SPACE_2), y, w - 2 * theme.px(Theme.SPACE_2), 1, theme.color(Theme.HAIRLINE));
        }
        if (v > 0.01f) {
            c.fillRoundRect(x, y + theme.px(1), w, h - theme.px(2), theme.pxf(Theme.ROW_RADIUS), Theme.fade(theme.color(Theme.HOVER), v));
        }
        int s = iconPx();
        c.itemIcon(item.entry().iconItemId(), iconX, y + (h - s) / 2, s);
        float tx = iconX + s + theme.px(Theme.SPACE_3);
        // human name first, the id under it as the quiet second line, the pair centred on the row as one block
        float nameH = c.ascent(theme.body) + c.descent(theme.body);
        float idH = c.ascent(theme.small) + c.descent(theme.small);
        float top = y + (h - nameH - idH) / 2f;
        c.text(shownName, tx, top + c.ascent(theme.body), theme.body, theme.color(Theme.TEXT));
        c.text(shownId, tx, top + nameH + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_3));

        if (v > 0.02f && !mine()) {
            Canvas g = fade.reset(c, v, 0f);
            minus.draw(g, f);
            plus.draw(g, f);
        }
        remove.draw(c, f);
        if (!mine()) {
            count.draw(c, f);
        }
    }
}
