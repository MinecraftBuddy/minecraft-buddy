package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.WoodBudget;
import adris.altoclef.tasks.speedrun.gamer.NeedEnd;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PrepSupport;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.Workbenches;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;

import java.util.List;

// wood, table, stone tools, furnace, first food. the kit comes from KitPlanner so this never re-collects what we hold
public class GatherPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    // the stations this phase puts down and takes back, shared by the side jobs and the smoker watch
    private final Workbenches benches = new Workbenches();
    private final PrepSupport support = new PrepSupport(false, benches);
    // the smoker the food cooks in (the same watch IRON uses for its furnace, it does not care what is cooking)
    private final FurnaceWatch furnaces = new FurnaceWatch(benches);
    // the climb out of a mine before the log step, the smelt's climb with a different reason (SmeltSurface.logTick)
    private final SmeltSurface surface = new SmeltSurface();
    // the need we are in the middle of while the food cooks, a different first need is the boundary where we turn around
    private KitNeed committed;
    // the stricter "that need is over" a far smoker waits for (FurnacePlan.COLLECT_CUT_IN)
    private final NeedEnd needEnd = new NeedEnd();
    // what the user had for altoAsyncSmelting, null when we did not touch it
    private Boolean userAsync;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.GATHER;
    }

    @Override
    public String hud() {
        return GamerPhase.GATHER.hud();
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
        WoodBudget wood = WoodBudget.of(facts, cfg.overworld, cfg.end.beds, GamerPhase.GATHER);
        return done(facts, state, cfg, FoodPlan.of(facts, cfg, GamerPhase.GATHER, state.foodRefilling, wood), wood);
    }

    // the engine's way: the tick's plan and budget, the very ones tick() walks, so done cannot say yes while they still want a chop
    @Override
    public boolean isDone(GamerContext ctx) {
        return done(ctx.facts(), ctx.state(), ctx.cfg(), ctx.food(), ctx.wood());
    }

    private boolean done(GamerFacts facts, RunState state, GamerConfig cfg, FoodPlan food, WoodBudget wood) {
        // a table or furnace of ours still standing is picked up first, this is the last chance (Workbenches.phaseMayEnd)
        // food still cooking is food we do not have, and the smoker coming down is part of the job
        // the food half is the kit's minimum (atMinimum): a trip still running past it carries on in IRON, the latch goes with us.
        // the wood half is not: the trip's fuel logs are in the tick's plan (the cook has something to burn when the meat comes in),
        // and a phase that calls itself done with a chop still in its own plan walks off from it. so everything the tick's plan wants
        // bar the food has to be gone as well
        boolean kitDone = KitPlanner.gather(facts, cfg.overworld, cfg.end.beds, food.atMinimum(), wood).isEmpty()
                && KitPlanner.gather(facts, cfg.overworld, cfg.end.beds, food, wood).stream().allMatch(n -> KitNeed.FOOD.equals(n.catalogueName()));
        return kitDone && facts.furnaceJobs().isEmpty()
                && Workbenches.phaseMayEnd(state, facts.dimension().name(), facts.gameTime()) && !furnaces.cooking();
    }

    @Override
    public void onRespawn() {
        furnaces.respawned();
        // a climb that finished (or half finished) before the death says nothing about the bed we woke up in
        surface.reset();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        support.onEnter(mod);
        furnaces.newPhase();
        surface.reset();
        committed = null;
        needEnd.reset();
        hudState = null;
        var async = Baritone.settings().altoAsyncSmelting;
        if (!SettingsOverrides.isHeld(async)) {
            userAsync = async.value;
        }
        SettingsOverrides.put(async, true);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        support.onExit(mod);
        // nobody outside the phases that come back for the smoker would collect from it, same rule as IRON
        IronPhase.releaseAsync(Baritone.settings().altoAsyncSmelting, userAsync);
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        // the smoker we just emptied is coming down, a few seconds and it goes with us
        Task takingBack = furnaces.finishing(mod, ctx);
        if (takingBack != null) {
            hudState = furnaces.hud();
            return takingBack;
        }
        if (!ctx.facts().furnaceJobs().isEmpty()) {
            furnaces.housekeeping(mod, ctx);
            if (!ctx.facts().furnaceJobs().isEmpty()) {
                return cookingTick(mod, ctx);
            }
        }
        furnaces.reset();
        committed = null;
        needEnd.reset();
        List<KitNeed> needs = KitPlanner.gather(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(), ctx.wood());
        Task side = support.tick(mod, ctx, needs);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        Task up = surface.logTick(mod, ctx, needs.isEmpty() ? null : needs.get(0));
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        Task task = runner.run(ctx, needs);
        hudState = runner.hud();
        return task;
    }

    // food is cooking in a smoker somewhere. same order as IRON's cookingTick: side jobs, the trip that already started, then
    // the gather list (wood, stone, crafts: all surface work) or the smoker itself when SmeltFiller says it is time. with nothing left to do the bot stands by the smoker with the screen closed, which beats staring at the gui
    private Task cookingTick(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        List<KitNeed> plan = KitPlanner.gather(f, ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(), ctx.wood());
        List<KitNeed> runnable = SmeltFiller.gatherRunnable(f, ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(), ctx.wood());
        KitNeed head = runnable.isEmpty() ? null : runnable.get(0);
        Task side = support.tick(mod, ctx, runnable);
        if (side != null) {
            hudState = support.hud();
            return side;
        }
        // (no quick smoker stand-by here: the gather work is right next to the smoker anyway)
        boolean boundary = head == null || !head.equals(committed);
        // food stuck behind its smoker, or a hungry food need at the head, only waits on food jobs. from far off only that (or the end of
        // the need) brings us back
        boolean blocking = SmeltFiller.gatherBlocking(f, plan);
        boolean hungry = ctx.food().band() == 0 || ctx.food().bag() <= 0;
        FurnacePlan.Moment moment = new FurnacePlan.Moment(head != null, boundary, needEnd.done(runnable, f.gameTime()),
                job -> (blocking && job.unitsEach > 0) || FurnacePlan.feeds(head, job, hungry, false), false, false, FurnaceWatch.where(mod), head == null ? "nothing" : head.catalogueName());
        FurnacePlan.Plan calls = furnaces.plan(mod, ctx, moment);
        Task trip = furnaces.trip(mod, ctx, calls, moment);
        if (trip != null) {
            if (furnaces.started()) {
                committed = null;
                needEnd.reset();
            }
            // standing by the smoker (screen closed between looks) is the plan, not a stall. but only until the food is due plus the
            // patience: a smoker that never finishes must not keep this alive
            if (furnaces.creditsWait(ctx)) {
                ctx.progress("waiting for the smoker");
            }
            hudState = furnaces.hud();
            return trip;
        }
        if (head == null) {
            return null;
        }
        Task up = surface.logTick(mod, ctx, head);
        if (up != null) {
            hudState = surface.hud();
            return up;
        }
        committed = head;
        needEnd.working(head, f.gameTime());
        Task task = runner.run(ctx, runnable);
        hudState = runner.hud() + " while the food cooks";
        return task;
    }

    // slow food (desert start, no animals around) must not end the run on its first phase: with both stone tools in
    // hand IRON carries on, its plan asks for the food again anyway
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (KitPlanner.stoneToolsMet(ctx.facts())) {
            return Timeout.SKIP;
        }
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }
}
