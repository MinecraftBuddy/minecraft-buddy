package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.List;

// the IRON phase's list surgery around the food gate: where the kit's food need sits in the plan, what would run without it, and
// whether a furnace screen is somebody else's. the numbers and the verdicts live in FoodPlan (leads / covered / nextTopUp), this
// only has the bits that look at the list and the world. pure, IronPhase supplies the world bits
public final class FoodGate {
    private FoodGate() {
    }

    // where the kit's own food need is in the list (the refill one, not the stock-up target or a filler's), -1 if none. the refill
    // asks for refillStop, which can be the very number a stock-up asks for (130 both), so the count alone cannot tell them apart.
    // the planners put the refill ahead of every other food need and only while FoodPlan.wantsRefill, so it is the first one then.
    // a first one asking for anything else (the target) is not it. under the floor the first one can be the floor-level bite that
    // leads the tools (FoodPlan.isRefillNeed knows both shapes), the full refill behind the tools is not what we look for then
    public static int index(List<KitNeed> needs, FoodPlan food) {
        if (!food.wantsRefill()) {
            return -1;
        }
        for (int i = 0; i < needs.size(); i++) {
            KitNeed need = needs.get(i);
            if (KitNeed.FOOD.equals(need.catalogueName())) {
                return food.isRefillNeed(need) ? i : -1;
            }
        }
        return -1;
    }

    // the job that would run if the food were not there is ore: a trip out costs the vein we are standing in
    public static boolean headIsOre(List<KitNeed> needs, int foodAt) {
        for (int i = 0; i < needs.size(); i++) {
            if (i != foodAt) {
                return "iron_ingot".equals(needs.get(i).catalogueName());
            }
        }
        return false;
    }

    // is a cook or a furnace load in the way of the soft top-up. a cook with its station is (it owns the station, the meat in it
    // is food). a furnace screen open or a smelt task that had one a moment ago is too, that is somebody's load with the meat
    // hidden in the screen, except when the food need led last tick: nothing was ahead of it, so the screen is the top-up's own
    // (it walked to the smoker for the cooked chicken), and counting it would hand the head to the iron the moment it opened,
    // again and again. same idea as IronPhase.otherLoadInFlight: whoever is running owns the screen
    public static boolean cookBusy(boolean cookStation, boolean loadInFlight, boolean ledLastTick) {
        return cookStation || (loadInFlight && !ledLastTick);
    }

    public static List<KitNeed> without(List<KitNeed> needs, int at) {
        List<KitNeed> out = new ArrayList<>(needs);
        out.remove(at);
        return out;
    }

    // the head of the list is the kit's own refill (index's test), not a stock-up that happens to be food. the two can be the same
    // record (130 both), and a stock-up reads as "keep hunting out here" to FurnacePlan.feeds: a hungry bot must never look like that
    public static boolean refillHead(List<KitNeed> needs, FoodPlan food) {
        return index(needs, food) == 0;
    }
}
