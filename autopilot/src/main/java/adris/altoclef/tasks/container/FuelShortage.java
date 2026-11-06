package adris.altoclef.tasks.container;

import adris.altoclef.util.helpers.FuelPolicy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// "we are out of fuel" for the smelt tasks, but only once it has been true for a moment. the tick after a fuel click the bag, the
// cursor and the cached furnace slots can disagree for a bit (the live log had the bot leave the open smoker for a coal trip with
// the coal half in; the main cause was the cook mode not counting the slot's fuel, fixed in fuelNeeded, this stays as the net for
// whatever else is in flight). the real thing stays true, the blip does not. pure so it can be tested without a game
final class FuelShortage {
    // half a second, longer than the two clicks of a move at the default 0.2 s delay
    static final long HOLD_TICKS = 10;
    // asked less often than this and it is a new question, not the same shortage still going
    private static final long GAP_TICKS = 40;

    private long since = -1;
    private long last = -1;

    // feed it whenever the question is asked. true once the shortage has held for HOLD_TICKS
    boolean confirmed(boolean lacking, long now) {
        boolean stale = last >= 0 && now - last > GAP_TICKS;
        last = now;
        if (!lacking || stale) {
            since = -1;
        }
        if (!lacking) {
            return false;
        }
        if (since < 0) {
            since = now;
        }
        return now - since >= HOLD_TICKS;
    }

    void reset() {
        since = -1;
        last = -1;
    }

    // smelts the bag has to hold before the bot goes back to the open station, 0 = not on a fuel trip. set by the check inside the
    // screen: once it has shut the screen the outer check must not go through its own half second first, the station is right
    // there, so it would reopen, read a lit number and close again until the fire ran out
    private double fetch;

    void fetchUntil(double smelts) {
        fetch = smelts;
    }

    // the trip target while the bag is still short of it, 0 once it holds it
    double fetchTarget(double bagFuel) {
        if (fetch > 0 && bagFuel >= fetch) {
            fetch = 0;
        }
        return fetch;
    }

    // smelts of fuel still to find for a smelt task. `slotMaterials` is what the input slot holds (the cook mode, ignoreMaterials,
    // fuels exactly that), otherwise the target less the output we already have. the fuel already in the station comes off in
    // both modes: the cook mode skipped it once, and coal in the slot (not lit yet, bag empty) read as the whole batch short
    static double needed(boolean ignoreMaterials, int slotMaterials, int target, int outInBag, int outInSlot, double fuelInStation) {
        double base = ignoreMaterials ? Math.min(slotMaterials, target) : target - outInBag - outInSlot;
        return base - fuelInStation;
    }

    // smelts of fuel a station with `input` items in it is still short of: its own slot, what is lit and the progress on the
    // item in hand all count, same sum the smelt tasks use for fuelNeeded
    static double missing(int input, double lit, double progress, double slotFuel) {
        return input - (lit + progress + slotFuel);
    }

    // the move that puts more fuel into the slot of a station with `input` items in it, null when the slot already covers the job or
    // nothing in the bag may go in. one rule for the furnace, the smoker and the blast furnace. the slot counts toward the job
    // here (it used to be only what was lit, so four coal waiting in the slot read as no fuel at all and the planks behind them
    // got picked to make up for it). the units are smelts, lit comes in the station's own scale (StorageHelper.litItems)
    static FuelPolicy.Pick fill(List<ItemStack> bag, ItemStack slot, int input, double lit, double progress,
                                Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        double slotFuel = slot.isEmpty() ? 0 : fuelPerItem.applyAsDouble(slot.getItem()) * slot.getCount();
        return FuelPolicy.chooseFor(bag, slot, missing(input, Math.max(lit, 0), Math.max(progress, 0), slotFuel), supported, fuelPerItem);
    }

    // fill for one load of a split batch: the bag holds every load's fuel (SmeltSplit.fuelStep), and choose puts coal in as the
    // whole stack, so load 1 would burn through furnace 2's coal and furnace 2 would go fetch. any item is capped at what this
    // load is short of, rounded up to a whole item. the cap is on the slot's total, the same count chooseFor hands back
    static FuelPolicy.Pick fillShare(List<ItemStack> bag, ItemStack slot, int input, double lit, double progress,
                                     Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        FuelPolicy.Pick pick = fill(bag, slot, input, lit, progress, supported, fuelPerItem);
        if (pick == null) {
            return null;
        }
        double each = fuelPerItem.applyAsDouble(pick.stack().getItem());
        if (each <= 0) {
            return pick;
        }
        double slotFuel = slot.isEmpty() ? 0 : fuelPerItem.applyAsDouble(slot.getItem()) * slot.getCount();
        double short_ = missing(input, Math.max(lit, 0), Math.max(progress, 0), slotFuel);
        int cap = (slot.isEmpty() ? 0 : slot.getCount()) + Math.max(1, (int) Math.ceil(short_ / each - 1e-9));
        return cap < pick.count() ? new FuelPolicy.Pick(pick.stack(), cap) : pick;
    }

    // fill's other half: the pick that replaces what is in the slot, only when it covers the whole job by itself (FuelPolicy.chooseSwap).
    // asked after stuckShort has held, not before
    static FuelPolicy.Pick swap(List<ItemStack> bag, ItemStack slot, int input, double lit, double progress,
                                Predicate<Item> supported, ToDoubleFunction<Item> fuelPerItem) {
        double slotFuel = slot.isEmpty() ? 0 : fuelPerItem.applyAsDouble(slot.getItem()) * slot.getCount();
        return FuelPolicy.chooseSwap(bag, slot, missing(input, Math.max(lit, 0), Math.max(progress, 0), slotFuel), supported, fuelPerItem);
    }

    // asked once fill came back empty: the slot has fuel in it, the job needs more, and nothing more may go in (what the bag has is
    // another fuel, and moving it in would swap the slot's) but that other fuel is enough to finish the job at the next visit. the
    // station is as full as it gets, so the bot leaves it instead of standing there until the slot empties. with too little in the
    // bag it is `dry` instead and gets fetched
    static boolean stuckShort(int input, double lit, double progress, double slotFuel, double bagFuel) {
        double missing = missing(input, lit, progress, slotFuel);
        return slotFuel > 0 && missing > 0 && bagFuel >= missing;
    }

    // out of fuel with the screen open: it is short and nothing in the bag (usable, see FuelPolicy) makes up the difference.
    // then the bot closes up and fetches it instead of standing at the gui saying "Waiting..." for ever
    static boolean dry(int input, double lit, double progress, double slotFuel, double bagFuel) {
        double missing = missing(input, lit, progress, slotFuel);
        return input > 0 && missing > 0 && bagFuel < missing;
    }
}
