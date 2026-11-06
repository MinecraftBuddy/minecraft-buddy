package adris.altoclef.tasks.speedrun.gamer.end;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the strat pick and the dead latch, both pure
public class DragonLogicTest {
    // a bed on the pillar counts in bedsLeft, so the last one placed is not the sword yet
    @Test
    public void bedsWhileAnyIsLeftThenTheSword() {
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(9));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(1));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(0));
    }

    @Test
    public void exitPortalMeansDeadEvenIfWeNeverSawTheDragon() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertTrue(latch.update(true, false, true, 0));
        assertTrue(latch.isDead());
    }

    @Test
    public void aDragonThatWasNeverSeenIsNotDeadJustBecauseItIsMissing() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertFalse(latch.update(false, false, true, 0));
        assertFalse(latch.update(false, false, true, 100));
        assertFalse(latch.hasSeenDragon());
    }

    @Test
    public void goneForFiveSecondsWithTheCenterLoadedIsDead() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertFalse(latch.update(false, true, true, 0));
        assertTrue(latch.hasSeenDragon());
        assertFalse(latch.update(false, false, true, 1));
        assertFalse(latch.update(false, false, true, 5.9));
        assertTrue(latch.update(false, false, true, 6));
        assertTrue(latch.isDead());
        // soft: nothing hard happened, so this one is NOT what RunState may save
        assertFalse(latch.exitPortalSeen());
    }

    @Test
    public void theSoftGuessIsUndoneWhenTheDragonShowsUpAgain() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertFalse(latch.update(false, false, true, 1));
        assertTrue(latch.update(false, false, true, 6));
        // out of tracking range for a while, back in range: still alive
        assertFalse(latch.update(false, true, true, 7));
        assertFalse(latch.isDead());
    }

    @Test
    public void theExitPortalIsHardAndStaysEvenWhenTheDragonIsSeen() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertTrue(latch.update(true, true, true, 1));
        assertTrue(latch.exitPortalSeen());
        assertTrue(latch.update(false, true, true, 2));
    }

    @Test
    public void softGuessWithAnUnloadedChunkClearsInsteadOfStaying() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        latch.update(false, false, true, 1);
        assertTrue(latch.update(false, false, true, 6));
        assertFalse(latch.update(false, false, false, 7));
    }

    @Test
    public void anUnloadedCenterChunkResetsTheTimer() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertFalse(latch.update(false, false, true, 1));
        // render distance blip: the dragon is not "gone", we just cannot see it
        assertFalse(latch.update(false, false, false, 4));
        assertFalse(latch.update(false, false, true, 5));
        assertFalse(latch.update(false, false, true, 9.9));
        assertTrue(latch.update(false, false, true, 10));
    }

    @Test
    public void seeingTheDragonAgainRestartsTheTimer() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        latch.update(false, false, true, 1);
        latch.update(false, true, true, 4);
        assertFalse(latch.update(false, false, true, 6));
        assertFalse(latch.update(false, false, true, 10.9));
        assertTrue(latch.update(false, false, true, 11));
    }

    @Test
    public void resetAndMarkDead() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.markDead();
        assertTrue(latch.update(false, false, false, 0));
        latch.reset();
        assertFalse(latch.isDead());
        assertFalse(latch.hasSeenDragon());
    }
}
