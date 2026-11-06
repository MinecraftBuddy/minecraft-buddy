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

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

// what TorchProcess remembers between ticks. cells are BlockPos.asLong, times are player ticks. pure on purpose, see
// TorchMemoryTest
final class TorchMemory {

    // one torch a second is plenty, and it gives the light engine time to catch up before the next scan
    static final int PLACE_COOLDOWN = 20;
    // a click is judged this long after it went out. the client draws the torch straight away and only takes it back
    // when the server says no, so looking too early calls a rejected torch a success. a second covers most pings
    static final int CHECK_DELAY = 20;
    // a cell we just tried stays off the list for a bit. the light update can lag the torch, and a miss shouldn't be
    // retried the very next tick
    static final int RETRY_TICKS = 40;
    // three misses in a row on one cell is the server saying no (spawn protection, claims, a plugin), not bad luck
    static final int MAX_MISSES = 3;
    static final int BLACKLIST_TICKS = 600;
    // couldn't get the crosshair onto the face in this long, something is in the way that the raytrace precheck didn't see
    static final int AIM_TIMEOUT = 10;
    static final int AIM_BLACKLIST_TICKS = 100;
    // nothing dark around, the next few scans would say the same thing
    static final int EMPTY_SCAN_BACKOFF = 5;
    // the pause stops the keys, not the bot. it slides on for a few ticks, and aiming from a moving eye puts the crosshair
    // a hair off the face by the time the look lands. friction about halves the speed every tick, so a sprint is under
    // the line in about 6
    static final int SETTLE_TICKS = 6;
    static final double SETTLE_SPEED = 0.01;

    private final Map<Long, Long> tried = new HashMap<>();
    private final Map<Long, Integer> misses = new HashMap<>();
    private final Map<Long, Long> lastMiss = new HashMap<>();
    private final Map<Long, Long> blacklist = new HashMap<>();
    private long lastClick;
    private boolean clicked;
    private boolean pending;
    private long pendingCell;
    private boolean aiming;
    private long aimCell;
    private long aimSince;
    private long aimLast;
    private long nextScan;
    private boolean landed;
    private boolean settled;
    private long landedAt;
    private long landedLast;

    TorchMemory() {
        reset();
    }

    void reset() {
        tried.clear();
        misses.clear();
        lastMiss.clear();
        blacklist.clear();
        clicked = false;
        pending = false;
        aiming = false;
        landed = false;
        nextScan = Long.MIN_VALUE;
    }

    // waiting on the last click's verdict counts as cooling down, so there's only ever one torch in flight
    boolean coolingDown(long now) {
        return pending || (clicked && now - lastClick < PLACE_COOLDOWN);
    }

    boolean skip(long cell, long now) {
        Long at = tried.get(cell);
        if (at != null && now - at < RETRY_TICKS) {
            return true;
        }
        Long until = blacklist.get(cell);
        return until != null && now < until;
    }

    void clicked(long cell, long now) {
        clicked = true;
        lastClick = now;
        tried.put(cell, now);
        pending = true;
        pendingCell = cell;
        aiming = false;
    }

    // the cell whose click is due for a verdict, empty while there's none or it's too early
    OptionalLong due(long now) {
        return pending && now - lastClick >= CHECK_DELAY ? OptionalLong.of(pendingCell) : OptionalLong.empty();
    }

    // a hit doesn't wipe the misses: a torch the server takes back late looks like a hit, and wiping would let a
    // rejected cell go round forever. the cell isn't air any more after a real hit, so it never comes up again anyway
    void verdict(boolean placed, long now) {
        if (!pending) {
            return;
        }
        pending = false;
        if (placed) {
            return;
        }
        int count = misses.merge(pendingCell, 1, Integer::sum);
        lastMiss.put(pendingCell, now);
        if (count >= MAX_MISSES) {
            blacklist.put(pendingCell, now + BLACKLIST_TICKS);
            misses.remove(pendingCell);
            lastMiss.remove(pendingCell);
        }
    }

    // called every tick we aim at a cell. true once we've been at it too long, and the cell goes on the short blacklist.
    // a gap of more than a tick (someone else had the wheel) starts the clock over
    boolean aimTimedOut(long cell, long now) {
        if (!aiming || aimCell != cell || now - aimLast > 1) {
            aiming = true;
            aimCell = cell;
            aimSince = now;
        }
        aimLast = now;
        if (now - aimSince < AIM_TIMEOUT) {
            return false;
        }
        aiming = false;
        blacklist.put(cell, now + AIM_BLACKLIST_TICKS);
        return true;
    }

    void stopAiming() {
        aiming = false;
    }

    // called every tick we hold the pause. true once it has landed and we stopped sliding, or SETTLE_TICKS after it landed
    // in case something keeps nudging us. sticks until the pause breaks, a tick without it starts the wait over. the aim
    // clock only starts after this, so the slide doesn't eat the aim time
    boolean settled(long now, boolean pauseLanded, double speed) {
        if (!pauseLanded) {
            landed = false;
            return false;
        }
        if (!landed || now - landedLast > 1) {
            landed = true;
            settled = false;
            landedAt = now;
        }
        landedLast = now;
        if (!settled && (speed < SETTLE_SPEED || now - landedAt >= SETTLE_TICKS)) {
            settled = true;
        }
        return settled;
    }

    boolean scanDue(long now) {
        return now >= nextScan;
    }

    void foundNothing(long now) {
        nextScan = now + EMPTY_SCAN_BACKOFF;
    }

    // old misses fade, so a cell that failed once an hour ago doesn't get blacklisted on its next single failure
    void prune(long now) {
        tried.values().removeIf(at -> now - at >= RETRY_TICKS);
        blacklist.values().removeIf(until -> now >= until);
        lastMiss.entrySet().removeIf(e -> {
            if (now - e.getValue() >= BLACKLIST_TICKS) {
                misses.remove(e.getKey());
                return true;
            }
            return false;
        });
    }
}
