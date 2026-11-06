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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.junit.Assert.*;

public class ThrowawayPicksTest {

    private static final List<String> BAG = Arrays.asList("cobble", "dirt", "netherrack");

    // plenty above any reserve, for the tests that are about ordering and not about counting
    private static final ToIntFunction<String> LOTS = x -> 64;

    @Test
    public void respectingProtectionDropsProtectedOnes() {
        // what backfill and everything else that isn't mid movement gets: altoclef's stash is off limits
        List<String> out = ThrowawayPicks.order(BAG, "cobble"::equals, LOTS, false);
        assertEquals(Arrays.asList("dirt", "netherrack"), out);
    }

    @Test
    public void movementScopeKeepsProtectedButLast() {
        // cobble is protected: the path can use it, but only after the stuff nobody was saving
        List<String> out = ThrowawayPicks.order(BAG, "cobble"::equals, LOTS, true);
        assertEquals(Arrays.asList("dirt", "netherrack", "cobble"), out);
    }

    @Test
    public void everythingProtectedStillPlacesInMovementScope() {
        // the pre-reserve bug: all protected meant "no throwaway", so the planner and the executor both gave up
        assertTrue(ThrowawayPicks.order(BAG, x -> true, LOTS, false).isEmpty());
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> true, LOTS, true));
    }

    @Test
    public void nothingProtectedIsUntouched() {
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> false, LOTS, true));
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> false, LOTS, false));
    }

    @Test
    public void sparePartIsWhatIsHeldAboveTheReserve() {
        assertEquals(0, ThrowawayPicks.spare(6, 6)); // exactly the recipe's cobble: dig out of the hole, don't pillar
        assertEquals(3, ThrowawayPicks.spare(9, 6));
        assertEquals(0, ThrowawayPicks.spare(3, 6)); // short of it: never negative
        assertEquals(5, ThrowawayPicks.spare(5, 0));
    }

    @Test
    public void protectedWithNoNumberIsNeverSpare() {
        // addProtectedItems without a count is FULLY_RESERVED (MAX_VALUE), and held - MAX_VALUE must not wrap around
        assertEquals(0, ThrowawayPicks.spare(0, Integer.MAX_VALUE));
        assertEquals(0, ThrowawayPicks.spare(2304, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("dirt", "netherrack"), ThrowawayPicks.order(BAG, "cobble"::equals, x -> ThrowawayPicks.spare(64, Integer.MAX_VALUE), true));
    }

    @Test
    public void protectedStackJoinsOnlyWithSomethingAboveItsReserve() {
        // cobble: 6 saved for the recipe. dirt is unprotected and always eligible, it's just not what's being asked about
        Map<String, Integer> held = new HashMap<>();
        Map<String, Integer> saved = new HashMap<>();
        saved.put("cobble", 6);
        ToIntFunction<String> spare = item -> ThrowawayPicks.spare(held.getOrDefault(item, 0), saved.getOrDefault(item, 0));

        held.put("cobble", 6);
        assertEquals(Arrays.asList("dirt", "netherrack"), ThrowawayPicks.order(BAG, "cobble"::equals, spare, true));

        held.put("cobble", 9);
        assertEquals(Arrays.asList("dirt", "netherrack", "cobble"), ThrowawayPicks.order(BAG, "cobble"::equals, spare, true));

        // and backfill still gets none of it, however much there is
        assertEquals(Arrays.asList("dirt", "netherrack"), ThrowawayPicks.order(BAG, "cobble"::equals, spare, false));
    }

    @Test
    public void unprotectedIsFirstAndNeverAsksHowMuchIsHeld() {
        // an unprotected one is eligible whatever the count says, the hotbar lookup is what finds out there's none
        List<String> asked = new ArrayList<>();
        List<String> out = ThrowawayPicks.order(BAG, "cobble"::equals, item -> {
            asked.add(item);
            return 0;
        }, true);
        assertEquals(Arrays.asList("dirt", "netherrack"), out);
        assertEquals(List.of("cobble"), asked);
    }

    private static boolean[] empties(int... emptySlots) {
        boolean[] e = new boolean[9];
        for (int i : emptySlots) {
            e[i] = true;
        }
        return e;
    }

    @Test
    public void tempSlotNeverStompsSelectedThrowawayOrPending() {
        // every temp slot empty except we hold slot 3, the block is in 5, and a swap into 6 is queued
        for (int roll = 0; roll < 7; roll++) {
            int r = roll;
            OptionalInt slot = ThrowawayPicks.tempHotbarSlot(empties(1, 2, 3, 4, 5, 6, 7), i -> false, 3, 5, 6, n -> r % n);
            assertTrue(slot.isPresent());
            assertFalse(Set.of(3, 5, 6, 0, 8).contains(slot.getAsInt()));
        }
    }

    @Test
    public void tempSlotNeverPicksAnythingButOneToSeven() {
        Set<Integer> seen = new HashSet<>();
        for (int roll = 0; roll < 20; roll++) {
            int r = roll;
            seen.add(ThrowawayPicks.tempHotbarSlot(empties(0, 1, 2, 3, 4, 5, 6, 7, 8), i -> false, -1, -1, -1, n -> r % n).getAsInt());
        }
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7)), seen);
    }

    @Test
    public void tempSlotPrefersEmptyOnes() {
        // only 2 is empty, but 1 and 4 are the ones in hand and holding the block, so 2 it is regardless of the roll
        for (int roll = 0; roll < 6; roll++) {
            int r = roll;
            assertEquals(2, ThrowawayPicks.tempHotbarSlot(empties(2), i -> false, 1, 4, -1, n -> r % n).getAsInt());
        }
    }

    @Test
    public void tempSlotFallsBackToOccupiedWhenNothingIsEmpty() {
        // a full hotbar still has to find somewhere, as long as it isn't one of the protected slots
        for (int roll = 0; roll < 5; roll++) {
            int r = roll;
            int slot = ThrowawayPicks.tempHotbarSlot(empties(), i -> false, 1, 4, -1, n -> r % n).getAsInt();
            assertNotEquals(1, slot);
            assertNotEquals(4, slot);
        }
    }

    @Test
    public void tempSlotHonorsCallerDisallowAndGivesUpWhenBoxedIn() {
        assertEquals(OptionalInt.empty(), ThrowawayPicks.tempHotbarSlot(empties(1, 2, 3, 4, 5, 6, 7), i -> true, -1, -1, -1, n -> 0));
        // 7 slots, one in hand, one holding the block, five more the caller says no to -> nothing left
        assertEquals(OptionalInt.empty(), ThrowawayPicks.tempHotbarSlot(empties(), i -> i > 2, 1, 2, -1, n -> 0));
    }

    @Test
    public void aStackPickedByTheBuilderFollowsTheSameReserveRule() {
        // nobody is saving an unprotected item
        assertTrue(ThrowawayPicks.mayBurn(false, false, 0));
        assertTrue(ThrowawayPicks.mayBurn(false, true, 0));
        // a protected one is for movements only, and only above the reserve
        assertFalse(ThrowawayPicks.mayBurn(true, false, 64));
        assertFalse(ThrowawayPicks.mayBurn(true, true, 0));
        assertTrue(ThrowawayPicks.mayBurn(true, true, 1));
        // and it agrees with order() about the same stack
        assertEquals(ThrowawayPicks.mayBurn(true, true, 0), ThrowawayPicks.order(BAG, "cobble"::equals, x -> 0, true).contains("cobble"));
        assertEquals(ThrowawayPicks.mayBurn(true, true, 3), ThrowawayPicks.order(BAG, "cobble"::equals, x -> 3, true).contains("cobble"));
    }

    @Test
    public void theHandWaitsUntilTheAwaitedItemIsInTheSelectedSlot() {
        assertFalse(ThrowawayPicks.handWaiting(null, "bucket", 7, 7, 10, 10));
        // selected the slot, the cobble hasn't arrived, a bucket is in there instead
        assertTrue(ThrowawayPicks.handWaiting("cobble", "bucket", 7, 7, 10, 10));
        assertTrue(ThrowawayPicks.handWaiting("cobble", null, 7, 7, 10, 10));
        // it landed
        assertFalse(ThrowawayPicks.handWaiting("cobble", "cobble", 7, 7, 10, 11));
        // somebody else's slot, say the bucket in slot 3 for a mlg
        assertFalse(ThrowawayPicks.handWaiting("cobble", "bucket", 7, 3, 10, 10));
    }

    @Test
    public void aWaitNobodyRefreshedExpires() {
        // the movement asks every tick, so a stamp from several ticks ago is a movement that stopped
        assertTrue(ThrowawayPicks.handWaiting("cobble", "bucket", 7, 7, 10, 12));
        assertFalse(ThrowawayPicks.handWaiting("cobble", "bucket", 7, 7, 10, 13));
    }

    @Test
    public void failureReasonsComeOutInOrder() {
        assertEquals(ThrowawayPicks.Failure.PAUSED, ThrowawayPicks.classify(true, true, true, true, true));
        assertEquals(ThrowawayPicks.Failure.AVOIDED, ThrowawayPicks.classify(false, true, false, false, false));
        assertEquals(ThrowawayPicks.Failure.NONE_IN_INVENTORY, ThrowawayPicks.classify(false, false, false, false, false));
        assertEquals(ThrowawayPicks.Failure.ALL_PROTECTED, ThrowawayPicks.classify(false, false, true, false, false));
        assertEquals(ThrowawayPicks.Failure.NOT_ON_HOTBAR, ThrowawayPicks.classify(false, false, true, true, false));
        assertEquals(ThrowawayPicks.Failure.NO_MATCH, ThrowawayPicks.classify(false, false, true, true, true));
    }
}
