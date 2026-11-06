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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

// the pure decisions behind InventoryBehavior's throwaway handling, split out so they can be tested without a world
// (and without minecraft's registries, which is why nothing in here knows what an Item is)
final class ThrowawayPicks {

    private ThrowawayPicks() {
    }

    enum Failure {
        PAUSED("interactions are paused"),
        AVOIDED("something told us not to place there"),
        NONE_IN_INVENTORY("no throwaway blocks in the inventory at all"),
        ALL_PROTECTED("every throwaway we have is protected by altoclef, nothing held above what it's saving"),
        NOT_ON_HOTBAR("throwaway is in the main inventory but not on the hotbar (allowInventory is off)"),
        NO_MATCH("have throwaways but none of them can be placed there");

        final String why;

        Failure(String why) {
            this.why = why;
        }
    }

    // throwaway() selects the hotbar slot before the item is in it when the swap into it was throttled (or waits for us to
    // stand still), and whatever sits there meanwhile is not what we picked. the wait is only believed for a couple of ticks:
    // the movement that picked asks again every tick, so a stamp nobody refreshed is a movement that moved on
    static <T> boolean handWaiting(T awaited, T inSlot, int awaitedSlot, int selectedSlot, long stampedAt, long now) {
        return awaited != null && selectedSlot == awaitedSlot && !awaited.equals(inSlot) && now - stampedAt <= 2;
    }

    // what a movement may burn of a protected stack: whatever is held above the reserve. a recipe waiting on 6 cobble
    // doesn't care about the 7th, but it very much cares about the 6th (building a staircase out of the recipe is how the
    // bot spent a whole minute mining the same cobble over and over). long math because "all of it" is MAX_VALUE
    static int spare(int held, int reserve) {
        return (int) Math.max(0L, (long) held - reserve);
    }

    // the same call for one stack picked by some other rule than the acceptable list (the builder asking for the exact block):
    // nobody is saving an unprotected item, and a protected one is only fair game for a movement with something above its reserve
    static boolean mayBurn(boolean isProtected, boolean allowProtected, int spare) {
        return !isProtected || (allowProtected && spare > 0);
    }

    // unprotected first, so a path burns dirt it was going to throw out anyway before it touches the cobble a recipe is
    // waiting on. protected ones only show up at all when the caller is a movement that needs the block right now, and
    // then only if there's something above their reserve (spare says how much): a failed path costs more than a cobble
    // that can be mined again, but a recipe short of its last cobble costs more than a path that can dig instead
    static <T> List<T> order(Collection<T> acceptable, Predicate<? super T> isProtected, ToIntFunction<? super T> spare, boolean allowProtected) {
        List<T> out = new ArrayList<>();
        List<T> held = new ArrayList<>();
        for (T item : acceptable) {
            (isProtected.test(item) ? held : out).add(item);
        }
        if (allowProtected) {
            for (T item : held) {
                if (spare.applyAsInt(item) > 0) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    // slots 1..7 are the temp slots (0 is the pickaxe, 8 the conventional throwaway). a temp swap sends whatever was in
    // the slot to the main inventory, so it can never be the slot in hand, the one holding the block we're about to place,
    // or the one a queued swap is already aimed at. empty slots first because they cost nothing
    static OptionalInt tempHotbarSlot(boolean[] hotbarEmpty, IntPredicate disallowed, int selected, int throwawaySlot, int pendingTarget, IntUnaryOperator pick) {
        List<Integer> empty = new ArrayList<>();
        List<Integer> any = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (i == selected || i == throwawaySlot || i == pendingTarget || disallowed.test(i)) {
                continue;
            }
            any.add(i);
            if (hotbarEmpty[i]) {
                empty.add(i);
            }
        }
        List<Integer> candidates = empty.isEmpty() ? any : empty;
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(pick.applyAsInt(candidates.size())));
    }

    static Failure classify(boolean paused, boolean avoided, boolean haveAny, boolean haveUsable, boolean usableOnHotbar) {
        if (paused) {
            return Failure.PAUSED;
        }
        if (avoided) {
            return Failure.AVOIDED;
        }
        if (!haveAny) {
            return Failure.NONE_IN_INVENTORY;
        }
        if (!haveUsable) {
            return Failure.ALL_PROTECTED;
        }
        if (!usableOnHotbar) {
            return Failure.NOT_ON_HOTBAR;
        }
        return Failure.NO_MATCH;
    }
}
