package adris.altoclef.util.helpers;

// the "how do we spread this stack over the grid" math, pure ints so it can be tested without a game.
// vanilla drag clicking: a left drag (type 0) gives every slot floor(cursor / slots) and keeps the remainder on the
// cursor, a right drag (type 1) gives every slot exactly one. both ADD to what the slot already holds
public final class CraftDragPlan {

    public enum Kind {
        // nothing to do, fewer than two slots still need anything
        NOTHING,
        // some right clicks into the return slot, then rounds of drags
        DRAG,
        // the cursor is too small to drag (needs one item per slot), put it back and grab a bigger stack
        PUT_BACK
    }

    // one burst is a single slot action (one 0.2s cooldown), and every click in it is its own packet. this is where
    // it stops being cute and starts looking like a bot to an anticheat
    public static final int MAX_BURST_PACKETS = 24;

    public final Kind kind;
    // indexes into the deficits array that the drag covers
    public final int[] slots;
    // true for a left drag (even split), false for a right drag (one each)
    public final boolean evenSplit;
    // how many drags to do this burst (always 1 for a left drag)
    public final int rounds;
    // right clicks that put surplus back into the return slot BEFORE the drag, so floor(cursor / n) lands exactly
    public final int returnFirst;
    // what every covered slot gets per round, for the log and the tests
    public final int giveEach;

    private CraftDragPlan(Kind kind, int[] slots, boolean evenSplit, int rounds, int returnFirst, int giveEach) {
        this.kind = kind;
        this.slots = slots;
        this.evenSplit = evenSplit;
        this.rounds = rounds;
        this.returnFirst = returnFirst;
        this.giveEach = giveEach;
    }

    private static final CraftDragPlan NOTHING = new CraftDragPlan(Kind.NOTHING, new int[0], true, 0, 0, 0);

    /**
     * @param deficits   how many more items each slot wants (0 for a slot that is done or can't take the item)
     * @param cursor     how many of the item are on the cursor
     * @param returnRoom how many items one slot in the inventory can take back, 0 if none
     */
    public static CraftDragPlan plan(int[] deficits, int cursor, int returnRoom) {
        int n = 0;
        int dmin = Integer.MAX_VALUE;
        for (int d : deficits) {
            if (d > 0) {
                ++n;
                dmin = Math.min(dmin, d);
            }
        }
        if (n < 2) return NOTHING;
        int[] covered = new int[n];
        int at = 0;
        for (int i = 0; i < deficits.length; ++i) {
            if (deficits[i] > 0) covered[at++] = i;
        }
        // everyone gets dmin this round, the slots that wanted more come around again
        int need = n * dmin;
        int dragCost = n + 2;

        if (cursor < n) {
            // a drag only adds a slot while the cursor holds MORE than the slots picked so far, so n slots need n items
            return new CraftDragPlan(Kind.PUT_BACK, covered, true, 0, 0, 0);
        }
        if (cursor < need) {
            // not enough for a full round. a left drag hands out what it can and we come back for more
            return new CraftDragPlan(Kind.DRAG, covered, true, 1, 0, cursor / n);
        }

        // enough on the cursor. a left drag is exact when floor(cursor / n) == dmin, ie cursor in [need, need + n - 1].
        // above that it overfills, and an overfilled grid makes the output fall back to one craft at a time and eat
        // ingredients meant for other crafts. so hand the surplus back first (1 packet per item)
        int returns = Math.max(0, cursor - (need + n - 1));
        int leftCost = returns + dragCost;
        // right drags are exact with nothing returned but cost dmin whole drags
        int rightCost = dmin * dragCost;

        boolean leftPossible = returns <= returnRoom;
        if (leftPossible && leftCost <= rightCost) {
            if (leftCost <= MAX_BURST_PACKETS) {
                return new CraftDragPlan(Kind.DRAG, covered, true, 1, returns, dmin);
            }
            // a huge surplus, return a chunk now and come back for the drag next action
            int chunk = Math.min(returns, MAX_BURST_PACKETS);
            return new CraftDragPlan(Kind.DRAG, covered, true, 0, chunk, 0);
        }
        int rounds = Math.max(1, Math.min(dmin, MAX_BURST_PACKETS / dragCost));
        return new CraftDragPlan(Kind.DRAG, covered, false, rounds, 0, 1);
    }

    /**
     * Which item type to drag, if any. A drag spreads one type from one stack, so a type only counts when its single
     * biggest stack (or the cursor) covers every slot that still wants it: 1 acacia + 3 oak planks over 4 slots is
     * not draggable, and picking either one just ends in a put back loop. All arrays are per candidate type.
     *
     * @param sourceCount  size of the one stack we would drag from (the cursor stack if it is on the cursor)
     * @param slotsNeeding how many grid slots would take this type
     * @param onCursor     whether the type is already on the cursor
     * @return index of the type to drag, or -1 to leave the grid to the one slot at a time path
     */
    public static int chooseDragType(int[] sourceCount, int[] slotsNeeding, boolean[] onCursor) {
        int best = -1;
        for (int i = 0; i < sourceCount.length; ++i) {
            if (slotsNeeding[i] < 2 || sourceCount[i] < slotsNeeding[i]) continue;
            boolean better = best == -1
                    || (onCursor[i] && !onCursor[best])
                    || (onCursor[i] == onCursor[best] && (slotsNeeding[i] > slotsNeeding[best]
                    || (slotsNeeding[i] == slotsNeeding[best] && sourceCount[i] > sourceCount[best])));
            if (better) best = i;
        }
        return best;
    }

    /**
     * Which stack to pick up. The smallest one that covers the whole round (the least surplus to hand back), and if
     * none does, the biggest one.
     *
     * @return index into stackCounts, or -1 if there are no stacks
     */
    public static int pickSource(int[] stackCounts, int need) {
        int best = -1;
        for (int i = 0; i < stackCounts.length; ++i) {
            if (stackCounts[i] <= 0) continue;
            if (best == -1) {
                best = i;
                continue;
            }
            int cb = stackCounts[best];
            int cc = stackCounts[i];
            boolean bestCovers = cb >= need;
            boolean checkCovers = cc >= need;
            if (checkCovers && (!bestCovers || cc < cb)) {
                best = i;
            } else if (!checkCovers && !bestCovers && cc > cb) {
                best = i;
            }
        }
        return best;
    }

    /**
     * What a plan leaves in each slot, ie the vanilla drag rules. Only here so the tests can check a plan really
     * lands where it says it does.
     *
     * @return the new cursor count
     */
    public static int simulate(CraftDragPlan plan, int[] present, int cursor) {
        cursor -= plan.returnFirst;
        for (int round = 0; round < plan.rounds; ++round) {
            int each = plan.evenSplit ? cursor / plan.slots.length : 1;
            for (int slot : plan.slots) {
                present[slot] += each;
                cursor -= each;
            }
        }
        return cursor;
    }
}
