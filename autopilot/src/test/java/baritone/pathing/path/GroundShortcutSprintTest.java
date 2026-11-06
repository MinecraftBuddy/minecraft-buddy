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

package baritone.pathing.path;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the ground shortcut keeps the sprint on whenever W is held, so this is the real player (PlayerSim) on a flat floor driven by
// the executor's own rule: steer at the target, W while shouldMoveForward says so, sprint whenever W is down
public class GroundShortcutSprintTest {

    private static final int OFFSET = 20;
    // a fully sprinting player on the flat, blocks per tick
    private static final double FULL_SPRINT = 5.612 / 20;

    private enum End {ARRIVED, CANCELLED, STALLED}

    private static ArrayWorld floor() {
        ArrayWorld world = new ArrayWorld(48, 80, 48);
        world.fill(0, 60, 0, 47, 63, 47, ArrayWorld.STONE);
        return world;
    }

    private static List<BetterBlockPos> staircase(int sx, int sz, boolean swap, int length) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            result.add(new BetterBlockPos(OFFSET + sx * (swap ? i / 2 : i), 64, OFFSET + sz * (swap ? i : i / 2)));
        }
        return result;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    // a wall two cells past the target along the way we are going, across the width of the room. the target is a cell centre, so
    // its face is a block and a half out: anything that gets there has overshot by more than the controller ever means to
    private static void wallBeyond(ArrayWorld world, GroundShortcut shortcut) {
        Vec3 heading = shortcut.target.subtract(shortcut.start);
        BlockPos cell = BlockPos.containing(shortcut.target);
        if (Math.abs(heading.x) >= Math.abs(heading.z)) {
            int x = cell.getX() + 2 * (int) Math.signum(heading.x);
            world.fill(x, 64, cell.getZ() - 4, x, 66, cell.getZ() + 4, ArrayWorld.STONE);
        } else {
            int z = cell.getZ() + 2 * (int) Math.signum(heading.z);
            world.fill(cell.getX() - 4, 64, z, cell.getX() + 4, 66, z, ArrayWorld.STONE);
        }
    }

    private static float yawTo(PlayerSim sim, Vec3 target) {
        return (float) Math.toDegrees(Math.atan2(-(target.x - sim.x), target.z - sim.z));
    }

    // the controller of tickGroundShortcut with the rotation settled: the shortcut ends in arrival or in canContinue saying no
    // (which the executor turns into a cancel and the old movement takes over), and neither is the movement timing out
    private static End run(PlayerSim sim, GroundShortcut shortcut, double yawError, int budget, int[] ticksOut) {
        int ticks = 0;
        while (ticks < budget) {
            Vec3 player = new Vec3(sim.x, sim.y, sim.z);
            Vec3 velocity = new Vec3(sim.vx, 0, sim.vz);
            if (shortcut.arrived(player, velocity)) {
                ticksOut[0] = ticks;
                return End.ARRIVED;
            }
            if (!shortcut.canContinue(player, velocity, p -> true)) {
                ticksOut[0] = ticks;
                return End.CANCELLED;
            }
            boolean forward = shortcut.shouldMoveForward(player, velocity);
            float yaw = yawTo(sim, shortcut.target) + (float) yawError;
            assertTrue(sim.tick(new PlayerSim.Controls(forward ? 1 : 0, 0, false, forward, yaw)));
            assertFalse("hit a wall after " + ticks + " ticks", sim.horizontalCollision);
            ticks++;
        }
        ticksOut[0] = ticks;
        return End.STALLED;
    }

    private static PlayerSim startAt(ArrayWorld world, GroundShortcut shortcut, double speed) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = shortcut.start.x;
        sim.y = 64;
        sim.z = shortcut.start.z;
        sim.vy = -0.0784;
        sim.onGround = true;
        Vec3 heading = shortcut.target.subtract(shortcut.start).normalize();
        sim.vx = heading.x * speed;
        sim.vz = heading.z * speed;
        sim.sprinting = speed > 0.2;
        return sim;
    }

    @Test
    public void sprintingAllTheWayInStillArrivesFromEveryStartSpeed() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                for (boolean swap : new boolean[]{false, true}) {
                    for (int length : new int[]{3, 4, 6, 8}) {
                        for (double speed : new double[]{0, 0.12, 0.2, FULL_SPRINT}) {
                            List<BetterBlockPos> path = staircase(sx, sz, swap, length);
                            GroundShortcut shortcut = GroundShortcut.find(path, 0, center(path.get(0)), i -> true, p -> true);
                            assertNotNull(shortcut);
                            ArrayWorld world = floor();
                            wallBeyond(world, shortcut);
                            PlayerSim sim = startAt(world, shortcut, speed);
                            int[] ticks = new int[1];
                            End end = run(sim, shortcut, 0, 200, ticks);
                            String what = "length " + length + " speed " + speed + " octant " + sx + "," + sz + "," + swap + ": " + end + " after " + ticks[0];
                            assertTrue(what, end == End.ARRIVED);
                            // five ticks a block is a walk with a stop at the end, anything slower is the controller dithering
                            assertTrue(what, ticks[0] <= shortcut.start.distanceTo(shortcut.target) * 5 + 20);
                            assertTrue(what, new Vec3(sim.x, sim.y, sim.z).distanceTo(shortcut.target) <= 0.3);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aSteeringErrorEndsInArrivalOrACleanCancelNeverAStall() {
        for (double error : new double[]{-9, -4, 4, 9}) {
            for (double speed : new double[]{0, 0.2, FULL_SPRINT}) {
                List<BetterBlockPos> path = staircase(1, 1, false, 8);
                GroundShortcut shortcut = GroundShortcut.find(path, 0, center(path.get(0)), i -> true, p -> true);
                assertNotNull(shortcut);
                ArrayWorld world = floor();
                wallBeyond(world, shortcut);
                int[] ticks = new int[1];
                End end = run(startAt(world, shortcut, speed), shortcut, error, 200, ticks);
                assertTrue("error " + error + " speed " + speed + ": " + end + " after " + ticks[0], end != End.STALLED);
                // a few degrees of aim error is nothing to give up over
                assertTrue("error " + error + " speed " + speed + ": " + end, Math.abs(error) > 4 || end == End.ARRIVED);
            }
        }
    }

    @Test
    public void goingInFastWithTheTargetCloserThanTheSlideEndsInArrivalOrACleanCancel() {
        // already past the point where letting go is enough: the slide carries us over the node. W is off from the first tick
        for (double behind : new double[]{0.15, 0.3, 0.45, 0.6}) {
            for (double error : new double[]{0, 6, -6}) {
                List<BetterBlockPos> path = staircase(1, 1, false, 8);
                GroundShortcut shortcut = GroundShortcut.find(path, 0, center(path.get(0)), i -> true, p -> true);
                assertNotNull(shortcut);
                Vec3 heading = shortcut.target.subtract(shortcut.start).normalize();
                ArrayWorld world = floor();
                wallBeyond(world, shortcut);
                PlayerSim sim = startAt(world, shortcut, FULL_SPRINT);
                sim.x = shortcut.target.x - heading.x * behind;
                sim.z = shortcut.target.z - heading.z * behind;
                int[] ticks = new int[1];
                End end = run(sim, shortcut, error, 120, ticks);
                assertTrue("behind " + behind + " error " + error + ": " + end + " after " + ticks[0], end != End.STALLED);
                assertTrue("behind " + behind + ": " + end, error != 0 || end == End.ARRIVED);
            }
        }
    }
}
