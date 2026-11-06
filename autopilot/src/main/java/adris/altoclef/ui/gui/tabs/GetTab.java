package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.ItemsAccess.BasketLine;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.CategoryTabs;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Segmented;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualGrid;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;
import adris.altoclef.ui.gui.model.CreativeCategories;
import adris.altoclef.ui.gui.model.ItemSearch;
import adris.altoclef.ui.gui.model.Pinned;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// the shopping tab: pick items from the grid, they pile up in the basket, one button sends the whole basket as a
// single #get (or #mine) command. the basket is per mode, so flipping to Mine does not eat your Get list
public final class GetTab implements Tab {

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final Map<String, ItemEntry> getByName = new HashMap<>();
    private final Map<String, ItemEntry> mineByName = new HashMap<>();
    private final GetTiles tiles;
    private final Segmented seg;
    private final CategoryTabs categories;
    private final VirtualGrid<ItemEntry> grid;
    private final GetRecentStrip strip;
    private final GetBasketPanel panel;
    private final Content content;
    private List<ItemEntry> getList = List.of();
    private List<ItemEntry> mineList = List.of();
    private List<ItemEntry> shown = List.of();
    private boolean mine;
    private String query = "";
    private String category = "";
    private boolean dirty;

    public GetTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        tiles = new GetTiles(theme);

        seg = new Segmented(theme, List.of("Get", "Mine"), 0, i -> setMine(i == 1));
        categories = new CategoryTabs(theme, this::pickCategory);
        grid = new VirtualGrid<>(theme, tiles.cellW(), tiles.cellH(),
                (c, f, e, i, x, y, w, h, hot) -> {
                    tiles.draw(c, e, countOf(e.name()), x, y, w, h, hot);
                    tiles.drawPin(c, e, x, y, w, h, hot);
                });
        grid.setKeyFunction(ItemEntry::name);
        grid.onClick((e, i, mods, mx, my, cx, cy, cw, ch) -> {
            int[] z = tiles.pinZone(cx, cy, cw, ch);
            if (mx >= z[0] && mx < z[0] + z[2] && my >= z[1] && my < z[1] + z[3]) {
                togglePin(e);
            } else {
                addEntry(e, mods, true);
            }
        });
        strip = new GetRecentStrip(theme, tiles, this::countOf, (e, mods) -> addEntry(e, mods, true));
        panel = new GetBasketPanel(theme, new GetBasketRow.Actions() {
            @Override
            public void step(String name, int delta) {
                basket().step(name, delta);
                dirty = true;
            }

            @Override
            public void set(String name, int count) {
                basket().set(name, count);
                dirty = true;
            }

            @Override
            public void remove(String name) {
                basket().remove(name);
                dirty = true;
            }

            @Override
            public int countOf(String name) {
                return basket().contains(name) ? basket().count(name) : -1;
            }

            @Override
            public void blur() {
                ctx.blur();
            }

            @Override
            public void setLimit(int n) {
                basket().setLimit(n);
                dirty = true;
            }

            @Override
            public int limit() {
                return basket().limit();
            }
        }, this::send, () -> {
            basket().clear();
            dirty = true;
        });
        content = new Content(theme);
        reload();
        panel.bind(basket(), mine, this::resolve);
        applyFilter(true);
    }

    @Override
    public String id() {
        return "get";
    }

    @Override
    public String title() {
        return "Get";
    }

    @Override
    public Icon icon() {
        return Icon.GET;
    }

    @Override
    public Widget content() {
        return content;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search items";
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q;
        applyFilter(true);
    }

    // enter in the search box: the first match goes in the basket, so typing "iron" and enter is one hand
    public void onSearchSubmit(String q) {
        ItemEntry first = ItemSearch.first(categorySource(), q);
        if (first != null) {
            addEntry(first, 0, false);
        }
    }

    @Override
    public void onShown() {
        reload();
        panel.refresh();
        applyFilter(false);
    }

    // ----- state

    private Basket basket() {
        return GetRecent.basket(mine);
    }

    // a fresh session, for tests and previews: the baskets and recent list are static on purpose
    public static void resetSession() {
        GetRecent.clear();
    }

    private List<ItemEntry> source() {
        return mine ? mineList : getList;
    }

    private int countOf(String name) {
        Basket b = basket();
        return b.contains(name) ? b.count(name) : -1;
    }

    private ItemEntry resolve(Basket.Line l) {
        ItemEntry e = (mine ? mineByName : getByName).get(l.name());
        return e != null ? e : new ItemEntry(l.name(), ItemEntry.titleCase(l.name()), "minecraft:" + l.name(), l.maxStack());
    }

    private static List<ItemEntry> load(String what, Supplier<List<ItemEntry>> from) {
        try {
            List<ItemEntry> got = from.get();
            List<ItemEntry> clean = new ArrayList<>();
            for (ItemEntry e : got == null ? List.<ItemEntry>of() : got) {
                // a bad entry from a facade is skipped, not a crash in the first search keystroke
                if (e == null || e.name() == null || e.name().isBlank()) {
                    continue;
                }
                String icon = e.iconItemId() == null || e.iconItemId().isBlank() ? "minecraft:" + e.name() : e.iconItemId();
                String shown = e.displayName() == null || e.displayName().isBlank() ? ItemEntry.titleCase(e.name()) : e.displayName();
                String category = e.category() == null ? "" : e.category();
                clean.add(new ItemEntry(e.name(), shown, icon, Math.max(1, e.maxStack()), category));
            }
            return clean;
        } catch (Throwable t) {
            Safe.report("get tab " + what, t);
            return List.of();
        }
    }

    private void reload() {
        getList = load("gettable", facade.items()::gettable);
        mineList = load("mineable", facade.items()::mineable);
        getByName.clear();
        mineByName.clear();
        for (ItemEntry e : getList) {
            getByName.put(e.name(), e);
        }
        for (ItemEntry e : mineList) {
            mineByName.put(e.name(), e);
        }
    }

    private void setMine(boolean m) {
        if (mine == m) {
            return;
        }
        // a number being typed belongs to the basket it was typed in: commit it before the basket underneath changes
        // (only then: blurring unconditionally would drop the keyboard focus off the Get|Mine control itself)
        if (panel.typing()) {
            ctx.blur();
        }
        flush();
        mine = m;
        category = "";
        panel.bind(basket(), mine, this::resolve);
        applyFilter(true);
    }

    private List<ItemEntry> categorySource() {
        if (category.isEmpty()) {
            return source();
        }
        return source().stream().filter(e -> category.equals(CreativeCategories.group(e.category()))).toList();
    }

    private List<String> categoryOptions() {
        List<String> out = new ArrayList<>();
        out.add("All");
        for (String group : CreativeCategories.GROUPS) {
            if (source().stream().anyMatch(e -> group.equals(CreativeCategories.group(e.category())))) {
                out.add(group);
            }
        }
        return out;
    }

    private void pickCategory(int i) {
        List<String> opts = categoryOptions();
        String chosen = i >= 0 && i < opts.size() ? opts.get(i) : "All";
        category = "All".equals(chosen) ? "" : chosen;
        applyFilter(true);
    }

    private void togglePin(ItemEntry e) {
        boolean now = Pinned.toggleItem(e.name());
        ctx.toast(now ? "Pinned " + e.displayName() : "Unpinned " + e.displayName(), null);
        applyFilter(false);
    }

    private void applyFilter(boolean toTop) {
        List<String> opts = categoryOptions();
        if (!category.isEmpty() && !opts.contains(category)) {
            category = "";
        }
        List<ItemEntry> filtered = ItemSearch.filter(categorySource(), query);
        // searches keep their ranking; browsing groups by category, then name, with pins first. sources with
        // no category information keep their own order
        if (query.trim().isEmpty()) {
            filtered = new ArrayList<>(filtered);
            boolean anyCategory = filtered.stream().anyMatch(e -> e.category() != null && !e.category().isEmpty());
            if (anyCategory) {
                Comparator<ItemEntry> byCategory = Comparator
                        .comparingInt((ItemEntry e) -> category.isEmpty() ? CreativeCategories.GROUPS.indexOf(CreativeCategories.group(e.category())) : 0)
                        .thenComparing((ItemEntry e) -> e.displayName() == null || e.displayName().isBlank() ? e.name() : e.displayName(),
                                String.CASE_INSENSITIVE_ORDER);
                filtered.sort(byCategory);
            }
            List<ItemEntry> pinned = new ArrayList<>();
            List<ItemEntry> rest = new ArrayList<>();
            for (ItemEntry e : filtered) {
                (Pinned.isItem(e.name()) ? pinned : rest).add(e);
            }
            pinned.addAll(rest);
            filtered = pinned;
        }
        shown = filtered;
        grid.setItems(shown);
        if (toTop) {
            grid.scroller().snapTo(0f);
        }
        List<ItemEntry> recent = new ArrayList<>();
        Map<String, ItemEntry> by = mine ? mineByName : getByName;
        for (String name : GetRecent.recent(mine)) {
            ItemEntry e = by.get(name);
            if (e != null && (category.isEmpty() || category.equals(CreativeCategories.group(e.category())))
                    && recent.size() < GetRecentStrip.MAX) {
                recent.add(e);
            }
        }
        strip.setEntries(recent);
        int at = category.isEmpty() ? 0 : Math.max(0, opts.indexOf(category));
        categories.setOptions(opts, at);
        content.relayout();
    }

    private void flush() {
        if (dirty) {
            dirty = false;
            panel.refresh();
            content.relayout();
        }
    }

    // ----- actions

    // a mine basket is a set of blocks, so clicking one that is already in takes it out (enter in the search never does)
    private void addEntry(ItemEntry e, int mods, boolean toggle) {
        flush();
        Basket b = basket();
        int stack = Math.max(1, e.maxStack());
        if (mine && toggle && b.contains(e.name())) {
            b.remove(e.name());
        } else {
            b.add(e.name(), Basket.stepFor(mods, stack), stack);
        }
        panel.refresh();
        content.relayout();
        panel.reveal(e.name());
    }

    private void send() {
        flush();
        Basket b = basket();
        if (b.isEmpty()) {
            return;
        }
        List<BasketLine> lines = b.toCommandLines();
        Result r;
        try {
            r = mine ? facade.items().mine(lines) : facade.items().get(lines);
            if (r == null) {
                r = Result.fail("Nothing came back from the bot. Try again.");
            }
        } catch (Throwable t) {
            Safe.report("get tab send", t);
            r = Result.fail("Could not send that. Try again.");
        }
        ctx.toast(r);
        if (r.ok()) {
            List<String> names = new ArrayList<>();
            for (BasketLine l : lines) {
                names.add(l.name());
            }
            GetRecent.record(mine, names);
            b.clear();
            panel.refresh();
            applyFilter(false);
        }
    }

    // ----- for tests

    Basket basketFor(boolean mineMode) {
        return GetRecent.basket(mineMode);
    }

    boolean mineMode() {
        return mine;
    }

    List<ItemEntry> shown() {
        return shown;
    }

    VirtualGrid<ItemEntry> grid() {
        return grid;
    }

    GetRecentStrip strip() {
        return strip;
    }

    GetBasketPanel panel() {
        return panel;
    }

    Segmented segmented() {
        return seg;
    }

    void flushForTest() {
        flush();
    }

    // ----- the widget

    private final class Content extends Widget {

        private final List<Widget> kids;

        Content(Theme theme) {
            super(theme);
            kids = List.of(seg, categories, strip, grid, panel);
        }

        // applyFilter runs once from the constructor, before the shell has given us a size
        void relayout() {
            if (w > 0 && h > 0) {
                layout();
            }
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int gap = theme.px(Theme.GAP);
            int trayH = Math.min(panel.dockHeight(), Math.max(panel.minimumDockHeight(), (h - 2 * pad) / 2));
            panel.setBounds(x + pad, y + h - pad - trayH, w - 2 * pad, trayH);
            int lx = x + pad;
            int lw = w - 2 * pad;
            int topH = theme.px(Theme.CONTROL_H);
            seg.setBounds(lx, y + pad, seg.preferredWidth(), topH);
            int catX = seg.x() + seg.w() + gap;
            int catW = Math.max(0, lw - seg.w() - gap);
            categories.setBounds(catX, y + pad, Math.min(categories.preferredWidth(), catW), topH);
            int cy = y + pad + topH + gap;
            int bottom = panel.y() - gap;
            // the strip only earns its space when the grid under it still has a couple of rows left
            boolean showStrip = !strip.entries().isEmpty() && query.trim().isEmpty()
                    && bottom - cy - strip.stripHeight() - gap >= 2 * tiles.cellH();
            strip.setVisible(showStrip);
            if (showStrip) {
                strip.setBounds(lx, cy, lw, strip.stripHeight());
                cy += strip.stripHeight() + gap;
            }
            grid.setBounds(lx, cy, Math.max(0, lw), Math.max(0, bottom - cy));
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            flush();
            return super.mouseDown(mx, my, button, mods);
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            flush();
            return super.scroll(mx, my, dy);
        }

        @Override
        public void tick() {
            flush();
            super.tick();
        }

        @Override
        public void draw(Canvas c, Frame f) {
            flush();
            seg.draw(c, f);
            categories.draw(c, f);
            drawCountText(c);
            if (strip.visible()) {
                strip.draw(c, f);
                float ly = grid.y() - theme.px(Theme.GAP) / 2f;
                c.fillRect(grid.x() + theme.px(Theme.SPACE_1), ly, grid.w() - theme.px(Theme.SPACE_2), 1, theme.color(Theme.HAIRLINE));
            }
            grid.draw(c, f);
            drawEmpty(c);
            panel.draw(c, f);
        }

        private void drawCountText(Canvas c) {
            String noun = mine ? "blocks" : "items";
            String t = query.trim().isEmpty()
                    ? shown.size() + " " + noun
                    : shown.size() + " of " + categorySource().size();
            float tw = c.measure(t, theme.small);
            float base = seg.y() + seg.h() / 2f + (c.ascent(theme.small) - c.descent(theme.small)) / 2f;
            // the same PAD from the edge as the controls on the left
            float right = x + w - theme.px(Theme.PAD);
            // keep the mode control readable even at the smallest resize
            if (right - tw >= categories.x() + categories.w() + theme.px(Theme.GAP)) {
                c.text(t, right - tw, base, theme.small, theme.color(Theme.TEXT_3));
            }
        }

        private void drawEmpty(Canvas c) {
            if (!shown.isEmpty() || grid.w() <= 0) {
                return;
            }
            String noun = mine ? "blocks" : "items";
            String msg = query.trim().isEmpty() ? "No " + noun + " to show yet." : "No " + noun + " match \"" + query.trim() + "\"";
            String fit = c.ellipsize(msg, theme.body, grid.w() - 2 * theme.px(Theme.PAD));
            float tw = c.measure(fit, theme.body);
            float base = grid.y() + grid.h() / 2f + (c.ascent(theme.body) - c.descent(theme.body)) / 2f;
            c.text(fit, grid.x() + (grid.w() - tw) / 2f, base, theme.body, theme.color(Theme.TEXT_2));
        }
    }
}
