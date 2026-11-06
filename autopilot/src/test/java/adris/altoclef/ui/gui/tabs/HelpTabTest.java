package adris.altoclef.ui.gui.tabs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.CommandsAccess.CommandInfo;
import adris.altoclef.ui.gui.facade.ItemsAccess;
import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.preview.FakeFacade;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HelpTabTest {

    private static final long MS = 1_000_000L;

    // records what the tab asked of the chrome
    private static final class Ctx implements ShellContext {
        final Theme theme;
        final List<Result> toasts = new ArrayList<>();
        Widget focus;

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
            if (focus != null) {
                focus.setFocused(false);
            }
            focus = widget;
            widget.setFocused(true);
        }

        @Override
        public void blur() {
            if (focus != null) {
                focus.setFocused(false);
            }
            focus = null;
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

    private final StubCanvas canvas = new StubCanvas(1600, 900);
    private final Theme theme = new Theme(1f, canvas);
    private final FakeFacade facade = new FakeFacade();
    private final Ctx ctx = new Ctx(theme);
    private long ms = 1000;
    private HelpTab tab;

    private HelpTab make() {
        tab = new HelpTab(theme, facade, ctx);
        tab.content().setBounds(0, 0, 900, 640);
        frame();
        return tab;
    }

    private void frame() {
        tab.content().draw(canvas, new Frame(ms * MS, 0.016f, -1, -1));
    }

    private void advance(int millis) {
        ms += millis;
        frame();
    }

    private void click(float[] xy) {
        assertNotNull(xy);
        tab.content().mouseMove(xy[0], xy[1]);
        tab.content().mouseDown(xy[0], xy[1], 0, 0);
        tab.content().mouseUp(xy[0], xy[1], 0, 0);
    }

    private static List<String> names(List<CommandInfo> l) {
        return l.stream().map(CommandInfo::name).toList();
    }

    @Test
    public void listsEveryCommandAlphabetically() {
        make();
        List<String> n = names(tab.showing());
        assertEquals(facade.commands.list.size(), n.size());
        List<String> sorted = new ArrayList<>(n);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, n);
        assertTrue(tab.hasSearch());
        assertEquals("Search commands", tab.searchPlaceholder());
        assertEquals("help", tab.id());
        assertEquals("Help", tab.title());
    }

    @Test
    public void searchRanksAndNarrows() {
        make();
        tab.onSearch("go");
        assertEquals("goto", tab.showing().get(0).name());
        tab.onSearch("xyz");
        assertTrue(tab.showing().isEmpty());
        tab.onSearch("");
        assertEquals(facade.commands.list.size(), tab.showing().size());
    }

    @Test
    public void emptyRegistryDrawsItsMessage() {
        facade.commands.list.clear();
        make();
        assertTrue(canvas.texts().contains("No commands registered"));
    }

    @Test
    public void noMatchDrawsTheQuery() {
        make();
        tab.onSearch("xyz");
        canvas.calls.clear();
        frame();
        assertTrue(canvas.texts().contains("No command matches \"xyz\""));
    }

    @Test
    public void clickingARowOpensItAndAnotherClosesTheFirst() {
        make();
        click(tab.headerCenter("goto"));
        assertEquals("goto", tab.expandedCommand());
        advance(400);
        click(tab.headerCenter("follow"));
        assertEquals("follow", tab.expandedCommand());
        advance(400);
        click(tab.headerCenter("follow"));
        assertNull(tab.expandedCommand());
    }

    @Test
    public void theRowGrowsOverTimeNotAtOnce() {
        make();
        float[] before = tab.headerCenter("get");
        float[] below = tab.headerCenter("gui");
        click(tab.headerCenter("gamer"));
        advance(50);
        float[] mid = tab.headerCenter("gui");
        advance(400);
        float[] end = tab.headerCenter("gui");
        assertTrue(before[1] < below[1]);
        assertTrue("grows", end[1] > below[1] + 20);
        assertTrue("still moving at 50ms", mid[1] > below[1] && mid[1] < end[1]);
    }

    @Test
    public void clickingUsageFillsTheFieldAndFocusesIt() {
        make();
        click(tab.headerCenter("goto"));
        advance(400);
        click(tab.usageCenter("goto", 2));
        assertEquals("goto <x> <y> <z>", tab.field().text());
        assertSame(tab.field(), ctx.focus);
        assertTrue(tab.field().isFocused());
        // nothing was run
        assertNull(facade.commands.lastRun);
    }

    @Test
    public void clickingTheExplanationDoesNotFoldTheRow() {
        make();
        click(tab.headerCenter("goto"));
        advance(400);
        float[] header = tab.headerCenter("goto");
        // first line of the text, just under the header
        click(new float[] {header[0], header[1] + 44 / 2f + 18});
        assertEquals("goto", tab.expandedCommand());
    }

    @Test
    public void enterRunsTheLineWithoutThePrefix() {
        make();
        tab.field().setText("#goto 100 64 -30");
        ctx.focus(tab.field());
        assertTrue(tab.field().keyDown(Keys.ENTER, 0));
        assertEquals("goto 100 64 -30", facade.commands.lastRun);
        assertEquals(1, ctx.toasts.size());
        assertTrue(ctx.toasts.get(0).ok());
        assertEquals("", tab.field().text());
        assertTrue(tab.inlineText().startsWith("Ran goto 100 64 -30"));
        frame();
    }

    @Test
    public void runButtonRunsToo() {
        make();
        tab.field().setText("stop");
        frame();
        click(new float[] {tab.runButton().x() + 5, tab.runButton().y() + 5});
        assertEquals("stop", facade.commands.lastRun);
    }

    @Test
    public void runButtonIsDimWhileTheLineIsBlank() {
        make();
        frame();
        assertFalse(tab.runButton().enabled());
        click(new float[] {tab.runButton().x() + 5, tab.runButton().y() + 5});
        assertNull(facade.commands.lastRun);
        tab.field().setText("  # ");
        frame();
        assertFalse(tab.runButton().enabled());
        tab.field().setText("stop");
        frame();
        assertTrue(tab.runButton().enabled());
    }

    @Test
    public void aFailureKeepsTheTextAndToastsRed() {
        make();
        facade.commands.failNext = true;
        tab.field().setText("gett oak_log");
        ctx.focus(tab.field());
        tab.field().keyDown(Keys.ENTER, 0);
        assertEquals("gett oak_log", tab.field().text());
        assertTrue(tab.field().invalid());
        assertFalse(tab.lastResult().ok());
        assertEquals("Unknown command \"gett\"", tab.inlineText());
        assertEquals(1, ctx.toasts.size());
        assertFalse(ctx.toasts.get(0).ok());
        frame();
        assertEquals(Theme.DANGER, canvas.textCall("Unknown command \"gett\"").argb());
    }

    @Test
    public void typingAgainClearsTheRedBorder() {
        make();
        facade.commands.failNext = true;
        tab.field().setText("gett");
        ctx.focus(tab.field());
        tab.field().keyDown(Keys.ENTER, 0);
        assertTrue(tab.field().invalid());
        tab.field().charTyped('x');
        assertFalse(tab.field().invalid());
    }

    @Test
    public void upArrowWalksTheSessionHistory() {
        make();
        ctx.focus(tab.field());
        for (String line : new String[] {"stop", "follow players", "get stick 4"}) {
            tab.field().setText(line);
            tab.field().keyDown(Keys.ENTER, 0);
        }
        assertEquals("", tab.field().text());
        tab.field().setText("half");
        tab.field().keyDown(Keys.UP, 0);
        assertEquals("get stick 4", tab.field().text());
        tab.field().keyDown(Keys.UP, 0);
        assertEquals("follow players", tab.field().text());
        tab.field().keyDown(Keys.DOWN, 0);
        tab.field().keyDown(Keys.DOWN, 0);
        assertEquals("half", tab.field().text());
    }

    @Test
    public void historyStopsAtTwenty() {
        make();
        ctx.focus(tab.field());
        for (int i = 0; i < 30; i++) {
            tab.field().setText("get stick " + i);
            tab.field().keyDown(Keys.ENTER, 0);
        }
        assertEquals(20, tab.history().size());
    }

    @Test
    public void aThrowingFacadeBecomesAFailureNotACrash() {
        BotFacade broken = new BotFacade() {
            @Override
            public SettingsAccess settings() {
                return facade.settings();
            }

            @Override
            public CommandsAccess commands() {
                return new CommandsAccess() {
                    @Override
                    public List<CommandInfo> commands() {
                        return facade.commands.commands();
                    }

                    @Override
                    public Result run(String line) {
                        throw new IllegalStateException("boom");
                    }
                };
            }

            @Override
            public ItemsAccess items() {
                return facade.items();
            }

            @Override
            public WorldAccess world() {
                return facade.world();
            }

            @Override
            public StatusAccess status() {
                return facade.status();
            }

            @Override
            public KeyNames keyNames() {
                return facade.keyNames();
            }

            @Override
            public Clipboard clipboard() {
                return facade.clipboard();
            }
        };
        HelpTab t = new HelpTab(theme, broken, ctx);
        t.content().setBounds(0, 0, 900, 640);
        t.field().setText("stop");
        ctx.focus(t.field());
        t.field().keyDown(Keys.ENTER, 0);
        assertFalse(t.lastResult().ok());
        assertEquals(1, ctx.toasts.size());
    }

    @Test
    public void wrapKeepsLinesInsideTheWidthAndCutsHugeWords() {
        List<String> w = HelpList.wrap(canvas, "one two three four five six seven", theme.body, 100);
        for (String line : w) {
            assertTrue(line, canvas.measure(line, theme.body) <= 100);
        }
        assertEquals("one two three four five six seven", String.join(" ", w));
        List<String> huge = HelpList.wrap(canvas, "abcdefghijklmnopqrstuvwxyz", theme.body, 60);
        assertTrue(huge.size() > 1);
        assertEquals("abcdefghijklmnopqrstuvwxyz", String.join("", huge));
        assertEquals(List.of(""), HelpList.wrap(canvas, "", theme.body, 60));
    }

    private void sixtyCommands() {
        facade.commands.list.clear();
        for (int i = 0; i < 60; i++) {
            String n = String.format("c%02d", i);
            facade.commands.list.add(new CommandInfo(List.of(n), "Short " + n,
                    List.of("Long text for " + n + ".", "", "Usage:", "> " + n + " <x> - does x", "> " + n + " <x> <y> - does x and y")));
        }
    }

    @Test
    public void aRowFilteredAwayWhileOpenComesBackShut() {
        make();
        click(tab.headerCenter("goto"));
        advance(400);
        tab.onSearch("xyz");
        advance(50);
        tab.onSearch("");
        advance(400);
        assertNull(tab.expandedCommand());
        float[] mine = tab.headerCenter("mine");
        float[] get = tab.headerCenter("get");
        float[] gotoAt = tab.headerCenter("goto");
        // goto is back at normal height: its neighbours are one row apart
        assertEquals(theme.px(Theme.ROW_H), tab.headerCenter("gui")[1] - gotoAt[1], 0.5f);
        click(mine);
        advance(400);
        assertEquals("mine", tab.expandedCommand());
        assertEquals(theme.px(Theme.ROW_H), tab.headerCenter("gui")[1] - tab.headerCenter("goto")[1], 0.5f);
        assertNotNull(get);
    }

    @Test
    public void aNewSearchScrollsBackToTheTop() {
        sixtyCommands();
        make();
        tab.content().scroll(400, 300, -6f);
        advance(400);
        assertTrue(tab.headerCenter("c00")[1] < tab.list().y());
        tab.onSearch("c");
        advance(50);
        assertEquals(tab.list().y() + theme.px(Theme.ROW_H) / 2f, tab.headerCenter("c00")[1], 1f);
    }

    @Test
    public void theShellsEmptySearchOnTabSwitchKeepsTheScroll() {
        sixtyCommands();
        make();
        tab.content().scroll(400, 300, -6f);
        advance(400);
        float before = tab.headerCenter("c00")[1];
        tab.onSearch("");
        advance(50);
        assertEquals(before, tab.headerCenter("c00")[1], 0.5f);
    }

    @Test
    public void descriptionsNeverSayAltoclef() {
        facade.commands.list.clear();
        facade.commands.list.add(new CommandInfo(List.of("get"), "Get items, AltoClef style",
                List.of("AltoClef's way to get things.", "", "Usage:", "> get oak_log")));
        make();
        assertEquals("Get items, Soprano style", tab.showing().get(0).shortDesc());
        assertEquals("Soprano's way to get things.", tab.showing().get(0).longDesc().get(0));
    }

    @Test
    public void aMouseClickLeavesNoStuckSelection() {
        make();
        click(tab.headerCenter("goto"));
        assertNull(tab.list().selectedName());
    }

    @Test
    public void theClickedRowStaysPutWhileTheOpenRowAboveFoldsUp() {
        sixtyCommands();
        make();
        click(tab.headerCenter("c02"));
        advance(400);
        tab.content().scroll(400, 300, -4f);
        advance(400);
        float y0 = tab.headerCenter("c10")[1];
        assertTrue(y0 > tab.list().y() && y0 < tab.list().y() + tab.list().h());
        click(new float[] {400, y0});
        advance(20);
        assertEquals(y0, tab.headerCenter("c10")[1], 3f);
        advance(500);
        float y1 = tab.headerCenter("c10")[1];
        assertEquals("c10", tab.expandedCommand());
        // the whole open row fits, so it was nudged up until its last usage line is on screen
        float bottom = tab.usageCenter("c10", 1)[1] + 14;
        assertTrue(y1 >= tab.list().y());
        assertTrue(bottom <= tab.list().y() + tab.list().h() + 1);
    }

    @Test
    public void usageLinesCopyOnlyTheCommandNotItsExplanation() {
        sixtyCommands();
        make();
        click(tab.headerCenter("c00"));
        advance(400);
        click(tab.usageCenter("c00", 1));
        assertEquals("c00 <x> <y>", tab.field().text());
    }

    @Test
    public void recallingAGoodLineClearsTheRedBorder() {
        make();
        ctx.focus(tab.field());
        tab.field().setText("stop");
        tab.field().keyDown(Keys.ENTER, 0);
        facade.commands.failNext = true;
        tab.field().setText("gett");
        tab.field().keyDown(Keys.ENTER, 0);
        assertTrue(tab.field().invalid());
        tab.field().keyDown(Keys.UP, 0);
        tab.field().keyDown(Keys.UP, 0);
        assertEquals("stop", tab.field().text());
        assertFalse(tab.field().invalid());
    }

    @Test
    public void numLockDoesNotBreakTheArrowKeys() {
        make();
        ctx.focus(tab.field());
        tab.field().setText("stop");
        tab.field().keyDown(Keys.ENTER, 0);
        assertTrue(tab.field().keyDown(Keys.UP, 32));
        assertEquals("stop", tab.field().text());
    }

    @Test
    public void aHashTypedFirstIsSwallowedSinceOneIsAlreadyDrawn() {
        make();
        ctx.focus(tab.field());
        tab.field().charTyped('#');
        assertEquals("", tab.field().text());
        tab.field().charTyped('g');
        tab.field().charTyped('#');
        assertEquals("g#", tab.field().text());
    }

    @Test
    public void releasingOutsideTheListIsNotAClick() {
        make();
        float[] g = tab.headerCenter("goto");
        tab.content().mouseMove(g[0], g[1]);
        tab.content().mouseDown(g[0], g[1], 0, 0);
        tab.content().mouseUp(-50, g[1], 0, 0);
        assertNull(tab.expandedCommand());
    }

    @Test
    public void namelessCommandsAreSkipped() {
        facade.commands.list.add(new CommandInfo(List.of(), "ghost", List.of()));
        make();
        assertEquals(12, tab.showing().size());
    }

    @Test
    public void aCommandWithNothingToSayStillOpensToSomething() {
        facade.commands.list.clear();
        facade.commands.list.add(new CommandInfo(List.of("quiet"), "", List.of()));
        make();
        click(tab.headerCenter("quiet"));
        advance(400);
        assertTrue(canvas.texts().contains("No more details for this command."));
    }

    @Test
    public void keyboardOpensTheSelectedRow() {
        make();
        tab.list().setFocused(true);
        tab.list().keyDown(Keys.DOWN, 0);
        tab.list().keyDown(Keys.DOWN, 0);
        assertEquals("farm", tab.list().selectedName());
        tab.list().keyDown(Keys.ENTER, 0);
        assertEquals("farm", tab.expandedCommand());
        // a filter that drops the selection forgets it
        tab.onSearch("zzz");
        assertNull(tab.list().selectedName());
    }

    @Test
    public void drawingNeverLeavesAClipOpen() {
        make();
        click(tab.headerCenter("goto"));
        advance(100);
        assertEquals(0, canvas.clipDepth);
    }
}
