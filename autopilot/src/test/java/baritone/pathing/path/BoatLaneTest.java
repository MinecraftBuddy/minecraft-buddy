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

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the string pulled boat lane: straight lines across open water, clearance round things, and how the controller
// copes with the result. pure, the world is a map of blocks
public class BoatLaneTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), 0, 256);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    private static final int Y = 64;

    // water at y 64 over sand at 63 for x 0..w-1, z 0..d-1, and nothing at all outside it
    private static MapWorld lake(int w, int d) {
        MapWorld world = new MapWorld();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                world.set(x, Y - 1, z, Blocks.SAND.defaultBlockState());
                world.set(x, Y, z, Blocks.WATER.defaultBlockState());
            }
        }
        return world;
    }

    private static void block(MapWorld world, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                world.set(x, Y, z, Blocks.STONE.defaultBlockState());
                world.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
            }
        }
    }

    // a path from (x, z): E is +x, S is +z, D is both, the way A* spells octile moves
    private static List<BetterBlockPos> path(int x, int z, String moves) {
        List<BetterBlockPos> out = new ArrayList<>();
        out.add(new BetterBlockPos(x, Y, z));
        for (char c : moves.toCharArray()) {
            x += c == 'E' || c == 'D' ? 1 : 0;
            z += c == 'S' || c == 'D' ? 1 : 0;
            out.add(new BetterBlockPos(x, Y, z));
        }
        return out;
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    private static BoatTrip.Lane lane(MapWorld world, List<BetterBlockPos> p) {
        return BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, p.size() - 1, null, 0);
    }

    private static double offLine(Vec3 a, Vec3 b, Vec3 p) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.abs(dx * (p.z - a.z) - dz * (p.x - a.x)) / Math.sqrt(dx * dx + dz * dz);
    }

    private static double worstOffLine(List<Vec3> pts, Vec3 a, Vec3 b) {
        double worst = 0;
        for (Vec3 p : pts) {
            worst = Math.max(worst, offLine(a, b, p));
        }
        return worst;
    }

    private static Vec3 centre(BetterBlockPos p) {
        return new Vec3(p.x + 0.5, p.y, p.z + 0.5);
    }

    // the old lane: relax every point toward its neighbours, never further than 0.6 from its own node, then chaikin
    public static List<Vec3> oldLane(List<BetterBlockPos> positions, int from, int to) {
        int n = to - from + 1;
        Vec3[] center = new Vec3[n];
        Vec3[] pts = new Vec3[n];
        for (int i = 0; i < n; i++) {
            BetterBlockPos p = positions.get(from + i);
            center[i] = new Vec3(p.x + 0.5, p.y, p.z + 0.5);
            pts[i] = center[i];
        }
        for (int round = 0; round < 40; round++) {
            for (int i = 1; i < n - 1; i++) {
                Vec3 mid = pts[i - 1].add(pts[i + 1]).scale(0.5);
                Vec3 pulled = pts[i].add(mid.subtract(pts[i]).scale(0.5));
                double dx = pulled.x - center[i].x;
                double dz = pulled.z - center[i].z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 0.6) {
                    pulled = new Vec3(center[i].x + dx / d * 0.6, center[i].y, center[i].z + dz / d * 0.6);
                }
                pts[i] = pulled;
            }
        }
        List<Vec3> out = new ArrayList<>();
        for (Vec3 p : pts) {
            out.add(p);
        }
        for (int round = 0; round < 2 && out.size() > 2; round++) {
            List<Vec3> cut = new ArrayList<>();
            cut.add(out.get(0));
            for (int i = 0; i + 1 < out.size(); i++) {
                Vec3 a = out.get(i);
                Vec3 b = out.get(i + 1);
                cut.add(a.scale(0.75).add(b.scale(0.25)));
                cut.add(a.scale(0.25).add(b.scale(0.75)));
            }
            cut.add(out.get(out.size() - 1));
            out = cut;
        }
        return out;
    }

    @Before
    public void setUp() {
        ItemFixture.boot();
    }

    @Test
    public void aStaircaseInOpenWaterIsOneStraightLine() {
        MapWorld world = lake(100, 50);
        // D E E D E D E E D ..., the order A* happens to break the ties in
        List<BetterBlockPos> p = path(10, 5, repeat("DEEDEDEED", 6) + repeat("E", 6));
        BoatTrip.Lane lane = lane(world, p);
        Vec3 a = centre(p.get(0));
        Vec3 b = centre(p.get(p.size() - 1));
        assertEquals(a.x, lane.points.get(0).x, 1e-9);
        assertEquals(a.z, lane.points.get(0).z, 1e-9);
        assertEquals(b.x, lane.points.get(lane.points.size() - 1).x, 1e-9);
        assertEquals(b.z, lane.points.get(lane.points.size() - 1).z, 1e-9);
        assertTrue("worst " + worstOffLine(lane.points, a, b), worstOffLine(lane.points, a, b) < 1e-6);
        // and the old one wobbled and bent: the path itself is a good block and more off the straight line
        double wander = 0;
        for (BetterBlockPos q : p) {
            wander = Math.max(wander, offLine(a, b, centre(q)));
        }
        assertTrue("the test path should be crooked, was " + wander, wander > 1.0);
        assertTrue(worstOffLine(oldLane(p, 0, p.size() - 1), a, b) > 0.2);
    }

    @Test
    public void everyLanePointKnowsItsNodeAndTheyOnlyGoForward() {
        MapWorld world = lake(100, 50);
        List<BetterBlockPos> p = path(10, 5, repeat("DEEDEDEED", 6));
        BoatTrip.Lane lane = lane(world, p);
        assertEquals(0, lane.node[0]);
        assertEquals(p.size() - 1, lane.node[lane.node.length - 1]);
        assertEquals(lane.points.size(), lane.node.length);
        for (int i = 1; i < lane.node.length; i++) {
            assertTrue(lane.node[i] >= lane.node[i - 1]);
        }
        // a point every quarter block at most, like the chaikin lane had, so the controller windows (counted in points) cover what they were tuned on
        for (int i = 0; i + 1 < lane.points.size(); i++) {
            double d = lane.points.get(i).distanceTo(lane.points.get(i + 1));
            assertTrue("gap " + d, d <= 0.25 + 1e-9);
        }
    }

    // does a boat's box (1.375 square, always axis aligned) centered here overlap the stone box [x0, x1+1] x [z0, z1+1]
    private static boolean hullHits(Vec3 p, int x0, int z0, int x1, int z1) {
        double h = 0.6875;
        return p.x + h > x0 && p.x - h < x1 + 1 && p.z + h > z0 && p.z - h < z1 + 1;
    }

    // lane points plus a few between each pair, the hull moves smoothly even if the lane is only points
    private static boolean laneHits(List<Vec3> lane, int x0, int z0, int x1, int z1) {
        for (int i = 0; i + 1 < lane.size(); i++) {
            for (int s = 0; s < 10; s++) {
                Vec3 p = lane.get(i).add(lane.get(i + 1).subtract(lane.get(i)).scale(s / 10.0));
                if (hullHits(p, x0, z0, x1, z1)) {
                    return true;
                }
            }
        }
        return hullHits(lane.get(lane.size() - 1), x0, z0, x1, z1);
    }

    @Test
    public void anIslandInTheWayKeepsTheLaneRoundIt() {
        MapWorld world = lake(60, 40);
        block(world, 18, 18, 21, 21);
        // A* goes round it on the north side, two cells off the shore: diagonal up, along, diagonal down
        // the z decreases, which the move letters can't spell, so the nodes are written out
        List<BetterBlockPos> p = new ArrayList<>();
        p.add(new BetterBlockPos(2, Y, 20));
        for (int i = 1; i <= 4; i++) {
            p.add(new BetterBlockPos(2 + i, Y, 20 - i));
        }
        for (int x = 7; x <= 33; x++) {
            p.add(new BetterBlockPos(x, Y, 16));
        }
        for (int i = 1; i <= 4; i++) {
            p.add(new BetterBlockPos(33 + i, Y, 16 + i));
        }
        Vec3 a = centre(p.get(0));
        Vec3 b = centre(p.get(p.size() - 1));
        assertTrue("the straight line would go through the island", hullHits(new Vec3(20, Y, 20.5), 18, 18, 21, 21));
        BoatTrip.Lane lane = lane(world, p);
        assertFalse("the lane's hull clipped the island", laneHits(lane.points, 18, 18, 21, 21));
        // it did cut the corners though: shorter than the nodes, and off the a to b chord
        double pathLen = 0;
        for (int i = 0; i + 1 < p.size(); i++) {
            pathLen += Math.sqrt(Math.pow(p.get(i).x - p.get(i + 1).x, 2) + Math.pow(p.get(i).z - p.get(i + 1).z, 2));
        }
        assertTrue("lane " + lane.length() + " path " + pathLen, lane.length() < pathLen - 0.5);
        assertTrue(worstOffLine(lane.points, a, b) > 2.0);
    }

    @Test
    public void aDiagonalPastACornerBlockDoesNotClipTheHull() {
        MapWorld world = lake(60, 40);
        block(world, 10, 6, 10, 6);
        // the straight line from the first node to the last passes through water cells only, but at z 5.83 where
        // it goes by the block the hull reaches 0.69 either side and rubs the block's corner
        List<BetterBlockPos> p = path(2, 2, repeat("E", 15) + repeat("D", 10));
        Vec3 a = centre(p.get(0));
        Vec3 b = centre(p.get(p.size() - 1));
        List<Vec3> line = new ArrayList<>();
        line.add(a);
        line.add(b);
        assertTrue("setup: the plain straight line should clip", laneHits(line, 10, 6, 10, 6));
        // and a test on the middle's cells alone says it's fine
        final BoatTrip.Cells waterOnly = (x, z) -> world.get0(x, Y, z).getBlock() == Blocks.WATER;
        assertTrue("setup: water cells alone would not notice", BoatTrip.segmentClear(waterOnly, a.x, a.z, b.x, b.z));
        assertFalse(BoatTrip.segmentClear(BoatTrip.cellsAt(world, Y), a.x, a.z, b.x, b.z));
        BoatTrip.Lane lane = lane(world, p);
        assertFalse("the lane's hull clipped the block", laneHits(lane.points, 10, 6, 10, 6));
        // and it is a real shortcut over the nodes, not just the nodes back
        assertTrue(lane.length() < 15 + 10 * Math.sqrt(2) - 1.0);
    }

    @Test
    public void aLineAlongACellEdgeAsksBothCells() {
        MapWorld world = lake(30, 30);
        block(world, 10, 10, 10, 10);
        // a line along x = 11.0 brushes the stone block's east face. cells 10 and 11 both get asked
        final List<Long> asked = new ArrayList<>();
        BoatTrip.segmentClear((x, z) -> {
            asked.add(((long) x << 32) | (z & 0xffffffffL));
            return true;
        }, 11.0, 5.5, 11.0, 8.5);
        assertTrue(asked.contains((10L << 32) | 7));
        assertTrue(asked.contains((11L << 32) | 7));
    }

    @Test
    public void aRunThatContinuesAcrossASegmentJoinIsPulledAsOne() {
        MapWorld world = lake(200, 80);
        // two A* segments end to end: the first ends on a node that is off the long line
        List<BetterBlockPos> p = path(10, 5, repeat("DEEDEDEED", 8) + repeat("EDEEDEDEE", 8));
        int join = 9 * 8;
        Vec3 a = centre(p.get(0));
        Vec3 b = centre(p.get(p.size() - 1));
        BoatTrip.Lane whole = lane(world, p);
        assertTrue(worstOffLine(whole.points, a, b) < 1e-6);
        // pulled alone, the first segment ends on its own last node: that node is not on the line to the real end
        BoatTrip.Lane first = BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, join, null, 0);
        Vec3 joinNode = centre(p.get(join));
        assertEquals(joinNode.x, first.points.get(first.points.size() - 1).x, 1e-9);
        assertTrue("setup: the join node is " + offLine(a, b, joinNode) + " off", offLine(a, b, joinNode) > 0.2);
    }

    @Test
    public void aSplicedOnRunIsPulledFromTheBoatAllTheWayToTheNewEnd() {
        MapWorld world = lake(200, 80);
        // what rebase leaves behind: the same indices, more path on the end, and the boat 40 nodes in
        List<BetterBlockPos> p = path(10, 5, repeat("DEEDEDEED", 8));
        int oldEnd = p.size() - 1;
        BetterBlockPos tail = p.get(oldEnd);
        p.addAll(path(tail.x, tail.z, repeat("EDEEDEDEE", 8)).subList(1, 73));
        int end = p.size() - 1;
        Vec3 boat = new Vec3(p.get(40).x + 0.9, Y, p.get(40).z + 0.3);
        int nearest = BoatTrip.nearestNode(p, 0, end, 38, boat.x, boat.z);
        assertTrue("nearest " + nearest, Math.abs(nearest - 40) <= 1);
        BoatTrip.Lane lane = BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, end, boat, nearest);
        Vec3 last = centre(p.get(end));
        assertEquals(last.x, lane.points.get(lane.points.size() - 1).x, 1e-9);
        assertEquals(last.z, lane.points.get(lane.points.size() - 1).z, 1e-9);
        assertEquals(end, lane.node[lane.node.length - 1]);
        assertTrue("one line from the boat across the join to the new end", worstOffLine(lane.points, boat, last) < 1e-6);
    }

    @Test
    public void aBoatThatCantSeeTheNextNodeGoesThroughTheNearestOneFirst() {
        // the boat is in the shallows with the bank beside it, so its own cell is not a boat cell
        MapWorld world = lake(80, 20);
        for (int z = 0; z < 20; z++) {
            world.set(-1, Y, z, Blocks.STONE.defaultBlockState());
        }
        List<BetterBlockPos> p = new ArrayList<>();
        for (int x = 0; x <= 60; x++) {
            p.add(new BetterBlockPos(x, Y, 10));
        }
        Vec3 boat = new Vec3(0.2, Y, 10.5);
        BoatTrip.Lane lane = BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, p.size() - 1, boat, 0);
        boolean through = false;
        for (Vec3 v : lane.points) {
            through |= Math.abs(v.x - 0.5) < 1e-6 && Math.abs(v.z - 10.5) < 1e-6;
        }
        assertTrue("went straight at node 1 from a boat that could not see it", through);
    }

    @Test
    public void aWindingCanalKeepsTheHullInTheWaterAndPullsFast() {
        MapWorld world = new MapWorld();
        for (int x = 0; x < 90; x++) {
            for (int z = 0; z < 100; z++) {
                world.set(x, Y - 1, z, Blocks.SAND.defaultBlockState());
                world.set(x, Y, z, Blocks.STONE.defaultBlockState());
                world.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
            }
        }
        // a snake: ten legs 50 long, 8 apart, the canal five wide round the middle line
        List<BetterBlockPos> p = new ArrayList<>();
        for (int leg = 0; leg < 10; leg++) {
            int z = 8 + leg * 8;
            for (int i = 0; i <= 50; i++) {
                p.add(new BetterBlockPos(leg % 2 == 0 ? 10 + i : 60 - i, Y, z));
            }
            if (leg < 9) {
                int x = leg % 2 == 0 ? 60 : 10;
                for (int dz = 1; dz < 8; dz++) {
                    p.add(new BetterBlockPos(x, Y, z + dz));
                }
            }
        }
        for (BetterBlockPos q : p) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    world.set(q.x + dx, Y, q.z + dz, Blocks.WATER.defaultBlockState());
                    world.set(q.x + dx, Y + 1, q.z + dz, Blocks.AIR.defaultBlockState());
                }
            }
        }
        long t0 = System.nanoTime();
        BoatTrip.Lane lane = lane(world, p);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("BOATLANE winding canal of " + p.size() + " nodes: " + lane.points.size() + " lane points in " + ms + " ms");
        assertTrue("took " + ms + " ms", ms < 2000);
        for (int i = 0; i + 1 < lane.points.size(); i++) {
            for (int s = 0; s < 5; s++) {
                Vec3 v = lane.points.get(i).add(lane.points.get(i + 1).subtract(lane.points.get(i)).scale(s / 5.0));
                for (int cx = -1; cx <= 1; cx += 2) {
                    for (int cz = -1; cz <= 1; cz += 2) {
                        int bx = (int) Math.floor(v.x + cx * 0.6875);
                        int bz = (int) Math.floor(v.z + cz * 0.6875);
                        assertTrue("hull in stone at " + v, world.get0(bx, Y, bz).getBlock() == Blocks.WATER);
                    }
                }
            }
        }
    }

    @Test
    public void startingFromTheBoatDrawsTheLaneFromTheBoat() {
        MapWorld world = lake(100, 50);
        List<BetterBlockPos> p = path(10, 5, repeat("DEEDEDEED", 6));
        // the boat has drifted a block and a half off the line, three nodes in
        Vec3 boat = new Vec3(15.5, Y, 10.5);
        BoatTrip.Lane lane = BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, p.size() - 1, boat, 3);
        assertEquals(boat.x, lane.points.get(0).x, 1e-9);
        assertEquals(boat.z, lane.points.get(0).z, 1e-9);
        Vec3 end = centre(p.get(p.size() - 1));
        assertEquals(end.x, lane.points.get(lane.points.size() - 1).x, 1e-9);
        assertEquals(end.z, lane.points.get(lane.points.size() - 1).z, 1e-9);
        assertTrue("one straight line from the boat to the end", worstOffLine(lane.points, boat, end) < 1e-6);
        assertEquals(3, lane.node[0]);
    }

    @Test
    public void theBankEndKeepsItsNodesUntilTheWaterIsClear() {
        // a stone bank along x < 0 at water level, land node on top of it. the first water node has the bank beside it,
        // so it is not a boat cell and nothing is pulled past it until the second
        MapWorld world = lake(80, 20);
        for (int z = 0; z < 20; z++) {
            world.set(-1, Y - 1, z, Blocks.STONE.defaultBlockState());
            world.set(-1, Y, z, Blocks.STONE.defaultBlockState());
        }
        List<BetterBlockPos> p = new ArrayList<>();
        p.add(new BetterBlockPos(-1, Y + 1, 10));
        for (int x = 0; x <= 60; x++) {
            p.add(new BetterBlockPos(x, Y, 10));
        }
        BoatTrip.Lane lane = BoatTrip.buildLane(BoatTrip.cellsAt(world, Y), p, 0, p.size() - 1, null, 0);
        boolean through = false;
        for (Vec3 v : lane.points) {
            through |= Math.abs(v.x - 0.5) < 1e-6 && Math.abs(v.z - 10.5) < 1e-6;
        }
        assertTrue("the first water node is on the lane", through);
        assertEquals(-0.5, lane.points.get(0).x, 1e-9);
    }

    // ---- the controller on a lane ----

    private static final class Drive {
        int ticks;
        boolean arrived;
        int keySwitches;
        int throttleSwitches;
        int repulls;
        double worstOffLane;
        final List<Vec3> track = new ArrayList<>();
    }

    // rows the lane with the real driver and the same boat equations it plans with, the boat reading last tick's keys
    // the way the game does
    private static Drive drive(List<Vec3> lane, BoatTrip.Clip clip) {
        return drive(lane, clip, 0, 0, false);
    }

    // a straight lane from here to there, the way the pull draws one in open water
    private static List<Vec3> lineFrom(double x, double z, Vec3 to) {
        List<Vec3> out = new ArrayList<>();
        double len = Math.sqrt((to.x - x) * (to.x - x) + (to.z - z) * (to.z - z));
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 0; i <= n; i++) {
            out.add(new Vec3(x + (to.x - x) * i / n, Y, z + (to.z - z) * i / n));
        }
        return out;
    }

    // the boat starts yawOff degrees off the lane heading and sideOff blocks to the left of it. with repull the
    // driver redraws the lane through the boat whenever BoatTrip.needsNewLane says to, like ride() does
    private static Drive drive(List<Vec3> lane, BoatTrip.Clip clip, double yawOff, double sideOff, boolean repull) {
        Drive d = new Drive();
        Vec3 a = lane.get(0);
        Vec3 b = lane.get(1);
        double heading = Math.toDegrees(Math.atan2(-(b.x - a.x), b.z - a.z));
        double len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        double[] s = {a.x + (b.z - a.z) / len * sideOff, a.z - (b.x - a.x) / len * sideOff, heading + yawOff, 0, 0, 0};
        Vec3 end = lane.get(lane.size() - 1);
        int seg = -1;
        int lastKey = 0;
        boolean lastFwd = false;
        int lastPull = 0;
        for (int tick = 0; tick < 3000; tick++) {
            double[] proj = BoatTrip.project(lane, seg, s[0], s[1]);
            seg = (int) proj[0];
            d.worstOffLane = Math.max(d.worstOffLane, proj[2]);
            d.track.add(new Vec3(s[0], Y, s[1]));
            if (repull && BoatTrip.needsNewLane(proj[2], tick - lastPull)) {
                lane = lineFrom(s[0], s[1], end);
                lastPull = tick;
                d.repulls++;
                proj = BoatTrip.project(lane, -1, s[0], s[1]);
                seg = (int) proj[0];
            }
            double speed = Math.sqrt(s[4] * s[4] + s[5] * s[5]);
            double endDist = Math.sqrt((end.x - s[0]) * (end.x - s[0]) + (end.z - s[1]) * (end.z - s[1]));
            BoatTrip.Pick pick = BoatTrip.pick(lane, seg, proj[1], s, lastKey, lastFwd, clip);
            boolean fwd = pick.fwd && endDist >= 9 * speed + 0.4;
            if (pick.key != lastKey) {
                d.keySwitches++;
            }
            if (fwd != lastFwd) {
                d.throttleSwitches++;
            }
            d.ticks = tick + 1;
            if ((endDist < 1.0 && speed < 0.08) || (endDist < 1.6 && speed < 0.02 && tick > 10)) {
                d.arrived = true;
                break;
            }
            BoatTrip.stepBoat(s, lastKey, lastFwd);
            lastKey = pick.key;
            lastFwd = fwd;
        }
        return d;
    }

    private static final BoatTrip.Clip NOTHING = (x, z) -> false;

    private static List<Vec3> straight(double length) {
        List<Vec3> lane = new ArrayList<>();
        for (double x = 0; x <= length + 1e-9; x += 0.25) {
            lane.add(new Vec3(0.5 + x, Y, 0.5));
        }
        return lane;
    }

    @Test
    public void aStraightLaneIsRowedStraightWithAFewKeyPresses() {
        Drive d = drive(straight(100), NOTHING);
        System.out.println("BOATDRIVE straight 100: " + d.ticks + " ticks, key switches " + d.keySwitches + ", throttle switches "
                + d.throttleSwitches + ", worst off " + d.worstOffLane);
        assertTrue(d.arrived);
        // on a straight line facing along it the best answer is to hold nothing but forward
        assertTrue("key switches " + d.keySwitches, d.keySwitches <= 2);
        assertTrue("off lane " + d.worstOffLane, d.worstOffLane < 0.05);
        // 100 blocks at the 0.4 cap is 250 ticks and the throttle ramps and brakes, so not much more than that
        assertTrue("ticks " + d.ticks, d.ticks < 330);
    }

    @Test
    public void aLaneAtAnAwkwardAngleDoesNotChatter() {
        // not along an axis: 100 blocks out and 37 across
        List<Vec3> lane = new ArrayList<>();
        for (int i = 0; i <= 427; i++) {
            lane.add(new Vec3(0.5 + 100.0 * i / 427, Y, 0.5 + 37.0 * i / 427));
        }
        Drive d = drive(lane, NOTHING);
        System.out.println("BOATDRIVE slanted 100x37: " + d.ticks + " ticks, key switches " + d.keySwitches + ", throttle switches "
                + d.throttleSwitches + ", worst off " + d.worstOffLane);
        assertTrue(d.arrived);
        assertTrue("key switches " + d.keySwitches, d.keySwitches <= 8);
        assertTrue("off lane " + d.worstOffLane, d.worstOffLane < 0.8);
    }

    @Test
    public void aBoatStartingOffTheLaneSettlesWithoutChatter() {
        double[][] starts = {{40, 0}, {0, 0.6}, {-60, 1.5}, {25, -1}, {90, 0.5}, {-120, 0}};
        for (double[] st : starts) {
            Drive d = drive(straight(100), NOTHING, st[0], st[1], true);
            System.out.println("BOATDRIVE off the lane (yaw " + st[0] + ", side " + st[1] + "): " + d.ticks + " ticks, key switches "
                    + d.keySwitches + ", throttle switches " + d.throttleSwitches + ", worst off " + d.worstOffLane + ", new lanes " + d.repulls
                    + ", ended at " + d.track.get(d.track.size() - 1));
            assertTrue(d.arrived);
            // a correction is a press and a release at least, a swing back the other way is two more. past about a dozen it is hunting
            assertTrue("yaw " + st[0] + " side " + st[1] + ": key switches " + d.keySwitches, d.keySwitches <= 14);
        }
    }

    @Test
    public void aBoatBesideTheLaneGetsANewLaneDrawnThroughIt() {
        // the reason for needsNewLane: 2 blocks off and exactly parallel is outside the band but every turn costs more
        // than the rollout gains, so the driver alone rows alongside the lane to the end and never gets there
        Drive fixed = drive(straight(100), NOTHING, 0, 2, true);
        System.out.println("BOATDRIVE 2 blocks beside the lane: " + fixed.ticks + " ticks, new lanes " + fixed.repulls + ", key switches " + fixed.keySwitches);
        assertTrue(fixed.arrived);
        assertEquals(1, fixed.repulls);
    }

    @Test
    public void aRightAngleBendIsTakenWithoutChatter() {
        List<Vec3> lane = new ArrayList<>();
        for (double x = 0; x < 40; x += 0.25) {
            lane.add(new Vec3(0.5 + x, Y, 0.5));
        }
        for (double z = 0; z <= 40; z += 0.25) {
            lane.add(new Vec3(40.5, Y, 0.5 + z));
        }
        Drive d = drive(lane, NOTHING);
        System.out.println("BOATDRIVE right angle: " + d.ticks + " ticks, key switches " + d.keySwitches + ", throttle switches "
                + d.throttleSwitches + ", worst off " + d.worstOffLane);
        assertTrue(d.arrived);
        assertTrue("key switches " + d.keySwitches, d.keySwitches <= 14);
    }

    @Test
    public void thePulledLaneBeatsTheOldOneOnATiedPath() {
        MapWorld world = lake(160, 80);
        // the L shape A* is allowed to pick: all the diagonals first, then straight on
        List<BetterBlockPos> p = path(5, 5, repeat("D", 30) + repeat("E", 70));
        BoatTrip.Lane pulled = lane(world, p);
        List<Vec3> old = oldLane(p, 0, p.size() - 1);
        Vec3 a = centre(p.get(0));
        Vec3 b = centre(p.get(p.size() - 1));
        Drive dn = drive(pulled.points, NOTHING);
        Drive dold = drive(old, NOTHING);
        double offNew = 0;
        for (Vec3 t : dn.track) {
            offNew = Math.max(offNew, offLine(a, b, t));
        }
        double offOld = 0;
        for (Vec3 t : dold.track) {
            offOld = Math.max(offOld, offLine(a, b, t));
        }
        System.out.println("BOATDRIVE L shape: pulled " + dn.ticks + " ticks, off the straight " + offNew + ", key switches " + dn.keySwitches
                + " | old " + dold.ticks + " ticks, off the straight " + offOld + ", key switches " + dold.keySwitches);
        assertTrue(dn.arrived);
        assertTrue(dold.arrived);
        assertTrue("pulled " + dn.ticks + " old " + dold.ticks, dn.ticks < dold.ticks);
        assertTrue("pulled " + offNew + " old " + offOld, offNew + 5 < offOld);
        assertTrue(offNew < 0.5);
    }
}
