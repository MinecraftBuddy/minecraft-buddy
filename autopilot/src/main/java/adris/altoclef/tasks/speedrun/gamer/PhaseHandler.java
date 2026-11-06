package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;

import java.util.Optional;

// one per phase. handlers are created once per GamerTask and keep their sub step memory in fields,
// durable memory (anything a relog must not lose) goes in RunState via the context
public interface PhaseHandler {
    GamerPhase phase();

    // plain words for the hud headline of this phase
    String hud();

    // the step inside the phase in plain words ("Collecting Blaze Rods"), shown after the headline. null = just the headline.
    // the engine feeds it to setDebugState(debug, hud) every tick, so keep it a field read
    default String hudState() {
        return null;
    }

    // the kit runner this phase drives, for the card's rows. null = the phase has no kit (the card shows no rows)
    default KitRunner kitRunner() {
        return null;
    }

    // the side jobs this phase runs. null = none
    default PrepSupport support() {
        return null;
    }

    // the diamond, coal or gravel detour that is going, for the card's detour row. null = none. PORTAL and NETHER have a gravel detour of
    // their own and no PrepSupport, they say so themselves
    default ResourceDetour detour() {
        PrepSupport s = support();
        return s == null ? null : s.activeDetour();
    }

    // true for the phases that run the station pickup themselves (Workbenches.tick with their plan: GATHER, IRON, PORTAL). for every
    // other phase the engine sweeps instead, so a table placed by a craft anywhere in the run does not stay behind
    default boolean ownsBenches() {
        return false;
    }

    // true = a night must not cut into this phase right now. only matters for phases NightSleep.phaseAllowed lets through, the
    // rest are held whatever this says
    default boolean nightHold() {
        return false;
    }

    // pure and cheap, the engine asks every tick. true = move on to the next phase
    boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg);

    // the same with the tick's context, which is how the engine asks: a phase whose answer hangs on the food plan or the wood budget
    // reads the tick's (ctx.food(), ctx.wood()) so it cannot call itself done while the plan it just walked still wants something
    default boolean isDone(GamerContext ctx) {
        return isDone(ctx.facts(), ctx.state(), ctx.cfg());
    }

    // pure. a rule that says "the stuff this phase needs is gone, an earlier phase has to run again"
    default Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return Optional.empty();
    }

    // the child task for this tick, or null when there is nothing to do. cache child tasks in fields,
    // Task.tick keeps the old child when isEqual says so but a new object per tick restarts its state
    Task tick(AltoClef mod, GamerContext ctx);

    // we died and came back. a phase that holds a trip or a task in flight (they belong to the old body) lets go of it here; RunState stays
    default void onRespawn() {
    }

    default void onEnter(AltoClef mod, GamerContext ctx) {
    }

    // also called when the run stops or gets stuck
    default void onExit(AltoClef mod, GamerContext ctx) {
    }

    // true = the budget clock stands still right now (the stall timer does not). for a long walk or a capped side job that
    // has its own limit, so the phase budget is spent on the work and not on getting there
    default boolean holdsBudget() {
        return false;
    }

    // seconds without progress before the watchdog times the phase out. 0 = never (waiting phases, they still have a budget)
    default double stallSeconds() {
        return 120;
    }

    // what to do when the budget or stall timer ran out. default: retry until cfg.maxAttempts, then stuck
    // (skipping is opt-in per phase, so the run ends STUCK instead of pretending a mandatory step happened)
    default Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }
}
