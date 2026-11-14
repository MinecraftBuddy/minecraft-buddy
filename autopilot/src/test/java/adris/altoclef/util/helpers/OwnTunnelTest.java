package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning mine_own_keep, mine_no_path_tunnel) the tunnel of its own kept over a mine made anew, and baritone's mine's stops for
// no path counted by place
public class OwnTunnelTest {

    @Test
    public void bothKeysOnByDefault() {
        assertEquals(1.0, Tuning.DEFAULTS.get("mine_own_keep"), 0);
        assertEquals(1.0, Tuning.DEFAULTS.get("mine_no_path_tunnel"), 0);
    }

    @Test
    public void aMineMadeAnewNearTheSpotTakesTheTunnelOn() {
        OwnTunnel.State s = new OwnTunnel.State();
        // the iron mine's tunnel of its own begun at 41233, 2438 (out of the band at y 25), at y 16, heading north, last step
        // at 40000
        s.keep("OVERWORLD", 16, 41233, 16, 2438, 0, -1, 40000);
        // the mine made anew after a pickaxe craft, 38 blocks off at 41216, 2404, a hundred ticks later
        OwnTunnel.Kept k = s.near("OVERWORLD", 16, 41216, 16, 2404, 40100, 48);
        assertNotNull(k);
        assertEquals(41233, k.x());
        assertEquals(2438, k.z());
        assertEquals(16, k.y());
        assertEquals(0, k.dx());
        assertEquals(-1, k.dz());
        // a mine that starts a little under the height (y 15) takes it on too
        assertNotNull(s.near("OVERWORLD", 16, 41216, 15, 2404, 40100, 48));
    }

    @Test
    public void noTunnelFarOffOtherHeightOtherOreOldOrElsewhere() {
        OwnTunnel.State s = new OwnTunnel.State();
        s.keep("OVERWORLD", 16, 100, 16, 200, 1, 0, 40000);
        // 50 blocks off, past the tunnel's reach
        assertNull(s.near("OVERWORLD", 16, 150, 16, 200, 40100, 48));
        // a mine at y 8 (more than three under the tunnel)
        assertNull(s.near("OVERWORLD", 16, 100, 8, 200, 40100, 48));
        // a mine for another height (copper's 48) at the spot
        assertNull(s.near("OVERWORLD", 48, 100, 16, 200, 40100, 48));
        // 31 game minutes after the last step, or another world's earlier clock
        assertNull(s.near("OVERWORLD", 16, 100, 16, 200, 40000 + 37200, 48));
        assertNull(s.near("OVERWORLD", 16, 100, 16, 200, 30000, 48));
        // the nether
        assertNull(s.near("NETHER", 16, 100, 16, 200, 40100, 48));
    }

    @Test
    public void aStepKeepsItFreshAndTheReachDropsIt() {
        OwnTunnel.State s = new OwnTunnel.State();
        s.keep("OVERWORLD", 16, 100, 16, 200, 1, 0, 0);
        // a step 29 game minutes on, turned a quarter
        s.keep("OVERWORLD", 16, 100, 16, 200, 0, 1, 34800);
        OwnTunnel.Kept k = s.near("OVERWORLD", 16, 110, 16, 200, 70000, 48);
        assertNotNull(k);
        assertEquals(1, k.dz());
        s.drop();
        assertNull(s.near("OVERWORLD", 16, 110, 16, 200, 70000, 48));
    }

    @Test
    public void theBandExitsCountOverMinesAndPerDimension() {
        OwnTunnel.State s = new OwnTunnel.State();
        // one mine's exit, then a new mine's within ten blocks: the second makes two
        assertFalse(s.bandExits("OVERWORLD").add(41233, 2438, 1000));
        assertTrue(s.bandExits("OVERWORLD").add(41236, 2440, 6000));
        s.bandExits("OVERWORLD").clear();
        assertFalse(s.bandExits("OVERWORLD").add(41233, 2438, 7000));
        // another dimension forgets them, and the tunnel too
        s.keep("OVERWORLD", 16, 41233, 16, 2438, 1, 0, 7000);
        assertFalse(s.bandExits("NETHER").add(41233, 2438, 7100));
        assertEquals(1, s.bandExits("NETHER").last());
        assertNull(s.near("OVERWORLD", 16, 41233, 16, 2438, 7100, 48));
    }

    @Test
    public void twoStopsForNoPathNearAndSoonMakeAPair() {
        OwnTunnel.State s = new OwnTunnel.State();
        // the run's first two: at the old tunnel's south end, then 47 s later at its north end, 86 blocks off
        assertNull(s.noPathStop("OVERWORLD", 41235, 2477, 1000));
        assertArrayEquals(new int[]{41235, 2477}, s.noPathStop("OVERWORLD", 41213, 2394, 1940));
        // the count starts again: the next stop is a first one
        assertNull(s.noPathStop("OVERWORLD", 41235, 2479, 2500));
        assertNotNull(s.noPathStop("OVERWORLD", 41213, 2394, 3000));
    }

    @Test
    public void noPairFarOffLateOrElsewhere() {
        OwnTunnel.State s = new OwnTunnel.State();
        assertNull(s.noPathStop("OVERWORLD", 0, 0, 1000));
        // 120 blocks off: no pair, and this one is the first of the next
        assertNull(s.noPathStop("OVERWORLD", 120, 0, 1500));
        // eleven game minutes after it: no pair
        assertNull(s.noPathStop("OVERWORLD", 120, 10, 1500 + 13200));
        // in the nether: no pair with the overworld's
        assertNull(s.noPathStop("NETHER", 120, 10, 14800));
        assertNotNull(s.noPathStop("NETHER", 125, 10, 15000));
    }

    @Test
    public void aSecondSwitchNearAnEarlierOneIsTheGiveUp() {
        OwnTunnel.State s = new OwnTunnel.State();
        assertFalse(s.switchedNear("OVERWORLD", 41213, 2394, 2000));
        s.switched("OVERWORLD", 41213, 2394, 2000);
        // 60 blocks on, ten game minutes later
        assertTrue(s.switchedNear("OVERWORLD", 41273, 2394, 14000));
        // 110 blocks off, or 31 game minutes later, or spent
        assertFalse(s.switchedNear("OVERWORLD", 41323, 2394, 14000));
        assertFalse(s.switchedNear("OVERWORLD", 41213, 2394, 2000 + 37200));
        s.switchSpent();
        assertFalse(s.switchedNear("OVERWORLD", 41213, 2394, 3000));
    }

    @Test
    public void theTunnelTurnsAQuarterFromTheWayBetweenTheStops() {
        // north along z (2477 to 2394): east (dx = -dz, dz = dx of north's 0, -1)
        assertArrayEquals(new int[]{1, 0}, OwnTunnel.heading(41235, 2477, 41213, 2394, 0, 1));
        // south: west
        assertArrayEquals(new int[]{-1, 0}, OwnTunnel.heading(41213, 2394, 41235, 2477, 0, 1));
        // east along x: south
        assertArrayEquals(new int[]{0, 1}, OwnTunnel.heading(0, 0, 80, 10, 0, 1));
        // the same spot: a quarter from the mine's heading (east to south), or from east with none
        assertArrayEquals(new int[]{0, 1}, OwnTunnel.heading(5, 5, 6, 5, 1, 0));
        assertArrayEquals(new int[]{0, 1}, OwnTunnel.heading(5, 5, 5, 5, 0, 0));
    }

    @Test
    public void aFreshRunForgetsItAll() {
        OwnTunnel.State s = new OwnTunnel.State();
        s.keep("OVERWORLD", 16, 0, 16, 0, 1, 0, 100);
        s.noPathStop("OVERWORLD", 0, 0, 100);
        s.switched("OVERWORLD", 0, 0, 100);
        assertEquals(3, s.size());
        s.clear();
        assertEquals(0, s.size());
        assertNull(s.noPathStop("OVERWORLD", 1, 1, 200));
    }
}
