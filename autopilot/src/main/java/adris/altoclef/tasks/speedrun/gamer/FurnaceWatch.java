package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.resources.CookRawFoodTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StationHook.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

// the game side of smelting in the background: keeps the job list honest (stale ones, furnaces that are gone) and hands out
// the task that goes back for the iron. no leash: the bot goes wherever the work is and remembers where the furnace is. one
// per phase that can have jobs (IRON, and PORTAL for the last look before we leave). the calls it follows are FurnacePlan's (when
// to go, how long to wait, when to give up), the bookkeeping is FurnaceJobs, this is only the part that needs a world. the station
// coming down after the last collect is Workbenches' business now: this only decides that the visit that emptied it is the moment,
// and cooks first when the rules say so
public final class FurnaceWatch {
    private CollectFromFurnaceTask task;
    private RunState.FurnaceJob target;
    // the target's count when its trip started
    private int visitCount;
    private String hud;
    // the last trip() started a new trip (the phase resets what it was on when that happens)
    private boolean fresh;
    // the trip under way started as a stand-by at a quick smoker (IronActivity tells that apart from a plain collect)
    private boolean quick;
    // the last call handed back the finishing (the cook before the pickup, or the pickup) because a visit just ended
    private boolean handedOver;
    private final Workbenches benches;
    // PORTAL takes the one it just emptied too (newExitPhase), but never loads meat into it
    private boolean cookHere = true;
    // the furnace we just emptied is standing right here and we carry raw meat: it cooks that before it comes down
    private Task cook;
    private long cookStart;
    private RunState.Pos cookAt;
    private boolean cookSmoker;

    public FurnaceWatch() {
        this(new Workbenches());
    }

    // the same Workbenches the phase's PrepSupport has, or the two would each hold a task for the same pickup
    public FurnaceWatch(Workbenches benches) {
        this.benches = benches;
    }

    // jobs come and go, every batch empties the list
    public void reset() {
        cook = null;
        cookAt = null;
        task = null;
        target = null;
        hud = null;
    }

    // a death: the trip and the cook in flight belonged to the old body (the screen they had open is gone). the jobs are in
    // RunState and stay, the plan sends a new trip. the job already follows the slots (settleMidVisit), so nothing to settle here
    public void respawned() {
        reset();
        quick = false;
        fresh = false;
        handedOver = false;
    }

    // a fresh phase
    public void newPhase() {
        reset();
        cookHere = true;
    }

    // a phase that only wants the station off the ground on its way out: it would not come back for a furnace that gets
    // loaded with meat here, so it never loads one
    public void newExitPhase() {
        newPhase();
        cookHere = false;
    }

    // the cook that reuses the furnace we just emptied leaves it cooking right where it stands. when that is the bottom of
    // a mine and the next work is not down there, it is a trip back (the smoker at y 32 that cost 40 s): the caller says no
    public void mayCookHere(boolean yes) {
        cookHere = yes;
    }

    // the cook that runs before the station comes down is going (the phase must not end under it). the pickup itself is on the
    // bench (Workbenches.phaseMayEnd)
    public boolean cooking() {
        return cook != null;
    }

    // what to do about the station we just emptied, first thing every tick: the cook that goes before it comes down, then the
    // pickup that is in flight (any station's, a pickup runs to the end whatever the plan says)
    public Task finishing(AltoClef mod, GamerContext ctx) {
        if (cook != null) {
            // loaded is done (the job lands in the state next tick), and a cook that drags on is not worth holding the furnace for
            boolean finished = cook.isFinished(mod);
            if (finished || FurnacePlan.cookFirstExpired(cookStart, ctx.facts().gameTime())) {
                // timed out part way (a long coal trip) with the meat already in the furnace: it is not a job yet, and breaking the
                // furnace now would spill it. leave a job, the furnace stays and the visit it brings takes the furnace back
                boolean stranded = !finished && cook instanceof CookRawFoodTask raw && raw.recordLeftBehind(mod);
                RunState.Pos spot = cookAt;
                cook = null;
                cookAt = null;
                hud = null;
                if (stranded) {
                    // same backoff as the cook's own give up, or the visit that takes the meat back out finds an empty furnace we
                    // are standing at and starts the same cook (and the same coal trip) all over again
                    FurnacePlan.cookSuspend(ctx.state().cook, ctx.facts().gameTime());
                    Debug.logInternal("bench: " + (cookSmoker ? "smoker" : "furnace") + " still has our meat in it, leaving it for the job instead of breaking it at " + spot);
                } else if (spot != null) {
                    // loaded: the job is the memory now and its last collect takes the furnace back (a station with a job is
                    // not ours to take: LEAVE). not loaded (gave up, timed out, the meat was gone): it was coming down anyway
                    String dimension = ctx.facts().dimension().name();
                    boolean ours = ours(ctx.state(), cookSmoker ? "smoker" : "furnace", spot, dimension);
                    if (WorkbenchRules.afterVisit(0, ours, FurnaceJobs.isBusy(ctx.state(), spot, dimension), false) == WorkbenchRules.Visit.PICK_UP) {
                        Debug.logInternal("bench: " + (cookSmoker ? "smoker" : "furnace") + " did not get the meat, taking it back at " + spot);
                        benches.pickUpNow(mod, ctx, spot, "the cook did not use it");
                    }
                }
            } else {
                hud = "Cooking the meat while the furnace is free";
                return cook;
            }
        }
        Task pickup = benches.resume(mod, ctx);
        hud = pickup == null ? null : benches.hud();
        return pickup;
    }

    // plain words for what we are doing about the furnace, null when nothing
    public String hud() {
        return hud;
    }

    // every tick that has jobs: forget the old ones and the ones whose furnace is gone. our own digging is kept off the rest by
    // GamerTask's jobSpots, which follows the job list (it used to be avoidBlockBreaking(at) here, every tick, into whatever
    // behaviour level was on top: those never came off, so a furnace that once had a job stayed "do not mine" for the whole run)
    public void housekeeping(AltoClef mod, GamerContext ctx) {
        List<RunState.FurnaceJob> jobs = ctx.state().furnaceJobs;
        long now = ctx.facts().gameTime();
        List<RunState.FurnaceJob> gone = FurnacePlan.dropStale(jobs, now);
        if (!gone.isEmpty()) {
            ctx.log("giving up on a furnace we loaded a long time ago");
            for (RunState.FurnaceJob job : gone) {
                Workbenches.giveUp(ctx.state(), job.pos, job.dimension);
                say(FurnacePlan.done(job, FurnacePlan.Why.STALE, now));
            }
        }
        for (RunState.FurnaceJob job : new ArrayList<>(ctx.facts().furnaceJobs())) {
            BlockPos at = at(job);
            if (mod.getChunkTracker().isChunkLoaded(at)
                    && !BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(at).getBlock()).getPath().equals(job.kind)) {
                // somebody (a creeper, us) took the furnace, and whatever was in it went with it
                jobs.remove(job);
                gone.add(job);
                say(FurnacePlan.done(job, FurnacePlan.Why.GONE, now));
                ctx.log("the furnace at " + job.pos + " is gone, forgetting what was in it");
            }
        }
        if (!gone.isEmpty()) {
            if (target != null && gone.contains(target)) {
                task = null;
                target = null;
            }
            ctx.save();
        }
    }

    // the collect trip that is already under way (finishing it when it is done), null when there is none. a trip that
    // started keeps going whatever the plan says, it already walked there
    public Task active(AltoClef mod, GamerContext ctx) {
        return active(mod, ctx, false);
    }

    // `mayAbandon` = the plan picks the trips (trip()), so a trip to a banned furnace can be let go for one that is not
    private Task active(AltoClef mod, GamerContext ctx, boolean mayAbandon) {
        handedOver = false;
        if (task == null) {
            return null;
        }
        // what the visit decided at the station goes in the log next to the call the trip started with
        CollectFromFurnaceTask.Said said = task.said();
        if (said != null && target != null) {
            say(FurnacePlan.say(target, said.call(), said.text(), ctx.facts().gameTime()));
        }
        if (!task.isFinished(mod)) {
            if (target != null && FurnaceJobs.settleMidVisit(ctx.state().furnaceJobs, target, task.seenLeft(), task.seenTicks(),
                    ctx.facts().gameTime())) {
                ctx.progress("collected from the furnace");
                ctx.save();
            }
            // the furnace under the trip got banned (nothing paths to it) and another job has a trip to make: the plan picks again
            if (mayAbandon && FurnacePlan.abandonBanned(target, ctx.facts().furnaceJobs(), banned(mod))) {
                drop();
                return null;
            }
            return task;
        }
        RunState.FurnaceJob visited = target;
        if (visited == null) {
            // housekeeping dropped the job under the trip, nothing to re-stamp
            task = null;
            return null;
        }
        int left = task.inputLeft();
        long now = ctx.facts().gameTime();
        long guess = visited.doneTick - now;
        // the count the visit started with: settleMidVisit lowered the job as the bag filled, and the stall and progress calls below
        // are about the whole visit
        visited.count = Math.max(visited.count, visitCount);
        int before = visited.count;
        FurnaceJobs.afterVisit(ctx.state().furnaceJobs, target, left, task.leftTicks(), now, task.cappedOut());
        if (left > 0) {
            // the estimate assumed the chunk ticked the whole time we were away, now it is what the furnace itself says
            Debug.logInternal("furnace at " + visited.pos + " still has " + left + " cooking, ready in "
                    + Math.round(task.leftTicks() / 20.0) + " s (we had guessed " + Math.round(guess / 20.0) + " s), timer re-stamped"
                    + (visited.stalls > 0 ? " (" + visited.stalls + " visits with nothing coming out)" : ""));
        }
        // only a trip that got something out (or emptied it) is progress. a capped wait that saw the same count it started
        // with used to count too, and with the fresh timer above every wait after it looked honest, so a furnace that never
        // finished held the phase for ever
        if (left < before) {
            ctx.progress("collected from the furnace");
        }
        // meat that came back out because the station was cold: the cook that put it there would walk straight back and do the same
        // thing (raw in the bag, a station, enough fuel on paper), so it sits out the usual backoff
        if (FurnaceJobs.backsOffCook(visited, task.tookBackStalled())) {
            FurnacePlan.cookSuspend(ctx.state().cook, now);
            Debug.logInternal("took the " + visited.input + " back out of the cold " + visited.kind + ", the cook sits out a while");
        }
        ctx.save();
        task = null;
        target = null;
        hud = null;
        Task next = afterVisit(mod, ctx, visited, left);
        handedOver = next != null;
        return next;
    }

    // the visit is over. a station that still has something cooking stays (busy, never taken). an emptied one of ours comes down
    // right now, before we walk off and the next craft has a say: after the raw meat in the bag has cooked in it, but only when no
    // smoker of ours is within NEAR or in the bag, the smoker is where meat goes (WorkbenchRules.cookInSmoker)
    private Task afterVisit(AltoClef mod, GamerContext ctx, RunState.FurnaceJob visited, int left) {
        GamerFacts f = ctx.facts();
        RunState.Pos spot = new RunState.Pos(visited.pos.x, visited.pos.y, visited.pos.z);
        boolean smoker = "smoker".equals(visited.kind);
        String dimension = f.dimension().name();
        boolean ours = ours(ctx.state(), visited.kind, spot, dimension);
        // (CookGate.reusable already wants MIN_RAW meat, so a bagged smoker only ever vetoes a cook that was going to happen)
        // the tick's wood budget was built with this job still booked, and a booked cook holds back the cook's fuel claim (CookGate.waiting),
        // so it read "not enough fuel" for logs the cook gate says yes to one tick later, and the smoker came down with the meat in the bag.
        // the job is gone now, ask the budget again
        WoodBudget wood = WoodBudget.of(f, ctx.cfg().overworld, ctx.cfg().end.beds, ctx.state().phase);
        boolean mayCook = cookHere && WorkbenchRules.emptiedStationMayCook(smoker, ctx.stations().smokerStandingNear(), f.has(Items.SMOKER))
                && CookGate.reusable(f, ctx.cfg().overworld, ctx.cfg().end.beds, smoker, wood);
        switch (WorkbenchRules.afterVisit(left, ours, FurnaceJobs.isBusy(ctx.state(), spot, dimension), mayCook)) {
            case COOK_THEN_PICK_UP -> {
                // an empty furnace is the best place for the raw meat we are carrying, and we are standing at it. the fuel reserve of
                // this tick was set from the old budget too, so the cook gets the fresh one before its first look at the logs
                WoodReserve.apply(wood);
                cook = new CookRawFoodTask(smoker);
                cookStart = f.gameTime();
                cookAt = spot;
                cookSmoker = smoker;
                Debug.logInternal("bench: " + visited.kind + " is empty and we hold " + CookGate.raw(f) + " raw meat, cooking it before taking the "
                        + visited.kind + " back");
                return finishing(mod, ctx);
            }
            case PICK_UP -> {
                if (!benches.pickUpNow(mod, ctx, spot, "the visit emptied it")) {
                    return null;
                }
                return finishing(mod, ctx);
            }
            default -> {
                return null;
            }
        }
    }

    // every tick the phase has jobs: one call per job (FurnacePlan.plan), the changes into the log. the trip under way holds its
    // job's call, so this can be asked before active() has had its say
    public FurnacePlan.Plan plan(AltoClef mod, GamerContext ctx, FurnacePlan.Moment moment) {
        GamerFacts f = ctx.facts();
        FurnacePlan.Plan plan = FurnacePlan.plan(f.furnaceJobs(), moment, f.gameTime(), task == null ? null : target, banned(mod));
        for (String line : plan.changes()) {
            say(line);
        }
        return plan;
    }

    // the trip that is under way, or the one the plan picked (a stand-by at a quick smoker, a collect, a take-back of what is
    // stuck), null when the plan wants none. a trip that ends on this very call changes the jobs under the plan, so it is made again
    public Task trip(AltoClef mod, GamerContext ctx, FurnacePlan.Plan plan, FurnacePlan.Moment moment) {
        fresh = false;
        boolean was = task != null;
        Task running = active(mod, ctx, true);
        if (running != null) {
            return running;
        }
        FurnacePlan.Verdict pick = was ? plan(mod, ctx, moment).pick() : plan.pick();
        return pick == null ? null : start(mod, ctx, pick);
    }

    // the job the trip under way is for, null when there is no trip
    public RunState.FurnaceJob tripJob() {
        return task != null ? target : null;
    }

    // the phase let the trip go (the food has the wheel). the task goes with its wait clock, or the first look after we are back
    // would read the whole detour as time spent standing at the furnace and give up on a job that is fine. the plan decides again
    public void drop() {
        task = null;
        target = null;
        quick = false;
        hud = null;
    }

    // standing at the station waiting for the job the trip is for, and it can still finish: the phase tells the stall timer so
    public boolean creditsWait(GamerContext ctx) {
        return FurnacePlan.creditsWait(task != null && task.waiting(), target, ctx.facts().gameTime());
    }

    // the last trip() started a new trip rather than handing back the one under way
    public boolean started() {
        return fresh;
    }

    // the trip under way is a stand-by at a quick smoker
    public boolean standingBy() {
        return task != null && quick;
    }

    // the last trip()/leave()/active() ended a visit and handed back what comes after it (the cook, the pickup) instead of a trip
    public boolean handedOver() {
        return handedOver;
    }

    // the phase is leaving (the mine, the dimension) and this job comes with us. `depth` = how far down it is, for the window a
    // nearly done job is still waited for
    public Task leave(AltoClef mod, GamerContext ctx, RunState.FurnaceJob job, FurnacePlan.Leaving why, int depth) {
        fresh = false;
        Task running = active(mod, ctx);
        if (running != null) {
            return running;
        }
        // a pick made before active() ended the visit can name a job that visit just removed (identity: same objects as the facts list)
        if (job == null || !FurnaceJobs.listed(ctx.state().furnaceJobs, job)) {
            return null;
        }
        return start(mod, ctx, FurnacePlan.leaving(job, why, depth));
    }

    // out of the dimension: the job that is ready first, then the next one on the next call
    public Task leave(AltoClef mod, GamerContext ctx, FurnacePlan.Leaving why) {
        // finish the visit first: it may remove the job the pick below would have named (argument order bit us once)
        fresh = false;
        Task running = active(mod, ctx);
        if (running != null) {
            return running;
        }
        return leave(mod, ctx, FurnaceJobs.soonest(ctx.facts().furnaceJobs(), banned(mod)), why, 0);
    }

    private Task start(AltoClef mod, GamerContext ctx, FurnacePlan.Verdict v) {
        RunState.FurnaceJob job = v.job();
        long now = ctx.facts().gameTime();
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(job.kind));
        target = job;
        visitCount = job.count;
        quick = v.call() == FurnacePlan.Call.STAND_BY && v.why() == FurnacePlan.Why.QUICK;
        if (quick) {
            FurnacePlan.startStandBy(job, now);
        }
        // the call is already in the log when the plan made it, a leaving one is announced here
        say(FurnacePlan.say(job, v.call(), v.why().text, now));
        task = new CollectFromFurnaceTask(at(job), block, job.kind, v.mode(), v.nearly(), FurnacePlan.waitCap(job, now, quick));
        boolean smoker = "smoker".equals(job.kind);
        hud = v.call() == FurnacePlan.Call.STAND_BY ? (smoker ? "Waiting for the smoker" : "Waiting for the furnace")
                : "Collecting from the " + (smoker ? "smoker" : "furnace");
        fresh = true;
        return task;
    }

    private static void say(String line) {
        if (line != null) {
            Debug.logInternal(line);
        }
    }

    // one of the stations we put down in this dimension (a village's blast furnace has no list, so it is never ours). the lists key on
    // x y z alone, so the dimension it was placed in is asked too, or a furnace at the same spot in the nether is ours here
    // (package private so the tests can ask it, nothing else calls it from outside)
    static boolean ours(RunState state, String kind, RunState.Pos pos, String dimension) {
        List<RunState.Pos> own = FurnaceJobs.placedFor(state, kind);
        Kind which = "smoker".equals(kind) ? Kind.SMOKER : Kind.FURNACE;
        return own != null && own.contains(pos) && Workbenches.dimensionOf(state, which, pos).equals(dimension);
    }

    // where we stand, for the Moment's cut-in distance. null without a player, which the plan reads as close (the old rules)
    public static RunState.Pos where(AltoClef mod) {
        if (mod.getPlayer() == null) {
            return null;
        }
        BlockPos p = mod.getPlayer().blockPosition();
        return new RunState.Pos(p.getX(), p.getY(), p.getZ());
    }

    // pathing gave up on this job's furnace (InteractWithBlockTask strikes it, the ban book remembers). such a job goes to the back
    public static java.util.function.Predicate<RunState.FurnaceJob> banned(AltoClef mod) {
        return job -> mod.getBlockTracker().unreachable(at(job));
    }

    private static BlockPos at(RunState.FurnaceJob job) {
        return new BlockPos(job.pos.x, job.pos.y, job.pos.z);
    }
}
