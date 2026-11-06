package adris.altoclef.tasks.construction;

import org.junit.Test;

import static adris.altoclef.tasks.construction.StationAttempt.Phase.AIM;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.CARVE;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.DONE;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.FALLBACK;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.PICK;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.RELOCATE;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.VERIFY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StationAttemptTest {

    private static void tickN(StationAttempt a, int n) {
        for (int i = 0; i < n; i++) {
            a.tick();
        }
    }

    // one whole try that ends with the block not showing up
    private static void loseATry(StationAttempt a) {
        a.picked();
        a.clicked();
        for (int i = 0; i < StationAttempt.VERIFY_TICKS && a.phase() == VERIFY; i++) {
            a.tick();
            a.verify(false);
        }
    }

    @Test
    public void aCleanPlacementIsDoneAfterTheBlockSettles() {
        StationAttempt a = new StationAttempt();
        assertEquals(PICK, a.phase());
        a.picked();
        assertEquals(AIM, a.phase());
        a.clicked();
        assertEquals(VERIFY, a.phase());
        for (int i = 0; i < StationAttempt.SETTLE_TICKS - 1; i++) {
            a.tick();
            a.verify(true);
            assertEquals(VERIFY, a.phase());
        }
        a.tick();
        a.verify(true);
        assertEquals(DONE, a.phase());
    }

    // the client puts the block there the moment we click, the server can take it back
    @Test
    public void aFlickeringBlockDoesNotCount() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.clicked();
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(false);
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(true);
        assertEquals(VERIFY, a.phase());
        a.tick();
        a.verify(true);
        assertEquals(DONE, a.phase());
    }

    @Test
    public void noBlockInTenTicksLosesTheTry() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.clicked();
        for (int i = 0; i < StationAttempt.VERIFY_TICKS - 1; i++) {
            a.tick();
            a.verify(false);
            assertEquals(VERIFY, a.phase());
        }
        a.tick();
        a.verify(false);
        assertEquals(PICK, a.phase());
        assertEquals(1, a.tries());
    }

    @Test
    public void aimingTimesOut() {
        StationAttempt a = new StationAttempt();
        a.picked();
        tickN(a, StationAttempt.AIM_TICKS - 1);
        assertFalse(a.aimTimedOut());
        a.tick();
        assertTrue(a.aimTimedOut());
        a.failed();
        assertEquals(PICK, a.phase());
        // and the clock restarts with the next spot
        a.picked();
        assertFalse(a.aimTimedOut());
    }

    // the old fail count lived on a task that was thrown away with the spot, so the 4th failure never came. this one lives here
    @Test
    public void failuresAddUpAcrossSpotsAndThirdOneRelocates() {
        StationAttempt a = new StationAttempt();
        loseATry(a);
        assertEquals(PICK, a.phase());
        loseATry(a);
        assertEquals(PICK, a.phase());
        assertEquals(2, a.tries());
        loseATry(a);
        assertEquals(RELOCATE, a.phase());
        assertEquals(1, a.relocations());
        assertEquals(0, a.tries());
    }

    // nothing usable here: the walk is next
    private static void nothingHere(StationAttempt a) {
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
    }

    // both walks used and nothing at either end. the dig is what is left
    private static void walkedTwiceAndStillNothing(StationAttempt a) {
        nothingHere(a);
        a.arrived();
        nothingHere(a);
        a.arrived();
        a.noSpot();
        assertEquals(CARVE, a.phase());
    }

    @Test
    public void nineEmptyGroundedPicksThenASpotNeverWalks() {
        StationAttempt a = new StationAttempt();
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
            assertEquals(PICK, a.phase());
        }
        a.picked();
        assertEquals(AIM, a.phase());
        assertEquals(0, a.relocations());
    }

    @Test
    public void tenEmptyGroundedPicksWalk() {
        StationAttempt a = new StationAttempt();
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
        }
        assertEquals(PICK, a.phase());
        a.noSpot(true);
        assertEquals(RELOCATE, a.phase());
        assertEquals(1, a.relocations());
    }

    // a tick in the air doesn't count, and it starts the wait over
    @Test
    public void airborneTicksDoNotCountAndResetTheWait() {
        StationAttempt a = new StationAttempt();
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS * 3; i++) {
            a.noSpot(false);
        }
        assertEquals(PICK, a.phase());
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
        }
        a.noSpot(false);
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
            assertEquals(PICK, a.phase());
        }
        a.noSpot(true);
        assertEquals(RELOCATE, a.phase());
    }

    // the wait is for the walk. once the walks are spent the dig doesn't hang about
    @Test
    public void theDigDoesNotWait() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.arrived();
        nothingHere(a);
        a.arrived();
        a.noSpot(true);
        assertEquals(CARVE, a.phase());
    }

    @Test
    public void aNewPickStartsTheWaitFresh() {
        StationAttempt a = new StationAttempt();
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
        }
        a.picked();
        a.failed();
        assertEquals(PICK, a.phase());
        assertEquals(0, a.noSpotTicks());
    }

    // a shaft, nothing usable around. the walk is free and a block mined for a station is not, so it comes first
    @Test
    public void noSpotWalksBeforeAnythingElse() {
        StationAttempt a = new StationAttempt();
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
        assertEquals(1, a.relocations());
        assertEquals(0, a.carves());
    }

    // with the walks used up, "no spot" digs before it gives up
    @Test
    public void noSpotOnTheLastPatchCarvesBeforeTheOldPlacer() {
        StationAttempt a = new StationAttempt();
        walkedTwiceAndStillNothing(a);
        a.noCarve();
        assertEquals(FALLBACK, a.phase());
        assertFalse(a.fallbackWhy().isEmpty());
    }

    @Test
    public void aDugHoleGetsALookBeforeAnythingElse() {
        StationAttempt a = new StationAttempt();
        walkedTwiceAndStillNothing(a);
        a.carveDone();
        assertEquals(PICK, a.phase());
        assertEquals(1, a.carves());
    }

    @Test
    public void holesThatDontHelpRunOutAndTheOldPlacerGetsIt() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.arrived();
        nothingHere(a);
        a.arrived();
        for (int i = 0; i < StationAttempt.MAX_CARVES; i++) {
            a.noSpot();
            assertEquals(CARVE, a.phase());
            a.carveDone();
        }
        a.noSpot();
        assertEquals(FALLBACK, a.phase());
        assertTrue(a.fallbackWhy(), a.fallbackWhy().contains(StationAttempt.MAX_CARVES + " holes"));
    }

    // a walk that found nowhere better to stand spends both of them: the next "no spot" would only find the same nothing
    @Test
    public void noStandpointGoesToDiggingAndStaysThere() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.noStandpoint();
        assertEquals(CARVE, a.phase());
        a.carveDone();
        a.noSpot();
        assertEquals(CARVE, a.phase());
    }

    @Test
    public void carveDoneOutsideTheCarvePhaseDoesNothing() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.carveDone();
        assertEquals(AIM, a.phase());
        assertEquals(0, a.carves());
    }

    @Test
    public void theFallbackSaysWhy() {
        StationAttempt a = new StationAttempt();
        assertEquals("", a.fallbackWhy());
        nothingHere(a);
        a.noStandpoint();
        a.noCarve();
        assertEquals(FALLBACK, a.phase());
        assertFalse(a.fallbackWhy().isEmpty());
    }

    @Test
    public void arrivingStartsAFreshPatch() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        assertEquals(RELOCATE, a.phase());
        a.arrived();
        assertEquals(PICK, a.phase());
        assertEquals(0, a.tries());
        // a late arrived() from somewhere else is not allowed to yank us out of a placement
        a.picked();
        a.arrived();
        assertEquals(AIM, a.phase());
    }

    @Test
    public void twoRelocationsThenTheDigThenTheOldPlacerGetsIt() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.arrived();
        nothingHere(a);
        assertEquals(2, a.relocations());
        a.arrived();
        a.noSpot();
        assertEquals(CARVE, a.phase());
        a.noCarve();
        assertEquals(FALLBACK, a.phase());
        assertFalse(a.fallbackWhy().isEmpty());
        // the cap holds
        assertEquals(2, a.relocations());
    }

    @Test
    public void threeLostTriesOnTheLastPatchAlsoFallBack() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.arrived();
        nothingHere(a);
        a.arrived();
        loseATry(a);
        loseATry(a);
        assertEquals(PICK, a.phase());
        loseATry(a);
        assertEquals(FALLBACK, a.phase());
    }

    @Test
    public void nowhereToStandMeansDigNow() {
        StationAttempt a = new StationAttempt();
        nothingHere(a);
        a.noStandpoint();
        assertEquals(CARVE, a.phase());
        assertEquals(StationAttempt.MAX_RELOCATIONS, a.relocations());
    }

    @Test
    public void totalTicksAndHowItWentCoverTheWholeAttempt() {
        StationAttempt clean = new StationAttempt();
        tickN(clean, 4);
        clean.picked();
        tickN(clean, 3);
        assertEquals(7, clean.totalTicks());
        assertEquals("pick", clean.how());
        StationAttempt walked = new StationAttempt();
        nothingHere(walked);
        walked.arrived();
        walked.picked();
        assertEquals("relocate", walked.how());
        StationAttempt dug = new StationAttempt();
        walkedTwiceAndStillNothing(dug);
        dug.carveDone();
        dug.picked();
        assertEquals("carve", dug.how());
        StationAttempt out = new StationAttempt();
        walkedTwiceAndStillNothing(out);
        out.noCarve();
        assertEquals("fallback", out.how());
    }

    @Test
    public void verifyOutsideTheVerifyPhaseDoesNothing() {
        StationAttempt a = new StationAttempt();
        a.verify(true);
        a.verify(true);
        a.verify(true);
        assertEquals(PICK, a.phase());
    }

    @Test
    public void aRefusedClickCountsAsALostTry() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.failed();
        assertEquals(PICK, a.phase());
        assertEquals(1, a.tries());
    }

    // swimming: the caller passes onGround || isInWater, so a steady swim of empty picks walks like a grounded one does
    @Test
    public void aSteadySwimOfEmptyPicksEscalates() {
        StationAttempt a = new StationAttempt();
        for (int i = 0; i < StationAttempt.NO_SPOT_TICKS - 1; i++) {
            a.noSpot(true);
            assertEquals(PICK, a.phase());
        }
        a.noSpot(true);
        assertEquals(RELOCATE, a.phase());
        assertEquals(1, a.relocations());
    }

    // ---- where a relocate goes

    @Test
    public void theBankAndTheFloorComeBeforeTheSwimBack() {
        assertEquals(StationAttempt.Move.SHORE, StationAttempt.relocateMove(true, true, true, 100, false));
        assertEquals(StationAttempt.Move.STANDPOINT, StationAttempt.relocateMove(false, true, true, 100, false));
        assertEquals(StationAttempt.Move.DRY_LAND, StationAttempt.relocateMove(false, false, true, 100, false));
    }

    @Test
    public void theSwimBackIsForWetBotsWithLandInRangeOnce() {
        // dry and stuck is a carve, not a swim
        assertEquals(StationAttempt.Move.NONE, StationAttempt.relocateMove(false, false, false, 100, false));
        // never stood on land in this dimension
        assertEquals(StationAttempt.Move.NONE, StationAttempt.relocateMove(false, false, true, -1, false));
        // the edge of the range counts, past it is an ocean crossing
        assertEquals(StationAttempt.Move.DRY_LAND, StationAttempt.relocateMove(false, false, true, StationAttempt.DRY_LAND_RANGE, false));
        assertEquals(StationAttempt.Move.NONE, StationAttempt.relocateMove(false, false, true, StationAttempt.DRY_LAND_RANGE + 1, false));
        // once per attempt
        assertEquals(StationAttempt.Move.NONE, StationAttempt.relocateMove(false, false, true, 100, true));
    }
}
