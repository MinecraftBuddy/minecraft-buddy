package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// (the buddy's fork, Tuning wood_fuel, a fix of 13 Nov 2026, on) logs and planks in the bag burn in the furnace, at the game's
// burn times (a log or a plank 1.5 smelts), past the wood the task under way still crafts from: the planks of its recipes, a
// crafting table's four when one is crafted with none in the bag, and the planks of the sticks the bag lacks. the default fuel
// list is coal and charcoal only, and one smelt for the shield's ingot became a coal branch mine at y 45 that went round its own
// staircase for 13 minutes with 9 oak logs in the bag (test heel, look 81); three runs churned "Collect Fuel x7". the kept wood
// goes to FuelPolicy as its plan, which keeps it out of the count and the slot alike (a gamer run's own reserve comes first)
public final class WoodFuel {
    private WoodFuel() {
    }

    // a fuel trip this many smelts short or less fetches logs and not coal: six logs cover it, one tree
    public static final double LOGS_UPTO = 8;
    // smelts a log burns (300 game ticks of a 200 tick smelt), for the logs a trip asks for
    public static final double LOG_SMELTS = 1.5;
    // planks a log makes, and a stick slot's share of a plank (two planks make four sticks)
    static final int LOG_PLANKS = 4;
    static final int TABLE_PLANKS = 4;
    // a recipe target's crafts counted at most this many (bread asks for 99999999)
    private static final int MAX_CRAFTS = 64;

    public static boolean on() {
        return Tuning.get("wood_fuel", 0) > 0;
    }

    // a log or a plank the game burns (crimson and warped wood do not)
    public static boolean wood(Item item) {
        return (in(item, ItemHelper.LOG) || in(item, ItemHelper.PLANKS)) && ItemHelper.isFuel(item);
    }

    private static boolean in(Item item, Item[] group) {
        for (Item i : group) {
            if (i == item) return true;
        }
        return false;
    }

    // the plan for FuelPolicy from the task under way, 0 with the key off. called where the bag's fuel is counted, so the slot's
    // fill later in the same tick reads the same plan
    public static void refresh(AltoClef mod) {
        // (Tuning stick_reserve, a fix of 14 Nov 2026, on) and the next pickaxe's wood on top: 3 planks (or a log) and the planks
        // of 2 sticks while the bag holds fewer, StickReserve
        int keep = StickReserve.on() ? StickReserve.fuelKeepPlanks(mod.getItemStorage().getItemCount(Items.STICK)) : 0;
        FuelPolicy.plan(on() ? planPlanks(mod) + keep : 0);
    }

    // one recipe of the task chain as the plan sees it: its planks, log and stick slots per craft, and the crafts still to do
    record Recipe(int planks, int logs, int sticks, int crafts) {
    }

    // the planks the recipes still take, with a table's four when a table craft is under way and none is in the bag, and the
    // planks of the sticks they take beyond the ones held, four sticks from two planks
    static int planPlanks(List<Recipe> recipes, boolean tableWanted, int sticksHeld) {
        long planks = tableWanted ? TABLE_PLANKS : 0;
        long sticks = 0;
        for (Recipe r : recipes) {
            planks += (long) r.crafts() * (r.planks() + (long) LOG_PLANKS * r.logs());
            sticks += (long) r.crafts() * r.sticks();
        }
        long lacking = Math.max(0, sticks - sticksHeld);
        planks += 2 * ((lacking + 3) / 4);
        return (int) Math.min(planks, 4096);
    }

    // the logs a fuel trip asks the bag to hold: what it holds, the logs for the smelts still missing past the plan's wood, and
    // the logs for the plan's wood the bag lacks (the plan's wood is kept, so a log for it burns nothing)
    public static int logsWanted(double missingSmelts, int logsHeld, int planksHeld, int planPlanks) {
        int forSmelts = missingSmelts <= 0 ? 0 : (int) Math.ceil(missingSmelts / LOG_SMELTS - 1e-9);
        int planShort = Math.max(0, planPlanks - planksHeld - LOG_PLANKS * logsHeld);
        return logsHeld + forSmelts + (planShort + LOG_PLANKS - 1) / LOG_PLANKS;
    }

    // logs and not coal: few smelts short, or no coal ore known within the coal task's reach
    public static boolean logsInstead(double missingSmelts, boolean coalNear) {
        return missingSmelts <= LOGS_UPTO || !coalNear;
    }

    // the recipes of the craft tasks on the user chain's path (the task under way and the ones over it)
    private static int planPlanks(AltoClef mod) {
        List<Task> path;
        try {
            path = new ArrayList<>(mod.getUserTaskChain().getTasks());
        } catch (RuntimeException e) {
            // the chain's list changed under the copy: no plan this time, the next count has it
            return FuelPolicy.plan();
        }
        List<Recipe> recipes = new ArrayList<>();
        boolean table = false;
        for (Task task : path) {
            if (task instanceof CraftInTableTask craft) {
                table = true;
                for (RecipeTarget target : craft.getRecipeTargets()) add(mod, recipes, target);
            } else if (task instanceof CraftInInventoryTask craft) {
                add(mod, recipes, craft.getRecipeTarget());
            }
        }
        boolean tableWanted = table && mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) == 0;
        return planPlanks(recipes, tableWanted, mod.getItemStorage().getItemCount(Items.STICK));
    }

    private static void add(AltoClef mod, List<Recipe> out, RecipeTarget target) {
        if (target == null || target.getRecipe() == null) return;
        CraftingRecipe recipe = target.getRecipe();
        int planks = 0, logs = 0, sticks = 0;
        for (ItemTarget slot : recipe.getSlots()) {
            if (slot == null || slot.isEmpty()) continue;
            Item[] matches = slot.getMatches();
            if (matches.length == 0) continue;
            if (anyIn(matches, ItemHelper.PLANKS)) planks++;
            else if (anyIn(matches, ItemHelper.LOG)) logs++;
            else if (anyIs(matches, Items.STICK)) sticks++;
        }
        if (planks + logs + sticks == 0) return;
        int held = target.getOutputItem() == null ? 0 : mod.getItemStorage().getItemCount(target.getOutputItem());
        int left = Math.max(0, target.getTargetCount() - held);
        int crafts = Math.min(MAX_CRAFTS, (left + recipe.outputCount() - 1) / Math.max(1, recipe.outputCount()));
        if (crafts > 0) out.add(new Recipe(planks, logs, sticks, crafts));
    }

    private static boolean anyIn(Item[] items, Item[] group) {
        for (Item item : items) {
            if (in(item, group)) return true;
        }
        return false;
    }

    private static boolean anyIs(Item[] items, Item one) {
        for (Item item : items) {
            if (item == one) return true;
        }
        return false;
    }
}
