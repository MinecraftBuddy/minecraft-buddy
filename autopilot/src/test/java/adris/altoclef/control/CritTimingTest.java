package adris.altoclef.control;

import adris.altoclef.control.CritTiming.Sample;
import adris.altoclef.control.CritTiming.Step;
import org.junit.Test;

import static org.junit.Assert.*;

public class CritTimingTest {

    // standing next to a mob, weapon a few ticks from ready, nothing in the way
    private static Sample ready() {
        Sample s = new Sample();
        s.ticksToFull = 4;
        return s;
    }

    // up in the air, not falling yet
    private static Sample rising(double ticksToFull) {
        Sample s = new Sample();
        s.onGround = false;
        s.ticksToFull = ticksToFull;
        return s;
    }

    private static Sample falling(double ticksToFull) {
        Sample s = rising(ticksToFull);
        s.falling = true;
        return s;
    }

    private static Sample landed() {
        Sample s = new Sample();
        s.ticksToFull = 0;
        return s;
    }

    @Test
    public void jumpsWhenTheCooldownIsAboutToFillAndTheMobIsInReach() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        assertTrue(t.inFlight(0));
    }

    @Test
    public void jumpsRightAtTheLeadAndNotBefore() {
        Sample s = ready();
        s.ticksToFull = CritTiming.LEAD_TICKS;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.ticksToFull = CritTiming.LEAD_TICKS + 0.5;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void aFullCooldownWhileGettingHitSwingsNowInsteadOfHopping() {
        // a hop is 7 ticks up before anything falls, which is past the 4 tick cap on holding a swing. the next one is timed
        Sample s = ready();
        s.ticksToFull = 0;
        s.recentlyHurt = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.ticksToFull = 0.5;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    // ---- where the mob is

    private static Sample at(double gap) {
        Sample s = ready();
        s.reachGap = gap;
        s.reach = 3.0;
        return s;
    }

    @Test
    public void noHopWhenTheMobIsAtTheEdgeOfReach() {
        // 3.5 is not even in vanilla reach (3.0), 2.9 is in reach but would be gone by the time we come down
        CritTiming far = new CritTiming();
        assertEquals(Step.APPROACH, far.step(0, at(3.5)));
        assertFalse(far.inFlight(0));
        CritTiming edge = new CritTiming();
        assertEquals(Step.APPROACH, edge.step(0, at(2.9)));
        assertFalse(edge.inFlight(0));
    }

    @Test
    public void hopsOnceTheMobIsInsideReachMinusTheMargin() {
        assertEquals(Step.JUMP, new CritTiming().step(0, at(2.4)));
        assertEquals(Step.JUMP, new CritTiming().step(0, at(2.5)));
        assertEquals(Step.APPROACH, new CritTiming().step(0, at(2.6)));
    }

    @Test
    public void theMarginFollowsTheReachWeActuallyHave() {
        Sample longArms = at(3.4);
        longArms.reach = 4.0;
        assertEquals(Step.JUMP, new CritTiming().step(0, longArms));
        Sample shortArms = at(2.4);
        shortArms.reach = 2.5;
        assertEquals(Step.APPROACH, new CritTiming().step(0, shortArms));
    }

    @Test
    public void noHopAtAMobThatIsWalkingAway() {
        Sample s = at(2.0);
        s.retreatSpeed = 0.2;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.retreatSpeed = CritTiming.MAX_RETREAT;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        // coming at us is the good kind of moving
        s.retreatSpeed = -0.2;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void aFarMobThatIsAlsoLeavingIsNotChased() {
        Sample s = at(3.2);
        s.retreatSpeed = 0.25;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void approachNeverAppearsWithoutAHopBeingDue() {
        // cooldown still far off: nothing to time yet. cooldown full while it hits us: the plain swing owns the tick
        Sample early = at(3.2);
        early.ticksToFull = CritTiming.LEAD_TICKS + 1;
        assertEquals(Step.PLAIN, new CritTiming().step(0, early));
        Sample full = at(3.2);
        full.ticksToFull = 0;
        full.recentlyHurt = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, full));
        // and every guard that cancels a hop cancels walking up for one too
        Sample pressured = at(3.2);
        pressured.meleeNear = CritTiming.CROWD_NO_CRIT;
        assertEquals(Step.PLAIN, new CritTiming().step(0, pressured));
        Sample wet = at(3.2);
        wet.inFluid = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, wet));
    }

    @Test
    public void backOffSitsOutWhileAHopIsOn() {
        assertTrue(CritTiming.shouldBackOff(true, false));
        assertFalse(CritTiming.shouldBackOff(true, true));
        assertFalse(CritTiming.shouldBackOff(false, false));
        assertFalse(CritTiming.shouldBackOff(false, true));
    }

    @Test
    public void firstFallingTickInReachSwingsEvenThoughTheMobDriftedABit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, at(2.2)));
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(Math.max(0, 4 - tick))));
        }
        // the moment it counts, not a tick later and not on the next hop
        Sample down = falling(0);
        down.reachGap = 2.9;
        assertEquals(Step.SWING, t.step(7, down));
    }

    @Test
    public void mobLeavingReachMidHopIsWaitedOutAndGivenUpOnLanding() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, at(2.2)));
        assertEquals(Step.WAIT, t.step(1, rising(3)));
        // gone while we were up: nothing can swing up here anyway, so keep the hop in case it steps back in
        Sample gone = falling(0);
        gone.inReach = false;
        gone.aimed = false;
        assertEquals(Step.WAIT, t.step(2, gone));
        assertTrue(t.inFlight(2));
        assertEquals(Step.WAIT, t.step(3, gone));
        // touched down: that is the give up, a plain swing right there
        assertEquals(Step.PLAIN, t.step(4, landed()));
        assertFalse(t.inFlight(4));
        // the next hit is plain too (same as any give up), then it is back to hopping
        assertEquals(Step.PLAIN, t.step(5, landed()));
        assertEquals(Step.JUMP, t.step(6, ready()));
    }

    @Test
    public void aMobSteppingBackIntoReachMidHopStillGetsTheCrit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, at(2.2)));
        Sample gone = rising(3);
        gone.inReach = false;
        gone.aimed = false;
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals(Step.WAIT, t.step(tick, gone));
        }
        assertEquals(Step.SWING, t.step(7, falling(0)));
    }

    // ---- not under fire

    @Test
    public void oneOrTwoOnUsStillHop() {
        // a zombie on us is the normal case, not a reason to stop crit hopping. being hit recently isn't a veto at all any
        // more (there is no hurt timer in the sample), only the crowd and the hearts are
        Sample s = ready();
        s.meleeNear = 1;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.meleeNear = 2;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.meleeNear = 3;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void noHopAtSixHealthOrLess() {
        Sample s = ready();
        s.health = 6;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.health = 6.5f;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.health = 10;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void pressureAsOneQuestionForWantsCritTick() {
        assertFalse(CritTiming.underPressure(1, 20));
        assertFalse(CritTiming.underPressure(2, 10));
        assertTrue(CritTiming.underPressure(3, 20));
        assertTrue(CritTiming.underPressure(0, 6));
    }

    @Test
    public void aBackOffPathUsedToVetoTheHopAndTheOneTheTaskAsksAboutDoesNot() {
        // the kill task's back-off is a baritone path. the sample that decides the hop says pathing, the one the task asks
        // "should I leave the back-off alone" with has it off, and the hop is on in that one
        Sample walking = ready();
        walking.pathing = true;
        assertEquals("pathing", CritTiming.hopVeto(walking));
        assertFalse(CritTiming.wantsHop(walking));
        Sample standing = ready();
        assertNull(CritTiming.hopVeto(standing));
        assertTrue(CritTiming.wantsHop(standing));
        // the back-off sits out while the hop is on, and only then
        assertFalse(CritTiming.shouldBackOff(true, CritTiming.wantsHop(standing)));
        Sample recharging = ready();
        recharging.ticksToFull = CritTiming.LEAD_TICKS + 3;
        assertTrue(CritTiming.shouldBackOff(true, CritTiming.wantsHop(recharging)));
    }

    @Test
    public void hopVetoNamesTheThingThatFired() {
        Sample s = ready();
        s.meleeNear = 4;
        assertTrue(CritTiming.hopVeto(s).startsWith("crowd"));
        Sample low = ready();
        low.health = 4;
        assertEquals("low health", CritTiming.hopVeto(low));
        Sample full = ready();
        full.ticksToFull = 0;
        full.recentlyHurt = true;
        assertNotNull(CritTiming.hopVeto(full));
    }

    @Test
    public void sprintingDelaysTheSwingUntilTheStopHasGoneOut() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        for (int tick = 1; tick <= 6; tick++) {
            t.step(tick, rising(Math.max(0, 4 - tick)));
        }
        // first falling tick, cooldown full, but the client still says sprinting: that would be a sprint hit on the server
        Sample sprinting = falling(0);
        sprinting.sprinting = true;
        assertEquals(Step.WAIT, t.step(7, sprinting));
        assertTrue(t.inFlight(7));
        // the WAIT cleared it, so the next falling tick is the crit
        assertEquals(Step.SWING, t.step(8, falling(0)));
        assertFalse(t.inFlight(8));
    }

    @Test
    public void sprintingAlsoDelaysTheCappedSwing() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT; tick++) {
            t.step(tick, rising(0));
        }
        Sample sprinting = rising(0);
        sprinting.sprinting = true;
        assertEquals(Step.WAIT, t.step(CritTiming.MAX_READY_WAIT + 1, sprinting));
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 2, rising(0)));
    }

    @Test
    public void aKnockbackCritWhileSprintingWaitsOneTick() {
        // no jump tick came first here, so nothing cleared sprint yet
        Sample s = falling(0);
        s.sprinting = true;
        CritTiming t = new CritTiming();
        assertEquals(Step.WAIT, t.step(0, s));
        assertEquals(Step.SWING, t.step(1, falling(0)));
    }

    @Test
    public void sprintingOnTheGroundDoesNotStopTheJump() {
        // the JUMP tick is where the adapter drops sprint, so being mid sprint when the hop starts is the normal case
        Sample s = ready();
        s.sprinting = true;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void aSecondMobArrivingMidAirOrTheHealthDroppingGivesUpToo() {
        CritTiming crowd = new CritTiming();
        crowd.step(0, ready());
        Sample two = rising(3);
        two.meleeNear = CritTiming.CROWD_NO_CRIT;
        assertEquals(Step.PLAIN, crowd.step(1, two));
        CritTiming hurting = new CritTiming();
        hurting.step(0, ready());
        Sample low = falling(0);
        low.health = 6;
        assertEquals(Step.PLAIN, hurting.step(1, low));
    }

    @Test
    public void aFreeCritIsStillTakenUnderPressure() {
        // already coming down with everything lined up costs nothing, no reason to throw it away
        Sample s = falling(0);
        s.meleeNear = 3;
        assertEquals(Step.SWING, new CritTiming().step(0, s));
    }

    // ---- the cap

    @Test
    public void aCrimpedHopNeverHoldsTheSwingMoreThanFourTicksPastFull() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // still rising, cooldown full from tick 1 on (a wall bonked the jump short, whatever): the cap steps in
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(0)));
        }
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 1, rising(0)));
        assertFalse(t.inFlight(CritTiming.MAX_READY_WAIT + 1));
    }

    @Test
    public void theCapCountsConsecutiveReadyTicksOnly() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        // ready, not ready, ready: the count restarts, no early swing
        assertEquals(Step.WAIT, t.step(1, rising(0)));
        assertEquals(Step.WAIT, t.step(2, rising(0)));
        assertEquals(Step.WAIT, t.step(3, rising(2)));
        for (int tick = 4; tick <= 7; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(0)));
        }
    }

    @Test
    public void everyGuardTurnsTheJumpIntoAPlainHit() {
        assertPlain(s -> s.inReach = false);
        assertPlain(s -> s.inFluid = true);
        assertPlain(s -> s.onClimbable = true);
        assertPlain(s -> s.blind = true);
        assertPlain(s -> s.riding = true);
        assertPlain(s -> s.usingItem = true);
        assertPlain(s -> s.shielding = true);
        assertPlain(s -> s.lowHeadroom = true);
        assertPlain(s -> s.unsafeFloor = true);
        assertPlain(s -> s.pathing = true);
        assertPlain(s -> s.normalHitKills = true);
        assertPlain(s -> s.enabled = false);
    }

    private static void assertPlain(java.util.function.Consumer<Sample> spoil) {
        Sample s = ready();
        spoil.accept(s);
        CritTiming t = new CritTiming();
        assertEquals(Step.PLAIN, t.step(0, s));
        assertFalse(t.inFlight(0));
    }

    @Test
    public void jumpThenWaitThenSwingOnTheWayDown() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // up for six ticks, cooldown filling the whole time
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(Math.max(0, 4 - tick))));
        }
        // first falling tick with the cooldown full: this is the crit
        assertEquals(Step.SWING, t.step(7, falling(0)));
        assertFalse(t.inFlight(7));
    }

    @Test
    public void fallingWithAnEmptyCooldownKeepsWaiting() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertEquals(Step.WAIT, t.step(1, rising(5)));
        assertEquals(Step.WAIT, t.step(2, falling(3)));
        assertEquals(Step.WAIT, t.step(3, falling(1)));
        assertEquals(Step.SWING, t.step(4, falling(0)));
    }

    @Test
    public void neverSwingsWhileRisingEvenWithAFullCooldown() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertEquals(Step.WAIT, t.step(1, rising(0)));
        assertEquals(Step.WAIT, t.step(2, rising(0)));
    }

    @Test
    public void aGiveUpMidJumpFallsBackToANormalHitNextTime() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample shield = rising(2);
        shield.shielding = true;
        assertEquals(Step.PLAIN, t.step(1, shield));
        assertFalse(t.inFlight(1));
        // the next hit is a plain one, not another hop
        assertEquals(Step.PLAIN, t.step(2, landed()));
        // and then it is back to hopping
        assertEquals(Step.JUMP, t.step(3, ready()));
    }

    @Test
    public void landingWithoutASwingFallsBackToANormalHitNextTime() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        for (int tick = 1; tick <= 8; tick++) {
            t.step(tick, tick < 7 ? rising(30) : falling(30));
        }
        // touched down with the cooldown still not there
        Sample down = landed();
        down.ticksToFull = 20;
        assertEquals(Step.PLAIN, t.step(9, down));
        assertFalse(t.inFlight(9));
        Sample again = ready();
        assertEquals(Step.PLAIN, t.step(10, again));
        assertEquals(Step.JUMP, t.step(11, again));
    }

    @Test
    public void aJumpThatNeverLeavesTheGroundIsAbandoned() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // the key press takes a tick or two to show up
        assertEquals(Step.WAIT, t.step(1, ready()));
        assertEquals(Step.WAIT, t.step(2, ready()));
        assertEquals(Step.PLAIN, t.step(3, ready()));
        assertFalse(t.inFlight(3));
        assertEquals(Step.PLAIN, t.step(4, ready()));
    }

    @Test
    public void guardsThatShowUpMidAirCancelTheCrit() {
        assertCancelledMidAir(s -> s.shielding = true);
        assertCancelledMidAir(s -> s.usingItem = true);
        assertCancelledMidAir(s -> s.inFluid = true);
        assertCancelledMidAir(s -> s.onClimbable = true);
        assertCancelledMidAir(s -> s.blind = true);
        assertCancelledMidAir(s -> s.riding = true);
        assertCancelledMidAir(s -> s.pathing = true);
    }

    private static void assertCancelledMidAir(java.util.function.Consumer<Sample> spoil) {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample s = falling(0);
        spoil.accept(s);
        assertEquals(Step.PLAIN, t.step(1, s));
        assertFalse(t.inFlight(1));
    }

    @Test
    public void turningTheSettingOffMidAirDropsTheJump() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample off = rising(1);
        off.enabled = false;
        assertEquals(Step.PLAIN, t.step(1, off));
        assertFalse(t.inFlight(1));
        // and no leftover "next one is plain" either
        assertEquals(Step.JUMP, t.step(2, ready()));
    }

    @Test
    public void alreadyFallingWithAFullCooldownIsAFreeCrit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.SWING, t.step(0, falling(0)));
        assertFalse(t.inFlight(0));
    }

    @Test
    public void fallingButNotReadyOrNotAllowedIsJustPlain() {
        assertEquals(Step.PLAIN, new CritTiming().step(0, falling(2)));
        Sample wet = falling(0);
        wet.inFluid = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, wet));
        Sample far = falling(0);
        far.inReach = false;
        far.aimed = false;
        assertEquals(Step.PLAIN, new CritTiming().step(0, far));
        // rising from a knockback is nothing to swing at
        assertEquals(Step.PLAIN, new CritTiming().step(0, rising(0)));
    }

    @Test
    public void aSecondCallerInTheSameTickDoesNotActTwice() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(5, ready()));
        assertEquals(Step.WAIT, t.step(5, ready()));
        // a plain answer stays plain for both
        CritTiming p = new CritTiming();
        Sample s = ready();
        s.pathing = true;
        assertEquals(Step.PLAIN, p.step(5, s));
        assertEquals(Step.PLAIN, p.step(5, s));
        // and a swing is only handed out once
        CritTiming w = new CritTiming();
        assertEquals(Step.SWING, w.step(9, falling(0)));
        assertEquals(Step.WAIT, w.step(9, falling(0)));
    }

    @Test
    public void aFlightNobodyIsSteeringAnyMoreGoesStale() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertTrue(t.inFlight(2));
        assertFalse(t.inFlight(10));
        // the next ask starts clean, jump included
        assertEquals(Step.JUMP, t.step(10, ready()));
    }

    @Test
    public void aStaleGiveUpDoesNotLeaveAPlainHitOwed() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample gone = rising(2);
        gone.usingItem = true;
        assertEquals(Step.PLAIN, t.step(1, gone));
        // nobody asked for a while, the "next one is plain" promise was about that fight, not this one
        assertEquals(Step.JUMP, t.step(20, ready()));
    }

    // ---- the opener on something that can't hit back

    // walked up to an animal: in reach, cooldown long since full, nothing in the way
    private static Sample opener() {
        Sample s = new Sample();
        s.harmless = true;
        s.ticksToFull = 0;
        return s;
    }

    private static Sample harmless(Sample s) {
        s.harmless = true;
        return s;
    }

    // jump at `start`, six ticks up with the cooldown full the whole way, crit on the seventh
    private static void assertHopsAndCrits(CritTiming t, long start) {
        assertEquals(Step.JUMP, t.step(start, opener()));
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(start + tick, harmless(rising(0))));
        }
        assertEquals(Step.SWING, t.step(start + 7, harmless(falling(0))));
        assertFalse(t.inFlight(start + 7));
    }

    @Test
    public void aHarmlessTargetGetsAHopOnAFullCooldownAndTheCritOnTheWayDown() {
        // six rising ticks with a full cooldown is past the ready-wait cap, which would have swung on the way up
        assertTrue(6 > CritTiming.MAX_READY_WAIT);
        assertNull(CritTiming.hopVeto(opener()));
        assertHopsAndCrits(new CritTiming(), 0);
    }

    @Test
    public void theSameFullCooldownOnSomethingHittingUsIsStillAPlainHit() {
        Sample s = opener();
        s.harmless = false;
        s.recentlyHurt = true;
        assertEquals("cooldown full, swing now", CritTiming.hopVeto(s));
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        // and its hop still has the cap
        CritTiming t = new CritTiming();
        t.step(0, ready());
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT; tick++) {
            assertEquals(Step.WAIT, t.step(tick, rising(0)));
        }
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 1, rising(0)));
    }

    @Test
    public void aHarmlessOpenerWaitsForThePathToLetGoAndThenHops() {
        CritTiming t = new CritTiming();
        Sample walking = opener();
        walking.pathing = true;
        assertEquals(Step.WAIT, t.step(0, walking));
        assertFalse(t.inFlight(0));
        assertEquals(Step.WAIT, t.step(1, walking));
        assertHopsAndCrits(t, 2);
    }

    @Test
    public void aPathThatNeverLetsGoGetsAPlainHitAfterTheCap() {
        CritTiming t = new CritTiming();
        Sample walking = opener();
        walking.pathing = true;
        for (int tick = 0; tick < CritTiming.MAX_OPENER_HOLD; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, walking));
        }
        assertEquals(Step.PLAIN, t.step(CritTiming.MAX_OPENER_HOLD, walking));
        // and it stays plain until the swing has gone out, no second helping
        assertEquals(Step.PLAIN, t.step(CritTiming.MAX_OPENER_HOLD + 1, walking));
    }

    @Test
    public void aHarmlessMobABitTooFarIsWalkedUpToAndThenJustHit() {
        CritTiming t = new CritTiming();
        Sample far = opener();
        far.reachGap = 2.9;
        for (int tick = 0; tick < CritTiming.MAX_OPENER_HOLD; tick++) {
            assertEquals("tick " + tick, Step.APPROACH, t.step(tick, far));
        }
        assertEquals(Step.PLAIN, t.step(CritTiming.MAX_OPENER_HOLD, far));
        // closing the gap in time is a hop
        CritTiming closes = new CritTiming();
        assertEquals(Step.APPROACH, closes.step(0, far));
        assertHopsAndCrits(closes, 1);
    }

    @Test
    public void thePathAndTheGapShareOneHold() {
        CritTiming t = new CritTiming();
        Sample walking = opener();
        walking.pathing = true;
        walking.reachGap = 2.9;
        Sample far = opener();
        far.reachGap = 2.9;
        for (int tick = 0; tick < CritTiming.MAX_OPENER_HOLD; tick++) {
            assertEquals("tick " + tick, tick < 4 ? Step.WAIT : Step.APPROACH, t.step(tick, tick < 4 ? walking : far));
        }
        assertEquals(Step.PLAIN, t.step(CritTiming.MAX_OPENER_HOLD, far));
    }

    @Test
    public void aHarmlessOpenerIsNotHeldForAnythingElse() {
        assertOpenerPlain(s -> s.normalHitKills = true);
        assertOpenerPlain(s -> s.retreatSpeed = 0.2);
        assertOpenerPlain(s -> s.unsafeFloor = true);
        assertOpenerPlain(s -> s.lowHeadroom = true);
        assertOpenerPlain(s -> s.meleeNear = CritTiming.CROWD_NO_CRIT);
        assertOpenerPlain(s -> s.health = CritTiming.LOW_HEALTH_NO_CRIT);
        assertOpenerPlain(s -> s.shielding = true);
        assertOpenerPlain(s -> s.usingItem = true);
        assertOpenerPlain(s -> s.inFluid = true);
    }

    // with and without a path in the way, the path must not turn another veto into a hold
    private static void assertOpenerPlain(java.util.function.Consumer<Sample> spoil) {
        Sample s = opener();
        spoil.accept(s);
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.pathing = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void aPathShowingUpMidHopStillGivesUpOnAHarmlessTarget() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, opener()));
        Sample walking = harmless(rising(0));
        walking.pathing = true;
        assertEquals(Step.PLAIN, t.step(1, walking));
        assertFalse(t.inFlight(1));
    }

    @Test
    public void everyAnimalOfAHuntGetsItsHopNotJustTheFirst() {
        // a hunt is all openers: we walk up to each one, so the cooldown is full every time we arrive
        CritTiming t = new CritTiming();
        assertHopsAndCrits(t, 0);
        assertHopsAndCrits(t, 60);
        assertHopsAndCrits(t, 200);
    }

    @Test
    public void aHoldThatRanOutOnOneAnimalIsNotOwedByTheNext() {
        CritTiming t = new CritTiming();
        Sample walking = opener();
        walking.pathing = true;
        for (int tick = 0; tick <= CritTiming.MAX_OPENER_HOLD; tick++) {
            t.step(tick, walking);
        }
        // the next one, after a walk: a fresh hold and then its hop
        assertEquals(Step.WAIT, t.step(100, walking));
        assertHopsAndCrits(t, 101);
        // same animal, the plain hit went out and the cooldown came back: also a fresh hold
        CritTiming same = new CritTiming();
        for (int tick = 0; tick <= CritTiming.MAX_OPENER_HOLD; tick++) {
            same.step(tick, walking);
        }
        Sample recharging = harmless(ready());
        recharging.ticksToFull = CritTiming.LEAD_TICKS + 5;
        assertEquals(Step.PLAIN, same.step(CritTiming.MAX_OPENER_HOLD + 1, recharging));
        assertEquals(Step.WAIT, same.step(CritTiming.MAX_OPENER_HOLD + 2, walking));
    }

    @Test
    public void aGiveUpDoesNotBuyAFreshHold() {
        CritTiming t = new CritTiming();
        Sample walking = opener();
        walking.pathing = true;
        for (int tick = 0; tick < CritTiming.MAX_OPENER_HOLD; tick++) {
            assertEquals(Step.WAIT, t.step(tick, walking));
        }
        // the path lets go on the last tick of the hold, the hop starts and a new path kills it
        long now = CritTiming.MAX_OPENER_HOLD;
        assertEquals(Step.JUMP, t.step(now, opener()));
        Sample up = harmless(rising(0));
        up.pathing = true;
        assertEquals(Step.PLAIN, t.step(now + 1, up));
        // the plain hit owed after a give up, and then no more waiting on this cooldown
        assertEquals(Step.PLAIN, t.step(now + 2, walking));
        assertEquals(Step.PLAIN, t.step(now + 3, walking));
    }

    @Test
    public void aHarmlessGiveUpMidRiseStillOwesAHitOnLanding() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, opener()));
        Sample gone = harmless(rising(0));
        gone.shielding = true;
        assertEquals(Step.PLAIN, t.step(1, gone));
        // still on the way up: a plain call here can't swing, so it must not spend the promise
        assertEquals(Step.PLAIN, t.step(2, harmless(rising(0))));
        // down and in reach with a full cooldown: the owed hit, not a second hop
        assertEquals(Step.PLAIN, t.step(3, opener()));
    }

    @Test
    public void swordCooldownAndAxeCooldownBothFindTheirLead() {
        // a sword is ready every 12.5 ticks, an axe every 25: the lead is in ticks, not in fractions of the cooldown
        Sample sword = ready();
        sword.ticksToFull = 6;
        assertEquals(Step.JUMP, new CritTiming().step(0, sword));
        Sample axe = ready();
        axe.ticksToFull = 18;
        assertEquals(Step.PLAIN, new CritTiming().step(0, axe));
        axe.ticksToFull = 7;
        assertEquals(Step.JUMP, new CritTiming().step(0, axe));
    }

    // a ceiling two above the feet: the hop is off the table for the whole cooldown, so the verdict has to settle on PLAIN
    // and the caller's plain swing goes out the tick the cooldown is full. no JUMP, no APPROACH, no flip in between
    @Test
    public void underARoofTheVerdictSettlesOnPlainAndTheSwingIsOwedTheTickItFills() {
        CritTiming t = new CritTiming();
        int swungAt = -1;
        double ticksToFull = 24.5;
        for (int tick = 0; tick < 40; tick++) {
            Sample s = new Sample();
            s.lowHeadroom = true;
            s.ticksToFull = ticksToFull;
            Step step = t.step(tick, s);
            assertEquals("tick " + tick, Step.PLAIN, step);
            assertEquals("low headroom", CritTiming.hopVeto(s));
            // the controller's plainSwing: a full cooldown and a PLAIN verdict is the hit
            if (ticksToFull <= 0) {
                swungAt = tick;
                break;
            }
            ticksToFull -= 1;
        }
        // 24.5 down to 0 or less is 25 steps, the swing is on tick 25 and not a tick later
        assertEquals(25, swungAt);
    }

    // ---- a weapon swap settling

    @Test
    public void aFreshSwapHoldsEverythingForATick() {
        // the reading says nearly full, but it may still be the old item's (a fist is ready after 5 ticks, an axe after 20)
        Sample s = ready();
        s.settling = true;
        CritTiming t = new CritTiming();
        assertEquals(Step.WAIT, t.step(0, s));
        assertFalse(t.inFlight(0));
        assertEquals("weapon swap settling", CritTiming.hopVeto(s));
        // a full cooldown reading on the swap tick isn't trusted either, no plain swing on it
        Sample full = landed();
        full.settling = true;
        full.recentlyHurt = true;
        assertEquals(Step.WAIT, new CritTiming().step(0, full));
        // settled: business as usual
        assertEquals(Step.JUMP, t.step(1, ready()));
    }

    @Test
    public void aSwapMidHopGivesUp() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        Sample swapped = rising(2);
        swapped.settling = true;
        assertEquals(Step.PLAIN, t.step(1, swapped));
        assertFalse(t.inFlight(1));
    }

    // ---- the opener on a hostile

    @Test
    public void aHostileThatHasNotHitUsGetsTheOpenerHop() {
        Sample s = landed();
        assertNull(CritTiming.hopVeto(s));
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, s));
        // six ticks up with the cooldown full: no cap swing on the way up, the crit is the point
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(0)));
        }
        assertEquals(Step.SWING, t.step(7, falling(0)));
    }

    @Test
    public void aHostileThatJustHitUsIsSwungAtNow() {
        Sample s = landed();
        s.recentlyHurt = true;
        assertEquals("cooldown full, swing now", CritTiming.hopVeto(s));
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void gettingHitMidOpenerHopBringsTheCapBack() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, landed()));
        Sample hit = rising(0);
        hit.recentlyHurt = true;
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT; tick++) {
            assertEquals(Step.WAIT, t.step(tick, hit));
        }
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 1, hit));
    }

    @Test
    public void aHostileOpenerABitFarIsWalkedUpToThenHit() {
        CritTiming t = new CritTiming();
        Sample far = landed();
        far.reachGap = 2.8;
        for (int tick = 0; tick < CritTiming.MAX_OPENER_HOLD; tick++) {
            assertEquals("tick " + tick, Step.APPROACH, t.step(tick, far));
        }
        assertEquals(Step.PLAIN, t.step(CritTiming.MAX_OPENER_HOLD, far));
    }

    @Test
    public void theCrowdAndLowHealthStillVetoAHostileOpener() {
        Sample crowd = landed();
        crowd.meleeNear = CritTiming.CROWD_NO_CRIT;
        assertEquals(Step.PLAIN, new CritTiming().step(0, crowd));
        Sample low = landed();
        low.health = CritTiming.LOW_HEALTH_NO_CRIT;
        assertEquals(Step.PLAIN, new CritTiming().step(0, low));
    }

    // ---- the aim

    @Test
    public void fallingWithAFullCooldownWaitsForTheCrosshair() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        for (int tick = 1; tick <= 6; tick++) {
            t.step(tick, rising(Math.max(0, 4 - tick)));
        }
        Sample off = falling(0);
        off.aimed = false;
        assertEquals(Step.WAIT, t.step(7, off));
        assertEquals(Step.WAIT, t.step(8, off));
        assertTrue(t.inFlight(8));
        // first falling tick that is on it
        assertEquals(Step.SWING, t.step(9, falling(0)));
    }

    @Test
    public void theCappedSwingWaitsForTheCrosshairToo() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample off = rising(0);
        off.aimed = false;
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT + 2; tick++) {
            assertEquals(Step.WAIT, t.step(tick, off));
        }
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 3, rising(0)));
    }

    @Test
    public void aFreeCritNotAimedYetWaits() {
        Sample s = falling(0);
        s.aimed = false;
        CritTiming t = new CritTiming();
        assertEquals(Step.WAIT, t.step(0, s));
        assertEquals(Step.SWING, t.step(1, falling(0)));
    }

    @Test
    public void pastThePlanningReachButAimedIsStillTheCrit() {
        // the plan's reach stops a bit short of the arm. a crosshair that is on the mob is the arm's say, and it wins
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        for (int tick = 1; tick <= 6; tick++) {
            t.step(tick, rising(Math.max(0, 4 - tick)));
        }
        Sample edge = falling(0);
        edge.inReach = false;
        assertEquals(Step.SWING, t.step(7, edge));
        // and the free kind too
        Sample free = falling(0);
        free.inReach = false;
        assertEquals(Step.SWING, new CritTiming().step(0, free));
    }

    @Test
    public void aCrosshairThatNeverArrivesGivesUpOnLanding() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample off = falling(0);
        off.aimed = false;
        for (int tick = 1; tick <= 8; tick++) {
            assertEquals(Step.WAIT, t.step(tick, off));
        }
        assertEquals(Step.PLAIN, t.step(9, landed()));
        assertFalse(t.inFlight(9));
    }

    // ---- the back-off and the plan

    @Test
    public void theBackOffStaysOffForTheWholeChargeWhenAHopIsPlanned() {
        // an axe, fresh off a swing: 25 ticks to full. the back-off asks every tick, and every tick the answer is stay
        for (double ticksToFull = 25; ticksToFull > 0; ticksToFull -= 1) {
            Sample s = ready();
            s.ticksToFull = ticksToFull;
            assertTrue("to full " + ticksToFull, CritTiming.hopPlanned(s));
            assertFalse(CritTiming.shouldBackOff(true, CritTiming.hopPlanned(s)));
            // the back-off's own path doesn't count against it
            s.pathing = true;
            assertTrue(CritTiming.hopPlanned(s));
            // nor does a swap settling, that's a tick or two
            s.settling = true;
            assertTrue(CritTiming.hopPlanned(s));
        }
    }

    @Test
    public void noHopPlannedMeansTheBackOffMayGo() {
        Sample roof = ready();
        roof.ticksToFull = 20;
        roof.lowHeadroom = true;
        assertFalse(CritTiming.hopPlanned(roof));
        assertTrue(CritTiming.shouldBackOff(true, CritTiming.hopPlanned(roof)));
        Sample crowd = ready();
        crowd.ticksToFull = 20;
        crowd.meleeNear = CritTiming.CROWD_NO_CRIT;
        assertFalse(CritTiming.hopPlanned(crowd));
    }

    @Test
    public void noBackOffUnderFireOrFromAnAngryEnderman() {
        assertTrue(CritTiming.noBackOff(true, false));
        assertTrue(CritTiming.noBackOff(false, true));
        assertTrue(CritTiming.noBackOff(true, true));
        assertFalse(CritTiming.noBackOff(false, false));
    }

    @Test
    public void aSwordEndsTheBackOffHardWhenTheSwingIsDue() {
        assertTrue(CritTiming.hardDropBackOff(true, true));
        assertFalse(CritTiming.hardDropBackOff(true, false));
        assertFalse(CritTiming.hardDropBackOff(false, true));
    }
}
