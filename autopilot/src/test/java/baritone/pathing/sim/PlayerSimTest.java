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

package baritone.pathing.sim;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// vanilla's numbers, held against the sim. the flat floor top is y = 64 everywhere, forward is +z at yaw 0
public class PlayerSimTest {

    private static final PlayerSim.Controls SPRINT = new PlayerSim.Controls(1, 0, false, true, 0F);
    private static final PlayerSim.Controls SPRINT_JUMP = SPRINT.withJump(true);
    private static final PlayerSim.Controls WALK = new PlayerSim.Controls(1, 0, false, false, 0F);

    private static ArrayWorld flat() {
        ArrayWorld world = new ArrayWorld(16, 80, 64);
        world.fill(0, 60, 0, 15, 63, 63, ArrayWorld.STONE);
        return world;
    }

    // a one block step whose face is at z = 12
    private static ArrayWorld stepAt12() {
        ArrayWorld world = flat();
        world.fill(0, 64, 12, 15, 64, 63, ArrayWorld.STONE);
        return world;
    }

    private static PlayerSim standing(ArrayWorld world, double z) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = 8.5;
        sim.y = 64;
        sim.z = z;
        sim.vy = -0.0784; // what gravity leaves a player who has been standing on the floor, so the first tick still lands
        sim.onGround = true;
        return sim;
    }

    private static PlayerSim sprinting(ArrayWorld world, double z) {
        PlayerSim sim = standing(world, z);
        sim.sprinting = true;
        return sim;
    }

    // holds sprint on a flat floor until the speed stops changing
    private static double steadySprintVelocity() {
        PlayerSim sim = standing(flat(), 2);
        for (int i = 0; i < 80; i++) {
            assertTrue(sim.tick(SPRINT));
        }
        return sim.vz;
    }

    @Test
    public void sprintingOnTheFlatTopsOutAt5point612BlocksASecond() {
        PlayerSim sim = standing(flat(), 2);
        double last = 0;
        for (int i = 0; i < 80; i++) {
            double before = sim.z;
            assertTrue(sim.tick(SPRINT));
            last = sim.z - before;
        }
        assertEquals(5.612 / 20, last, 1e-4);
        assertTrue(sim.sprinting);
        assertTrue(sim.onGround);
        assertFalse(sim.horizontalCollision);
        assertEquals(64, sim.y, 0);
    }

    @Test
    public void walkingTopsOutAt4point317() {
        PlayerSim sim = standing(flat(), 2);
        double last = 0;
        for (int i = 0; i < 80; i++) {
            double before = sim.z;
            assertTrue(sim.tick(WALK));
            last = sim.z - before;
        }
        assertEquals(4.317 / 20, last, 1e-4);
        assertFalse(sim.sprinting);
    }

    // Player.getSpeed() is the attribute itself, so the sprint modifier counts on the very tick the flag flips
    @Test
    public void sprintSpeedAppliesTheTickItStarts() {
        PlayerSim sim = standing(flat(), 2);
        assertTrue(sim.tick(SPRINT));
        assertTrue(sim.sprinting);
        assertEquals((0.1F * 1.3) * 0.98F, sim.z - 2, 1e-5);
        double before = sim.z, v = sim.vz;
        assertTrue(sim.tick(new PlayerSim.Controls(0, 0, false, true, 0F)));
        assertFalse(sim.sprinting);
        assertEquals(v, sim.z - before, 1e-9); // no forward, so nothing is added to the velocity it carried
    }

    // jumpFromGround and moveRelative both get the yaw Baritone aims with, the head can be pointing anywhere
    @Test
    public void sprintJumpBoostFollowsTheMoveYawNotTheViewYaw() {
        PlayerSim sim = sprinting(flat(), 2);
        assertTrue(sim.tick(new PlayerSim.Controls(1, 0, true, true, 0F, 90F)));
        assertTrue(sim.jumped);
        assertEquals(0, sim.vx, 1e-9);
        assertTrue(sim.vz > 0.15); // (0.2 boost + 0.1274 of walking) * 0.546
    }

    @Test
    public void letGoOfForwardAndSprintIsGoneThatTick() {
        PlayerSim sim = sprinting(flat(), 2);
        assertTrue(sim.tick(new PlayerSim.Controls(0, 0, false, true, 0F)));
        assertFalse(sim.sprinting);
    }

    @Test
    public void standingJumpApexAndAirTime() {
        PlayerSim sim = standing(flat(), 2);
        assertTrue(sim.tick(new PlayerSim.Controls(0, 0, true, false, 0F)));
        assertTrue(sim.jumped);
        assertFalse(sim.onGround);
        assertEquals(64.42, sim.y, 1e-6);
        double apex = sim.y;
        int ticks = 1;
        while (!sim.onGround && ticks < 40) {
            assertTrue(sim.tick(WALK.withJump(false)));
            apex = Math.max(apex, sim.y);
            ticks++;
        }
        assertEquals(65.2522, apex, 1e-3); // the 1.2522 everybody knows
        assertEquals(12, ticks); // and the 12 ticks a jump takes
        assertEquals(64, sim.y, 0);
    }

    // the same recurrence SprintJump walks with doubles by hand, from its own reading of travelInAir and jumpFromGround
    @Test
    public void flatSprintJumpMatchesTheHandRolledRecurrence() {
        double v = steadySprintVelocity();
        PlayerSim sim = sprinting(flat(), 20);
        sim.vz = v;
        double groundAccel = (0.1F * (1.0 + PlayerSim.SPRINT_MODIFIER)) * 0.98;
        double airAccel = 0.026 * 0.98;
        double s = 0, y = 0, vy = 0.42;
        double ref = v;
        boolean first = true;
        int ticks = 0;
        while (true) {
            double disp = first ? ref + 0.2 + groundAccel : ref + airAccel;
            ref = disp * (first ? 0.6 * 0.91 : 0.91);
            s += disp;
            y += vy;
            vy = (vy - 0.08) * 0.98;
            first = false;
            ticks++;
            if (y <= 0) {
                break;
            }
        }
        double start = sim.z;
        int simTicks = 0;
        do {
            assertTrue(sim.tick(SPRINT_JUMP.withJump(simTicks == 0)));
            simTicks++;
        } while (!sim.onGround && simTicks < 40);
        assertEquals(ticks, simTicks);
        assertEquals(s, sim.z - start, 2e-3);
        assertTrue(sim.sprinting);
        // 3.63 blocks: a three block gap, with the hitbox hanging 0.3 over each edge
        assertEquals(3.63, sim.z - start, 0.02);
    }

    @Test
    public void jumpTooCloseToAStepClipsItsFaceAndLosesTheSprint() {
        PlayerSim sim = sprinting(stepAt12(), 11.3);
        sim.vz = steadySprintVelocity();
        int ticks = 0;
        // the tick that touches the face still has a sliver of room to move in, which vanilla calls minor. the one after is the wall
        do {
            assertTrue(sim.tick(SPRINT_JUMP.withJump(ticks == 0)));
            ticks++;
        } while (!(sim.horizontalCollision && !sim.minorHorizontalCollision) && ticks < 20);
        assertTrue(sim.horizontalCollision && !sim.minorHorizontalCollision);
        assertTrue(sim.sprinting); // the flag only drops at the start of the next aiStep
        assertEquals(0, sim.vz, 0);
        assertTrue(sim.tick(SPRINT));
        assertFalse(sim.sprinting);
        assertTrue(sim.vz < 0.03); // all the speed it had went into the face
    }

    @Test
    public void jumpFromFarEnoughBackClearsTheStepAndKeepsSprinting() {
        PlayerSim sim = sprinting(stepAt12(), 9.5);
        sim.vz = steadySprintVelocity();
        int ticks = 0;
        do {
            assertTrue(sim.tick(SPRINT_JUMP.withJump(ticks == 0)));
            assertFalse("tick " + ticks, sim.horizontalCollision);
            ticks++;
        } while (!sim.onGround && ticks < 40);
        assertTrue(sim.onGround);
        assertEquals(65, sim.y, 1e-9);
        assertTrue(sim.z > 12 - 0.3);
        assertTrue(sim.sprinting);
    }

    // a hop up a step has a window and both ends of it are a clip: too far back and the arc comes down against the face, too
    // close and the first two moves (a sprint hop spends ~0.77 of them below the top) run into it
    @Test
    public void thereIsOneJumpWindowAndBothEndsOfItAreClips() {
        double v = steadySprintVelocity();
        double first = Double.NaN, last = Double.NaN;
        for (double z = 7.0; z < 11.65; z += 0.05) {
            PlayerSim sim = sprinting(stepAt12(), z);
            sim.vz = v;
            boolean clipped = false;
            int ticks = 0;
            do {
                assertTrue(sim.tick(SPRINT_JUMP.withJump(ticks == 0)));
                clipped |= sim.horizontalCollision && !sim.minorHorizontalCollision;
                ticks++;
            } while (!sim.onGround && ticks < 40);
            if (!clipped && sim.y == 65) {
                if (Double.isNaN(first)) {
                    first = z;
                }
                assertTrue("a hole in the window at " + z, Double.isNaN(last) || z - last < 0.06);
                last = z;
            }
        }
        // front of the box is z + 0.3 and the face is at 12
        assertEquals(9.3, first, 0.15);
        assertEquals(11.2, last, 0.15);
    }

    @Test
    public void walkingIntoAHalfSlabStepsUpWithoutAJump() {
        ArrayWorld world = flat();
        world.fill(0, 64, 12, 15, 64, 63, ArrayWorld.SLAB);
        PlayerSim sim = standing(world, 10);
        boolean collided = false;
        for (int i = 0; i < 30; i++) {
            assertTrue(sim.tick(SPRINT));
            collided |= sim.horizontalCollision;
        }
        assertFalse(collided);
        assertEquals(64.5, sim.y, 1e-9);
        assertTrue(sim.onGround);
        assertTrue(sim.sprinting);
    }

    @Test
    public void headOnIntoAWallStopsTheSprintNextTick() {
        ArrayWorld world = flat();
        world.fill(0, 64, 20, 15, 66, 22, ArrayWorld.STONE);
        PlayerSim sim = standing(world, 14);
        boolean touched = false;
        int ticks = 0;
        while (!(sim.horizontalCollision && !sim.minorHorizontalCollision) && ticks++ < 60) {
            assertTrue(sim.tick(SPRINT));
            touched |= sim.horizontalCollision && sim.minorHorizontalCollision;
        }
        // the tick that closes the last gap still moves along the line the keys point at, so it counts as a graze
        assertTrue(touched);
        assertTrue(sim.horizontalCollision && !sim.minorHorizontalCollision);
        assertTrue(sim.sprinting); // still true: the flag drops at the start of the NEXT aiStep
        assertTrue(sim.tick(SPRINT));
        assertFalse(sim.sprinting);
        assertEquals(20 - 0.3, sim.z, 1e-6);
    }

    @Test
    public void grazingAWallUnderEightDegreesIsMinorAndKeepsTheSprint() {
        ArrayWorld world = flat();
        world.fill(10, 64, 0, 15, 66, 63, ArrayWorld.STONE); // wall on the +x side, face at x = 10
        PlayerSim sim = standing(world, 2);
        sim.x = 9.69;
        // yaw -5 heads 5 degrees toward +x, into the wall
        PlayerSim.Controls graze = new PlayerSim.Controls(1, 0, false, true, -5F);
        boolean sawMinor = false;
        for (int i = 0; i < 30; i++) {
            assertTrue(sim.tick(graze));
            sawMinor |= sim.horizontalCollision && sim.minorHorizontalCollision;
        }
        assertTrue(sawMinor);
        assertTrue(sim.sprinting);
        // twenty degrees is a wall
        sim = standing(world, 2);
        sim.x = 9.69;
        PlayerSim.Controls wall = new PlayerSim.Controls(1, 0, false, true, -20F);
        boolean wasMajor = false;
        for (int i = 0; i < 30; i++) {
            assertTrue(sim.tick(wall));
            wasMajor |= sim.horizontalCollision && !sim.minorHorizontalCollision;
        }
        assertTrue(wasMajor);
    }

    @Test
    public void iceIsNotSomethingWeClaimToKnow() {
        ArrayWorld world = flat();
        world.fill(0, 63, 0, 15, 63, 63, ArrayWorld.ICE);
        PlayerSim sim = standing(world, 5);
        double z = sim.z;
        assertFalse(sim.tick(SPRINT));
        assertNotNull(sim.failure());
        assertEquals(z, sim.z, 0); // a refusal leaves the player alone
    }

    @Test
    public void copiesRunApart() {
        PlayerSim a = sprinting(flat(), 2);
        PlayerSim b = a.copy();
        assertTrue(a.tick(SPRINT_JUMP));
        assertEquals(2, b.z, 0);
        assertTrue(b.tick(SPRINT));
        assertTrue(a.y > b.y);
    }
}
