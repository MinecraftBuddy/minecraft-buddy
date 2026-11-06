/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.FailedMoves;
import baritone.pathing.movement.SwimStalls;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.path.SprintTelemetry;
import baritone.pathing.sim.AscendHop;
import baritone.utils.ExperimentalMovement;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

    private PathExecutor current;
    private PathExecutor next;
    private Movement lastTicked;

    private Goal goal;
    private CalculationContext context;
    // the context came from a PathingCommandContext, so it carries settings of its own and we don't rebuild it
    private boolean customContext;

    /*eta*/
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    // a handover that landed mid-step, waiting for the executor to reach a safe tick (see DeferredCancel)
    private final DeferredCancel deferredCancel = new DeferredCancel();
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;
    // calcFailedLastTick is one tick wide and which thread sees it first is luck. this just goes up (written under pathPlanLock)
    private volatile int calcFailures;
    // the ones of those that ran out of nodes to look at (a timed out search is a failure too, it just proves nothing)
    private volatile int calcExhausted;

    private volatile AbstractNodeCostSearch inProgress;
    private final Object pathCalcLock = new Object();

    private final Object pathPlanLock = new Object();

    private boolean lastAutoJump;

    private BetterBlockPos expectedSegmentStart;

    private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

    // the path a movement task's handover just cancelled, in case the next task asks for the same goal. any other cancel
    // throws it away
    private final PathKeep<PathExecutor> keep = new PathKeep<>();
    // our own tick count for the park's age, ticksElapsedSoFar gets reset by the eta
    private long tickCount;

    private final SwimStalls swimStalls = new SwimStalls();
    private final FailedMoves failedMoves = new FailedMoves();

    // the cancel-and-ask-again-every-tick guard (SearchHold). everything that touches it holds pathCalcLock
    private final SearchHold hold = new SearchHold();

    // what makes two searches the same one for SearchHold
    private record SearchKey(BetterBlockPos start, Goal goal) {
    }

    private final SprintTelemetry sprintTelemetry;

    public PathingBehavior(Baritone baritone) {
        super(baritone);
        this.sprintTelemetry = new SprintTelemetry(baritone);
    }

    // the movements and the executor report what they decided to it, it does nothing with the setting off
    public SprintTelemetry sprintTelemetry() {
        return sprintTelemetry;
    }

    // the brake plan a hop up a step is following, shared by the ascend and the traverse that skips into it (see AscendHop.Brakes)
    private final AscendHop.Brakes ascendBrakes = new AscendHop.Brakes();

    public AscendHop.Brakes ascendBrakes() {
        return ascendBrakes;
    }

    public SwimStalls swimStalls() {
        return swimStalls;
    }

    public FailedMoves failedMoves() {
        return failedMoves;
    }

    private void queuePathEvent(PathEvent event) {
        toDispatch.add(event);
    }

    private void dispatchEvents() {
        ArrayList<PathEvent> curr = new ArrayList<>();
        toDispatch.drainTo(curr);
        calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : curr) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        tickCount++;
        dispatchEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            // left the world: nothing is coming to ask for that search again, and no tickPath is going to settle it
            cancelSearchNow();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
        boolean telemetry = ExperimentalMovement.sprintTelemetry();
        if (telemetry) {
            sprintTelemetry.begin(current != null);
        } else {
            sprintTelemetry.off();
        }
        baritone.getPathingControlManager().preTick();
        PathExecutor ticked = current;
        tickPath();
        if (telemetry) {
            // an executor that finished or cancelled this tick is gone from `current`, its last word on sprint still counts
            sprintTelemetry.end(current != null ? current : ticked, pausedThisTick);
        }
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (baritone.getInputOverrideHandler().settleActive()) {
            // the gated executor's last answer is stale, and a sprint on a ledge is the opposite of settling
            event.setState(false);
        } else if (isPathing()) {
            event.setState(current.isSprinting());
        }
    }

    private void tickPath() {
        // before the pause return below, or a stop during a pause would leave the held search running until the pause ends
        settleHeldCancel();
        // before the pause too, a pause is a safe tick as well and the old path has nothing left to say
        settleDeferredCancel();
        pausedThisTick = false;
        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return;
        }
        unpausedLastTick = true;
        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // we are calculating
                    // are we calculating the right thing though? 🤔
                    BetterBlockPos calcFrom = inProgress.getStart();
                    Optional<IPath> currentBest = inProgress.bestPathSoFar();
                    if ((current == null || !current.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                            && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                            && (!currentBest.isPresent() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
                    ) {
                        // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
                        inProgress.cancel(); // cancellation doesn't dispatch any events
                        hold.dropped(); // we moved, nobody gets to hold on to this one
                    }
                }
            }
            if (current == null) {
                return;
            }
            if (baritone.getInputOverrideHandler().settleActive()) {
                // the edge settle has the wheel (EdgeSettle). no movement runs, old or new, the cancels above still land.
                // not a pause: pausedThisTick would make isPathing false and the processes would read a stall
                baritone.getInputOverrideHandler().clearAllKeys();
                safeToCancel = true;
                return;
            }
            safeToCancel = current.onTick();
            if (current.failed() || current.finished()) {
                boolean hanging = current.bailedOverDrop();
                current = null;
                // the segment it was waiting on is gone, and the next path to show up is not the one to cancel
                deferredCancel.clear();
                if (goal == null || goal.isInGoal(ctx.playerFeet())) {
                    logDebug("All done. At " + goal);
                    queuePathEvent(PathEvent.AT_GOAL);
                    next = null;
                    if (Baritone.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                        }
                    }
                    return;
                }
                if (next != null && hanging) {
                    // the bridge just gave up with us hanging off the edge on sneak. a next path taking over this same tick
                    // presses its keys, EdgeSettle reads that as steering and never walks us back. a replan instead: it waits
                    // while the settle has the wheel (settleActive above)
                    logDebug("Discarding next path, the bridge gave up over a drop");
                    queuePathEvent(PathEvent.DISCARD_NEXT);
                    next = null;
                }
                if (next != null && !next.getPath().positions().contains(ctx.playerFeet()) && !next.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
                    // if the current path failed, we may not actually be on the next one, so make sure
                    logDebug("Discarding next path as it does not contain current position");
                    // for example if we had a nicely planned ahead path that starts where current ends
                    // that's all fine and good
                    // but if we fail in the middle of current
                    // we're nowhere close to our planned ahead path
                    // so need to discard it sadly.
                    queuePathEvent(PathEvent.DISCARD_NEXT);
                    next = null;
                }
                if (next != null) {
                    logDebug("Continuing on to planned next path");
                    queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                    current = next;
                    next = null;
                    current.onTick(); // don't waste a tick doing nothing, get started right away
                    return;
                }
                // at this point, current just ended, but we aren't in the goal and have no plan for the future
                synchronized (pathCalcLock) {
                    if (inProgress != null) {
                        // we are standing here now, so the plan ahead stops getting its long timeouts
                        inProgress.somebodyIsWaiting();
                        queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                        return;
                    }
                    // we aren't calculating
                    queuePathEvent(PathEvent.CALC_STARTED);
                    if (hanging && !customContext) {
                        // this tick's context snapshotted the failed moves before the bail recorded the bridge, and the
                        // replan would happily pick the same bridge again. a fresh one has it priced in
                        context = new CalculationContext(baritone, true);
                    }
                    findPathInNewThread(expectedSegmentStart, true, context);
                }
                return;
            }
            // at this point, we know current is in progress
            if (safeToCancel && next != null && next.snipsnapifpossible()) {
                // a movement just ended; jump directly onto the next path
                logDebug("Splicing into planned next path early...");
                queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
                current = next;
                next = null;
                current.onTick();
                return;
            }
            if (Baritone.settings().splicePath.value) {
                current = current.trySplice(next);
            }
            if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
                next = null;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // if we aren't calculating right now
                    return;
                }
                if (next != null) {
                    // and we have no plan for what to do next
                    return;
                }
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    // and this path doesn't get us all the way there
                    return;
                }
                if (ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value) {
                    // and this path has 7.5 seconds or less left
                    // don't include the current movement so a very long last movement (e.g. descend) doesn't trip it up
                    // if we actually included current, it wouldn't start planning ahead until the last movement was done, if the last movement took more than 7.5 seconds on its own
                    logDebug("Path almost over. Planning ahead...");
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                    findPathInNewThread(current.getPath().getDest(), false, context);
                }
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (current != null) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = ctx.minecraft().options.autoJump().get();
                    ctx.minecraft().options.autoJump().set(false);
                    break;
                case POST:
                    ctx.minecraft().options.autoJump().set(lastAutoJump);
                    break;
                default:
                    break;
            }
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public boolean secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            context = ((PathingCommandContext) command).desiredCalcContext;
            customContext = true;
        } else {
            context = new CalculationContext(baritone, true);
            customContext = false;
        }
        if (goal == null) {
            return false;
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            return false;
        }
        synchronized (pathPlanLock) {
            if (current != null) {
                if (goal.equals(current.getPath().getGoal())) {
                    // the next task walks to the very same place, the path we were waiting to drop is the right one
                    deferredCancel.clear();
                }
                return false;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    if (!hold.isPending()) {
                        return false;
                    }
                    // a cancel was held back (SearchHold). asked for the very same thing again, that search just carries on.
                    // an ask with its own calc context wants its own search, that is a change
                    if (!(command instanceof PathingCommandContext) && hold.resume(new SearchKey(expectedSegmentStart, goal))) {
                        return true;
                    }
                    inProgress.cancel();
                    inProgress = null;
                    hold.dropped();
                }
                if (unpark()) {
                    return true;
                }
                queuePathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(expectedSegmentStart, true, context);
                return true;
            }
        }
    }

    // the parked path back as current, when PathKeep says it is still ours. the park is used up either way. the keys and the
    // block breaking that the cancel let go of come back on the executor's next tick, same as coming back from a pause
    private boolean unpark() {
        BetterBlockPos feet = ctx.playerFeet();
        PathExecutor back = keep.take(Baritone.settings().keepPathOnSameGoal.value, tickCount, ctx.world(), ctx.player(),
                p -> !p.failed() && !p.finished() && goal.equals(p.getPath().getGoal()), p -> p.getPath().positions().contains(feet));
        if (back == null) {
            return false;
        }
        logDebug("Same goal as the path a handover just cancelled, carrying on with it");
        current = back;
        next = null;
        return true;
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrent() {
        return current;
    }

    // the falltrace takes this once a client tick. a movement that failed and got cancelled, or finished and handed the
    // same tick to the next one, is gone from the path by the time anyone looks, so the executor drops it here instead
    public void noteTicked(Movement movement) {
        lastTicked = movement;
    }

    // null when no movement ran since the last take
    public Movement takeTicked() {
        Movement movement = lastTicked;
        lastTicked = null;
        return movement;
    }

    @Override
    public PathExecutor getNext() {
        return next;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        // a search whose cancel is being held (SearchHold) is cancelled as far as everybody else can tell
        synchronized (pathCalcLock) {
            return hold.isPending() ? Optional.empty() : Optional.ofNullable(inProgress);
        }
    }

    // cancel with no holding back, for when nobody can be coming to ask for it again
    private void cancelSearchNow() {
        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    inProgress.cancel();
                }
                hold.dropped();
            }
        }
    }

    // a cancel that got held back for a tick and nobody asked for the same thing again goes through now
    private void settleHeldCancel() {
        synchronized (pathCalcLock) {
            if (inProgress != null && hold.expired(tickCount)) {
                inProgress.cancel();
                hold.dropped();
            }
        }
    }

    // a handover that had to wait: every tick until the executor is on a safe one, nothing planned for the old goal gets
    // to splice in, then the segment goes the way forceCancel's does (without cancelEverything, the new task owns the
    // processes by now)
    private void settleDeferredCancel() {
        if (!deferredCancel.pending()) {
            return;
        }
        synchronized (pathPlanLock) {
            if (current == null) {
                deferredCancel.clear();
                return;
            }
            next = null;
            if (deferredCancel.fire(safeToCancel)) {
                dropSegmentAndSearch();
            }
        }
    }

    private void dropSegmentAndSearch() {
        secretInternalSegmentCancel();
        synchronized (pathCalcLock) {
            // a search that was held back stays in the slot, the next ask or the next tickPath settles it
            if (!hold.isPending()) {
                inProgress = null;
            }
        }
    }

    // the one place the search in the slot gets cancelled from the outside. caller holds pathPlanLock. a cancel that
    // SearchHold holds back leaves it running (and in the slot) for the next ask to pick up
    private void cancelSearch() {
        synchronized (pathCalcLock) {
            if (inProgress != null && hold.cancel(tickCount) == SearchHold.Verdict.CANCEL) {
                inProgress.cancel();
            }
        }
    }

    public boolean isSafeToCancel() {
        if (current == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    // asked from inside preTick (a process's onTick) this is still last tick's answer, tickPath resets it after. so it says
    // whether the pause really landed last tick, which a process that wants to click needs: the tick the pause lands is
    // the tick clearAllKeys eats every key we just pressed
    public boolean pausedLastTick() {
        return pausedThisTick;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
        pausedBy = PAUSE_CALLER.walk(frames -> frames.skip(1).findFirst()
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName()).orElse(null));
    }

    // who asked for a pause last, for the stand line. paused=true alone never said whose fault the standing around was
    private static final StackWalker PAUSE_CALLER = StackWalker.getInstance();
    private String pausedBy;

    public String pausedBy() {
        return pausedBy;
    }

    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelEverything() {
        // the user's cancel (and everything else that comes through here) is never a handover
        dropPark();
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
        return doIt;
    }

    // the processes go now, and a segment that is mid step goes on the first safe tick (see DeferredCancel). unlike handoverCancel
    // nothing is parked for a later task. NOT exposed on public api
    public void cancelWhenSafe() {
        cancelEverything();
        synchronized (pathPlanLock) {
            if (current != null && !isSafeToCancel()) {
                deferredCancel.request();
                next = null;
            }
        }
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return calcFailedLastTick;
    }

    // how many searches that started from a standstill came back with nothing. NOT exposed on public api
    public int calcFailures() {
        return calcFailures;
    }

    // how many of calcFailures searched everything there was to search before giving up. read calcFailures first, then this.
    // NOT exposed on public api
    public int calcExhausted() {
        return calcExhausted;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanLock) {
            keep.drop();
            cancelSearch(); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            current = null;
            next = null;
        }
        cancelRequested = true;
        // do everything BUT clear keys
    }

    // just cancel the current path
    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathPlanLock) {
            keep.drop();
            deferredCancel.clear();
            cancelSearch();
            if (current != null) {
                current = null;
                next = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    @Override
    public void forceCancel() { // exposed on public api because :sob:
        cancelEverything();
        dropSegmentAndSearch();
    }

    // forceCancel for an alto movement task starting or stopping, the one cancel that parks the path (PathKeep). a stop and
    // the next task's start are two cancels in the same tick, so the second one (with nothing left to park) keeps the
    // first one's park. NOT exposed on public api
    public void handoverCancel() {
        if (current != null && !isSafeToCancel()) {
            // mid-step, letting go of every key here is how you walk off a ledge. the processes go now, the path goes on
            // the first safe tick. a plain cancelEverything is not enough: current would drive the whole old path
            cancelEverything();
            synchronized (pathPlanLock) {
                deferredCancel.request();
                next = null;
            }
            return;
        }
        PathExecutor running = current;
        PathKeep.Park<PathExecutor> before = keep.peek();
        forceCancel();
        if (!Baritone.settings().keepPathOnSameGoal.value || ctx.player() == null) {
            return;
        }
        synchronized (pathPlanLock) {
            keep.handover(before, running, tickCount, ctx.world(), ctx.player());
        }
    }

    // a stop that is nobody's handover (the user stopping the task) says so, so the stop's own handoverCancel can't leave a
    // path lying around for the next half second. NOT exposed on public api
    public void dropPark() {
        synchronized (pathPlanLock) {
            keep.drop();
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }
        double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
            return Optional.empty();
        }
        double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
        return Optional.of(eta);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            // the lip search runs whether or not onGround says so. onGround blinks on a lip, and every blink used to flip the
            // start between the lip and the block under us, a block lower, which plans a whole different path from a spot
            // we aren't standing at
            // but only at rest height. a bot hanging off a lip sits exactly on the block line, one walking off a ledge or at
            // the top of a jump is a bit off it and still right next to the block it left, which isn't a start. and not going
            // up: the third tick of a jump is +1.0013, right on the next line, the famous number that gets you onto a block.
            // and not falling fast either, a fall past a ledge crosses block lines too. a perch has one tick of gravity, -0.08
            BetterBlockPos support = ctx.player().onGround() || perched(feet) ? lipSupport(feet) : null;
            if (support != null) {
                return support;
            }
            // nothing to be hanging off of, so we're in the middle of a jump
            if (midJump(ctx.player().onGround(), ctx.player().onClimbable(), MovementHelper.canWalkOn(ctx, feet.below().below()))) {
                //logDebug("Faking path start assuming player is midair and falling");
                return feet.below();
            }
        }
        return feet;
    }

    // a ladder or a vine isn't a jump, same exclusion as perched. sneaking on one right above something we can't walk on reads
    // as off the ground forever, and the guess would start every path inside that block, where the first move can never finish
    static boolean midJump(boolean onGround, boolean onClimbable, boolean solidTwoBelow) {
        return !onGround && !onClimbable && solidTwoBelow;
    }

    // off the ground but sitting still on a block line, which is what hanging off a lip looks like. so does hanging on a
    // ladder or a vine with air under it, hovering in creative flight and bobbing in water, and for those the lip search
    // finds some open cell next to us and the start flips between it and our feet every tick
    private boolean perched(BetterBlockPos feet) {
        double vy = ctx.player().getDeltaMovement().y;
        if (Math.abs(ctx.player().position().y - feet.y) >= 0.01 || vy > 0 || vy <= -0.1) {
            return false;
        }
        return !ctx.player().onClimbable() && !ctx.player().getAbilities().flying && !ctx.player().isInWater();
    }

    // the block we're sneaking (or just standing) off the edge of, if there's a plausible one
    private BetterBlockPos lipSupport(BetterBlockPos feet) {
        double playerX = ctx.player().position().x;
        double playerZ = ctx.player().position().z;
        ArrayList<BetterBlockPos> closest = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
            }
        }
        closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
        for (int i = 0; i < 4; i++) {
            BetterBlockPos possibleSupport = closest.get(i);
            double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
            double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
            if (xDist > 0.8 && zDist > 0.8) {
                // can't possibly be sneaking off of this one, we're too far away
                continue;
            }
            if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                // this is plausible
                //logDebug("Faking path start assuming player is standing off the edge of a block");
                return possibleSupport;
            }
        }
        return null;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
        // this must be called with synchronization on pathCalcLock!
        // actually, we can check this, muahaha
        if (!Thread.holdsLock(pathCalcLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
            // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
        }
        if (inProgress != null) {
            throw new IllegalStateException("Already doing it"); // should have been checked by caller
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
        Goal goal = this.goal;
        if (goal == null) {
            logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
            return;
        }
        long primaryTimeout;
        long failureTimeout;
        if (current == null) {
            primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
            failureTimeout = Baritone.settings().failureTimeoutMS.value;
        } else {
            primaryTimeout = Baritone.settings().planAheadPrimaryTimeoutMS.value;
            failureTimeout = Baritone.settings().planAheadFailureTimeoutMS.value;
        }
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, current == null ? null : current.getPath(), context);
        if (!Objects.equals(pathfinder.getGoal(), goal)) { // will return the exact same object if simplification didn't happen
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        inProgress = pathfinder;
        hold.started(new SearchKey(new BetterBlockPos(start), goal), tickCount);
        Baritone.getExecutor().execute(() -> {
            try {
                runSearch(pathfinder, start, goal, talkAboutIt, primaryTimeout, failureTimeout);
            } finally {
                // only clear our own slot: a forceCancel plus a fresh search can have replaced us while we were still running
                // (and the finally means a throwing calc can't leave inProgress stuck either)
                synchronized (pathCalcLock) {
                    if (inProgress == pathfinder) {
                        inProgress = null;
                        hold.ended();
                    }
                }
            }
        });
    }

    private void runSearch(AbstractNodeCostSearch pathfinder, BlockPos start, Goal goal, boolean talkAboutIt, long primaryTimeout, long failureTimeout) {
        if (talkAboutIt) {
            logDebug("Starting to search for path from " + start + " to " + goal);
        }

        PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
        synchronized (pathPlanLock) {
            if (inProgress != pathfinder) {
                // forceCancel nulled us (and maybe a newer search took the slot), so this result is stale
                return;
            }
            synchronized (pathCalcLock) {
                if (hold.isPending() && calcResult.getPath().isPresent()) {
                    // finished inside the tick its cancel was held back for, and nobody has asked for it again yet. a path
                    // nobody wants is not one to walk. (an empty result is still true, so that one gets reported below)
                    inProgress = null;
                    hold.ended();
                    return;
                }
            }
            Optional<PathExecutor> executor = calcResult.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
            if (current == null) {
                if (executor.isPresent()) {
                    if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                        queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                        current = executor.get();
                        resetEstimatedTicksToGoal(start);
                    } else {
                        logDebug("Warning: discarding orphan path segment with incorrect start");
                    }
                } else {
                    if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
                        // don't dispatch CALC_FAILED on cancellation
                        // exhausted first: a reader that sees the failure count go up (it reads that one first) has to see this too
                        if (pathfinder.exhausted()) {
                            calcExhausted++;
                        }
                        calcFailures++;
                        queuePathEvent(PathEvent.CALC_FAILED);
                    }
                }
            } else {
                if (next == null) {
                    if (executor.isPresent()) {
                        if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                            queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                            // a plan for the goal we are about to drop is not worth splicing into
                            if (!deferredCancel.pending()) {
                                next = executor.get();
                            }
                        } else {
                            logDebug("Warning: discarding orphan next segment with incorrect start");
                        }
                    } else {
                        queuePathEvent(PathEvent.NEXT_CALC_FAILED);
                    }
                } else {
                    //throw new IllegalStateException("I have no idea what to do with this path");
                    // no point in throwing an exception here, and it gets it stuck with inProgress being not null
                    logDirect("Warning: PathingBehavior illegal state! Discarding invalid path!");
                }
            }
            if (talkAboutIt && current != null && current.getPath() != null) {
                if (goal.isInGoal(current.getPath().getDest())) {
                    logDebug("Finished finding a path from " + start + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                } else {
                    logDebug("Found path segment from " + start + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                }
            }
            // clear in here too so nobody sees the new path while the search still looks like it's running
            synchronized (pathCalcLock) {
                if (inProgress == pathfinder) {
                    inProgress = null;
                    hold.ended();
                }
            }
        }
    }

    private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        BetterBlockPos feet = ctx.playerFeet();
        var realStart = new BetterBlockPos(start);
        var sub = feet.subtract(realStart);
        if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
            realStart = feet;
        }
        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);

    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }
}
