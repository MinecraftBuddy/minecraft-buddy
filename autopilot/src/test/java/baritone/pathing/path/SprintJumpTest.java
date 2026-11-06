/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import org.junit.Test;

import static org.junit.Assert.*;

public class SprintJumpTest {

    // steady state ground sprint: v = (v + 0.13 * 0.98) * 0.546
    private static final double ACCEL = 0.13 * 0.98, SPRINT = ACCEL * SprintJump.GROUND_FRICTION / (1 - SprintJump.GROUND_FRICTION);

    private static Double plan(double... floors) {
        return new SprintJump(floors, 1, ACCEL).plan(0, SPRINT);
    }

    @Test
    public void fullSendOnFlatGround() {
        SprintJump jump = new SprintJump(new double[]{0, 0, 0, 0, 0, 0}, 1, ACCEL);
        assertTrue(jump.safe(jump.jump(0, SPRINT, Double.NaN)));
        assertTrue(Double.isNaN(plan(0, 0, 0, 0, 0, 0)));
        assertNull(plan(0, 0, 0)); // lands past the runway
        assertNotNull(new SprintJump(new double[]{0, 0, 0, 0, 0}, Math.sqrt(2), ACCEL).plan(0, SPRINT));
    }

    @Test
    public void stepsAndHills() {
        assertTrue(Double.isNaN(plan(0, 0, 1, 1, 1, 1)));
        assertNull(plan(0, 0, 1, 2, 2, 2)); // two steps in a row is a wall
        assertNull(plan(0, -2, -2, -2, -2, -2, -2)); // 1.25 up then 2 down is past the 3 block safe fall
    }

    @Test
    public void brakesOntoTheFirstStepOfAHill() {
        SprintJump jump = new SprintJump(new double[]{0, 0, -1, -1, -2, -2, -2, -2}, 1, ACCEL);
        double target = jump.plan(0, SPRINT);
        assertFalse(Double.isNaN(target));
        assertEquals(-1, jump.jump(0, SPRINT, target).y(), 1e-9);
    }

    @Test
    public void setsUpTheNextHopToPopAStep() {
        // full send clips the step face and landing right in front of it leaves nothing to pop it with, so brake onto
        // cell 2 and come down still moving, from where the next hop clears the step
        SprintJump jump = new SprintJump(new double[]{0, 0, 0, 0, 1, 1, 1, 1, 1, 1}, 1, ACCEL);
        assertNull(jump.jump(0, SPRINT, Double.NaN));
        assertEquals(2, jump.plan(0, SPRINT), 1e-9);
        SprintJump.Landing landing = jump.jump(0, SPRINT, 2);
        SprintJump next = new SprintJump(new double[]{0, 0, 1, 1, 1, 1, 1, 1}, 1, ACCEL);
        assertTrue(next.safe(next.jump(landing.s() - 2, landing.v(), Double.NaN)));
    }

    // a flat parkour over a gap: the takeoff cell, dist - 1 cells of nothing, the landing and `after` more cells of floor
    private static SprintJump hop(int dist, int after) {
        double[] floors = new double[dist + 1 + after];
        for (int k = 1; k < dist; k++) {
            floors[k] = SprintJump.NO_FLOOR;
        }
        SprintJump jump = new SprintJump(floors, 1, ACCEL);
        jump.hop = true;
        return jump;
    }

    // every start the executor could hand over: anywhere in the takeoff cell, at the speeds a sprinter enters it with and
    // the slower ones (the sprint was only just dropped, a walking pace, standing). for each one the plan either says no or
    // it lands on the floor on the far side with the speed off by 15% either way, which is the margin the real thing gets
    @Test
    public void aHopEitherSaysNoOrLandsOnTheFarSide() {
        for (int dist = 2; dist <= 3; dist++) {
            for (int after = 0; after <= 4; after++) {
                SprintJump jump = hop(dist, after);
                for (double v : new double[]{0, 0.07, 0.118, 0.137, SPRINT}) {
                    for (double s = -0.5; s <= 0.5; s += 0.05) {
                        Double target = jump.plan(s, v);
                        if (target == null) {
                            continue;
                        }
                        for (double margin : new double[]{0.85, 1, 1.15}) {
                            SprintJump.Landing l = jump.jump(s, v * margin, target);
                            String at = "dist " + dist + " after " + after + " s " + s + " v " + v + " x" + margin;
                            assertNotNull(at, l);
                            assertTrue(at + " landed in the gap or short, s " + l.s(), Math.round(l.s()) >= dist);
                            assertTrue(at + " came down off the runway, s " + l.s(), Math.round(l.s()) < jump.floors.length);
                            assertEquals(at, 0, l.y(), 1e-9);
                            assertEquals(at + " 12 ticks is how long a flat hop is up", 12, l.ticks());
                            assertTrue(at, jump.safe(l));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aSprintingEntryGetsItsHopWheneverTheRunwayContinues() {
        for (int dist = 2; dist <= 3; dist++) {
            SprintJump jump = hop(dist, 2);
            for (double s = -0.5; s <= 0.5; s += 0.05) {
                assertNotNull("dist " + dist + " s " + s, jump.plan(s, SPRINT));
            }
        }
    }

    @Test
    public void oneCellOfFloorPastTheLandingIsEnoughForASprintingEntry() {
        // hopPrice charges a braked hop for exactly this much runway, so the sim has to agree it works from anywhere in the cell
        for (int dist = 2; dist <= 3; dist++) {
            SprintJump jump = hop(dist, 1);
            for (double s = -0.5; s <= 0.5; s += 0.05) {
                assertNotNull("dist " + dist + " s " + s, jump.plan(s, SPRINT));
            }
        }
    }

    @Test
    public void aHopWithNothingToStopOnAfterTheLandingStaysAWalk() {
        // the slide after landing would end up past the last cell we know about, and that is a wall as far as the sim goes
        for (int dist = 2; dist <= 3; dist++) {
            for (double s = -0.5; s <= 0.5; s += 0.05) {
                assertNull("dist " + dist + " s " + s, hop(dist, 0).plan(s, SPRINT));
            }
        }
    }

    @Test
    public void aShortGapBrakesInsteadOfFlyingPastTheLanding() {
        // dist 2 over one block with a single cell of floor after it: full send lands at 3, past the end of the world
        SprintJump jump = hop(2, 1);
        assertTrue(Double.isNaN(hop(2, 3).plan(0, SPRINT))); // with room it just goes
        Double target = jump.plan(0, SPRINT);
        assertNotNull(target);
        assertFalse(Double.isNaN(target));
        SprintJump.Landing l = jump.jump(0, SPRINT, target);
        assertEquals(2, l.s(), 0.5);
    }

    @Test
    public void aHopFliesTwelveTicksLikeTheGameDoes() {
        SprintJump.Landing l = hop(3, 4).jump(0, SPRINT, Double.NaN);
        assertEquals(12, l.ticks());
        assertEquals(0, l.y(), 1e-9);
        assertEquals(1.25, l.apex(), 0.01); // the 0.42 jump tops out at 1.2522
    }
}
