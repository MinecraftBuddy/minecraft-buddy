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

// the movement timeout starts over every time pathPosition changes, so a resync back and a normal success forward can
// trade the same two movements forever and the timeout never gets a word in. this one only starts over when we get
// further than we've ever been on this path
final class HighWater {

    private int high = -1;
    private int ticks;
    // the budget in half ticks, see tick
    private int halves;
    private double cost;

    // once per tick a movement runs, same spot as ticksOnCurrent. cost is the original estimate of the movement at
    // pathPosition, only kept when that's a new high. true once we've been stuck at or behind the high for longer than the
    // high movement should take plus the timeout, same budget the per movement timeout gives it.
    // a tick behind the high only counts half: knockback or an overshoot puts us back a movement for a second and then
    // we carry on, and at full rate a few of those cancelled a path that was getting somewhere. a real bounce still
    // spends some of every lap on the high, so it runs out within about twice the budget
    boolean tick(int pathPosition, double costHere, int timeout) {
        if (pathPosition > high) {
            high = pathPosition;
            cost = costHere;
            ticks = 0;
            halves = 0;
        }
        ticks++;
        halves += pathPosition == high ? 2 : 1;
        return halves > 2 * (cost + timeout);
    }

    int high() {
        return high;
    }

    int ticks() {
        return ticks;
    }

    double cost() {
        return cost;
    }

    // a cut drops the front of the path, every index after it moves down by that much
    void shift(int by) {
        if (high >= 0) {
            high = Math.max(-1, high - by);
        }
    }

    // got moved somewhere that isn't progress or a lack of it (a boat trip giving up), start counting fresh
    void forget() {
        high = -1;
        ticks = 0;
        halves = 0;
    }
}
