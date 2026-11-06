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

package baritone.behavior;

import java.util.Objects;

// a caller that cancels the search every tick and asks for the same one again every tick never lets a search do any work,
// and a cancelled search is not a failure so nobody upstream ever gives up. after a few searches in a row for the same
// thing that all died right after starting, the next cancel is held: the search keeps running, the same ask next tick
// just carries on (resume), and with no ask the cancel goes through then (expired). a changed goal or a plain stop
// costs one tick. pure, key is whatever says "the same search"
final class SearchHold {
    // cancelled searches in a row, all for the same thing, before the next cancel is held
    static final int STORM_STARTS = 3;
    // a search cancelled this many ticks after it started never got anywhere
    static final long YOUNG_TICKS = 2;

    enum Verdict {
        CANCEL,
        HOLD
    }

    private Object lastKey;
    private boolean live;
    private long startedAt;
    private int streak;
    private boolean pending;
    private long pendingAt;

    // a search went into the slot
    void started(Object key, long tick) {
        if (!Objects.equals(key, lastKey)) {
            streak = 0;
        }
        lastKey = key;
        live = true;
        startedAt = tick;
        pending = false;
    }

    // somebody wants the search in the slot dead. HOLD means leave it running and see if the same ask comes right back
    Verdict cancel(long tick) {
        if (!live) {
            return Verdict.CANCEL;
        }
        if (pending) {
            return Verdict.HOLD;
        }
        if (streak >= STORM_STARTS) {
            pending = true;
            pendingAt = tick;
            return Verdict.HOLD;
        }
        streak = tick - startedAt <= YOUNG_TICKS ? streak + 1 : 0;
        live = false;
        return Verdict.CANCEL;
    }

    // the held search is exactly what is being asked for again, so it carries on and the cancel never happened
    boolean resume(Object key) {
        if (live && pending && Objects.equals(key, lastKey)) {
            pending = false;
            return true;
        }
        return false;
    }

    // nobody asked for it by the time the tick after the cancel gets to tickPath, so the cancel goes through now
    boolean expired(long tick) {
        return live && pending && tick > pendingAt;
    }

    // the cancel that was held goes through (a different ask, expired, or any cancel that has to happen right now)
    void dropped() {
        live = false;
        pending = false;
        streak = 0;
    }

    // the search finished by itself. a cancel's own thread ending afterwards is not this, live is already false by then
    void ended() {
        if (live) {
            live = false;
            pending = false;
            streak = 0;
        }
    }

    boolean isPending() {
        return live && pending;
    }

    boolean isLive() {
        return live;
    }
}
