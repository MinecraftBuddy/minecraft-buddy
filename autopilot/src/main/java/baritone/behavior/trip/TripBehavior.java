/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.trip;

import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.Behavior;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// "may I collect some blocks?". watches trips the user started (goto, come, waypoint goto, click to go), asks at most
// once per trip when the goal needs blocks we don't have or a much faster route does, and hands a yes to altoclef to
// go and get them. the state is TripBook, this is the wiring. everything here runs on the client thread except the
// searches, which hand their answer back through `found` and get looked at on the next tick
public final class TripBehavior extends Behavior {

    // one low priority thread. a what-if is a nice to have, it must never queue behind or in front of the real search
    private static final ExecutorService WHAT_IF = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Soprano trip what-if");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // a gather that's still going after this long (or 3x its estimate, whichever is longer) isn't going to finish
    private static final int MIN_GATHER_TICKS = 1200;

    private final TripBook book = new TripBook();
    private final AtomicBoolean searching = new AtomicBoolean();
    private final AtomicReference<Found> found = new AtomicReference<>();

    // set by CustomGoalProcess on a failed search, dealt with on our own tick so the contexts get built after the
    // processes are done with theirs. stays set until a search actually took it, the executor may be busy
    private Goal stuck;
    // the first real path of the trip is out, time to see if a faster one exists
    private boolean pathOut;
    private long ticks;
    private long gatherDeadline;

    private record Found(long tripId, Trip.Ask kind, WhatIfSearch.Result whatIf, WhatIfSearch.Result real, boolean pretendPickaxe, int have) {
    }

    public TripBehavior(Baritone baritone) {
        super(baritone);
    }

    // goto, come, waypoint goto and click to go call this right before they set the goal. nothing else does: an
    // altoclef task, tunnel, surface, #path... are not trips and never ask
    public void userTrip(Goal goal) {
        endTrip();
        if (ctx.world() != null && goal != null) {
            book.start(goal, ctx.world().dimension());
        }
    }

    // stop, cancel, forcecancel, death, a dimension change, leaving the world, arriving, a new trip
    public void endTrip() {
        Trip ended = book.end();
        stuck = null;
        pathOut = false;
        if (ended != null && ended.gathering) {
            TripGatherer gatherer = AltoClefBridge.tripGatherer();
            if (gatherer != null) {
                gatherer.cancel(ended.id);
            }
        }
    }

    // CustomGoalProcess, right before it drops a goal whose search came back empty
    public void onCalcFailed(Goal goal) {
        if (book.forGoal(goal) != null) {
            stuck = goal;
        }
    }

    // CustomGoalProcess, we're there
    public void onArrived(Goal goal) {
        if (book.forGoal(goal) != null) {
            endTrip();
        }
    }

    public Trip current() {
        return book.current();
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (event == PathEvent.CALC_FINISHED_NOW_EXECUTING && book.current() != null) {
            pathOut = true;
        }
    }

    @Override
    public void onPlayerDeath() {
        endTrip();
    }

    @Override
    public void onTick(TickEvent event) {
        ticks++;
        if (event.getType() == TickEvent.Type.OUT) {
            if (book.current() != null) {
                endTrip();
            }
            return;
        }
        Trip trip = book.current();
        if (trip == null) {
            found.set(null);
            return;
        }
        if (ctx.player() == null || ctx.world() == null || !Objects.equals(ctx.world().dimension(), trip.dimension)) {
            endTrip();
            return;
        }
        tickGather(trip);
        if (!Baritone.settings().askToGatherForPath.value) {
            stuck = null;
            pathOut = false;
            return;
        }
        handleFound();
        tickStuck();
        tickFaster();
    }

    // #gather yes/no, typed or clicked. null id means whatever is being asked right now. returns what to tell the user
    public String answer(Long id, boolean yes) {
        Trip trip = book.current();
        if (trip == null || (id != null && id != trip.id)) {
            return "That question is from a trip that's over.";
        }
        if (trip.decision != Trip.Decision.PENDING) {
            return trip.asked == null ? "Nothing to answer right now." : "Already answered for this trip.";
        }
        if (yes && movedOn(trip)) {
            endTrip();
            return "You've moved on to something else, so I'm dropping that trip.";
        }
        Trip next = book.answer(trip.id, yes);
        if (next == null) {
            return "Already answered for this trip.";
        }
        if (yes) {
            startGather(next, next.plan);
            return null;
        }
        if (next.asked == Trip.Ask.STUCK) {
            book.tellNo(next.id);
            return "Ok, I won't collect anything this trip. Staying put.";
        }
        return "Ok, sticking to the normal route.";
    }

    // the user typed something else since we asked. a stuck question leaves nothing running, so any real process running
    // now is theirs. a faster question was asked while walking our goal, a different goal means they changed it.
    // no goal at all is the faster route's normal route failing while the question was up, that's still our trip.
    // "in control" used to be the test, but backfill or the inventory pauser holding the wheel for a tick is not the
    // user moving on, and they hold it a lot
    private boolean movedOn(Trip trip) {
        if (AltoClefBridge.userTaskActive()) {
            return true;
        }
        Goal goal = baritone.getCustomGoalProcess().getGoal();
        if (goal != null) {
            return goal != trip.goal;
        }
        return baritone.getPathingControlManager().nonTemporaryActive();
    }

    private void tickStuck() {
        Goal goal = stuck;
        // a faster answer still waiting in `found` gets read first, next tick. launching now would let the two questions
        // cross and the stuck one's message land while the faster one is up
        if (goal == null || found.get() != null) {
            return;
        }
        Trip trip = book.forGoal(goal);
        if (trip == null || trip.gathering) {
            stuck = null;
            return;
        }
        switch (trip.decision) {
            case NO -> {
                stuck = null;
                if (book.tellNo(trip.id) != null) {
                    TripPrompt.say("I can't get there without blocks, and you said no to collecting them. Stopping here.");
                }
            }
            // the question is already up, the bot just stands there like it always did. stuck stays set: if the faster
            // question is the one that's up and gets a no, the next tick says so like any other no
            case PENDING -> {
            }
            case UNASKED, YES -> {
                if (launch(trip, Trip.Ask.STUCK)) {
                    stuck = null;
                }
            }
        }
    }

    private void tickFaster() {
        if (!pathOut) {
            return;
        }
        Trip trip = book.current();
        if (trip == null || trip.decision != Trip.Decision.UNASKED || trip.fasterChecked || baritone.getCustomGoalProcess().getGoal() != trip.goal) {
            pathOut = false;
            return;
        }
        if (launch(trip, Trip.Ask.FASTER)) {
            pathOut = false;
            book.checkFaster(trip.id);
        }
    }

    // true when it's dealt with: launched, or nothing to search for. false only when the executor is busy, try next tick
    private boolean launch(Trip trip, Trip.Ask kind) {
        Settings s = Baritone.settings();
        if (!s.allowPlace.value) {
            return true; // no placing at all, no bag is going to change that
        }
        boolean hasBlocks = baritone.getInventoryBehavior().hasGenericThrowaway();
        boolean hasPickaxe = WhatIfContext.hasStonePickaxe(ctx.player());
        // a stuck trip asks about blocks, that's the question the user gets. the pickaxe only comes into the faster one
        boolean pretendPickaxe = kind == Trip.Ask.FASTER && !hasPickaxe && s.allowBreak.value;
        if (kind == Trip.Ask.FASTER && hasBlocks && !pretendPickaxe) {
            return true; // the real search already had everything the what-if would pretend
        }
        if (!searching.compareAndSet(false, true)) {
            return false;
        }
        try {
            WhatIfContext whatIf = WhatIfContext.create(baritone, true, pretendPickaxe);
            CalculationContext real = kind == Trip.Ask.FASTER ? WhatIfContext.real(baritone) : null;
            BetterBlockPos start = baritone.getPathingBehavior().pathStart();
            int have = baritone.getInventoryBehavior().spareThrowawayCount();
            long budget = s.failureTimeoutMS.value;
            long id = trip.id;
            Goal goal = trip.goal;
            WHAT_IF.execute(() -> {
                try {
                    // same start, same budget, back to back so they don't fight each other for a core
                    WhatIfSearch.Result realResult = real == null ? null : WhatIfSearch.run(real, start, goal, budget);
                    WhatIfSearch.Result whatIfResult = WhatIfSearch.run(whatIf, start, goal, budget);
                    found.set(new Found(id, kind, whatIfResult, realResult, pretendPickaxe, have));
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    searching.set(false);
                }
            });
        } catch (Throwable t) {
            searching.set(false);
            throw t;
        }
        return true;
    }

    private void handleFound() {
        Found f = found.getAndSet(null);
        if (f == null) {
            return;
        }
        Trip trip = book.current();
        if (trip == null || trip.id != f.tripId || trip.gathering) {
            return;
        }
        if (f.kind == Trip.Ask.STUCK) {
            onStuckResult(trip, f);
        } else {
            onFasterResult(trip, f);
        }
    }

    private void onStuckResult(Trip trip, Found f) {
        WhatIfSearch.Result w = f.whatIf;
        boolean yes = trip.decision == Trip.Decision.YES;
        if (!w.found() || !TripMath.madeProgress(w.reachesGoal(), w.startHeuristic(), w.endHeuristic(), Baritone.settings().costHeuristic.value)) {
            giveUp(trip, yes, "I can't find a way there, even with blocks.");
            return;
        }
        int max = Baritone.settings().gatherMaxBlocks.value;
        if (w.placements() > max) {
            giveUp(trip, yes, "The only way there I can find places " + w.placements() + " blocks, more than gatherMaxBlocks (" + max + "). Not collecting that many.");
            return;
        }
        GatherPlan plan = new GatherPlan(Trip.Ask.STUCK, w.placements(), TripMath.deficit(w.placements(), f.have), false);
        if (plan.isEmpty()) {
            // the what-if found a way with the bag we have. whatever made the real search fail, more blocks won't fix it
            giveUp(trip, yes, yes ? "I collected blocks but still can't get there." : "I can't get there, and more blocks wouldn't help.");
            return;
        }
        List<Item> items = GatherSources.items(Baritone.settings().acceptableThrowawayItems.value);
        if (!GatherFeasibility.plausible(ctx, plan, items)) {
            giveUp(trip, yes, "I can't get there without blocks, and there's nothing to build with nearby.");
            return;
        }
        if (yes) {
            startGather(trip, plan);
        } else if (trip.decision == Trip.Decision.UNASKED) {
            Trip asked = book.ask(trip.id, Trip.Ask.STUCK, plan);
            if (asked != null) {
                TripPrompt.ask(asked.id, plan, 0);
            }
        }
    }

    private void onFasterResult(Trip trip, Found f) {
        if (trip.decision != Trip.Decision.UNASKED) {
            return;
        }
        WhatIfSearch.Result w = f.whatIf;
        WhatIfSearch.Result r = f.real;
        // no real path is the stuck question's business, it'll come round on the failed search
        if (r == null || !r.found() || !w.found() || w.placements() > Baritone.settings().gatherMaxBlocks.value) {
            return;
        }
        Settings s = Baritone.settings();
        GatherPlan plan = new GatherPlan(Trip.Ask.FASTER, w.placements(), TripMath.deficit(w.placements(), f.have), f.pretendPickaxe && w.breaksStone());
        if (plan.isEmpty()) {
            return;
        }
        double gather = TripMath.gatherTicks(plan.blocks(), plan.pickaxe(), s.gatherTicksPerBlock.value, s.gatherPickaxeTicks.value);
        if (!TripMath.worthAsking(r.estTicks(), w.estTicks(), gather, s.fasterPathMinPercent.value, s.fasterPathMinSeconds.value)) {
            return;
        }
        if (!GatherFeasibility.plausible(ctx, plan, GatherSources.items(s.acceptableThrowawayItems.value))) {
            return;
        }
        Trip asked = book.ask(trip.id, Trip.Ask.FASTER, plan);
        if (asked != null) {
            TripPrompt.ask(asked.id, plan, r.estTicks() - w.estTicks() - gather);
        }
    }

    // a yes that can't be kept turns into a no for the rest of the trip. so does a question we never got to ask: the
    // trip is settled, a faster check or a late #gather yes can't dig it back up. one message, said here
    private void giveUp(Trip trip, boolean yes, String message) {
        if (!yes && trip.decision == Trip.Decision.PENDING) {
            TripPrompt.say(message);
            return;
        }
        book.refuse(trip.id);
        if (book.tellNo(trip.id) != null) {
            TripPrompt.say(message);
        }
    }

    private void startGather(Trip trip, GatherPlan plan) {
        Settings s = Baritone.settings();
        Trip next = book.startGather(trip.id, plan, 1 + Math.max(0, s.gatherMaxRetries.value));
        if (next == null) {
            if (trip.decision == Trip.Decision.YES && !trip.gathering) {
                giveUp(trip, true, "I've already gone collecting " + trip.gathers + " times this trip, that's enough. Stopping here.");
            }
            return;
        }
        if (plan.blocks() > 0 && ctx.player().getInventory().getFreeSlot() < 0) {
            failGather(next, "My inventory is full, I can't collect blocks.");
            return;
        }
        TripGatherer gatherer = AltoClefBridge.tripGatherer();
        List<Item> items = GatherSources.items(s.acceptableThrowawayItems.value);
        if (gatherer == null || !gatherer.start(next.id, items, plan.blocks(), plan.pickaxe())) {
            failGather(next, "I can't go collecting right now (altoclef is off or busy).");
            return;
        }
        if (baritone.getCustomGoalProcess().getGoal() == next.goal) {
            // a faster route yes, still walking the slow one. the gather's first child cancels it too, but not before the
            // trip goal gets a tick or two of walking the wrong way. just our goal, same as CustomBaritoneGoalTask's unstuck,
            // cancelEverything would also take out a #mine or #follow that has nothing to do with us
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
        double estimate = TripMath.gatherTicks(plan.blocks(), plan.pickaxe(), s.gatherTicksPerBlock.value, s.gatherPickaxeTicks.value);
        gatherDeadline = ticks + (long) Math.max(MIN_GATHER_TICKS, 3 * estimate);
        TripPrompt.say("Collecting " + plan.describe() + ", then I'll carry on.");
    }

    private void tickGather(Trip trip) {
        if (!trip.gathering) {
            return;
        }
        TripGatherer gatherer = AltoClefBridge.tripGatherer();
        TripGatherer.Status status = gatherer == null ? TripGatherer.Status.NONE : gatherer.status(trip.id);
        switch (status) {
            case DONE -> {
                if (book.gatherDone(trip.id) != null) {
                    TripPrompt.say("Got them, carrying on.");
                    // straight to the custom goal process, not userTrip: this is the same trip carrying on
                    baritone.getCustomGoalProcess().setGoalAndPath(trip.goal);
                }
            }
            case FAILED, NONE -> failGather(trip, "Collecting got stopped or didn't work out.");
            case RUNNING -> {
                if (ticks > gatherDeadline) {
                    gatherer.cancel(trip.id);
                    failGather(trip, "Collecting is taking too long, giving up on it.");
                }
            }
        }
    }

    // a faster route yes that never got going is still walking the normal route, so don't claim to stop. once the
    // gather has taken over we don't walk back on our own: a FAILED can just as well be the user's ctrl+k or a new task
    private void failGather(Trip trip, String message) {
        book.refuse(trip.id);
        book.tellNo(trip.id);
        boolean stillWalking = baritone.getCustomGoalProcess().getGoal() == trip.goal;
        TripPrompt.say(message + (stillWalking ? " Sticking to the normal route." : " Stopping here."));
    }
}
