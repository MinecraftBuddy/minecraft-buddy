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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

// cells the builder let go of after standing still next to them for too long, see BuilderProcess.stallCheck. a few
// goes with a break in between, then left alone till a block near them changes. no world in here, the tests ask it straight
final class GivenUp {

    static final int EXPIRY_TICKS = 200;
    // the second break is longer. ten seconds was often just long enough to walk back and stall on it again
    static final int SECOND_EXPIRY_TICKS = 600;
    static final int MAX_ATTEMPTS = 3;
    // a block this close changing is a reason to look at a for good one again. just the cell and its neighbours,
    // with the old 5 box every block the builder put down anywhere near kept waking up the whole stuck pile
    static final int NEARBY = 1;

    private final Long2ObjectOpenHashMap<Entry> cells = new Long2ObjectOpenHashMap<>();

    private static final class Entry {
        final int x;
        final int y;
        final int z;
        long until;
        int attempts;
        // given up by the ledger (a cell the path keeps digging out), not by a stall. a neighbour changing is the
        // build going on around it, that's no reason to put it back in the path's way again
        boolean pinned;

        Entry(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // how many times this cell got let go of, this one included
    int giveUp(BetterBlockPos pos, long now) {
        Entry e = cells.computeIfAbsent(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z), k -> new Entry(pos.x, pos.y, pos.z));
        e.attempts++;
        e.until = e.attempts >= MAX_ATTEMPTS ? Long.MAX_VALUE : now + breakTicks(e.attempts);
        return e.attempts;
    }

    // straight to for good, and it stays that way till the next resume
    void forGood(BetterBlockPos pos) {
        Entry e = cells.computeIfAbsent(BetterBlockPos.serializeToLong(pos.x, pos.y, pos.z), k -> new Entry(pos.x, pos.y, pos.z));
        e.attempts = MAX_ATTEMPTS;
        e.until = Long.MAX_VALUE;
        e.pinned = true;
    }

    // how long the break after this many give ups is, for anything short of MAX_ATTEMPTS
    static int breakTicks(int attempts) {
        return attempts <= 1 ? EXPIRY_TICKS : SECOND_EXPIRY_TICKS;
    }

    boolean isGivenUp(int x, int y, int z, long now) {
        if (cells.isEmpty()) {
            return false; // the scans ask this for every cell in the box, every tick
        }
        Entry e = cells.get(BetterBlockPos.serializeToLong(x, y, z));
        return e != null && now < e.until;
    }

    // the ones still waiting out their break come back right now. true if there were any. the for good ones stay out,
    // so this runs dry after MAX_ATTEMPTS rounds of it
    boolean retryNow(long now) {
        boolean any = false;
        for (Entry e : cells.values()) {
            if (e.until != Long.MAX_VALUE && e.until > now) {
                e.until = now;
                any = true;
            }
        }
        return any;
    }

    // anything still sitting out its break that counts. the for good ones don't, they're as done as they get
    boolean anyWaiting(long now, CellFilter counts) {
        for (Entry e : cells.values()) {
            if (e.until != Long.MAX_VALUE && e.until > now && counts.test(e.x, e.y, e.z)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface CellFilter {
        boolean test(int x, int y, int z);
    }

    // a neighbour going in or coming out can be exactly what a for good one was waiting on, so it gets one more go.
    // one, not a fresh MAX_ATTEMPTS: if the change didn't help it's back to for good after a single stall
    void nearbyChanged(int x, int y, int z, long now) {
        if (cells.isEmpty()) {
            return;
        }
        for (Entry e : cells.values()) {
            if (e.until == Long.MAX_VALUE && !e.pinned && Math.abs(e.x - x) <= NEARBY && Math.abs(e.y - y) <= NEARBY && Math.abs(e.z - z) <= NEARBY) {
                e.attempts = MAX_ATTEMPTS - 1;
                e.until = now;
            }
        }
    }

    // we weren't looking for a while (the build stopped and came back), so nearbyChanged never got its chance. every for
    // good one gets the one more go a change next to it would have bought
    void unwatched(long now) {
        for (Entry e : cells.values()) {
            if (e.until == Long.MAX_VALUE && !e.pinned) {
                e.attempts = MAX_ATTEMPTS - 1;
                e.until = now;
            }
        }
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }
}
