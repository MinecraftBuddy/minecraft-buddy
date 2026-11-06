/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import org.junit.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static baritone.pathing.movement.movements.ClimbJump.*;
import static org.junit.Assert.*;

public class ClimbJumpTest {

    // a ladder (or vine) that goes all the way up and down, on a wall, for the sim to poke at
    private static Shape grab(int dist, int dy, int kind, boolean sprint) {
        return new Shape(true, dist, dy, 0, kind, BELOW_CLIMB, 3, false, 0, true, 1, sprint);
    }

    private static Shape leap(int dist, int dy, int srcKind, int destKind) {
        int below = destKind == KIND_FLOOR ? BELOW_SOLID : BELOW_CLIMB;
        return new Shape(false, dist, dy, srcKind, destKind, below, destKind == KIND_FLOOR ? 0 : 3, true, 2, true, 0, false);
    }

    private static boolean ok(Shape shape) {
        return ClimbJump.lookup(shape).ok();
    }

    @Test
    public void climbSpeed() {
        // (0.2 - 0.08) * 0.98, holding space in a vine. the first tick doesn't move at all, vy was 0 going in
        ClimbJump sim = new ClimbJump(leap(2, 0, KIND_VINE, KIND_FLOOR), WALK_SPEED);
        sim.put(-0.2, 0, 0, 0, false);
        sim.tick(COAST, true);
        assertEquals(0, sim.state()[1], 1e-12);
        for (int i = 0; i < 4; i++) {
            double before = sim.state()[1];
            sim.tick(COAST, true);
            assertEquals(0.1176, sim.state()[1] - before, 1e-12);
        }
    }

    @Test
    public void slideSpeed() {
        // let go and the fall gets clamped to 0.15 a tick
        ClimbJump sim = new ClimbJump(leap(2, 0, KIND_VINE, KIND_FLOOR), WALK_SPEED);
        sim.put(-0.2, 0.9, 0, 0, false);
        for (int i = 0; i < 3; i++) {
            sim.tick(COAST, false);
        }
        double before = sim.state()[1];
        sim.tick(COAST, false);
        assertEquals(-0.15, sim.state()[1] - before, 1e-12);
    }

    @Test
    public void jumpHeight() {
        // the 1.2522 everybody knows
        ClimbJump sim = new ClimbJump(grab(2, 0, KIND_VINE, false), WALK_SPEED);
        sim.put(0, 0, 0, STAND, true);
        double top = 0;
        sim.tick(COAST, true);
        for (int i = 0; i < 20; i++) {
            top = Math.max(top, sim.state()[1]);
            sim.tick(COAST, false);
        }
        assertEquals(1.2522, top, 1e-4);
    }

    @Test
    public void ladderStopsYou() {
        // the plank is 3/16 thick on the far side of the cell, so the middle of us stops 0.0125 past the middle of the cell
        ClimbJump sim = new ClimbJump(grab(3, 0, KIND_LADDER, false), WALK_SPEED);
        sim.put(2.8, 0.2, 0.1, 0, false);
        for (int i = 0; i < 5; i++) {
            sim.tick(PUSH, false);
        }
        assertEquals(3.0125, sim.state()[0], 1e-9);
    }

    @Test
    public void sprintOnlyStartsOnTheGround() {
        ClimbJump sim = new ClimbJump(grab(2, 0, KIND_VINE, true), WALK_SPEED);
        // the model ignores the key in the air (vanilla 26.3 would not): 0.02, not 0.026
        sim.put(-1, 5, 0, 0, false);
        sim.tick(PUSH, false, true);
        assertEquals(0.02 * 0.98, sim.state()[0] + 1, 1e-12);
        // and on the ground it's the 0.13 and it takes effect the same tick
        sim.put(-1, 0, 0, STAND, true);
        sim.tick(PUSH, false, true);
        assertEquals(0.13 * 0.98, sim.state()[0] + 1, 1e-12);
    }

    @Test
    public void grabsThatWork() {
        // across a one block gap, plain jump or sprint, the plank in front or a bare vine, one up or level
        for (int kind : new int[]{KIND_LADDER, KIND_VINE}) {
            for (int dy = 0; dy <= 1; dy++) {
                assertTrue(ok(grab(2, dy, kind, false)));
                assertTrue(ok(grab(2, dy, kind, true)));
            }
        }
        // two blocks takes a sprint unless it's level with a ladder, whose plank is close enough to catch a walking jump
        assertTrue(ok(grab(3, 0, KIND_LADDER, false)));
        assertTrue(ok(grab(3, 1, KIND_LADDER, true)));
        assertFalse(ok(grab(3, 1, KIND_LADDER, false)));
        // three blocks of gap, sprinting
        assertTrue(ok(grab(4, 0, KIND_LADDER, true)));
        assertFalse(ok(grab(4, 0, KIND_LADDER, false)));
    }

    @Test
    public void grabsThatDont() {
        // up a block and three out is further than a sprint jump goes
        assertFalse(ok(grab(4, 1, KIND_VINE, false)));
        assertFalse(ok(new Shape(true, 4, 1, 0, KIND_LADDER, BELOW_AIR, 0, false, 0, true, 3, true)));
        // nothing to run up on and a ladder at the far end of a 3 gap isn't getting caught by a walk
        assertFalse(ok(new Shape(true, 4, 0, 0, KIND_LADDER, BELOW_CLIMB, 3, false, 0, true, 0, false)));
    }

    @Test
    public void grabStartsOnTheRunway() {
        Result result = ClimbJump.lookup(grab(3, 0, KIND_LADDER, true));
        assertTrue(result.ok());
        // somewhere on the floor, at the back of it
        assertTrue(result.us() >= -1.5 && result.us() <= 0.5);
    }

    @Test
    public void grabRollout() {
        // the closed loop, the way MovementClimb runs it, has to be at least as sure of itself as A* asked for
        Result result = ClimbJump.lookup(grab(3, 0, KIND_VINE, true));
        ClimbJump sim = new ClimbJump(grab(3, 0, KIND_VINE, true), WALK_SPEED);
        assertTrue(sim.rollout(result.us(), 0, 0, STAND, false) >= PLAN_ROBUST);
    }

    @Test
    public void leapsLandOneGapOut() {
        // off a ladder or a vine, hanging on the wall: a block of gap, same level or down to the longest fall we'd take
        for (int srcKind : new int[]{KIND_LADDER, KIND_VINE}) {
            for (int dy = 0; dy >= -3; dy--) {
                assertTrue(ok(leap(2, dy, srcKind, KIND_FLOOR)));
            }
            // and onto the next ladder or vine
            for (int destKind : new int[]{KIND_LADDER, KIND_VINE}) {
                assertTrue(ok(leap(2, 0, srcKind, destKind)));
                assertTrue(ok(leap(2, -1, srcKind, destKind)));
                // up is further than a climb out gets you
                assertFalse(ok(leap(2, 1, srcKind, destKind)));
            }
        }
    }

    @Test
    public void leapsAreShort() {
        // 0.15 a tick is the most we're allowed on the ladder and the model only sprints from the ground, so that's it
        assertFalse(ok(leap(3, 0, KIND_LADDER, KIND_FLOOR)));
        assertFalse(ok(leap(3, -2, KIND_LADDER, KIND_FLOOR)));
        assertFalse(ok(leap(4, -3, KIND_LADDER, KIND_FLOOR)));
        // the one exception is dropping the full three blocks, which is enough time in the air to get two blocks of gap
        assertTrue(ok(leap(3, -3, KIND_LADDER, KIND_FLOOR)));
        assertFalse(ok(leap(3, 0, KIND_VINE, KIND_VINE)));
    }

    @Test
    public void leapRollout() {
        Shape shape = leap(2, 0, KIND_LADDER, KIND_FLOOR);
        ClimbJump sim = new ClimbJump(shape, WALK_SPEED);
        // however high in the cell we are when we start, as long as it's the bottom half
        for (double fy : new double[]{0.05, 0.3, 0.5}) {
            assertTrue(sim.rollout(sim.hugU(), fy, 0, STAND, false) >= PLAN_ROBUST);
        }
        // pressed up against the plank: it's 3/16 thick and we're 0.3 either side of the middle
        assertEquals(-0.0125, sim.hugU(), 1e-12);
    }

    @Test
    public void pendingShapesSayNothing() throws Exception {
        // the A* side of the table: ask about something nobody has worked out and get told no, and then yes
        Shape shape = new Shape(true, 2, 1, 0, KIND_VINE, BELOW_AIR, 1, false, 0, false, 2, false);
        ClimbJump.Result first = ClimbJump.lookupIfKnown(shape);
        if (first == null) {
            // the fill runs on the common pool, so wait for that to go idle instead of guessing how long a busy machine takes
            assertTrue("the fill never finished", ForkJoinPool.commonPool().awaitQuiescence(120, TimeUnit.SECONDS));
            first = ClimbJump.lookupIfKnown(shape);
        }
        assertNotNull(first);
        assertSame(first, ClimbJump.lookupIfKnown(shape));
    }
}
