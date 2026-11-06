package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// pure: which way we build the portal, and what we must hold before we start walking away from the overworld
public final class PortalPlanner {
    public enum Method {
        // lava lake + water + buckets, no diamonds needed
        CAST,
        // mine or place obsidian with a diamond pickaxe and build the frame
        OBSIDIAN
    }

    private PortalPlanner() {
    }

    public static Method parse(String name) {
        if (name != null) {
            for (Method m : Method.values()) {
                if (m.name().equalsIgnoreCase(name)) {
                    return m;
                }
            }
        }
        return Method.CAST;
    }

    // the lava pool build goes first when it is switched on and has not already given up. OBSIDIAN is the sticky "no lava
    // way works" answer, so it never tries the pool again, and a failed pool hands over to the cast for the rest of the phase
    public static boolean usePool(boolean enabled, boolean poolFailed, Method current) {
        return enabled && !poolFailed && current != Method.OBSIDIAN;
    }

    // castSeconds = how long the cast has been running (not the prep before it). OBSIDIAN is sticky, going back and
    // forth would just burn the budget twice. DefaultGoToDimensionTask never finishes when there is no lava lake, it
    // wanders, so the clock is the only thing that ends a cast. sinceProgressSeconds = how long since a frame cell last
    // turned obsidian, castSeconds when none has: a cast still landing cells is not walked away from on the clock alone
    // (that mined a half done frame to build a new one), only once it has stalled too
    // sawLava is false for a tracker that has never seen any and for a fetch that finds no source it can use (a lake 60 blocks
    // down, every pool banned): both are "no lava", and the no lava line below is the way out
    public static Method decide(Method current, double castSeconds, double sinceProgressSeconds, OverworldConfig cfg,
                                boolean sawLava, boolean hasDiamondPickaxe) {
        if (current == Method.OBSIDIAN) {
            return Method.OBSIDIAN;
        }
        // the obsidian way mines it and that wants a diamond pickaxe. without one it is no way out at all, so the cast keeps
        // wandering for a lake and the phase budget is what ends it
        if (!hasDiamondPickaxe) {
            return Method.CAST;
        }
        if (castSeconds >= cfg.castGiveUpMinutes * 60 && sinceProgressSeconds >= cfg.castStallSeconds) {
            return Method.OBSIDIAN;
        }
        // no lava anywhere and the pickaxe is already in hand: obsidian is the cheap way now
        if (!sawLava && sinceProgressSeconds >= cfg.noLavaSeconds) {
            return Method.OBSIDIAN;
        }
        return Method.CAST;
    }

    // the portal phase timed out and gets another try: which method that try uses. a timeout while the lava pool was still
    // being built says nothing about the cast (it never ran), so the pool hands over to it first. obsidian needs the diamond
    // pickaxe like decide says, and it is sticky once chosen
    public static Method afterTimeout(Method current, boolean poolWasRunning, boolean hasDiamondPickaxe) {
        return afterTimeout(current, poolWasRunning, hasDiamondPickaxe, false);
    }

    // castRose = the obsidian count went up during the try that timed out. a lake that works is not dropped for a natural
    // obsidian somewhere far off, the cast just gets its clock back
    public static Method afterTimeout(Method current, boolean poolWasRunning, boolean hasDiamondPickaxe, boolean castRose) {
        if (current == Method.OBSIDIAN) {
            return Method.OBSIDIAN;
        }
        return poolWasRunning || castRose || !hasDiamondPickaxe ? Method.CAST : Method.OBSIDIAN;
    }

    // what a portal phase timeout turns into: another try (with which method) or the end. the engine counts every retry,
    // so a pool that stalled gets one extra attempt to pay for itself: the pool is the optional fast path and its stall must
    // not eat the attempt the cast needs. poolTimedOut = it stalled on an earlier try (saved in RunState)
    public record TimeoutPlan(boolean retry, Method method, boolean poolTimedOut) {
    }

    public static TimeoutPlan onTimeout(Method current, int attempt, int maxAttempts, boolean poolWasRunning,
                                        boolean poolTimedOut, boolean hasDiamondPickaxe) {
        return onTimeout(current, attempt, maxAttempts, poolWasRunning, poolTimedOut, hasDiamondPickaxe, false);
    }

    public static TimeoutPlan onTimeout(Method current, int attempt, int maxAttempts, boolean poolWasRunning,
                                        boolean poolTimedOut, boolean hasDiamondPickaxe, boolean castRose) {
        boolean timedOut = poolTimedOut || poolWasRunning;
        boolean retry = attempt < maxAttempts + (timedOut ? 1 : 0);
        Method next = retry && current == Method.CAST ? afterTimeout(current, poolWasRunning, hasDiamondPickaxe, castRose) : current;
        return new TimeoutPlan(retry, next, timedOut);
    }

    // the engine passes the tick's FoodPlan, this one (package only) builds its own
    static List<KitNeed> gate(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return gate(f, cfg, endBeds, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    // what to hold before leaving the overworld (Marvion ordering: get it all here, not in the nether). the cast needs
    // two buckets (one becomes water, one lava) and a light, the end needs the water bucket back later
    public static List<KitNeed> gate(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food) {
        return gate(f, cfg, endBeds, food, BuildBlocks.NONE);
    }

    // `build` is the tick's band (ctx.buildBlocks()): the blocks need starts under netherEntryBlocksLow and runs to netherEntryBlocks
    public static List<KitNeed> gate(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food, BuildBlocks build) {
        return gate(f, cfg, endBeds, food, build, true);
    }

    // `fillUp` = the food runs past the minimum to the trip's stop. the phase turns it off when that top-up drags on (an island
    // with nothing to hunt), the minimum is all the gate insists on then
    public static List<KitNeed> gate(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food, BuildBlocks build, boolean fillUp) {
        List<KitNeed> out = new ArrayList<>();
        // the same count the kit plan uses, so meat still cooking for us (or meat that cannot be cooked) reads the same here. and
        // the same refill gap: 69 at the portal is not a trip
        int entry = food.entryEnd(cfg.netherEntryFoodUnits);
        if (food.wantsRefill()) {
            out.add(new KitNeed(KitNeed.FOOD, Math.max(food.refillStop(), entry)));
        } else if (fillUp && !food.coveredAt(entry) && food.shortOf(entry)) {
            // covered: the gap is raw meat that counts once the smoker is in reach. without it the count read 124 away from the
            // smoker and 132 next to it, so food and water took turns as the head every few seconds and neither finished
            // last stop before a nether leg that eats 8 units a minute in the fights, and the walk home from 40 is five minutes
            // of overworld: fill up to netherEntryFoodUnits, not just to where a trip would. entryEnd is the minimum on a full
            // bag, so this cannot ask for a pile there is no room for
            out.add(new KitNeed(KitNeed.FOOD, entry));
        }
        int buckets = KitPlanner.have(f, "bucket");
        if (buckets < 2) {
            out.add(new KitNeed("bucket", KitPlanner.held(f, "bucket") + 2 - buckets));
        }
        if (!f.has(Items.WATER_BUCKET)) {
            out.add(new KitNeed("water_bucket", 1));
        }
        if (f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) < 1) {
            out.add(new KitNeed("flint_and_steel", 1));
        }
        // piglins hate us unless one thing on us is gold. last chance to make the helmet before the nether, from gold we hold
        out.addAll(PiglinGold.gate(f));
        // and no gold to make it from: the nether is the last place to dig some (NetherGoldTask) and its crafts need a table, which
        // is not something it goes and fetches there
        out.addAll(PiglinGold.tableForNether(f));
        int unworn = KitPlanner.toEquip(f, cfg).size();
        if (unworn > 0) {
            out.add(new KitNeed(KitNeed.EQUIP_ARMOR, unworn));
        }
        // the nether has no stone and no trees worth the name, so the picks it will wear out are made here. before the build
        // blocks: the picks eat cobble and the blocks are what is left after that
        int picks = KitPlanner.sparePicksShort(f, cfg);
        if (picks > 0) {
            out.add(new KitNeed("stone_pickaxe", KitPlanner.held(f, "stone_pickaxe") + picks));
        }
        int blocks = build.want(f.buildBlocks(), cfg.netherEntryBlocksLow, cfg.netherEntryBlocks);
        if (blocks > 0) {
            out.add(new KitNeed(KitNeed.BUILD_BLOCKS, blocks));
        }
        // raw meat does not cook in the nether, so it is the last thing done here, same spot as KitPlanner.plan. null when no
        // station is feasible or a batch is already cooking (PortalPhase's leave visit waits that one out)
        KitNeed cook = CookGate.need(f, cfg, endBeds);
        if (cook != null) {
            out.add(cook);
        }
        return out;
    }

    // we arrive standing in the other portal, so that is where the nether side of the pair is
    public static void recordArrival(RunState state, GamerFacts f) {
        if (f.dimension() == Dimension.NETHER && state.netherPortal == null) {
            state.netherPortal = new RunState.Pos(f.x(), f.y(), f.z());
        }
    }
}
