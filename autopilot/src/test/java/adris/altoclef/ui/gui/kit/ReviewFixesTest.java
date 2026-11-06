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
import org.junit.Test;

// the bugs a second pair of eyes found, each pinned so it stays fixed
public class ReviewFixesTest {

    private static final long MS = 1_000_000L;
    private static final int G = 71;

    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);
    private long clock = 1000;

    private void draw(Widget w) {
        clock += 16;
        w.draw(canvas, new Frame(clock * MS, 0.016f, -1, -1));
    }

    private static class Idle implements StatusAccess {
        @Override
        public Activity activity() {
            return new Activity(false, "Idle", "", null);
        }

        @Override
        public Result stop() {
            return Result.ok("Stopped", "#stop");
        }
    }

    private static class OneTab implements Tab {
        final Widget content;
        final boolean search;

        OneTab(Widget content, boolean search) {
            this.content = content;
            this.search = search;
        }

        @Override
        public String id() {
            return "t";
        }

        @Override
        public String title() {
            return "T";
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
            return search;
        }
    }

    private Shell shellOf(Widget content, int[] closeCount) {
        Shell s = new Shell(theme, List.of(new OneTab(content, true)), new Idle(), k -> "key" + k, 344, k -> { },
                Clipboard.inMemory(), () -> closeCount[0]++);
        s.setViewport(1920, 1080);
        draw(s);
        return s;
    }

    private static List<Integer> numbers(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    // a settings style row: a text field that records commits along with the item it was bound to when it fired
    private static class FieldRow extends RowWidgets.Row<Integer> {
        final TextField field;
        final List<String> commits;
        int item = -1;

        FieldRow(Theme theme, List<String> commits) {
            super(theme);
            this.commits = commits;
            field = new TextField(theme, "", s -> commits.add(item + ":" + s));
        }

        @Override
        public void bind(Integer it, int index) {
            item = it;
            field.setText("v" + it);
        }

        @Override
        protected void layout() {
            field.setBounds(x, y, 200, h);
        }

        @Override
        public List<Widget> children() {
            return List.of(field);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            field.draw(c, f);
        }
    }

    @Test
    public void aRecycledRowCannotKeepFocusAndCommitToTheWrongItem() {
        List<String> commits = new ArrayList<>();
        RowWidgets<Integer> rows = new RowWidgets<>(theme, 36, () -> new FieldRow(theme, commits));
        rows.setItems(numbers(100));
        int[] closed = {0};
        Shell shell = shellOf(rows, closed);
        draw(shell);
        shell.mouseMove(rows.x() + 20, rows.y() + 10);
        shell.mouseDown(rows.x() + 20, rows.y() + 10, 0, 0);
        shell.mouseUp(rows.x() + 20, rows.y() + 10, 0, 0);
        assertTrue(shell.focusedWidget() instanceof TextField);
        rows.scroller().snapTo(36 * 60);
        draw(shell);
        shell.keyDown(Keys.ENTER, 0);
        assertTrue("commit leaked into a recycled row: " + commits, commits.isEmpty());
        assertNull(shell.focusedWidget());
    }

    @Test
    public void aRecycledFieldCanBeFocusedAgainAndTakesText() {
        List<String> commits = new ArrayList<>();
        RowWidgets<Integer> rows = new RowWidgets<>(theme, 36, () -> new FieldRow(theme, commits));
        rows.setItems(numbers(100));
        Shell shell = shellOf(rows, new int[1]);
        float fx = rows.x() + 20;
        float fy = rows.y() + 10;
        shell.mouseDown(fx, fy, 0, 0);
        shell.mouseUp(fx, fy, 0, 0);
        assertTrue(shell.focusedWidget() instanceof TextField);
        rows.scroller().snapTo(36 * 60);
        draw(shell);
        rows.scroller().snapTo(0);
        draw(shell);
        shell.mouseDown(fx, fy, 0, 0);
        shell.mouseUp(fx, fy, 0, 0);
        TextField now = (TextField) shell.focusedWidget();
        assertTrue(now.isFocused());
        shell.charTyped('z');
        // the click landed after the "v", so the char goes there
        assertEquals("vz0", now.text());
        assertEquals("", shell.search().text());
    }

    @Test
    public void theCharThatComesWithARebindIsNotSearchText() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        List<String> searches = new ArrayList<>();
        Tab tab = new OneTab(blank, true) {
            @Override
            public void onSearch(String q) {
                searches.add(q);
            }
        };
        Shell shell = new Shell(theme, List.of(tab), new Idle(), k -> "k", 344, k -> { }, Clipboard.inMemory(),
                () -> { });
        shell.setViewport(1920, 1080);
        searches.clear();
        shell.mouseDown(shell.keyCapture().x() + 5, shell.keyCapture().y() + 5, 0, 0);
        shell.keyDown(G, 0);
        shell.charTyped('g');
        assertEquals("", shell.search().text());
        assertTrue(searches.isEmpty());
        assertSame(shell.keyCapture(), shell.focusedWidget());
    }

    @Test
    public void holdingEscapeToCancelACaptureDoesNotCloseThePanel() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.mouseDown(shell.keyCapture().x() + 5, shell.keyCapture().y() + 5, 0, 0);
        shell.keyDown(Keys.ESCAPE, 0);
        for (int i = 0; i < 5; i++) {
            shell.keyDown(Keys.ESCAPE, 0);
        }
        assertFalse(shell.closing());
        shell.keyUp(Keys.ESCAPE);
        shell.keyDown(Keys.ESCAPE, 0);
        shell.keyDown(Keys.ESCAPE, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void setOpenKeyAloneDoesNotSwallowTheNextPress() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.setOpenKey(G);
        shell.keyDown(G, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void aRefreshedRowListKeepsTheRowYouAreTypingIn() {
        List<String> commits = new ArrayList<>();
        List<Integer> binds = new ArrayList<>();
        RowWidgets<Integer> rows = new RowWidgets<>(theme, 36, () -> new FieldRow(theme, commits) {
            @Override
            public void bind(Integer it, int index) {
                binds.add(index);
                super.bind(it, index);
            }
        });
        rows.setBounds(0, 0, 400, 300);
        rows.setItems(numbers(100));
        draw(rows);
        FieldRow first = (FieldRow) rows.children().get(0);
        first.field.setFocused(true);
        first.field.charTyped('x');
        binds.clear();
        rows.setItems(numbers(100));
        assertFalse("focused row was rebound", binds.contains(0));
        assertTrue(binds.contains(1));
        assertEquals("v0x", first.field.text());
    }

    @Test
    public void aRowThatChangedItemIsReboundEvenIfFocusedAndDefocused() {
        List<String> commits = new ArrayList<>();
        RowWidgets<Integer> rows = new RowWidgets<>(theme, 36, () -> new FieldRow(theme, commits));
        rows.setBounds(0, 0, 400, 300);
        rows.setItems(numbers(100));
        draw(rows);
        FieldRow first = (FieldRow) rows.children().get(0);
        first.field.setFocused(true);
        List<Integer> other = numbers(100);
        other.set(0, 500);
        rows.setItems(other);
        assertEquals("v500", first.field.text());
        assertFalse(first.field.isFocused());
    }

    @Test
    public void toggleOnlyFlipsWhenThePressStartedOnIt() {
        List<Boolean> seen = new ArrayList<>();
        Toggle t = new Toggle(theme, false, seen::add);
        t.setBounds(0, 0, 34, 18);
        t.mouseUp(5, 5, 0, 0);
        assertTrue(seen.isEmpty());
        t.mouseDown(5, 5, 0, 0);
        t.mouseUp(5, 5, 0, 0);
        assertEquals(List.of(true), seen);
        t.mouseDown(5, 5, 0, 0);
        t.mouseUp(100, 5, 0, 0);
        assertEquals(1, seen.size());
    }

    @Test
    public void aClickBelongsToTheItemYouPressedEvenIfTheListReshuffles() {
        VirtualList<String> list = new VirtualList<>(theme, 36, (c, f, item, i, x, y, w, h, hot) -> { });
        list.setBounds(0, 0, 400, 300);
        list.setItems(List.of("a", "b", "c"));
        List<String> clicks = new ArrayList<>();
        list.onClick((item, i) -> clicks.add(item + "@" + i));
        draw(list);
        list.mouseDown(10, 10, 0, 0);
        list.setItems(List.of("z", "a", "b", "c"));
        list.mouseUp(10, 10, 0, 0);
        assertTrue("fired for the wrong item: " + clicks, clicks.isEmpty());
        list.mouseDown(10, 10, 0, 0);
        list.setItems(List.of("z", "y", "a"));
        list.mouseUp(10, 80, 0, 0);
        assertTrue(clicks.isEmpty());
        list.setItems(List.of("a", "b"));
        list.mouseDown(10, 10, 0, 0);
        list.setItems(List.of("q", "a"));
        list.mouseUp(10, 50, 0, 0);
        assertEquals(List.of("a@1"), clicks);
    }

    @Test
    public void typingStillReachesTheSearchAfterClickingAList() {
        VirtualList<Integer> list = new VirtualList<>(theme, 36, (c, f, item, i, x, y, w, h, hot) -> { });
        list.setItems(numbers(50));
        int[] closed = {0};
        Shell shell = shellOf(list, closed);
        draw(shell);
        float lx = list.x() + 10;
        float ly = list.y() + 10;
        shell.mouseDown(lx, ly, 0, 0);
        shell.mouseUp(lx, ly, 0, 0);
        assertSame(list, shell.focusedWidget());
        assertTrue(shell.charTyped('o'));
        assertEquals("o", shell.search().text());
        assertSame(shell.search(), shell.focusedWidget());
    }

    @Test
    public void tabCanBeBoundWhileCapturing() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        List<Integer> rebinds = new ArrayList<>();
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, rebinds::add,
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.mouseDown(shell.keyCapture().x() + 5, shell.keyCapture().y() + 5, 0, 0);
        assertTrue(shell.keyCapture().capturing());
        assertTrue(shell.keyDown(Keys.TAB, 0));
        assertEquals(List.of(Keys.TAB), rebinds);
        assertSame(shell.keyCapture(), shell.focusedWidget());
    }

    @Test
    public void holdingTheFreshlyBoundKeyDoesNotCloseThePanel() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.mouseDown(shell.keyCapture().x() + 5, shell.keyCapture().y() + 5, 0, 0);
        shell.keyDown(G, 0);
        shell.blur();
        assertTrue(shell.keyDown(G, 0));
        assertTrue(shell.keyDown(G, 0));
        assertFalse(shell.closing());
        shell.keyUp(G);
        shell.keyDown(G, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void anyOtherKeyEndsTheRepeatSuppression() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.mouseDown(shell.keyCapture().x() + 5, shell.keyCapture().y() + 5, 0, 0);
        shell.keyDown(G, 0);
        shell.blur();
        shell.keyDown(72, 0);
        shell.keyDown(G, 0);
        assertTrue(shell.closing());
    }

    @Test
    public void aDisabledOpenKeyDoesNotCloseEither() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, true)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.setOpenKeyEnabled(false);
        shell.keyDown(344, 0);
        assertFalse(shell.closing());
    }

    @Test
    public void altGrDoesNotRunTheCtrlShortcuts() {
        TextField f = new TextField(theme, "", s -> { });
        f.setBounds(0, 0, 200, 30);
        f.setText("abc");
        f.setFocused(true);
        assertFalse(f.keyDown(Keys.A, Keys.MOD_CTRL | Keys.MOD_ALT));
        assertFalse(f.edit().hasSelection());
        assertTrue(f.keyDown(Keys.A, Keys.MOD_CTRL));
        assertTrue(f.edit().hasSelection());
    }

    @Test
    public void pastedTabsBecomeSpaces() {
        Clipboard clip = Clipboard.inMemory();
        clip.set("100\t64\t-30");
        TextEdit e = new TextEdit();
        e.paste(clip);
        assertEquals("100 64 -30", e.text());
    }

    @Test
    public void pasteReplacesASelectionAndRespectsMaxLength() {
        Clipboard clip = Clipboard.inMemory();
        clip.set("123456");
        TextEdit e = new TextEdit();
        e.setMaxLength(5);
        e.setText("ab");
        e.setCaret(0, false);
        e.setCaret(2, true);
        assertTrue(e.paste(clip));
        assertEquals("12345", e.text());
        assertFalse(e.paste(clip));
    }

    @Test
    public void toastFloodKeepsTheNewestMessage() {
        Toast t = new Toast(theme);
        t.setBounds(0, 0, 800, 600);
        for (int i = 0; i < 20; i++) {
            t.show("n" + i, null);
        }
        assertEquals("n0", t.currentLine());
        String last = null;
        for (int i = 0; i < 40 && t.pending() > 0; i++) {
            clock += 4000;
            draw(t);
            if (t.currentLine() != null) {
                last = t.currentLine();
            }
        }
        assertEquals("n19", last);
    }

    @Test
    public void aFailedStopIsRed() {
        StatusAccess boom = new Idle() {
            @Override
            public Result stop() {
                throw new IllegalStateException("no");
            }
        };
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, false)), boom, k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.mouseDown(shell.stopButton().x() + 5, shell.stopButton().y() + 5, 0, 0);
        shell.mouseUp(shell.stopButton().x() + 5, shell.stopButton().y() + 5, 0, 0);
        assertEquals(1, shell.toast().pending());
        assertFalse(shell.toast().currentOk());
    }

    @Test
    public void aStatusFacadeThatStartsThrowingKeepsTheLastLine() {
        boolean[] boom = {false};
        StatusAccess flaky = new StatusAccess() {
            @Override
            public Activity activity() {
                if (boom[0]) {
                    throw new IllegalStateException("down");
                }
                return new Activity(true, "Mining iron_ore", "", null);
            }

            @Override
            public Result stop() {
                return Result.ok("x");
            }
        };
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(blank, false)), flaky, k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        shell.tick();
        boom[0] = true;
        shell.tick();
        canvas.calls.clear();
        draw(shell);
        assertTrue(canvas.texts().contains("Mining iron_ore"));
    }

    @Test
    public void clickingAButtonDropsFocusFromAField() {
        TextField field = new TextField(theme, "", s -> { });
        int[] clicks = {0};
        Button button = new Button(theme, "Go", () -> clicks[0]++);
        Widget box = new Widget(theme) {
            @Override
            protected void layout() {
                button.setBounds(x + 10, y + 10, 80, 30);
                field.setBounds(x + 10, y + 60, 200, 30);
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
        };
        Shell shell = shellOf(box, new int[1]);
        shell.focus(field);
        shell.mouseDown(button.x() + 5, button.y() + 5, 0, 0);
        shell.mouseUp(button.x() + 5, button.y() + 5, 0, 0);
        assertEquals(1, clicks[0]);
        assertNull(shell.focusedWidget());
        assertFalse(field.isFocused());
    }

    @Test
    public void reopeningAfterDisposeShowsTheTabAgain() {
        List<String> events = new ArrayList<>();
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Tab tab = new OneTab(blank, false) {
            @Override
            public void onShown() {
                events.add("shown");
            }

            @Override
            public void onHidden() {
                events.add("hidden");
            }
        };
        Shell shell = new Shell(theme, List.of(tab), new Idle(), k -> "k", 344, k -> { }, Clipboard.inMemory(),
                () -> { });
        shell.dispose();
        shell.open(clock * MS);
        assertEquals(List.of("shown", "hidden", "shown"), events);
    }

    @Test
    public void setViewportWithTheSameSizeDoesNotRelayout() {
        Widget blank = new Widget(theme) {
            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        int[] layouts = {0};
        Widget counting = new Widget(theme) {
            @Override
            protected void layout() {
                layouts[0]++;
            }

            @Override
            public void draw(Canvas c, Frame f) {
            }
        };
        Shell shell = new Shell(theme, List.of(new OneTab(counting, false)), new Idle(), k -> "k", 344, k -> { },
                Clipboard.inMemory(), () -> { });
        shell.setViewport(1920, 1080);
        int after = layouts[0];
        shell.setViewport(1920, 1080);
        assertEquals(after, layouts[0]);
        assertNotNullBlank(blank);
    }

    private static void assertNotNullBlank(Widget w) {
        assertTrue(w != null);
    }

    @Test
    public void proportionalGlyphsPlaceTheCaretByRealWidths() {
        // i is narrow, W is wide: dividing by an average char width would land on the wrong boundary
        StubCanvas varied = new StubCanvas() {
            @Override
            public float measure(String s, TextStyle style) {
                float w = 0f;
                for (char ch : s.toCharArray()) {
                    w += ch == 'i' ? 3f : (ch == 'W' ? 12f : 6f);
                }
                return w;
            }
        };
        Theme t = new Theme(1f, varied);
        TextField f = new TextField(t, "", s -> { });
        f.setBounds(0, 0, 300, 30);
        f.setText("iiiWWW");
        f.setFocused(true);
        f.draw(varied, new Frame(0, 0.016f, -1, -1));
        // boundaries at 10, 13, 16, 19, 31, 43, 55 (an average width of 7.5 would put x=28 at index 2)
        f.mouseDown(10 + 18, 15, 0, 0);
        assertEquals(4, f.edit().caret());
        f.mouseUp(0, 0, 0, 0);
        f.draw(varied, new Frame(5_000 * MS, 0.016f, -1, -1));
        f.mouseDown(10 + 38, 15, 0, 0);
        assertEquals(5, f.edit().caret());
    }
}
