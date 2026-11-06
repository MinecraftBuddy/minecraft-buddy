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

package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

// movements the executor gave up on, timed out or came back FAILED. without this the replan is the same search from the
// same spot, so it picks the same edge, which fails the same way, forever. same snapshot pattern as SwimStalls, but it
// escalates: once can be bad luck, three times is the planner being wrong about this edge and it should go around.
// never infinite, if it's the only way in then it's still the only way in
public final class FailedMoves {

    // same as SwimStalls, counted from the last failure so a loop keeps it alive
    public static final long MEMORY_MS = 30_000;
    public static final double FIRST = 40;
    public static final double SECOND = 400;
    public static final double THIRD = 2000;

    private final List<long[]> fails = new ArrayList<>(); // {src, dest, count, expiry}
    private Object world;

    public synchronized void record(BetterBlockPos src, BetterBlockPos dest, Object world, long now) {
        sameWorld(world);
        long from = BetterBlockPos.longHash(src);
        long to = BetterBlockPos.longHash(dest);
        long[] mine = null;
        for (long[] f : fails) {
            if (f[0] != from) {
                continue;
            }
            if (f[1] == to) {
                // an expired entry still sitting here (nobody snapshotted since) starts over, it had its 30s
                f[2] = f[3] <= now ? 1 : f[2] + 1;
                mine = f;
            } else if (f[3] > now) {
                // the planner rotates through the exits of a cell that keeps failing, and each one's timer ran out
                // while the others took their turn. a cell that keeps failing keeps all its exits warm
                f[3] = now + MEMORY_MS;
            }
        }
        if (mine == null) {
            fails.add(new long[]{from, to, 1, now + MEMORY_MS});
        } else {
            mine[3] = now + MEMORY_MS;
        }
    }

    // how many different moves out of src are failing right now. two or more means it's the cell, not the edge
    public synchronized int exitsFrom(BetterBlockPos src, Object world, long now) {
        sameWorld(world);
        long from = BetterBlockPos.longHash(src);
        int n = 0;
        for (long[] f : fails) {
            if (f[0] == from && f[3] > now) {
                n++;
            }
        }
        return n;
    }

    public synchronized Snapshot snapshot(Object world, long now) {
        sameWorld(world);
        fails.removeIf(f -> f[3] <= now);
        if (fails.isEmpty()) {
            return Snapshot.NONE;
        }
        long[] src = new long[fails.size()];
        long[] dest = new long[fails.size()];
        double[] cost = new double[fails.size()];
        for (int i = 0; i < src.length; i++) {
            long[] f = fails.get(i);
            src[i] = f[0];
            dest[i] = f[1];
            cost[i] = penaltyFor(f[2]);
        }
        return new Snapshot(src, dest, cost);
    }

    // ladders and vines (MovementHelper.isClimbable), plus scaffolding and cave vines, which you climb the same way.
    // PathExecutor skips recording a timed out edge that touches one of these
    public static boolean climbable(Block block) {
        return MovementHelper.isClimbable(block) || block == Blocks.SCAFFOLDING || block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT;
    }

    // only the timeout gets let off. swaying on the rungs runs the clock out, but a climb that says UNREACHABLE or FAILED
    // means it really can't, and forgetting those let A* send us up the same broken ladder forever
    public static boolean forgives(boolean timedOut, boolean climbEdge) {
        return timedOut && climbEdge;
    }

    static double penaltyFor(long count) {
        return count >= 3 ? THIRD : count == 2 ? SECOND : FIRST;
    }

    private void sameWorld(Object world) {
        if (world != this.world) {
            fails.clear();
            this.world = world;
        }
    }

    public static final class Snapshot {

        public static final Snapshot NONE = new Snapshot(new long[0], new long[0], new double[0]);

        private final long[] src;
        private final long[] dest;
        private final double[] cost;

        private Snapshot(long[] src, long[] dest, double[] cost) {
            this.src = src;
            this.dest = dest;
            this.cost = cost;
        }

        // every move A* looks at asks this, same deal as SwimStalls, the empty case is free
        public double penalty(int x, int y, int z, int destX, int destY, int destZ) {
            if (src.length == 0) {
                return 0;
            }
            long from = BetterBlockPos.longHash(x, y, z);
            long to = BetterBlockPos.longHash(destX, destY, destZ);
            for (int i = 0; i < src.length; i++) {
                if (src[i] == from && dest[i] == to) {
                    return cost[i];
                }
            }
            return 0;
        }
    }
}
