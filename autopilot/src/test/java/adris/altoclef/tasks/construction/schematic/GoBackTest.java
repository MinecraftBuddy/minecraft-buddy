package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.tasks.construction.schematic.GoBack.Step;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoBackTest {

    @Test
    public void rangeFollowsTheViewDistance() {
        assertEquals(16, GoBack.range(2));
        assertEquals(16, GoBack.range(1));
        // a tiny render distance still walks to one chunk out, not onto the build
        assertEquals(16, GoBack.range(0));
        assertEquals(32, GoBack.range(3));
        assertEquals(32, GoBack.range(12));
        assertEquals(32, GoBack.range(32));
    }

    @Test
    public void insideTheFootprintIsZero() {
        assertEquals(0, GoBack.distance(5.5, 5.5, 0, 0, 9, 9), 1e-9);
        // the far edge of the last block is still touching
        assertEquals(0, GoBack.distance(10, 10, 0, 0, 9, 9), 1e-9);
    }

    @Test
    public void distanceIsToTheNearestColumn() {
        assertEquals(10, GoBack.distance(-10, 5, 0, 0, 9, 9), 1e-9);
        // off a corner it's the diagonal
        assertEquals(5, GoBack.distance(13, 14, 0, 0, 9, 9), 1e-9);
    }

    @Test
    public void standingInsideNeverTravels() {
        GoBack g = new GoBack();
        assertEquals(Step.THERE, g.step(0, 16, true, false));
    }

    @Test
    public void justInsideTheRangeNeverTravels() {
        GoBack g = new GoBack();
        assertEquals(Step.THERE, g.step(32, 32, true, false));
    }

    @Test
    public void walksUntilHalfTheRange() {
        GoBack g = new GoBack();
        assertEquals(Step.TRAVEL, g.step(200, 32, true, false));
        assertEquals(Step.TRAVEL, g.step(100, 32, true, false));
        // inside the leave line but not at the arrive line: keep going
        assertEquals(Step.TRAVEL, g.step(30, 32, true, false));
        assertEquals(Step.TRAVEL, g.step(17, 32, true, false));
        assertEquals(Step.THERE, g.step(16, 32, true, false));
        // and the next ask from the same spot doesn't start another walk
        assertEquals(Step.THERE, g.step(16, 32, true, false));
    }

    @Test
    public void waitsForChunksThenGoesAnyway() {
        GoBack g = new GoBack();
        for (int i = 0; i < GoBack.MAX_WAIT_TICKS; i++) {
            assertEquals(Step.WAIT, g.step(5, 32, false, false));
        }
        assertEquals(Step.THERE, g.step(5, 32, false, false));
    }

    @Test
    public void chunksShowingUpEndTheWait() {
        GoBack g = new GoBack();
        assertEquals(Step.WAIT, g.step(5, 32, false, false));
        assertEquals(Step.THERE, g.step(5, 32, true, false));
    }

    @Test
    public void noProgressBails() {
        GoBack g = new GoBack();
        assertEquals(Step.TRAVEL, g.step(300, 32, true, false));
        for (int i = 0; i < GoBack.MAX_STUCK_TICKS; i++) {
            // swaying half a block back and forth isn't getting anywhere
            assertEquals(Step.TRAVEL, g.step(300 - (i % 2) * 0.5, 32, true, false));
        }
        assertEquals(Step.BAIL, g.step(300, 32, true, false));
    }

    @Test
    public void progressStartsTheClockOver() {
        GoBack g = new GoBack();
        g.step(300, 32, true, false);
        for (int i = 0; i < GoBack.MAX_STUCK_TICKS; i++) {
            g.step(300, 32, true, false);
        }
        assertEquals(Step.TRAVEL, g.step(290, 32, true, false));
        for (int i = 0; i < GoBack.MAX_STUCK_TICKS; i++) {
            assertEquals(Step.TRAVEL, g.step(290, 32, true, false));
        }
        assertEquals(Step.BAIL, g.step(290, 32, true, false));
    }

    @Test
    public void aDeathHalfwayStartsTheWalkOver() {
        GoBack g = new GoBack();
        g.step(300, 32, true, false);
        assertEquals(Step.TRAVEL, g.step(40, 32, true, false));
        // respawned at the bed, a long way back. that's not the walk being stuck, but it isn't closer either
        assertEquals(Step.TRAVEL, g.step(1000, 32, true, false));
        assertFalse(g.gotCloser());
        for (int i = 0; i < GoBack.MAX_STUCK_TICKS; i++) {
            assertEquals(Step.TRAVEL, g.step(1000, 32, true, false));
        }
        // the stuck clock still works from the new start
        assertEquals(Step.BAIL, g.step(1000, 32, true, false));
    }

    @Test
    public void swingingBackAndForthStillRunsOut() {
        GoBack g = new GoBack();
        g.step(100, 32, true, false);
        // 40 blocks out and back, forever. only the first few swings count as getting thrown back
        int ticks = 0;
        Step last = Step.TRAVEL;
        while (last == Step.TRAVEL && ticks < 20 * GoBack.MAX_STUCK_TICKS) {
            last = g.step(ticks % 2 == 0 ? 140 : 100, 32, true, false);
            ticks++;
        }
        assertEquals(Step.BAIL, last);
    }

    @Test
    public void aWalkThatEndsShortBails() {
        GoBack g = new GoBack();
        g.step(300, 32, true, false);
        assertEquals(Step.BAIL, g.step(120, 32, true, true));
    }

    @Test
    public void aWalkThatEndsThereIsFine() {
        GoBack g = new GoBack();
        g.step(300, 32, true, false);
        assertEquals(Step.THERE, g.step(10, 32, true, true));
    }

    @Test
    public void gotCloserOnlyWhileWalkingAndOnANewBest() {
        GoBack g = new GoBack();
        g.step(300, 32, true, false);
        g.step(250, 32, true, false);
        assertTrue(g.gotCloser());
        g.step(250, 32, true, false);
        assertFalse(g.gotCloser());
        g.step(10, 32, true, false);
        assertFalse(g.gotCloser());
    }
}
