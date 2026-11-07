package adris.altoclef.util.helpers;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SnowballRunTest {

    @Test
    public void beginsOnceWithSnowballsAndFewerThanTwoRods() {
        SnowballRun run = new SnowballRun();
        assertTrue(run.active(64, 0));
        assertEquals("strategy snowball: begun, 64 snowballs (rods 0, range 24.0)", run.start(64, 0, 1000, 24));
        assertNull(run.start(60, 0, 1010, 24));
        assertTrue(run.begun());
        assertEquals("strategy snowball: first throw 2 seconds after begun, at a blaze 12.0 off, flight 9 ticks", run.thrown(1040, 12, 8.6));
        assertNull(run.thrown(1050, 12, 8.6));
        assertEquals(2, run.throwsAll());
    }

    @Test
    public void overAtTwoRodsAndStaysOverWhenTheBrewTakesThem() {
        SnowballRun run = new SnowballRun();
        run.start(64, 0, 0, 24);
        run.thrown(20, 10, 7);
        run.kill();
        List<String> lines = run.ends(50, 2, 2000);
        assertEquals(List.of("strategy snowball: two rods after 1 throws in 100 seconds (1 kills by snowball, 50 snowballs left)",
                "strategy snowball: back to melee, two rods"), lines);
        assertTrue(run.over());
        assertTrue(run.ends(50, 2, 2010).isEmpty());
        // the stand and the powder took the rods: still the sword's, no new start in this order
        assertFalse(run.active(50, 0));
        assertNull(run.start(50, 0, 3000, 24));
    }

    @Test
    public void theNextOrderBeginsAnew() {
        // one order got its two rods; the rods task of the next order in the same game has a run of its own
        SnowballRun first = new SnowballRun();
        first.start(64, 0, 0, 24);
        first.ends(40, 2, 4000);
        assertTrue(first.over());
        SnowballRun next = new SnowballRun();
        assertTrue(next.active(64, 0));
        assertEquals("strategy snowball: begun, 64 snowballs (rods 0, range 24.0)", next.start(64, 0, 9000, 24));
        assertFalse(next.over());
    }

    @Test
    public void outOfSnowballsOnlyAfterTenTicksWithNone() {
        SnowballRun run = new SnowballRun();
        run.start(16, 1, 0, 24);
        for (int i = 1; i < SnowballRun.OUT_TICKS; i++) assertTrue(run.ends(0, 1, i).isEmpty());
        // a slot being moved read as none: the count starts over
        assertTrue(run.ends(3, 1, 20).isEmpty());
        for (int i = 1; i < SnowballRun.OUT_TICKS; i++) assertTrue(run.ends(0, 1, 20 + i).isEmpty());
        List<String> lines = run.ends(0, 1, 20 + SnowballRun.OUT_TICKS);
        assertEquals(2, lines.size());
        assertEquals("strategy snowball: back to melee, out of snowballs", lines.get(1));
        assertTrue(lines.get(0).startsWith("strategy snowball: out of snowballs after 0 throws in 1 seconds, rods 1"));
        assertTrue(run.over());
        assertFalse(run.active(16, 1));
    }

    @Test
    public void noSnowballsSaysSoOnceAndNeverBegins() {
        SnowballRun run = new SnowballRun();
        assertEquals("strategy snowball: not begun, no snowballs in the bag", run.start(0, 0, 0, 24));
        assertNull(run.start(0, 0, 10, 24));
        assertFalse(run.begun());
        assertTrue(run.ends(0, 0, 400).isEmpty());
        // two rods already: nothing to begin
        assertNull(new SnowballRun().start(64, 2, 0, 24));
    }
}
