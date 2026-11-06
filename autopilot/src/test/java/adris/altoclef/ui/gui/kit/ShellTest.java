package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ShellTest {

    private static final long MS = 1_000_000L;
    private static final int RSHIFT = 344;

    static final class FakeStatus implements StatusAccess {
        Activity now = new Activity(false, "Idle", "", null);
        int stops;
        boolean throwOnStop;

        @Override
        public Activity activity() {
            return now;
        }

        @Override
        public Result stop() {
            stops++;
            if (throwOnStop) {
                throw new IllegalStateException("no stopping");
            }
            return Result.ok("Stopped", "#stop");
        }
    }

    // a content widget with a button and a text field, laid out by hand like a real tab would
    static final class Box extends Widget {
        final Button button;
        final TextField field;
        int clicks;

        Box(Theme theme) {
            super(theme);
            button = new Button(theme, "Go", () -> clicks++);
            field = new TextField(theme, "Name", s -> { });
        }

        @Override
        protected void layout() {
            button.setBounds(x + 16, y + 16, 100, 30);
            field.setBounds(x + 16, y + 60, 200, 30);
        }

        @Override
        public List<Widget> children() {
            return List.of(button, field);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            button.draw(c, f);
            field.draw(c, f);
        }
    }

    static final class FakeTab implements Tab {
        final String id;
        final boolean search;
        final Box box;
        final List<String> events = new ArrayList<>();
        boolean throwOnSearch;

        FakeTab(Theme theme, String id, boolean search) {
            this.id = id;
            this.search = search;
            this.box = new Box(theme);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return id.toUpperCase();
        }

        @Override
        public Icon icon() {
            return Icon.GET;
        }

        @Override
        public Widget content() {
            return box;
        }

        @Override
        public boolean hasSearch() {
            return search;
        }

        @Override
        public void onSearch(String q) {
            if (throwOnSearch) {
                throw new IllegalStateException("search blew up");
            }
            events.add("search:" + q);
        }

        @Override
        public void onShown() {
            events.add("shown");
        }

        @Override
        public void onHidden() {
            events.add("hidden");
        }
    }

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private final FakeStatus status = new FakeStatus();
    private final List<Integer> rebinds = new ArrayList<>();
    private final int[] closed = {0};
    private FakeTab a;
    private FakeTab b;
    private Shell shell;
    private long clock = 1000;

    @Before
    public void setUp() {
        a = new FakeTab(theme, "get", true);
        b = new FakeTab(theme, "help", false);
        shell = new Shell(theme, List.of(a, b), status, k -> k == RSHIFT ? "RShift" : "Key " + k, RSHIFT,
                rebinds::add, Clipboard.inMemory(), () -> closed[0]++);
        shell.setViewport(1920, 1080);
        draw();
    }

    private void draw() {
        clock += 16;
        canvas.calls.clear();
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void drawAfter(long ms) {
        clock += ms;
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private void click(float x, float y) {
        shell.mouseMove(x, y);
        shell.mouseDown(x, y, 0, 0);
        shell.mouseUp(x, y, 0, 0);
    }

    private void clickTab(int i) {
        click(shell.x() + 60, shell.tabRowY(i) + shell.tabRowH() / 2f);
    }

    @Test
    public void panelIsCentredAndSizedFromTheWindow() {
        assertEquals(1040, shell.w());
        assertEquals(680, shell.h());
        assertEquals((1920 - shell.w()) / 2, shell.x());
        assertEquals((1080 - shell.h()) / 2, shell.y());
    }

    @Test
    public void panelClampsToTheMinimumAndMaximum() {
        shell.setViewport(1000, 600);
        assertEquals(640, shell.w());
        assertEquals(440, shell.h());
        shell.setViewport(4000, 3000);
        assertEquals(1040, shell.w());
        assertEquals(680, shell.h());
        shell.setViewport(700, 400);
        assertTrue(shell.w() <= 700 && shell.h() <= 400);
    }

    @Test
    public void draggingTheHeaderMovesThePanel() {
        int beforeX = shell.x();
        int beforeY = shell.y();
        // the header strip, clear of the search box and the tab rows
        shell.mouseDown(shell.x() + 200, shell.y() + 20, 0, 0);
        shell.mouseDrag(shell.x() + 240, shell.y() + 45, 0, 40, 25);
        assertEquals(beforeX + 40, shell.x());
        assertEquals(beforeY + 25, shell.y());
        shell.mouseUp(shell.x() + 240, shell.y() + 45, 0, 0);
        // a second drag still works, it is not a one shot
        shell.mouseDown(shell.x() + 200, shell.y() + 20, 0, 0);
        shell.mouseDrag(shell.x() + 190, shell.y() + 10, 0, -10, -10);
        assertEquals(beforeX + 30, shell.x());
        assertEquals(beforeY + 15, shell.y());
    }

    @Test
    public void tabsPlaceTheirContentInTheContentArea() {
        assertEquals(shell.contentX(), a.box.x());
        assertEquals(shell.contentY(), a.box.y());
        assertEquals(shell.contentW(), a.box.w());
        assertEquals(shell.contentH(), a.box.h());
        assertTrue(shell.contentH() > 0);
    }

    @Test
    public void clickingASidebarRowSwitchesTabs() {
        assertEquals(0, shell.activeIndex());
        clickTab(1);
        assertEquals(1, shell.activeIndex());
        assertEquals(List.of("search:", "shown", "hidden"), a.events);
        assertEquals(List.of("shown"), b.events);
        assertSame(b.box, shell.children().get(0));
        clickTab(0);
        assertEquals(0, shell.activeIndex());
        assertEquals(List.of("search:", "shown", "hidden", "search:", "shown"), a.events);
    }

    @Test
    public void searchIsOnlyThereForTabsThatWantIt() {
        assertTrue(shell.search().visible());
        clickTab(1);
        assertFalse(shell.search().visible());
    }

    @Test
    public void switchingTabsResetsTheSearchText() {
        shell.charTyped('x');
        assertEquals("x", shell.search().text());
        clickTab(1);
        clickTab(0);
        assertEquals("", shell.search().text());
    }

    @Test
    public void switchingTabsDropsFocus() {
        shell.focus(a.box.field);
        clickTab(1);
        assertNull(shell.focusedWidget());
        assertFalse(a.box.field.isFocused());
    }

    @Test
    public void escapeClosesOnlyWhenNothingIsFocused() {
        shell.focus(a.box.field);
        assertTrue(shell.keyDown(Keys.ESCAPE, 0));
        assertFalse(shell.closing());
        assertNull(shell.focusedWidget());
        assertTrue(shell.keyDown(Keys.ESCAPE, 0));
        assertTrue(shell.closing());
    }

    @Test
    public void escapeClearsSearchTextBeforeClosing() {
        shell.charTyped('a');
        shell.charTyped('b');
        assertSame(shell.search(), shell.focusedWidget());
        shell.keyDown(Keys.ESCAPE, 0);
        assertEquals("", shell.search().text());
        assertFalse(shell.closing());
        shell.keyDown(Keys.ESCAPE, 0);
        assertNull(shell.focusedWidget());
        assertFalse(shell.closing());
        shell.keyDown(Keys.ESCAPE, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void closingAnimatesAndThenCallsBack() {
        shell.requestClose();
        assertTrue(shell.closing());
        drawAfter(50);
        assertEquals(0, closed[0]);
        drawAfter(400);
        assertEquals(1, closed[0]);
        assertTrue(shell.closed(clock * MS));
        drawAfter(100);
        assertEquals(1, closed[0]);
    }

    @Test
    public void openFadesInFromNothing() {
        shell.open(clock * MS);
        canvas.calls.clear();
        shell.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
        assertEquals(0, canvas.calls.size());
        drawAfter(300);
        assertTrue(canvas.count("text") > 0);
    }

    @Test
    public void tabCyclesFocusInOrder() {
        List<Widget> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            shell.keyDown(Keys.TAB, 0);
            seen.add(shell.focusedWidget());
        }
        assertSame(shell.search(), seen.get(0));
        assertSame(a.box.button, seen.get(1));
        assertSame(a.box.field, seen.get(2));
        assertSame(shell.appearance(), seen.get(3));
        assertSame(shell.stopButton(), seen.get(4));
        assertSame(shell.search(), seen.get(5));
    }

    @Test
    public void shiftTabGoesBackwards() {
        shell.keyDown(Keys.TAB, Keys.MOD_SHIFT);
        assertSame(shell.stopButton(), shell.focusedWidget());
        shell.keyDown(Keys.TAB, Keys.MOD_SHIFT);
        assertSame(shell.appearance(), shell.focusedWidget());
        shell.keyDown(Keys.TAB, Keys.MOD_SHIFT);
        assertSame(a.box.field, shell.focusedWidget());
    }

    @Test
    public void tabSkipsTheSearchOnTabsWithoutOne() {
        clickTab(1);
        shell.keyDown(Keys.TAB, 0);
        assertSame(b.box.button, shell.focusedWidget());
    }

    @Test
    public void enterOnAFocusedButtonClicksIt() {
        shell.keyDown(Keys.TAB, 0);
        shell.keyDown(Keys.TAB, 0);
        assertTrue(shell.keyDown(Keys.ENTER, 0));
        assertEquals(1, a.box.clicks);
    }

    @Test
    public void typingWithNothingFocusedLandsInTheSearchField() {
        assertTrue(shell.charTyped('c'));
        assertTrue(shell.charTyped('o'));
        assertSame(shell.search(), shell.focusedWidget());
        assertEquals("co", shell.search().text());
        assertEquals("search:co", a.events.get(a.events.size() - 1));
    }

    @Test
    public void typingGoesToTheFocusedFieldNotTheSearch() {
        shell.focus(a.box.field);
        shell.charTyped('z');
        assertEquals("z", a.box.field.text());
        assertEquals("", shell.search().text());
    }

    @Test
    public void typingOnATabWithoutSearchIsIgnored() {
        clickTab(1);
        assertFalse(shell.charTyped('c'));
        assertNull(shell.focusedWidget());
    }

    @Test
    public void spaceDoesNotStartASearch() {
        assertFalse(shell.charTyped(' '));
        assertNull(shell.focusedWidget());
    }

    @Test
    public void clickingTheFieldFocusesItAndClickingEmptySpaceBlurs() {
        float fx = a.box.field.x() + 20;
        float fy = a.box.field.y() + 10;
        click(fx, fy);
        assertSame(a.box.field, shell.focusedWidget());
        click(shell.contentX() + 600, shell.contentY() + 300);
        assertNull(shell.focusedWidget());
    }

    @Test
    public void clickingAButtonRunsItWithoutTakingFocus() {
        click(a.box.button.x() + 10, a.box.button.y() + 10);
        assertEquals(1, a.box.clicks);
        assertNull(shell.focusedWidget());
    }

    private static final class Pop extends Widget {
        int downs;

        Pop(Theme t) {
            super(t);
        }

        @Override
        public void draw(Canvas c, Frame f) {
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            downs++;
            return true;
        }
    }

    @Test
    public void aClickOutsideAnOpenOverlayClosesIt() {
        Pop pop = new Pop(theme);
        pop.setBounds(shell.contentX() + 20, shell.contentY() + 20, 100, 100);
        shell.openOverlay(pop);
        assertSame(pop, shell.overlay());
        shell.mouseDown(shell.contentX() + 500, shell.contentY() + 300, 0, 0);
        assertNull(shell.overlay());
        assertFalse(pop.visible());
        assertEquals(0, pop.downs);
    }

    @Test
    public void aClickInsideAnOverlayGoesToIt() {
        Pop pop = new Pop(theme);
        pop.setBounds(shell.contentX() + 20, shell.contentY() + 20, 100, 100);
        shell.openOverlay(pop);
        shell.mouseDown(shell.contentX() + 30, shell.contentY() + 30, 0, 0);
        assertEquals(1, pop.downs);
        assertSame(pop, shell.overlay());
    }

    @Test
    public void theClickThatClosesAnOverlayDoesNotAlsoPressWhatIsUnderIt() {
        Pop pop = new Pop(theme);
        pop.setBounds(shell.contentX() + 300, shell.contentY() + 300, 50, 50);
        shell.openOverlay(pop);
        click(a.box.button.x() + 10, a.box.button.y() + 10);
        assertEquals(0, a.box.clicks);
        assertNull(shell.overlay());
    }

    @Test
    public void escapeClosesTheOverlayFirst() {
        Pop pop = new Pop(theme);
        shell.openOverlay(pop);
        shell.keyDown(Keys.ESCAPE, 0);
        assertNull(shell.overlay());
        assertFalse(shell.closing());
    }

    @Test
    public void anOverlaySwallowsKeys() {
        Pop pop = new Pop(theme);
        shell.openOverlay(pop);
        shell.keyDown(Keys.TAB, 0);
        assertNull(shell.focusedWidget());
    }

    @Test
    public void stopCallsTheFacadeAndQueuesAToast() {
        click(shell.stopButton().x() + 10, shell.stopButton().y() + 10);
        assertEquals(1, status.stops);
        assertEquals(1, shell.toast().pending());
        assertEquals("Stopped", shell.toast().currentLine());
        assertEquals("#stop", shell.toast().currentCommand());
    }

    @Test
    public void aStopThatThrowsStillToastsAnError() {
        status.throwOnStop = true;
        click(shell.stopButton().x() + 10, shell.stopButton().y() + 10);
        assertEquals(1, shell.toast().pending());
    }

    @Test
    public void toastAppearsAndEventuallyGoesAway() {
        shell.toast("Following Steve", "follow Steve");
        draw();
        assertTrue(canvas.texts().contains("Following Steve"));
        assertTrue(canvas.texts().contains("#follow Steve"));
        drawAfter(200);
        drawAfter(3200);
        drawAfter(300);
        assertEquals(0, shell.toast().pending());
    }

    @Test
    public void aFailedToastIsRed() {
        shell.toast(Result.fail("Nothing nearby"));
        draw();
        drawAfter(300);
        canvas.calls.clear();
        drawAfter(16);
        StubCanvas.Call line = canvas.textCall("Nothing nearby");
        assertEquals(Theme.DANGER, line.argb());
    }

    @Test
    public void statusLineShowsActivityAndDetail() {
        status.now = new StatusAccess.Activity(true, "Mining iron_ore", "goal 120 64 -30", "gather");
        shell.tick();
        draw();
        assertTrue(canvas.texts().contains("Mining iron_ore"));
        assertTrue(canvas.texts().contains("goal 120 64 -30"));
        assertTrue(canvas.texts().contains("gather"));
        assertTrue(canvas.calls.stream().anyMatch(c -> c.kind().equals("icon") && c.text().equals("DOT")));
    }

    @Test
    public void drawListsTheChromeText() {
        draw();
        List<String> t = canvas.texts();
        // the sidebar header is the logo now, not the word
        assertFalse(t.contains("soprano"));
        assertTrue(canvas.calls.stream().anyMatch(c -> c.kind().equals("image") && c.w() > 0 && c.w() == c.h()));
        assertTrue(t.contains("GET"));
        assertTrue(t.contains("HELP"));
        assertTrue(t.contains("Open key"));
        assertTrue(t.contains("RShift"));
        assertTrue(t.contains("Idle"));
        assertEquals(0, canvas.clipDepth);
    }

    @Test
    public void noStringTheShellDrawsHasAnEmDash() {
        status.now = new StatusAccess.Activity(true, "Mining iron_ore", "goal", "gather");
        shell.tick();
        draw();
        for (String s : canvas.texts()) {
            assertFalse(s, s.contains("—"));
        }
    }

    @Test
    public void theOpenKeyClosesWhenNothingIsBeingTyped() {
        shell.keyDown(RSHIFT, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void theOpenKeyDoesNotCloseWhileTypingInAField() {
        shell.focus(a.box.field);
        shell.keyDown(RSHIFT, 0);
        assertFalse(shell.closing());
    }

    @Test
    public void capturingANewKeySwallowsTheOldOneAndRebinds() {
        click(shell.keyCapture().x() + 10, shell.keyCapture().y() + 10);
        assertTrue(shell.keyCapture().capturing());
        assertSame(shell.keyCapture(), shell.focusedWidget());
        assertTrue(shell.keyDown(RSHIFT, 0));
        assertFalse(shell.closing());
        assertEquals(List.of(RSHIFT), rebinds);
        assertFalse(shell.keyCapture().capturing());
        shell.blur();
        shell.keyDown(71, 0);
        assertFalse(shell.closing());
        shell.keyDown(RSHIFT, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void rebindingToAnotherKeyTakesEffectAtOnce() {
        click(shell.keyCapture().x() + 10, shell.keyCapture().y() + 10);
        shell.keyDown(71, 0);
        assertEquals(List.of(71), rebinds);
        assertEquals(71, shell.openKey());
        shell.blur();
        shell.keyDown(RSHIFT, 0);
        assertFalse(shell.closing());
        shell.keyDown(71, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void escapeWhileCapturingCancelsInsteadOfClosing() {
        click(shell.keyCapture().x() + 10, shell.keyCapture().y() + 10);
        shell.keyDown(Keys.ESCAPE, 0);
        assertFalse(shell.closing());
        assertFalse(shell.keyCapture().capturing());
        assertEquals(RSHIFT, shell.openKey());
        assertTrue(rebinds.isEmpty());
    }

    @Test
    public void backspaceWhileCapturingUnbinds() {
        click(shell.keyCapture().x() + 10, shell.keyCapture().y() + 10);
        shell.keyDown(Keys.BACKSPACE, 0);
        assertEquals(List.of(-1), rebinds);
        draw();
        assertTrue(canvas.texts().contains("Unbound"));
        shell.blur();
        shell.keyDown(RSHIFT, 0);
        assertFalse(shell.closing());
    }

    @Test
    public void clickingElsewhereCancelsCapture() {
        click(shell.keyCapture().x() + 10, shell.keyCapture().y() + 10);
        click(shell.contentX() + 600, shell.contentY() + 300);
        assertFalse(shell.keyCapture().capturing());
    }

    @Test
    public void aTabThatThrowsDoesNotKillTheShell() {
        a.throwOnSearch = true;
        assertTrue(shell.charTyped('x'));
        assertEquals("x", shell.search().text());
        clickTab(1);
        assertEquals(1, shell.activeIndex());
        draw();
        assertTrue(canvas.texts().contains("Idle"));
    }

    @Test
    public void aStatusFacadeThatThrowsKeepsTheLastLine() {
        StatusAccess boom = new StatusAccess() {
            @Override
            public Activity activity() {
                throw new IllegalStateException("status down");
            }

            @Override
            public Result stop() {
                return Result.ok("x");
            }
        };
        Shell s = new Shell(theme, List.of(a), boom, k -> "k", RSHIFT, k -> { }, Clipboard.inMemory(), () -> { });
        s.setViewport(1920, 1080);
        s.tick();
        s.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
        assertTrue(canvas.texts().contains("Idle"));
    }

    @Test
    public void hoveringARowAnimatesInAFill() {
        shell.mouseMove(shell.x() + 60, shell.tabRowY(1) + 10);
        drawAfter(16);
        drawAfter(200);
        canvas.calls.clear();
        drawAfter(16);
        // a hovered row that is not the active one gets a half strength pill
        boolean hoverFill = canvas.calls.stream().anyMatch(c -> c.kind().equals("fillRoundRect")
                && (c.argb() & 0x00FFFFFF) == (Theme.HOVER & 0x00FFFFFF) && (c.argb() >>> 24) < 255
                && Math.abs(c.y() - shell.tabRowY(1)) < 1f);
        assertTrue(hoverFill);
    }

    @Test
    public void disposeTellsTheVisibleTab() {
        shell.dispose();
        assertEquals("hidden", a.events.get(a.events.size() - 1));
    }
}
