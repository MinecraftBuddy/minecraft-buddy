package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the iron pickaxe lines are only worth anything if the edge fires once and the wording stays greppable
public class PickDiagTest {
    @Test
    public void firesOnceWhenTheKitLosesItsPick() {
        PickDiag d = new PickDiag();
        assertFalse(d.lost(0));
        assertFalse(d.lost(1));
        assertFalse(d.lost(1));
        assertTrue(d.lost(0));
        // still gone is not news
        assertFalse(d.lost(0));
        assertFalse(d.lost(0));
    }

    @Test
    public void entersThePhaseWithoutAPickWithoutAnEdge() {
        PickDiag d = new PickDiag();
        assertFalse(d.lost(0));
        assertFalse(d.lost(0));
    }

    @Test
    public void aPickThatComesBackCanBeLostAgain() {
        PickDiag d = new PickDiag();
        assertFalse(d.lost(1));
        assertTrue(d.lost(0));
        assertFalse(d.lost(1));
        assertTrue(d.lost(0));
    }

    @Test
    public void resetForgetsWhatWasHeld() {
        PickDiag d = new PickDiag();
        assertFalse(d.lost(1));
        d.reset();
        // the first look after a reset only records
        assertFalse(d.lost(0));
    }

    @Test
    public void droppingFromTwoToOneIsNotLosingIt() {
        // a spare pick and then one of them gone: still owned, so not an edge
        PickDiag d = new PickDiag();
        assertFalse(d.lost(2));
        assertFalse(d.lost(1));
        assertTrue(d.lost(0));
    }

    @Test
    public void stackReadsDamageOverMaxAndWhere() {
        assertEquals("iron_pickaxe 215/250 hotbar#3 worn", PickDiag.stack("iron_pickaxe", 215, 250, "hotbar", 3, true));
        assertEquals("stone_pickaxe 4/131 cursor", PickDiag.stack("stone_pickaxe", 4, 131, "cursor", -1, false));
    }

    @Test
    public void missingLineStartsWithTheGrepPrefixAndHasEveryField() {
        String scan = PickDiag.scan(List.of("iron_pickaxe 215/250 hotbar#3 worn", "stone_pickaxe 4/131 bag#12"),
                List.of("iron_pickaxe 0/250 menu#2"), "FurnaceScreen", "FurnaceMenu");
        String line = PickDiag.missing(0, 1, 1, scan);
        assertEquals("kit: iron pickaxe missing, have=0 held=1 spent=1 picks=[iron_pickaxe 215/250 hotbar#3 worn, stone_pickaxe 4/131 bag#12]"
                + " open=[iron_pickaxe 0/250 menu#2] screen=FurnaceScreen menu=FurnaceMenu", line);
    }

    @Test
    public void emptyListsStillPrintTheirBrackets() {
        assertEquals("picks=[] open=[] screen=none menu=none", PickDiag.scan(List.of(), List.of(), "none", "none"));
    }

    @Test
    public void wornOutLineHasTheDamage() {
        String line = PickDiag.wornOut(213, 250, "hotbar", 2);
        assertTrue(line, line.startsWith("kit: iron pickaxe worn out, iron_pickaxe 213/250 hotbar#2"));
    }
}
