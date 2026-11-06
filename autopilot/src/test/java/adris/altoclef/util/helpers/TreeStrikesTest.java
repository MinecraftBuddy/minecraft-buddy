package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TreeStrikesTest {
    private static final BlockPos A = new BlockPos(100, 72, 40);
    private static final Dimension OW = Dimension.OVERWORLD;

    @Test
    public void theSecondStallOnATreeEndsIt() {
        TreeStrikes s = new TreeStrikes();
        assertTrue(s.strike(OW, A, 0).isEmpty());
        // a different log, a canopy over
        List<BlockPos> out = s.strike(OW, A.east(3).above(), 130);
        assertEquals(List.of(A, A.east(3).above()), out);
    }

    @Test
    public void theSameLogTwiceIsTwoStrikes() {
        TreeStrikes s = new TreeStrikes();
        assertTrue(s.strike(OW, A, 0).isEmpty());
        assertEquals(List.of(A), s.strike(OW, A, 130));
    }

    @Test
    public void anotherTreeStartsItsOwnCount() {
        TreeStrikes s = new TreeStrikes();
        assertTrue(s.strike(OW, A, 0).isEmpty());
        BlockPos far = A.east(TreeStrikes.RADIUS + 1);
        assertTrue(s.strike(OW, far, 130).isEmpty());
        assertEquals(2, s.open());
        assertFalse(TreeStrikes.sameTree(A, far));
    }

    @Test
    public void aTreeIsTallAsWellAsWide() {
        assertTrue(TreeStrikes.sameTree(A, A.above(TreeStrikes.HEIGHT).west(TreeStrikes.RADIUS)));
        assertFalse(TreeStrikes.sameTree(A, A.above(TreeStrikes.HEIGHT + 1)));
        assertFalse(TreeStrikes.sameTree(A, A.north(TreeStrikes.RADIUS + 1)));
    }

    @Test
    public void aCountThatSatLongEnoughStartsOver() {
        TreeStrikes s = new TreeStrikes();
        assertTrue(s.strike(OW, A, 0).isEmpty());
        assertTrue(s.strike(OW, A, TreeStrikes.DECAY).isEmpty());
        // and that one counts again from there
        assertFalse(s.strike(OW, A, TreeStrikes.DECAY + 20).isEmpty());
    }

    @Test
    public void aTreeThatEndedStartsFreshAfterwards() {
        TreeStrikes s = new TreeStrikes();
        s.strike(OW, A, 0);
        assertFalse(s.strike(OW, A, 10).isEmpty());
        assertEquals(0, s.open());
        // the logs are banned by now, but if the ban ran out and we came back it is two more tries and not one
        assertTrue(s.strike(OW, A, 7000).isEmpty());
    }

    @Test
    public void theNetherDoesNotShareATreeWithTheOverworld() {
        TreeStrikes s = new TreeStrikes();
        s.strike(OW, A, 0);
        assertTrue(s.strike(Dimension.NETHER, A, 10).isEmpty());
    }

    @Test
    public void aClockAFewTicksBackKeepsTheCount() {
        TreeStrikes s = new TreeStrikes();
        s.strike(OW, A, 5000);
        assertFalse(s.strike(OW, A, 4998).isEmpty());
    }

    @Test
    public void aClockThatWentBackwardsForgetsTheOldCount() {
        // a new world restarts game time, the old strikes mean nothing there
        TreeStrikes s = new TreeStrikes();
        s.strike(OW, A, 5000);
        assertTrue(s.strike(OW, A, 20).isEmpty());
    }
}
