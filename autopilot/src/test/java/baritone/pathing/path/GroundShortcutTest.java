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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class GroundShortcutTest {

    @Test
    public void cutsStaircaseIntoDirectLineInEveryOctant() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                for (boolean swap : new boolean[]{false, true}) {
                    List<BetterBlockPos> path = staircase(sx, sz, swap, 8);
                    GroundShortcut shortcut = find(path);
                    assertNotNull(shortcut);
                    assertEquals(8, shortcut.endIndex);
                    assertEquals(center(path.get(8)), shortcut.target);
                    assertTrue(shortcut.start.distanceTo(shortcut.target) < 4 + 4 * Math.sqrt(2));
                }
            }
        }
    }

    @Test
    public void leavesStraightRunsForSprintJumpingAndExistingSmoothing() {
        assertNull(find(path(0, 0, 1, 0, 2, 0, 3, 0)));
        assertNull(find(path(0, 0, 1, 1, 2, 2, 3, 3)));
    }

    @Test
    public void canRoundOpenCornerButCannotCutThroughItsWall() {
        List<BetterBlockPos> path = path(0, 0, 1, 0, 2, 0, 2, 1, 2, 2);
        assertNotNull(find(path));
        // the shortcut's center ray misses this block on some candidates, its body doesn't
        assertNull(GroundShortcut.find(path, 0, center(path.get(0)), i -> true,
                p -> !p.equals(new BlockPos(1, 64, 1))));
    }

    @Test
    public void stopsBeforeSpecialMovementsAndHeightChanges() {
        List<BetterBlockPos> path = staircase(1, 1, false, 8);
        assertNull(GroundShortcut.find(path, 0, center(path.get(0)), i -> i != 1, p -> true));
        GroundShortcut shortRun = GroundShortcut.find(path, 0, center(path.get(0)), i -> i < 4, p -> true);
        assertEquals(4, shortRun.endIndex);
        for (int height : new int[]{63, 65, 68}) {
            List<BetterBlockPos> changed = new ArrayList<>(path);
            changed.set(2, new BetterBlockPos(2, height, 1));
            assertNull(find(changed));
        }
    }

    @Test
    public void doesNotSkipReversalsOrLargeDetours() {
        assertNull(find(path(0, 0, 1, 0, 0, 0, -1, 0)));
        List<BetterBlockPos> detour = path(0, 0, 1, 0, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3);
        GroundShortcut shortcut = find(detour);
        assertTrue(shortcut == null || shortcut.endIndex < 6);
    }

    @Test
    public void rejectsRecoveryPositionsAndShortPaths() {
        List<BetterBlockPos> path = staircase(1, 1, false, 8);
        for (Vec3 player : Arrays.asList(new Vec3(0.5, 64.2, 0.5), new Vec3(0.5, 64, 1.2),
                new Vec3(-1, 64, 0.5), new Vec3(3, 64, 0.5))) {
            assertNull(GroundShortcut.find(path, 0, player, i -> true, p -> true));
        }
        assertNull(find(path.subList(0, 2)));
        assertNull(GroundShortcut.find(path, -1, center(path.get(0)), i -> true, p -> true));
        assertNull(GroundShortcut.find(path, path.size() - 1, center(path.get(8)), i -> true, p -> true));
    }

    @Test
    public void boundsLookaheadEvenOnVeryLongPaths() {
        List<BetterBlockPos> path = staircase(1, 1, false, 1000);
        GroundShortcut shortcut = GroundShortcut.find(path, 0, center(path.get(0)), i -> {
            assertTrue(i < 8);
            return true;
        }, p -> {
            assertTrue(p.getX() <= 8);
            return true;
        });
        assertEquals(8, shortcut.endIndex);
    }

    @Test
    public void footprintChecksBothSidesOfDiagonalAndNegativeCoordinates() {
        for (int offset : new int[]{0, -10}) {
            Set<BlockPos> checked = new HashSet<>();
            Vec3 from = new Vec3(offset + 0.5, 64, offset + 0.5);
            Vec3 to = new Vec3(offset + 2.5, 64, offset + 2.5);
            assertTrue(GroundShortcut.clearSegment(from, to, p -> {
                checked.add(p);
                return true;
            }));
            for (BlockPos side : Arrays.asList(new BlockPos(offset + 1, 64, offset),
                    new BlockPos(offset, 64, offset + 1))) {
                assertTrue(checked.contains(side));
                assertFalse(GroundShortcut.clearSegment(from, to, p -> !p.equals(side)));
            }
            assertFalse(checked.contains(new BlockPos(offset + 2, 64, offset)));
        }
    }

    @Test
    public void footprintFitsOneBlockCorridorButRejectsOverhangingItsFloor() {
        assertTrue(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.5), new Vec3(6.5, 64, 0.5),
                p -> p.getZ() == 0));
        assertFalse(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.8), new Vec3(6.5, 64, 0.8),
                p -> p.getZ() == 0));
        assertFalse(GroundShortcut.clearSegment(new Vec3(0.5, 64, 0.5), new Vec3(6.5, 64, 0.5),
                p -> p.getX() != 3));
    }

    @Test
    public void rechecksRouteAndMomentumWithoutChangingEndpoint() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        Vec3 player = new Vec3(2.5, 64, 1.5);
        Vec3 target = shortcut.target;
        assertTrue(shortcut.canContinue(player, Vec3.ZERO, p -> true));
        assertFalse(shortcut.canContinue(player, Vec3.ZERO, p -> p.getX() != 6));
        assertTrue(shortcut.canContinue(player, Vec3.ZERO, p -> p.getX() != 0)); // changes behind us don't matter
        assertFalse(shortcut.canContinue(player, new Vec3(0, 0, -1), p -> p.getZ() >= 0));
        assertFalse(shortcut.canContinue(player.add(0, 1, 0), Vec3.ZERO, p -> true));
        assertFalse(shortcut.canContinue(player.add(0, 0, 2), Vec3.ZERO, p -> true));
        assertEquals(target, shortcut.target);
    }

    @Test
    public void brakesBeforeEndpointAndDoesNotFinishWithDangerousMomentum() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        Vec3 approaching = shortcut.target.add(-0.25, 0, 0);
        assertTrue(shortcut.shouldMoveForward(approaching, Vec3.ZERO));
        assertFalse(shortcut.shouldMoveForward(approaching, new Vec3(0.2, 0, 0)));
        assertFalse(shortcut.arrived(approaching, new Vec3(0.4, 0, 0)));
        assertTrue(shortcut.arrived(approaching, new Vec3(0.1, 0, 0)));
        assertTrue(shortcut.arrived(shortcut.target, Vec3.ZERO));
        assertFalse(shortcut.shouldMoveForward(shortcut.target, Vec3.ZERO));
    }

    @Test
    public void lettingGoSlidesToTheNodeNotShortOfIt() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        // sprint speed on the ground, and the slide that follows is v / (1 - 0.546) = 2.2 v, not 2.5
        double v = 0.153;
        double slide = v / (1 - SprintJump.GROUND_FRICTION);
        Vec3 velocity = shortcut.target.subtract(shortcut.start).normalize().scale(v);
        Vec3 dir = velocity.normalize();
        assertTrue(shortcut.shouldMoveForward(shortcut.target.subtract(dir.scale(slide + 0.02)), velocity));
        assertFalse(shortcut.shouldMoveForward(shortcut.target.subtract(dir.scale(slide - 0.02)), velocity));
    }

    @Test
    public void controllerReachesEndpointWithGroundInertiaAndSmallSteeringError() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                GroundShortcut shortcut = find(staircase(sx, sz, false, 8));
                Vec3 player = shortcut.start;
                Vec3 velocity = Vec3.ZERO;
                int ticks = 0;
                // simplified ground motion fixture: acceleration before movement, friction afterward.
                // this checks controller convergence; it is not a Minecraft travel-speed benchmark.
                while (!shortcut.arrived(player, velocity) && ticks++ < 100) {
                    assertTrue(shortcut.canContinue(player, velocity, p -> true));
                    if (shortcut.shouldMoveForward(player, velocity)) {
                        // sprint is on whenever W is held, so the push is the sprint one all the way in
                        double accel = GroundShortcut.PUSH;
                        Vec3 thrust = shortcut.target.subtract(player).normalize().yRot((float) Math.toRadians(ticks % 2 == 0 ? 2 : -2));
                        velocity = velocity.add(thrust.scale(accel));
                    }
                    player = player.add(velocity);
                    velocity = velocity.scale(0.546);
                }
                assertTrue("controller failed to settle", ticks < 100);
                assertTrue(player.distanceTo(shortcut.target) <= 0.3);
            }
        }
    }

    @Test
    public void segmentClearanceIsSymmetricAndIncludesStationaryFootprint() {
        Vec3 from = new Vec3(-1.2, 64, 0.7);
        Vec3 to = new Vec3(3.1, 64, 2.2);
        Set<BlockPos> forward = new HashSet<>();
        Set<BlockPos> backward = new HashSet<>();
        assertTrue(GroundShortcut.clearSegment(from, to, p -> { forward.add(p); return true; }));
        assertTrue(GroundShortcut.clearSegment(to, from, p -> { backward.add(p); return true; }));
        assertEquals(forward, backward);
        assertFalse(GroundShortcut.clearSegment(from, from, p -> !p.equals(new BlockPos(-2, 64, 0))));
    }

    @Test
    public void aBendBulgesAwayFromTheStraightLineAndTheCurveSeesWhatTheLineMisses() {
        // sprinting along +x and turning to +z. the line from here to the far end of the push cuts the inside of the turn
        Vec3 velocity = new Vec3(0.28, 0, 0);
        Vec3 look = new Vec3(0, 0, 1);
        int found = 0;
        for (double ox = 0.3; ox <= 0.7; ox += 0.05) {
            for (double oz = 0.3; oz <= 0.7; oz += 0.05) {
                Vec3 player = new Vec3(10 + ox, 64, 10 + oz);
                Vec3 end = player.add(velocity.x * 2.5, 0, velocity.z * 2.5 + 0.5);
                List<Vec3> curve = GroundShortcut.trajectory(player, velocity, look.scale(GroundShortcut.PUSH), 2);
                for (int x = 8; x <= 14; x++) {
                    for (int z = 8; z <= 14; z++) {
                        BlockPos cell = new BlockPos(x, 64, z);
                        if (GroundShortcut.clearSegment(player, end, p -> !p.equals(cell)) && !GroundShortcut.clearPath(curve, p -> !p.equals(cell))) {
                            found++;
                            // what the executor's forward gate asks: the line AND the curve
                            assertFalse(GroundShortcut.clearSegment(player, end, p -> !p.equals(cell))
                                    && GroundShortcut.clearAhead(player, velocity, look, p -> !p.equals(cell)));
                        }
                    }
                }
            }
        }
        assertTrue("starts where a cell is touched by the curve and not by the line", found > 0);
    }

    @Test
    public void theInterruptSaysWhatStoppedTheShortcut() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        Vec3 player = new Vec3(2.5, 64, 1.5);
        assertNull(shortcut.stopReason(player, Vec3.ZERO, p -> true));
        assertTrue(shortcut.stopReason(player.add(0, 1, 0), Vec3.ZERO, p -> true).contains("height"));
        assertTrue(shortcut.stopReason(player, Vec3.ZERO, p -> p.getX() != 6).contains("target"));
        assertTrue(shortcut.stopReason(player, new Vec3(0, 0, -1), p -> p.getZ() >= 0).contains("slide"));
    }

    // heading along `dir` (a unit step) towards a target 4 blocks out, at 0.3 a tick: far enough that the push and the slide reach
    // into the next cell past the target. yaw for the camera looking along dir
    private static GroundShortcut.Blocked aheadOf(int dx, int dz, Predicate<BlockPos> clear) {
        Vec3 start = new Vec3(10.5, 64, 10.5);
        Vec3 target = start.add(4 * dx, 0, 4 * dz);
        Vec3 player = start.add(3 * dx, 0, 3 * dz);
        Vec3 velocity = new Vec3(0.3 * dx, 0, 0.3 * dz);
        return GroundShortcut.blockedAhead(start, target, player, velocity, Math.atan2(-dx, dz), clear);
    }

    @Test
    public void aWallJustPastTheTargetIsNotInTheWayOfTheProjections() {
        int[][] headings = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] h : headings) {
            // the cell right after the target cell along the heading. the old projections ran into it
            BlockPos wall = new BlockPos(10 + 5 * h[0], 64, 10 + 5 * h[1]);
            assertNull("heading " + h[0] + "," + h[1], aheadOf(h[0], h[1], p -> !p.equals(wall)));
            // and without the clip it was in the way, or this proves nothing
            Vec3 player = new Vec3(10.5 + 3 * h[0], 64, 10.5 + 3 * h[1]);
            Vec3 projected = player.add(0.3 * h[0] * 2.5 + 0.5 * h[0], 0, 0.3 * h[1] * 2.5 + 0.5 * h[1]);
            assertFalse(GroundShortcut.clearSegment(player, projected, p -> !p.equals(wall)));
        }
    }

    @Test
    public void aWallBeforeTheTargetStillBlocks() {
        // the target is 4 out, the wall is the cell 3 out where the player stands next to it
        for (int[] h : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
            BlockPos wall = new BlockPos(10 + 4 * h[0], 64, 10 + 4 * h[1]);
            Vec3 start = new Vec3(10.5, 64, 10.5);
            Vec3 target = start.add(8 * h[0], 0, 8 * h[1]);
            Vec3 player = start.add(3 * h[0], 0, 3 * h[1]);
            GroundShortcut.Blocked blocked = GroundShortcut.blockedAhead(start, target, player, new Vec3(0.2 * h[0], 0, 0.2 * h[1]),
                    Math.atan2(-h[0], h[1]), p -> !p.equals(wall));
            assertNotNull(blocked);
            assertEquals(wall, blocked.cell());
            assertEquals("forward projection", blocked.check());
        }
    }

    @Test
    public void theOutsideOfABendPastTheTargetIsLeftAloneToo() {
        // a diagonal shortcut, the wall on the far side of the target cell (the outside of the bend the path makes there)
        Vec3 start = new Vec3(0.5, 64, 0.5);
        Vec3 target = new Vec3(4.5, 64, 4.5);
        Vec3 player = new Vec3(3.7, 64, 3.7);
        Vec3 velocity = new Vec3(0.3, 0, 0.3);
        double yaw = Math.atan2(-1, 1);
        BlockPos outside = new BlockPos(5, 64, 5);
        assertNull(GroundShortcut.blockedAhead(start, target, player, velocity, yaw, p -> !p.equals(outside)));
        assertNull(GroundShortcut.blockedAhead(start, target, player, velocity, yaw, p -> p.getX() != 5));
        assertNull(GroundShortcut.blockedAhead(start, target, player, velocity, yaw, p -> p.getZ() != 5));
        // the same wall between the player and the target is another thing
        assertNotNull(GroundShortcut.blockedAhead(start, new Vec3(8.5, 64, 8.5), player, velocity, yaw, p -> !p.equals(new BlockPos(4, 64, 4))));
    }

    @Test
    public void theSlideChecksStopAtTheTargetToo() {
        GroundShortcut shortcut = find(staircase(1, 1, false, 8));
        // the target is (8.5, 4.5), this is on the line and about to pass it at a speed that coasts a block and a bit beyond
        Vec3 player = new Vec3(7.7, 64, 4.1);
        Vec3 velocity = new Vec3(0.5, 0, 0.25);
        assertNull(shortcut.stopReason(player, velocity, p -> p.getX() != 9));
        // and what is in the cells up to the target still counts
        assertNotNull(shortcut.stopReason(player, velocity, p -> p.getX() != 8));
    }

    @Test
    public void clippingKeepsTheWayToTheTargetAndDropsWhatIsBeyondIt() {
        Vec3 start = new Vec3(0.5, 64, 0.5);
        Vec3 target = new Vec3(4.5, 64, 0.5);
        // short of the plane: untouched, and the same object
        Vec3 near = new Vec3(3, 64, 0.5);
        assertSame(near, GroundShortcut.clipAtTarget(start, target, new Vec3(2, 64, 0.5), near));
        // across it: cut at the target, y and the other axis follow the segment
        Vec3 cut = GroundShortcut.clipAtTarget(start, target, new Vec3(3.5, 64, 0.5), new Vec3(5.5, 64, 1.5));
        assertEquals(4.5, cut.x, 1.0E-9);
        assertEquals(1.0, cut.z, 1.0E-9);
        // already past it and going further out: nothing to test, the point we are at
        Vec3 past = new Vec3(5, 64, 0.5);
        assertEquals(past, GroundShortcut.clipAtTarget(start, target, past, new Vec3(6, 64, 0.5)));
        // past it and coming back is the way to the target
        Vec3 back = new Vec3(5, 64, 0.5);
        assertSame(back, GroundShortcut.clipAtTarget(start, target, new Vec3(6, 64, 0.5), back));
        // a whole trajectory ends where it crosses
        List<Vec3> points = Arrays.asList(new Vec3(3.5, 64, 0.5), new Vec3(4, 64, 0.5), new Vec3(5, 64, 0.5), new Vec3(5.5, 64, 0.5));
        List<Vec3> clipped = GroundShortcut.clipAtTarget(start, target, points);
        assertEquals(3, clipped.size());
        assertEquals(4.5, clipped.get(2).x, 1.0E-9);
        // no direction at all (start is the target) clips nothing
        assertSame(points.get(3), GroundShortcut.clipAtTarget(start, start, points.get(2), points.get(3)));
    }

    @Test
    public void whereToCarryOnAfterLettingGoOfAShortcut() {
        BetterBlockPos[] node = new BetterBlockPos[6];
        for (int i = 0; i < node.length; i++) {
            node[i] = new BetterBlockPos(i, 64, 0);
        }
        // movement i goes from node i to node i + 1, five of them
        List<Set<BetterBlockPos>> valid = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            valid.add(new HashSet<>(Arrays.asList(node[i], node[i + 1])));
        }
        // the shortcut ran from movement 1 to node 4: the feet on the start node, a middle node, and the end node
        assertEquals(1, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), node[1], node[4]));
        assertEquals(2, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), node[2], node[4]));
        // the node between two movements is the start of the later one
        assertEquals(3, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), node[3], node[4]));
        assertEquals(4, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), node[4], node[4]));
        // behind the range, ahead of it, off the line altogether: replan
        assertEquals(0, GroundShortcut.resumeIndex(valid::get, 0, 1, valid.size(), node[0], node[1]));
        assertEquals(-1, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), node[0], node[4]));
        assertEquals(-1, GroundShortcut.resumeIndex(valid::get, 1, 2, valid.size(), node[4], node[2]));
        assertEquals(-1, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), new BetterBlockPos(2, 64, 1), node[4]));
        // the end movement's runway cell is not where it starts, so it is not a place to carry on from
        BetterBlockPos runway = new BetterBlockPos(4, 64, 1);
        valid.get(4).add(runway);
        assertEquals(-1, GroundShortcut.resumeIndex(valid::get, 1, 4, valid.size(), runway, node[4]));
        // a shortcut that ends at the last node has no movement of its own there, the end of the last one is where it ends
        assertEquals(4, GroundShortcut.resumeIndex(valid::get, 1, 5, valid.size(), node[5], node[5]));
    }

    private static GroundShortcut find(List<BetterBlockPos> path) {
        return GroundShortcut.find(path, 0, center(path.get(0)), i -> true, p -> true);
    }

    private static List<BetterBlockPos> staircase(int sx, int sz, boolean swap, int length) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            result.add(new BetterBlockPos(sx * (swap ? i / 2 : i), 64, sz * (swap ? i : i / 2)));
        }
        return result;
    }

    private static List<BetterBlockPos> path(int... xz) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            result.add(new BetterBlockPos(xz[i], 64, xz[i + 1]));
        }
        return result;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
