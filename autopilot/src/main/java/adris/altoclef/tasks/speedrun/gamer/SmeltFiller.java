package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.KitItem;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// what to do while the iron cooks. pure (facts and config in, needs out) so the order of the filler can be tested without a game.
// nothing in here walks anywhere: IronPhase turns it into tasks. when to go back for the output is FurnacePlan's call.
// there is no leash: a furnace in an unloaded chunk just pauses, so the bot goes where the work is and walks back for the
// output when it is due (and whatever is in the furnace when we get there is the truth, see CollectFromFurnaceTask)
public final class SmeltFiller {
    // runnable = what we can work on right now, most useful first. blocked = needs that want ingots we do not hold yet.
    // stockUps = the entries of runnable that are only there to use the wait (cfg.smeltExtras), a due job cuts those short
    public record Schedule(List<KitNeed> runnable, List<KitNeed> blocked, List<KitNeed> stockUps) {
        public Schedule(List<KitNeed> runnable, List<KitNeed> blocked) {
            this(runnable, blocked, List.of());
        }

        // is this need one of the stock-ups. record equality (HeadLatch hands back last tick's copy), so a plan need that matches one
        // is the plan's: the food refill asks for 130 and so does the default food stock-up (100 + 30), and a due iron job cutting
        // the refill short as if it were filler sent the hunt home halfway. stock-ups sit at the end of runnable
        public boolean isStockUp(KitNeed need) {
            if (need == null || !stockUps.contains(need)) {
                return false;
            }
            int planPart = Math.max(0, runnable.size() - stockUps.size());
            return !runnable.subList(0, planPart).contains(need);
        }
    }

    private SmeltFiller() {
    }

    // with no job running this is exactly KitPlanner.plan. with one: crafts that want more ingots than we hold wait in
    // `blocked`, and the runnable list grows by what is useful to do meanwhile, in this order:
    //   a. the rest of the kit that needs no iron (food, wool, armor we already hold) and the portal's build blocks
    //   b. prep for the blocked crafts: flint, planks and sticks
    //   c. stock-up, one entry of cfg.smeltExtras at a time
    // the engine passes the tick's FoodPlan, the two short forms (package only) build their own
    static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return schedule(f, cfg, endBeds, true);
    }

    static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface) {
        return schedule(f, cfg, endBeds, nearSurface, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    // nearSurface = SmeltSurface.shallow: the log stock-up only happens where a tree is a short walk (see WoodBudget.stockUpLogs)
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food) {
        return schedule(f, cfg, endBeds, nearSurface, food, WoodBudget.of(f, cfg, endBeds));
    }

    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food, WoodBudget wood) {
        return schedule(f, cfg, endBeds, nearSurface, food, wood, BuildBlocks.NONE);
    }

    // `build` is the tick's band (ctx.buildBlocks()), see BuildBlocks
    public static Schedule schedule(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food, WoodBudget wood,
                                    BuildBlocks build) {
        List<KitNeed> needs = KitPlanner.plan(f, cfg, endBeds, food, wood);
        if (f.furnaceJobs().isEmpty()) {
            return new Schedule(needs, List.of());
        }
        List<KitNeed> runnable = new ArrayList<>();
        List<KitNeed> blocked = new ArrayList<>();
        split(f, needs, runnable, blocked);
        int base = build.want(f.buildBlocks(), cfg.ironBuildBlocksLow, cfg.portalBuildBlocks);
        if (base > 0) {
            runnable.add(new KitNeed(KitNeed.BUILD_BLOCKS, base));
        }
        prep(f, cfg, wood, blocked, runnable);
        List<KitNeed> stockUps = new ArrayList<>();
        extras(f, cfg, endBeds, nearSurface, food, wood, build, stockUps);
        runnable.addAll(stockUps);
        runnable.removeIf(need -> foodBlocked(f, need));
        // the same out of the stock-ups, so they stay exactly the tail of runnable (isStockUp counts on that)
        stockUps.removeIf(need -> foodBlocked(f, need));
        return new Schedule(runnable, blocked, stockUps);
    }

    // a craft is runnable while the ingots we hold cover it, handed out in plan order (the pickaxe gets its three before
    // the boots get theirs). the ones we cannot pay for yet wait for the furnace
    private static void split(GamerFacts f, List<KitNeed> needs, List<KitNeed> runnable, List<KitNeed> blocked) {
        int ingots = f.count(Items.IRON_INGOT);
        boolean craftWaiting = false;
        for (KitNeed need : needs) {
            if (foodBlocked(f, need)) {
                blocked.add(need);
                continue;
            }
            // the diamond dig costs no ingots, but it goes down for minutes: with a craft above it still waiting on the furnace it
            // would leave the armor in the furnace and the bot climbing back up for every collect. it waits for the crafts
            if (KitPlanner.DIAMOND.equals(need.catalogueName()) && craftWaiting) {
                blocked.add(need);
                continue;
            }
            int price = KitPlanner.ingotCost(need.catalogueName()) * (need.count() - KitPlanner.held(f, need.catalogueName()));
            // a spare shield never takes ingots from a craft that is still waiting for its own
            if (craftWaiting && price > 0 && "shield".equals(need.catalogueName()) && KitPlanner.held(f, "shield") > 0) {
                blocked.add(need);
            } else if (price <= 0) {
                runnable.add(need);
            } else if (price <= ingots) {
                ingots -= price;
                runnable.add(need);
            } else {
                blocked.add(need);
                craftWaiting = true;
            }
        }
    }

    // the food need while a smoker is still cooking the last batch and the bag has more raw meat for it: there is one input
    // slot, so the next batch has to wait for the first to be collected. same shape as a craft waiting for ingots, and the
    // food task would otherwise stand around "cooking" at a smoker that is busy
    static boolean foodBlocked(GamerFacts f, KitNeed need) {
        return KitNeed.FOOD.equals(need.catalogueName()) && f.pendingFoodUnits() > 0 && f.count(ItemHelper.RAW_FOODS) > 0;
    }

    // what GATHER can work on while its food cooks. there are no ingots to wait for here, the one thing that can be stuck is
    // food behind its own smoker
    static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds) {
        return gatherRunnable(f, cfg, endBeds, FoodPlan.ofBeds(f, cfg, endBeds));
    }

    public static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food) {
        return gatherRunnable(f, cfg, endBeds, food, WoodBudget.of(f, cfg, endBeds));
    }

    public static List<KitNeed> gatherRunnable(GamerFacts f, OverworldConfig cfg, int endBeds, FoodPlan food, WoodBudget wood) {
        List<KitNeed> out = new ArrayList<>();
        for (KitNeed need : KitPlanner.gather(f, cfg, endBeds, food, wood)) {
            if (!foodBlocked(f, need)) {
                out.add(need);
            }
        }
        return out;
    }

    // the one blocked-need case worth cutting a need short for: the next need is the food and the smoker is still on the last
    // batch. (an iron craft heading the plan does not count, that is every tick of a long cook and the bot got pulled off a
    // ladder need for it)
    public static boolean gatherBlocking(GamerFacts f, List<KitNeed> plan) {
        return !plan.isEmpty() && foodBlocked(f, plan.get(0));
    }

    private static void prep(GamerFacts f, OverworldConfig cfg, WoodBudget wood, List<KitNeed> blocked, List<KitNeed> out) {
        boolean flintAndSteel = isBlocked(blocked, "flint_and_steel");
        if (flintAndSteel && f.count(Items.FLINT) < 1 && f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) < 1) {
            out.add(new KitNeed("flint", 1));
        }
        int planks = planksWanted(blocked, cfg, wood.bedPlanksOwed());
        if (planks > 0 && f.count(ItemHelper.PLANKS) < planks) {
            out.add(new KitNeed("planks", planks));
        }
    }

    // shield is 6 planks, the pickaxe and sword 3 sticks between them (2 planks make 4), a bed is 3 planks (a few beds'
    // worth is stocked, the rest is chopped when the beds are made)
    static int planksWanted(List<KitNeed> blocked, GamerFacts f, OverworldConfig cfg, int endBeds) {
        return planksWanted(blocked, cfg, WoodBudget.bedPlanksOwed(f, endBeds));
    }

    // `bedPlanksOwed` = WoodBudget's, 3 planks for every bed the end fight still wants and we do not hold
    static int planksWanted(List<KitNeed> blocked, OverworldConfig cfg, int bedPlanksOwed) {
        int planks = 0;
        if (isBlocked(blocked, "shield")) {
            planks += 6;
        }
        if (isBlocked(blocked, "iron_pickaxe") || isBlocked(blocked, "iron_axe") || isBlocked(blocked, "iron_sword")) {
            planks += 2;
        }
        return planks + Math.min(cfg.smeltBedPlanks, bedPlanksOwed);
    }

    private static boolean isBlocked(List<KitNeed> blocked, String name) {
        return blocked.stream().anyMatch(n -> n.catalogueName().equals(name));
    }

    // every stock-up is surface work (sheep, animals, a tree, loose stone), so none of them is worth climbing out of the mine for:
    // down there the list is empty and the wait is spent standing at the furnace instead. IronPhase asks for what it needs
    // itself while the surface is still close
    private static void extras(GamerFacts f, OverworldConfig cfg, int endBeds, boolean nearSurface, FoodPlan food, WoodBudget wood,
                               BuildBlocks build, List<KitNeed> out) {
        if (cfg.smeltExtras == null) {
            return;
        }
        for (KitItem extra : cfg.smeltExtras) {
            if (extra.item == null || extra.count <= 0) {
                continue;
            }
            switch (extra.item) {
                case "food" -> {
                    int units = food.stockUp(extra.count);
                    if (nearSurface && food.shortOf(units)) {
                        out.add(new KitNeed(KitNeed.FOOD, units));
                    }
                }
                case "wool_beds" -> {
                    int missing = KitPlanner.woolShortfall(f, KitPlanner.bedGoal(f, endBeds) + extra.count);
                    if (nearSurface && missing > 0 && !f.woolHuntGaveUp()) {
                        out.add(new KitNeed("wool", f.count(ItemHelper.WOOL) + missing));
                    }
                }
                case "build_blocks" -> {
                    // starts from well under a full base and not under the stock-up target, or every pillar after the first fill would
                    // send us back for more. once it does start it runs to the target, and it gets one trip per phase entry
                    int blocks = nearSurface ? build.wantExtra(f.buildBlocks(), cfg.stockUpBuildBlocksLow, cfg.portalBuildBlocks + extra.count) : 0;
                    if (blocks > 0) {
                        out.add(new KitNeed(KitNeed.BUILD_BLOCKS, blocks));
                    }
                }
                case "log" -> {
                    int logs = wood.stockUpLogs(extra.count, nearSurface);
                    if (logs > 0) {
                        out.add(new KitNeed("log", logs));
                    }
                }
                default -> {
                    // a typo in the config is a stock-up that never happens, not a crash in the middle of a run
                }
            }
        }
    }
}
