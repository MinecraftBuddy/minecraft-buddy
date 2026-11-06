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

import java.util.ArrayList;
import java.util.List;

// movements a swimmer just got pinned on (see SwimStall). the planner doesn't model the coasting box clipping a
// neighbour, so after the give up it would happily plan the exact same move again and pin again a second later.
// for a little while that move costs extra, enough that a detour wins. one per PathingBehavior, the main thread writes
// it and every CalculationContext takes an immutable snapshot, so the pathing thread never sees it change
public final class SwimStalls {

    // long enough to get well past the spot, short enough that a corner that was just bad luck comes back
    public static final long MEMORY_MS = 30_000;
    // a diagonal swim is ~10 ticks and going around it is ~14, a traverse is 7. this outbids any of that
    public static final double PENALTY = 40;

    private final List<long[]> stalls = new ArrayList<>(); // {src, dest, expiry}
    private Object world;

    // world is anything that changes identity with the dimension (the client level does)
    public synchronized void record(BetterBlockPos src, BetterBlockPos dest, Object world, long now) {
        sameWorld(world);
        long from = BetterBlockPos.longHash(src);
        long to = BetterBlockPos.longHash(dest);
        stalls.removeIf(s -> s[0] == from && s[1] == to);
        stalls.add(new long[]{from, to, now + MEMORY_MS});
    }

    public synchronized Snapshot snapshot(Object world, long now) {
        sameWorld(world);
        stalls.removeIf(s -> s[2] <= now);
        if (stalls.isEmpty()) {
            return Snapshot.NONE;
        }
        long[] src = new long[stalls.size()];
        long[] dest = new long[stalls.size()];
        for (int i = 0; i < src.length; i++) {
            src[i] = stalls.get(i)[0];
            dest[i] = stalls.get(i)[1];
        }
        return new Snapshot(src, dest);
    }

    private void sameWorld(Object world) {
        if (world != this.world) {
            // new dimension (or a new world entirely), those coordinates mean nothing here
            stalls.clear();
            this.world = world;
        }
    }

    public static final class Snapshot {

        public static final Snapshot NONE = new Snapshot(new long[0], new long[0]);

        private final long[] src;
        private final long[] dest;

        private Snapshot(long[] src, long[] dest) {
            this.src = src;
            this.dest = dest;
        }

        // asked for every single move A* looks at, so the empty case has to be free. it nearly always is empty
        public double penalty(int x, int y, int z, int destX, int destY, int destZ) {
            if (src.length == 0) {
                return 0;
            }
            long from = BetterBlockPos.longHash(x, y, z);
            long to = BetterBlockPos.longHash(destX, destY, destZ);
            for (int i = 0; i < src.length; i++) {
                if (src[i] == from && dest[i] == to) {
                    return PENALTY;
                }
            }
            return 0;
        }
    }
}
