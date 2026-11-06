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

// a task handing over to another one while a movement is mid-step (a sneak bridge, a diagonal, a descend) can't just
// clearAllKeys: letting go of everything there walks you off the edge. so the cancel is parked until the first tick
// the executor says it is safe, same idea as requestPause which also waits for that tick. any hard cancel throws it away.
// pure
final class DeferredCancel {
    private boolean pending;

    void request() {
        pending = true;
    }

    boolean pending() {
        return pending;
    }

    // true once, on the first tick that is safe. asked every tick with the executor's own answer
    boolean fire(boolean safeToCancel) {
        if (!pending || !safeToCancel) {
            return false;
        }
        pending = false;
        return true;
    }

    // a hard cancel (or no path left to cancel) makes the wait pointless
    void clear() {
        pending = false;
    }
}
