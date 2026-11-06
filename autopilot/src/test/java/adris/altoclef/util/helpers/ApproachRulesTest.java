package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ApproachRules.Plan;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApproachRulesTest {
    private static final double REACH = 4;
    private static final double MAINTAIN = REACH - 1;

    // one run of the kill task against a mob that does not move: we walk in at 0.5 a tick, the child counts as started
    // every time it goes from not in the tree to in the tree. `gates` says, per tick, whether swinging is allowed yet
    private static final class Run {
        double dist;
        boolean live;
        int starts;
        int stops;
        int waited;

        Plan tick(boolean ready, boolean safe) {
            boolean inRange = dist <= REACH;
            boolean tooClose = dist < MAINTAIN;
            Plan plan = ApproachRules.plan(live, inRange, inRange, ready, safe, tooClose, waited >= ApproachRules.WAIT_TICKS);
            waited = plan == Plan.WAIT ? waited + 1 : 0;
            boolean next = plan == Plan.APPROACH || plan == Plan.KEEP_CHILD;
            if (next && !live) starts++;
            if (!next && live) stops++;
            live = next;
            // the child walks us in while it is in the tree
            if (live) dist = Math.max(0.5, dist - 0.5);
            return plan;
        }
    }

    @Test
    public void inReachWithAClearShotAndAllGatesOpenIsTheSwing() {
        assertEquals(Plan.INTERACT, ApproachRules.plan(true, true, true, true, true, false, false));
        assertEquals(Plan.INTERACT, ApproachRules.plan(false, true, true, true, true, true, false));
    }

    @Test
    public void outOfReachWalksAndKeepsTheSameChild() {
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, false, false, true, true, false, false));
        assertEquals(Plan.APPROACH, ApproachRules.plan(true, false, false, true, false, false, false));
    }

    @Test
    public void halfwayThroughAStepTheWalkingChildIsNotCutOff() {
        // in reach and close, but baritone is mid step: this used to return null and stop the child
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, true, false));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, false, false));
        // and when nothing is walking there is nothing to start
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, true, false, true, false));
    }

    @Test
    public void inReachWithAGateClosedStandsStillInsteadOfStartingTheChild() {
        // eating, a fall, the bucket: not a reason to walk. the old rule started the child on every such tick past the line
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, true, false, false));
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, true, true, false));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, false, true, false, false));
    }

    @Test
    public void noShotInReachStillApproachesToFindOneOrDigTowardsIt() {
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, true, false, true, true, false, false));
        // too close with no shot is the old shuffle, unless a step is running
        assertEquals(Plan.WANDER, ApproachRules.plan(false, true, false, true, true, true, false));
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, false, true, false, true, false));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, false, true, false, true, false));
    }

    @Test
    public void aMobThatStaysPutIsApproachedOnceNoMatterHowTheGatesFlicker() {
        Run run = new Run();
        run.dist = 12;
        // walking in, with the path unsafe to cancel every other tick and a gate closing now and then
        for (int tick = 0; tick < 40; tick++) {
            run.tick(tick % 7 != 3, tick % 2 == 0);
        }
        // and standing next to it for a long while with everything flickering
        for (int tick = 0; tick < 200; tick++) {
            run.tick(tick % 3 != 0, tick % 5 != 0);
        }
        assertEquals(1, run.starts);
        assertEquals(1, run.stops);
        assertEquals(false, run.live);
    }

    @Test
    public void everyTimeTheMobLeavesReachIsOneMoreApproach() {
        Run run = new Run();
        run.dist = 10;
        for (int tick = 0; tick < 30; tick++) {
            run.tick(true, tick % 2 == 0);
        }
        assertEquals(1, run.starts);
        for (int leave = 2; leave <= 4; leave++) {
            // knocked back, or wandered off, past reach
            run.dist = REACH + 1.5;
            for (int tick = 0; tick < 30; tick++) {
                run.tick(tick % 4 != 1, tick % 3 != 0);
            }
            assertEquals(leave, run.starts);
        }
        assertEquals(4, run.stops);
    }

    @Test
    public void aMobDriftingInsideReachNeverRestartsTheApproach() {
        // 3.2 to 4.0 and back: in reach the whole time, the child is not wanted for any of it
        Run run = new Run();
        run.dist = 3.5;
        for (int tick = 0; tick < 100; tick++) {
            run.dist = 3.2 + 0.8 * (tick % 2);
            double was = run.dist;
            run.tick(tick % 3 != 0, tick % 4 != 0);
            run.dist = was;
        }
        assertEquals(0, run.starts);
    }

    @Test
    public void aGateThatNeverOpensDoesNotFreezeUsForever() {
        // swimming next to a mob: in reach, a clear shot, but never on the ground. we stand a while, then the old rules
        // get a go (walk when there is room, shuffle when too close)
        Run run = new Run();
        run.dist = 3.5;
        int standing = 0;
        boolean moved = false;
        for (int tick = 0; tick < 3 * ApproachRules.WAIT_TICKS; tick++) {
            Plan plan = run.tick(false, true);
            if (plan == Plan.WAIT) standing++;
            if (plan == Plan.APPROACH || plan == Plan.WANDER) moved = true;
        }
        assertEquals(true, moved);
        // never more than the wait in a row before it tries something
        assertEquals(true, standing >= ApproachRules.WAIT_TICKS);
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, true, true, false, true, false, true));
        assertEquals(Plan.WANDER, ApproachRules.plan(false, true, true, false, true, true, true));
    }

    @Test
    public void aWalkingChildOfThisMobStillBeatsTheWaitCap() {
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, true, true));
    }

    @Test
    public void aKillTaskThatStoodInReachForTheCapSwingsThroughWhateverIsInTheWay() {
        // the path is alive (not safe to cancel), nothing is mid step for the child, and the cap is up
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, false, true, true, ApproachRules.Policy.KILL));
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(true, true, true, true, false, false, true, ApproachRules.Policy.KILL));
        // even when the swing is allowed on paper: a hop that waits on a fall that never comes is stuck just the same
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, true, true, true, ApproachRules.Policy.KILL));
        // not before the cap
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, true, false, true, false, ApproachRules.Policy.KILL));
    }

    @Test
    public void underFireInReachSwingsRightAwayButNotWhileEatingOrOnARim() {
        boolean waited = ApproachRules.swingWaited(false, true, false);
        assertTrue(waited);
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, false, true, waited, ApproachRules.Policy.KILL));
        // eating or the bucket has the gates shut: it stands, hurt or not
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, true, false, true, waited, ApproachRules.Policy.KILL_GATED));
        // a rim has its own way off, and no fire means the stand clock decides as before
        assertFalse(ApproachRules.swingWaited(false, true, true));
        assertFalse(ApproachRules.swingWaited(false, false, false));
        assertTrue(ApproachRules.swingWaited(true, false, true));
    }

    @Test
    public void aKillTaskNeverShufflesOffAMobInReachAndEatingIsNotForcedThrough() {
        for (boolean tooClose : new boolean[]{false, true}) {
            for (boolean safe : new boolean[]{false, true}) {
                assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, safe, tooClose, true, ApproachRules.Policy.KILL_GATED));
                assertEquals(Plan.WAIT, ApproachRules.plan(false, true, false, true, safe, true, false, ApproachRules.Policy.KILL));
            }
        }
        // out of reach is still the approach, and everybody else keeps the old shuffle
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, false, false, true, true, false, true, ApproachRules.Policy.KILL));
        assertEquals(Plan.WANDER, ApproachRules.plan(false, true, true, false, true, true, true, ApproachRules.Policy.OTHER));
    }

    // the plan flickering between INTERACT and WAIT used to put the cap back to zero every other tick. counting ticks since the
    // last swing does not care what the plan said
    @Test
    public void aFlickeringPlanStillReachesTheCap() {
        int sinceSwing = 0;
        Plan last = null;
        for (int tick = 0; tick < 3 * ApproachRules.WAIT_TICKS; tick++) {
            boolean ready = tick % 2 == 0;
            last = ApproachRules.plan(false, true, true, ready, ready, true, sinceSwing >= ApproachRules.WAIT_TICKS, ApproachRules.Policy.KILL);
            if (last == Plan.SWING_NOW) {
                assertEquals(true, tick >= ApproachRules.WAIT_TICKS);
                return;
            }
            sinceSwing++;
        }
        throw new AssertionError("never forced a swing, last plan " + last);
    }

    // the explore path the wander starts is what makes baritone unsafe to cancel on the very next tick. the old rule
    // stopped the wander there and started a new one the tick after, forever
    @Test
    public void aLiveWanderIsNotStoppedByItsOwnPath() {
        boolean wandering = false;
        int starts = 0;
        int stops = 0;
        for (int tick = 0; tick < 200; tick++) {
            // safe on the first tick, then the wander's own path is live and never lets go
            boolean safe = tick == 0;
            boolean next = ApproachRules.wanderWhenNoTarget(wandering, safe);
            if (next && !wandering) starts++;
            if (!next && wandering) stops++;
            wandering = next;
        }
        assertEquals(1, starts);
        assertEquals(0, stops);
    }

    // nothing mid-stride and nothing running: start one. a path that belongs to someone else (a lost approach child) is
    // still left alone
    @Test
    public void aFreshWanderOnlyStartsWhenSafe() {
        assertTrue(ApproachRules.wanderWhenNoTarget(false, true));
        assertFalse(ApproachRules.wanderWhenNoTarget(false, false));
        assertTrue(ApproachRules.wanderWhenNoTarget(true, false));
        assertTrue(ApproachRules.wanderWhenNoTarget(true, true));
    }

    // an enderman teleports onto us, so the walk after it is a path to wherever it just was
    @Test
    public void anEndermanOutOfReachIsWaitedForNotApproached() {
        for (ApproachRules.Policy policy : ApproachRules.Policy.values()) {
            // out of reach, no shot: the old rules walk in
            assertEquals(Plan.APPROACH, ApproachRules.plan(false, false, false, true, true, false, false, policy, false));
            assertEquals(Plan.WAIT, ApproachRules.plan(false, false, false, true, true, false, false, policy, true));
            // a walk that is already going is let go of too
            assertEquals(Plan.WAIT, ApproachRules.plan(true, false, false, true, true, false, false, policy, true));
            // and the same standing when baritone is mid step
            assertEquals(Plan.WAIT, ApproachRules.plan(false, false, false, true, false, false, false, policy, true));
            // in reach with no clear shot it is the old rules' business, a new angle
            assertEquals(ApproachRules.plan(false, true, false, true, true, false, false, policy),
                    ApproachRules.plan(false, true, false, true, true, false, false, policy, true));
        }
    }

    // in reach with a shot an angry enderman is hit right away, no waiting on a hop, a path step or the walk
    @Test
    public void anEndermanInReachIsSwungAtFirst() {
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, true, false, false, ApproachRules.Policy.KILL, true));
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, true, false, true, ApproachRules.Policy.KILL, true));
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(true, true, true, false, true, false, false, ApproachRules.Policy.KILL, true));
        // baritone mid step
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(false, true, true, true, false, false, false, ApproachRules.Policy.KILL, true));
        assertEquals(Plan.SWING_NOW, ApproachRules.plan(true, true, true, true, false, true, false, ApproachRules.Policy.KILL, true));
    }

    @Test
    public void aGatedKillStillWaitsOnAnEnderman() {
        // eating or the bucket: the gates are shut, no forced swing
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, false, false, false, ApproachRules.Policy.KILL_GATED, true));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, false, false, false, false, ApproachRules.Policy.KILL_GATED, true));
        // and out of reach it's still let come
        assertEquals(Plan.WAIT, ApproachRules.plan(false, false, true, true, false, false, false, ApproachRules.Policy.KILL, true));
    }

    @Test
    public void anythingElseInReachKeepsTheOldRules() {
        assertEquals(Plan.INTERACT, ApproachRules.plan(false, true, true, true, true, false, false, ApproachRules.Policy.KILL, false));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, false, false, ApproachRules.Policy.KILL, false));
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, true, false, false, false, ApproachRules.Policy.KILL, false));
        // not a kill task: comesToUs never gets past the out of reach wait
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, false, false, ApproachRules.Policy.OTHER, true));
    }
}
