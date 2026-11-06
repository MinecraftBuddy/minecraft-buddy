package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.GameClock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

// the one place that answers "is this furnace or smoker still ours to wait on, collect, refuel or give up on". it used to be
// about fourteen timers in as many files that each had a say (and a slightly different number for the same question), now every
// job gets ONE call per tick and every number lives here with its reason.
// pure (jobs, a moment and the clock in, a call out) so the rules are testable without a game. two layers share the vocabulary:
//   the trip layer works from the job's memory, away from the station: should we go, and how (plan, leaving)
//   the visit layer works from the real slots, at the station: wait, refuel, take it back, leave it (atStation)
// the world halves are FurnaceWatch (trips) and CollectFromFurnaceTask (visits), they only do what this says
public final class FurnacePlan {
    // ---- the clock. every number the furnace logic waits on, and why it is that number

    // within this of done counts as done: the trip leaves a little early (the walk is the rest of the wait) and at the station we
    // stand and wait instead of walking off and coming straight back. also how far ahead of its guess an unvisited job is due
    public static final long NEARLY_TICKS = 200;
    // food still cooking when we leave the dimension is waited for all the way (the cap of the visit still ends it). the nether
    // cooks nothing, and taking a batch back raw just lets the cook gate load it again, whatever its size
    public static final long FOOD_LEAVE_TICKS = Integer.MAX_VALUE;
    // how long past the estimate we keep standing there: a furnace that never finishes (no fuel, a chunk that stopped ticking) must
    // not hold the run. the progress credit of a wait and the cap of one visit use it (a quick smoker's stand-by has its own, shorter
    // budget), and so does the revisit delay of a visit that gave up, so a capped job is not due again before it had its half minute
    public static final long PATIENCE_TICKS = 600;
    // waits to the end of the estimate with nothing coming out before the job is called stuck and its input is taken back
    public static final int STALL_LIMIT = 2;
    // a smoker does 5 s an item, so a batch with more than this left (a stack of 64 is five minutes) is not worth standing at, it is
    // worked like a furnace until it gets down to the comeback limit. asked again every look until the stand-by starts: judging it once at first
    // sight meant anything over a handful of items was never stood by at all. 40 s is eight items: a small batch is cheaper to wait
    // out than to walk off from and back to, a big one is a bot doing nothing for minutes. a batch seen over this comes back at the
    // (shorter) comeback limit below instead
    public static final long STAND_BY_MAX_TICKS = 800;
    // a batch that was over the limit when we first looked is a lot of food: we went off to work and only come back to stand for the
    // last bit of it, same as before the limit went up
    public static final long STAND_BY_COMEBACK_TICKS = 400;
    // the stand-by of a quick smoker lasts until the batch is done plus this, so a cook that runs a little late (or a job that got
    // re-stamped a few ticks out) does not cut the wait short with the last item still in there
    public static final long STAND_BY_SLACK_TICKS = 200;
    // ...and never longer than this from the moment its trip starts, whatever the job says: a smoker that stalled (no fuel, the food
    // taken) must not pin the bot. a full small batch plus its slack
    public static final long STAND_BY_HARD_CAP_TICKS = STAND_BY_MAX_TICKS + STAND_BY_SLACK_TICKS;
    // the far-trip wait: a batch that finishes within this is waited for before setting off on a long trip. not the quick stand-by,
    // that one has its own numbers above
    public static final long STAND_BY_CAP_TICKS = 600;
    // a job from a world that ran on without us (a relog days later) is not worth a walk: the contents are probably gone, or the
    // furnace is. half an hour of game time since it was loaded or last visited
    public static final long STALE_TICKS = 36000;
    // WAIT_ALL stands with the screen closed (the gui open for minutes means no eating, no seeing a creeper, and looks afk to a
    // server) and looks again at the next item or this often, whichever comes first, in case the furnace stalled meanwhile
    public static final long REOPEN_TICKS = 200;
    // ...and never so short that the screen flickers open every other tick
    public static final long MIN_IDLE_TICKS = 20;
    // leaving the mine: waiting for a nearly done job is no worse than the climb out, which takes this long per block of depth
    public static final long LEAVE_TICKS_PER_DEPTH = 20;
    // LEAVE -> an idle STAND_BY only follows the filler list having nothing runnable this tick, and that flickers (a need finishing,
    // food moving in and out of a screen). it has to be the answer this long in a row before the job changes its call, so a flicker is
    // no change at all, not a line per tick and not a wait trip that starts with the filler already back. the way back to LEAVE, a
    // first call, a due job, a stuck one and a leaving one never wait
    public static final long SOFT_HOLD_TICKS = 40;
    // the cook that uses the furnace we just emptied before it comes down: a side trip, not worth holding the furnace for longer
    public static final long COOK_FIRST_TICKS = 600;
    // the cook task with no change in the bag for this long is going nowhere (no fuel to be found, no room for a smoker)
    public static final long COOK_GIVE_UP_TICKS = 3000;
    // and after it gave up the planner stops asking for this long: long enough to get on with the run, short enough that a coal
    // found meanwhile still cooks before the nether
    public static final long COOK_BACKOFF_TICKS = 4800;
    // the station a cook picked is dropped when the task has not said so for this long (it stopped being ticked without a stop),
    // or the planner stays pinned to a smoker for ever
    public static final long COOK_STATION_STALE_TICKS = 200;
    // the climb to the surface before a load: past this the load happens down here, a bot stuck under a ceiling must not hold the run
    public static final long CLIMB_GIVE_UP_TICKS = 1800;
    // an early iron load is "in flight" this long without the job landing. the load itself is a few seconds, this is the backstop
    // for a furnace that got broken or a bot that died half way
    public static final long EARLY_LOAD_TICKS = 1800;

    // ---- the one distance

    // a due job only cuts into what we are doing from this close (blocks, 3d, inclusive). further out it waits for the end of the
    // thing: iron in a furnace does not spoil, a walk of 100 blocks back and 100 out again to finish the wool is the expensive part.
    // inside this the walk back is a few seconds and the work is still right there after
    public static final int COLLECT_CUT_IN = 32;

    private FurnacePlan() {
    }

    // ---- the vocabulary

    // what to do about one job
    public enum Call {
        // cooking and not due, or due and not worth cutting the current need short for: carry on
        LEAVE,
        // worth waiting next to it: short time left and nothing better to do
        STAND_BY,
        // go and take what is done, now
        COLLECT_NOW,
        // the fire went out with input left: put fuel in (a visit finds this out at the station)
        REFUEL,
        // give up on cooking it: go and take the whole input back out (stuck, or we are leaving)
        TAKE_ALL,
        // we are done with it: the visit emptied it (the bench takes the station down), or it went stale or is gone
        DONE
    }

    public enum Why {
        COOKING("not due yet"),
        MID_NEED("due, but the need we are on is not done"),
        BOUNDARY("due, and the need we were on is done"),
        INTERRUPT("due, and the output is holding up the plan"),
        STOCK_UP("due, and all we were doing was stocking up for later"),
        IDLE_DUE("due, and there is nothing else to do"),
        FAR_BUSY("due, but we are far off and busy, collecting after"),
        FAR_TRIP("a long trip is next, so this batch is seen to first"),
        EARLY_IRON("the early iron is nearly done, the armor is worth the wait"),
        IDLE_WAIT("nothing else to do, waiting it out"),
        QUICK("a smoker is quick, waiting for it instead of mining"),
        TOO_LONG("not due, and too long a batch to stand at, working it like a furnace"),
        STUCK("waited to the end of the estimate twice and nothing came out"),
        LEAVING_AREA("leaving the mine with it still cooking down here"),
        LEAVING_DIMENSION("leaving the overworld with it still cooking"),
        STALE("loaded too long ago to be worth a walk"),
        GONE("the block is gone");

        public final String text;

        Why(String text) {
            this.text = text;
        }
    }

    // how a visit stays at the station
    public enum Mode {
        // take what is done. wait if the rest is nearly done, otherwise leave it cooking and report how much is left
        NORMAL,
        // nothing else to do, stay until it is all out (or the cap runs out)
        WAIT_ALL,
        // we are leaving: wait if nearly done, otherwise take the unfinished input back out as well
        TAKE_ALL
    }

    // what is going on around the furnace right now, as the phase sees it. the phase knows its plan, this knows the furnace
    //   fillerLeft: the runnable list is not empty. atBoundary: the plan head changed (we are between two needs, close by that is
    //   enough). headDone: the stricter one for far off, the need we were on is really over or a side job just ended (NeedEnd).
    //   waitsOn: the jobs whose output the plan is stuck on, the one thing that may cut a need short from any distance (the early
    //   pick wants its ingots, food stuck behind its own smoker). per job, so a pick waiting on iron does not drag us to a smoker.
    //   stockUp: the need we are on is only there to use the wait, nothing waits on it. mayStandBy: the phase stands at a quick
    //   smoker instead of mining (IRON does, GATHER's work is right there anyway).
    //   me: where we stand, null = no idea (everything counts as close, the old behaviour). doing: what the far line says we are on
    //   farTrip: the need we are about to start is a long walk away (WorkbenchRules.farTrip), so a job that finishes soon is worth
    //   waiting for before we go: left behind it would sit done and cost a walk back
    //   earlyIron: the iron on the stove is the early batch the plan is waiting on (EarlyIronPick.collectNow). a pick or armor that
    //   is seconds from done is worth standing for like a far trip is, the walk back to a batch left done is the dear part
    public record Moment(boolean fillerLeft, boolean atBoundary, boolean headDone, Predicate<RunState.FurnaceJob> waitsOn, boolean stockUp,
                         boolean mayStandBy, RunState.Pos me, String doing, boolean farTrip, boolean earlyIron) {
        public Moment(boolean fillerLeft, boolean atBoundary, boolean headDone, Predicate<RunState.FurnaceJob> waitsOn, boolean stockUp,
                      boolean mayStandBy, RunState.Pos me, String doing, boolean farTrip) {
            this(fillerLeft, atBoundary, headDone, waitsOn, stockUp, mayStandBy, me, doing, farTrip, false);
        }

        public Moment(boolean fillerLeft, boolean atBoundary, boolean headDone, Predicate<RunState.FurnaceJob> waitsOn, boolean stockUp,
                      boolean mayStandBy, RunState.Pos me, String doing) {
            this(fillerLeft, atBoundary, headDone, waitsOn, stockUp, mayStandBy, me, doing, false, false);
        }

        // the short form the tests grew up on: one interrupt for every job, and nowhere in particular
        public Moment(boolean fillerLeft, boolean atBoundary, boolean interrupt, boolean stockUp, boolean mayStandBy) {
            this(fillerLeft, atBoundary, atBoundary, job -> interrupt, stockUp, mayStandBy, null, null, false, false);
        }
    }

    // where we are heading when we leave
    public enum Leaving {
        // out of the mine: waiting is worth as much as the climb costs
        AREA,
        // out of the dimension: nothing comes back for it
        DIMENSION
    }

    public record Verdict(RunState.FurnaceJob job, Call call, Why why, Mode mode, long nearly) {
    }

    // what FurnacePlan remembers about one job between ticks. transient on the job (RunState.FurnaceJob.track)
    public static final class Track {
        // the last trip decision: an active trip keeps it, whatever the plan would say now
        Call call;
        Why why;
        // game tick the raw answer first became "nothing else to do, wait" and has been since, -1 while it is anything else (the
        // soft hold)
        long idleSince = -1;
        // the last call that went into the log, from either layer: one line per change
        Call logged;
        // the stand-by budget, taken when the stand-by trip starts and never again. the job object is the
        // same one across visits (afterVisit re-stamps it in place), so a smoker that keeps coming up short cannot restart the clock
        boolean standSeen;
        // the last look said the batch has too long to go to stand at (the log says TOO_LONG instead of "not due yet")
        boolean standTooLong;
        // some look said it was over STAND_BY_MAX_TICKS and never cleared, unlike standTooLong which follows the clock down. a batch
        // that big is back to work, and only comes back for the shorter window. transient with the rest of the track, a relog judges
        // a stack afresh (with 40 s or less left it is stood at like a small one)
        boolean standBig;
        long standUntil = -1;
        // the "too far, collecting after" line went out for this wait. back to false once a trip (or any call but LEAVE) happens,
        // so walking in and out of the cut-in range is not a line every time
        boolean farSaid;
    }

    // ---- the trip layer

    public static boolean stuck(RunState.FurnaceJob job) {
        return job.stalls >= STALL_LIMIT;
    }

    // is this job due. an unvisited one is a guess, so it is due a little early (the walk there is the rest of the wait); a job we
    // already visited has an honest timer, and going back inside the slack of it is how the bot ping-ponged
    public static boolean due(RunState.FurnaceJob job, long now) {
        return now + (job.visited ? 0 : NEARLY_TICKS) >= job.doneTick;
    }

    public static boolean anyDue(List<RunState.FurnaceJob> jobs, long now) {
        for (RunState.FurnaceJob job : jobs) {
            if (due(job, now)) {
                return true;
            }
        }
        return false;
    }

    // an iron job we are close to that is done or done within the stand-by cap: the early batch's last half minute. one that has sat
    // done for the patience is a pickup that is not happening, and the night stops waiting for it
    public static boolean earlyIronNear(RunState.FurnaceJob job, RunState.Pos me, long now) {
        return "iron_ingot".equals(job.output) && me != null && close(job, me) && job.doneTick - now <= STAND_BY_CAP_TICKS
                && now - job.doneTick < PATIENCE_TICKS;
    }

    // is `me` within COLLECT_CUT_IN of the job. no idea where we are counts as close, that is how it always was
    public static boolean close(RunState.FurnaceJob job, RunState.Pos me) {
        if (me == null) {
            return true;
        }
        long dx = job.pos.x - me.x;
        long dy = job.pos.y - me.y;
        long dz = job.pos.z - me.z;
        return dx * dx + dy * dy + dz * dz <= (long) COLLECT_CUT_IN * COLLECT_CUT_IN;
    }

    // the head need is food we really need now (`hungry`: under the floor, or nothing edible in the bag) and this job is food cooking
    // for us: its output is the need, so it goes from any distance, same as the pick waiting on its ingots. a stock-up or a top-up
    // between the floor and the target keeps hunting where we are, the smoker is still there after. meat left cold in a station is
    // not cooking, that one waits. the cook never counts: CookGate does not ask for it while a food job is pending, and when it does
    // it picks or places its own station rather than waiting on ours
    public static boolean feeds(KitNeed head, RunState.FurnaceJob job, boolean hungry, boolean stockUp) {
        if (head == null || !hungry || stockUp || job.unitsEach <= 0 || job.stranded) {
            return false;
        }
        return KitNeed.FOOD.equals(head.catalogueName());
    }

    // a due job close enough to cut in: what a detour ends itself on. a far one waits for the detour like it waits for the wool
    public static boolean anyDueClose(List<RunState.FurnaceJob> jobs, long now, RunState.Pos me) {
        for (RunState.FurnaceJob job : jobs) {
            if (due(job, now) && close(job, me)) {
                return true;
            }
        }
        return false;
    }

    // standing by a furnace is only progress while a job in it can still finish: until the last job is due plus the patience (the
    // walk back, a collect that takes a moment). past that the wait is just a bot standing still and the stall timer may say so
    public static boolean waitIsHonest(List<RunState.FurnaceJob> jobs, long now) {
        for (RunState.FurnaceJob job : jobs) {
            if (now <= job.doneTick + PATIENCE_TICKS) {
                return true;
            }
        }
        return false;
    }

    // a visit may stand at the screen this long once it started waiting: what the estimate has left, plus the patience. a job already
    // past its estimate (we got there late) still gets the patience
    public static long waitCap(RunState.FurnaceJob job, long now) {
        return Math.max(0, job.doneTick - now) + PATIENCE_TICKS;
    }

    // a quick smoker's visit stands no longer than its stand-by budget. the budget running out only changes the plan's call,
    // the trip already under way keeps its own cap, so without this a slow smoker still had the bot standing there the full
    // estimate plus the patience
    public static long waitCap(RunState.FurnaceJob job, long now, boolean quick) {
        long cap = waitCap(job, now);
        Track t = job.track;
        if (!quick || !t.standSeen) {
            return cap;
        }
        return Math.min(cap, Math.max(0, t.standUntil - now));
    }

    // how close to done a leaving trip still waits. the climb costs about this much anyway, never under the usual nearly and never
    // over the patience
    public static long leaveWindow(int depth) {
        return Math.max(NEARLY_TICKS, Math.min(PATIENCE_TICKS, depth * LEAVE_TICKS_PER_DEPTH));
    }

    public static boolean stale(RunState.FurnaceJob job, long now) {
        return now - job.startTick > STALE_TICKS;
    }

    // drops what went stale and returns it. the jobs are a memory, the walk is the cost
    public static List<RunState.FurnaceJob> dropStale(List<RunState.FurnaceJob> jobs, long now) {
        List<RunState.FurnaceJob> gone = new ArrayList<>();
        for (RunState.FurnaceJob job : jobs) {
            if (stale(job, now)) {
                gone.add(job);
            }
        }
        jobs.removeAll(gone);
        return gone;
    }

    // standingBy = a quick smoker is being stood at, so the side jobs that walk off can wait
    public record Plan(List<Verdict> verdicts, Verdict pick, List<String> changes, boolean standingBy) {
    }

    // does standing here count as the bot doing something (the stall timer is told so). only while it is AT the station waiting for
    // the job the trip is for, and only until that job is due plus the patience: walking there is movement the watchdog already
    // sees, and a wait on a job that never finishes must be allowed to look stuck
    public static boolean creditsWait(boolean waitingAtStation, RunState.FurnaceJob target, long now) {
        return waitingAtStation && target != null && now <= target.doneTick + PATIENCE_TICKS;
    }

    // one call per job. `active` = the job a collect trip is already under way for (null = none): its call holds until the trip is
    // over, so a plan that flickers (the filler list emptying for a tick) cannot flip a trip that already walked there. the one
    // thing that still ends it is a quick smoker's stand-by budget.
    // `pick` = the trip to start now, null when there is none or one is already running. a quick smoker's stand-by outranks every
    // other collect (it is out in under 50 s, the furnace can wait that long), then the one that is ready first
    public static Plan plan(List<RunState.FurnaceJob> jobs, Moment m, long now, RunState.FurnaceJob active) {
        return plan(jobs, m, now, active, j -> false);
    }

    // `banned` = jobs whose furnace the pathing gave up on. they still get their calls, but they are picked last
    public static Plan plan(List<RunState.FurnaceJob> jobs, Moment m, long now, RunState.FurnaceJob active, Predicate<RunState.FurnaceJob> banned) {
        List<Verdict> out = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        boolean standingBy = false;
        for (RunState.FurnaceJob job : jobs) {
            Track t = job.track;
            boolean running = job == active && (t.call == Call.COLLECT_NOW || t.call == Call.STAND_BY || t.call == Call.TAKE_ALL);
            Verdict v;
            if (running && !budgetEnded(job, m, now)) {
                v = held(job);
            } else {
                v = settle(job, decide(job, m, now), m, now);
                t.call = v.call();
                t.why = v.why();
                String line = line(job, v, m, now);
                if (line != null) {
                    changes.add(line);
                }
            }
            out.add(v);
        }
        Verdict pick = null;
        if (active == null) {
            Predicate<Verdict> quick = v -> v.call() == Call.STAND_BY && v.why() == Why.QUICK;
            Predicate<Verdict> trip = v -> v.call() == Call.COLLECT_NOW || v.call() == Call.STAND_BY || v.call() == Call.TAKE_ALL;
            // banned goes behind every group, not just behind its own: a banned quick smoker still loses to a plain collect
            pick = soonest(out, quick, banned, false);
            if (pick == null) {
                pick = soonest(out, trip, banned, false);
            }
            if (pick == null) {
                pick = soonest(out, quick, banned, true);
            }
            if (pick == null) {
                pick = soonest(out, trip, banned, true);
            }
        }
        // a quick smoker only holds the side jobs once its trip is the one picked or going, not while another job's trip runs
        for (Verdict v : out) {
            if (v.call() == Call.STAND_BY && v.why() == Why.QUICK && (v == pick || v.job() == active)) {
                standingBy = true;
            }
        }
        return new Plan(out, pick, changes, standingBy);
    }

    // the bag is under the food floor: if the trip picked is not for food but a food job wants one too, that one goes first. the
    // plain pick is just the soonest ready, and an iron job ready a minute earlier would shadow the meat the bag is waiting for
    public static Plan foodFirst(Plan plan) {
        return foodFirst(plan, j -> false);
    }

    public static Plan foodFirst(Plan plan, Predicate<RunState.FurnaceJob> banned) {
        Verdict pick = plan.pick();
        if (pick == null || pick.job().unitsEach > 0) {
            return plan;
        }
        Predicate<Verdict> wantsFood = v -> v.job().unitsEach > 0
                && (v.call() == Call.COLLECT_NOW || v.call() == Call.STAND_BY || v.call() == Call.TAKE_ALL);
        Verdict food = soonest(plan.verdicts(), wantsFood, banned, false);
        // only banned food left: it does not shove a pick we can reach aside
        if (food == null && banned.test(pick.job())) {
            food = soonest(plan.verdicts(), wantsFood, banned, true);
        }
        return food == null ? plan : new Plan(plan.verdicts(), food, plan.changes(), plan.standingBy());
    }

    // LEAVE -> an idle STAND_BY follows a flickering input (the filler list having nothing runnable), so it has to be the answer
    // SOFT_HOLD_TICKS in a row. so does LEAVE -> a far IDLE_DUE: one empty tick 100 blocks out would be a trip that then holds to
    // the end. everything else (the way back to LEAVE, a first call, a close due job, a quick smoker, stuck, leaving) goes through at
    // once. a held answer is the old call, never a half way STAND_BY the pick could start a trip from
    private static Verdict settle(RunState.FurnaceJob job, Verdict raw, Moment m, long now) {
        Track t = job.track;
        boolean farIdle = raw.why() == Why.IDLE_DUE && !close(job, m.me());
        if (!farIdle && (raw.call() != Call.STAND_BY || raw.why() != Why.IDLE_WAIT)) {
            t.idleSince = -1;
            return raw;
        }
        if (t.idleSince < 0) {
            t.idleSince = now;
        }
        if (t.call != Call.LEAVE || now - t.idleSince >= SOFT_HOLD_TICKS) {
            return raw;
        }
        return verdict(job, Call.LEAVE, t.why);
    }

    // the running trip's job: whatever it was sent for
    private static Verdict held(RunState.FurnaceJob job) {
        Track t = job.track;
        return new Verdict(job, t.call, t.why, modeOf(t.call), NEARLY_TICKS);
    }

    // a quick smoker's stand-by is the one decision with a budget of its own, and it ends a running trip's hold too
    private static boolean budgetEnded(RunState.FurnaceJob job, Moment m, long now) {
        Track t = job.track;
        return t.call == Call.STAND_BY && t.why == Why.QUICK && !(quickCandidate(job, m) && standByOn(job, now));
    }

    private static Verdict decide(RunState.FurnaceJob job, Moment m, long now) {
        // judged by the batch, wherever we were and whatever the phase let us do with it: a stack seen from far off (or from GATHER)
        // is still a stack when we get close. kept apart from standTooLong, which clears as the clock runs down, or the limit would
        // climb back to 800 with it and the comeback threshold would flip-flop
        if (!job.track.standSeen && job.doneTick - now > STAND_BY_MAX_TICKS) {
            job.track.standBig = true;
        }
        boolean close = close(job, m.me());
        // a quick stand-by from far off is up to 40 s of smoker behind a walk longer than its whole budget, and it cuts in on the side
        // jobs (IronActivity.preempts). it gets its chance again once we are back in range
        if (close && quickCandidate(job, m) && standByOn(job, now)) {
            return stuckOr(job, verdict(job, Call.STAND_BY, Why.QUICK));
        }
        boolean due = due(job, now);
        Verdict v;
        if (!m.fillerLeft()) {
            // nothing else to do is the end of everything, any distance
            v = due ? verdict(job, Call.COLLECT_NOW, Why.IDLE_DUE) : verdict(job, Call.STAND_BY, Why.IDLE_WAIT);
        } else if (!due) {
            // setting off on a long trip with a batch about to finish leaves it sitting done until we are back: the walk back is
            // the expensive part, so one that finishes within the stand-by cap is waited for. a job that already stood out a visit
            // without anything coming out (stalls) or a smoker that used its quick budget is not: both are re-stamped with a fresh
            // half minute, and standing again would just be the same wait twice
            // the early iron batch gets the same treatment with no trip needed: its output is the armor, and a bot that walks off
            // to mine leaves the ingots done in the furnace while the night and the mobs eat the walk back
            boolean early = m.earlyIron() && "iron_ingot".equals(job.output);
            boolean waitFirst = close && (m.farTrip() || early) && job.doneTick - now <= STAND_BY_CAP_TICKS && job.stalls == 0
                    && !job.track.standSeen;
            v = waitFirst ? verdict(job, Call.STAND_BY, m.farTrip() ? Why.FAR_TRIP : Why.EARLY_IRON)
                    : verdict(job, Call.LEAVE, tooLong(job) ? Why.TOO_LONG : Why.COOKING);
        } else {
            v = dueCall(job, m, close);
        }
        return stuckOr(job, v);
    }

    // due, with something to do. the plan stuck on this output goes from anywhere, and so does the end of what we are on (from far
    // off only a real end, headDone: that IS the thing we came out here to finish). cutting a stock-up short only pays from close
    // by: from far off it is 200 blocks of walking to save a few seconds of waiting that cost nothing
    private static Verdict dueCall(RunState.FurnaceJob job, Moment m, boolean close) {
        if (m.waitsOn().test(job)) {
            return verdict(job, Call.COLLECT_NOW, Why.INTERRUPT);
        }
        if (close && m.stockUp()) {
            return verdict(job, Call.COLLECT_NOW, Why.STOCK_UP);
        }
        if (close ? m.atBoundary() : m.headDone()) {
            return verdict(job, Call.COLLECT_NOW, Why.BOUNDARY);
        }
        // a long trip about to start from right beside the furnace. a far job while we are already out still waits below, this is
        // not a reason to turn around halfway
        if (close && m.farTrip()) {
            return verdict(job, Call.COLLECT_NOW, Why.FAR_TRIP);
        }
        return verdict(job, Call.LEAVE, close ? Why.MID_NEED : Why.FAR_BUSY);
    }

    // two waits to the end of the estimate and nothing came out: it is not cooking (fuel that ran dry under a lit reading, an input it
    // will not smelt). take the input back out and let the planner redo it, and the station comes down with it once it is empty. an
    // unlit furnace with no fuel is already handled inside the visit, this is the rest
    private static Verdict stuckOr(RunState.FurnaceJob job, Verdict v) {
        boolean wantsTrip = v.call() == Call.COLLECT_NOW || v.call() == Call.STAND_BY;
        return wantsTrip && stuck(job) ? verdict(job, Call.TAKE_ALL, Why.STUCK) : v;
    }

    // meat left cold in a smoker is not running, and a furnace is 10 s an item: both keep their filler
    private static boolean quickCandidate(RunState.FurnaceJob job, Moment m) {
        return m.mayStandBy() && "smoker".equals(job.kind) && !job.stranded;
    }

    private static boolean standByOn(RunState.FurnaceJob job, long now) {
        Track t = job.track;
        if (!t.standSeen) {
            // not started yet: quick is only a question of what is left, asked again every look
            t.standTooLong = job.doneTick - now > (t.standBig ? STAND_BY_COMEBACK_TICKS : STAND_BY_MAX_TICKS);
            return !t.standTooLong;
        }
        return now <= t.standUntil;
    }

    // the stand-by trip really starts (FurnaceWatch.start): the budget starts here and never again. not at the pick, a pick
    // can sit behind a golem fight or the climb out for longer than the whole budget
    public static void startStandBy(RunState.FurnaceJob job, long now) {
        Track t = job.track;
        if (t.standSeen) {
            return;
        }
        t.standSeen = true;
        // stands until the batch is done plus the slack, a job already past due gets the slack from now, not from then. the hard cap
        // is the backstop for a doneTick that lies
        t.standUntil = Math.min(Math.max(now, job.doneTick) + STAND_BY_SLACK_TICKS, now + STAND_BY_HARD_CAP_TICKS);
    }

    private static boolean tooLong(RunState.FurnaceJob job) {
        return !job.track.standSeen && job.track.standTooLong;
    }

    private static Verdict verdict(RunState.FurnaceJob job, Call call, Why why) {
        return new Verdict(job, call, why, modeOf(call), NEARLY_TICKS);
    }

    public static Mode modeOf(Call call) {
        return switch (call) {
            case STAND_BY -> Mode.WAIT_ALL;
            case TAKE_ALL -> Mode.TAKE_ALL;
            default -> Mode.NORMAL;
        };
    }

    // a banned job (pathing gave up on its furnace) goes to the back, not out: dropping it would hold the phase open for ever.
    // `bannedOnes` = ask for the banned jobs only (the pick falls through to them when nothing else wants a trip)
    private static Verdict soonest(List<Verdict> verdicts, Predicate<Verdict> want, Predicate<RunState.FurnaceJob> banned, boolean bannedOnes) {
        return soonest(verdicts, v -> want.test(v) && banned.test(v.job()) == bannedOnes);
    }

    private static Verdict soonest(List<Verdict> verdicts, Predicate<Verdict> want) {
        Verdict best = null;
        for (Verdict v : verdicts) {
            if (want.test(v) && (best == null || v.job().doneTick < best.job().doneTick)) {
                best = v;
            }
        }
        return best;
    }

    // the trip under way is for a furnace the pathing gave up on, and a job that is not banned has a trip of its own to make (its call
    // from the last plan): let the trip go so the plan picks again. a banned target with nothing else to do keeps going
    public static boolean abandonBanned(RunState.FurnaceJob target, List<RunState.FurnaceJob> jobs, Predicate<RunState.FurnaceJob> banned) {
        if (target == null || !banned.test(target)) {
            return false;
        }
        for (RunState.FurnaceJob job : jobs) {
            Call c = job.track.call;
            if (job != target && !banned.test(job) && (c == Call.COLLECT_NOW || c == Call.STAND_BY || c == Call.TAKE_ALL)) {
                return true;
            }
        }
        return false;
    }

    // the call for a job we are about to leave behind. nothing else asks: the phase says where it is going and which job is worth the
    // trip (PackUp for a mine, the soonest for a dimension), and the answer is always to take it with us
    public static Verdict leaving(RunState.FurnaceJob job, Leaving why, int depth) {
        boolean area = why == Leaving.AREA;
        // the nether has no smoker, so meat we walk out on stays raw (and the PORTAL gate would just load it again). food on its
        // way out of the dimension is waited for, whole
        long window = area ? leaveWindow(depth) : job.unitsEach > 0 && !job.stranded ? FOOD_LEAVE_TICKS : NEARLY_TICKS;
        Verdict v = new Verdict(job, Call.TAKE_ALL, area ? Why.LEAVING_AREA : Why.LEAVING_DIMENSION, Mode.TAKE_ALL, window);
        job.track.call = v.call();
        job.track.why = v.why();
        return v;
    }

    // ---- the log. one `furnace:` line per change of call per job, from either layer

    // the line to log when `call` is not what this job last logged, else null
    public static String say(RunState.FurnaceJob job, Call call, String reason, long now) {
        if (job.track.logged == call) {
            return null;
        }
        job.track.logged = call;
        long left = job.doneTick - now;
        String timer = left > 0 ? "~" + Math.round(left / 20.0) + " s to go" : "due " + Math.round(-left / 20.0) + " s ago";
        return "furnace: " + job.kind + " at " + job.pos + ": " + call + " (" + reason + "; " + job.count + " " + job.input + ", " + timer + ")";
    }

    // the plan's line for a fresh verdict. a far wait is a LEAVE like the ones around it, so the per-call dedupe would never say it:
    // it gets its own line, once per wait
    private static String line(RunState.FurnaceJob job, Verdict v, Moment m, long now) {
        Track t = job.track;
        if (v.why() != Why.FAR_BUSY) {
            if (v.call() != Call.LEAVE) {
                t.farSaid = false;
            }
            return say(job, v.call(), v.why().text, now);
        }
        t.logged = v.call();
        if (t.farSaid) {
            return null;
        }
        t.farSaid = true;
        return "furnace: " + job.kind + " at " + job.pos.x + "," + job.pos.y + "," + job.pos.z + " is due, but we're " + away(job, m.me())
                + " blocks away doing " + (m.doing() == null ? "something" : m.doing()) + ", collecting after";
    }

    private static long away(RunState.FurnaceJob job, RunState.Pos me) {
        double dx = job.pos.x - me.x;
        double dy = job.pos.y - me.y;
        double dz = job.pos.z - me.z;
        return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    // a job that is over without a visit: dropped as stale, or its block is gone
    public static String done(RunState.FurnaceJob job, Why why, long now) {
        return say(job, Call.DONE, why.text, now);
    }

    // ---- the visit layer

    // what the slots say right now. `waited` = ticks since this visit started waiting at the screen, -1 = not waiting yet
    public record Look(boolean outputPresent, int input, boolean lit, boolean fuelSlotEmpty, boolean spareFuel, long remaining,
                       long untilDry, long waited) {
    }

    public enum Act {
        TAKE_OUTPUT(Call.COLLECT_NOW, true),
        TAKE_SPARE_FUEL(Call.COLLECT_NOW, true),
        // stand here (the screen stays closed between looks in WAIT_ALL)
        WAIT(Call.STAND_BY, false),
        // stood to the end of the cap and it is still not done: come back later, the count says how long
        LEAVE_CAPPED(Call.LEAVE, false),
        LEAVE_COOKING(Call.LEAVE, false),
        FEED(Call.REFUEL, false),
        KEEP_FEEDING(Call.REFUEL, false),
        // the input comes back out because the station was not cooking it (cold with no fuel, or it never finished)
        TAKE_BACK_STALLED(Call.TAKE_ALL, false),
        // ...or because we are leaving
        TAKE_BACK(Call.TAKE_ALL, false),
        DONE(Call.DONE, false);

        public final Call call;
        // part of a visit that was already announced as a collect: not a change worth a line
        public final boolean quiet;

        Act(Call call, boolean quiet) {
            this.call = call;
            this.quiet = quiet;
        }
    }

    // the chain the visit follows. in this order: what is done comes out first, then the input is waited for, left, refueled or taken
    // back, and an empty station only gives its spare fuel back before it is done
    public static Act atStation(Look l, Mode mode, long nearly, long cap, boolean fedBefore, boolean feedingNow, BooleanSupplier canFeed) {
        if (l.outputPresent()) {
            return Act.TAKE_OUTPUT;
        }
        if (l.input() <= 0) {
            // all out. the leftover fuel is worth carrying, the next smelt needs it
            return !l.fuelSlotEmpty() && l.spareFuel() ? Act.TAKE_SPARE_FUEL : Act.DONE;
        }
        // not lit and no fuel to light it with is a furnace that will never finish. not lit WITH fuel is one tick from lit
        boolean stalled = !l.lit() && l.fuelSlotEmpty();
        // fuel in the slot counts as lit: the tick after we feed it the fire still reads out, and leaving then walked off with the
        // last item a few seconds from done and came straight back for it. a feed this visit always leaves fuel or fire, so
        // fedBefore is already in here
        boolean nearlyDone = !stalled && l.remaining() <= nearly;
        // it did not finish when it should have. what is in there stays in there (the job keeps its own count), or comes back out
        // when we are leaving
        boolean capped = l.waited() >= 0 && l.waited() > cap;
        if (!stalled && !capped && (mode == Mode.WAIT_ALL || nearlyDone || dryingSoon(l.lit(), mode, l.untilDry(), nearly))) {
            return Act.WAIT;
        }
        if (capped && mode != Mode.TAKE_ALL) {
            return Act.LEAVE_CAPPED;
        }
        // the click is done the moment the slot has anything or it is lit: the server burns the first item as it lands, so the slot
        // reads one short of the target and the move would push one more in
        if (feedingNow) {
            return Act.KEEP_FEEDING;
        }
        if (mayFeed(stalled, mode, fedBefore) && canFeed.getAsBoolean()) {
            return Act.FEED;
        }
        if (stalled || capped) {
            return Act.TAKE_BACK_STALLED;
        }
        return mode == Mode.TAKE_ALL ? Act.TAKE_BACK : Act.LEAVE_COOKING;
    }

    // fuel goes into a cold station once per visit, and never when we are here to take everything back out (leaving the mine, a
    // job that is stuck): lighting it would burn a coal, take the meat out anyway and never tell the cook to back off
    public static boolean mayFeed(boolean stalled, Mode mode, boolean fedBefore) {
        return stalled && mode != Mode.TAKE_ALL && !fedBefore;
    }

    // lit but about to run out, in a mode that stays: stand by until it goes cold, then the visit refuels it or takes the input back.
    // leaving now would re-stamp a timer that is up in seconds and walk back over and over while the bot is still in reach
    public static boolean dryingSoon(boolean lit, Mode mode, long untilDry, long nearly) {
        return lit && mode != Mode.TAKE_ALL && untilDry <= nearly;
    }

    // ticks until the fuel in and under the station is used up, Long.MAX_VALUE when it covers the rest of the input (the arrow's
    // share needs none). a load left short on purpose runs dry long before its input is done, and a visit that re-stamps the job as
    // if the fuel kept going has the next one show up to a cold station minutes late
    public static long fuelTicks(String kind, int input, double arrow, double litItems, double slotItems) {
        double fuel = Math.max(litItems, 0) + Math.max(slotItems, 0);
        if (fuel + Math.min(1.0, Math.max(0, arrow)) >= input) {
            return Long.MAX_VALUE;
        }
        return Math.round(FurnaceJobs.ticksPerItem(kind) * fuel);
    }

    // how long the job we leave behind still needs. only for the acts that leave something in the station. a visit that gave up
    // waiting says half a minute, the arrow is no use (it should have been done by now and was not); one that chose to leave says
    // what the furnace itself says, or when the fuel runs out if that comes first (a load left short, the next visit refuels it)
    public static long leftTicks(Act act, Look l) {
        return act == Act.LEAVE_CAPPED ? Math.max(l.remaining(), PATIENCE_TICKS) : Math.min(l.remaining(), l.untilDry());
    }

    // how long to stand with the screen closed: until the next item is out, but never longer than the reopen timer and never so
    // short that we flicker the screen open every other tick
    public static long idleTicks(long ticksUntilNextOutput) {
        return Math.max(MIN_IDLE_TICKS, Math.min(REOPEN_TICKS, ticksUntilNextOutput + 10));
    }

    // what a visit says about itself, for the log
    // what the slots looked like when the visit picked `act`, for the log. the fire reads out the tick after a feed, and whether a
    // leave was that or a real cold station is the whole question when one comes back for the last ingot
    public static String visitState(Act act, Look l) {
        String secs = l.remaining() == Long.MAX_VALUE ? "?" : String.valueOf(Math.round(l.remaining() / 20.0));
        String dry = l.untilDry() == Long.MAX_VALUE ? "covered" : "dry in ~" + Math.round(l.untilDry() / 20.0) + " s";
        return act + " (" + (l.lit() ? "lit" : "not lit") + ", fuel slot " + (l.fuelSlotEmpty() ? "empty" : "has fuel") + ", "
                + l.input() + " in, ~" + secs + " s left, " + dry + ")";
    }

    // the part of visitState that is worth a new line: the act and the two fire readings, not the clocks ticking down
    public static String visitKey(Act act, Look l) {
        return act + "/" + l.lit() + "/" + l.fuelSlotEmpty();
    }

    public static String visitText(Act act, Look l) {
        String secs = l.remaining() == Long.MAX_VALUE ? "?" : String.valueOf(Math.round(l.remaining() / 20.0));
        return switch (act) {
            case WAIT -> "waiting here, ~" + secs + " s of cooking left";
            case LEAVE_CAPPED -> "stood as long as it was worth and " + l.input() + " is still in it, back in a while";
            case LEAVE_COOKING -> l.input() + " still cooking, ~" + secs + " s, leaving it";
            case FEED, KEEP_FEEDING -> "the fire is out with " + l.input() + " left, putting fuel in";
            case TAKE_BACK_STALLED -> "cold or never finished, taking the " + l.input() + " back out";
            case TAKE_BACK -> "leaving, taking the " + l.input() + " back out";
            case DONE -> "empty";
            default -> "taking what is done";
        };
    }

    // ---- the cook (what the running cook task tells the planner, held in RunState.cook)

    public static void cookSuspend(RunState.Cook cook, long now) {
        cook.until = now + COOK_BACKOFF_TICKS;
        cook.station = null;
    }

    public static boolean cookSuspended(RunState.Cook cook, long now) {
        return now < cook.until;
    }

    // the task stamps this every tick it runs
    public static void cookCommit(RunState.Cook cook, boolean smoker, long now) {
        cook.station = smoker ? "smoker" : "furnace";
        cook.stamp = now;
    }

    public static void cookRelease(RunState.Cook cook) {
        cook.station = null;
    }

    // "smoker" or "furnace" while a cook task has its station, null when none or the stamp went stale
    public static String cookStation(RunState.Cook cook, long now) {
        String s = cook.station;
        return s != null && now - cook.stamp <= COOK_STATION_STALE_TICKS ? s : null;
    }

    // the cook has been going nowhere: nothing changed in the bag since `lastChange`
    public static boolean cookGaveUp(long lastChange, long now) {
        return now - lastChange > COOK_GIVE_UP_TICKS;
    }

    // the cook before the pickup has run long enough
    public static boolean cookFirstExpired(long started, long now) {
        return now - started > COOK_FIRST_TICKS;
    }

    // ---- the climb and the early load

    public static boolean climbGaveUp(long since, long now) {
        return now - since > CLIMB_GIVE_UP_TICKS;
    }

    // is an early load that started at `since` still worth waiting for. -1 = none, and a clock that went backwards (a relog,
    // another world) is not a flight
    public static boolean earlyLoadInFlight(long since, long now) {
        return since >= 0 && GameClock.since(since, now) < EARLY_LOAD_TICKS;
    }
}
