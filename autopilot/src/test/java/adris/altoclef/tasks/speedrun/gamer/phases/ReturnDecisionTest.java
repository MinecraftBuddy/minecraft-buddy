package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.ReturnPhase.Step;
import baritone.api.utils.Dimension;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the way home: portal known / far from it / dark frame / nothing left
public class ReturnDecisionTest {
    // portalKnown, haveHome, nearHome, frameStanding, haveLighter, obsidian
    @Test
    public void aKnownPortalIsTheDefaultTasksJob() {
        assertEquals(Step.DEFAULT, ReturnPhase.decide(true, true, false, false, false, 0));
        assertEquals(Step.DEFAULT, ReturnPhase.decide(true, false, false, false, false, 0));
    }

    @Test
    public void unknownToTheGameButRememberedMeansWalkThere() {
        assertEquals(Step.WALK_HOME, ReturnPhase.decide(false, true, false, false, true, 0));
    }

    @Test
    public void darkPortalWithItsFrameGetsRelit() {
        assertEquals(Step.RELIGHT, ReturnPhase.decide(false, true, true, true, true, 0));
    }

    @Test
    public void darkPortalWithoutALighterBuildsOnlyWithObsidianInHand() {
        assertEquals(Step.DEFAULT, ReturnPhase.decide(false, true, true, true, false, 10));
        assertEquals(Step.FAIL, ReturnPhase.decide(false, true, true, true, false, 9));
    }

    @Test
    public void noFrameAndNoObsidianFailsAtOnceInsteadOfRecursingIntoTheCatalogue() {
        assertEquals(Step.FAIL, ReturnPhase.decide(false, true, true, false, true, 3));
        assertEquals(Step.DEFAULT, ReturnPhase.decide(false, true, true, false, true, 12));
    }

    @Test
    public void nothingRememberedAndNothingToBuildWithFails() {
        assertEquals(Step.FAIL, ReturnPhase.decide(false, false, false, false, true, 0));
        assertEquals(Step.DEFAULT, ReturnPhase.decide(false, false, false, false, true, 10));
    }

    @Test
    public void returnIsDoneInTheOverworldOffThePortal() {
        ReturnPhase phase = new ReturnPhase();
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.NETHER;
        assertFalse(phase.isDone(f, new RunState(), new GamerConfig()));
        f.dimension = Dimension.OVERWORLD;
        assertTrue(phase.isDone(f, new RunState(), new GamerConfig()));
    }
}
