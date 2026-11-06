package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.DropExpect;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.NoReturn;
import adris.altoclef.util.helpers.PursuitProgress;
import adris.altoclef.util.helpers.ScanWait;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.HashMap;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Use this whenever you want to travel to a target position that may change.
 * <p>
 * https://www.notion.so/Closest-threshold-ing-system-utility-c3816b880402494ba9209c9f9b62b8bf
 */
public abstract class AbstractDoToClosestObjectTask<T> extends Task {

    private final HashMap<T, CachedHeuristic> _heuristicMap = new HashMap<>();
    private T _currentlyPursuing = null;
    private boolean _wasWandering;
    private final ScanWait _scanWait = new ScanWait();
    // is the current pursuit getting anywhere, see PursuitProgress
    private final PursuitProgress _progress = new PursuitProgress();
    private final NoReturn _noReturn = new NoReturn();
    // the block the controller was last seen breaking, so a break on the way to the pursuit counts as progress
    private BlockPos _lastBreaking;
    private Task _goalTask = null;
    // the break we just made, see DropExpect. subclasses arm it from onPursuitGone and say what they see in dropSeen
    private final DropExpect _expect = new DropExpect();
    private Vec3 _expectSpot;

    protected abstract Vec3 getPos(AltoClef mod, T obj);

    protected abstract Optional<T> getClosestTo(AltoClef mod, Vec3 pos);

    protected abstract Vec3 getOriginPos(AltoClef mod);

    protected abstract Task getGoalTask(T obj);

    protected abstract boolean isValid(AltoClef mod, T obj);

    // virtual. true to take the candidate over the current pursuit even though it is not much closer (the thing we are
    // halfway through breaking, say)
    protected boolean mustSwitchTo(AltoClef mod, T current, T candidate) {
        return false;
    }

    // what a subclass says about trading the current pursuit for a new pick
    protected enum SwitchCall { SWITCH, STAY, NO_OPINION }

    // virtual. a subclass with its own idea of "better" answers here, ahead of the remembered heuristic retry and the
    // twice-as-close rule. NO_OPINION leaves it to those. the mid-break guard and the stall repick still come first
    protected SwitchCall switchCall(AltoClef mod, T current, T candidate) {
        return SwitchCall.NO_OPINION;
    }

    // virtual. false keeps a pursuit that got nowhere for 7 s anyway (see PursuitProgress)
    protected boolean repicksWhenStalled() {
        return true;
    }

    // virtual. the thing we were after stopped being valid (broken, picked up, dead) and we are about to pick another
    protected void onPursuitGone(AltoClef mod, T gone) {
    }

    // virtual. whether the drop we are waiting on is in sight yet, around the spot passed to expectDrop
    protected boolean dropSeen(AltoClef mod, Vec3 spot) {
        return false;
    }

    // stand still for the drop that is about to exist at spot, before the next thing gets picked
    protected final void expectDrop(boolean kill, Vec3 spot) {
        _expectSpot = spot;
        if (kill) {
            _expect.expectKill(WorldHelper.getTicks());
        } else {
            _expect.expectBreak(WorldHelper.getTicks());
        }
    }

    // true once the wait ended because a drop showed up that needs fetching (not because it timed out)
    protected final boolean dropReady() {
        return _expect.takeNow();
    }

    protected final void forgetDropExpect() {
        _expect.clear();
    }

    // Virtual
    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    // virtual. true while the thing we search through hasn't had its first look yet (a block tracker scan that's still
    // on its way), so "nothing found" means "haven't looked" and wandering off would be walking away from the answer
    protected boolean stillLooking(AltoClef mod) {
        return false;
    }

    public void resetSearch() {
        _scanWait.clear();
        _progress.clear();
        _lastBreaking = null;
        _currentlyPursuing = null;
        _noReturn.clear();
        _heuristicMap.clear();
        _goalTask = null;
        _expect.clear();
    }

    // what we are chasing right now, null for nothing
    public T pursuing() {
        return _currentlyPursuing;
    }

    public boolean wasWandering() {
        return _wasWandering;
    }

    private double getCurrentCalculatedHeuristic(AltoClef mod) {
        Optional<Double> ticksRemainingOp = mod.getClientBaritone().getPathingBehavior().ticksRemainingInSegment();
        return ticksRemainingOp.orElse(Double.POSITIVE_INFINITY);
    }

    // the controller is breaking the very block we are pursuing right now
    private boolean breakingPursuit(AltoClef mod) {
        return _currentlyPursuing instanceof BlockPos at && mod.getControllerExtras().isBreakingBlock()
                && at.equals(mod.getControllerExtras().getBreakingBlockPos());
    }

    // a block we were breaking is gone now (or we are on the pursuit itself, which is the opposite of getting nowhere)
    private boolean brokeSomething(AltoClef mod) {
        if (breakingPursuit(mod)) {
            return true;
        }
        boolean broke = _lastBreaking != null && mod.getWorld().getBlockState(_lastBreaking).isAir();
        if (broke) {
            _lastBreaking = null;
        }
        if (mod.getControllerExtras().isBreakingBlock()) {
            _lastBreaking = mod.getControllerExtras().getBreakingBlockPos();
        }
        return broke;
    }

    private boolean isMovingToClosestPos(AltoClef mod) {
        return _goalTask != null;// && _goalTask.isActive() && !_goalTask.isFinished(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {

        _wasWandering = false;

        // Reset our pursuit if our pursuing object no longer is pursuable.
        if (_currentlyPursuing != null && !isValid(mod, _currentlyPursuing)) {
            // This is probably a good idea, no?
            _heuristicMap.remove(_currentlyPursuing);
            T gone = _currentlyPursuing;
            _currentlyPursuing = null;
            onPursuitGone(mod, gone);
        }

        // the drop of what we just broke or killed is a few ticks from existing. picking the next one now is how the
        // drop got left behind, so we stand here until it shows (or the wait is up) and the pickup gets its turn
        if (_expect.isLive() && _expect.hold(WorldHelper.getTicks(), dropSeen(mod, _expectSpot))) {
            setDebugState("Waiting for the drop to show up");
            _goalTask = null;
            return null;
        }

        if (_currentlyPursuing != null) {
            _progress.tick(_currentlyPursuing, getPos(mod, _currentlyPursuing).distanceTo(mod.getPlayer().position()), brokeSomething(mod), WorldHelper.getTicks());
        }

        // Get closest object
        Optional<T> checkNewClosest = getClosestTo(mod, getOriginPos(mod));

        // Receive closest object and position. mid-break on the block we picked nothing takes over (MineStick's idea, for every
        // closest-block task): two hay bales side by side traded "closest" as we stepped and every trade threw the crack away
        if (checkNewClosest.isPresent() && !checkNewClosest.get().equals(_currentlyPursuing) && !breakingPursuit(mod)) {
            T newClosest = checkNewClosest.get();
            // Different closest object
            if (_currentlyPursuing == null) {
                // We don't have a closest object
                _currentlyPursuing = newClosest;
            } else {
                if (isMovingToClosestPos(mod)) {
                    setDebugState("Moving towards closest...");
                    double currentHeuristic = getCurrentCalculatedHeuristic(mod);
                    double closestDistanceSqr = getPos(mod, _currentlyPursuing).distanceToSqr(mod.getPlayer().position());
                    int lastTick = WorldHelper.getTicks();

                    if (!_heuristicMap.containsKey(_currentlyPursuing)) {
                        _heuristicMap.put(_currentlyPursuing, new CachedHeuristic());
                    }
                    CachedHeuristic h = _heuristicMap.get(_currentlyPursuing);
                    h.updateHeuristic(currentHeuristic);
                    h.updateDistance(closestDistanceSqr);
                    h.setTickAttempted(lastTick);
                    if (PursuitProgress.repick(repicksWhenStalled(), _progress.stalled(), _currentlyPursuing, newClosest)) {
                        // 7 s and not a step closer: the 2x rule below would keep us on it forever, whatever the pick
                        // says is nearest now gets a go instead
                        setDebugState("Current pursuit got nowhere, trying the nearest");
                        switchTo(newClosest);
                    } else if (!subclassDecided(mod, newClosest)) {
                        oldRules(mod, newClosest, h, closestDistanceSqr);
                    }
                } else {
                    setDebugState("Waiting for move task to kick in...");
                    // We should keep moving towards our object until we get some new info.
                }
            }
        }

        if (_currentlyPursuing != null) {
            _scanWait.clear();
            _goalTask = getGoalTask(_currentlyPursuing);
            return _goalTask;
        } else {
            _goalTask = null;
        }

        //noinspection ConstantConditions
        if (checkNewClosest.isEmpty() && _currentlyPursuing == null) {
            if (waitingOnFirstLook(mod)) {
                setDebugState("Waiting for the first scan before wandering");
                return null;
            }
            setDebugState("Waiting for calculations I think (wandering)");
            _wasWandering = true;
            return getWanderTask(mod);
        }

        setDebugState("Waiting for calculations I think (NOT wandering)");
        return null;
    }

    // the remembered heuristic retry, then the twice-as-close rule. what everybody gets unless switchCall says otherwise
    private void oldRules(AltoClef mod, T newClosest, CachedHeuristic h, double closestDistanceSqr) {
        // the one we just left stays left unless the subclass insists (a half broken block, a drop)
        boolean noReturn = _noReturn.blocks(newClosest, WorldHelper.getTicks());
        if (noReturn && !mustSwitchTo(mod, _currentlyPursuing, newClosest)) {
            setDebugState("Staying with the current pursuit");
            return;
        }
        if (_heuristicMap.containsKey(newClosest)) {
            // Our new object has a past potential heuristic calculated, if it's better try it out.
            CachedHeuristic maybeReAttempt = _heuristicMap.get(newClosest);
            double maybeClosestDistance = getPos(mod, newClosest).distanceToSqr(mod.getPlayer().position());
            // Get considerably closer (divide distance by 2)
            if (maybeReAttempt.getHeuristicValue() < h.getHeuristicValue() || maybeClosestDistance < maybeReAttempt.getClosestDistanceSqr() / 4) {
                setDebugState("Retrying old heuristic!");
                // The currently closest previously calculated heuristic is better, move towards it!
                switchTo(newClosest);
                // In theory, this next line shouldn't need to be run,
                // but it's CRITICAL to making this work for some reason
                maybeReAttempt.updateDistance(maybeClosestDistance);
            }
        } else if (mustSwitchTo(mod, _currentlyPursuing, newClosest)
                || MineStick.clearlyCloser(getPos(mod, newClosest).distanceToSqr(mod.getPlayer().position()), closestDistanceSqr)) {
            setDebugState("Trying out NEW pursuit");
            // Our new object does not have a heuristic, TRY IT OUT! but only when it is clearly better: the
            // two nearest things trading places as we step (a block and the drop next to it) switched the
            // task every few ticks, and every switch lets go of the mouse button and resets the crack
            switchTo(newClosest);
        } else {
            setDebugState("Staying with the current pursuit");
        }
    }

    // true when switchCall had an opinion, which is then what happens
    private boolean subclassDecided(AltoClef mod, T newClosest) {
        SwitchCall call = switchCall(mod, _currentlyPursuing, newClosest);
        if (call == SwitchCall.NO_OPINION) {
            return false;
        }
        if (call == SwitchCall.SWITCH) {
            setDebugState("Trying out NEW pursuit");
            switchTo(newClosest);
        } else {
            setDebugState("Staying with the current pursuit");
        }
        return true;
    }

    // every swap of one live target for another goes through here so the old one gets remembered (NoReturn)
    private void switchTo(T next) {
        if (_currentlyPursuing != null && !_currentlyPursuing.equals(next)) {
            _noReturn.left(_currentlyPursuing, WorldHelper.getTicks());
        }
        _currentlyPursuing = next;
    }

    // a scan is well under a second. the cap (ScanWait) is for the day one gets stuck behind a slow one
    private boolean waitingOnFirstLook(AltoClef mod) {
        return _scanWait.waiting(WorldHelper.getTicks(), stillLooking(mod));
    }

    private static class CachedHeuristic {

        private double _closestDistanceSqr;
        private int _tickAttempted;
        private double _heuristicValue;

        public CachedHeuristic() {
            _closestDistanceSqr = Double.POSITIVE_INFINITY;
            _heuristicValue = Double.POSITIVE_INFINITY;
        }

        public CachedHeuristic(double closestDistanceSqr, int tickAttempted, double heuristicValue) {
            _closestDistanceSqr = closestDistanceSqr;
            _tickAttempted = tickAttempted;
            _heuristicValue = heuristicValue;
        }

        public double getHeuristicValue() {
            return _heuristicValue;
        }

        public void updateHeuristic(double heuristicValue) {
            _heuristicValue = Math.min(_heuristicValue, heuristicValue);
        }

        public double getClosestDistanceSqr() {
            return _closestDistanceSqr;
        }

        public void updateDistance(double closestDistanceSqr) {
            _closestDistanceSqr = Math.min(_closestDistanceSqr, closestDistanceSqr);
        }

        public int getTickAttempted() {
            return _tickAttempted;
        }

        public void setTickAttempted(int tickAttempted) {
            _tickAttempted = tickAttempted;
        }
    }
}
