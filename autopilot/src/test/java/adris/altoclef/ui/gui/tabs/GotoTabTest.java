package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GotoTabTest {

    private FakeFacade facade;
    private Preview p;
    private GotoTab tab;

    @Before
    public void setUp() throws Exception {
        GotoRecent.clear();
        GotoRecent.clock = () -> 1_000_000_000L;
        facade = new FakeFacade();
        GotoTab[] holder = new GotoTab[1];
        p = new Preview(facade, pv -> {
            holder[0] = new GotoTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        p.settle();
    }

    @After
    public void tearDown() {
        GotoRecent.clear();
        GotoRecent.clock = System::currentTimeMillis;
    }

    private void focus(int i) {
        TextField f = tab.fieldAt(i).field();
        p.click(f.x() + 10, f.y() + f.h() / 2f);
        assertTrue(f.isFocused());
    }

    private void fillIn(String x, String y, String z) {
        focus(0);
        p.type(x);
        focus(1);
        p.type(y);
        focus(2);
        p.type(z);
    }

    private void clickButton(adris.altoclef.ui.gui.kit.Button b) {
        p.click(b.x() + b.w() / 2f, b.y() + b.h() / 2f);
    }

    @Test
    public void goWithThreeNumbers() {
        fillIn("120", "64", "-30");
        clickButton(tab.goButton());
        assertEquals(List.of("goto 120 64 -30"), facade.actions);
        assertEquals(1, GotoRecent.snapshot().size());
    }

    @Test
    public void yIsOptional() {
        fillIn("120", "", "-30");
        clickButton(tab.goButton());
        assertEquals(List.of("goto 120 -30"), facade.actions);
    }

    @Test
    public void yAloneIsALevel() {
        focus(1);
        p.type("70");
        clickButton(tab.goButton());
        assertEquals(List.of("goto 70"), facade.actions);
    }

    @Test
    public void tildeIsRelativeToTheBlockUnderThePlayer() {
        fillIn("~10", "", "~-5");
        clickButton(tab.goButton());
        // player is at 120.5, -30.5: block 120, -31
        assertEquals(List.of("goto 130 -36"), facade.actions);
    }

    @Test
    public void setGoalOnlyDoesNotWalk() {
        fillIn("1", "2", "3");
        clickButton(tab.setGoalButton());
        assertEquals(List.of("goal 1 2 3"), facade.actions);
    }

    @Test
    public void enterInAnyBoxIsGo() {
        fillIn("5", "6", "7");
        p.key(Keys.ENTER);
        assertEquals(List.of("goto 5 6 7"), facade.actions);
        focus(0);
        p.key(Keys.ENTER);
        assertEquals(2, facade.actions.size());
        focus(1);
        p.key(Keys.ENTER);
        assertEquals(3, facade.actions.size());
    }

    @Test
    public void enterInABlankYBoxStillGoes() {
        fillIn("5", "", "7");
        focus(1);
        p.key(Keys.ENTER);
        assertEquals(List.of("goto 5 7"), facade.actions);
    }

    @Test
    public void nudgeButtonsDoNotGo() {
        // a full target, so a go would really go if the nudge leaked one
        fillIn("10", "", "20");
        var f = tab.fieldAt(0);
        int s = p.theme.px(24);
        int edge = p.theme.px(3);
        p.click(f.x() + f.w() - edge - s / 2f, f.y() + f.h() / 2f);
        assertEquals("11", f.text());
        assertTrue(facade.actions.isEmpty());
        // the minus button too, and enter still goes
        p.click(f.x() + f.w() - edge - s - p.theme.px(2) - s / 2f, f.y() + f.h() / 2f);
        assertEquals("10", f.text());
        assertTrue(facade.actions.isEmpty());
        focus(0);
        p.key(Keys.ENTER);
        assertEquals(List.of("goto 10 20"), facade.actions);
    }

    @Test
    public void aNudgeEndsTheMissingZNag() {
        focus(0);
        p.type("5");
        p.key(Keys.ENTER);
        assertEquals("Add a z to go with the x", tab.statusLine());
        assertTrue(tab.fieldAt(2).field().invalid());
        var z = tab.fieldAt(2);
        int s = p.theme.px(24);
        int edge = p.theme.px(3);
        p.click(z.x() + z.w() - edge - s / 2f, z.y() + z.h() / 2f);
        p.tick();
        assertEquals("1", z.text());
        assertFalse(tab.statusIsError());
        assertFalse(z.field().invalid());
    }

    @Test
    public void halfAnAnswerAsksForTheRest() {
        focus(0);
        p.type("12");
        p.key(Keys.ENTER);
        assertTrue(facade.actions.isEmpty());
        assertEquals("Add a z to go with the x", tab.statusLine());
        assertTrue(tab.statusIsError());
        assertTrue(tab.fieldAt(2).field().invalid());
        // typing again clears the nag
        focus(2);
        p.type("3");
        assertFalse(tab.statusIsError());
        assertFalse(tab.fieldAt(2).field().invalid());
    }

    @Test
    public void nothingEnteredSaysWhatToEnter() {
        p.tick();
        assertEquals("Enter x and z, or just a y level", tab.statusLine());
        assertFalse(tab.statusIsError());
        clickButton(tab.goButton());
        assertTrue(facade.actions.isEmpty());
        assertEquals("Enter x and z, or just a y level", tab.statusLine());
    }

    @Test
    public void straightLineUpdatesLive() {
        fillIn("520", "", "-30");
        assertEquals("Straight line 400 blocks · overworld", tab.statusLine());
        facade.world.pos = new adris.altoclef.ui.gui.facade.WorldAccess.Position(320.5, 64, -30.5);
        p.tick();
        assertEquals("Straight line 200 blocks · overworld", tab.statusLine());
        facade.world.dimension = "the_nether";
        assertEquals("Straight line 200 blocks · the nether", tab.statusLine());
    }

    @Test
    public void mistakesTurnTheBoxRedLive() {
        tab.fieldAt(1).setValue("abc");
        p.tick();
        assertTrue(tab.fieldAt(1).field().invalid());
        assertFalse(tab.fieldAt(0).field().invalid());
        assertEquals("Y needs a number or ~", tab.statusLine());
        assertTrue(tab.statusIsError());
        // enter on a bad box does not go
        tab.fieldAt(0).setValue("1");
        tab.fieldAt(2).setValue("2");
        focus(1);
        p.key(Keys.ENTER);
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void aLoneMinusIsStillBeingTyped() {
        focus(0);
        p.type("-");
        assertFalse(tab.fieldAt(0).field().invalid());
        assertFalse(tab.statusIsError());
        p.type("5");
        assertEquals("-5", tab.fieldAt(0).text());
        assertEquals(-5, (int) tab.current().x());
    }

    @Test
    public void letterGarbageNeverGetsIn() {
        focus(0);
        p.type("1a2b3");
        assertEquals("123", tab.fieldAt(0).text());
    }

    @Test
    public void pastingThreeNumbersSpreadsAcrossTheBoxes() {
        facade.clipboard.set("120, 64, -30");
        focus(1);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("120", tab.fieldAt(0).text());
        assertEquals("64", tab.fieldAt(1).text());
        assertEquals("-30", tab.fieldAt(2).text());
        assertTrue(tab.current().complete());
    }

    @Test
    public void pastingTwoNumbersIsXAndZ() {
        tab.fieldAt(1).setValue("99");
        facade.clipboard.set("10 20");
        focus(2);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("10", tab.fieldAt(0).text());
        assertEquals("", tab.fieldAt(1).text());
        assertEquals("20", tab.fieldAt(2).text());
    }

    @Test
    public void pastingWithSlashes() {
        facade.clipboard.set("1/2/3");
        focus(0);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("1", tab.fieldAt(0).text());
        assertEquals("2", tab.fieldAt(1).text());
        assertEquals("3", tab.fieldAt(2).text());
    }

    @Test
    public void pastingOneNumberJustPastesIt() {
        facade.clipboard.set("120.9");
        focus(2);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("120", tab.fieldAt(2).text());
        assertEquals("", tab.fieldAt(0).text());
    }

    @Test
    public void pastingWordsLeavesTheBoxesAloneAndSaysSo() {
        fillIn("5", "6", "7");
        for (String junk : new String[]{"hello world", "12 abc", "1 2 3 4", "abc", "1e3"}) {
            facade.clipboard.set(junk);
            focus(1);
            p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
            assertEquals(junk, "5", tab.fieldAt(0).text());
            assertEquals(junk, "6", tab.fieldAt(1).text());
            assertEquals(junk, "7", tab.fieldAt(2).text());
            p.settle();
            assertEquals(junk, "Not coordinates. Paste x y z, like 120 64 -30.", p.shell.toast().currentLine());
            assertFalse(p.shell.toast().currentOk());
        }
    }

    @Test
    public void aRelativeDecimalPastesAsItsWholeBlock() {
        facade.clipboard.set("~1.5");
        focus(0);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("~1", tab.fieldAt(0).text());
        facade.clipboard.set("~5k");
        focus(2);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("~5000", tab.fieldAt(2).text());
    }

    @Test
    public void anF3TeleportLinePastesItsPosition() {
        facade.clipboard.set("/execute in minecraft:overworld run tp @s 120.50 64.00 -30.50 0.00 0.00");
        focus(2);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("120", tab.fieldAt(0).text());
        assertEquals("64", tab.fieldAt(1).text());
        assertEquals("-31", tab.fieldAt(2).text());
    }

    @Test
    public void pastingBlankDoesNothingAndDoesNotNag() {
        fillIn("5", "", "7");
        facade.clipboard.set("   ");
        focus(0);
        p.shell.keyDown(Keys.V, Keys.MOD_CTRL);
        assertEquals("5", tab.fieldAt(0).text());
        p.settle();
        assertNull(p.shell.toast().currentLine());
    }

    @Test
    public void copyStillWorksInABox() {
        focus(0);
        p.type("123");
        p.shell.keyDown(Keys.A, Keys.MOD_CTRL);
        p.shell.keyDown(Keys.C, Keys.MOD_CTRL);
        assertEquals("123", facade.clipboard.get());
    }

    @Test
    public void myPositionAndClear() {
        clickButton(tab.hereButton());
        assertEquals("120", tab.fieldAt(0).text());
        assertEquals("64", tab.fieldAt(1).text());
        assertEquals("-31", tab.fieldAt(2).text());
        assertTrue(tab.current().complete());
        clickButton(tab.clearButton());
        assertEquals("", tab.fieldAt(0).text());
        assertEquals("", tab.fieldAt(1).text());
        assertEquals("", tab.fieldAt(2).text());
        assertSame(tab.fieldAt(0).field(), p.shell.focusedWidget());
    }

    @Test
    public void myPositionWithNobodyHomeToasts() {
        facade.world.pos = null;
        clickButton(tab.hereButton());
        assertEquals("", tab.fieldAt(0).text());
        p.settle();
        assertEquals("You are not in a world yet.", p.shell.toast().currentLine());
    }

    @Test
    public void tabOrderIsBoxesThenButtons() {
        p.key(Keys.TAB);
        assertSame(tab.fieldAt(0).field(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.fieldAt(1).field(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.fieldAt(2).field(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.goButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.setGoalButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.hereButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.clearButton(), p.shell.focusedWidget());
        // nothing recent yet, so focus goes straight to the shell's footer controls
        p.key(Keys.TAB);
        assertSame(p.shell.appearance(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(p.shell.stopButton(), p.shell.focusedWidget());
    }

    @Test
    public void recentListIsATabStopOnceItHasRows() {
        GotoRecent.push(1, null, 2, "overworld");
        tab.onShown();
        p.settle();
        for (int i = 0; i < 7; i++) {
            p.key(Keys.TAB);
        }
        assertSame(tab.clearButton(), p.shell.focusedWidget());
        p.key(Keys.TAB);
        assertSame(tab.recentList(), p.shell.focusedWidget());
    }

    @Test
    public void recentGoInAnotherDimensionOnlyFills() {
        GotoRecent.push(120, 64, -30, "the_nether");
        tab.onShown();
        p.settle();
        int[] z = tab.goZone(0);
        p.click(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        assertEquals("120", tab.fieldAt(0).text());
        assertTrue(facade.actions.isEmpty());
        p.settle();
        assertEquals("That spot is in the nether. Switch dimensions to go there.", p.shell.toast().currentLine());
        // and in the right dimension it goes
        facade.world.dimension = "the_nether";
        p.click(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        assertEquals(List.of("goto 120 64 -30"), facade.actions);
    }

    @Test
    public void recentWithNoDimensionNameJustGoes() {
        GotoRecent.push(5, null, 6, "");
        tab.onShown();
        p.settle();
        int[] z = tab.goZone(0);
        p.click(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        assertEquals(List.of("goto 5 6"), facade.actions);
    }

    @Test
    public void myPositionUsesTheFeetBlock() {
        facade.world.pos = new adris.altoclef.ui.gui.facade.WorldAccess.Position(10.5, 63.9375, 3.5);
        clickButton(tab.hereButton());
        assertEquals("64", tab.fieldAt(1).text());
    }

    // a facade whose goTo says no, or blows up, and everything else is the fake
    private GotoTab tabWithBrokenGoTo(boolean throwing) throws Exception {
        adris.altoclef.ui.gui.facade.WorldAccess broken = (adris.altoclef.ui.gui.facade.WorldAccess) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{adris.altoclef.ui.gui.facade.WorldAccess.class}, (proxy, m, args) -> {
                    if (m.getName().equals("goTo")) {
                        if (throwing) {
                            throw new IllegalStateException("boom");
                        }
                        return adris.altoclef.ui.gui.facade.Result.fail("Baritone said no", "goto 1 2");
                    }
                    return m.invoke(facade.world, args);
                });
        adris.altoclef.ui.gui.facade.BotFacade wrapped = (adris.altoclef.ui.gui.facade.BotFacade) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{adris.altoclef.ui.gui.facade.BotFacade.class}, (proxy, m, args) ->
                        m.getName().equals("world") ? broken : m.invoke(facade, args));
        GotoTab[] holder = new GotoTab[1];
        p = new Preview(facade, pv -> {
            holder[0] = new GotoTab(pv.theme, wrapped, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        p.settle();
        return tab;
    }

    @Test
    public void aRefusedGoToastsTheReasonAndIsNotRemembered() throws Exception {
        tabWithBrokenGoTo(false);
        fillIn("1", "", "2");
        clickButton(tab.goButton());
        p.settle();
        assertEquals("Baritone said no", p.shell.toast().currentLine());
        assertFalse(p.shell.toast().currentOk());
        assertTrue(GotoRecent.snapshot().isEmpty());
    }

    @Test
    public void aThrowingFacadeDoesNotTakeTheGuiDown() throws Exception {
        tabWithBrokenGoTo(true);
        fillIn("1", "", "2");
        clickButton(tab.goButton());
        p.settle();
        assertFalse(p.shell.toast().currentOk());
        assertTrue(GotoRecent.snapshot().isEmpty());
    }

    @Test
    public void recentRemembersTheNewestFirstWithoutDuplicates() {
        fillIn("1", "", "1");
        clickButton(tab.goButton());
        tab.fieldAt(0).setValue("2");
        tab.fieldAt(2).setValue("2");
        clickButton(tab.goButton());
        tab.fieldAt(0).setValue("1");
        tab.fieldAt(2).setValue("1");
        clickButton(tab.goButton());
        List<GotoRecent.Entry> r = GotoRecent.snapshot();
        assertEquals(2, r.size());
        assertEquals("1 1", GotoRecent.label(r.get(0)));
        assertEquals("2 2", GotoRecent.label(r.get(1)));
    }

    @Test
    public void recentKeepsEight() {
        for (int i = 0; i < 12; i++) {
            GotoRecent.push(i, null, i, "overworld");
        }
        List<GotoRecent.Entry> r = GotoRecent.snapshot();
        assertEquals(8, r.size());
        assertEquals("11 11", GotoRecent.label(r.get(0)));
        assertEquals("4 4", GotoRecent.label(r.get(7)));
    }

    @Test
    public void sameSpotInAnotherDimensionIsAnotherEntry() {
        GotoRecent.push(1, 2, 3, "overworld");
        GotoRecent.push(1, 2, 3, "the_nether");
        assertEquals(2, GotoRecent.snapshot().size());
    }

    @Test
    public void clickingARecentRowFillsTheBoxes() {
        GotoRecent.push(120, 64, -30, "overworld");
        GotoRecent.push(7, null, 9, "overworld");
        tab.onShown();
        p.settle();
        var list = tab.recentList();
        p.click(list.x() + 30, list.y() + p.theme.px(18));
        assertEquals("7", tab.fieldAt(0).text());
        assertEquals("", tab.fieldAt(1).text());
        assertEquals("9", tab.fieldAt(2).text());
        assertTrue(facade.actions.isEmpty());
    }

    @Test
    public void theGoButtonOnARecentRowGoesStraightAway() {
        GotoRecent.push(120, 64, -30, "overworld");
        tab.onShown();
        p.settle();
        int[] z = tab.goZone(0);
        p.click(z[0] + z[2] / 2f, z[1] + z[3] / 2f);
        assertEquals(List.of("goto 120 64 -30"), facade.actions);
    }

    @Test
    public void recentLabelsAndAges() {
        assertEquals("120 64 -30", GotoRecent.label(new GotoRecent.Entry(120, 64, -30, "overworld", 0)));
        assertEquals("120 -30", GotoRecent.label(new GotoRecent.Entry(120, null, -30, "overworld", 0)));
        assertEquals("y 64", GotoRecent.label(new GotoRecent.Entry(null, 64, null, "overworld", 0)));
        assertEquals("just now", GotoRecent.ago(0, 5_000));
        assertEquals("40 s ago", GotoRecent.ago(0, 40_000));
        assertEquals("2 min ago", GotoRecent.ago(0, 130_000));
        assertEquals("3 h ago", GotoRecent.ago(0, 3 * 3_600_000L + 5));
        assertEquals("2 d ago", GotoRecent.ago(0, 2 * 86_400_000L + 5));
        assertEquals("just now", GotoRecent.ago(10_000, 0));
    }

    @Test
    public void sidebarContract() {
        assertEquals("goto", tab.id());
        assertEquals("Go to", tab.title());
        assertFalse(tab.hasSearch());
        assertNull(tab.fieldAt(0).error());
    }
}
