package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.speedrun.gamer.BuildBlocks;
import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.DiamondHunt;
import adris.altoclef.tasks.speedrun.gamer.EarlyIronPick;
import adris.altoclef.tasks.speedrun.gamer.FoodDry;
import adris.altoclef.tasks.speedrun.gamer.FoodGate;
import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.GoldHunt;
import adris.altoclef.tasks.speedrun.gamer.IronActivity;
import adris.altoclef.tasks.speedrun.gamer.IronActivity.Kind;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.WoodBudget;
import adris.altoclef.tasks.speedrun.gamer.WoolHunt;
import adris.altoclef.tasks.speedrun.gamer.NeedEnd;
import adris.altoclef.tasks.speedrun.gamer.Workbenches;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules;
import adris.altoclef.tasks.speedrun.gamer.PackUp;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PickDiag;
import adris.altoclef.tasks.speedrun.gamer.PrepSupport;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.resources.FoodHunt;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.WearFromBag;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// iron gear, armor, wool for the beds, food top up. KitPlanner sizes ONE iron ingot need for everything missing so we
// mine and smelt once. the engine turns altoUseBlastFurnace off for the run so we never craft a blast furnace, but a
// standing one (village armorer) within altoNearbyBlastFurnaceRange still gets used, the plain furnace is the fallback.
// the smelting itself is fire and forget (altoAsyncSmelting, on for this phase only): the iron goes in, the bot does other
// things wherever the work is, and comes back between two needs, when the next need is waiting on the iron, or when it has
// run out of things to do
public class IronPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    // the stations this phase puts down and takes back, shared by the side jobs and the furnace watch
    private final Workbenches benches = new Workbenches();
    private final PrepSupport support = new PrepSupport(true, benches);
    private final FurnaceWatch furnaces = new FurnaceWatch(benches);
    private final SmeltSurface surface = new SmeltSurface();
    // says so (once) when the plan stops owning its iron pickaxe, see PickDiag
    private final PickDiag pickDiag = new PickDiag();
    // furnaces we already made a pack-up trip to (PackUp), so a trip that left input cooking is not repeated
    private final Set<RunState.Pos> packed = new HashSet<>();
    // the need we are in the middle of while a furnace cooks. a different first need means the last one is done, which is
    // the only time a finished furnace gets fetched. old path only, the arbiter keeps its own (kitHead)
    private KitNeed committed;
    // what we are doing right now (altoIronArbiter). the jobs above are its candidates
    private final IronActivity arbiter = new IronActivity();
    // the kit head the arbiter last ran while something cooked, same job as `committed` on the old path
    private KitNeed kitHead;
    // the stricter "that need is over" a far due job waits for (FurnacePlan.COLLECT_CUT_IN), both paths
    private final NeedEnd needEnd = new NeedEnd();
    // which path ran last tick, so flipping the setting mid phase starts the other one clean
    private Boolean arbiterOn;
    // a furnace backed kind was already asked this tick: the plan from the top of the tick may be about a visit that just ended
    private boolean furnaceAsked;
    // what the user had for altoAsyncSmelting, null when we did not touch it
    private Boolean userAsync;
    private String hudState;
    // FoodGate: a food top-up that started on the surface runs to the full amount, and the band the count was last in (-1 = unset)
    private boolean foodTopUp;
    private int foodBand = -1;
    // when armor in the bag first held the furnace trip back, -1 = not holding it back
    private long wearSince = -1;
    private static final long WEAR_FIRST_TICKS = 20 * 15;
    // the food need led last tick (a screen that opens then is its own, FoodGate.cookBusy) and the soft top-up was called off
    // because the raw meat in the bag covers the hole (FoodPlan.covered), the second only so it is announced once
    private boolean foodLed;
    private boolean foodCovered;
    // the food-before-the-furnace line was written for this dry spell (FoodPlan.starving), so it is said once
    private boolean starvedSaid;
    // CookGate: a cook that started early (surface, or between jobs) keeps the front until the meat is cooked
    private boolean cookLatch;
    // this tick's plan as the path that ran saw it, whether the kit runner ran the diamond dig this tick, and the attempt clock on
    // the last tick it did (-1 = it did not)
    private List<KitNeed> tickNeeds;
    private boolean dug;
    private double huntAge = -1;
    // the same two for the raw gold dig (GoldHunt)
    private boolean goldDug;
    private double goldAge = -1;
    // and the sheep chase (WoolHunt)
    private boolean woolChased;
    private double woolAge = -1;

    @Override
    public GamerPhase phase() {
        return GamerPhase.IRON;
    }

    @Override
    public String hud() {
        return GamerPhase.IRON.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public KitRunner kitRunner() {
        return runner;
    }

    @Override
    public PrepSupport support() {
        return support;
    }

    @Override
    public boolean ownsBenches() {
        return true;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        // no tick at hand (a test): plans of its own, the same bag gives the same answers
        WoodBudget wood = WoodBudget.of(facts, cfg.overworld, cfg.end.beds, GamerPhase.IRON);
        return done(facts, state, cfg, FoodPlan.of(facts, cfg, GamerPhase.IRON, state.foodRefilling, wood), wood);
    }

    // the engine's way: the tick's plan and budget, the very ones tick() walks, so done cannot say yes while they still want something
    @Override
    public boolean isDone(GamerContext ctx) {
        // the cascade asks while the state is still GATHER (the phase before this one just said done), and the budget in the context
        // is that phase's: the plan walked here is the iron phase's, so it gets the iron budget
        WoodBudget wood = ctx.wood().phase() == GamerPhase.IRON
                ? ctx.wood() : WoodBudget.of(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds, GamerPhase.IRON);
        return done(ctx.facts(), ctx.state(), ctx.cfg(), ctx.food(), wood);
    }

    private boolean done(GamerFacts facts, RunState state, GamerConfig cfg, FoodPlan food, WoodBudget wood) {
        // a table or furnace of ours still standing is picked up first, this is the last chance (Workbenches.phaseMayEnd)
        // iron still cooking is iron we do not have, however empty the plan looks
        // the food half is the kit's minimum (atMinimum): a trip still running past it carries on in the next phase
        return KitPlanner.plan(facts, cfg.overworld, cfg.end.beds, food.atMinimum(), wood).isEmpty() && facts.furnaceJobs().isEmpty()
                && Workbenches.phaseMayEnd(state, facts.dimension().name(), facts.gameTime()) && !furnaces.cooking();
    }

    @Override
    public void onRespawn() {
        furnaces.respawned();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        BuildBlocks.newEntry(ctx.state());
        support.onEnter(mod);
        furnaces.newPhase();
        surface.reset();
        // a split smelt is only decided up top (or where the climb gave up), never in the mine
        SmeltSplit.watchSite(() -> surface.smeltSite(mod));
        pickDiag.reset();
        packed.clear();
        committed = null;
        arbiter.reset();
        kitHead = null;
        needEnd.reset();
        arbiterOn = null;
        hudState = null;
        foodTopUp = false;
        foodLed = false;
        foodCovered = false;
        cookLatch = false;
        starvedSaid = false;
        foodBand = -1;
        tickNeeds = null;
        dug = false;
        huntAge = -1;
        goldDug = false;
        goldAge = -1;
        var async = Baritone.settings().altoAsyncSmelting;
        if (!SettingsOverrides.isHeld(async)) {
            userAsync = async.value;
        }
        SettingsOverrides.put(async, true);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        support.onExit(mod);
        FoodHunt.setWoolWanted(false);
        SmeltSplit.watchSite(null);
        // other phases smelt too and nobody there would come back for the furnace, so the setting is ours for this phase only
        releaseAsync(Baritone.settings().altoAsyncSmelting, userAsync);
    }

    // only while the hold is still ours: a #set in the middle of the phase dropped it, and that value is the user's now
    static void releaseAsync(Settings.Setting<Boolean> async, Boolean user) {
        if (user != null && SettingsOverrides.isHeld(async) && async.value) {
            SettingsOverrides.put(async, user);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        int pick = KitPlanner.have(ctx.facts(), "iron_pickaxe");
        if (pickDiag.lost(pick)) {
            Debug.logInternal(PickDiag.missing(pick, KitPlanner.held(ctx.facts(), "iron_pickaxe"), ctx.facts().spent(Items.IRON_PICKAXE),
                    ctx.facts().pickScan()));
        }
        // the food task is another tree entirely, this is how it learns that a sheep is worth more shorn than eaten
        FoodHunt.setWoolWanted(!ctx.facts().woolHuntGaveUp() && KitPlanner.woolShortfall(ctx.facts(), KitPlanner.bedGoal(ctx.facts(), ctx.cfg().end.beds)) > 0);
        boolean on = Baritone.settings().altoIronArbiter.value;
        if (arbiterOn == null || on != arbiterOn) {
            arbiter.reset();
            kitHead = null;
            committed = null;
            needEnd.reset();
            arbiterOn = on;
        }
        tickNeeds = null;
        dug = false;
        goldDug = false;
        woolChased = false;
        Task task = on ? arbiterTick(mod, ctx) : oldTick(mod, ctx);
        huntClock(mod, ctx);
        return task;
    }

    // the diamond dig's clock (DiamondHunt), only on ticks the kit runner ran it. out of time: the need is gone for the rest of the run
    private void huntClock(AltoClef mod, GamerContext ctx) {
        DiamondHunt.Step step = DiamondHunt.tick(ctx.state(), huntAge, dug, tickNeeds, ctx.secondsInPhase(),
                ctx.cfg().overworld.diamondPickMinutes, ctx.cfg().budgets.iron);
        huntAge = step.lastAge();
        if (step.gaveUp()) {
            ctx.log("no diamonds in time, going with the iron pick");
            ctx.save();
        }
        DiamondHunt.Step gold = GoldHunt.tick(ctx.state(), goldAge, goldDug, ctx.secondsInPhase(), ctx.cfg().overworld.goldHuntMinutes);
        goldAge = gold.lastAge();
        if (gold.gaveUp()) {
            ctx.save();
        }
        DiamondHunt.Step wool = WoolHunt.tick(ctx.state(), woolAge, woolChased && !woolSheepInView(mod), ctx.secondsInPhase(), ctx.cfg().overworld.woolHuntMinutes);
        woolAge = wool.lastAge();
        if (wool.gaveUp()) {
            ctx.log("no sheep around, moving on without the wool. the end prep gets the beds later");
            ctx.save();
        }
    }

    // same sheep CollectWoolTask would go for: lambs and shorn ones are just scenery
    private static boolean woolSheepInView(AltoClef mod) {
        return mod.getEntityTracker().entityFound(e -> e instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby(), Sheep.class);
    }

    // the tick's list without the stock-ups SmeltFiller tacks on the end: an optional log or wool top-up behind the dig is not a
    // reason to give it up a minute and a half early (DiamondHunt.moreAfter)
    private static List<KitNeed> planPart(List<KitNeed> needs, Schedule schedule) {
        return schedule == null ? needs : needs.stream().filter(n -> !schedule.isStockUp(n)).toList();
    }

    // every kit run goes through here, so the dig's clock knows it was the runner and not a side job that had the wheel
    private Task runKit(GamerContext ctx, List<KitNeed> needs) {
        Task task = runner.run(ctx, needs);
        dug = task != null && DiamondHunt.digging(runner.current());
        goldDug = task != null && GoldHunt.digging(runner.current());
        woolChased = task != null && WoolHunt.chasing(runner.current());
        return task;
    }

    // ---- the arbiter path (altoIronArbiter): IronActivity picks, this asks the one job it picked

    // armor sitting in the bag beats starting a furnace trip, see IronActivity.wearFirst. the same toEquip and held test the
    // runner's equip uses, so there is no second gold rule in here
    private boolean wearFirst(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        boolean wanted = needs.stream().anyMatch(n -> KitNeed.EQUIP_ARMOR.equals(n.catalogueName()));
        if (!wanted) {
            wearSince = -1;
            return false;
        }
        boolean held = !WearFromBag.held(mod, KitPlanner.toEquip(ctx.facts(), ctx.cfg().overworld)).isEmpty();
        double r = IronActivity.WEAR_HOSTILE_RADIUS;
        boolean close = mod.getEntityTracker().getHostiles().stream()
                .anyMatch(e -> e.distanceToSqr(mod.getPlayer()) <= r * r);
        if (!held) {
            wearSince = -1;
            return false;
        }
        // a piece the equip can't put on (a bound one on the body) must not hold the furnace off for good
        long now = ctx.facts().gameTime();
        if (wearSince < 0) {
            wearSince = now;
        }
        return IronActivity.wearFirst(true, now - wearSince < WEAR_FIRST_TICKS, close);
    }

    private Task arbiterTick(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        long now = f.gameTime();
        if (!f.furnaceJobs().isEmpty()) {
            furnaces.housekeeping(mod, ctx);
        }
        boolean jobs = !f.furnaceJobs().isEmpty();
        Schedule schedule = null;
        List<KitNeed> needs;
        if (jobs) {
            schedule = SmeltFiller.schedule(f, ctx.cfg().overworld, ctx.cfg().end.beds, SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod)), ctx.food(),
                    ctx.wood(), ctx.buildBlocks());
            needs = gateFood(mod, ctx, schedule.runnable());
        } else {
            kitHead = null;
            needEnd.reset();
            needs = gateFood(mod, ctx, KitPlanner.plan(f, ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(), ctx.wood()));
        }
        tickNeeds = planPart(needs, schedule);
        KitNeed head = needs.isEmpty() ? null : needs.get(0);
        FurnacePlan.Moment moment = null;
        FurnacePlan.Plan plan = null;
        if (jobs) {
            // same moment the old cooking tick built: a new head is the boundary, the early pick cuts the mining need short
            boolean boundary = head == null || !head.equals(kitHead);
            moment = moment(mod, ctx, needs, head, boundary, schedule);
            plan = furnaces.plan(mod, ctx, moment);
            if (ctx.food().held() < ctx.food().floor()) {
                plan = FurnacePlan.foodFirst(plan, FurnaceWatch.banned(mod));
            }
            furnaces.mayCookHere(SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod))
                    || SmeltSurface.nextWorkDown(head, f.count(Items.RAW_IRON), f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT)));
        }
        // an empty bag and a trip that is not for food (the one under way, else the one the plan would start): the iron trip and the
        // optional detours give way to the food at the head. a visit with the furnace screen open finishes first, walking off in the
        // middle of a take would leave the job's books out of step with the bag
        RunState.FurnaceJob tripJob = furnaces.tripJob();
        if (tripJob == null && plan != null && plan.pick() != null) {
            tripJob = plan.pick().job();
        }
        boolean atScreen = mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu;
        // a dry spell is not starving: nothing to hunt for minutes, so the pick keeps swinging (FoodDry)
        boolean starving = !atScreen && ctx.food().starving(tripJob) && !FoodDry.defers(ctx.facts().foodDryLatched(), ctx.facts().preyNear());
        IronActivity.Scene scene = new IronActivity.Scene(jobs, plan != null && plan.standingBy(), head != null, support.loots(),
                support.golemFighting(), starving, wearFirst(mod, ctx, needs));
        support.beginTick(mod, ctx);
        furnaceAsked = false;
        Kind before = arbiter.current();
        sayStarving(starving, before, plan != null && plan.pick() != null, ctx.food());
        if (starving && before != Kind.PACK_UP && furnaces.tripJob() != null) {
            // the trip on its way is let go for real (whoever cut in on it since, a pack-up has its own book), see FurnaceWatch.drop
            furnaces.drop();
        }
        // asked once and kept: the line below says who was lined up and who said no
        List<Kind> asked = arbiter.ask(scene, now);
        for (int i = 0; i < asked.size(); i++) {
            Kind k = asked.get(i);
            Task task = offer(k, mod, ctx, needs, head, jobs, plan, moment);
            if (task == null) {
                // the kit with an empty runnable list while something cooks is a flicker, not the end of anything (no line pair)
                if (k == arbiter.current() && !(k == Kind.KIT && jobs && head == null)) {
                    arbiter.ended(k, now, endWords(k));
                    if (sideJob(k)) {
                        // the village chest is empty, the bed is punched: that is a finish too, a far furnace may go now
                        needEnd.sideEnded();
                    }
                }
                continue;
            }
            Kind got = k;
            if ((k == Kind.STAND_BY || k == Kind.FURNACE || k == Kind.PACK_UP) && furnaces.handedOver()) {
                // the visit ended on this call and what came back is the cook or the pickup after it: that is the finishing. no
                // ended() and so no rest, a pack-up with a second furnace down here has to come straight back after the pickup
                got = Kind.FINISHING;
            } else if ((k == Kind.STAND_BY || k == Kind.FURNACE) && furnaces.started()) {
                // a fresh trip: which one it is depends on the call it started with, not on who asked
                got = furnaces.standingBy() ? Kind.STAND_BY : Kind.FURNACE;
                if (got != k && k == arbiter.current()) {
                    arbiter.ended(k, now, "visit over, next trip");
                }
            }
            say(IronActivity.withDetail(arbiter.chose(got, now), asked, i, scene));
            if (before == Kind.DIAMOND && got != Kind.DIAMOND) {
                support.endDiamond(now);
            }
            if (before == Kind.GOLD && got != Kind.GOLD) {
                support.endGold(now);
            }
            if (before == Kind.COAL && got != Kind.COAL) {
                support.endCoal(now);
            }
            if (before == Kind.GRAVEL && got != Kind.GRAVEL) {
                support.endGravel(now);
            }
            if (!jobs && got != Kind.FINISHING) {
                // nothing cooking and nothing being finished: the furnace side starts the next batch clean
                furnaces.reset();
            }
            return task;
        }
        if (!(arbiter.current() == Kind.KIT && jobs && head == null)) {
            say(IronActivity.withDetail(arbiter.idle(now), asked, asked.size(), scene));
        }
        if (before == Kind.DIAMOND) {
            support.endDiamond(now);
        }
        if (before == Kind.GOLD) {
            support.endGold(now);
        }
        if (before == Kind.COAL) {
            support.endCoal(now);
        }
        if (before == Kind.GRAVEL) {
            support.endGravel(now);
        }
        if (!jobs) {
            furnaces.reset();
        }
        hudState = null;
        return null;
    }

    // one line per run dry, and only when it actually cost something a trip: the one that holds the wheel, or one that is due to start
    private void sayStarving(boolean starving, Kind held, boolean tripDue, FoodPlan food) {
        if (!starving) {
            starvedSaid = false;
            return;
        }
        Kind gives = IronActivity.gave(held, tripDue);
        if (gives != null && !starvedSaid) {
            say(IronActivity.starvingLine(gives, food.held(), food.floor()));
        }
        starvedSaid = starvedSaid || gives != null;
    }

    // the one job the arbiter asked for. each sets the hud when it hands back a task
    private Task offer(Kind k, AltoClef mod, GamerContext ctx, List<KitNeed> needs, KitNeed head, boolean jobs, FurnacePlan.Plan plan,
                       FurnacePlan.Moment moment) {
        Task task = switch (k) {
            case FINISHING -> furnaces.finishing(mod, ctx);
            case GOLEM_FIGHT -> support.golemFight(mod, ctx, head);
            case STAND_BY, FURNACE -> furnaceTrip(mod, ctx, plan, moment);
            case STATION -> support.station(mod, ctx, needs);
            case RUINED_PORTAL -> support.ruinedPortal(mod, ctx);
            case VILLAGE_CHEST -> support.villageChest(mod, ctx, head);
            case BED -> support.bed(mod, ctx);
            case GOLEM_START -> support.golemStart(mod, ctx, head);
            case DIAMOND -> support.diamondDetour(mod, ctx, head);
            case GOLD -> support.goldDetour(mod, ctx, head);
            case COAL -> support.coalDetour(mod, ctx, head);
            case GRAVEL -> support.gravelDetour(mod, ctx, head);
            case PACK_UP -> packUpTrip(mod, ctx, head, second(needs), jobs);
            case SURFACE -> jobs && head == null ? null : surface.tick(mod, ctx, head, second(needs));
            case KIT -> kit(ctx, needs, head, jobs);
        };
        if (task != null) {
            hudState = switch (k) {
                case FINISHING, STAND_BY, FURNACE, PACK_UP -> furnaces.hud();
                case SURFACE -> surface.hud();
                case KIT -> runner.hud() + (jobs ? " while a batch cooks" : "");
                default -> support.hud();
            };
        }
        return task;
    }

    private Task furnaceTrip(AltoClef mod, GamerContext ctx, FurnacePlan.Plan plan, FurnacePlan.Moment moment) {
        if (plan == null) {
            return null;
        }
        // a pack-up or a stand-by ended earlier this tick: its visit changed the jobs, so the plan is made again (trip() only
        // re-plans for a trip that ends inside its own call)
        FurnacePlan.Plan fresh = furnaceAsked ? furnaces.plan(mod, ctx, moment) : plan;
        furnaceAsked = true;
        Task trip = furnaces.trip(mod, ctx, fresh, moment);
        if (trip == null) {
            return null;
        }
        if (furnaces.started()) {
            kitHead = null;
            needEnd.reset();
        }
        // standing next to the furnace (screen closed between looks) is the plan, not a stall. but only until the job is due plus
        // the patience: a furnace that never finishes must not keep the watchdog off
        if (furnaces.creditsWait(ctx)) {
            ctx.progress("waiting for the furnace");
        }
        return trip;
    }

    // the pack-up trip that is going, or a new one when we are about to climb. it runs on FurnaceWatch like a collect does, so the
    // one under way comes back first (packUp only picks a furnace once, the next tick it would find nothing and drop the trip)
    private Task packUpTrip(AltoClef mod, GamerContext ctx, KitNeed head, KitNeed next, boolean jobs) {
        if (!jobs) {
            return null;
        }
        furnaceAsked = true;
        Task running = furnaces.active(mod, ctx);
        if (running != null) {
            return running;
        }
        return head == null ? null : packUp(mod, ctx, head, next);
    }

    private Task kit(GamerContext ctx, List<KitNeed> needs, KitNeed head, boolean jobs) {
        // the runnable list goes empty for a tick now and then while something cooks, the old cooking tick just waited it out
        if (jobs && head == null) {
            return null;
        }
        // the early batch is about to go in: from here it stays owed until the furnace is lit. the old path only did this with
        // nothing cooking, an early batch while a smoker cooks needs the latch just the same
        EarlyIronPick.track(ctx.state(), head, ctx.facts(), ctx.cfg().overworld);
        if (jobs) {
            kitHead = head;
            needEnd.working(head, ctx.facts().gameTime());
        }
        return runKit(ctx, needs);
    }

    // why the activity that just handed back null is over, for the activity line
    private String endWords(Kind k) {
        return switch (k) {
            case DIAMOND -> support.diamondEnded();
            case GOLD -> support.goldEnded();
            case COAL -> support.coalEnded();
            case GRAVEL -> support.gravelEnded();
            case STAND_BY, FURNACE, PACK_UP -> "visit over";
            case STATION -> "pickup over";
            case GOLEM_FIGHT, GOLEM_START -> "fight over";
            case RUINED_PORTAL, VILLAGE_CHEST -> "nothing left to loot";
            case BED -> "no bed left to take";
            case SURFACE -> "up top, or the climb gave up";
            case FINISHING -> "station dealt with";
            case KIT -> "nothing left in the plan";
        };
    }

    private static void say(String line) {
        if (line != null) {
            Debug.logInternal(line);
        }
    }

    // ---- the old path (altoIronArbiter off), kept for one round to compare against

    private Task oldTick(AltoClef mod, GamerContext ctx) {
        // the furnace we just emptied is coming down, a few seconds and it goes with us to the next work site
        Task takingBack = furnaces.finishing(mod, ctx);
        if (takingBack != null) {
            hudState = furnaces.hud();
            return takingBack;
        }
        if (!ctx.facts().furnaceJobs().isEmpty()) {
            return cookingTick(mod, ctx);
        }
        // nothing in a furnace (or it just got collected): the plain kit loop
        furnaces.reset();
        committed = null;
        needEnd.reset();
        List<KitNeed> needs = gateFood(mod, ctx, KitPlanner.plan(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(),
                ctx.wood()));
        tickNeeds = needs;
        KitNeed first = needs.isEmpty() ? null : needs.get(0);
        Task side = support.tick(mod, ctx, needs);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // all the ore is mined and we are still down the mine: up first, then the smelt places its furnace in the open
        // (the early pick batch is three items and goes in right where the ore is, SmeltSurface leaves it alone)
        Task up = surface.tick(mod, ctx, first, second(needs));
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        // the early batch's raw iron is about to go in: from here the early need stays owed until the furnace is lit
        EarlyIronPick.track(ctx.state(), first, ctx.facts(), ctx.cfg().overworld);
        Task task = runKit(ctx, needs);
        hudState = runner.hud();
        return task;
    }

    // iron is cooking somewhere. the side jobs still go first (a golem fight in progress outranks everything), then a trip
    // to the furnace that already started, then the plan: the runnable list, or the furnace itself when SmeltFiller says it
    // is time
    private Task cookingTick(AltoClef mod, GamerContext ctx) {
        furnaces.housekeeping(mod, ctx);
        GamerFacts f = ctx.facts();
        if (f.furnaceJobs().isEmpty()) {
            return null;
        }
        Schedule schedule = SmeltFiller.schedule(f, ctx.cfg().overworld, ctx.cfg().end.beds, SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod)),
                ctx.food(), ctx.wood(), ctx.buildBlocks());
        List<KitNeed> runnable = gateFood(mod, ctx, schedule.runnable());
        tickNeeds = planPart(runnable, schedule);
        KitNeed head = runnable.isEmpty() ? null : runnable.get(0);
        // no pick yet and the early batch is done: the pick is worth the detour, the mining need would not end for 36 more
        // ingots. nothing else cuts a need short, an iron craft waiting on the output is not a reason to leave a ladder.
        // not in the middle of a cook's load either, that is a few seconds and the pick can wait for them
        boolean boundary = head == null || !head.equals(committed);
        FurnacePlan.Moment moment = moment(mod, ctx, runnable, head, boundary, schedule);
        FurnacePlan.Plan plan = furnaces.plan(mod, ctx, moment);
        Task side = plan.standingBy() ? support.tickStandBy(mod, ctx, runnable) : support.tick(mod, ctx, runnable);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // a furnace emptied down a mine is not a place to start the next batch of meat unless the work is down there too
        furnaces.mayCookHere(SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod))
                || SmeltSurface.nextWorkDown(head, f.count(Items.RAW_IRON), f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT)));
        // the trip waits until everything is out for a stand-by (the screen stays closed between looks), then FurnaceWatch reloads
        // or picks the smoker up as it always does, and the filler gets the bot back once no smoker is left
        Task trip = furnaces.trip(mod, ctx, plan, moment);
        if (trip != null) {
            if (furnaces.started()) {
                committed = null;
                needEnd.reset();
            }
            // standing next to the furnace (screen closed between looks) is the plan, not a stall. but only until the job is due plus
            // the patience: a furnace that never finishes must not keep the watchdog off
            if (furnaces.creditsWait(ctx)) {
                ctx.progress("waiting for the furnace");
            }
            hudState = furnaces.hud();
            return trip;
        }
        if (head == null) {
            return null;
        }
        // about to leave the mine: whatever is still cooking at the bottom of it comes with us first
        Task pack = packUp(mod, ctx, head, second(runnable));
        if (pack != null) {
            hudState = furnaces.hud();
            return pack;
        }
        Task up = surface.tick(mod, ctx, head, second(runnable));
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        committed = head;
        needEnd.working(head, f.gameTime());
        Task task = runKit(ctx, runnable);
        hudState = runner.hud() + " while a batch cooks";
        return task;
    }

    // the furnace's view of this tick, both paths. the early pick only waits on ingots (it used to send us to a due smoker as well),
    // a hungry food need at the head waits on the food jobs (FurnacePlan.feeds),
    // and where we stand decides whether a due job may cut in at all (FurnacePlan.COLLECT_CUT_IN). a smoker cooks 5 s an item:
    // standing at a batch with 40 s left (50 s at most, see FurnacePlan) beats walking off to mine and back (mayStandBy)
    private FurnacePlan.Moment moment(AltoClef mod, GamerContext ctx, List<KitNeed> needs, KitNeed head, boolean boundary, Schedule schedule) {
        GamerFacts f = ctx.facts();
        boolean pick = EarlyIronPick.collectNow(f, ctx.cfg().overworld) && f.cookStation() == null;
        boolean done = needEnd.done(needs, f.gameTime());
        boolean stockUp = schedule.isStockUp(head);
        boolean hungry = ctx.food().band() == 0 || ctx.food().bag() <= 0;
        // only feeds hears the gate's own word on it, close by a stock-up is cut short the way it always was
        boolean extraFood = stockUp && !FoodGate.refillHead(needs, ctx.food());
        return new FurnacePlan.Moment(head != null, boundary, done,
                job -> (pick && "iron_ingot".equals(job.output)) || FurnacePlan.feeds(head, job, hungry, extraFood), stockUp, true,
                FurnaceWatch.where(mod), doing(head), farTrip(mod, head), pick);
    }

    // the head need is a long walk from where the furnaces are (we stand next to them when this matters). only asked for the need
    // WorkbenchRules.farTrip knows, so the entity lookup is not paid every tick
    private static boolean farTrip(AltoClef mod, KitNeed head) {
        if (head == null || mod.getPlayer() == null || WorkbenchRules.siteOf(head.catalogueName()) != WorkbenchRules.Site.SHEEP) {
            return false;
        }
        return WorkbenchRules.farTrip(head.catalogueName(), Workbenches.siteFrom(mod, mod.getPlayer().position(), head.catalogueName()));
    }

    // the side jobs whose end counts as finishing what we were out there for (NeedEnd.sideEnded). the furnace kinds, the climb and
    // the kit are not that
    private static boolean sideJob(Kind k) {
        return k.ordinal() >= Kind.GOLEM_FIGHT.ordinal() && k.ordinal() <= Kind.GRAVEL.ordinal() && k != Kind.STAND_BY;
    }

    // what the far line says we are busy with: the side job that holds the wheel, else the head need. (the old path has no
    // activity, it always says the head need)
    private String doing(KitNeed head) {
        Kind k = arbiter.current();
        if (k != null && k != Kind.KIT && k != Kind.FURNACE && k != Kind.STAND_BY) {
            return k.words;
        }
        return head == null ? "nothing" : head.catalogueName();
    }

    private static KitNeed second(List<KitNeed> needs) {
        return needs.size() > 1 ? needs.get(1) : null;
    }

    // the climb to the surface is about to start and one of our furnaces down here still has a job: go to it first. nearly done
    // and we wait for it, otherwise its contents come back out and the station comes with us (FurnaceWatch takes it down after
    // the last collect). the planner re-smelts or re-cooks what came back on the surface. once per furnace, a trip that left
    // input behind (the cap) must not send us round again
    private Task packUp(AltoClef mod, GamerContext ctx, KitNeed head, KitNeed next) {
        GamerFacts f = ctx.facts();
        if (f.furnaceJobs().isEmpty() || !surface.aboutToClimb(mod, ctx, head, next)) {
            return null;
        }
        // end a finished visit before picking, it can remove the job the pick would name
        Task running = furnaces.active(mod, ctx);
        if (running != null) {
            return running;
        }
        Vec3 me = mod.getPlayer().position();
        RunState.FurnaceJob job = PackUp.pick(f.furnaceJobs(), packed, j -> jobDepth(mod, j), j -> PackUp.walk(j, me.x, me.y, me.z), FurnaceWatch.banned(mod));
        if (job == null) {
            return null;
        }
        Task trip = furnaces.leave(mod, ctx, job, FurnacePlan.Leaving.AREA, jobDepth(mod, job));
        if (trip != null) {
            packed.add(job.pos);
        }
        return trip;
    }

    // blocks between the job's furnace and the open sky over it (over us, when its chunk is not loaded)
    private static int jobDepth(AltoClef mod, RunState.FurnaceJob job) {
        BlockPos at = new BlockPos(job.pos.x, job.pos.y, job.pos.z);
        BlockPos column = mod.getChunkTracker().isChunkLoaded(at) ? at : mod.getPlayer().blockPosition();
        return SmeltSurface.depth(mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), job.pos.y);
    }

    // food first, then the cook. the cook is already the last need of the plan (nothing leaves IRON with raw meat a furnace can
    // cook); this lets it go early, on the surface or between two jobs, once there is enough of it to be worth the stop
    private List<KitNeed> gateFood(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        List<KitNeed> gated = gateFoodNeed(mod, ctx, needs);
        int at = CookGate.index(gated);
        if (at < 0) {
            cookLatch = false;
            return gated;
        }
        if (at == 0) {
            return gated;
        }
        boolean surfaced = SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod));
        boolean loadBusy = otherLoadInFlight(mod, ctx);
        // a cook that is running is committed: the meat moving into the station is not a reason to hand the head to the iron
        boolean lead = CookGate.leads(gated, at, CookGate.raw(ctx.facts()), surfaced, cookLatch, loadBusy, ctx.facts().cookStation() != null);
        if (lead && !cookLatch) {
            Debug.logInternal("cook: " + CookGate.raw(ctx.facts()) + " raw meat in the bag, cooking it now instead of eating it raw");
        }
        // an iron load in flight only holds the cook back for its few ticks, the latch waits with it (clearing it sent the cook
        // back to "wait until we surface" for the rest of a cave trip)
        if (!loadBusy) {
            cookLatch = lead;
        }
        return lead ? CookGate.lead(gated, at) : gated;
    }

    // a furnace load is going on that is not the cook's: a screen of one is open or a smelt task had it a moment ago (the same
    // test the station pickup waits on). once the cook is the one running it owns that screen, so the answer is no
    private static boolean otherLoadInFlight(AltoClef mod, GamerContext ctx) {
        return ctx.facts().cookStation() == null && loadInFlight(mod, ctx);
    }

    private static boolean loadInFlight(AltoClef mod, GamerContext ctx) {
        return WorkbenchRules.loadInFlight(mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu, AsyncSmelting.lastWork(), ctx.facts().gameTime());
    }

    // the kit's own food need only leads when FoodGate says so, otherwise it waits behind the ore (it comes back the moment
    // we surface, and the food chain eats on its own in the meantime). the stock-up fillers
    // SmeltFiller adds are surface work and not touched
    private List<KitNeed> gateFoodNeed(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        // the planner's own count (bag, open screen, what a smoker is cooking, the raw meat nobody can cook at its raw value)
        FoodPlan food = ctx.food();
        int held = food.held();
        int band = food.band();
        if (band != foodBand) {
            // the user wants to see this one (an apple and six mutton was why the bot kept leaving the mine)
            if (foodBand >= 0) {
                Debug.logInternal("food: " + held + " units held, " + (band == 0 ? "under the floor of " + food.floor()
                        : band == 1 ? "over " + food.floor() + " but short of " + food.overworldMinimum() : "at the " + food.overworldMinimum() + " we want"));
            }
            foodBand = band;
        }
        int at = FoodGate.index(needs, food);
        if (at < 0) {
            foodTopUp = false;
            foodLed = false;
            foodCovered = false;
            return needs;
        }
        // the heightmap only matters over the floor. a trip runs past the minimum to refillStop now, so band 2 needs it too: a trip
        // GATHER handed over at 80 would never lead up top otherwise, and sit latched until we ate back under 70
        boolean surfaced = band >= 1 && SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod));
        // a cook picked its station, or a smelt screen is in hand right now: the meat in that screen is not in the bag, not a job
        // yet and not in the held sum, which is how a half loaded smoker read as 56 units and sent the bot for cows. unless the
        // food need was the one leading: then the screen is the top-up's own and it must not cut itself off
        boolean busy = FoodGate.cookBusy(ctx.facts().cookStation() != null, loadInFlight(mod, ctx), foodLed);
        boolean lead = food.leads(surfaced, foodTopUp, busy);
        boolean covered = food.covered();
        if (covered && !foodCovered) {
            Debug.logInternal("food: " + held + " units held, short of " + food.refillStop() + " but the raw meat in the bag covers it once cooked, no trip");
        }
        foodCovered = covered;
        boolean wasTopUp = foodTopUp;
        foodTopUp = food.nextTopUp(foodTopUp, lead);
        if (foodTopUp && !wasTopUp) {
            Debug.logInternal("food: topping up from " + held + " to " + food.refillStop() + " now that it is cheap");
        }
        // nextTopUp only heard the real answer: a kept lead starting the soft latch would march a mine off to refillStop
        boolean keep = lead || food.keepsLead(foodLed, CookGate.rawGap(ctx.facts()));
        foodLed = keep;
        return keep ? needs : FoodGate.without(needs, at);
    }

    // iron is the one phase where "mostly done" is fine: with the pickaxe, a light and two buckets the portal can be
    // built, the armor and wool are nice to have. anything less and we stop instead of walking into the nether naked
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (attempt < ctx.cfg().maxAttempts) {
            return Timeout.RETRY;
        }
        // skipping with iron still in a furnace is fine: PORTAL takes a last look before it leaves the overworld (TAKE_ALL on
        // every job that is left, see PortalPhase.tick), so nothing gets stranded
        if (!KitPlanner.essentialsMet(ctx.facts(), ctx.cfg().overworld)) return Timeout.STUCK;
        // the armor is not an essential, so a skip can send us to the fortress in leather. say how much we left with
        Debug.logInternal("iron: out of time with the essentials in hand, skipping at " + ctx.facts().armorPoints() + " armor");
        return Timeout.SKIP;
    }
}
