package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.RunZones;

import java.util.function.DoubleSupplier;

// walks the sweep the planner lays out: one structure cell after another, waypoint by waypoint. it does not look at
// blocks itself, whoever owns the planner scans for sightings (NetherPhase does it every few ticks, so the same scan
// also catches a warped forest while we are busy with rods). finishes when the goal is met, failed() says why it gave up
public class FindNetherStructureTask extends Task {
    private final NetherSweepPlanner planner;
    private final Goal goal;
    private final DoubleSupplier clock;

    private GetToXZTask walk;
    private int walkX;
    private int walkZ;
    private String failure;
    private String step = "Looking for a fortress";
    // what the path predicate sees, from baritone's thread: where we stand, the game clock, and the waypoint we are walking to
    // (NaN = none yet). written every tick, so the zones from RunZones are judged against where we are now
    private volatile int zoneX;
    private volatile int zoneZ;
    private volatile long zoneNow;
    private volatile double goalX = Double.NaN;
    private volatile double goalZ = Double.NaN;

    public FindNetherStructureTask(NetherSweepPlanner planner, Goal goal, DoubleSupplier clock) {
        this.planner = planner;
        this.goal = goal;
        this.clock = clock;
        if (goal == Goal.WARPED) {
            step = "Looking for a warped forest";
        }
    }

    public String failure() {
        return failure;
    }

    public String step() {
        return step;
    }

    @Override
    protected void onStart(AltoClef mod) {
        failure = null;
        // bastions are where piglin brutes live, the path planner treats the avoid zone as a wall
        mod.getBehaviour().push();
        noteZoneInputs(mod);
        // (and the spots a heavy hitter ran us off from lately, see RunZones for which zones never count as a wall)
        mod.getBehaviour().avoidWalkingThrough(p -> planner.blocksPath(p.getX(), p.getZ())
                || RunZones.INSTANCE.blocks(p.getX(), p.getZ(), zoneNow, zoneX, zoneZ, goalX, goalZ));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        noteZoneInputs(mod);
        int px = mod.getPlayer().blockPosition().getX();
        int pz = mod.getPlayer().blockPosition().getZ();
        Action action = planner.step(goal, px, pz, clock.getAsDouble());
        switch (action.kind()) {
            case GOTO -> {
                setDebugState("Sweeping " + action.why() + " towards " + action.x() + "," + action.z(), step);
                return walkTo(action.x(), action.z());
            }
            case FAIL -> {
                failure = action.why();
                setDebugState("Giving up the sweep: " + failure, step);
                return null;
            }
            default -> {
                setDebugState("Found it", step);
                return null;
            }
        }
    }

    private void noteZoneInputs(AltoClef mod) {
        zoneX = mod.getPlayer().blockPosition().getX();
        zoneZ = mod.getPlayer().blockPosition().getZ();
        zoneNow = mod.getWorld().getGameTime();
    }

    // the count the restock goes by, for the phase that decides on a trip home when the dig keeps coming up empty
    public static int heldThrowaways(AltoClef mod) {
        return NetherBlockRestock.heldThrowaways(mod);
    }

    // a new GetToXZTask object only when the waypoint changes, a new one per tick would restart its pathing every tick
    private Task walkTo(int x, int z) {
        if (walk == null || walkX != x || walkZ != z) {
            walk = new GetToXZTask(x, z, planner.arriveRadius());
            walkX = x;
            walkZ = z;
            goalX = x;
            goalZ = z;
        }
        return walk;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return planner.goalMet(goal);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FindNetherStructureTask task && task.planner == planner && task.goal == goal;
    }

    @Override
    protected String toDebugString() {
        return "Sweeping the Nether for " + goal;
    }

    @Override
    protected String toHudString() {
        return goal == Goal.WARPED ? "Searching for a warped forest" : "Searching for a Nether fortress";
    }
}
