package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Monitors;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.trace.CutRing;
import adris.altoclef.trace.Lines;
import adris.altoclef.trace.RepeatCounter;
import adris.altoclef.trace.StartStormWatch;
import adris.altoclef.trace.StormGate;
import adris.altoclef.trace.Trace;
import adris.altoclef.ui.HudText;
import baritone.api.event.events.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public abstract class Task {

    // debug state only logs on change, so a retry loop looks like a few unrelated lines. these count the flips and the
    // starts across every task, the main thread is the only one that ticks them
    private static final RepeatCounter REPEATS = new RepeatCounter();
    private static final StartStormWatch STARTS = new StartStormWatch();
    // who stopped whom, to name the other side of a storm. and the outermost-only rule for flagging one
    private static final CutRing CUTS = new CutRing();
    private static final StormGate STORM_GATE = new StormGate();

    // whoever ticked this task last, only read when a storm is flagged
    private Task _parent;
    // the chain that ticked us last. a paused task's hand over is filed under it (BotBehaviour.holdWhilePaused)
    private TaskChain _chain;

    private String _oldDebugState = "";
    private String _debugState = "";
    private String _hudState = "";

    private final TaskClock _clock = new TaskClock();

    private Task _sub = null;

    private boolean _first = true;

    private boolean _stopped = false;

    private boolean _active = false;

    // true while onStop runs because a chain took over for a bit (the task comes back through onStart), false for a real stop
    private boolean _interrupting = false;

    // an interrupt ran onStop and the task has not been started again yet. a real stop in that state would skip onStop
    // (it only runs when the task is running), so onStopWhilePaused gets the chance to clean up instead
    private boolean _paused = false;

    public void tick(AltoClef mod, TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        _chain = parentChain;
        if (_first) {
            Debug.logInternal("Task START: " + this);
            watchStart();
            _paused = false;
            _active = true;
            startAs(mod);
            _first = false;
            _stopped = false;
        }
        if (_stopped) return;

        // ticks only when the task really runs, so an interrupt is never billed. note a parent's clock keeps running
        // while its child does the work, that is on purpose: the parent's give up covers everything it asked for
        _clock.tick();
        Task newSub = tickAs(mod);
        // Debug state print
        if (!_oldDebugState.equals(_debugState)) {
            Debug.logInternal(toString());
            _oldDebugState = _debugState;
            watchRepeat();
        }
        // We have a sub task
        if (newSub != null) {
            if (!newSub.isEqual(_sub)) {
                if (canBeInterrupted(mod, _sub, newSub)) {
                    // Our sub task is new
                    if (_sub != null) {
                        // Our previous sub must be interrupted.
                        _sub.stop(mod, newSub);
                    }

                    _sub = newSub;
                }
            }

            // Run our child
            _sub._parent = this;
            _sub.tick(mod, parentChain);
        } else {
            // We are null
            if (_sub != null && canBeInterrupted(mod, _sub, null)) {
                // Our previous sub must be interrupted.
                if (_sub.isActive()) {
                    noteCut(_sub.getClass(), getClass(), true);
                }
                _sub.stop(mod);
                _sub = null;
            }
        }
    }

    // the task whose onStart/onTick is running right now, so InputControls knows whose hand is on a key. only those two
    // are claimed: a hold made in onStop (or by a chain's getPriority) has no owner and stays down like it always did.
    // restoring the old one instead of nulling means a child that is driven from inside its parent's onTick gives the
    // key back to the parent when it is done
    private static Task _holdOwner = null;

    public static Task currentOwner() {
        return _holdOwner;
    }

    private void startAs(AltoClef mod) {
        Task before = _holdOwner;
        _holdOwner = this;
        try {
            onStart(mod);
        } finally {
            _holdOwner = before;
        }
    }

    private Task tickAs(AltoClef mod) {
        Task before = _holdOwner;
        _holdOwner = this;
        try {
            return onTick(mod);
        } finally {
            _holdOwner = before;
        }
    }

    // onStop is where a task lets go on purpose, so nothing it presses in there belongs to it (or to whoever is
    // calling stop on it from inside their own onTick)
    private void stopAs(AltoClef mod, Task interruptTask) {
        Task before = _holdOwner;
        _holdOwner = null;
        try {
            onStop(mod, interruptTask);
        } finally {
            _holdOwner = before;
        }
    }

    private void pausedStopAs(AltoClef mod) {
        Task before = _holdOwner;
        _holdOwner = null;
        try {
            onStopWhilePaused(mod);
        } finally {
            _holdOwner = before;
        }
    }

    // lets go of every key this task is holding, after its own onStop had its say. the other owners of a key keep it down
    private void letGo(AltoClef mod) {
        if (mod == null || mod.getInputControls() == null) {
            return;
        }
        mod.getInputControls().releaseOwnedBy(this);
    }

    // for a root that is done without anybody calling stop on it (stop would run onStop, and that is not the plan here):
    // the whole tree of children lets go of its keys
    public final void letGoOfKeys(AltoClef mod) {
        for (Task t = this; t != null; t = t._sub) {
            t.letGo(mod);
        }
    }

    // a flag and never an action: 21 starts of one class in a minute is a loop, say so and keep the lead up
    private void watchStart() {
        try {
            long now = System.currentTimeMillis();
            if (STARTS.start(getClass(), now)) {
                flagStorm(now);
            }
        } catch (RuntimeException e) {
            // a monitor must never keep a task from starting, but a broken one should say so once
            Monitors.broke("start storm", e);
        }
    }

    // the storm is real, now only the line and who gets it. a class under one that is already flagged (or about to be) stays quiet
    private void flagStorm(long now) {
        List<Class<?>> ancestors = new ArrayList<>();
        for (Task up = _parent; up != null && ancestors.size() < 8; up = up._parent) {
            ancestors.add(up.getClass());
        }
        boolean quiet = STORM_GATE.shadowed(getClass(), ancestors, now, up -> STARTS.recent(up, now));
        STORM_GATE.seen(getClass(), now);
        if (quiet) {
            return;
        }
        CutRing.Culprit by = CUTS.mostCommon(getClass(), now, StartStormWatch.WINDOW_MS);
        Debug.logInternal(by == null ? Lines.startStorm(className()) : Lines.startStorm(className(), nameOf(by.by()), by.parent()));
        Trace.dump("monitor:storm");
    }

    private static String nameOf(Class<?> type) {
        String name = type.getSimpleName();
        return name.isEmpty() ? type.getName() : name;
    }

    // one task stopped another (or a parent let go of it): kept for the storm line
    private void noteCut(Class<?> victim, Class<?> cutter, boolean byParent) {
        try {
            CUTS.record(victim, cutter, byParent, System.currentTimeMillis());
        } catch (RuntimeException e) {
            Monitors.broke("cut ring", e);
        }
    }

    // the state flipped into something new. the same (class, state) coming round again and again is a retry loop
    private void watchRepeat() {
        if (_debugState.isEmpty()) {
            return;
        }
        try {
            int times = REPEATS.hit(className() + "|" + _debugState, System.currentTimeMillis());
            if (times > 0) {
                Debug.logInternal(Lines.repeat(className(), _debugState, times));
            }
        } catch (RuntimeException e) {
            Monitors.broke("repeat", e);
        }
    }

    private String className() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }

    // how long this task has really run. nothing in here ever clears it, reset() included: SingleTaskChain calls reset()
    // on every resume after an interrupt, so clearing there would give every interrupt a fresh budget again
    protected final double timeOnTaskSeconds() {
        return _clock.spentSeconds();
    }

    protected final boolean overTimeOnTask(double limitSeconds) {
        return _clock.over(limitSeconds);
    }

    public void reset() {
        _first = true;
        _active = false;
        _stopped = false;
        _paused = false;
    }

    // for a task that wants to take its child away after the child threw: the child is stopped and forgotten, so the
    // next onTick can hand in a fresh one (a same-class child would be kept by isEqual otherwise)
    protected final void dropChild(AltoClef mod) {
        Task sub = _sub;
        _sub = null;
        if (sub != null) {
            try {
                sub.stop(mod);
            } catch (RuntimeException e) {
                Debug.logInternal("Task: dropped child " + sub + " threw while stopping: " + e);
            }
        }
    }

    // the child we handed out last tick (stopped ones included, ask isActive). read only, swapping it is tick's job
    protected final Task subTask() {
        return _sub;
    }

    public void stop(AltoClef mod) {
        stop(mod, null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (interruptTask != null) {
            noteCut(getClass(), interruptTask.getClass(), false);
        }
        try {
            if (!_first) {
                stopAs(mod, interruptTask);
            } else if (_paused) {
                _paused = false;
                pausedStopAs(mod);
            }
        } finally {
            letGo(mod);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.stop(mod, interruptTask);
        }

        _first = true;
        _active = false;
        _stopped = true;
    }

    // stop() on a task that was interrupted and never came back (#stop in the middle of a fight). nothing else runs for it
    protected void onStopWhilePaused(AltoClef mod) {
    }

    /**
     * Lets the task know it's execution has been "suspended"
     * <p>
     * STILL RUNS `onStop`
     * <p>
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     */
    public void interrupt(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        if (interruptTask != null) {
            noteCut(getClass(), interruptTask.getClass(), false);
        }
        if (!_first) {
            _interrupting = true;
            try {
                stopAs(mod, interruptTask);
            } finally {
                _interrupting = false;
                letGo(mod);
            }
            _paused = true;
        } else {
            // onStart can hold and then throw, which leaves _first set with a key down
            letGo(mod);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.interrupt(mod, interruptTask);
        }

        _first = true;
    }

    // for onStop: a pause, not an end. both pass a null interruptTask, so this is the only way to tell
    protected final boolean isInterrupting() {
        return _interrupting;
    }

    protected final TaskChain chain() {
        return _chain;
    }

    protected void setDebugState(String state) {
        setDebugState(state, "");
    }

    // the hud state is tied to the debug state on purpose: a task that sets a plain debug state after a friendly
    // one would otherwise leave the friendly one on screen while doing something else entirely
    protected void setDebugState(String state, String hudState) {
        if (state == null) {
            state = "";
        }
        _debugState = state;
        _hudState = hudState == null ? "" : hudState;
    }

    // what the task hud shows instead of toDebugString. plain words a player understands: no class names, no
    // toString dumps, no brackets, no "x Infinity". the default is the humanized class name, which beats the debug
    // string for the long tail. never called directly by the hud, see getHudName
    protected String toHudString() {
        return HudText.humanizeClassName(getClass());
    }

    // plumbing (slot shuffling, cursor freeing, "do to closest" wrappers) only shows on the hud when it is the leaf,
    // that is when it's the thing actually happening right now
    protected boolean isHudPlumbing() {
        return false;
    }

    public final String getHudName() {
        try {
            String s = toHudString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Throwable t) {
            // a label that throws is a bug in the label, not a reason to lose the hud
        }
        return HudText.humanizeClassName(getClass());
    }

    public final String getHudState() {
        return _hudState;
    }

    public final boolean hudPlumbing() {
        try {
            return isHudPlumbing();
        } catch (Throwable t) {
            return false;
        }
    }

    // Virtual
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean stopped() {
        return _stopped;
    }

    protected abstract void onStart(AltoClef mod);

    protected abstract Task onTick(AltoClef mod);

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + _debugState;
    }

    // the hud draws the name and the state in different colors, so it wants them apart
    public String getDebugName() {
        return toDebugString();
    }

    public String getDebugState() {
        return _debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    // equals is whatever the subclass's isEqual says, and some of those accept a whole family (a plain pickup equals
    // the recover pickup subclass), so not even the class is safe to hash. a constant is the only hash that always
    // agrees with equals. nothing keys a map on a task, so the buckets being one long list does not matter
    @Override
    public int hashCode() {
        return 31;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t._sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    // a wander in the chain below that has run a long time without getting anywhere. a plain thisOrChildAreTimedOut
    // is true the whole time a wander is active, this one only once it is stuck
    public boolean thisOrChildWanderFailing() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask w && w.failing());
    }

    // a force held longer than this is assumed to be stuck, so it can't deadlock the whole tree
    private static final int FORCE_TIMEOUT_TICKS = 200;
    private int _forcedSinceTick = -1;
    private boolean _forceTimeoutLogged = false;

    static boolean forceTimedOut(int sinceTick, int nowTick) {
        return nowTick - sinceTick > FORCE_TIMEOUT_TICKS;
    }

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    boolean canBeInterrupted(AltoClef mod, Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // Our task can declare that is FORCES itself to be active NOW.
        // this is an exists-walk: ANY forcing node down the chain holds the swap, not just the first node.
        // it used to return true at the first node that wasn't forcing, so nested forces were never honoured
        boolean forced = subTask.thisOrChildSatisfies(task ->
                task instanceof ITaskCanForce canForce && canForce.shouldForce(mod, toInterruptWith));
        if (!forced) {
            _forcedSinceTick = -1;
            _forceTimeoutLogged = false;
            return true;
        }
        // game ticks, not wall time: a long esc pause must not look like a held force that ran out
        int now = TickEvent.currentCount();
        if (_forcedSinceTick < 0) {
            _forcedSinceTick = now;
        }
        if (forceTimedOut(_forcedSinceTick, now)) {
            // a stuck shouldForce must not freeze the tree forever (log once, not 20 times a second)
            if (!_forceTimeoutLogged) {
                _forceTimeoutLogged = true;
                Debug.logInternal("Task force held for over " + FORCE_TIMEOUT_TICKS / 20 + "s, ignoring it: " + subTask);
            }
            return true;
        }
        return false;
    }
}
