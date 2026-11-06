package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute;
import adris.altoclef.tasksystem.Task;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// a forced nether portal can hang on a 2x3 obsidian slab over the lava sea with the first real ground a hundred blocks out, and the
// sweep never accepts a bridge that long. so right after every arrival: wait for the chunks around us (or LOAD_TICKS), ask
// EscapeRoute for the nearest ground, and if that is more than a few placements away walk the route with EscapeBridgeTask. an
// ordinary arrival has floor beside the portal, so it plans nothing and whoever owns the walk goes on as it always did.
// the nether phase and the walk back for our things after a death both arrive through a portal, so both own one of these
public class EscapeRun {
    private enum State { NONE, PENDING, RUNNING, OVER, LAVA_LAKE }

    // what the arrival plan means to do about the portal
    enum Verdict { STAY, LAKE, STRANDED, BRIDGE }

    // how long the arrival waits for the chunks around it before it plans with what it has
    private static final long LOAD_TICKS = 60;
    private static final int CHUNKS = 6;

    private State state = State.NONE;
    private EscapeBridgeTask escape;
    private long arrivalTick;
    // we were in the nether last time we were asked, so a change to true is an arrival
    private boolean seen;
    private int hops;
    private String hud;

    public void reset() {
        state = State.NONE;
        escape = null;
        seen = false;
        hud = null;
    }

    public boolean running() {
        return state == State.RUNNING;
    }

    // the portal floats over a lava lake and there is no cheap way off: the run is over, the owner calls that an unlucky world
    public boolean lavaLake() {
        return state == State.LAVA_LAKE;
    }

    public String hud() {
        return hud;
    }

    // a cheap way off (a wall to dig, a few blocks to lay) still gets taken over lava, the lake only ends the run when the way off is long
    static Verdict verdict(EscapeRoute.Plan plan, BooleanSupplier overLavaLake) {
        if (!plan.isolated()) {
            return Verdict.STAY;
        }
        boolean cheap = plan.route() != null && plan.route().placements() <= EscapeRoute.ISOLATED_PLACEMENTS;
        if (!cheap && overLavaLake.getAsBoolean()) {
            return Verdict.LAKE;
        }
        return plan.route() == null ? Verdict.STRANDED : Verdict.BRIDGE;
    }

    // once per tick. null = nothing to do, carry on; else the task that has the wheel
    public Task step(AltoClef mod, boolean inNether, long gameTime, Consumer<String> say, Consumer<String> progress) {
        hud = null;
        if (!inNether) {
            // leaving (or never there) and coming back is a new arrival
            reset();
            return null;
        }
        if (!seen) {
            seen = true;
            state = State.PENDING;
            arrivalTick = gameTime;
            escape = null;
        }
        if (state == State.OVER || state == State.LAVA_LAKE) {
            return null;
        }
        if (state == State.PENDING) {
            if (gameTime - arrivalTick < LOAD_TICKS && !EscapeBridgeTask.chunksLoaded(mod, CHUNKS)) {
                hud = "Waiting for the Nether to load";
                return new IdleTask();
            }
            EscapeRoute.Plan plan = EscapeBridgeTask.plan(mod);
            Verdict v = verdict(plan, () -> EscapeBridgeTask.lavaLake(mod));
            if (v == Verdict.STAY) {
                state = State.OVER;
                return null;
            }
            if (v == Verdict.LAKE) {
                state = State.LAVA_LAKE;
                return null;
            }
            if (v == Verdict.STRANDED) {
                state = State.OVER;
                say.accept("nether: stranded and no way off fits the bag, sweeping anyway");
                return null;
            }
            escape = new EscapeBridgeTask(plan.route());
            hops = 0;
            state = State.RUNNING;
            say.accept("nether: floating over nothing, bridging " + plan.route().placements() + " blocks to ground");
        }
        if (escape.failed()) {
            state = State.OVER;
            say.accept("nether: the bridge off the portal jammed, sweeping");
            return null;
        }
        if (escape.done(mod)) {
            state = State.OVER;
            progress.accept("off the slab");
            return null;
        }
        // the phase's stall timer wants to see the bridge moving, and a hold for a fireball is not a stall
        if (escape.hopsDone() != hops || escape.holding()) {
            hops = escape.hopsDone();
            progress.accept("bridge");
        }
        hud = "Bridging off the portal";
        return escape;
    }
}
