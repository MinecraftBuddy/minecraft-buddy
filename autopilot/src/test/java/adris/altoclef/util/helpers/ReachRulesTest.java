package adris.altoclef.util.helpers;

import adris.altoclef.control.CritTiming;
import adris.altoclef.util.helpers.ApproachRules.Plan;
import baritone.behavior.look.CombatAimMath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReachRulesTest {

    private static final double EYE = 1.62;
    // altoEntityReachRange's default
    private static final double SETTING = 4;
    // a plain player's interaction range
    private static final double RANGE = ReachRules.MELEE_REACH;

    // a mob whose feet are at (dx, dy, dz) from ours, with a box w wide and h tall. our eye is 1.62 above our own feet
    private static double gap(double dx, double dy, double dz, double w, double h) {
        return ReachRules.gap(0, EYE, 0, dx - w / 2, dy, dz - w / 2, dx + w / 2, dy + h, dz + w / 2);
    }

    private static double zombie(double dx, double dy, double dz) {
        return gap(dx, dy, dz, 0.6, 1.95);
    }

    private static boolean killReach(double gap) {
        return ReachRules.killReach(gap, SETTING, RANGE);
    }

    @Test
    public void aMobInOurOwnCellHasNoGapAtAll() {
        assertEquals(0, zombie(0, 0, 0), 1e-9);
        assertEquals(true, killReach(zombie(0, 0, 0)));
    }

    @Test
    public void aBabyInOurCellIsBelowTheEyeButStillInReach() {
        // 0.975 tall, so the top of it is 0.645 under the eye
        assertEquals(0.645, gap(0, 0, 0, 0.3, 0.975), 1e-9);
        assertEquals(true, killReach(gap(0, 0, 0, 0.3, 0.975)));
    }

    @Test
    public void aMobStraightUpTheShaftIsCloserThanItsFeetSay() {
        // at +1 the eye is inside its box, at +2 the box starts 0.38 above the eye
        assertEquals(0, zombie(0, 1, 0), 1e-9);
        assertEquals(0.38, zombie(0, 2, 0), 1e-9);
        assertEquals(1.88, zombie(0, 3.5, 0), 1e-9);
        assertEquals(true, killReach(zombie(0, 3.5, 0)));
        // 4.3 up by the feet is still inside the plan's 2.7 by the eye
        assertEquals(true, killReach(zombie(0, 4.3, 0)));
        // and the far edge: the eye is 2.7 from the bottom of its box at 4.32 up
        assertEquals(false, killReach(zombie(0, 4.4, 0)));
    }

    @Test
    public void aMobStraightBelowIsFartherThanItsFeetSay() {
        // the nearest point is the top of its head, 1.95 up from its feet
        assertEquals(0.67, zombie(0, -1, 0), 1e-9);
        assertEquals(2.67, zombie(0, -3, 0), 1e-9);
        assertEquals(true, killReach(zombie(0, -3, 0)));
        // 3.5 down by the feet is 3.17 by the eye, past the swing
        assertEquals(3.17, zombie(0, -3.5, 0), 1e-9);
        assertEquals(false, killReach(zombie(0, -3.5, 0)));
    }

    @Test
    public void aSpidersWideBoxPullsItCloserThanItsFeet() {
        // 1.4 wide, 0.9 tall, feet 3.2 away: the near face is 2.5 away, and the eye is 0.72 over the top of it
        double spider = gap(3.2, 0, 0, 1.4, 0.9);
        assertEquals(Math.sqrt(2.5 * 2.5 + 0.72 * 0.72), spider, 1e-9);
        assertEquals(true, killReach(spider));
        // and a diagonal one is measured on all three axes at once
        assertEquals(Math.sqrt(2 * 2 + 1.5 * 1.5 + 0.72 * 0.72), gap(2.7, 0, 2.2, 1.4, 0.9), 1e-9);
    }

    // the kill reach is the swing's reach less a margin, never more. it used to be the feet line OR the gap inside the
    // setting (4), past what an aimed swing (interaction range, 3) will go out from
    @Test
    public void killReachIsTheSwingsReachLessTheMargin() {
        assertEquals(3.0, ReachRules.swingReach(SETTING, RANGE), 1e-9);
        double edge = 3.0 - ReachRules.KILL_REACH_MARGIN;
        assertEquals(true, killReach(edge));
        assertEquals(false, killReach(edge + 0.01));
        // a zombie 3.0 out by the feet is 2.7 by the eye: the last spot that is in reach
        assertEquals(true, killReach(zombie(3.0, 0, 0)));
        assertEquals(false, killReach(zombie(3.05, 0, 0)));
        // a setting under the interaction range wins, a longer arm (creative, a tool) is capped by the setting
        assertEquals(2.5, ReachRules.swingReach(2.5, RANGE), 1e-9);
        assertEquals(4.0, ReachRules.swingReach(SETTING, 5), 1e-9);
    }

    // the margin is there because the aimed ray to the middle of a mob is longer than the shortest gap to its box. anywhere
    // the plan calls a zombie in reach, the ray to where CombatAim settles has to enter its box inside the swing's 3
    @Test
    public void everywhereThePlanSaysInReachTheAimedRayLandsInReach() {
        double[] eye = {0, EYE, 0};
        for (double dx = 0; dx <= 3.2; dx += 0.1) {
            for (double dz = 0; dz <= 3.2; dz += 0.1) {
                for (double dy = -3; dy <= 3; dy += 0.25) {
                    double g = zombie(dx, dy, dz);
                    if (g <= 0 || !killReach(g)) {
                        continue;
                    }
                    double[] box = {dx - 0.3, dy, dz - 0.3, dx + 0.3, dy + 1.95, dz + 0.3};
                    double[] aim = CombatAimMath.preferredPoint(box, EYE);
                    float[] rot = CombatAimMath.rotationTo(eye, aim);
                    double entry = CombatAimMath.rayEntry(eye, CombatAimMath.direction(rot[0], rot[1]), box);
                    assertTrue("at " + dx + " " + dy + " " + dz + " gap " + g + " entry " + entry, entry <= RANGE);
                }
            }
        }
    }

    // feet close but out of eye reach: a chicken two down and one and a half across is 2.5 by the feet and 3.2 by the gap.
    // it is walked closer, not shuffled away from and not swung at from where the swing can't land
    @Test
    public void aShortMobFeetCloseButOutOfEyeReachIsWalkedUpTo() {
        double gap = gap(1.5, -2, 0, 0.4, 0.7);
        assertEquals(true, gap > ReachRules.MELEE_REACH);
        assertEquals(false, killReach(gap));
        boolean tooClose = Math.sqrt(1.5 * 1.5 + 2 * 2) < ReachRules.BACK_OFF_START;
        Plan plan = ApproachRules.plan(false, killReach(gap), true, true, true, tooClose, false, ApproachRules.Policy.KILL);
        assertEquals(Plan.APPROACH, plan);
    }

    // the kill task's distances have to nest or one rule undoes the next: the back-off only starts well inside where the
    // approach stops, the approach stops inside hop range, and hop range is inside swing range
    @Test
    public void theKillDistancesNest() {
        double hopGap = ReachRules.MELEE_REACH - CritTiming.JUMP_MARGIN;
        assertTrue(ReachRules.BACK_OFF_START < ReachRules.BACK_OFF_TO);
        assertTrue(ReachRules.BACK_OFF_TO <= ReachRules.KILL_APPROACH);
        // a hop is planned from inside where the swing plan starts, which is inside the swing itself
        assertTrue(hopGap < ReachRules.MELEE_REACH - ReachRules.KILL_REACH_MARGIN);
        assertTrue(ReachRules.KILL_REACH_MARGIN > 0);
        // a zombie at the approach endpoint, level, a block below or a block above, is in hop range
        for (double dy = -1; dy <= 1; dy += 1) {
            double atApproach = zombie(ReachRules.KILL_APPROACH, dy, 0);
            assertTrue("dy " + dy + " gap " + atApproach, atApproach <= hopGap);
            // and diagonally, which is the same feet distance
            double d = ReachRules.KILL_APPROACH / Math.sqrt(2);
            assertTrue(zombie(d, dy, d) <= hopGap);
            // same at the end of a back-off
            assertTrue(zombie(ReachRules.BACK_OFF_TO, dy, 0) <= hopGap);
        }
        // the back-off line itself is nowhere near the edge of anything
        assertTrue(zombie(ReachRules.BACK_OFF_START, 0, 0) < hopGap);
    }
}
