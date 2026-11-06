package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// the standard speedrun move, stretched: the first ore goes in the furnace the moment there is enough of it, and out comes the
// iron pickaxe, the chestplate, the shield and the shears instead of the stone pick grinding through 39 ore at armor 0. the
// whole kit's batch used to be the first armor, ten minutes of caves later, and the skeletons did not wait that long.
// the name stuck from when it was only the pick. pure (facts in, answers out) so the trigger is testable, the planner and
// IronPhase ask these
public final class EarlyIronPick {
    // what the early batch makes, in two stages. armor first: the stone pick mines iron fine, it is the chestplate (6 of iron's
    // 15 armor points in one craft) and the shield that keep us alive while we dig. 3 chest ingots no longer make an instant pick
    // and that is ok. stage two is the pick and the shears (2, sheared sheep give 1 to 3 wool and stay alive for the next one, a
    // killed one gives 1). the stages fire one after the other, see batch
    private static final List<List<String>> STAGES = List.of(
            List.of("iron_chestplate", "shield"), List.of("iron_pickaxe", "shears"));
    // all of it in ingot order, stage one then stage two
    static final List<String> ITEMS = List.of("iron_chestplate", "shield", "iron_pickaxe", "shears");
    // a shield is 6 planks and a log is 4 of them
    private static final int SHIELD_PLANKS = 6;

    private EarlyIronPick() {
    }

    // the early items the kit still owes, in ITEMS order. checked from what we hold and wear, no run flag, so a death that lost
    // the gear fires it again. an item DiamondSpend is about to outclass is not owed (KitPlanner.ironItemsOwed leaves it out): eight
    // ingots for a chestplate the diamond one replaces the moment it is crafted is a waste of a furnace load
    static List<String> owed(GamerFacts f, OverworldConfig cfg) {
        if (!f.earlyIronPick()) {
            return List.of();
        }
        Set<String> owed = KitPlanner.ironItemsOwed(f, cfg);
        List<String> out = new ArrayList<>();
        for (String item : ITEMS) {
            // one early shield, the spares (KitPlanner.ironKit) are the normal crafts' business
            if (owed.contains(item) && (!"shield".equals(item) || KitPlanner.have(f, item) == 0)) {
                out.add(item);
            }
        }
        return out;
    }

    // the setting is on and the kit still owes any of the early items
    static boolean wanted(GamerFacts f, OverworldConfig cfg) {
        return !owed(f, cfg).isEmpty();
    }

    // how many ingots the early batch is: a running total over the stages that stops at the first stage the ingots in the bag do
    // not cover. 9 on a fresh run (chestplate and shield), then 5 for the pick and shears once those are crafted. a shield that
    // waits on planks costs 1 and then the stage two total is 6, so the pick is not stuck behind a wood trip. same function for
    // need() and the head check, so isEarlyBatch stays right
    public static int batch(GamerFacts f, OverworldConfig cfg) {
        List<String> owed = owed(f, cfg);
        int ingots = f.count(Items.IRON_INGOT);
        int cum = 0;
        for (List<String> stage : STAGES) {
            for (String item : stage) {
                if (owed.contains(item)) {
                    cum += KitPlanner.ingotCost(item);
                }
            }
            if (cum > ingots) {
                return cum;
            }
        }
        return cum;
    }

    // time to put the early batch in. pending ingots count: one early job at a time, it does not fire again while those cook,
    // nor once the ingots are in the bag, nor after the items exist (wanted). totalIngots is the whole kit's, when all of it
    // is already mined (or cooking) the big batch is one smelt and two would only be slower
    public static boolean due(GamerFacts f, OverworldConfig cfg, int totalIngots) {
        int batch = batch(f, cfg);
        if (batch == 0) {
            return false;
        }
        int ingots = f.count(Items.IRON_INGOT);
        if (ingots >= batch || f.pendingOutput(Items.IRON_INGOT) > 0) {
            return false;
        }
        // the first raw iron going into the furnace used to end the trigger (the bag count dropped under the batch), the head
        // went to the whole kit's need, and that one even fetched the ore back out of the furnace. once started it is due until
        // it is cooking
        if (f.earlyLoadInFlight()) {
            return true;
        }
        int raw = f.count(Items.RAW_IRON);
        return raw >= batch - ingots && raw + ingots < totalIngots;
    }

    // IronPhase calls this every tick it plans. the tick the early batch becomes the head is the start of the load, and it
    // lasts until the job is recorded (GamerTask.takeLoadedFurnaces), the ingots are in the bag, or the window runs out
    // (FurnacePlan.earlyLoadInFlight). the window only has to cover the load, the cook itself is a recorded job by then
    public static void track(RunState state, KitNeed head, GamerFacts f, OverworldConfig cfg) {
        if (isEarlyBatch(head, f, cfg)) {
            if (!f.earlyLoadInFlight()) {
                state.earlyLoadTick = f.gameTime();
            }
        } else if (state.earlyLoadTick >= 0 && !due(f, cfg, KitPlanner.ingotsNeeded(f, cfg))) {
            state.earlyLoadTick = -1;
        }
    }

    // a drained job ends the early load only if it is the iron cooking. a meat job landing mid load used to clear the latch,
    // due() dropped and the head flipped away from the furnace we were standing at (the #40 flip)
    public static boolean endsLoad(List<RunState.FurnaceJob> drained) {
        for (RunState.FurnaceJob job : drained) {
            if ("iron_ingot".equals(job.output)) {
                return true;
            }
        }
        return false;
    }

    // "hold this many ingots", the catalogue smelts exactly that many and leaves the rest of the raw iron alone
    public static KitNeed need(GamerFacts f, OverworldConfig cfg) {
        return new KitNeed("iron_ingot", batch(f, cfg));
    }

    // is this head the early batch. SmeltSurface must leave it alone: it is smelted right where we stand, climbing out of the
    // mine for it would cost more than the furnace does
    public static boolean isEarlyBatch(KitNeed head, GamerFacts f, OverworldConfig cfg) {
        return head != null && "iron_ingot".equals(head.catalogueName()) && head.count() == batch(f, cfg)
                && due(f, cfg, KitPlanner.ingotsNeeded(f, cfg));
    }

    // the early crafts the ingots in the bag pay for right now, in ITEMS order: these go in front of a single ore more.
    // the ingots go down the list and stop at the first item they do not cover, so a short bag (a chest's three, a load that
    // came back light) never spends the pick's ingots on shears. the shield is skipped, not stopped at, when the bag has no
    // planks for it: down a cave that would be a wood trip, so the chestplate and the shears go ahead and it waits its turn
    static List<String> front(GamerFacts f, OverworldConfig cfg) {
        List<String> out = new ArrayList<>();
        int ingots = f.count(Items.IRON_INGOT);
        for (String item : owed(f, cfg)) {
            if ("shield".equals(item) && !shieldWoodInHand(f)) {
                continue;
            }
            int cost = KitPlanner.ingotCost(item);
            if (cost > ingots) {
                break;
            }
            out.add(item);
            ingots -= cost;
        }
        return out;
    }

    // planks for the shield without leaving: planks in the bag, or logs to turn into them in the 2x2
    static boolean shieldWoodInHand(GamerFacts f) {
        return f.count(ItemHelper.PLANKS) + 4 * f.count(ItemHelper.LOG) >= SHIELD_PLANKS;
    }

    // a bot still owed early items with their ingots cooking goes back the moment they are done, not at the next need boundary
    // (the mining need is one long task and its boundary is 26 ingots away)
    public static boolean collectNow(GamerFacts f, OverworldConfig cfg) {
        return f.pendingOutput(Items.IRON_INGOT) > 0 && f.count(Items.IRON_INGOT) < batch(f, cfg);
    }
}
