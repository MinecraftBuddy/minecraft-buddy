package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.kit.VirtualGrid;

import java.util.List;

// the numbers the game has been quietly keeping: distance walked, blocks mined, mobs killed, that sort of thing.
// the facade formats them, this lays out one line cards (name left, number right) and refreshes on a tick so a
// live session moves
public final class StatsTab implements Tab {

    private static final int REFRESH_TICKS = 20;
    // a cell is this wide at least: the full size panel fits two of them side by side, a small window gets one
    private static final int CELL_W = 340;
    private static final int HEADING_H = 20;
    private static final int SUB_H = 16;

    private final Theme theme;
    private final BotFacade facade;
    private final View view;
    private List<StatsAccess.Stat> stats = List.of();
    private int ticks;

    public StatsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.view = new View();
    }

    @Override
    public String id() {
        return "stats";
    }

    @Override
    public String title() {
        return "Stats";
    }

    @Override
    public Icon icon() {
        return Icon.STATS;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public void onShown() {
        ticks = 0;
        refresh();
    }

    private void refresh() {
        Safe.run("stats refresh", () -> {
            List<StatsAccess.Stat> got = facade.stats().snapshot();
            stats = got == null ? List.of() : got;
            view.cards.setItems(stats);
        });
    }

    private final class View extends Widget {

        private final VirtualGrid<StatsAccess.Stat> cards;

        View() {
            super(StatsTab.this.theme);
            cards = new VirtualGrid<>(theme, theme.px(CELL_W), theme.px(Theme.ROW_H + Theme.SPACE_2 + Theme.GAP), this::drawCard);
        }

        // where the cards start: heading, its sub line, then SECTION_BELOW before the first row
        private int gridTop() {
            int pad = theme.px(Theme.PAD);
            return y + pad + theme.px(HEADING_H) + theme.px(Theme.SPACE_1) + theme.px(SUB_H) + theme.px(Theme.SECTION_BELOW);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int top = gridTop();
            // each cell keeps SPACE_1 of air on every side, so the grid sticks out that far to line the cards up
            int inset = theme.px(Theme.SPACE_1);
            int width = w - 2 * pad + 2 * inset;
            cards.setBounds(x + pad - inset, top - inset, width, Math.max(0, y + h - pad - top + inset));
        }

        @Override
        public List<Widget> children() {
            return List.of(cards);
        }

        @Override
        public void tick() {
            super.tick();
            if (++ticks % REFRESH_TICKS == 0) {
                refresh();
            }
        }

        // one card per number: the name on the left, the number on the right. flat, no hover, nothing to click
        private void drawCard(Canvas c, Frame f, StatsAccess.Stat stat, int index, int cx, int cy, int cw, int ch, boolean hot) {
            int in = theme.px(Theme.SPACE_1);
            int cardH = ch - 2 * in;
            theme.card(c, cx + in, cy + in, cw - 2 * in, cardH, theme.pxf(Theme.CARD_RADIUS), false);
            float left = cx + in + theme.px(Theme.PAD);
            float right = cx + cw - in - theme.px(Theme.PAD);
            TextStyle value = theme.body.asTabular();
            String number = c.ellipsize(stat.value(), value, Math.max(0, (right - left) / 2f));
            float nw = c.measure(number, value);
            float mid = cy + in + cardH / 2f;
            c.text(number, right - nw, mid + (c.ascent(value) - c.descent(value)) / 2f, value, theme.color(Theme.TEXT_2));
            String name = c.ellipsize(stat.label(), theme.label, Math.max(0, right - nw - theme.px(Theme.SPACE_3) - left));
            c.text(name, left, mid + (c.ascent(theme.label) - c.descent(theme.label)) / 2f, theme.label, theme.color(Theme.TEXT));
        }

        @Override
        public void draw(Canvas c, Frame f) {
            int pad = theme.px(Theme.PAD);
            float top = y + pad;
            c.text("Your numbers", x + pad, top + c.ascent(theme.label), theme.label, theme.color(Theme.TEXT));
            top += theme.px(HEADING_H) + theme.px(Theme.SPACE_1);
            String sub = "What the game has seen you do. Distances count every kind of travel.";
            c.text(c.ellipsize(sub, theme.small, w - 2 * pad), x + pad, top + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_2));
            cards.draw(c, f);
            if (stats.isEmpty()) {
                c.text(c.ellipsize("No numbers yet. Join a world and move around a bit.", theme.small, w - 2 * pad), x + pad,
                        gridTop() + c.ascent(theme.small), theme.small, theme.color(Theme.TEXT_3));
            }
        }
    }
}
