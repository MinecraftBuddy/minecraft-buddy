package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.schematic.BuildNightSleep;
import adris.altoclef.tasksystem.Task;

import java.util.function.BooleanSupplier;

// the buddy's fork: any task with the gamer's night round it (altoSleepAtNight), the same one a survival schematic build has
// (BuildNightSleep: NightSleep's rules, the evening's wool and bed, the trip to bed and back). a #get from nothing went down
// a cave at night for its diamonds and a crowd of zombies, spiders and skeletons killed it. with no bed and no wool to be
// had it works the night as before, which is the night's own rule
// (it is the wrapper round every run the buddy hands the autopilot, so it also takes back the stations a run's smelts and crafts
// put down, StationsBack)
public class SleepThroughNightsTask extends Task {

    private final Task _inner;
    private final BuildNightSleep _night = new BuildNightSleep();
    // the buddy's say on whether tonight is slept, null for every night (the gamer's rule). a night it says no to is no night
    // here: no evening's bed, no trip, the inner task goes on. a trip or an evening's craft under way still runs to its end
    private final BooleanSupplier _tonight;

    public SleepThroughNightsTask(Task inner) {
        this(inner, null);
    }

    public SleepThroughNightsTask(Task inner, BooleanSupplier tonight) {
        _inner = inner;
        _tonight = tonight;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (Tuning bag_keep_stations, a fix of 13 Nov 2026, on) a furnace, smoker or crafting table a smelt or craft of this run put
        // down and is done with goes back in the bag before the task's next tick (StationsBack); not while the night's trip holds
        if (!_night.holding()) {
            Task back = StationsBack.task(mod);
            if (back != null) {
                setDebugState("A station it put down, back in the bag");
                return back;
            }
        }
        if (_tonight != null && !_night.holding() && !_tonight.getAsBoolean()) {
            setDebugState(_inner.toString());
            return _inner;
        }
        Task night = _night.tick(mod, false);
        if (night != null) {
            setDebugState("The night");
            return night;
        }
        if (_night.holding()) {
            setDebugState("The night, waiting on the bed scan");
            return null;
        }
        setDebugState(_inner.toString());
        return _inner;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _night.drop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _inner.isFinished(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SleepThroughNightsTask t && t._inner.equals(_inner);
    }

    @Override
    protected String toDebugString() {
        return "Nights asleep, " + _inner;
    }
}
