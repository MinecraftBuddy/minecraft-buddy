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

package baritone.process;

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

// what the builder's watchdog calls getting somewhere: more of the build done than ever before, or closer to where the
// path is going than we ever were. moving, digging and blocks going in and out don't count by themselves, a loop does
// all three forever. no world in here, the tests ask it straight
final class WatchdogProgress {

    // a long build visits a lot of path ends, forgetting them all now and then is cheaper than trimming
    private static final int MAX_DESTS = 512;

    private long bestDone = -1;
    // per path end, the closest (squared) we ever got. per end and not just the last one, or two goals taking turns
    // would each count as new every time
    private final Long2DoubleOpenHashMap destBest = new Long2DoubleOpenHashMap();

    // done = how much of the build is right, dest = where the path is going (null for no path)
    boolean progressed(long done, BetterBlockPos dest, BetterBlockPos feet) {
        boolean any = false;
        if (done > bestDone) {
            bestDone = done;
            any = true;
        }
        if (dest != null) {
            long key = BetterBlockPos.serializeToLong(dest.x, dest.y, dest.z);
            double dist = dest.distSqr(feet);
            if (!destBest.containsKey(key) || dist < destBest.get(key)) {
                if (destBest.size() >= MAX_DESTS) {
                    destBest.clear();
                }
                destBest.put(key, dist);
                any = true;
            }
        }
        return any;
    }

    void reset() {
        bestDone = -1;
        destBest.clear();
    }
}
