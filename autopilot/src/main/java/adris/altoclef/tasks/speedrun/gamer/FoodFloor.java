package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;

import java.util.ArrayList;
import java.util.List;

// a food need for the phases that have none of their own. FoodChain only eats, its own hunting reads two settings (the minimum
// and the amount to collect) that the gamer leaves at 0, so a bot walking to the stronghold on a bad bag just ran down to nothing.
// same floor and same top-up the IRON phase uses (the floor starts it, the overworld minimum ends it, FoodPlan.floorNext),
// overworld only: there is nothing to hunt in the nether or the End. the instance only holds the latch and the runner
public final class FoodFloor {
    // the blocks a nether trip tops the bag up to. a trip home is a short hop for a stack that the nether digs back for itself, so
    // it is not worth a long mining session. a bit over the entry stack (netherEntryBlocks) these days, no harm in that
    public static final int TRIP_BLOCKS = 64;

    private final KitRunner runner = new KitRunner();
    private boolean active;

    public void reset() {
        runner.reset();
        active = false;
    }

    // the task that gets food while the floor is breached, null when the phase should carry on with its own work
    public Task tick(GamerContext ctx) {
        FoodPlan food = ctx.food();
        boolean was = active;
        active = ctx.facts().dimension() == Dimension.OVERWORLD && food.floorNext(active);
        if (!active) {
            if (was) {
                runner.reset();
            }
            return null;
        }
        return runner.run(ctx, List.of(new KitNeed(KitNeed.FOOD, food.overworldMinimum())));
    }

    // the same hunt for a nether trip: the bag is nowhere near the floor by then, so it runs from the first overworld tick
    // to the minimum and not only under the floor. one runner over whichever needs are short: food to the minimum, and on a blocks
    // trip the TRIP_BLOCKS stack (emitted straight, no BuildBlocks band; KitRunner still notes a refill, which the next phase entry wipes). null = none short
    public Task tickTrip(GamerContext ctx, boolean wantBlocks) {
        return tickTrip(ctx, wantBlocks, 0);
    }

    // endUnits > 0 = the trip's own stop (FoodTripRules.tripEndUnits) instead of the plan's
    public Task tickTrip(GamerContext ctx, boolean wantBlocks, int endUnits) {
        boolean overworld = ctx.facts().dimension() == Dimension.OVERWORLD;
        FoodPlan food = overworld ? ctx.food() : null;
        List<KitNeed> needs = new ArrayList<>();
        int end = food == null ? 0 : endUnits > 0 ? Math.min(endUnits, food.tripEnd()) : food.tripEnd();
        if (food != null && FoodTripRules.tripFoodShort(FoodTripRules.units(food), end)) {
            // the runner reads the need against held, which counts what is still cooking: ask for that on top so it is the bag
            // that has to reach the trip's end (a real bag, not the 70 that sent the bot straight back down for another trip)
            needs.add(new KitNeed(KitNeed.FOOD, end + food.pending()));
        }
        if (overworld && wantBlocks && blocksShort(ctx)) {
            needs.add(new KitNeed(KitNeed.BUILD_BLOCKS, TRIP_BLOCKS));
        }
        boolean was = active;
        active = !needs.isEmpty();
        if (!active) {
            if (was) {
                runner.reset();
            }
            return null;
        }
        return runner.run(ctx, needs);
    }

    // the bag is under the overworld minimum. held counts what is still cooking in a smoker too, and a trip that is judged on that
    // goes back down without the food it came up for (FoodTripRules.units is the bag alone)
    public static boolean foodShort(GamerContext ctx) {
        FoodPlan food = ctx.food();
        return FoodTripRules.units(food) < food.overworldMinimum();
    }

    // the trip's own read: the bag is under where a trip ends (tripEnd, which settles for the minimum on a full bag). foodShort stays
    // the overworld floor's line for everyone who only asks "is the bag bad", the trip wants a real pile before it goes back down
    public static boolean tripFoodShort(GamerContext ctx) {
        FoodPlan food = ctx.food();
        return FoodTripRules.tripFoodShort(FoodTripRules.units(food), food.tripEnd());
    }

    // the trip's read against its own stop (FoodTripRules.tripEndUnits, 0 = the plan's), same clamp tickTrip uses
    public static boolean tripFoodShort(GamerContext ctx, int endUnits) {
        FoodPlan food = ctx.food();
        int end = endUnits > 0 ? Math.min(endUnits, food.tripEnd()) : food.tripEnd();
        return FoodTripRules.tripFoodShort(FoodTripRules.units(food), end);
    }

    public static boolean blocksShort(GamerContext ctx) {
        return ctx.facts().buildBlocks() < TRIP_BLOCKS;
    }

    public boolean active() {
        return active;
    }

    public String hud() {
        return runner.hud();
    }
}
