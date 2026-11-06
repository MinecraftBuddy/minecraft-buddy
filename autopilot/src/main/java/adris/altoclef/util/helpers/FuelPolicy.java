package adris.altoclef.util.helpers;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// which of the fuel in the bag a furnace may eat. the default is everything supported, same as ever. a caller with plans for
// the wood (the gamer, which chops its whole wood budget up front) sets a reserve, and then logs and planks only burn above
// it, and coal or charcoal is picked first when it covers the job. static because the smelt tasks cannot see their owner
public final class FuelPolicy {
    // wood we keep, logs and planks counted separately, shared across species
    private static volatile int keepLogs;
    private static volatile int keepPlanks;
    private static volatile boolean preferCoal;

    private FuelPolicy() {
    }

    public static void set(int logs, int planks, boolean coalFirst) {
        keepLogs = Math.max(0, logs);
        keepPlanks = Math.max(0, planks);
        preferCoal = coalFirst;
    }

    public static void clear() {
        set(0, 0, false);
    }

    private static boolean isCoal(Item item) {
        return item == Items.COAL || item == Items.CHARCOAL;
    }

    private static boolean in(Item item, Item[] group) {
        for (Item i : group) {
            if (i == item) {
                return true;
            }
        }
        return false;
    }

    // how much of each stack may burn. the log reserve is one pool over every species, a stack gives up what is left of it.
    // the pool is handed out by item and size, never by where a stack sits: by list order, lifting the acacia onto the cursor
    // to put it in the furnace moved it in the list, the reserve landed on it instead of the oak, the oak became the pick, the
    // acacia went back, and the bot swapped logs on the cursor four times a second with the furnace window open
    public static int[] usable(List<ItemStack> stacks) {
        Item[] items = new Item[stacks.size()];
        int[] counts = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = stacks.get(i).getItem();
            counts[i] = stacks.get(i).getCount();
        }
        return usable(items, counts, keepLogs, keepPlanks);
    }

    // the same over plain counts and a reserve of the caller's choosing: items[i] is held counts[i] times, the answer is how many of
    // each may burn. this IS the rule, the stack form above and the gamer's wood budget (what its cook counts as fuel) both go
    // through it, so what the gate counts is what the furnace will be handed, species the furnace won't touch included
    public static int[] usable(Item[] items, int[] counts, int reserveLogs, int reservePlanks) {
        int[] out = new int[items.length];
        int logs = Math.max(0, reserveLogs);
        int planks = Math.max(0, reservePlanks);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, String>comparing(i -> items[i].getDescriptionId()).thenComparing(i -> -counts[i]));
        for (int i : order) {
            int count = counts[i];
            if (in(items[i], ItemHelper.LOG)) {
                int kept = Math.min(count, logs);
                logs -= kept;
                count -= kept;
            } else if (in(items[i], ItemHelper.PLANKS)) {
                int kept = Math.min(count, planks);
                planks -= kept;
                count -= kept;
            }
            out[i] = count;
        }
        return out;
    }

    // fuel units in the bag that may burn, what calculateInventoryFuelCount is
    public static double usableFuel(List<ItemStack> stacks, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        int[] usable = usable(stacks);
        double total = 0;
        for (int i = 0; i < usable.length; i++) {
            Item item = stacks.get(i).getItem();
            if (supported.test(item)) {
                total += fuelPerItem.applyAsDouble(item) * usable[i];
            }
        }
        return total;
    }

    // count = what the fuel slot should hold once the move is done. choose only ever sees an empty slot, where that is also how
    // many to move, and the move task counts the destination slot anyway
    public record Pick(ItemStack stack, int count) {
    }

    // the stack to put in the fuel slot for a job that needs `needs` smelts of fuel, null when nothing may burn. the choice
    // among the candidates is the one the smelt tasks always had (closest to the need without going under, else the biggest
    // that falls short); the only new things are the reserve and "coal first if coal alone covers it"
    public static Pick choose(List<ItemStack> stacks, double needs, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        int[] usable = usable(stacks);
        boolean coalOnly = false;
        double coal = 0;
        double charcoal = 0;
        if (preferCoal) {
            for (int i = 0; i < usable.length; i++) {
                Item item = stacks.get(i).getItem();
                if (supported.test(item)) {
                    double fuel = fuelPerItem.applyAsDouble(item) * usable[i];
                    if (item == Items.COAL) {
                        coal += fuel;
                    } else if (item == Items.CHARCOAL) {
                        charcoal += fuel;
                    }
                }
            }
            // per item, not coal and charcoal pooled: the slot takes one item, and 24 smelts of each is not a coal that covers 37.
            // pooled, the pick landed on one of them, the top-up could not finish it, and a wood stack that covered was never asked
            coalOnly = coal >= needs || charcoal >= needs;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < usable.length; i++) {
            Item item = stacks.get(i).getItem();
            // coal first means a coal item that covers the job by itself, not any coal at all
            boolean coalThatCovers = (item == Items.COAL && coal >= needs) || (item == Items.CHARCOAL && charcoal >= needs);
            if (usable[i] > 0 && supported.test(item) && (!coalOnly || coalThatCovers)) {
                candidates.add(i);
            }
        }
        // a tie (two log species both covering the job) goes to the same item every tick, not to whichever stack the list shows
        // first, same reason as the order in usable
        candidates.sort(Comparator.comparing(i -> stacks.get(i).getItem().getDescriptionId()));
        int best = -1;
        double closestDelta = Double.NEGATIVE_INFINITY;
        for (int i : candidates) {
            double delta = needs - fuelPerItem.applyAsDouble(stacks.get(i).getItem()) * usable[i];
            if (best == -1 || (closestDelta > 0 && delta < closestDelta) || (closestDelta < 0 && delta < 0 && delta > closestDelta)) {
                best = i;
                closestDelta = delta;
            }
        }
        if (best == -1) {
            return null;
        }
        Item item = stacks.get(best).getItem();
        int count = usable[best];
        // wood only goes in as far as the job needs it: a stack of coal in the slot comes back out at the collect and costs nothing,
        // but fourteen logs sitting in a smoker are fourteen logs the wood budget cannot see until then
        double per = fuelPerItem.applyAsDouble(item);
        if (!isCoal(item) && per > 0) {
            count = Math.min(count, Math.max(1, (int) Math.ceil(needs / per)));
        }
        return new Pick(stacks.get(best), count);
    }

    // choose for a fuel slot that may already hold something. what is in it stays: a different fuel moved onto it swaps the two,
    // the other one looks like the better pick on the next tick and they trade places for ever. so with a stack in the slot the
    // only pick is more of the same item, topped up to a full stack at most. `needs` is what the slot still lacks (the caller
    // already counted the slot's own fuel), the reserve and coal first still run over the whole bag, the slot's item is only a
    // filter on who may be picked. null = nothing to add
    public static Pick chooseFor(List<ItemStack> stacks, ItemStack slot, double needs, Predicate<Item> supported,
                                 ToDoubleFunction<Item> fuelPerItem) {
        if (needs <= 0) {
            return null;
        }
        if (slot.isEmpty()) {
            return choose(stacks, needs, supported, fuelPerItem);
        }
        Item held = slot.getItem();
        int room = slot.getMaxStackSize() - slot.getCount();
        Pick pick = room > 0 ? choose(stacks, needs, supported.and(item -> item == held), fuelPerItem) : null;
        return pick == null ? null : new Pick(pick.stack(), slot.getCount() + Math.min(pick.count(), room));
    }

    // the one time a stack in the slot may be swapped out: a different fuel in the bag covers the WHOLE job on its own (`needs` is
    // what the slot still lacks, so the whole job is that plus the slot's fuel; the lit part and the arrow are already off it).
    // once it is in the job is covered and nothing wants the old stack back, which is the thing chooseFor's rule protects against.
    // the smelt tasks use this as a yes/no and do the swap in two steps (empty the slot, then the ordinary fill puts a covering pick
    // in, AsyncSmelting.emptyFuelSlot), so what goes in is sized by the fill and the reserve, not by one click. the caller waits for
    // the shortage to hold before it acts on this, the reading of the fire lags a tick behind a fill. null = no such pick
    public static Pick chooseSwap(List<ItemStack> stacks, ItemStack slot, double needs, Predicate<Item> supported,
                                  ToDoubleFunction<Item> fuelPerItem) {
        if (needs <= 0 || slot.isEmpty()) {
            return null;
        }
        Item held = slot.getItem();
        double whole = needs + fuelPerItem.applyAsDouble(held) * slot.getCount();
        return chooseCovering(stacks, whole, supported.and(item -> item != held), fuelPerItem);
    }

    // choose, but null unless the pick alone covers the job. the collect visit uses it: a stalled station gets its fuel only when
    // that is the whole job, otherwise the input comes back out as it always did
    public static Pick chooseCovering(List<ItemStack> stacks, double needs, Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        Pick pick = choose(stacks, needs, supported, fuelPerItem);
        if (pick == null || fuelPerItem.applyAsDouble(pick.stack().getItem()) * pick.count() < needs) {
            return null;
        }
        return pick;
    }
}
