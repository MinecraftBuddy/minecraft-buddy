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

import java.util.function.Predicate;

// a task handing over to the next one cancels the path, and when the next one asks for the very same goal the bot used
// to stand there while a fresh path got calculated to where it was already walking. the handover parks the path it
// cancelled here and the next request may take it back. pure, P is the path and PathingBehavior asks the world questions
public final class PathKeep<P> {
    // half a second. a handover re-issues the goal in the same tick, anything later is a different story
    public static final long MAX_AGE_TICKS = 10;

    public record Park<P>(P path, long tick, Object world, Object player) {
    }

    private Park<P> park;

    // what is parked right now, so a handover can hand it on past the cancel that drops it
    public Park<P> peek() {
        return park;
    }

    // after the cancel: the path that was running gets parked. with none running (the next task's start, right after the
    // previous task's stop) the park from before carries on
    public void handover(Park<P> before, P current, long tick, Object world, Object player) {
        park = current != null ? new Park<>(current, tick, world, player) : before;
    }

    // every other cancel, the user's included
    public void drop() {
        park = null;
    }

    // the parked path if the new request may have it, else null. used up either way. sameGoal / feetOn ask about the path:
    // the new goal equals the one it was made for, where we stand is one of its positions (knockback, a teleport or a
    // respawn put us somewhere else). the world and player objects catch a dimension change or a death in between
    public P take(boolean enabled, long now, Object world, Object player, Predicate<P> sameGoal, Predicate<P> feetOn) {
        Park<P> p = park;
        park = null;
        if (p == null) {
            return null;
        }
        boolean back = restore(enabled, sameGoal.test(p.path()), now - p.tick(), feetOn.test(p.path()), world == p.world(), player == p.player());
        return back ? p.path() : null;
    }

    public static boolean restore(boolean enabled, boolean sameGoal, long age, boolean feetOnPath, boolean sameWorld, boolean samePlayer) {
        return enabled && sameGoal && age >= 0 && age <= MAX_AGE_TICKS && feetOnPath && sameWorld && samePlayer;
    }
}
