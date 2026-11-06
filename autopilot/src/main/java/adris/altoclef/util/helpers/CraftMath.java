package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// the pure half of "how much can this recipe actually make right now". generic over the item type so the tests
// don't need a booted minecraft, the real callers just pass Items
public final class CraftMath {

    private CraftMath() {
    }

    // how many times the recipe has to run to make `wanted` items. a recipe that makes 9 wheat and a wanted of
    // 99999999 is eleven million crafts, which is exactly the number the grid then has to be clamped against
    // the recipe target for a matching materials craft: what we hold of the one output it counts, plus what the crafts still owe
    // (the wanted total counts every colour, the recipe does not)
    public static int matchingTarget(int heldOfOutput, int canCraft, int stillRequired) {
        return heldOfOutput + Math.min(canCraft, stillRequired);
    }

    public static int craftsFor(int wanted, int outputCount) {
        if (wanted <= 0) return 0;
        return (int) Math.ceil((double) wanted / Math.max(1, outputCount));
    }

    // targets are totals (CollectPlanksTask hands down "20 planks" with 10 already in the bag), so the grid only owes the
    // difference. crafting for the whole 20 burned 5 logs for 10 planks and the extra 20 planks sat there looking smug
    public static int craftsStillOwed(int target, int held, int outputCount) {
        return craftsFor(target - Math.max(0, held), outputCount);
    }

    public record TopUp<T>(T item, int target) {
    }

    // the "convert some logs first" step of a matching-materials craft. targets are totals, so asking for 1 birch plank
    // with 2 in the bag is already met: nothing crafts, the slabs still want 3 per craft and we sit there forever.
    // so ask for what the crafts eat, capped at what this wood can really become so the target never reaches for a tree.
    // the caller only gets here when no type holds sameNeeded already, so with a raw item in the bag the target is
    // always above what we hold and the step really crafts. it also doesn't move while logs turn into planks
    // (held + perRaw * raw stays put), which matters, ItemTarget equality includes the count
    public static <T> TopUp<T> sameResourceTopUp(List<T> types, ToIntFunction<T> held, ToIntFunction<T> raw, int perRaw, int sameNeeded) {
        if (sameNeeded <= 0) return null;
        List<T> ordered = new ArrayList<>(new LinkedHashSet<>(types));
        Map<T, Integer> expected = new HashMap<>();
        for (T type : ordered) expected.put(type, held.applyAsInt(type) + perRaw * raw.applyAsInt(type));
        // biggest pile first, close to the parent's majority pick (that one goes by whole crafts, so two woods a craft
        // apart can disagree: wrong wood turned into planks, not a freeze). List.sort is stable so ties keep the caller's order
        ordered.sort(Comparator.comparingInt((T type) -> expected.get(type)).reversed());
        for (T type : ordered) {
            if (raw.applyAsInt(type) >= 1) return new TopUp<>(type, Math.min(sameNeeded, expected.get(type)));
        }
        return null;
    }

    // can a quick move take the output, or would it craft more than we wanted. one craft in the grid is one craft either way,
    // so wanting 2 planks out of a log's 4 is no reason to go through the cursor: the pickup left 4 planks in hand that nothing
    // put away until the cursor watchdog did, 3 s later, on every plank and stick of the opening crafts
    public static boolean quickMoveCraftsNoExtra(int wanted, int craftCount, int multiples) {
        return wanted >= craftCount || (multiples == 1 && wanted > 0);
    }

    // bag + grid per item, each item once. "birch log, birch log" in a match list is still one pile of logs
    public static <T> int usableCount(Collection<T> items, ToIntFunction<T> bag, ToIntFunction<T> grid) {
        int sum = 0;
        for (T item : new LinkedHashSet<>(items)) {
            sum += bag.applyAsInt(item) + grid.applyAsInt(item);
        }
        return sum;
    }

    // slots: one entry per non-empty recipe slot, holding every item that can go in that slot (planks of any wood,
    // one hay block, three wheat slots...). have: how many of an item we own, grid contents included. cap stops the
    // search early, 99999999 wanted means we never care about more than what the inventory can give anyway.
    // each slot is fed from ONE item type (different planks don't stack in a grid slot), slots with the fewest
    // choices pick first and everybody takes the biggest pile they are allowed to
    public static <T> int maxCrafts(List<? extends List<T>> slots, ToIntFunction<T> have, int cap) {
        if (cap <= 0 || slots.isEmpty()) return 0;
        List<List<T>> ordered = new ArrayList<>(slots.size());
        for (List<T> options : slots) {
            // a slot nothing can fill means the recipe can't run at all
            if (options == null || options.isEmpty()) return 0;
            ordered.add(options);
        }
        ordered.sort(Comparator.comparingInt(List::size));
        Map<T, Integer> owned = new HashMap<>();
        int lo = 0;
        int hi = cap;
        while (lo < hi) {
            // upper middle so the loop always makes progress
            int mid = (int) (((long) lo + hi + 1) / 2);
            if (feasible(ordered, have, owned, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // what the grid fill can really do per slot. maxCrafts is happy to feed each slot from its own wood, the fill isn't: it drags one
    // wood over the lot, then the single slot path right clicks the next wood onto that, which swaps, and over satisfied takes half
    // back, forever. having enough of one wood doesn't save it, both paths pick by stack size and not by who could pay, and one slot
    // can do it all by itself (7 oak logs on the slot, 6 birch in hand, 12 wanted). so a batch is only for an ingredient we own one
    // type of, there's nothing to swap with. anything else is one craft a round: any wood satisfies a slot at one and nothing ever
    // lands on anything
    public static <T> int perSlotToFill(List<? extends List<T>> slots, ToIntFunction<T> have, int perSlot) {
        if (perSlot <= 1) return perSlot;
        for (List<T> options : slots) {
            if (options.stream().distinct().filter(option -> have.applyAsInt(option) > 0).count() > 1) return 1;
        }
        return perSlot;
    }

    private static <T> boolean feasible(List<List<T>> slots, ToIntFunction<T> have, Map<T, Integer> owned, int crafts) {
        Map<T, Integer> left = new HashMap<>();
        for (List<T> options : slots) {
            T best = null;
            int bestLeft = -1;
            for (T option : options) {
                int remaining = left.computeIfAbsent(option, k -> owned.computeIfAbsent(k, have::applyAsInt));
                if (remaining > bestLeft) {
                    best = option;
                    bestLeft = remaining;
                }
            }
            if (best == null || bestLeft < crafts) return false;
            left.put(best, bestLeft - crafts);
        }
        return true;
    }
}
