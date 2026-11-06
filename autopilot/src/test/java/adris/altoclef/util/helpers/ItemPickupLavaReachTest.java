package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.LavaWorld.Cell.LAVA;
import static adris.altoclef.util.helpers.LavaWorld.Cell.STONE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// lava near a drop only costs us the drop when there is nowhere dry to grab it from. the drop sits at 10.5 64.1 10.5 on a
// floor whose top block is y 63
public class ItemPickupLavaReachTest {

    private static boolean blocked(LavaWorld w) {
        return ItemPickupRules.lavaBlocksPickup(10.5, 64.1, 10.5, w, w);
    }

    @Test
    public void noLavaAnywhereIsNeverBlocked() {
        assertFalse(blocked(LavaWorld.flat(63)));
    }

    @Test
    public void aShoreDropWithDryGroundBehindItIsStillPickedUp() {
        // the lake is the east side (x 11 and up, level with the floor), the drop lies on the last shore block
        LavaWorld w = LavaWorld.flat(63).fill(11, 63, -5, 25, 63, 25, LAVA);
        assertTrue(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, w));
        // x 9 is two columns from the lake, so stepping there is not stepping next to lava
        assertFalse(blocked(w));
    }

    @Test
    public void aShoreDropOnAOneWideStripIsGivenUp() {
        // the strip is x 10, lava on both sides: every cell we could grab from is beside the lake
        LavaWorld w = LavaWorld.flat(63).fill(11, 63, -5, 25, 63, 25, LAVA).fill(-5, 63, -5, 9, 63, 25, LAVA);
        assertTrue(blocked(w));
    }

    @Test
    public void aDropOnAnIslandIsGivenUp() {
        LavaWorld w = new LavaWorld().fill(0, 63, 0, 20, 63, 20, LAVA).set(10, 63, 10, STONE);
        assertTrue(blocked(w));
    }

    @Test
    public void aDropInLavaIsGivenUpEvenWithDryGroundNextToIt() {
        // nothing within reach of a lava cell is lava-free
        LavaWorld w = LavaWorld.flat(63).set(10, 64, 10, LAVA);
        assertTrue(blocked(w));
    }

    @Test
    public void anOreDropWithAHoleInTheFloorDiagonallyIsStillPickedUp() {
        // the cave case: tunnel floor, a lava hole one step diagonal from the drop, solid floor on the other sides
        LavaWorld w = LavaWorld.flat(63).set(11, 63, 11, LAVA);
        assertTrue(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, w));
        assertFalse(blocked(w));
    }

    @Test
    public void aDropInATunnelWhoseOnlyFloorIsBesideLavaIsGivenUp() {
        // a 1 wide bridge of floor along z with lava under both sides' neighbours: every standing cell touches it
        LavaWorld w = new LavaWorld().fill(10, 60, 0, 10, 63, 20, STONE)
                .fill(9, 63, 0, 9, 63, 20, LAVA).fill(11, 63, 0, 11, 63, 20, LAVA);
        assertTrue(blocked(w));
    }

    @Test
    public void aDropOnASingleBlockBesideLavaIsGivenUp() {
        // the one block it lies on is the only floor there is, and it touches the lava
        LavaWorld w = new LavaWorld().set(10, 63, 10, STONE).set(11, 63, 10, LAVA);
        assertTrue(blocked(w));
    }
}
