package adris.altoclef.tasks.construction;

import adris.altoclef.tasks.construction.FromSideRules.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

// the dig that can't get beside its block has to let go of the keys and then give up, not crouch against a wall for a minute
public class FromSideRulesTest {

    private static Verdict decide(double idle, double total, boolean pathing, boolean failed, boolean cracking, int backed) {
        return FromSideRules.decide(idle, total, pathing, failed, cracking, backed);
    }

    @Test
    public void aFreshTryJustKeepsGoing() {
        assertEquals(Verdict.KEEP_TRYING, decide(0, 0, false, false, false, 0));
        assertEquals(Verdict.KEEP_TRYING, decide(3, 3, true, false, false, 4));
    }

    @Test
    public void aFailedSearchLetsGoOfTheKeys() {
        assertEquals(Verdict.RELEASE_KEYS, decide(1, 1, false, true, false, 0));
    }

    @Test
    public void aLivePathNeverReleasesTheKeysByItself() {
        // the walk handles its own inputs, and the task releases them separately while pathing
        assertEquals(Verdict.KEEP_TRYING, decide(1, 1, true, true, false, 99));
    }

    @Test
    public void theBackUpIsAboutTenTicksAndNoMore() {
        assertEquals(Verdict.KEEP_TRYING, decide(1, 1, false, false, false, FromSideRules.BACK_UP_MAX_TICKS - 1));
        assertEquals(Verdict.RELEASE_KEYS, decide(1, 1, false, false, false, FromSideRules.BACK_UP_MAX_TICKS));
    }

    @Test
    public void tenIdleSecondsAndItGivesUp() {
        assertEquals(Verdict.RELEASE_KEYS, decide(FromSideRules.IDLE_GIVE_UP_SECONDS, 10, false, true, false, 0));
        assertEquals(Verdict.GIVE_UP, decide(FromSideRules.IDLE_GIVE_UP_SECONDS + 0.05, 10.05, false, true, false, 0));
    }

    @Test
    public void aLongWalkIsFineButNotAnEndlessOne() {
        // the idle clock only runs without a path, the total one is the cap on replanning forever
        assertEquals(Verdict.KEEP_TRYING, decide(0, 30, true, false, false, 0));
        assertEquals(Verdict.GIVE_UP, decide(0, FromSideRules.TOTAL_GIVE_UP_SECONDS + 0.05, true, false, false, 0));
    }

    @Test
    public void aCrackInProgressIsNeverGivenUpOn() {
        assertEquals(Verdict.KEEP_TRYING, decide(60, 90, false, true, true, 50));
    }
}
