package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// the basket dock. empty it is one slim row (the hint on the left, the dead button on the right) so the grid keeps the
// room; with items it grows into title, the lines, a hairline and a footer with the total and the big button. in mine
// mode the footer also has the one shared "stop after" box. it only shows what the basket says, the tab owns the
// basket and tells this to refresh() after it changes it
final class GetBasketPanel extends Widget {

    private final GetBasketRow.Actions actions;
    private final RowWidgets<GetBasketRow.Item> list;
    private final Button clear;
    private final GetGoButton go;
    private final IconButton limitMinus;
    private final IconButton limitPlus;
    private final GetCountField limitField;
    private final List<Widget> kids;
    private Basket basket = new Basket(false);
    private boolean mine;
    private Function<Basket.Line, ItemEntry> resolve = l -> new ItemEntry(l.name(), ItemEntry.titleCase(l.name()), "minecraft:" + l.name(), l.maxStack());
    private String summary = "";
    private long now;

    GetBasketPanel(Theme theme, GetBasketRow.Actions actions, Runnable onGo, Runnable onClear) {
        super(theme);
        this.actions = actions;
        list = new RowWidgets<>(theme, rowH(), () -> new GetBasketRow(theme, actions));
        list.setKeyFunction(it -> it.entry().name());
        clear = new Button(theme, "Clear", Button.Kind.GHOST, null, onClear);
        clear.setFocusable(false);
        go = new GetGoButton(theme, onGo);
        limitMinus = new IconButton(theme, Icon.MINUS, "Less", () -> nudgeLimit(-1));
        limitPlus = new IconButton(theme, Icon.PLUS, "More", () -> nudgeLimit(1));
        limitMinus.setFocusable(false);
        limitPlus.setFocusable(false);
        limitField = new GetCountField(theme, new GetCountField.Host() {
            @Override
            public void commit(int n) {
                actions.setLimit(n);
                limitField.sync(actions.limit());
            }

            @Override
            public void step(int dir, int mods) {
                actions.setLimit(Math.max(0, actions.limit() + dir * Basket.stepFor(mods, 64)));
                limitField.sync(actions.limit());
            }

            @Override
            public void done() {
                actions.blur();
            }
        });
        kids = List.of(list, clear, go, limitMinus, limitField, limitPlus);
        setLimitVisible(false);
    }

    private void nudgeLimit(int dir) {
        IconButton b = dir < 0 ? limitMinus : limitPlus;
        actions.setLimit(Math.max(0, actions.limit() + dir * Basket.stepFor(b.lastMods(), 64)));
        limitField.sync(actions.limit());
    }

    private void setLimitVisible(boolean on) {
        limitMinus.setVisible(on);
        limitPlus.setVisible(on);
        limitField.setVisible(on);
    }

    void bind(Basket b, boolean mineMode, Function<Basket.Line, ItemEntry> resolver) {
        basket = b;
        mine = mineMode;
        resolve = resolver;
        refresh();
    }

    void refresh() {
        List<GetBasketRow.Item> items = new ArrayList<>();
        for (Basket.Line l : basket.lines()) {
            items.add(new GetBasketRow.Item(resolve.apply(l), l.count(), mine));
        }
        list.setItems(items);
        summary = basket.summaryLine();
        go.setText(basket.buttonLabel());
        go.setEnabled(!basket.isEmpty());
        clear.setVisible(!basket.isEmpty());
        // a limit on nothing is a question nobody asked yet, it shows up with the first line
        setLimitVisible(mine && !basket.isEmpty());
        layout();
        // a box you are typing in keeps its text, everyone else follows the basket
        if (mine && !limitField.isFocused()) {
            limitField.bind(basket.limit(), "any");
        }
    }

    // true while any count box (a row's or the limit) has the keyboard
    boolean typing() {
        if (limitField.isFocused()) {
            return true;
        }
        for (Widget row : list.children()) {
            for (Widget k : row.children()) {
                if (k instanceof GetCountField && k.isFocused()) {
                    return true;
                }
            }
        }
        return false;
    }

    RowWidgets<GetBasketRow.Item> list() {
        return list;
    }

    GetGoButton goButton() {
        return go;
    }

    Button clearButton() {
        return clear;
    }

    GetCountField limitField() {
        return limitField;
    }

    IconButton limitPlus() {
        return limitPlus;
    }

    IconButton limitMinus() {
        return limitMinus;
    }

    String summary() {
        return summary;
    }

    // scrolls the line for a name into view, for when a click added it below the fold
    void reveal(String name) {
        int row = 0;
        for (Basket.Line l : basket.lines()) {
            if (l.name().equals(name)) {
                float h = list.rowHeight();
                list.scroller().ensureVisible(row * h, (row + 1) * h, now);
                return;
            }
            row++;
        }
    }

    // an empty dock is the hint and the button on one row, nothing else
    private int emptyH() {
        return theme.px(Theme.ROW_H + Theme.SPACE_3);
    }

    private int rowH() {
        return theme.px(Theme.ROW_H + Theme.SPACE_2);
    }

    private int titleH() {
        return theme.px(Theme.ROW_H);
    }

    // total and button, and in mine mode the limit row on top of them
    private int footerH() {
        int one = theme.px(Theme.SPACE_3 * 2 + Theme.CONTROL_H);
        return mine ? one + theme.px(Theme.CONTROL_H + Theme.SPACE_2) : one;
    }

    int dockHeight() {
        if (basket.isEmpty()) {
            return emptyH();
        }
        return titleH() + 1 + rowH() * Math.min(3, basket.size()) + footerH();
    }

    // one row is the least a basket with something in it can get by on
    int minimumDockHeight() {
        return basket.isEmpty() ? emptyH() : titleH() + 1 + rowH() + footerH();
    }

    @Override
    protected void layout() {
        int pad = theme.px(Theme.PAD);
        int bh = theme.px(Theme.CONTROL_H);
        int goW = Math.min(theme.px(200), w * 38 / 100);
        boolean empty = basket.isEmpty();
        if (empty) {
            list.setBounds(x, y, 0, 0);
            go.setBounds(x + w - pad - goW, y + (h - bh) / 2, goW, bh);
            return;
        }
        int cw = clear.preferredWidth();
        clear.setBounds(x + w - theme.px(Theme.SPACE_2) - cw, y + (titleH() - bh) / 2, cw, bh);
        int side = theme.px(Theme.SPACE_2);
        list.setBounds(x + side, y + titleH() + 1, w - 2 * side, Math.max(0, h - titleH() - 1 - footerH()));
        go.setBounds(x + w - pad - goW, y + h - theme.px(Theme.SPACE_3) - bh, goW, bh);
        // limit row: label on the left, [-] box [+] on the right
        int b = GetCountField.stepperPx(theme);
        int gap = theme.px(Theme.SPACE_1);
        int boxW = GetCountField.boxW(theme);
        int ly = y + h - footerH() + theme.px(Theme.SPACE_3);
        int right = x + w - pad;
        limitPlus.setBounds(right - b, ly + (bh - b) / 2, b, b);
        limitField.setBounds(limitPlus.x() - gap - boxW, ly, boxW, bh);
        limitMinus.setBounds(limitField.x() - gap - b, ly + (bh - b) / 2, b, b);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        now = f.nowNanos();
        // the dock sits over the grid, so it is the one card here that gets the lift
        theme.card(c, x, y, w, h, theme.pxf(Theme.CARD_RADIUS), true);
        int pad = theme.px(Theme.PAD);
        if (basket.isEmpty()) {
            drawHint(c, pad);
            go.draw(c, f);
            return;
        }

        float tb = y + titleH() / 2f + (c.ascent(theme.label) - c.descent(theme.label)) / 2f;
        c.text("Basket", x + pad, tb, theme.label, theme.color(Theme.TEXT));
        c.fillRect(x + 1, y + titleH(), w - 2, 1, theme.color(Theme.HAIRLINE));
        list.draw(c, f);
        clear.draw(c, f);

        int footTop = y + h - footerH();
        c.fillRect(x + 1, footTop, w - 2, 1, theme.color(Theme.HAIRLINE));
        if (mine) {
            float lb = limitField.y() + limitField.h() / 2f + (c.ascent(theme.body) - c.descent(theme.body)) / 2f;
            c.text("Stop after", x + pad, lb, theme.body, theme.color(Theme.TEXT_2));
            limitMinus.draw(c, f);
            limitField.draw(c, f);
            limitPlus.draw(c, f);
        }
        TextStyle small = theme.small;
        float summaryW = go.x() - x - pad - theme.px(Theme.GAP);
        String shown = c.ellipsize(summary, small, summaryW);
        float summaryBase = go.y() + go.h() / 2f + (c.ascent(small) - c.descent(small)) / 2f;
        c.text(shown, x + pad, summaryBase, small, theme.color(Theme.TEXT_2));
        go.draw(c, f);
    }

    // one line, and the actionable part is the last thing to go: the whole hint, then without the key tips, then just
    // the instruction, then an ellipsis for a window that is really not trying
    private void drawHint(Canvas c, int pad) {
        String act = mine ? "Click a block to add it." : "Click an item to add it.";
        String[] tiers = {
                mine ? "Nothing added yet. " + act + " Set a limit once one is in, or leave it empty to keep mining."
                        : "Nothing added yet. Click an item to add it; Shift-click adds a stack, Ctrl-click adds 10.",
                "Nothing added yet. " + act,
                act};
        TextStyle small = theme.small;
        float room = go.x() - x - pad - theme.px(Theme.GAP);
        String msg = null;
        for (String t : tiers) {
            if (c.measure(t, small) <= room) {
                msg = t;
                break;
            }
        }
        if (msg == null) {
            msg = c.ellipsize(act, small, room);
        }
        float base = y + h / 2f + (c.ascent(small) - c.descent(small)) / 2f;
        c.text(msg, x + pad, base, small, theme.color(Theme.TEXT_2));
    }
}
