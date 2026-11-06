package adris.altoclef.tasks.movement;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MLGLandingPickTest {
    @BeforeClass
    public static void bootstrap() {
        // the task class has static registry lookups
        ItemFixture.boot();
    }

    // args: lava, isDeadlyFall, hasClutchItem, closestSoFar, highestSoFar, bestIsLava, bestIsDeadlyFall

    @Test
    public void aSurvivableLedgeReplacesADeadlyFirstPickWithNoClutchItem() {
        assertTrue(MLGBucketTask.replacesBest(false, false, false, false, false, false, true));
    }

    @Test
    public void aDeadlyCandidateNeverReplacesAnything() {
        assertFalse(MLGBucketTask.replacesBest(false, true, false, true, true, true, true));
    }

    @Test
    public void lavaCandidatesGoThroughTheirOwnRule() {
        assertFalse(MLGBucketTask.replacesBest(true, false, false, true, true, false, true));
    }

    @Test
    public void aSurvivableLedgeStillReplacesLava() {
        assertTrue(MLGBucketTask.replacesBest(false, false, false, false, false, true, false));
    }

    @Test
    public void noClutchItemAndASurvivableBestKeepsTheBest() {
        assertFalse(MLGBucketTask.replacesBest(false, false, false, true, true, false, false));
    }

    @Test
    public void withAClutchItemItIsHigherAndCloserOnly() {
        assertTrue(MLGBucketTask.replacesBest(false, false, true, true, true, false, false));
        assertFalse(MLGBucketTask.replacesBest(false, false, true, true, false, false, false));
        assertFalse(MLGBucketTask.replacesBest(false, false, true, false, true, false, false));
    }
}
