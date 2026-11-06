package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;

// what a phase handler gets from the engine each tick
public interface GamerContext {
    GamerConfig cfg();

    // durable memory, saved by the engine. anything that should survive a relog goes in here
    RunState state();

    GamerFacts facts();

    // how much food we hold and what every gate makes of it, as of this tick. the engine builds it once per tick, the default
    // (a test's stub) builds it on every ask. every food gate reads this and nothing else, see FoodPlan. the phase it is built for
    // is the state's: that is what decides whether raw meat in the bag can become dinner. the refill latch moves on every ask,
    // same answer for the same bag
    default FoodPlan food() {
        return FoodPlan.latched(facts(), cfg(), state(), Debug::logInternal, wood());
    }

    // the build blocks band as of this tick (BuildBlocks): a refill under way keeps running to its target, a fresh one is refused
    // once the phase entry has used up its refills
    default BuildBlocks buildBlocks() {
        return BuildBlocks.of(facts(), state(), Debug::logMessage);
    }

    // how much wood we hold, owe and may burn, as of this tick: the one answer every wood question reads (see WoodBudget). the engine
    // builds it once per tick and hands the same object to the furnaces' reserve, the default (a test's stub) builds it on every ask.
    // it is the state's phase's view of the wood, and has no food plan in it, so asking never moves the refill latch
    default WoodBudget wood() {
        return WoodBudget.of(facts(), cfg().overworld, cfg().end.beds, state().phase);
    }

    // what the planner holds of the stations as of this tick (StationVerdict). the engine
    // hands out the one the facts built in refresh() and drops its copy when the next tick starts, the default (a test's stub) asks the facts
    default StationVerdict stations() {
        return facts().stations();
    }

    // 1 on the first try of this phase, 2 after a retry...
    int attempt();

    double secondsInPhase();

    // write RunState now (the engine also saves on phase change and every ~20 s when dirty)
    void save();

    // tell the watchdog something useful happened so the stall timer restarts
    void progress(String what);

    // this phase cannot work the way it is going: hands the decision to the handler's onTimeout
    void fail(String reason);

    // the run is over and nothing here can fix it: say message as is and stop. stubs just fail the step
    default void giveUp(String reason, String message) {
        fail(reason);
    }

    // one line in chat, soprano prefix, for things the user should know ("giving up on casting, mining obsidian")
    void log(String line);

    // lets baritone path over end portal blocks (volatile toggle in AltoClefSettings), reset by the engine on stop
    void walkOnEndPortal(boolean on);
}
