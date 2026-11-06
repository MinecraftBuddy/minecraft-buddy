package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoneTunnelTest {

    @Test
    public void startsWithNoOpinion() {
        StoneTunnel t = new StoneTunnel();
        assertFalse(t.latched());
        // nothing latched, nothing to leave
        assertFalse(t.left(1000, 1000, Dimension.NETHER));
    }

    @Test
    public void staysLatchedAlongTheTunnel() {
        StoneTunnel t = new StoneTunnel();
        t.latch(52, 0, 0, Dimension.OVERWORLD);
        assertTrue(t.latched());
        assertEquals(52, t.floor());
        assertFalse(t.left(10, 0, Dimension.OVERWORLD));
        assertFalse(t.left(StoneTunnel.MAX_WANDER, 0, Dimension.OVERWORLD));
    }

    @Test
    public void walkingOffOrAPortalEndsIt() {
        StoneTunnel t = new StoneTunnel();
        t.latch(52, 0, 0, Dimension.OVERWORLD);
        assertTrue(t.left(StoneTunnel.MAX_WANDER + 1, 0, Dimension.OVERWORLD));
        assertTrue(t.left(18, 18, Dimension.OVERWORLD));
        assertTrue(t.left(0, 0, Dimension.NETHER));
        t.clear();
        assertFalse(t.latched());
    }

    @Test
    public void aRoomKeepsItsFloorAcrossAnUnlatch() {
        StoneTunnel t = new StoneTunnel();
        t.latch(52, 0, 0, Dimension.OVERWORLD);
        t.unlatch();
        assertFalse(t.latched());
        // unlatched means left() has nothing to say
        assertFalse(t.left(100, 0, Dimension.OVERWORLD));
        // standing on the floor of the room we dug: same floor, not two under it
        assertEquals(52, t.reuse(3, 2, 52, Dimension.OVERWORLD).getAsInt());
        // back up on the surface after a craft: the same band again
        assertEquals(52, t.reuse(3, 2, 66, Dimension.OVERWORLD).getAsInt());
    }

    @Test
    public void reuseOnlyNearbyAtOrOverTheFloorAndInTheSameWorld() {
        StoneTunnel t = new StoneTunnel();
        assertTrue(t.reuse(0, 0, 64, Dimension.OVERWORLD).isEmpty());
        t.latch(52, 0, 0, Dimension.OVERWORLD);
        // under it (a cave took us down) the old floor is above our head, work a new one out
        assertTrue(t.reuse(0, 0, 51, Dimension.OVERWORLD).isEmpty());
        assertTrue(t.reuse(StoneTunnel.MAX_WANDER + 1, 0, 60, Dimension.OVERWORLD).isEmpty());
        assertTrue(t.reuse(0, 0, 60, Dimension.NETHER).isEmpty());
        t.clear();
        assertTrue(t.reuse(0, 0, 60, Dimension.OVERWORLD).isEmpty());
    }
}
