package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the pathfinder is left alone for a bit after a goal was handed to it and did not stick. a goal nothing can reach was
// re-sent on every tick, a full search each time, for as long as the run lasted
public class CommittedRunTaskTest {

    @Test
    public void theFirstIssueAlwaysGoesThrough() {
        // nothing was handed over yet
        assertTrue(CommittedRunTask.mayReissue(0, Long.MIN_VALUE / 2));
        assertTrue(CommittedRunTask.mayReissue(5000, Long.MIN_VALUE / 2));
    }

    @Test
    public void asksAreSpacedByTheGap() {
        assertEquals(40, CommittedRunTask.REISSUE_GAP_TICKS);
        long issued = 1000;
        assertFalse(CommittedRunTask.mayReissue(issued + 1, issued));
        assertFalse(CommittedRunTask.mayReissue(issued + 39, issued));
        assertTrue(CommittedRunTask.mayReissue(issued + 40, issued));
        assertTrue(CommittedRunTask.mayReissue(issued + 400, issued));
    }

    @Test
    public void aClockThatWentBackwardsIsLongEnoughAgo() {
        // a new world: the old issue time is nonsense
        assertTrue(CommittedRunTask.mayReissue(10, 5000));
    }

    @Test
    public void aRunThatStartsMidFallLetsTheFallFinish() {
        // in the air: the soft cancel, the fall movement keeps its bucket or clutch
        assertFalse(CommittedRunTask.mayCancelHard(false, false, false));
        // standing, swimming or on a ladder there is nothing to fall out of
        assertTrue(CommittedRunTask.mayCancelHard(true, false, false));
        assertTrue(CommittedRunTask.mayCancelHard(false, true, false));
        assertTrue(CommittedRunTask.mayCancelHard(false, false, true));
    }

    @Test
    public void onlyAfloatMeansSwimToTheBank() {
        assertTrue(CommittedRunTask.afloat(true, false));
        // wading with the bottom under us walks out by itself
        assertFalse(CommittedRunTask.afloat(true, true));
        assertFalse(CommittedRunTask.afloat(false, false));
        assertFalse(CommittedRunTask.afloat(false, true));
    }
}
