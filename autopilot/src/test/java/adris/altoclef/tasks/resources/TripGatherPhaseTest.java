package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// ten blocks for a path is ten dirt, not a tree, a pickaxe and a hole in a hill
public class TripGatherPhaseTest {

    @Test
    public void noPickaxeAndDirtAroundMeansDirtByHand() {
        assertFalse(TripGatherTask.wide(false, false, true, false));
    }

    @Test
    public void noPickaxeAndNoDirtLeftMeansTheTreeAndTheStone() {
        assertTrue(TripGatherTask.wide(false, false, true, true));
        // netherrack only, nothing comes out by hand
        assertTrue(TripGatherTask.wide(false, false, false, false));
    }

    @Test
    public void aPickaxeMeansWhateverIsNearest() {
        assertTrue(TripGatherTask.wide(false, true, true, false));
    }

    @Test
    public void onceWideItStaysWide() {
        // the pickaxe broke and dirt showed up again, still not going back
        assertTrue(TripGatherTask.wide(true, false, true, false));
    }
}
