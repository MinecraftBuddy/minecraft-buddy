package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess.BasketLine;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.CategoryTabs;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Basket;
import adris.altoclef.ui.gui.preview.FakeFacade;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the tab driven straight through its widget tree with the stub canvas: no shell, no pixels, just what a click does
public class GetTabTest {

    private static final int SHIFT = 1;
    private static final int CTRL = 2;

    static final class Ctx implements ShellContext {
        final Theme theme;
        final List<Result> toasts = new ArrayList<>();
        Widget focused;
        int blurs;

        Ctx(Theme theme) {
            this.theme = theme;
        }

        @Override
        public Theme theme() {
            return theme;
        }

        @Override
        public Clipboard clipboard() {
            return Clipboard.inMemory();
        }

        @Override
        public void toast(Result result) {
            toasts.add(result);
        }

        @Override
        public void toast(String line, String command) {
            toasts.add(Result.ok(line, command));
        }

        @Override
        public void focus(Widget widget) {
            if (focused != null && focused != widget) {
                focused.setFocused(false);
            }
            focused = widget;
            widget.setFocused(true);
        }

        @Override
        public void blur() {
            blurs++;
            if (focused != null) {
                focused.setFocused(false);
                focused = null;
            }
        }

        @Override
        public void openOverlay(Widget overlay) {
        }

        @Override
        public void closeOverlay() {
        }

        @Override
        public void requestClose() {
        }
    }

    // a facade whose items misbehave on demand, everything else is the fake
    static final class Wrapped implements BotFacade {
        final FakeFacade fake = new FakeFacade();
        boolean throwOnGet;
        boolean failOnGet;
        final List<BasketLine> mineLines = new ArrayList<>();

        @Override
        public SettingsAccess settings() {
            return fake.settings();
        }

        @Override
        public CommandsAccess commands() {
            return fake.commands();
        }

        @Override
        public ItemsAccess items() {
            return new ItemsAccess() {
                @Override
                public List<ItemEntry> gettable() {
                    return fake.items().gettable();
                }

                @Override
                public List<ItemEntry> mineable() {
                    return fake.items().mineable();
                }

                @Override
                public Result get(List<BasketLine> lines) {
                    if (throwOnGet) {
                        throw new IllegalStateException("boom");
                    }
                    if (failOnGet) {
                        return Result.fail("Not in a world.");
                    }
                    return fake.items().get(lines);
                }

                @Override
                public Result mine(List<BasketLine> lines) {
                    mineLines.addAll(lines);
                    return fake.items().mine(lines);
                }
            };
        }

        @Override
        public WorldAccess world() {
            return fake.world();
        }

        @Override
        public StatusAccess status() {
            return fake.status();
        }

        @Override
        public KeyNames keyNames() {
            return fake.keyNames();
        }

        @Override
        public Clipboard clipboard() {
            return fake.clipboard();
        }
    }

    private Theme theme;
    private Wrapped facade;
    private Ctx ctx;
    private GetTab tab;
    private Widget root;
    private long t;

    private CategoryTabs categories() {
        return (CategoryTabs) root.children().get(1);
    }

    private void categorizedItems() {
        facade.fake.items.gettable.clear();
        String[][] items = {
                {"stone", "Building Blocks"}, {"oak_log", "Natural Blocks"},
                {"iron_pickaxe", "Tools & Utilities"}, {"iron_sword", "Combat"},
                {"bread", "Food & Drinks"}, {"iron_ingot", "Ingredients"},
                {"repeater", "Redstone Blocks"}, {"mystery", ""}
        };
        for (String[] item : items) {
            facade.fake.items.gettable.add(new ItemEntry(item[0], ItemEntry.titleCase(item[0]), "minecraft:" + item[0], 64, item[1]));
        }
        tab.onShown();
        draw();
    }

    private void category(String name) {
        CategoryTabs tabs = categories();
        ctx.focus(tabs);
        tabs.keyDown(Keys.HOME, 0);
        int index = tabs.options().indexOf(name);
        assertTrue(index >= 0);
        for (int i = 0; i < index; i++) {
            tabs.keyDown(Keys.RIGHT, 0);
        }
        draw();
    }

    @Test
    public void categoriesCombineCreativeGroupsAndKeepTheBasket() {
        categorizedItems();
        assertEquals(List.of("All", "Blocks", "Tools", "Combat", "Food", "Materials", "Redstone", "Other"), categories().options());
        category("Blocks");
        assertEquals(List.of("oak_log", "stone"), tab.shown().stream().map(ItemEntry::name).toList());
        clickTile(0, 0);
        category("Food");
        assertEquals(List.of("bread"), tab.shown().stream().map(ItemEntry::name).toList());
        assertEquals(1, tab.basketFor(false).count("oak_log"));
        category("Other");
        assertEquals("mystery", shown(0).name());
    }

    @Test
    public void searchAndEnterStayInsideTheSelectedCategory() {
        categorizedItems();
        category("Tools");
        tab.onSearch("iron");
        assertEquals(List.of("iron_pickaxe"), tab.shown().stream().map(ItemEntry::name).toList());
        tab.onSearchSubmit("iron");
        assertTrue(tab.basketFor(false).contains("iron_pickaxe"));
        assertFalse(tab.basketFor(false).contains("iron_ingot"));
        tab.onSearch("bread");
        assertTrue(tab.shown().isEmpty());
        tab.onSearchSubmit("bread");
        assertFalse(tab.basketFor(false).contains("bread"));
        category("Food");
        assertEquals("bread", shown(0).name());
    }

    @Test
    public void aRemovedCategoryFallsBackToAllOnReload() {
        categorizedItems();
        category("Tools");
        facade.fake.items.gettable.removeIf(e -> e.name().equals("iron_pickaxe"));
        tab.onShown();
        assertEquals(0, categories().selected());
        assertEquals(7, tab.shown().size());
        assertFalse(categories().options().contains("Tools"));
    }

    @Test
    public void narrowCategoryArrowsReachEveryGroupWithoutOverlappingTheGrid() {
        categorizedItems();
        root.setBounds(0, 0, 360, 300);
        draw();
        CategoryTabs tabs = categories();
        assertTrue(tabs.preferredWidth() > tabs.w());
        for (int i = 1; i < tabs.options().size(); i++) {
            click(tabs.x() + tabs.w() - theme.px(10), tabs.y() + tabs.h() / 2f, 0);
            assertEquals(i, tabs.selected());
            assertFalse(tab.shown().isEmpty());
        }
        assertTrue(tabs.x() + tabs.w() <= root.x() + root.w());
        assertTrue(tabs.y() + tabs.h() <= tab.grid().y());
        assertTrue(tab.grid().h() > 0);
        assertTrue(tab.grid().y() + tab.grid().h() <= tab.panel().y());
    }

    @Before
    public void setUp() {
        GetTab.resetSession();
        theme = StubCanvas.theme();
        facade = new Wrapped();
        ctx = new Ctx(theme);
        tab = new GetTab(theme, facade, ctx);
        root = tab.content();
        root.setBounds(0, 0, 1000, 640);
        t = 10_000_000_000L;
        draw();
        // let the first rows finish fading in, a fading cell draws with a washed out colour
        t += 300_000_000L;
        draw();
    }

    private StubCanvas draw() {
        StubCanvas c = new StubCanvas(1000, 640);
        t += 16_000_000L;
        root.draw(c, new Frame(t, 1f / 60f, 0, 0));
        return c;
    }

    private void click(float x, float y, int mods) {
        root.mouseMove(x, y);
        root.mouseDown(x, y, 0, mods);
        root.mouseUp(x, y, 0, mods);
        draw();
    }

    private float[] tileCentre(int i) {
        var g = tab.grid();
        int cols = g.columns();
        int cw = (g.w() - theme.px(10)) / cols;
        int ch = g.cellHeight();
        return new float[] {g.x() + (i % cols) * cw + cw / 2f, g.y() + (i / cols) * ch + ch / 2f};
    }

    private void clickTile(int i, int mods) {
        float[] p = tileCentre(i);
        click(p[0], p[1], mods);
    }

    private ItemEntry shown(int i) {
        return tab.shown().get(i);
    }

    private GetBasketRow row(int i) {
        List<Widget> rows = tab.panel().list().children();
        return (GetBasketRow) rows.get(i);
    }

    private void clickWidget(Widget w, int mods) {
        click(w.x() + w.w() / 2f, w.y() + w.h() / 2f, mods);
    }

    // hover first so the row's hidden buttons exist, then press the one asked for
    private void clickRowButton(int i, String which, int mods) {
        GetBasketRow r = row(i);
        root.mouseMove(r.x() + 20, r.y() + r.h() / 2f);
        Widget b = switch (which) {
            case "plus" -> r.plus();
            case "minus" -> r.minus();
            default -> r.removeButton();
        };
        clickWidget(b, mods);
        root.tick();
        draw();
    }

    private Basket gb() {
        return tab.basketFor(false);
    }

    // ----- tiles

    @Test
    public void basketTrayUsesOnlyTheSpaceItsRowsNeed() {
        assertEquals(tab.grid().w(), tab.panel().w());
        assertTrue(tab.panel().h() < root.h() / 3);
        int emptyHeight = tab.panel().h();
        for (int i = 0; i < 5; i++) {
            clickTile(i, 0);
        }
        assertTrue(tab.panel().h() > emptyHeight);
        assertTrue(tab.grid().y() + tab.grid().h() < tab.panel().y());
        assertTrue(tab.panel().list().scroller().scrollable());
        assertTrue(tab.panel().goButton().y() + tab.panel().goButton().h() < root.y() + root.h());
    }

    @Test
    public void anEmptyDockIsOneSlimRowAndGrowsWithTheFirstItem() {
        assertEquals(theme.px(48), tab.panel().dockHeight());
        assertEquals(theme.px(48), tab.panel().minimumDockHeight());
        assertEquals(theme.px(48), tab.panel().h());
        clickTile(0, 0);
        assertTrue(tab.panel().dockHeight() > theme.px(48));
        assertTrue(tab.panel().minimumDockHeight() > theme.px(48));
        assertTrue(tab.panel().minimumDockHeight() <= tab.panel().dockHeight());
        clickWidget(tab.panel().clearButton(), 0);
        assertEquals(theme.px(48), tab.panel().h());
    }

    @Test
    public void aNarrowEmptyDockDropsTheKeyTipsButKeepsTheInstruction() {
        root.setBounds(0, 0, 700, 400);
        StubCanvas c = draw();
        assertTrue(c.texts().toString(), c.texts().stream().anyMatch(s -> s.contains("Click an item to add it")));
        assertTrue(c.texts().toString(), c.texts().stream().noneMatch(s -> s.contains("Shift-click")));
    }

    @Test
    public void theMineLimitGoesAwayWithTheLastBlock() {
        switchToMine();
        clickTile(0, 0);
        assertTrue(tab.panel().limitField().visible());
        clickWidget(tab.panel().clearButton(), 0);
        assertFalse(tab.panel().limitField().visible());
        assertFalse(tab.panel().limitPlus().visible());
    }

    @Test
    public void theBinIsAlwaysThereButTheStepButtonsWaitForTheMouse() {
        clickTile(0, 0);
        draw();
        assertTrue(row(0).removeButton().visible());
        assertFalse(row(0).plus().visible());
        assertFalse(row(0).minus().visible());
    }

    @Test
    public void plainClickAddsOne() {
        clickTile(0, 0);
        assertEquals(1, gb().count(shown(0).name()));
        clickTile(0, 0);
        assertEquals(2, gb().count(shown(0).name()));
        assertEquals(1, gb().size());
    }

    @Test
    public void shiftClickAddsAStackAndCtrlClickAddsTen() {
        // oak_log stacks to 64
        clickTile(0, SHIFT);
        assertEquals(shown(0).maxStack(), gb().count(shown(0).name()));
        clickTile(1, CTRL);
        assertEquals(10, gb().count(shown(1).name()));
    }

    @Test
    public void shiftClickOnAToolAddsOne() {
        int idx = -1;
        for (int i = 0; i < tab.shown().size(); i++) {
            if (shown(i).name().equals("iron_pickaxe")) {
                idx = i;
            }
        }
        assertTrue(idx >= 0);
        clickTile(idx, SHIFT);
        assertEquals(1, gb().count("iron_pickaxe"));
    }

    @Test
    public void tilesDrawTheirIconsInOurFont() {
        // new rows fade in and the item sprite waits for the fade to be mostly there
        t += 300_000_000L;
        StubCanvas c = draw();
        assertTrue(c.count("itemIcon") >= tab.grid().columns());
        for (StubCanvas.Call call : c.calls) {
            if (call.kind().equals("itemIcon")) {
                assertEquals(theme.itemIconPx() * 2, call.w(), 0.01f);
            }
        }
    }

    @Test
    public void inBasketTilesShowACountBadge() {
        clickTile(0, CTRL);
        StubCanvas c = draw();
        // the row on the right also says 10, but only the badge is dark text on the white pill
        boolean badge = c.calls.stream().anyMatch(k -> k.kind().equals("text") && k.text().equals("10")
                && k.argb() == Theme.ON_ACCENT);
        assertTrue(badge);
        float[] tile = tileCentre(0);
        for (StubCanvas.Call k : c.calls) {
            if (k.kind().equals("text") && k.text().equals("10") && k.argb() == Theme.ON_ACCENT) {
                assertTrue(Math.abs(k.x() - tile[0]) < theme.px(96) / 2f);
            }
        }
    }

    // ----- basket rows

    @Test
    public void plusAndMinusStepTheLine() {
        clickTile(0, 0);
        String name = shown(0).name();
        clickRowButton(0, "plus", 0);
        assertEquals(2, gb().count(name));
        clickRowButton(0, "plus", CTRL);
        assertEquals(12, gb().count(name));
        clickRowButton(0, "plus", SHIFT);
        assertEquals(12 + shown(0).maxStack(), gb().count(name));
        clickRowButton(0, "minus", 0);
        assertEquals(11 + shown(0).maxStack(), gb().count(name));
    }

    @Test
    public void minusToZeroRemovesTheLineInGet() {
        clickTile(0, 0);
        clickRowButton(0, "minus", 0);
        assertTrue(gb().isEmpty());
        assertTrue(tab.panel().list().items().isEmpty());
    }

    @Test
    public void xRemovesTheLine() {
        clickTile(0, 0);
        clickTile(1, 0);
        clickRowButton(0, "x", 0);
        assertEquals(1, gb().size());
        assertFalse(gb().contains(shown(0).name()));
        assertTrue(gb().contains(shown(1).name()));
    }

    @Test
    public void typingACountCommitsOnEnter() {
        clickTile(0, 0);
        String name = shown(0).name();
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("");
        f.charTyped('1');
        f.charTyped('2');
        assertTrue(f.keyDown(Keys.ENTER, 0));
        assertEquals(12, gb().count(name));
        assertEquals(1, ctx.blurs);
        assertEquals("12", f.text());
    }

    @Test
    public void countFieldOnlyTakesDigitsAndFourOfThem() {
        clickTile(0, 0);
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("");
        for (char ch : "a1-2.3456789".toCharArray()) {
            f.charTyped(ch);
        }
        assertEquals("1234", f.text());
        f.keyDown(Keys.ENTER, 0);
        assertEquals(1234, gb().count(shown(0).name()));
    }

    @Test
    public void zeroOrEmptyRemovesALineInGet() {
        clickTile(0, 0);
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("0");
        f.keyDown(Keys.ENTER, 0);
        root.tick();
        assertTrue(gb().isEmpty());

        clickTile(0, 0);
        f = row(0).countField();
        ctx.focus(f);
        f.setText("");
        f.keyDown(Keys.ENTER, 0);
        root.tick();
        assertTrue(gb().isEmpty());
    }

    @Test
    public void leavingTheFieldCommitsTheTypedNumber() {
        clickTile(0, 0);
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("30");
        ctx.blur();
        assertEquals(30, gb().count(shown(0).name()));
    }

    @Test
    public void escapeWalksAwayWithoutCommitting() {
        clickTile(0, 0);
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("77");
        // the shell blurs after a field says it did not use esc
        assertFalse(f.keyDown(Keys.ESCAPE, 0));
        ctx.blur();
        assertEquals(1, gb().count(shown(0).name()));
        assertEquals("1", f.text());
    }

    @Test
    public void arrowKeysStepTheFocusedCount() {
        clickTile(0, 0);
        TextField f = row(0).countField();
        ctx.focus(f);
        f.keyDown(Keys.UP, 0);
        assertEquals(2, gb().count(shown(0).name()));
        assertEquals("2", f.text());
        // ctrl steps ten, two minus ten is zero, zero removes the line
        f.keyDown(Keys.DOWN, CTRL);
        assertTrue(gb().isEmpty());
    }

    @Test
    public void staleCommitCannotResurrectARemovedLine() {
        clickTile(0, 0);
        String name = shown(0).name();
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("9");
        gb().remove(name);
        ctx.blur();
        assertFalse(gb().contains(name));
    }

    @Test
    public void aTypedNumberLandsOnItsOwnLineWhenAnotherLineAboveIsRemoved() {
        clickTile(0, 0);
        clickTile(1, 0);
        clickTile(2, 0);
        String b = shown(1).name();
        TextField f = row(1).countField();
        ctx.focus(f);
        f.setText("77");
        clickRowButton(0, "x", 0);
        assertFalse(gb().contains(shown(0).name()));
        assertEquals(77, gb().count(b));
        assertEquals(1, gb().count(shown(2).name()));
    }

    @Test
    public void aFocusedRowScrolledAwayCommitsToTheRightLine() {
        for (int i = 0; i < 24; i++) {
            clickTile(i, 0);
        }
        String first = shown(0).name();
        var list = tab.panel().list();
        // adding the last line scrolled the list down to it
        list.scroller().snapTo(0f);
        draw();
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("42");
        list.scroller().snapTo(list.scroller().maxOffset());
        draw();
        draw();
        root.tick();
        assertEquals(42, gb().count(first));
        for (int i = 1; i < 24; i++) {
            assertEquals(shown(i).name(), 1, gb().count(shown(i).name()));
        }
    }

    @Test
    public void switchingModeCommitsATypedCountIntoTheBasketItWasTypedIn() {
        clickTile(0, 0);
        String name = shown(0).name();
        TextField f = row(0).countField();
        ctx.focus(f);
        f.setText("50");
        var s = tab.segmented();
        // the segmented control acts on mouse down, the shell only blurs afterwards
        click(s.x() + s.w() - 10, s.y() + s.h() / 2f, 0);
        assertTrue(tab.mineMode());
        assertEquals(50, gb().count(name));
        assertTrue(tab.basketFor(true).isEmpty());
    }

    // ----- sending

    @Test
    public void sendFiresOneCommandForTheWholeBasketAndClearsIt() {
        clickTile(0, SHIFT);
        clickTile(1, CTRL);
        String a = shown(0).name();
        String b = shown(1).name();
        int total = shown(0).maxStack() + 10;
        assertTrue(tab.panel().goButton().enabled());
        assertEquals("Get " + total + " items", tab.panel().goButton().text());
        clickWidget(tab.panel().goButton(), 0);
        assertEquals(List.of("get " + a + " " + shown(0).maxStack() + " " + b + " 10"), facade.fake.actions);
        assertEquals(1, ctx.toasts.size());
        assertTrue(ctx.toasts.get(0).ok());
        assertTrue(gb().isEmpty());
        assertEquals(List.of(a, b), GetRecent.recent(false));
        assertEquals(List.of(shown(0), shown(1)), tab.strip().entries());
    }

    @Test
    public void emptyBasketButtonDoesNothing() {
        assertFalse(tab.panel().goButton().enabled());
        assertEquals("Get items", tab.panel().goButton().text());
        clickWidget(tab.panel().goButton(), 0);
        assertTrue(facade.fake.actions.isEmpty());
        assertTrue(ctx.toasts.isEmpty());
    }

    @Test
    public void failedSendKeepsTheBasketAndToastsTheFailure() {
        facade.failOnGet = true;
        clickTile(0, 0);
        clickWidget(tab.panel().goButton(), 0);
        assertEquals(1, ctx.toasts.size());
        assertFalse(ctx.toasts.get(0).ok());
        assertEquals(1, gb().size());
        assertTrue(GetRecent.recent(false).isEmpty());
    }

    @Test
    public void aThrowingFacadeBecomesAToast() {
        facade.throwOnGet = true;
        clickTile(0, 0);
        clickWidget(tab.panel().goButton(), 0);
        assertEquals(1, ctx.toasts.size());
        assertFalse(ctx.toasts.get(0).ok());
        assertEquals(1, gb().size());
    }

    @Test
    public void clearEmptiesTheBasketWithoutSending() {
        clickTile(0, 0);
        clickTile(1, 0);
        clickWidget(tab.panel().clearButton(), 0);
        root.tick();
        assertTrue(gb().isEmpty());
        assertTrue(facade.fake.actions.isEmpty());
    }

    // ----- mine mode

    private void switchToMine() {
        var s = tab.segmented();
        click(s.x() + s.w() - 10, s.y() + s.h() / 2f, 0);
        assertTrue(tab.mineMode());
    }

    @Test
    public void mineModeSwapsTheListAndKeepsBothBaskets() {
        clickTile(0, 0);
        List<String> getNames = tab.shown().stream().map(ItemEntry::name).toList();
        switchToMine();
        List<String> mineNames = tab.shown().stream().map(ItemEntry::name).toList();
        assertFalse(getNames.equals(mineNames));
        assertTrue(mineNames.contains("iron_ore"));
        assertTrue(tab.panel().list().items().isEmpty());
        var s = tab.segmented();
        click(s.x() + 10, s.y() + s.h() / 2f, 0);
        assertFalse(tab.mineMode());
        assertEquals(1, tab.panel().list().items().size());
    }

    @Test
    public void flippingModeFromTheKeyboardKeepsTheControlFocused() {
        var s = tab.segmented();
        ctx.focus(s);
        assertTrue(s.keyDown(Keys.RIGHT, 0));
        assertTrue(tab.mineMode());
        assertTrue(s.isFocused());
        assertTrue(s.keyDown(Keys.LEFT, 0));
        assertFalse(tab.mineMode());
    }

    @Test
    public void clickingABlockAddsItAndClickingAgainTakesItOut() {
        switchToMine();
        String name = shown(0).name();
        clickTile(0, 0);
        Basket m = tab.basketFor(true);
        assertTrue(m.contains(name));
        assertEquals("Mine 1 block", tab.panel().goButton().text());
        clickTile(0, 0);
        assertFalse(m.contains(name));
        // shift and ctrl mean nothing without per block counts, they just add the block
        clickTile(1, SHIFT);
        clickTile(2, CTRL);
        assertTrue(m.contains(shown(1).name()));
        assertTrue(m.contains(shown(2).name()));
        assertEquals(0, m.count(shown(1).name()));
    }

    @Test
    public void mineRowsHaveNoCountBoxAndTilesNoBadge() {
        switchToMine();
        clickTile(0, 0);
        assertEquals(1, row(0).children().size());
        StubCanvas c = draw();
        // the only dark on white text left is the button, a badge would be a bare number
        assertTrue(c.calls.stream().noneMatch(k -> k.kind().equals("text") && k.argb() == Theme.ON_ACCENT
                && k.text().matches("[0-9]+")));
    }

    @Test
    public void mineSendsOneCommandWithTheSharedLimit() {
        switchToMine();
        clickTile(0, 0);
        clickTile(1, 0);
        GetCountField lim = tab.panel().limitField();
        ctx.focus(lim);
        lim.setText("20");
        lim.keyDown(Keys.ENTER, 0);
        assertEquals(20, tab.basketFor(true).limit());
        assertEquals("Mine 2 blocks", tab.panel().goButton().text());
        clickWidget(tab.panel().goButton(), 0);
        assertEquals(List.of("mine " + shown(0).name() + " " + shown(1).name()), facade.fake.actions);
        assertEquals(List.of(new BasketLine(shown(0).name(), 20), new BasketLine(shown(1).name(), 20)), facade.mineLines);
        assertTrue(tab.basketFor(true).isEmpty());
        assertEquals(0, tab.basketFor(true).limit());
        assertEquals(List.of(shown(0).name(), shown(1).name()), GetRecent.recent(true));
        assertTrue(GetRecent.recent(false).isEmpty());
    }

    @Test
    public void theLimitBoxStepsAndEmptyMeansNoLimit() {
        switchToMine();
        clickTile(0, 0);
        Basket m = tab.basketFor(true);
        clickWidget(tab.panel().limitPlus(), 0);
        assertEquals(1, m.limit());
        clickWidget(tab.panel().limitPlus(), CTRL);
        assertEquals(11, m.limit());
        clickWidget(tab.panel().limitMinus(), 0);
        assertEquals(10, m.limit());
        GetCountField lim = tab.panel().limitField();
        ctx.focus(lim);
        lim.keyDown(Keys.UP, SHIFT);
        assertEquals(74, m.limit());
        assertEquals("74", lim.text());
        lim.setText("");
        lim.keyDown(Keys.ENTER, 0);
        assertEquals(0, m.limit());
        assertEquals("", lim.text());
        root.tick();
        assertTrue(tab.panel().summary().endsWith("no limit"));
    }

    @Test
    public void theLimitBoxOnlyExistsInMineMode() {
        clickTile(0, 0);
        assertFalse(tab.panel().limitField().visible());
        switchToMine();
        // an empty basket is one slim row, the limit shows up with the first block
        assertFalse(tab.panel().limitField().visible());
        clickTile(0, 0);
        assertTrue(tab.panel().limitField().visible());
    }

    // ----- search

    @Test
    public void searchFiltersAndRanksThePrefixFirst() {
        tab.onSearch("iron");
        assertFalse(tab.shown().isEmpty());
        for (ItemEntry e : tab.shown()) {
            assertTrue(e.name(), e.name().contains("iron"));
        }
        assertTrue(tab.shown().get(0).name().startsWith("iron"));
        tab.onSearch("");
        assertTrue(tab.shown().size() > 40);
    }

    @Test
    public void noMatchShowsTheEmptyLine() {
        tab.onSearch("zzzq");
        assertTrue(tab.shown().isEmpty());
        StubCanvas c = draw();
        assertNotNull(c.textCall("No items match \"zzzq\""));
    }

    @Test
    public void emptyBasketShowsTheHint() {
        StubCanvas c = draw();
        boolean found = c.texts().stream().anyMatch(s -> s.startsWith("Nothing added yet. Click an item to add it"));
        assertTrue(c.texts().toString(), found);
    }

    @Test
    public void searchResetsTheGridToTheTop() {
        tab.grid().scroller().snapTo(200f);
        tab.onSearch("a");
        assertEquals(0f, tab.grid().scroller().target(), 0.01f);
    }

    @Test
    public void enterInTheSearchAddsTheFirstMatch() {
        tab.onSearchSubmit("iron");
        assertEquals(1, gb().size());
        assertTrue(gb().lines().get(0).name().startsWith("iron"));
        tab.onSearchSubmit("iron");
        assertEquals(2, gb().lines().get(0).count());
        tab.onSearchSubmit("zzzq");
        assertEquals(1, gb().size());
    }

    // ----- recent strip

    @Test
    public void recentStripShowsAfterASendAndHidesWhileSearching() {
        assertFalse(tab.strip().visible());
        clickTile(0, 0);
        clickWidget(tab.panel().goButton(), 0);
        draw();
        assertTrue(tab.strip().visible());
        tab.onSearch("iron");
        assertFalse(tab.strip().visible());
        tab.onSearch("");
        assertTrue(tab.strip().visible());
    }

    @Test
    public void clickingARecentTileAddsIt() {
        clickTile(0, 0);
        String name = shown(0).name();
        clickWidget(tab.panel().goButton(), 0);
        draw();
        var s = tab.strip();
        int cw = (s.w() - theme.px(10)) / tab.grid().columns();
        click(s.x() + cw / 2f, s.y() + s.labelH() + theme.px(30), 0);
        assertEquals(1, gb().count(name));
    }

    @Test
    public void recentKeepsAtMostEightAndMostRecentFirst() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                clickTile(round * 4 + i, 0);
            }
            clickWidget(tab.panel().goButton(), 0);
        }
        assertEquals(8, tab.strip().entries().size());
        assertEquals(shown(8).name(), tab.strip().entries().get(0).name());
    }

    // ----- robustness

    @Test
    public void aTinyWindowStillDraws() {
        root.setBounds(0, 0, 10, 10);
        draw();
        root.setBounds(0, 0, 400, 200);
        draw();
        root.setBounds(0, 0, 1000, 640);
        draw();
    }

    @Test
    public void theTabAnswersToItsContract() {
        assertEquals("get", tab.id());
        assertEquals("Get", tab.title());
        assertTrue(tab.hasSearch());
        assertEquals("Search items", tab.searchPlaceholder());
    }
}
