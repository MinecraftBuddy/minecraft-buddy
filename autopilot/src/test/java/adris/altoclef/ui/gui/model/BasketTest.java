package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.ItemsAccess.BasketLine;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BasketTest {

    private static final int SHIFT = 1;
    private static final int CTRL = 2;
    private static final int SUPER = 8;

    @Test
    public void addKeepsOrderAndAccumulates() {
        Basket b = new Basket(false);
        b.add("oak_log", 1, 64);
        b.add("iron_ingot", 3, 64);
        b.add("oak_log", 2, 64);
        assertEquals(List.of("oak_log", "iron_ingot"), b.lines().stream().map(Basket.Line::name).toList());
        assertEquals(3, b.count("oak_log"));
        assertEquals(3, b.count("iron_ingot"));
        assertEquals(6, b.total());
        assertEquals(2, b.size());
    }

    @Test
    public void addRemembersTheStackSizeOfTheFirstAdd() {
        Basket b = new Basket(false);
        b.add("ender_pearl", 1, 16);
        b.add("ender_pearl", 1, 64);
        assertEquals(16, b.stackOf("ender_pearl"));
        assertEquals(1, b.stackOf("nothing"));
    }

    @Test
    public void addIgnoresNoiseInAGetBasket() {
        Basket b = new Basket(false);
        b.add("", 5, 64);
        b.add(null, 5, 64);
        b.add("stick", 0, 64);
        b.add("stick", -4, 64);
        assertTrue(b.isEmpty());
    }

    @Test
    public void stepNeverGoesBelowZeroAndZeroRemovesInGet() {
        Basket b = new Basket(false);
        b.add("stick", 2, 64);
        b.step("stick", -1);
        assertEquals(1, b.count("stick"));
        b.step("stick", -5);
        assertFalse(b.contains("stick"));
        assertTrue(b.isEmpty());
    }

    @Test
    public void stepClampsAtNineNineNineNine() {
        Basket b = new Basket(false);
        b.add("cobblestone", 9990, 64);
        b.step("cobblestone", 64);
        assertEquals(Basket.MAX, b.count("cobblestone"));
        b.add("cobblestone", 500, 64);
        assertEquals(Basket.MAX, b.count("cobblestone"));
        b.set("cobblestone", 100000);
        assertEquals(Basket.MAX, b.count("cobblestone"));
    }

    @Test
    public void stepAndSetIgnoreUnknownNames() {
        Basket b = new Basket(false);
        b.step("ghost", 5);
        b.set("ghost", 5);
        assertTrue(b.isEmpty());
    }

    @Test
    public void setZeroRemovesInGet() {
        Basket g = new Basket(false);
        g.add("stick", 4, 64);
        g.set("stick", 0);
        assertFalse(g.contains("stick"));
    }

    @Test
    public void mineBasketIsASetOfBlocksAndOneLimit() {
        Basket m = new Basket(true);
        m.add("coal_ore", 64, 64);
        m.add("coal_ore", 64, 64);
        m.add("iron_ore", 5, 64);
        assertEquals(2, m.size());
        // no per block counts in a mine basket, whatever the click asked for
        assertEquals(0, m.count("coal_ore"));
        m.step("coal_ore", 5);
        m.set("coal_ore", 5);
        assertEquals(0, m.count("coal_ore"));
        assertEquals(0, m.limit());
        m.setLimit(20);
        assertEquals(20, m.limit());
        assertEquals(20, m.total());
        m.stepLimit(-30);
        assertEquals(0, m.limit());
        m.stepLimit(100000);
        assertEquals(Basket.MAX, m.limit());
    }

    @Test
    public void aGetBasketHasNoLimit() {
        Basket g = new Basket(false);
        g.add("stick", 2, 64);
        g.setLimit(50);
        g.stepLimit(5);
        assertEquals(0, g.limit());
        assertEquals(2, g.total());
    }

    @Test
    public void clearForgetsTheLimitToo() {
        Basket m = new Basket(true);
        m.add("coal_ore", 0, 64);
        m.setLimit(20);
        m.clear();
        assertEquals(0, m.limit());
        assertTrue(m.isEmpty());
    }

    @Test
    public void removeAndClear() {
        Basket b = new Basket(false);
        b.add("a", 1, 1);
        b.add("b", 1, 1);
        b.remove("a");
        assertEquals(1, b.size());
        b.clear();
        assertTrue(b.isEmpty());
        assertEquals(0, b.total());
    }

    @Test
    public void commandLinesFollowTheBasket() {
        Basket b = new Basket(false);
        b.add("oak_log", 64, 64);
        b.add("iron_ingot", 3, 64);
        assertEquals(List.of(new BasketLine("oak_log", 64), new BasketLine("iron_ingot", 3)), b.toCommandLines());
    }

    @Test
    public void mineCommandLinesCarryTheSharedLimit() {
        Basket b = new Basket(true);
        b.add("iron_ore", 0, 64);
        b.add("coal_ore", 0, 64);
        assertEquals(List.of(new BasketLine("iron_ore", 0), new BasketLine("coal_ore", 0)), b.toCommandLines());
        b.setLimit(20);
        assertEquals(List.of(new BasketLine("iron_ore", 20), new BasketLine("coal_ore", 20)), b.toCommandLines());
    }

    @Test
    public void linesIsASnapshot() {
        Basket b = new Basket(false);
        b.add("a", 1, 1);
        List<Basket.Line> before = b.lines();
        b.add("b", 1, 1);
        assertEquals(1, before.size());
    }

    @Test
    public void stepForReadsTheModifiers() {
        assertEquals(1, Basket.stepFor(0, 64));
        assertEquals(64, Basket.stepFor(SHIFT, 64));
        assertEquals(16, Basket.stepFor(SHIFT, 16));
        assertEquals(10, Basket.stepFor(CTRL, 64));
        assertEquals(10, Basket.stepFor(SUPER, 64));
        assertEquals(10, Basket.stepFor(SHIFT | CTRL, 64));
        assertEquals(1, Basket.stepFor(SHIFT, 1));
        // a stack size of zero from a bad facade must not become a step of zero
        assertEquals(1, Basket.stepFor(SHIFT, 0));
    }

    @Test
    public void summaryAndButtonForGet() {
        Basket b = new Basket(false);
        assertEquals("Nothing added yet", b.summaryLine());
        assertEquals("Get items", b.buttonLabel());
        b.add("oak_log", 64, 64);
        assertEquals("1 item · 64 total", b.summaryLine());
        assertEquals("Get 64 items", b.buttonLabel());
        b.add("iron_ingot", 3, 64);
        assertEquals("2 items · 67 total", b.summaryLine());
        assertEquals("Get 67 items", b.buttonLabel());
        b.clear();
        b.add("stick", 1, 64);
        assertEquals("Get 1 item", b.buttonLabel());
    }

    @Test
    public void summaryAndButtonForMine() {
        Basket b = new Basket(true);
        assertEquals("Mine blocks", b.buttonLabel());
        assertEquals("Nothing added yet", b.summaryLine());
        b.add("iron_ore", 0, 64);
        assertEquals("Mine 1 block", b.buttonLabel());
        assertEquals("1 block · no limit", b.summaryLine());
        b.setLimit(20);
        assertEquals("1 block · stop after 20", b.summaryLine());
        b.add("coal_ore", 0, 64);
        assertEquals("Mine 2 blocks", b.buttonLabel());
        assertEquals("2 blocks · stop after 20 in total", b.summaryLine());
    }
}
