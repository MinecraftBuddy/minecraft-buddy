package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BiteRules.Verdict.ASK_STOP;
import static adris.altoclef.util.helpers.BiteRules.Verdict.CHEW_ON_THE_MOVE;
import static adris.altoclef.util.helpers.BiteRules.Verdict.EAT_NOW;
import static adris.altoclef.util.helpers.BiteRules.Verdict.NOT_NOW;
import static adris.altoclef.util.helpers.BiteRules.Verdict.WAIT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.BiteRules.Press;
import adris.altoclef.util.helpers.BiteRules.Scene;
import adris.altoclef.util.helpers.BiteRules.UseWatch;
import adris.altoclef.util.helpers.BiteRules.Verdict;
import adris.altoclef.util.helpers.BiteRules.Waiting;
import org.junit.Test;

// when a bite may begin: standing, or not at all (outside a fight)
public class BiteRulesTest {

    private static final double RUNNING = 0.28;

    // a path is walking us somewhere and has not been asked to stop
    private static Scene walking() {
        return Scene.standing().withPath(true, false, true).withSpeed(RUNNING);
    }

    @Test
    public void standingInAFieldWithNoPathEatsRightAway() {
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing()));
    }

    @Test
    public void walkingOnAPathAsksForAStopAndTouchesNothingElse() {
        assertEquals(ASK_STOP, BiteRules.verdict(walking()));
    }

    @Test
    public void aGrantedPauseFlipsTheAskIntoAMeal() {
        Scene sliding = walking().withPath(true, true, true).withSpeed(0.2);
        assertEquals(ASK_STOP, BiteRules.verdict(sliding));
        assertEquals(EAT_NOW, BiteRules.verdict(sliding.withSpeed(0)));
        assertEquals(EAT_NOW, BiteRules.verdict(sliding.withSpeed(BiteRules.STILL_SPEED)));
    }

    @Test
    public void aMovementThatCannotBeCancelledWaitsAndDoesNotAsk() {
        assertEquals(WAIT, BiteRules.verdict(walking().withPath(true, false, false)));
    }

    @Test
    public void midAirWaitsWithOrWithoutAPath() {
        assertEquals(WAIT, BiteRules.verdict(walking().withOnGround(false)));
        assertEquals(WAIT, BiteRules.verdict(Scene.standing().withOnGround(false)));
        // even a pause that was granted a tick ago, a stop in the air is a different landing spot
        assertEquals(WAIT, BiteRules.verdict(walking().withPath(true, true, true).withSpeed(0).withOnGround(false)));
    }

    @Test
    public void waterLadderAndBoatWaitWhileAPathIsLive() {
        assertEquals(WAIT, BiteRules.verdict(walking().withInWater(true)));
        assertEquals(WAIT, BiteRules.verdict(walking().withOnLadder(true)));
        assertEquals(WAIT, BiteRules.verdict(walking().withRiding(true)));
        // granted or not
        assertEquals(WAIT, BiteRules.verdict(walking().withPath(true, true, true).withSpeed(0).withInWater(true)));
    }

    @Test
    public void floatingIdleWithNoPathIsStillAMeal() {
        // nothing is steering us, so there is no movement to ruin. starving in a lake is not a reason to starve more
        // (and none of these are on the ground, as far as the game is concerned)
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withOnGround(false).withInWater(true)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withOnGround(false).withOnLadder(true)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withOnGround(false).withRiding(true)));
        // but a bare fall is still a fall
        assertEquals(WAIT, BiteRules.verdict(Scene.standing().withOnGround(false)));
    }

    @Test
    public void swimmingWithAPathIsWaterAndNotMidAir() {
        // the game says not on ground for a swimmer, a ladder hanger and a passenger. the reason has to be the real one
        assertEquals("water", BiteRules.why(walking().withOnGround(false).withInWater(true)));
        assertEquals("ladder", BiteRules.why(walking().withOnGround(false).withOnLadder(true)));
        assertEquals("boat", BiteRules.why(walking().withOnGround(false).withRiding(true)));
        assertEquals("mid-air", BiteRules.why(walking().withOnGround(false)));
        assertEquals(WAIT, BiteRules.verdict(walking().withOnGround(false).withInWater(true)));
    }

    @Test
    public void starvingInTheWaterEventuallyEatsOnTheMove() {
        int line = BiteRules.ESCAPE_TICKS;
        assertFalse(BiteRules.starvingEscape(line - 1, 6, 20, "water"));
        assertTrue(BiteRules.starvingEscape(line, 6, 20, "water"));
        assertTrue(BiteRules.starvingEscape(line, 0, 20, "boat"));
        assertTrue(BiteRules.starvingEscape(line * 3, 3, 4, "water"));
    }

    @Test
    public void notHungryEnoughKeepsWaiting() {
        assertFalse(BiteRules.starvingEscape(1000, 7, 20, "water"));
        assertFalse(BiteRules.starvingEscape(1000, 12, 18, "boat"));
        // hurt and a bit hungry is the desperate meal, that one counts at any hunger under 20
        assertTrue(BiteRules.starvingEscape(1000, 15, 10, "water"));
        assertFalse(BiteRules.starvingEscape(1000, 20, 10, "water"));
    }

    @Test
    public void airLaddersAndUnsafeMovementsNeverEatOnTheMove() {
        for (String why : new String[]{"mid-air", "ladder", "movement not safe", "pause not granted", "sliding to a stop",
                "screen", "moving"}) {
            assertFalse(why, BiteRules.starvingEscape(10_000, 0, 1, why));
        }
    }

    @Test
    public void everybodyElseGetsTheirChecksBackAfterTheWaitLine() {
        assertFalse(BiteRules.waitedTooLong(BiteRules.WAIT_LOG_TICKS - 1));
        assertTrue(BiteRules.waitedTooLong(BiteRules.WAIT_LOG_TICKS));
    }

    @Test
    public void noPathButStillMovingWaits() {
        assertEquals(WAIT, BiteRules.verdict(Scene.standing().withSpeed(RUNNING)));
    }

    @Test
    public void aScreenWaitsInsteadOfFightingIt() {
        assertEquals(WAIT, BiteRules.verdict(Scene.standing().withScreen(true)));
        assertEquals(WAIT, BiteRules.verdict(walking().withScreen(true)));
    }

    @Test
    public void aBiteAlreadyGoingIsFinished() {
        // pause or not, wherever we are: the pause is ours until the meal is done
        assertEquals(EAT_NOW, BiteRules.verdict(walking().withMeal(true)));
        assertEquals(EAT_NOW, BiteRules.verdict(walking().withMeal(true).withOnGround(false)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withMeal(true).withScreen(true)));
    }

    @Test
    public void combatAlwaysChewsOnTheMove() {
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(Scene.standing().withCombat(true)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true).withMeal(true)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true).withOnGround(false).withInWater(true)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true).withScreen(true)));
    }

    @Test
    public void everyWaitHasAReason() {
        assertEquals("screen", BiteRules.why(walking().withScreen(true)));
        assertEquals("mid-air", BiteRules.why(walking().withOnGround(false)));
        assertEquals("water", BiteRules.why(walking().withInWater(true)));
        assertEquals("ladder", BiteRules.why(walking().withOnLadder(true)));
        assertEquals("boat", BiteRules.why(walking().withRiding(true)));
        assertEquals("movement not safe", BiteRules.why(walking().withPath(true, false, false)));
        assertEquals("pause not granted", BiteRules.why(walking()));
        assertEquals("sliding to a stop", BiteRules.why(walking().withPath(true, true, true)));
        assertEquals("moving", BiteRules.why(Scene.standing().withSpeed(RUNNING)));
    }

    @Test
    public void theWaitLineFiresOnceAtSixtyTicks() {
        Waiting waiting = new Waiting();
        for (int i = 1; i < BiteRules.WAIT_LOG_TICKS; i++) {
            assertNull("tick " + i, waiting.tick(WAIT, "water"));
        }
        assertEquals("food: waiting for a safe stop, 60t (water)", waiting.tick(WAIT, "water"));
        for (int i = 0; i < 200; i++) {
            assertNull(waiting.tick(WAIT, "water"));
        }
    }

    @Test
    public void askingAndWaitingBothCountTowardsTheLine() {
        Waiting waiting = new Waiting();
        String line = null;
        for (int i = 1; i <= BiteRules.WAIT_LOG_TICKS; i++) {
            line = waiting.tick(i % 2 == 0 ? ASK_STOP : WAIT, "mid-air");
        }
        assertNotNull(line);
    }

    @Test
    public void aStopOrAFightClearsTheWaitAndItCanFireAgain() {
        Waiting waiting = new Waiting();
        for (int i = 0; i < 59; i++) waiting.tick(WAIT, "water");
        assertNull(waiting.tick(EAT_NOW, null));
        assertEquals(0, waiting.ticks());
        for (int i = 1; i < BiteRules.WAIT_LOG_TICKS; i++) {
            assertNull(waiting.tick(ASK_STOP, "pause not granted"));
        }
        assertNotNull(waiting.tick(ASK_STOP, "pause not granted"));
        waiting.tick(CHEW_ON_THE_MOVE, null);
        for (int i = 1; i < BiteRules.WAIT_LOG_TICKS; i++) waiting.tick(WAIT, "x");
        assertNotNull(waiting.tick(WAIT, "x"));
    }

    @Test
    public void aBiteThatStartsPromptlyNeverTripsTheWatchdog() {
        UseWatch watch = new UseWatch();
        for (int i = 0; i < BiteRules.USE_START_TICKS - 1; i++) {
            assertEquals(Press.HOLD, watch.step(false));
            assertFalse(watch.startedThisTick());
        }
        assertEquals(Press.HOLD, watch.step(true));
        assertTrue(watch.startedThisTick());
        assertEquals(BiteRules.USE_START_TICKS - 1, watch.ticksToStart());
        assertEquals(Press.HOLD, watch.step(true));
        assertFalse(watch.startedThisTick());
    }

    @Test
    public void tenIdleTicksPressAgain() {
        UseWatch watch = new UseWatch();
        for (int i = 0; i < BiteRules.USE_START_TICKS - 1; i++) {
            assertEquals(Press.HOLD, watch.step(false));
        }
        assertEquals(Press.PRESS_AGAIN, watch.step(false));
        assertEquals(1, watch.retries());
        // and the clock starts over
        for (int i = 0; i < BiteRules.USE_START_TICKS - 1; i++) {
            assertEquals(Press.HOLD, watch.step(false));
        }
        assertEquals(Press.PRESS_AGAIN, watch.step(false));
        assertEquals(2, watch.retries());
    }

    @Test
    public void threeFailedPressesBackOffForFortyTicks() {
        UseWatch watch = new UseWatch();
        for (int press = 1; press <= BiteRules.RETRIES; press++) {
            for (int i = 0; i < BiteRules.USE_START_TICKS - 1; i++) watch.step(false);
            assertEquals(Press.PRESS_AGAIN, watch.step(false));
        }
        for (int i = 0; i < BiteRules.USE_START_TICKS - 1; i++) {
            assertEquals(Press.HOLD, watch.step(false));
        }
        assertFalse(watch.backingOff());
        assertEquals(Press.BACK_OFF, watch.step(false));
        assertTrue(watch.backingOff());
        for (int i = 1; i < BiteRules.BACKOFF_TICKS; i++) {
            watch.tickBackoff();
            assertTrue("tick " + i, watch.backingOff());
        }
        watch.tickBackoff();
        assertFalse(watch.backingOff());
    }

    @Test
    public void aMealEndingDoesNotCancelABackoff() {
        UseWatch watch = new UseWatch();
        for (int press = 0; press <= BiteRules.RETRIES; press++) {
            for (int i = 0; i < BiteRules.USE_START_TICKS; i++) watch.step(false);
        }
        assertTrue(watch.backingOff());
        watch.reset();
        assertTrue(watch.backingOff());
        watch.clear();
        assertFalse(watch.backingOff());
    }

    @Test
    public void aBiteThatLandsClearsTheRetries() {
        UseWatch watch = new UseWatch();
        for (int i = 0; i < BiteRules.USE_START_TICKS; i++) watch.step(false);
        assertEquals(1, watch.retries());
        watch.step(true);
        assertEquals(0, watch.retries());
        // the next bite in a fillup waits out the game's own delay between uses without tripping anything
        watch.step(false);
        for (int i = 0; i < 4; i++) assertEquals(Press.HOLD, watch.step(false));
    }

    @Test
    public void aNormalBiteNeverTripsTheStuckCheck() {
        UseWatch watch = new UseWatch();
        for (int remaining = 32; remaining >= -BiteRules.STUCK_TICKS; remaining--) {
            assertEquals("remaining " + remaining, Press.HOLD, watch.step(true, remaining));
        }
        assertEquals(Press.HOLD, watch.step(false, 0));
    }

    @Test
    public void aUseStuckBelowTheLimitReleases() {
        UseWatch watch = new UseWatch();
        assertEquals(Press.RELEASE, watch.step(true, -BiteRules.STUCK_TICKS - 1));
    }

    @Test
    public void stuckAgainAndAgainBacksOff() {
        UseWatch watch = new UseWatch();
        for (int i = 0; i < BiteRules.RETRIES; i++) {
            assertEquals(Press.RELEASE, watch.step(true, -50));
            // let go, press, the bite starts and sticks all over again
            watch.step(false, 0);
            assertEquals(Press.HOLD, watch.step(true, 30));
        }
        assertEquals(Press.BACK_OFF, watch.step(true, -50));
        assertTrue(watch.backingOff());
    }

    @Test
    public void resetAndClearForgetTheStuckCount() {
        UseWatch watch = new UseWatch();
        for (int i = 0; i < BiteRules.RETRIES; i++) watch.step(true, -50);
        watch.reset();
        assertEquals(Press.RELEASE, watch.step(true, -50));
        for (int i = 1; i < BiteRules.RETRIES; i++) watch.step(true, -50);
        watch.clear();
        assertEquals(Press.RELEASE, watch.step(true, -50));
    }

    @Test
    public void eachBiteOfAFillupStartsAgain() {
        UseWatch watch = new UseWatch();
        watch.step(true);
        assertTrue(watch.startedThisTick());
        watch.step(true);
        assertFalse(watch.startedThisTick());
        watch.step(false);
        watch.step(true);
        assertTrue(watch.startedThisTick());
    }

    @Test
    public void aHitDropsAStandingMealAtOnce() {
        Scene chewing = walking().withPath(true, true, true).withSpeed(0).withMeal(true);
        assertEquals(EAT_NOW, BiteRules.verdict(chewing));
        assertEquals(NOT_NOW, BiteRules.verdict(chewing.withHitAge(10)));
        assertEquals(NOT_NOW, BiteRules.verdict(Scene.standing().withMeal(true).withHitAge(0)));
    }

    @Test
    public void aHitWindowIsFortyTicksAndNoMore() {
        assertEquals(NOT_NOW, BiteRules.verdict(Scene.standing().withHitAge(BiteRules.HIT_TICKS)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withHitAge(BiteRules.HIT_TICKS + 1)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withHitAge(BiteRules.NO_HIT)));
        // a clock that went backwards (a new world) is not a hit
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withHitAge(-5)));
        assertTrue(BiteRules.recentlyHit(0));
        assertFalse(BiteRules.recentlyHit(BiteRules.NO_HIT));
    }

    @Test
    public void aHitStopsEveryWayOfStartingAMeal() {
        assertEquals(NOT_NOW, BiteRules.verdict(walking().withHitAge(5)));
        assertEquals(NOT_NOW, BiteRules.verdict(walking().withPath(true, true, true).withSpeed(0).withHitAge(5)));
        assertEquals(NOT_NOW, BiteRules.verdict(walking().withOnGround(false).withHitAge(5)));
        assertEquals(NOT_NOW, BiteRules.verdict(Scene.standing().withScreen(true).withHitAge(5)));
    }

    @Test
    public void aHitNeverTouchesTheFightsOwnMeal() {
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true).withHitAge(0)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true).withMeal(true).withHitAge(10)));
    }

    @Test
    public void onlyThingsWithABodyCountAsAHit() {
        // poison, wither, a fall, fire, lava, hunger: no attacker and nothing carrying it
        assertFalse(BiteRules.entityHit(false, false));
        // a zombie, an arrow with a skeleton behind it, a fireball, an arrow from nobody we can see
        assertTrue(BiteRules.entityHit(true, true));
        assertTrue(BiteRules.entityHit(true, false));
        assertTrue(BiteRules.entityHit(false, true));
    }

    @Test
    public void aPoisonTickLeavesTheMealAlone() {
        // the chain never records a hit for a source with no body, so the age stays at "none"
        Scene chewing = Scene.standing().withMeal(true).withHitAge(BiteRules.NO_HIT);
        assertEquals(EAT_NOW, BiteRules.verdict(chewing));
    }

    @Test
    public void everyVerdictIsReachable() {
        // a guard on the table above: if one of these stops being possible somebody changed what the words mean
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing()));
        assertEquals(ASK_STOP, BiteRules.verdict(walking()));
        assertEquals(WAIT, BiteRules.verdict(walking().withOnGround(false)));
        assertEquals(CHEW_ON_THE_MOVE, BiteRules.verdict(walking().withCombat(true)));
        assertEquals(NOT_NOW, BiteRules.verdict(Scene.standing().withHitAge(1)));
        assertEquals(5, Verdict.values().length);
    }

    @Test
    public void shieldCalledForThisTickOrLastBlocksTheBite() {
        assertTrue(BiteRules.dodgeHolds(100, 100));
        assertTrue(BiteRules.dodgeHolds(101, 100));
        assertFalse(BiteRules.dodgeHolds(102, 100));
        // never dodged, and a clock that went backwards
        assertFalse(BiteRules.dodgeHolds(100, Long.MIN_VALUE));
        assertFalse(BiteRules.dodgeHolds(50, 100));
    }

    @Test
    public void anArrowOrAFireballDoesNotCountAsAContactHit() {
        // a zombie in contact, a skeleton's arrow, a ghast ball: only the first one drops a standing bite
        assertTrue(BiteRules.contactHit(true, true, false));
        assertFalse(BiteRules.contactHit(true, true, true));
        assertFalse(BiteRules.contactHit(true, false, true));
        assertFalse(BiteRules.contactHit(false, true, true));
        // and the other kinds of damage still are not hits at all
        assertFalse(BiteRules.contactHit(false, false, false));
    }

    @Test
    public void aBiteOnlyGivesWayToTheShieldWhenTheProjectileIsAboutToLand() {
        assertTrue(BiteRules.shieldMayStart(false, Double.POSITIVE_INFINITY, false));
        assertTrue(BiteRules.shieldMayStart(false, 40, false));
        assertFalse(BiteRules.shieldMayStart(true, 40, false));
        assertFalse(BiteRules.shieldMayStart(true, Double.POSITIVE_INFINITY, false));
        assertTrue(BiteRules.shieldMayStart(true, BiteRules.SHIELD_IMPACT_TICKS, false));
        assertTrue(BiteRules.shieldMayStart(true, 3, false));
    }

    @Test
    public void aGhastBallGetsTheShieldEarlierThanAnArrow() {
        // 14 on the clock: an arrow lets the bite finish, a ghast ball (which speeds up) does not
        assertFalse(BiteRules.shieldMayStart(true, 14, false));
        assertTrue(BiteRules.shieldMayStart(true, 14, true));
        assertTrue(BiteRules.shieldMayStart(true, BiteRules.SHIELD_IMPACT_FIREBALL_TICKS, true));
        assertFalse(BiteRules.shieldMayStart(true, BiteRules.SHIELD_IMPACT_FIREBALL_TICKS + 1, true));
        assertFalse(BiteRules.shieldMayStart(true, Double.POSITIVE_INFINITY, true));
        // not biting: nothing to give way
        assertTrue(BiteRules.shieldMayStart(false, 40, true));
    }

    @Test
    public void aMeleeHitStillDropsTheBiteAndAnArrowDoesNot() {
        // the chain feeds the age of the last contact hit: an arrow never gets there, a zombie does
        assertEquals(NOT_NOW, BiteRules.verdict(Scene.standing().withMeal(true).withHitAge(5)));
        assertEquals(EAT_NOW, BiteRules.verdict(Scene.standing().withMeal(true).withHitAge(BiteRules.NO_HIT)));
    }
}
