package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the small widgets: buttons, toggles, segmented, dropdown, key capture, toast, layout
public class WidgetsTest {

    private static final long MS = 1_000_000L;
    private final StubCanvas canvas = new StubCanvas();
    private final Theme theme = new Theme(1f, canvas);

    private void draw(Widget w, long ms) {
        w.draw(canvas, new Frame(ms * MS, 0.016f, -1, -1));
    }

    private static final class Ctx implements ShellContext {
        final Theme theme;
        Widget overlay;
        Widget focus;

        Ctx(Theme t) {
            theme = t;
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
        }

        @Override
        public void toast(String line, String command) {
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
        public void openOverlay(Widget o) {
            overlay = o;
            o.setVisible(true);
        }

        @Override
        public void closeOverlay() {
            if (overlay != null) {
                overlay.setVisible(false);
            }
            overlay = null;
        }

        @Override
        public void requestClose() {
        }
    }

    private StubCanvas.Call fill() {
        return canvas.calls.stream().filter(c -> c.kind().equals("fillRoundRect")).findFirst().orElseThrow();
    }

    private StubCanvas.Call pill() {
        return canvas.calls.stream().filter(c -> c.kind().equals("fillRoundRect") && c.argb() == Theme.SELECTED)
                .findFirst().orElseThrow();
    }

    @Test
    public void buttonClicksOnReleaseInsideOnly() {
        int[] n = {0};
        Button b = new Button(theme, "Get 67 items", () -> n[0]++);
        b.setBounds(0, 0, 120, 30);
        assertTrue(b.mouseDown(10, 10, 0, 0));
        b.mouseUp(10, 10, 0, 0);
        assertEquals(1, n[0]);
        b.mouseDown(10, 10, 0, 0);
        b.mouseUp(500, 500, 0, 0);
        assertEquals(1, n[0]);
        assertFalse(b.mouseDown(500, 10, 0, 0));
    }

    @Test
    public void disabledButtonDoesNothingAndCannotFocus() {
        int[] n = {0};
        Button b = new Button(theme, "Go", () -> n[0]++);
        b.setBounds(0, 0, 120, 30);
        b.setEnabled(false);
        assertFalse(b.mouseDown(10, 10, 0, 0));
        assertFalse(b.focusable());
        b.setFocused(true);
        assertFalse(b.keyDown(Keys.ENTER, 0));
        assertEquals(0, n[0]);
    }

    @Test
    public void focusedButtonTriggersOnEnterAndSpace() {
        int[] n = {0};
        Button b = new Button(theme, "Go", () -> n[0]++);
        b.setBounds(0, 0, 120, 30);
        assertFalse(b.keyDown(Keys.ENTER, 0));
        b.setFocused(true);
        assertTrue(b.keyDown(Keys.ENTER, 0));
        assertTrue(b.keyDown(Keys.SPACE, 0));
        assertEquals(2, n[0]);
    }

    @Test
    public void buttonPreferredWidthIsTextPlusPadding() {
        Button b = new Button(theme, "Go", () -> { });
        assertEquals((int) Math.ceil(0.6f * theme.label.sizePx() * 2) + 24, b.preferredWidth());
        Button withIcon = new Button(theme, "Go", Button.Kind.GHOST, Icon.PLUS, () -> { });
        assertEquals(b.preferredWidth() + 16 + 6, withIcon.preferredWidth());
        assertEquals(30, b.preferredHeight());
    }

    @Test
    public void buttonLooksFollowTheKind() {
        Button p = new Button(theme, "Go", Button.Kind.PRIMARY, null, () -> { });
        p.setBounds(0, 0, 100, 30);
        draw(p, 0);
        assertEquals(Theme.ACCENT, fill().argb());
        assertEquals(Theme.ON_ACCENT, canvas.textCall("Go").argb());
        canvas.calls.clear();
        Button d = new Button(theme, "Stop", Button.Kind.DANGER, null, () -> { });
        d.setBounds(0, 0, 100, 30);
        draw(d, 0);
        // secondary and danger sit a step up from the surface they are on
        assertEquals(Theme.RAISED, fill().argb());
        assertEquals(Theme.DANGER, canvas.textCall("Stop").argb());
    }

    @Test
    public void hoverFadesTheFillIn() {
        Button b = new Button(theme, "Go", () -> { });
        b.setBounds(0, 0, 100, 30);
        draw(b, 0);
        b.mouseMove(10, 10);
        draw(b, 1);
        canvas.calls.clear();
        draw(b, 500);
        assertEquals(Theme.HOVER, fill().argb());
    }

    @Test
    public void pressedFlashOutlivesAKeyboardActivation() {
        Button b = new Button(theme, "Go", () -> { });
        b.setBounds(0, 0, 100, 30);
        b.setFocused(true);
        draw(b, 0);
        b.keyDown(Keys.ENTER, 0);
        canvas.calls.clear();
        draw(b, 1);
        int first = fill().argb();
        assertTrue(first != Theme.RAISED);
        canvas.calls.clear();
        draw(b, 500);
        assertEquals(Theme.RAISED, fill().argb());
    }

    @Test
    public void ghostButtonHasNoFillUntilHover() {
        IconButton b = new IconButton(theme, Icon.CLOSE, "Close", () -> { });
        b.setBounds(0, 0, 30, 30);
        draw(b, 0);
        assertEquals(0L, canvas.count("fillRoundRect"));
        assertEquals(1L, canvas.count("icon"));
        assertEquals("Close", b.tooltip());
    }

    @Test
    public void toggleFlipsOnClickAndSlides() {
        List<Boolean> seen = new ArrayList<>();
        Toggle t = new Toggle(theme, false, seen::add);
        t.setBounds(0, 0, 34, 18);
        draw(t, 0);
        t.mouseDown(5, 5, 0, 0);
        t.mouseUp(5, 5, 0, 0);
        assertTrue(t.on());
        assertEquals(List.of(true), seen);
        canvas.calls.clear();
        draw(t, 1);
        float startKnob = canvas.calls.get(canvas.calls.size() - 1).x();
        canvas.calls.clear();
        draw(t, 500);
        StubCanvas.Call knob = canvas.calls.get(canvas.calls.size() - 1);
        assertTrue(knob.x() > startKnob);
        assertEquals(17f + 0f, knob.x() - 0f, 3f);
        assertEquals(14f, knob.w(), 0f);
        assertEquals(0xFFFFFFFF, knob.argb());
    }

    @Test
    public void toggleSetOnIsSilentAndSnaps() {
        List<Boolean> seen = new ArrayList<>();
        Toggle t = new Toggle(theme, false, seen::add);
        t.setBounds(0, 0, 34, 18);
        t.setOn(true);
        assertTrue(t.on());
        assertTrue(seen.isEmpty());
        draw(t, 0);
        assertEquals(Theme.ACCENT, fill().argb());
    }

    @Test
    public void toggleKeys() {
        List<Boolean> seen = new ArrayList<>();
        Toggle t = new Toggle(theme, false, seen::add);
        assertFalse(t.keyDown(Keys.SPACE, 0));
        t.setFocused(true);
        assertTrue(t.keyDown(Keys.SPACE, 0));
        assertTrue(t.keyDown(Keys.ENTER, 0));
        assertEquals(List.of(true, false), seen);
    }

    @Test
    public void segmentedSelectsByClickAndKeys() {
        List<Integer> seen = new ArrayList<>();
        Segmented s = new Segmented(theme, List.of("Get", "Mine"), 0, seen::add);
        s.setBounds(0, 0, s.preferredWidth(), 30);
        draw(s, 0);
        s.mouseDown(s.w() - 5, 10, 0, 0);
        assertEquals(1, s.selected());
        s.mouseDown(s.w() - 5, 10, 0, 0);
        s.mouseDown(5, 10, 0, 0);
        assertEquals(0, s.selected());
        assertEquals(List.of(1, 0), seen);
        s.setFocused(true);
        s.keyDown(Keys.RIGHT, 0);
        assertEquals(1, s.selected());
        s.keyDown(Keys.RIGHT, 0);
        assertEquals(1, s.selected());
        s.keyDown(Keys.LEFT, 0);
        assertEquals(0, s.selected());
    }

    @Test
    public void segmentedPillSlidesBetweenSegments() {
        Segmented s = new Segmented(theme, List.of("Get", "Mine"), 0, i -> { });
        s.setBounds(0, 0, s.preferredWidth(), 30);
        draw(s, 0);
        float at0 = pill().x();
        s.mouseDown(s.w() - 5, 10, 0, 0);
        canvas.calls.clear();
        draw(s, 40);
        float mid = pill().x();
        canvas.calls.clear();
        draw(s, 500);
        float at1 = pill().x();
        assertTrue(at0 < mid && mid < at1);
    }

    @Test
    public void dropdownOpensAnOverlayAndPicksWithTheMouse() {
        Ctx ctx = new Ctx(theme);
        List<Integer> seen = new ArrayList<>();
        Dropdown d = new Dropdown(theme, List.of("Normal", "Hard", "Peaceful"), 0, seen::add, ctx);
        d.setBounds(100, 100, 200, 30);
        draw(d, 0);
        assertTrue(d.mouseDown(110, 110, 0, 0));
        Widget menu = ctx.overlay;
        assertNotNull(menu);
        assertTrue(d.isOpen());
        assertEquals(100, menu.x());
        assertEquals(134, menu.y());
        assertEquals(3 * Theme.ROW_H + 8, menu.h());
        draw(menu, 10);
        assertTrue(canvas.texts().containsAll(List.of("Normal", "Hard", "Peaceful")));
        float rowY = menu.y() + 4 + Theme.ROW_H + 10;
        menu.mouseDown(120, rowY, 0, 0);
        assertEquals(List.of(1), seen);
        assertEquals(1, d.selected());
        assertNull(ctx.overlay);
        assertFalse(d.isOpen());
    }

    @Test
    public void dropdownFlipsAboveWhenThereIsNoRoomBelow() {
        Ctx ctx = new Ctx(theme);
        Dropdown d = new Dropdown(theme, List.of("a", "b", "c"), 0, i -> { }, ctx);
        d.setBounds(100, 1040, 200, 30);
        draw(d, 0);
        d.mouseDown(110, 1050, 0, 0);
        Widget menu = ctx.overlay;
        assertTrue(menu.y() + menu.h() <= 1040);
    }

    @Test
    public void dropdownMenuKeys() {
        Ctx ctx = new Ctx(theme);
        List<Integer> seen = new ArrayList<>();
        Dropdown d = new Dropdown(theme, List.of("Alpha", "Beta", "Bravo", "Delta"), 0, seen::add, ctx);
        d.setBounds(100, 100, 200, 30);
        draw(d, 0);
        d.setFocused(true);
        assertTrue(d.keyDown(Keys.DOWN, 0));
        Widget menu = ctx.overlay;
        menu.keyDown(Keys.DOWN, 0);
        menu.keyDown(Keys.DOWN, 0);
        menu.keyDown(Keys.ENTER, 0);
        assertEquals(List.of(2), seen);
        assertNull(ctx.overlay);
    }

    @Test
    public void dropdownEscClosesWithoutPicking() {
        Ctx ctx = new Ctx(theme);
        List<Integer> seen = new ArrayList<>();
        Dropdown d = new Dropdown(theme, List.of("a", "b"), 0, seen::add, ctx);
        d.setBounds(100, 100, 200, 30);
        d.mouseDown(110, 110, 0, 0);
        ctx.overlay.keyDown(Keys.ESCAPE, 0);
        assertNull(ctx.overlay);
        assertTrue(seen.isEmpty());
    }

    @Test
    public void typingJumpsToAMatchingOption() {
        Ctx ctx = new Ctx(theme);
        List<Integer> seen = new ArrayList<>();
        Dropdown d = new Dropdown(theme, List.of("Alpha", "Beta", "Bravo", "Delta"), 0, seen::add, ctx);
        d.setBounds(100, 100, 200, 30);
        d.mouseDown(110, 110, 0, 0);
        Widget menu = ctx.overlay;
        draw(menu, 100);
        menu.charTyped('b');
        menu.charTyped('r');
        menu.keyDown(Keys.ENTER, 0);
        assertEquals(List.of(2), seen);
    }

    @Test
    public void clickingTheOpenDropdownAgainClosesIt() {
        Ctx ctx = new Ctx(theme);
        Dropdown d = new Dropdown(theme, List.of("a", "b"), 0, i -> { }, ctx);
        d.setBounds(100, 100, 200, 30);
        d.mouseDown(110, 110, 0, 0);
        assertNotNull(ctx.overlay);
        d.mouseDown(110, 110, 0, 0);
        assertNull(ctx.overlay);
    }

    @Test
    public void longDropdownScrollsItsMenu() {
        Ctx ctx = new Ctx(theme);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("opt" + i);
        }
        Dropdown d = new Dropdown(theme, many, 0, i -> { }, ctx);
        d.setBounds(100, 100, 200, 30);
        draw(d, 0);
        d.mouseDown(110, 110, 0, 0);
        Widget menu = ctx.overlay;
        assertEquals(7 * Theme.ROW_H + 8, menu.h());
        for (int i = 0; i < 10; i++) {
            menu.keyDown(Keys.DOWN, 0);
        }
        canvas.calls.clear();
        draw(menu, 10);
        assertTrue(canvas.texts().contains("opt10"));
        assertFalse(canvas.texts().contains("opt0"));
    }

    @Test
    public void keyCaptureShowsNameThenCaptures() {
        Ctx ctx = new Ctx(theme);
        List<Integer> seen = new ArrayList<>();
        KeyCapture k = new KeyCapture(theme, 344, key -> "Right Shift", seen::add, ctx);
        k.setBounds(0, 0, 120, 30);
        draw(k, 0);
        assertNotNull(canvas.textCall("Right Shift"));
        k.mouseDown(10, 10, 0, 0);
        assertTrue(k.capturing());
        assertSame(k, ctx.focus);
        canvas.calls.clear();
        draw(k, 10);
        assertNotNull(canvas.textCall("Press a key"));
        assertTrue(k.keyDown(65, 0));
        assertEquals(List.of(65), seen);
        assertFalse(k.capturing());
    }

    @Test
    public void keyCaptureSwallowsEverythingWhileWaiting() {
        Ctx ctx = new Ctx(theme);
        KeyCapture k = new KeyCapture(theme, 344, key -> "x", i -> { }, ctx);
        k.setBounds(0, 0, 120, 30);
        k.mouseDown(10, 10, 0, 0);
        assertTrue(k.keyDown(Keys.ESCAPE, 0));
        assertEquals(344, k.bind().key);
        k.mouseDown(10, 10, 0, 0);
        assertTrue(k.keyDown(Keys.RIGHT_SHIFT, 0));
        assertEquals(344, k.bind().key);
    }

    @Test
    public void keyCaptureCancelsWhenFocusIsLost() {
        Ctx ctx = new Ctx(theme);
        KeyCapture k = new KeyCapture(theme, 344, key -> "x", i -> { }, ctx);
        k.setBounds(0, 0, 120, 30);
        k.mouseDown(10, 10, 0, 0);
        ctx.blur();
        assertFalse(k.capturing());
    }

    @Test
    public void keyCaptureShowsUnboundForMinusOne() {
        KeyCapture k = new KeyCapture(theme, -1, key -> "x", i -> { }, new Ctx(theme));
        k.setBounds(0, 0, 120, 30);
        draw(k, 0);
        assertEquals(Theme.TEXT_3, canvas.textCall("Unbound").argb());
    }

    @Test
    public void toastQueuesAndShowsOneAtATime() {
        Toast t = new Toast(theme);
        t.setBounds(0, 0, 800, 600);
        t.show("First", "get oak_log 64");
        t.show("Second", null);
        assertEquals(2, t.pending());
        draw(t, 1000);
        assertTrue(canvas.texts().contains("First"));
        assertTrue(canvas.texts().contains("#get oak_log 64"));
        assertFalse(canvas.texts().contains("Second"));
        draw(t, 1000 + 3000 + 2 * Theme.ANIM);
        assertEquals(1, t.pending());
        canvas.calls.clear();
        draw(t, 1000 + 3100 + 2 * Theme.ANIM);
        assertTrue(canvas.texts().contains("Second"));
    }

    @Test
    public void toastSitsBottomCentreAndSlidesUp() {
        Toast t = new Toast(theme);
        t.setBounds(100, 100, 800, 600);
        t.show("Hello", null);
        draw(t, 1000);
        StubCanvas.Call start = canvas.textCall("Hello");
        canvas.calls.clear();
        draw(t, 1500);
        StubCanvas.Call end = canvas.textCall("Hello");
        assertTrue(end.y() < start.y());
        assertEquals(8f, start.y() - end.y(), 0.5f);
        assertTrue(end.y() < 700 && end.y() > 600);
        float boxLeft = canvas.calls.stream().filter(c -> c.kind().equals("fillRoundRect")).findFirst().get().x();
        float boxW = canvas.calls.stream().filter(c -> c.kind().equals("fillRoundRect")).findFirst().get().w();
        assertEquals(500f, boxLeft + boxW / 2f, 1f);
    }

    @Test
    public void toastIgnoresEmptyAndNeverTakesClicks() {
        Toast t = new Toast(theme);
        t.setBounds(0, 0, 800, 600);
        t.show("", null);
        t.show((Result) null);
        assertEquals(0, t.pending());
        t.show("Hi", null);
        assertFalse(t.mouseDown(400, 560, 0, 0));
    }

    @Test
    public void toastFloodIsCapped() {
        Toast t = new Toast(theme);
        for (int i = 0; i < 50; i++) {
            t.show("n" + i, null);
        }
        assertTrue(t.pending() <= 5);
    }

    @Test
    public void rowLayoutGivesFlexWidgetsTheRest() {
        Button a = new Button(theme, "OK", () -> { });
        Label flex = new Label(theme, "x", theme.body, Theme.TEXT);
        IconButton c = new IconButton(theme, Icon.CLOSE, null, () -> { });
        Widget spacer = new Divider(theme, false);
        int end = Layout.row(10, 20, 400, 30, 8, a, new Widget(theme) {
            @Override
            public void draw(Canvas cv, Frame f) {
            }
        }, c);
        assertEquals(10, a.x());
        assertEquals(20, a.y());
        assertEquals(30, a.h());
        assertEquals(410, end);
        assertEquals(410 - c.preferredWidth(), c.x());
        assertEquals(a.preferredWidth(), a.w());
        assertNotNull(flex);
        assertNotNull(spacer);
    }

    @Test
    public void columnLayoutStacksWithGaps() {
        Widget fixed1 = new Divider(theme, true);
        Button b = new Button(theme, "x", () -> { });
        int end = Layout.column(0, 0, 200, 300, 8, fixed1, b);
        assertEquals(0, fixed1.y());
        assertEquals(1 + 8, b.y());
        assertEquals(1 + 8 + 30, end);
    }

    @Test
    public void invisibleWidgetsAreSkippedByLayout() {
        Button a = new Button(theme, "a", () -> { });
        Button b = new Button(theme, "b", () -> { });
        b.setVisible(false);
        Button c = new Button(theme, "c", () -> { });
        Layout.row(0, 0, 400, 30, 10, a, b, c);
        assertEquals(a.preferredWidth() + 10, c.x());
    }

    @Test
    public void labelEllipsizesAndAligns() {
        Label l = new Label(theme, "a very long label that cannot fit", theme.body, Theme.TEXT);
        l.setBounds(0, 0, 60, 20);
        draw(l, 0);
        String shown = canvas.texts().get(0);
        assertTrue(shown.endsWith("…"));
        assertTrue(canvas.measure(shown, theme.body) <= 60f);
        canvas.calls.clear();
        Label r = new Label(theme, "hi", theme.body, Theme.TEXT).setAlign(Label.Align.RIGHT);
        r.setBounds(0, 0, 100, 20);
        draw(r, 0);
        assertEquals(100f - 0.6f * theme.body.sizePx() * 2, canvas.textCall("hi").x(), 0.01f);
    }

    @Test
    public void dividerIsAHairline() {
        Divider h = new Divider(theme, true);
        h.setBounds(0, 10, 200, 1);
        draw(h, 0);
        StubCanvas.Call c = canvas.calls.get(0);
        assertEquals(1f, c.h(), 0f);
        assertEquals(200f, c.w(), 0f);
        assertEquals(Theme.HAIRLINE, c.argb());
    }

    @Test
    public void reducedMotionMakesTheToggleLandAtOnce() {
        Anim.enabled = false;
        try {
            Toggle t = new Toggle(theme, false, b -> { });
            t.setBounds(0, 0, 34, 18);
            draw(t, 0);
            t.mouseDown(5, 5, 0, 0);
            t.mouseUp(5, 5, 0, 0);
            canvas.calls.clear();
            draw(t, 1);
            assertEquals(Theme.ACCENT, fill().argb());
        } finally {
            Anim.enabled = true;
        }
    }
}
