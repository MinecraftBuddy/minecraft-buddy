package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;

// what the planner counts as held, for the tables, furnaces and smokers it put down, worked out once per tick. "do we have a usable
// crafting table / furnace / smoker" used to be asked in about forty places and answered four ways, so the places agreed on the
// block and disagreed on the verdict (see WoodBudget for the same cure on wood). this is the one home: MinecraftFacts builds it in
// refresh() (the world half asks the game, this class only does arithmetic over the registry), GamerContext.stations() hands it out.
// it is immutable. the only thing its construction writes is the StationLatches it is handed: the hysteresis lives here and nowhere else.
//
// it carries the held flags and nothing else, because nothing else is read. what is NOT in it on purpose: the per-bench decisions
// (WorkbenchRules.decide moves the bench's clocks and reads the phase's plan, so it stays in Workbenches.run), idle / in use (they ask
// the task tree), the container tasks' own choice (StationChoice is alto's, it runs on candidates the task builds from the tracker) and
// phaseMayEnd (the isDone predicates and PortalPhase ask AFTER the tick's pickups moved the registry and have no ctx, so a value built
// in refresh() would answer one tick late: they call Workbenches.phaseMayEnd)
public final class StationVerdict {
    // nobody is placed anywhere, nothing is held
    public static final StationVerdict NONE = fromFlags(false, false, false);

    // what the game says about the stations, as questions. MinecraftFacts answers them from the world, the equivalence test from a table
    interface World {
        // the block is still there (an unloaded chunk keeps its station)
        boolean stands(Kind kind, RunState.Pos pos);

        // the bag holds one of the item
        boolean inBag(Kind kind);

        // DoStuffInContainerTask.bagCanMake: the bag has what one more takes
        boolean canMake(Kind kind);

        // DoStuffInContainerTask.walkBackUsable for this bench
        boolean walkUsable(Kind kind, Bench b);
    }

    // one kind of station. held is what the planner counts: one of ours stands (or is coming down, it is on its way to the bag) within
    // the line, or the container task would walk back to it past the line (Workbenches.plannerWalksBack). the flag KitPlanner, CookGate
    // and the wood budget read. a smoker's also says "smoker first", so it only counts the far ones when no furnace is at hand
    // (WorkbenchRules.plannerSmoker)
    public record Station(boolean heldForPlanner) {
    }

    private final Station table;
    private final Station furnace;
    private final Station smoker;

    // a smoker of ours stands within the band, no walk back counted. what the visit that just emptied a furnace asks: the flag above
    // is built at the start of the tick, with that furnace's job still booked
    private final boolean smokerNear;

    private StationVerdict(Station table, Station furnace, Station smoker, boolean smokerNear) {
        this.table = table;
        this.furnace = furnace;
        this.smoker = smoker;
        this.smokerNear = smokerNear;
    }

    public boolean smokerStandingNear() {
        return smokerNear;
    }

    public Station table() {
        return table;
    }

    public Station furnace() {
        return furnace;
    }

    public Station smoker() {
        return smoker;
    }

    // only the planner's flags known (a test's facts, a state that is not loaded yet)
    public static StationVerdict fromFlags(boolean table, boolean furnace, boolean smoker) {
        return new StationVerdict(new Station(table), new Station(furnace), new Station(smoker), smoker);
    }

    // the verdict for one look from (px, py, pz). moves `latches`: this is the only place they are written
    static StationVerdict of(RunState state, String dimension, double px, double py, double pz, World world, StationLatches latches) {
        // held when one of ours is within NEAR (a straight line, height counts), the same test the container tasks use, with a latch on
        // top so the plan does not flip at the line (the cobble floor, the cook gate, a log trip against the cobble). the table's is big
        // (once out of NEAR it only counts again well inside it, its planks swing the plan). furnace and smoker only get a block, and
        // only inward: a bot digging at the line would swing the stone need by 8 cobble on every step, and the flag is never true
        // where the task would put down a second one (a latch that reached past the line did that once: at 21.5 the task puts the one
        // from the bag down, or skips a parked one and makes another). in (20, 21] the planner may say "not held" where the task
        // would use ours, a conservative plan inside a one block ring
        // (table first, then furnace, then smoker: each latch is read before it is written, in the order the flags always ran in)
        boolean tableNear = heldNear(state, Kind.TABLE, dimension, px, py, pz, WorkbenchRules.returnRadius(latches.tableFar), world);
        latches.tableFar = !tableNear && Workbenches.any(state, Kind.TABLE, dimension);
        // past the latch a table of ours still counts as far as the craft would walk back to it (Workbenches.plannerWalksBack), and
        // once it counts that way it keeps counting HOLD past the line, the same slack the task gives the one it is walking to
        // (StationChoice.nearest), or the plan asks for table planks while the craft is still on its way to the table
        boolean tableBack = !tableNear && walksBack(state, Kind.TABLE, dimension, px, py, pz, latches.tableBack ? StationChoice.HOLD : 0, world);
        latches.tableBack = tableBack;
        // past the band a furnace or smoker of ours still counts when the smelt would walk back to it (plannerWalksBack). the latch
        // only ever remembers the band's own answer, the walk back has no line to dither over
        boolean furnaceNear = heldNear(state, Kind.FURNACE, dimension, px, py, pz, WorkbenchRules.bandRadius(latches.furnaceHeld), world);
        latches.furnaceHeld = Workbenches.any(state, Kind.FURNACE, dimension) ? Boolean.valueOf(furnaceNear) : null;
        boolean furnaceBack = !furnaceNear && walksBack(state, Kind.FURNACE, dimension, px, py, pz, world);
        boolean smokerNear = heldNear(state, Kind.SMOKER, dimension, px, py, pz, WorkbenchRules.bandRadius(latches.smokerHeld), world);
        latches.smokerHeld = Workbenches.any(state, Kind.SMOKER, dimension) ? Boolean.valueOf(smokerNear) : null;
        // a furnace with iron in it takes no meat (CookGate.furnaceHasIron), so it is not "at hand" for the smoker rule. counting it
        // made the far smoker blink on and off as we bobbed over the furnace's line, and the food count blinked with it. ironOwed is
        // not asked here on purpose, it reads the facts and the facts read this
        boolean furnaceAtHand = furnaceNear && !ironInFurnace(state, dimension) || world.inBag(Kind.FURNACE);
        boolean smokerBack = !smokerNear && !furnaceAtHand && walksBack(state, Kind.SMOKER, dimension, px, py, pz, world);
        boolean smokerHeld = WorkbenchRules.plannerSmoker(smokerNear, furnaceAtHand, smokerBack);
        return new StationVerdict(new Station(tableNear || tableBack), new Station(furnaceNear || furnaceBack), new Station(smokerHeld), smokerNear);
    }

    // ore on a plain furnace in this dimension. CookGate.furnaceHasIron calls every job on one ore because the cook waits for food
    // jobs, but a furnace that is cooking meat for us is a booked job too (unitsEach > 0) and it is where the next batch goes once
    // it is out, so it is not iron
    private static boolean ironInFurnace(RunState state, String dimension) {
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if ("furnace".equals(job.kind) && dimension.equals(job.dimension) && job.unitsEach == 0) {
                return true;
            }
        }
        return false;
    }

    // one of the stations this run placed (or is taking back) that is still there and within `radius` of us. the planner counts it
    // as held about where crafting and smelting will walk to it, so the plan and the container tasks can't disagree about whether
    // a second one is needed. an unloaded chunk keeps its station
    private static boolean heldNear(RunState state, Kind kind, String dimension, double px, double py, double pz, double radius, World world) {
        return Workbenches.heldNear(state, kind, dimension, px, py, pz, radius, pos -> world.stands(kind, pos));
    }

    // the walk back half asks DoStuffInContainerTask's own two questions (can the bag make one, would it take that far one), so the
    // planner and StationChoice agree on the block and on the bag
    private static boolean walksBack(RunState state, Kind kind, String dimension, double px, double py, double pz, World world) {
        return walksBack(state, kind, dimension, px, py, pz, 0, world);
    }

    private static boolean walksBack(RunState state, Kind kind, String dimension, double px, double py, double pz, double hold, World world) {
        boolean inBag = world.inBag(kind);
        boolean canMake = !inBag && world.canMake(kind);
        // in the bag is no walk back (KitPlanner counts a table in the bag itself, the furnace's readers ask the bag too)
        return Workbenches.plannerWalksBack(state, kind, dimension, px, py, pz, inBag, canMake, hold, b -> world.walkUsable(kind, b));
    }
}
