package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LavaHunt.Move;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class LavaHuntTest {
    private static final int BAG = 64;
    private static final int FAR = LavaHunt.PICK_REACH + 30;

    // a layout whose middle row cell is (x, 64, 0)
    private static Layout siteAt(int x) {
        return new Layout(x - 1, 64, 0, 1, 0, 0, 1);
    }

    private static Lake good(P anchor, int x, int cost, int fills) {
        return new Lake(anchor, Set.of(anchor), siteAt(x), cost, fills, 0, false);
    }

    private static Lake bad(P anchor) {
        return new Lake(anchor, Set.of(anchor), null, 0, 0, 0, false);
    }

    private static Lake halfRead(P anchor) {
        return new Lake(anchor, Set.of(anchor), null, 0, 0, 0, true);
    }

    @Test
    public void seedsComeCheapestFirstAndHeightCostsMore() {
        P flatFar = new P(40, 64, 0);
        P deepNear = new P(5, 40, 0);
        P flatNear = new P(10, 64, 0);
        List<P> out = LavaHunt.byCost(List.of(deepNear, flatFar, flatNear, flatNear), 0, 64, 0);
        assertEquals(List.of(flatNear, flatFar, deepNear), out);
    }

    @Test
    public void theBandStaysAtTheSurfaceWhenWeStartInACave() {
        assertEquals(LavaHunt.SURFACE_Y, LavaHunt.bandCentre(2));
        assertEquals(LavaHunt.SURFACE_Y, LavaHunt.bandCentre(-30));
        // up on a hill it follows us
        assertEquals(90, LavaHunt.bandCentre(90));
    }

    @Test
    public void chunkCapLetsThreeThroughPerChunk() {
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        assertTrue(cap.take(0, 0));
        assertTrue(cap.take(15, 15));
        assertTrue(cap.take(3, 9));
        assertFalse(cap.take(1, 1));
        // next chunk over, and negative coordinates are their own chunks
        assertTrue(cap.take(16, 0));
        assertTrue(cap.take(-1, 0));
        assertTrue(cap.take(-16, -16));
        assertTrue(cap.take(-1, -1));
        assertTrue(cap.take(-2, -2));
        assertFalse(cap.take(-3, -3));
    }

    @Test
    public void chunkCapReportsFull() {
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        assertFalse(cap.full(0, 0));
        cap.take(0, 0);
        cap.take(1, 1);
        assertFalse(cap.full(2, 2));
        cap.take(2, 2);
        assertTrue(cap.full(3, 3));
        assertFalse(cap.full(16, 0));
    }

    @Test
    public void nothingIsDecidedBeforeAScanFinished() {
        LavaHunt h = new LavaHunt();
        // an empty list from a scan that hasn't run is not "no lava"
        assertEquals(Move.WAIT, h.decide(List.of(), false, false, 0, 64, 0, 0, BAG));
        assertEquals(Move.GIVE_UP, h.decide(List.of(), true, false, 0, 64, 0, 0, BAG));
    }

    @Test
    public void noGiveUpWhileALakeStillHasNoVerdict() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, 0));
        assertEquals(Move.WAIT, h.decide(List.of(a), true, false, 0, 64, 0, 0, BAG));
        h.surveyed(bad(a), 1);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 2, BAG));
    }

    @Test
    public void aLakeInTheMiddleOfItsSurveyIsAReasonToWaitEvenIfTheSeedSlidOutOfTheList() {
        LavaHunt h = new LavaHunt();
        assertEquals(Move.WAIT, h.decide(List.of(), true, true, 0, 64, 0, 0, BAG));
        assertEquals(Move.GIVE_UP, h.decide(List.of(), true, false, 0, 64, 0, 1, BAG));
    }

    @Test
    public void theFirstWalkWaitsForEveryLakeToHaveAVerdict() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P b = new P(150, 64, 0);
        List<P> seeds = List.of(a, b);
        h.surveyed(good(a, 100, 0, 0), 0);
        // b still has none: no walk yet, even though a passed
        assertEquals(b, h.nextToSurvey(seeds, 0, 64, 0, 1));
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 0, 64, 0, 1, BAG));
        h.surveyed(bad(b), 2);
        assertNull(h.nextToSurvey(seeds, 0, 64, 0, 3));
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 3, BAG));
        assertEquals(new P(100, 64, 0), h.target());
    }

    @Test
    public void aWalkAlreadyGoingDoesntStopForANewLake() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        h.surveyed(good(a, 100, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        P fresh = new P(300, 64, 0);
        assertEquals(Move.WALK, h.decide(List.of(a, fresh), true, false, 5, 64, 0, 2, BAG));
    }

    @Test
    public void aSeedOfAnAlreadySurveyedLakeIsNotSurveyedAgain() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P cellOfA = new P(101, 64, 0);
        h.surveyed(new Lake(a, Set.of(a, cellOfA), null, 0, 0, 0, false), 0);
        assertNull(h.nextToSurvey(List.of(cellOfA), 0, 64, 0, 1));
    }

    @Test
    public void aLakeWeCouldntReadAllOfIsNotBadAndComesBack() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        h.surveyed(halfRead(a), 0);
        // not a no, so we go and read the rest of it
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        assertEquals(a, h.target());
        assertTrue(h.blindWalk());
        assertTrue(rejected.isEmpty());
        assertNull(h.nextToSurvey(List.of(a), 0, 64, 0, LavaHunt.UNKNOWN_TICKS));
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, LavaHunt.UNKNOWN_TICKS + 1));
    }

    @Test
    public void theNearestHalfReadLakeIsTheOneWalkedTo() {
        LavaHunt h = new LavaHunt();
        P near = new P(100, 64, 0);
        P far = new P(180, 64, 0);
        h.surveyed(halfRead(far), 0);
        h.surveyed(halfRead(near), 0);
        assertEquals(Move.WALK, h.decide(List.of(near, far), true, false, 0, 64, 0, 1, BAG));
        assertEquals(near, h.target());
    }

    @Test
    public void aSiteBeatsAHalfReadLakeFromTheStart() {
        LavaHunt h = new LavaHunt();
        P maybe = new P(60, 64, 0);
        P real = new P(150, 64, 0);
        h.surveyed(halfRead(maybe), 0);
        h.surveyed(good(real, 150, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(maybe, real), true, false, 0, 64, 0, 1, BAG));
        assertEquals(real, h.target());
        assertFalse(h.blindWalk());
    }

    @Test
    public void theWalkKeepsItsTargetThroughTheLookAgain() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        List<P> seeds = List.of(a);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 1, BAG));
        // its verdict went stale, it's being read again and has no entry at all right now
        int later = LavaHunt.UNKNOWN_TICKS + 1;
        assertEquals(a, h.nextToSurvey(seeds, 10, 64, 0, later));
        assertEquals(Move.WALK, h.decide(seeds, true, true, 10, 64, 0, later, BAG));
        assertEquals(a, h.target());
        // still half read when it comes back, still walking
        h.surveyed(halfRead(a), later + 1);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 20, 64, 0, later + 2, BAG));
        assertEquals(a, h.target());
    }

    @Test
    public void aRealNoEndsTheWalkAndCountsAsAWalk() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P b = new P(120, 64, 40);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        assertEquals(a, h.target());
        int later = LavaHunt.UNKNOWN_TICKS + 1;
        assertEquals(a, h.nextToSurvey(List.of(a, b), 90, 64, 0, later));
        h.surveyed(bad(a), later);
        // the walk over there showed another half read one, and that gets the second walk
        h.surveyed(halfRead(b), later);
        assertEquals(Move.WALK, h.decide(List.of(a, b), true, false, 90, 64, 0, later + 1, BAG));
        assertEquals(b, h.target());
    }

    @Test
    public void aRealYesEndsTheBlindWalkAndWalksToTheSite() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        h.nextToSurvey(List.of(a), 40, 64, 0, LavaHunt.UNKNOWN_TICKS + 1);
        h.surveyed(good(a, 104, 3, 0), LavaHunt.UNKNOWN_TICKS + 1);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 40, 64, 0, LavaHunt.UNKNOWN_TICKS + 2, BAG));
        assertEquals(new P(104, 64, 0), h.target());
        assertFalse(h.blindWalk());
    }

    @Test
    public void aSiteShowingUpSomewhereElseEndsTheBlindWalk() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P real = new P(0, 64, 90);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        h.surveyed(new Lake(real, Set.of(real), new Layout(-1, 64, 90, 1, 0, 0, 1), 0, 0, 0, false), 2);
        assertEquals(Move.WALK, h.decide(List.of(a, real), true, false, 10, 64, 0, 3, BAG));
        assertEquals(new P(0, 64, 90), h.target());
        assertFalse(h.blindWalk());
    }

    @Test
    public void twoWalksToReadALakeAndThenTheCast() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        P b = new P(150, 64, 0);
        P c = new P(200, 64, 0);
        List<P> seeds = List.of(a, b, c);
        h.surveyed(halfRead(a), 0);
        h.surveyed(halfRead(b), 0);
        h.surveyed(halfRead(c), 0);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 1, BAG));
        assertEquals(a, h.target());
        int t = LavaHunt.UNKNOWN_TICKS + 1;
        h.nextToSurvey(seeds, 95, 64, 0, t);
        h.surveyed(bad(a), t);
        h.surveyed(halfRead(b), t);
        h.surveyed(halfRead(c), t);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 95, 64, 0, t + 1, BAG));
        assertEquals(b, h.target());
        t += LavaHunt.UNKNOWN_TICKS + 1;
        h.nextToSurvey(seeds, 145, 64, 0, t);
        h.surveyed(bad(b), t);
        h.surveyed(halfRead(c), t);
        // c is still half read, and that is now as good as a no. nothing is rejected though, it just isn't our problem
        assertEquals(Move.GIVE_UP, h.decide(seeds, true, false, 145, 64, 0, t + 1, BAG));
        assertNull(h.target());
        assertTrue(rejected.isEmpty());
    }

    @Test
    public void aBlindWalkThatGetsNowhereRejectsTheLakeAndUsesItsTurn() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        P b = new P(100, 64, 100);
        P c = new P(100, 64, 200);
        List<P> seeds = List.of(a, b, c);
        h.surveyed(halfRead(a), 0);
        h.surveyed(halfRead(b), 0);
        h.surveyed(halfRead(c), 0);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 0, BAG));
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 0, 64, 0, LavaHunt.PROGRESS_TICKS + 1, BAG));
        assertEquals(1, rejected.size());
        assertNull(h.target());
        // the stale half read ones get their look again first
        int t = LavaHunt.PROGRESS_TICKS + 2;
        assertEquals(b, h.nextToSurvey(seeds, 0, 64, 0, t));
        h.surveyed(halfRead(b), t);
        h.surveyed(halfRead(c), t);
        assertNull(h.nextToSurvey(seeds, 0, 64, 0, t));
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, t, BAG));
        assertEquals(b, h.target());
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 0, 64, 0, t + LavaHunt.PROGRESS_TICKS + 1, BAG));
        t += LavaHunt.PROGRESS_TICKS + 2;
        h.nextToSurvey(seeds, 0, 64, 0, t);
        h.surveyed(halfRead(c), t);
        assertEquals(Move.GIVE_UP, h.decide(seeds, true, false, 0, 64, 0, t, BAG));
    }

    @Test
    public void aRejectedHalfReadLakeIsNotWalkedTo() {
        List<P> rejected = new ArrayList<>();
        rejected.add(new P(100, 64, 0));
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(101, 64, 0);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
    }

    @Test
    public void aBagTooLightForTheSiteDoesNotKeepABlindWalkGoingOnIt() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        h.surveyed(halfRead(a), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, 9));
        h.nextToSurvey(List.of(a), 50, 64, 0, LavaHunt.UNKNOWN_TICKS + 1);
        // a real verdict we can't afford is still a real verdict, and skipped like any other
        h.surveyed(good(a, 100, 3, 2), LavaHunt.UNKNOWN_TICKS + 1);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 50, 64, 0, LavaHunt.UNKNOWN_TICKS + 2, 9));
    }

    @Test
    public void aLakeTheBagCantAffordIsSkippedButNeverRejected() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        h.surveyed(good(a, 100, 3, 2), 0);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 1, 9));
        assertTrue(rejected.isEmpty());
        // blocks later and it is the target
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 2, 10));
        assertEquals(new P(100, 64, 0), h.target());
    }

    @Test
    public void theCheaperWalkWins() {
        LavaHunt h = new LavaHunt();
        P near = new P(60, 64, 0);
        P far = new P(150, 64, 0);
        h.surveyed(good(far, 150, 0, 0), 0);
        h.surveyed(good(near, 60, 2, 0), 0);
        h.decide(List.of(near, far), true, false, 0, 64, 0, 1, BAG);
        assertEquals(new P(60, 64, 0), h.target());
    }

    @Test
    public void pickTakesALakeInReachAndOnlyAPerfectOneWhileOthersAreStillBeingLookedAt() {
        LavaHunt h = new LavaHunt();
        P rough = new P(4, 64, 0);
        P perfect = new P(8, 64, 0);
        P far = new P(FAR, 64, 0);
        h.surveyed(good(rough, 4, 4, 0), 0);
        h.surveyed(good(far, FAR, 0, 0), 0);
        assertNull(h.pick(0, 64, 0, BAG, true));
        assertEquals(siteAt(4), h.pick(0, 64, 0, BAG, false));
        h.surveyed(good(perfect, 8, 0, 0), 0);
        assertEquals(siteAt(8), h.pick(0, 64, 0, BAG, true));
    }

    @Test
    public void onlyALakeWeAreBasicallyAtIsPicked() {
        LavaHunt h = new LavaHunt();
        h.surveyed(good(new P(LavaHunt.PICK_REACH, 64, 0), LavaHunt.PICK_REACH, 0, 0), 0);
        assertEquals(siteAt(LavaHunt.PICK_REACH), h.pick(0, 64, 0, BAG, false));
        assertNull("a step further is a walk", h.pick(-1, 64, 0, BAG, false));
        assertEquals(siteAt(LavaHunt.PICK_REACH), h.pick(0, 64 + LavaHunt.PICK_DY, 0, BAG, false));
        assertNull("over it is not at it", h.pick(0, 64 + LavaHunt.PICK_DY + 1, 0, BAG, false));
        assertNull(h.pick(0, 64 - LavaHunt.PICK_DY - 1, 0, BAG, false));
    }

    @Test
    public void aLakeOutOfPickReachIsWalkedToSoTheClocksStartOnArrival() {
        LavaHunt h = new LavaHunt();
        P a = new P(30, 64, 0);
        h.surveyed(good(a, 30, 0, 0), 0);
        assertNull("this used to be close enough to build from", h.pick(0, 64, 0, BAG, false));
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        assertEquals(new P(30, 64, 0), h.target());
        // right over it is still a walk, the dig in is part of the walk too
        assertNull(h.pick(28, 84, 0, BAG, true));
        assertEquals(siteAt(30), h.pick(25, 66, 0, BAG, true));
    }

    @Test
    public void groundOverALakeCountsAgainstItInTheWalk() {
        LavaHunt h = new LavaHunt();
        P buried = new P(60, 64, 0);
        P open = new P(75, 64, 0);
        h.surveyed(new Lake(buried, Set.of(buried), siteAt(60), 0, 0, 15, false), 0);
        h.surveyed(good(open, 75, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(buried, open), true, false, 0, 64, 0, 1, BAG));
        // 60 + 15 blocks of dirt at 2 each is farther than 75 of plain walking
        assertEquals(new P(75, 64, 0), h.target());
    }

    @Test
    public void aLittleGroundDoesntLoseToALongWalk() {
        LavaHunt h = new LavaHunt();
        P buried = new P(60, 64, 0);
        P open = new P(100, 64, 0);
        h.surveyed(new Lake(buried, Set.of(buried), siteAt(60), 0, 0, 15, false), 0);
        h.surveyed(good(open, 100, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(buried, open), true, false, 0, 64, 0, 1, BAG));
        assertEquals(new P(60, 64, 0), h.target());
    }

    @Test
    public void theLakeWeWalkedToIsPickedEvenWhileOthersAreStillBeingLookedAt() {
        LavaHunt h = new LavaHunt();
        P rough = new P(100, 64, 0);
        h.surveyed(good(rough, 100, 6, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(rough), true, false, 0, 64, 0, 0, BAG));
        assertEquals(siteAt(100), h.pick(95, 64, 0, BAG, true));
    }

    @Test
    public void aWalkThatGetsNowhereIsGivenUpAndTheNextLakeIsTaken() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        P b = new P(200, 64, 0);
        List<P> seeds = List.of(a, b);
        h.surveyed(good(a, 100, 0, 0), 0);
        h.surveyed(good(b, 200, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 0, BAG));
        assertEquals(new P(100, 64, 0), h.target());
        // 2 blocks of shuffling is jitter, 3 is the bar
        assertEquals(Move.WALK, h.decide(seeds, true, false, 2, 64, 0, LavaHunt.PROGRESS_TICKS, BAG));
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 1, 64, 0, LavaHunt.PROGRESS_TICKS + 1, BAG));
        assertEquals(1, rejected.size());
        assertNull(h.target());
        assertEquals(Move.WALK, h.decide(seeds, true, false, 1, 64, 0, LavaHunt.PROGRESS_TICKS + 2, BAG));
        assertEquals(new P(200, 64, 0), h.target());
    }

    @Test
    public void oscillatingNeverBeatsTheBest() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 10, 64, 0, 0, BAG));
        Move m = Move.WALK;
        int t = 1;
        // forth and back over the same few blocks, forever: every other step is a decrease but never a new best
        while (m == Move.WALK && t < LavaHunt.PROGRESS_TICKS * 3) {
            m = h.decide(List.of(a), true, false, t % 2 == 0 ? 10 : 12, 64, 0, t, BAG);
            t += 5;
        }
        assertEquals(Move.WAIT, m);
        assertTrue(t <= LavaHunt.PROGRESS_TICKS + 10);
    }

    @Test
    public void everyNewBestOfThreeBlocksRestartsTheWalkClock() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        int t = 0;
        int x = 0;
        // 4 blocks every 14 s: slow, but it is getting there
        for (int i = 0; i < 4; i++) {
            assertEquals(Move.WALK, h.decide(List.of(a), true, false, x, 64, 0, t, BAG));
            t += LavaHunt.PROGRESS_TICKS - 20;
            x += 4;
        }
        assertEquals(new P(150, 64, 0), h.target());
    }

    @Test
    public void theWholeWalkHasAHardCap() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        int x = 0;
        int t = 0;
        Move m = Move.WALK;
        // always a bit closer, forever
        while (t <= LavaHunt.WALK_TICKS + 200 && m == Move.WALK) {
            m = h.decide(List.of(a), true, false, x, 64, 0, t, BAG);
            x += 4;
            t += 100;
        }
        assertEquals(Move.WAIT, m);
        assertNull(h.target());
    }

    @Test
    public void aRejectedLakeStaysRejectedForTheNextTry() {
        List<P> rejected = new ArrayList<>();
        LavaHunt first = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        first.surveyed(good(a, 100, 0, 0), 0);
        first.siteFailed(new P(101, 64, 0));
        // the lake is gone from the verdicts and its cells are not looked at again
        assertNull(first.nextToSurvey(List.of(a, new P(110, 64, 10)), 0, 64, 0, 1));
        // a retry builds a new hunt on the same list: nothing there is surveyed, let alone walked to
        LavaHunt retry = new LavaHunt(rejected);
        assertNull(retry.nextToSurvey(List.of(a), 0, 64, 0, 0));
        assertEquals(Move.GIVE_UP, retry.decide(List.of(a), true, false, 0, 64, 0, 0, BAG));
        // another lake is still fair game
        assertEquals(new P(300, 64, 0), retry.nextToSurvey(List.of(a, new P(300, 64, 0)), 0, 64, 0, 1));
    }

    @Test
    public void aStaleVerdictDroppedAtPickTimeIsSurveyedAgain() {
        LavaHunt h = new LavaHunt();
        P a = new P(20, 64, 0);
        h.surveyed(good(a, 20, 0, 0), 0);
        assertNull(h.nextToSurvey(List.of(a), 0, 64, 0, 0));
        h.drop(siteAt(20));
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, 1));
    }

    @Test
    public void theCastLakeIsTheNearestOneBigEnoughForTheFlood() {
        P tiny = new P(5, 64, 0);
        P near = new P(30, 64, 0);
        P far = new P(90, 64, 0);
        // the tiny puddle is the closest seed but the cast wants a flood, so it is walked past
        assertEquals(near, LavaHunt.castLake(List.of(far, tiny, near), 0, 64, 0, 12, seed -> seed.equals(tiny) ? 3 : 20));
        assertNull(LavaHunt.castLake(List.of(tiny), 0, 64, 0, 12, seed -> 3));
        assertNull(LavaHunt.castLake(List.of(), 0, 64, 0, 12, seed -> 99));
    }

    @Test
    public void theFetchBandIsTheSurfaceAndNotTheCaves() {
        assertTrue(LavaHunt.inBand(64, 70));
        assertTrue(LavaHunt.inBand(64 + LavaHunt.BAND, 64));
        assertFalse(LavaHunt.inBand(64 + LavaHunt.BAND + 1, 64));
        // standing in a cave does not move the band down with us
        assertFalse(LavaHunt.inBand(2, 10));
        assertTrue(LavaHunt.inBand(60 - LavaHunt.BAND, 10));
    }
}
