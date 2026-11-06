package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.GameClock;
import net.minecraft.world.entity.monster.Blaze;

// the last rod is in and the blazes did not get the memo. the rods task finishes the moment the count is met, and with it goes the
// "these blazes are mine" line and the fight brain: the next task then walked off with three of them still shooting, and combat
// turned them into a run and a stand in the open. this keeps the same line up and the same fight running (kill the one we
// picked, run when hp says so, BlazeFight.disengageOnly) until nothing has been worth a swing or a run for a few seconds
public class BlazeDisengageTask extends Task {

    private final BlazeFight fight = new BlazeFight();
    private BotBehaviour.State level;
    private long startTick = Long.MIN_VALUE;
    // game time we were last fighting or running
    private long lastSeenTick = Long.MIN_VALUE;
    private boolean over;

    public BlazeDisengageTask() {
        fight.disengageOnly();
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
        level = mod.getBehaviour().push();
        // same two lines the rods task keeps, for the same reason: mob defense chasing a hovering blaze with a sword is how this
        // used to go wrong, and the aura swinging at one six blocks off pauses the path that gets us out of its sight
        mod.getBehaviour().addMobDefenseExclusion(entity -> entity instanceof Blaze);
        mod.getBehaviour().addForceFieldExclusion(entity -> entity instanceof Blaze && mod.getPlayer().distanceTo(entity) > 3.5);
        mod.getBehaviour().avoidBlockPlacing(fight::noPlacing);
        if (startTick == Long.MIN_VALUE) {
            startTick = mod.getWorld().getGameTime();
            // the caller starts this because one is looking, so the calm clock counts from now and not from never
            lastSeenTick = startTick;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        Task away = fight.tick(mod);
        // CAMP is done: nothing in reach and not running. the calm spell only counts from the last tick we were fighting
        if (fight.mode() != BlazeFightRules.Mode.CAMP) {
            lastSeenTick = now;
        }
        // a clock a tick or two back is lag, one way back is a world change and the old stamps mean nothing
        long sinceSeen = GameClock.since(lastSeenTick, now);
        long running = GameClock.since(startTick, now);
        over = BlazeFightRules.disengageOver(sinceSeen, running);
        if (away != null) {
            setDebugState("Finishing the blazes: " + fight.mode().name().toLowerCase());
            return away;
        }
        setDebugState("Waiting for the blazes to lose us");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // a pause keeps the blazes ours until we are back, a real stop lets go (same hand over as ResourceTask)
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), level);
        } else {
            mod.getBehaviour().releasePaused(this);
        }
        mod.getBehaviour().pop();
        level = null;
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return over;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BlazeDisengageTask;
    }

    @Override
    protected String toDebugString() {
        return "Back away from the blazes";
    }
}
