package adris.altoclef.util.helpers;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning pickless_way_out) the way the buddy came down, for the walk back up with no pickaxe above wood (test heel: no pickaxe at
// y 10 to 26 in a dripstone cave, every climb by hand "no way up")
public class DownTrailTest {
    private static final String OW = "minecraft:overworld";

    @Test
    public void theTrailRunsFromTheLastOpenCellDown() {
        DownTrail t = new DownTrail();
        t.note(OW, 0, 70, 0, true);
        // a staircase down under a roof, a cell kept each three blocks
        for (int i = 1; i <= 12; i++) t.note(OW, i, 70 - i, 0, false);
        List<int[]> back = t.wayBack();
        assertArrayEquals(new int[]{0, 70, 0}, back.get(back.size() - 1));
        assertArrayEquals(new int[]{12, 58, 0}, back.get(0));
        assertTrue(back.size() >= 5 && back.size() <= 7);
        assertTrue(t.fromOpen());
        // in the open again: the trail starts over there
        t.note(OW, 30, 64, 0, true);
        assertEquals(1, t.size());
    }

    @Test
    public void aLoopIsCutOut() {
        DownTrail t = new DownTrail();
        t.note(OW, 0, 64, 0, true);
        t.note(OW, 0, 60, 4, false);
        t.note(OW, 0, 56, 8, false);
        // a tunnel east and back
        for (int x = 1; x <= 30; x++) t.note(OW, x, 56, 8, false);
        for (int x = 29; x >= 0; x--) t.note(OW, x, 56, 8, false);
        assertEquals(3, t.size());
        assertArrayEquals(new int[]{0, 56, 8}, t.wayBack().get(0));
    }

    @Test
    public void noTrailWithoutAnOpenStartOrAfterAJump() {
        DownTrail t = new DownTrail();
        // into the world under a roof
        t.note(OW, 0, 20, 0, false);
        t.note(OW, 5, 20, 0, false);
        assertEquals(0, t.size());
        t.note(OW, 0, 64, 0, true);
        t.note(OW, 0, 60, 3, false);
        // a death: the feet 200 blocks off
        t.note(OW, 200, 64, 0, false);
        assertEquals(0, t.size());
        assertFalse(t.fromOpen());
        // another dimension starts over
        t.note(OW, 0, 64, 0, true);
        t.note("minecraft:the_nether", 0, 64, 0, false);
        assertEquals(0, t.size());
    }

    // (pickless_way_out, food_climb_trail) the way out both climbs take: none with the key off, a pickaxe above wood, no open
    // start or a trail of one cell
    @Test
    public void theWayOutNeedsTheKeyNoPickaxeAndAnOpenStart() {
        DownTrail t = new DownTrail();
        t.note(OW, 0, 70, 0, true);
        assertNull(DownTrail.wayOut(true, false, t));
        for (int i = 1; i <= 12; i++) t.note(OW, i, 70 - i, 0, false);
        List<int[]> way = DownTrail.wayOut(true, false, t);
        assertArrayEquals(new int[]{0, 70, 0}, way.get(way.size() - 1));
        assertNull(DownTrail.wayOut(false, false, t));
        assertNull(DownTrail.wayOut(true, true, t));
        DownTrail roof = new DownTrail();
        roof.note(OW, 0, 20, 0, false);
        roof.note(OW, 5, 20, 0, false);
        assertNull(DownTrail.wayOut(true, false, roof));
    }
}
