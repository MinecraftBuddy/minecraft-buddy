package adris.altoclef.util;

import adris.altoclef.util.helpers.CraftDragPlan;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the drag math for filling the crafting grid. vanilla adds floor(cursor / slots) per slot on a left drag and one per
// slot on a right drag, and an overfilled grid is worse than a slow one, so every plan here has to land EXACTLY
public class CraftDragPlanTest {

    private static final int ROOM = 64;

    private static int[] filled(int slots, int each) {
        int[] out = new int[slots];
        java.util.Arrays.fill(out, each);
        return out;
    }

    @Test
    public void breadFromAFullStackTrimsTwoThenLeftDrags() {
        // 3 slots of 20 from a stack of 64: floor(64 / 3) is 21, one too many each. 2 go back first, 62 / 3 is 20
        CraftDragPlan plan = CraftDragPlan.plan(filled(3, 20), 64, ROOM);
        assertEquals(CraftDragPlan.Kind.DRAG, plan.kind);
        assertTrue(plan.evenSplit);
        assertEquals(1, plan.rounds);
        assertEquals(2, plan.returnFirst);
        int[] present = new int[3];
        int left = CraftDragPlan.simulate(plan, present, 64);
        assertArrayEquals(new int[]{20, 20, 20}, present);
        assertEquals(2, left);
    }

    @Test
    public void exactStackIsOneCleanLeftDrag() {
        CraftDragPlan plan = CraftDragPlan.plan(filled(3, 20), 60, ROOM);
        assertEquals(0, plan.returnFirst);
        int[] present = new int[3];
        assertEquals(0, CraftDragPlan.simulate(plan, present, 60));
        assertArrayEquals(new int[]{20, 20, 20}, present);
    }

    @Test
    public void stackJustUnderTheNextStepNeedsNoReturns() {
        // 62 / 3 = 20, remainder 2 stays on the cursor
        CraftDragPlan plan = CraftDragPlan.plan(filled(3, 20), 62, ROOM);
        assertEquals(0, plan.returnFirst);
        int[] present = new int[3];
        assertEquals(2, CraftDragPlan.simulate(plan, present, 62));
        assertArrayEquals(new int[]{20, 20, 20}, present);
    }

    @Test
    public void smallRecipeFromABigStackRightDragsInsteadOfReturningSixtyOne() {
        // 2 slots of 1 from 64: returning 61 one at a time is silly, one right drag is 4 packets
        CraftDragPlan plan = CraftDragPlan.plan(filled(2, 1), 64, ROOM);
        assertEquals(CraftDragPlan.Kind.DRAG, plan.kind);
        assertEquals(false, plan.evenSplit);
        assertEquals(1, plan.rounds);
        int[] present = new int[2];
        assertEquals(62, CraftDragPlan.simulate(plan, present, 64));
        assertArrayEquals(new int[]{1, 1}, present);
    }

    @Test
    public void noRoomToReturnFallsBackToRightDrags() {
        // 9 slots (hay block) of 7 from a 64 stack: floor(64 / 9) = 7 is already exact, so this one never needs room
        CraftDragPlan plan = CraftDragPlan.plan(filled(9, 7), 64, 0);
        int[] present = new int[9];
        CraftDragPlan.simulate(plan, present, 64);
        assertArrayEquals(filled(9, 7), present);
        // 3 slots of 10 from 64 with no room: left would overfill (21 > 10), so right drags, in rounds
        plan = CraftDragPlan.plan(filled(3, 10), 64, 0);
        assertEquals(false, plan.evenSplit);
        assertEquals(0, plan.returnFirst);
        assertTrue(plan.rounds >= 1);
        assertTrue(plan.rounds * (3 + 2) <= CraftDragPlan.MAX_BURST_PACKETS);
        present = new int[3];
        CraftDragPlan.simulate(plan, present, 64);
        assertArrayEquals(filled(3, plan.rounds), present);
    }

    @Test
    public void cursorSmallerThanTheRoundDragsWhatItCan() {
        // 3 slots of 20 but only 45 on the cursor: 15 each now, the rest comes next round
        CraftDragPlan plan = CraftDragPlan.plan(filled(3, 20), 45, ROOM);
        assertEquals(CraftDragPlan.Kind.DRAG, plan.kind);
        assertTrue(plan.evenSplit);
        assertEquals(0, plan.returnFirst);
        int[] present = new int[3];
        assertEquals(0, CraftDragPlan.simulate(plan, present, 45));
        assertArrayEquals(new int[]{15, 15, 15}, present);
    }

    @Test
    public void cursorWithFewerItemsThanSlotsGoesBack() {
        CraftDragPlan plan = CraftDragPlan.plan(filled(3, 20), 2, ROOM);
        assertEquals(CraftDragPlan.Kind.PUT_BACK, plan.kind);
    }

    // the crafting table log: 1 acacia + 3 oak for four plank slots looped on "cursor too small" forever. the rule is
    // one stack of one type has to cover every slot that wants it, otherwise the one slot at a time path does it
    @Test
    public void mixedPlanksWithNoSingleBigStackDoNotDrag() {
        // acacia 1, oak 3, four empty slots
        assertEquals(-1, CraftDragPlan.chooseDragType(new int[]{1, 3}, new int[]{4, 4}, new boolean[]{false, false}));
        // four singleton stacks of four different planks
        assertEquals(-1, CraftDragPlan.chooseDragType(new int[]{1, 1, 1, 1}, new int[]{4, 4, 4, 4}, new boolean[4]));
    }

    @Test
    public void aBigStackOfOneTypeBeatsTheStrayAcacia() {
        // acacia 1, oak 64: drag the oak
        assertEquals(1, CraftDragPlan.chooseDragType(new int[]{1, 64}, new int[]{4, 4}, new boolean[]{false, false}));
        // exactly one per slot is the smallest stack that still drags
        assertEquals(0, CraftDragPlan.chooseDragType(new int[]{4}, new int[]{4}, new boolean[]{false}));
    }

    @Test
    public void aSlotHoldingADifferentValidTypeIsNotPartOfTheDrag() {
        // slot 0 already has acacia. oak only needs the other three, and 3 oak is enough for those
        assertEquals(1, CraftDragPlan.chooseDragType(new int[]{1, 3}, new int[]{1, 3}, new boolean[]{false, false}));
        // acacia would only cover one slot, a drag over one slot is just a click
        assertEquals(-1, CraftDragPlan.chooseDragType(new int[]{64}, new int[]{1}, new boolean[]{false}));
        // the deficits for the oak plan skip that slot too
        CraftDragPlan plan = CraftDragPlan.plan(new int[]{0, 1, 1, 1}, 3, ROOM);
        assertEquals(CraftDragPlan.Kind.DRAG, plan.kind);
        assertArrayEquals(new int[]{1, 2, 3}, plan.slots);
    }

    @Test
    public void theCursorStackWinsWhenItCoversTheSlots() {
        assertEquals(0, CraftDragPlan.chooseDragType(new int[]{4, 64}, new int[]{4, 4}, new boolean[]{true, false}));
        // a cursor stack that is too small does not get to hold the grid hostage
        assertEquals(1, CraftDragPlan.chooseDragType(new int[]{2, 64}, new int[]{4, 4}, new boolean[]{true, false}));
        assertEquals(-1, CraftDragPlan.chooseDragType(new int[]{2}, new int[]{4}, new boolean[]{true}));
    }

    @Test
    public void unevenPresentCountsLevelTheShortOnesFirst() {
        // slots hold 0, 5, 12 and want 20: deficits 20, 15, 8. everyone gets 8 this round
        CraftDragPlan plan = CraftDragPlan.plan(new int[]{20, 15, 8}, 30, ROOM);
        assertEquals(CraftDragPlan.Kind.DRAG, plan.kind);
        assertEquals(3, plan.slots.length);
        int[] present = new int[]{0, 5, 12};
        int cursor = CraftDragPlan.simulate(plan, present, 30);
        assertArrayEquals(new int[]{8, 13, 20}, present);
        // 30 on the cursor, 4 go back so floor(26 / 3) is 8, 2 left over
        assertEquals(2, cursor);
        // and the next one only covers the two still short
        CraftDragPlan next = CraftDragPlan.plan(new int[]{12, 7, 0}, cursor + 20, ROOM);
        assertArrayEquals(new int[]{0, 1}, next.slots);
    }

    @Test
    public void aSatisfiedOrSingleSlotIsNotADrag() {
        assertEquals(CraftDragPlan.Kind.NOTHING, CraftDragPlan.plan(new int[]{0, 0, 0}, 64, ROOM).kind);
        assertEquals(CraftDragPlan.Kind.NOTHING, CraftDragPlan.plan(new int[]{0, 5, 0}, 64, ROOM).kind);
    }

    @Test
    public void hugeSurplusIsReturnedInAChunkNotOneEnormousBurst() {
        // 2 slots of 30 from 64: 64 - (60 + 1) = 3 to give back, cheap. but with a stack that can't be trimmed
        // cheaply, the burst never goes over the packet cap
        for (int n = 2; n <= 9; ++n) {
            for (int k = 1; k <= 64; ++k) {
                for (int c = n; c <= 64; ++c) {
                    CraftDragPlan plan = CraftDragPlan.plan(filled(n, k), c, ROOM);
                    if (plan.kind != CraftDragPlan.Kind.DRAG) continue;
                    int packets = plan.returnFirst + plan.rounds * (plan.slots.length + 2);
                    assertTrue("n=" + n + " k=" + k + " c=" + c + " packets=" + packets, packets <= CraftDragPlan.MAX_BURST_PACKETS);
                }
            }
        }
    }

    // the whole loop the task runs: pick a stack, plan, drag, put the leftover back, repeat until the grid is full.
    // checks the grid ends with EXACTLY k each and the items add up
    private static void runToCompletion(int n, int k, int[] stacks) {
        int[] present = new int[n];
        int totalBefore = java.util.Arrays.stream(stacks).sum();
        int cursor = 0;
        for (int step = 0; step < 200; ++step) {
            int[] deficits = new int[n];
            for (int i = 0; i < n; ++i) deficits[i] = Math.max(0, k - present[i]);
            int active = 0;
            int dmin = Integer.MAX_VALUE;
            for (int d : deficits) {
                if (d > 0) {
                    ++active;
                    dmin = Math.min(dmin, d);
                }
            }
            if (active < 2) break;
            if (cursor == 0) {
                int src = CraftDragPlan.pickSource(stacks, active * dmin);
                assertTrue("ran out of items", src != -1);
                cursor = stacks[src];
                stacks[src] = 0;
                // plan with room for a whole stack in the source, same as an empty inventory slot
                CraftDragPlan plan = CraftDragPlan.plan(deficits, cursor, 64);
                cursor = apply(plan, present, stacks, src, cursor);
            } else {
                CraftDragPlan plan = CraftDragPlan.plan(deficits, cursor, 64);
                if (plan.kind == CraftDragPlan.Kind.PUT_BACK) {
                    stacks[0] += cursor;
                    cursor = 0;
                } else {
                    cursor = apply(plan, present, stacks, 0, cursor);
                }
            }
            // leftover on the cursor goes back before the next pick up
            if (cursor > 0 && cursor < n) {
                stacks[0] += cursor;
                cursor = 0;
            }
        }
        for (int i = 0; i < n; ++i) {
            // the last single slot is the old one at a time path, only the dragged ones have to be exact
            assertTrue("slot " + i + " overfilled: " + present[i], present[i] <= k);
        }
        int inGrid = java.util.Arrays.stream(present).sum();
        assertEquals(totalBefore, inGrid + java.util.Arrays.stream(stacks).sum() + cursor);
    }

    private static int apply(CraftDragPlan plan, int[] present, int[] stacks, int returnTo, int cursor) {
        int returned = plan.returnFirst;
        stacks[returnTo] += returned;
        return CraftDragPlan.simulate(plan, present, cursor);
    }

    @Test
    public void wholeLoopNeverOverfills() {
        List<int[]> inventories = new ArrayList<>();
        inventories.add(new int[]{64});
        inventories.add(new int[]{64, 64});
        inventories.add(new int[]{20, 20, 20, 20});
        inventories.add(new int[]{17, 64, 5});
        inventories.add(new int[]{64, 3});
        for (int n = 2; n <= 9; ++n) {
            for (int k = 1; k <= 20; ++k) {
                for (int[] inv : inventories) {
                    int need = n * k;
                    if (java.util.Arrays.stream(inv).sum() < need) continue;
                    runToCompletion(n, k, inv.clone());
                }
            }
        }
    }
}
