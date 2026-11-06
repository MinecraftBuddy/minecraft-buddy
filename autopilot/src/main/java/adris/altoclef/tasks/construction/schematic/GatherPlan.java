package adris.altoclef.tasks.construction.schematic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

// what to go and get for a schematic before (or between) builder runs. pure: item ids and slot counts in, a shopping
// list out, so the stack and slot maths has tests and no game
public final class GatherPlan {

    // kept empty for what a gather makes on the way (logs before planks, cobble before stone, a pickaxe), or the last
    // free slot fills with planks and the logs for the next craft have nowhere to go
    static final int RESERVED_SLOTS = 2;

    // one main inventory slot. item null = empty, max = how many of that item fit in a slot
    public record Slot(String item, int count, int max) {
        public static Slot empty() {
            return new Slot(null, 0, 0);
        }
    }

    // batch: item -> how many more to get this round. ungettable: item -> how many short, nothing in the catalogue
    // makes it. more: something gettable didn't fit this round, so there will be another one
    public record Plan(Map<String, Integer> batch, Map<String, Integer> ungettable, boolean more) {
        public boolean nothingToGet() {
            return batch.isEmpty();
        }

        // gettable things are short and not one of them fits: the bag is full of something else
        public boolean bagFull() {
            return batch.isEmpty() && more;
        }
    }

    private GatherPlan() {
    }

    // need is what the rest of the build still takes, by item, biggest first (MaterialList's order, and the order the
    // batch fills in). have is what the player carries, slots the 36 main slots as they are right now
    public static Plan plan(Map<String, Integer> need, Map<String, Integer> have, Predicate<String> gettable,
                            List<Slot> slots, ToIntFunction<String> stackSize) {
        Map<String, Integer> batch = new LinkedHashMap<>();
        Map<String, Integer> ungettable = new LinkedHashMap<>();
        boolean more = false;
        int free = usableSlots(slots);
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            String item = e.getKey();
            int shortBy = e.getValue() - have.getOrDefault(item, 0);
            if (shortBy <= 0) {
                continue;
            }
            if (!gettable.test(item)) {
                ungettable.put(item, shortBy);
                continue;
            }
            int stack = Math.max(1, stackSize.applyAsInt(item));
            int room = roomIn(slots, item);
            // a slot already holding some of it fills up first, then whole free slots
            long fits = room + (long) free * stack;
            int take = (int) Math.min(shortBy, fits);
            if (take < shortBy) {
                more = true;
            }
            if (take <= 0) {
                continue;
            }
            free -= slotsFor(take - room, stack);
            batch.put(item, take);
        }
        return new Plan(batch, ungettable, more);
    }

    // the bill for the rest of the build, topped up with what the builder just said it misses. the builder only sees
    // the cells near it, the diff sees all of them, but a diff that disagrees (a substitute, a selection) shouldn't send
    // us back out with nothing for the thing the builder is stuck on
    public static Map<String, Integer> need(Map<String, Integer> left, Map<String, Integer> builderMissing) {
        Map<String, Integer> out = new LinkedHashMap<>(left);
        if (builderMissing != null) {
            builderMissing.forEach((item, n) -> out.merge(item, n, Math::max));
        }
        return out;
    }

    // the free slots a batch may fill. the reserve never takes the last one, a bag with one hole still gets something
    static int usableSlots(List<Slot> slots) {
        int empty = 0;
        for (Slot s : slots) {
            if (s.item() == null || s.count() <= 0) {
                empty++;
            }
        }
        return empty == 0 ? 0 : Math.max(1, empty - RESERVED_SLOTS);
    }

    // what still fits on top of the stacks of this item we already carry
    static int roomIn(List<Slot> slots, String item) {
        int room = 0;
        for (Slot s : slots) {
            if (item.equals(s.item()) && s.count() > 0) {
                room += Math.max(0, s.max() - s.count());
            }
        }
        return room;
    }

    static int slotsFor(int items, int stack) {
        return items <= 0 ? 0 : (items + stack - 1) / stack;
    }
}
