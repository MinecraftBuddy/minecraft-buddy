package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;

import java.util.List;
import java.util.function.Consumer;

// how much food we hold and what every gate makes of it, built once per tick (GamerContext.food). every gate used to do its own sum
// and they drifted: the End gate read the bare bag, two phases kept their own copy of the latch, and two configs both call their
// line minFoodUnits. held is the one valuation: the bag and the furnace screen we have open (raw meat at its cooked value), plus
// what a smoker is cooking for us, minus the cooked value of raw meat nothing can cook (it gets eaten raw, 3 a porkchop and not 8).
// what can cook depends on the phase: GATHER, IRON and PORTAL run the cook, so a standing smoker with fuel and three raw meat
// makes dinner, from the nether on the plan counts on no cook and the meat counts raw, a pending batch or not (GamerPhase.cooks;
// NetherPhase may still cook in place instead of a trip, FoodTripRules.cookHere, and that raises the bag when it happens).
// counted is the same without that correction and is only for "did we get closer", held jumps by a chicken whenever a smoker comes
// or goes. the lines are named so the two minFoodUnits cannot be mixed up: overworldMinimum is 70 (OverworldConfig), endFloor is 24
// (EndConfig), floor is OverworldConfig.minHeldFoodUnits. pure: facts and configs in, numbers out
public final class FoodPlan {
    // a soft top-up shortfall this small is not worth a trip when the raw meat in the bag would close it. 10 is the most a smoker
    // standing nearby can swing the sum by: under CookGate.MIN_RAW the cook is only feasible with a station already there, so that
    // is MIN_RAW - 1 = two raw beef or porkchop at 5 apiece. a smaller cap left two beef churning like the chicken did
    public static final int SMALL_GAP = 10;
    // the End hunt aims this far over its floor, or it would stop on the very unit the gate starts at and flip with every bite
    public static final int END_MARGIN = 8;
    // a trip that looks due (not under the floor) has to stay due this long before it starts. held dips for a second or two when
    // a cook loses the head halfway through a load: the meat sits in a smoker no job points at yet and the rest of the raw meat
    // reads raw, so held drops by half for a moment and a trip started on that ends the tick the job is adopted. a real drain takes
    // minutes, 5 s costs it nothing
    public static final int REFILL_CONFIRM_TICKS = 100;

    private final int bag;
    private final int pending;
    private final int rawLeftOut;
    private final int junk;
    private final int station;
    private final int skipped;
    private final int floor;
    private final int minimum;
    private final int target;
    private final int endFloor;
    private final int refillStart;
    private final int refillStop;
    private final boolean refilling;
    private final boolean surface;
    private final boolean bagFull;
    // a start is due but has not lasted REFILL_CONFIRM_TICKS yet (latched), so it does not count. false off the latch
    private final boolean waiting;

    FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, OverworldConfig ow, EndConfig end) {
        this(bag, pending, rawLeftOut, junk, station, skipped, ow, end, false);
    }

    FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, OverworldConfig ow, EndConfig end, boolean refilling) {
        this(bag, pending, rawLeftOut, junk, station, skipped, ow, end, refilling, false);
    }

    FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, OverworldConfig ow, EndConfig end, boolean refilling,
             boolean surface) {
        this(bag, pending, rawLeftOut, junk, station, skipped, ow, end, refilling, surface, false);
    }

    FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, OverworldConfig ow, EndConfig end, boolean refilling,
             boolean surface, boolean bagFull) {
        // a start over the minimum would be a trip that is done before it begins, so it never goes past it. a stop under the
        // minimum would end the trip before the kit's own need is met, so it never goes under it
        this(bag, pending, rawLeftOut, junk, station, skipped, ow.minHeldFoodUnits, ow.minFoodUnits, ow.targetFoodUnits, end.minFoodUnits,
                Math.min(ow.refillStartFoodUnits, ow.minFoodUnits), Math.max(ow.refillStopFoodUnits, ow.minFoodUnits), refilling, surface,
                bagFull, false);
    }

    private FoodPlan(int bag, int pending, int rawLeftOut, int junk, int station, int skipped, int floor, int minimum, int target,
                     int endFloor, int refillStart, int refillStop, boolean refilling, boolean surface, boolean bagFull, boolean waiting) {
        this.bag = bag;
        this.pending = pending;
        this.rawLeftOut = rawLeftOut;
        this.junk = junk;
        this.station = station;
        this.skipped = skipped;
        this.floor = floor;
        this.minimum = minimum;
        this.target = target;
        this.endFloor = endFloor;
        this.refillStart = refillStart;
        this.refillStop = refillStop;
        this.refilling = refilling;
        this.surface = surface;
        this.bagFull = bagFull;
        this.waiting = waiting;
    }

    // the same bag with the trip ending at the minimum, for the phase-done checks: a phase is done with its food once the kit's
    // need is met, the rest of a trip carries on in the next phase (the latch lives in RunState, not in the phase)
    public FoodPlan atMinimum() {
        return new FoodPlan(bag, pending, rawLeftOut, junk, station, skipped, floor, minimum, target, endFloor, refillStart, minimum, refilling,
                surface, bagFull, waiting);
    }

    // the same bag with a due start still sitting out REFILL_CONFIRM_TICKS
    private FoodPlan waiting() {
        return new FoodPlan(bag, pending, rawLeftOut, junk, station, skipped, floor, minimum, target, endFloor, refillStart, refillStop,
                refilling, surface, bagFull, true);
    }

    // the phase is what says whether a cook can happen at all, and `refilling` whether a food trip is under way
    // (RunState.foodRefilling), so there is no way to ask without either
    public static FoodPlan of(GamerFacts f, GamerConfig cfg, GamerPhase phase, boolean refilling) {
        return of(f, cfg.overworld, cfg.end, phase.cooks(), refilling);
    }

    // `wood` = the tick's budget, so the cook check reads the wood the planner counts instead of working it out again
    public static FoodPlan of(GamerFacts f, GamerConfig cfg, GamerPhase phase, boolean refilling, WoodBudget wood) {
        return of(f, cfg.overworld, cfg.end, phase.cooks(), refilling, wood);
    }

    // `cooksHere` = the phase runs the cook (GamerPhase.cooks). no trip under way, the tests' entry
    public static FoodPlan of(GamerFacts f, OverworldConfig ow, EndConfig end, boolean cooksHere) {
        return of(f, ow, end, cooksHere, false);
    }

    public static FoodPlan of(GamerFacts f, OverworldConfig ow, EndConfig end, boolean cooksHere, boolean refilling) {
        return of(f, ow, end, cooksHere, refilling, null);
    }

    // (null wood = work it out here, only worth doing for a cook check that is actually asked)
    public static FoodPlan of(GamerFacts f, OverworldConfig ow, EndConfig end, boolean cooksHere, boolean refilling, WoodBudget wood) {
        return new FoodPlan(f.foodUnits(), f.pendingFoodUnits(), rawLeftOut(f, ow, end.beds, cooksHere, wood), f.junkFoodUnits(),
                f.stationFoodUnits(), f.stationFoodSkipped(), ow, end, refilling, f.nearSurface(), f.bagFull());
    }

    // the tick's plan, with the refill latch in `state` moved on to what this bag says. the engine's food() and a test context's
    // both come through here, so the latch lives in one place. only the cooking phases have the kit's food need, everywhere else
    // the latch just drops (quietly, FoodFloor has its own). `log` hears the start and the end of a trip
    public static FoodPlan latched(GamerFacts f, GamerConfig cfg, RunState state, Consumer<String> log) {
        return latched(f, cfg, state, log, null);
    }

    public static FoodPlan latched(GamerFacts f, GamerConfig cfg, RunState state, Consumer<String> log, WoodBudget wood) {
        GamerPhase phase = state.phase;
        FoodPlan plan = of(f, cfg, phase, state.foodRefilling, wood);
        // a start has to last REFILL_CONFIRM_TICKS. under the floor never waits, that is not ours to soften
        long now = f.gameTime();
        boolean due = phase.cooks() && !state.foodRefilling && plan.held() >= plan.floor && plan.startDue();
        boolean dipOver = false;
        if (due) {
            // a clock that went backwards (the server's time packets) restarts the wait instead of making it negative
            if (state.foodDueSince < 0 || now < state.foodDueSince) {
                state.foodDueSince = now;
            }
            if (now - state.foodDueSince < REFILL_CONFIRM_TICKS) {
                plan = plan.waiting();
            }
        } else {
            dipOver = state.foodDueSince >= 0;
            state.foodDueSince = -1;
        }
        boolean next = phase.cooks() && plan.nextRefilling();
        if (dipOver && !next && !state.foodRefilling && phase.cooks()) {
            log.accept("food: back to " + plan.held() + " inside " + REFILL_CONFIRM_TICKS / 20 + " s, no trip");
        }
        if (next == state.foodRefilling) {
            return plan;
        }
        if (phase.cooks()) {
            log.accept(plan.refillLine(next));
        }
        state.foodRefilling = next;
        // wantsRefill reads the same on both sides of the flip, the rebuild is only so refilling() tells the truth
        FoodPlan moved = of(f, cfg, phase, next, wood);
        return plan.waiting ? moved.waiting() : moved;
    }

    // for the planners' short entry points, which know the bed count and nothing else of the End. the End lines are the config
    // defaults there, so a caller that reads endFloor() wants the other overload. the planners behind them are the kit's, which
    // run in the cooking phases
    public static FoodPlan ofBeds(GamerFacts f, OverworldConfig ow, int endBeds) {
        EndConfig end = new EndConfig();
        end.beds = endBeds;
        return of(f, ow, end, true);
    }

    // the cooked value of the raw meat that held does not count: all of CookGate.rawGap while no cook can happen, none while one
    // can (the bag figure has it in already, adding it again would pay the same chicken twice). in a phase that never cooks all of it
    // stays out, whatever stands there or cooks there: nobody loads a station and nothing collects one after PORTAL, so a batch
    // still pending is not food we will get and the raw meat behind it never reaches a smoker (CollectFoodTask will not load a
    // second one while the first is out). a standing smoker and fuel are only a dream there
    private static int rawLeftOut(GamerFacts f, OverworldConfig ow, int endBeds, boolean cooksHere, WoodBudget wood) {
        int gap = CookGate.rawGap(f);
        if (gap <= 0) {
            return 0;
        }
        return cooksHere && (wood == null ? CookGate.cookFeasible(f, ow, endBeds) : CookGate.cookFeasible(f, ow, endBeds, wood)) ? 0 : gap;
    }

    // ---- the numbers

    public int held() {
        return bag + pending - rawLeftOut;
    }

    public int counted() {
        return bag + pending;
    }

    // what facts.foodUnits() said: the bag, raw meat at the cooked value, and the open screen's share
    public int bag() {
        return bag;
    }

    public int pending() {
        return pending;
    }

    // the open furnace or smoker screen's share of bag (FoodPlan.inStation), 0 once a job is recorded for that station
    public int station() {
        return station;
    }

    public int rawLeftOut() {
        return rawLeftOut;
    }

    // ---- the lines

    // OverworldConfig.minHeldFoodUnits: under it food always goes first
    public int floor() {
        return floor;
    }

    // OverworldConfig.minFoodUnits: what the phases need held to be done, the HUD line. a trip runs past it to refillStop. not the
    // End's line
    public int overworldMinimum() {
        return minimum;
    }

    // OverworldConfig.refillStartFoodUnits (never over the minimum): a food trip starts under it
    public int refillStart() {
        return refillStart;
    }

    // OverworldConfig.refillStopFoodUnits (never under the minimum): a food trip, once it starts, runs to this. the food need asks
    // for it while a trip is due or under way, so one trip buys a real pile and not the 25 units the old stop at the minimum did
    public int refillStop() {
        return refillStop;
    }

    // the highest line the plan hunts to right now outside a stock-up: the trip's stop while one is due or on, else the target
    public int huntsTo() {
        return wantsRefill() ? Math.max(tripEnd(), target) : target;
    }

    // a food trip is under way, as of the start of this tick (see latched)
    public boolean refilling() {
        return refilling;
    }

    // OverworldConfig.targetFoodUnits: the stock-up at the end of the kit
    public int target() {
        return target;
    }

    // EndConfig.minFoodUnits: the least we walk into the End with. not the overworld's line
    public int endFloor() {
        return endFloor;
    }

    // the two shapes the planner gives the kit's own refill: the whole trip (refillStop), or, under the floor with tools still to
    // make, the floor-level bite that goes ahead of them (KitPlanner.plan). anything else asking for food is a stock-up or a filler
    public boolean isRefillNeed(KitNeed need) {
        return KitNeed.FOOD.equals(need.catalogueName()) && (need.count() == refillStop || (held() < floor && need.count() == floor));
    }

    // a stock-up on top of the target (SmeltFiller's smeltExtras), in the same units
    public int stockUp(int extra) {
        return target + extra;
    }

    // ---- what to ask the food task for

    // CollectFoodTask counts anything edible but held only what we would eat, so with a bag of junk it thinks it is done while
    // the gate stays short and the bot wanders. ask it for the junk on top
    public int collect(int units) {
        return units + junk;
    }

    // the End hunt: the floor and a margin, plus the junk, plus what is still cooking. the hunt must not count a batch any more than
    // the gate does (shortOfEndFloor), but CollectFoodTask books one in a smoker as food on the way and that is not ours to switch
    // off from here: asking for the same number on top cancels it, so the task hunts for units that are really in the bag
    public int endCollect() {
        return endCollect(endFloor);
    }

    // same sum for any line, the prep's top-up hunts to its own
    public int endCollect(int line) {
        // and while a batch is out the task will not cook the raw meat in the bag (one smoker, one slot) but still counts it cooked:
        // without the raw gap on top it thinks it has enough, cannot cook, and wanders instead of hunting. with nothing pending
        // it cooks the meat itself, so the gap is real food there
        return collect(line + END_MARGIN) + pending + (pending > 0 ? rawLeftOut : 0);
    }

    // ---- the verdicts

    public boolean shortOf(int units) {
        return held() < units;
    }

    public boolean shortOfMinimum() {
        return shortOf(minimum);
    }

    // does the kit want its food need in the plan. a trip starts under refillStart (under the minimum on the surface, see
    // surfaceTopUp) and then runs to refillStop,
    // in between with no trip on the bot keeps working: without the gap one bite at 70 made food the head for a single unit.
    // the floor is under the start anyway, the check is for a config that put it higher. reads the same whether the latch was
    // moved on this tick or not (under the start is on either way, between the lines the latch says, over the stop is off),
    // so nobody has to care who asked first
    public boolean wantsRefill() {
        int held = held();
        return held < floor || (!waiting && startDue()) || (refilling && held < tripEnd());
    }

    // the latch for the next tick: on from under the start (or the floor, or the surface top-up) until the stop
    public boolean nextRefilling() {
        int held = held();
        return held < tripEnd() && (refilling || held < floor || (!waiting && startDue()));
    }

    // a trip would start here (the floor aside): under refillStart, or the surface top-up
    boolean startDue() {
        return held() < refillStart || surfaceTopUp();
    }

    // where a trip ends: refillStop, or the minimum once the bag has no room. past the minimum more food would cost a slot of
    // something we carry on purpose (the hunt throws whatever the task is not using), under it the kit needs it anyway
    public int tripEnd() {
        return bagFull ? minimum : refillStop;
    }

    // where the last stop before the nether ends: `entry` (never under the trip's stop), or the minimum on a full bag, same reason
    // as tripEnd
    public int entryEnd(int entry) {
        return bagFull ? minimum : Math.max(entry, refillStop);
    }

    // up top and short of the minimum: the trip costs a walk here, and the same trip from a mine later is a climb out and back.
    // so on the surface a trip starts under the minimum, in a mine only under refillStart. a small hole the raw meat in the bag
    // covers once cooked is not one (coveredAt, the smoker coming and going swings held by a chicken right at this line). a big
    // pile of raw meat that reads raw because the fuel dipped under it IS a trip on purpose: CollectFoodTask counts the pile cooked,
    // skips the hunt and goes straight to the smoker, which fetches its own fuel. nothing else asks for that fuel
    public boolean surfaceTopUp() {
        int held = held();
        // with no room in the bag the trip would end at the minimum it started under, one bite's worth of hunt
        return surface && !bagFull && held >= floor && held < minimum && !coveredAt(minimum);
    }

    // the log line when the latch flips, `on` = it starts
    public String refillLine(boolean on) {
        if (!on) {
            return "food: refill done at " + held() + (bagFull && held() < refillStop ? ", the bag is full" : "");
        }
        int held = held();
        String why = held < Math.max(refillStart, floor) ? "under " + Math.max(refillStart, floor) : "on the surface, under " + minimum;
        return "food: refilling from " + held + " (" + why + "), up to " + refillStop;
    }

    // the End gate. food still cooking is not ours yet: END_PREP never goes back to a furnace, so a batch in a smoker is food we would
    // walk away from. everything else is held (raw meat nothing will cook is already at its raw value there, see rawLeftOut)
    public boolean shortOfEndFloor() {
        return shortOfEnd(endFloor);
    }

    // the same count against any line
    public boolean shortOfEnd(int line) {
        return held() - pending < line;
    }

    // which side of the two overworld lines held is on (0 under the floor, 1 between, 2 at the minimum or over), for the log line
    public int band() {
        return held() < floor ? 0 : held() < minimum ? 1 : 2;
    }

    // an empty bag and the trip on the table is not food: under the floor, and `trip` (the furnace job a trip is under way for, or
    // the one the plan would send us to, null = none) is not something we eat. IronActivity reads it, an iron trip or a detour never
    // takes the wheel from the food then. food in a station IS the fastest food, cooked or the cold meat a pickup brings back, so
    // a trip for that keeps the wheel like it always did
    public boolean starving(RunState.FurnaceJob trip) {
        return held() < floor && (trip == null || trip.unitsEach <= 0);
    }

    // the soft top-up has nothing left to do: the hole under the trip's stop is small and the raw meat in the bag, cooked, fills it. one
    // raw chicken is 2 units raw and 6 cooked, and held counts it at 6 only while a smoker stands nearby (a cook is feasible then),
    // so picking the smoker up or putting it down moves the sum by 4. a top-up that starts and stops on 4 units the bag already
    // holds is pure churn. the cap is the reason the raw valuation exists at all: ten raw porkchop is a 50 unit gap, and that one
    // still gets a real hunt. under the floor never counts, that rule is not ours to soften
    public boolean covered() {
        return coveredAt(refillStop);
    }

    // the same rule against any line
    boolean coveredAt(int line) {
        int held = held();
        return held >= floor && held < line && line - held <= SMALL_GAP && held + rawLeftOut >= line;
    }

    // does the food need go first in the IRON phase. always below the floor, otherwise only during a refill (wantsRefill, between
    // the start and the minimum with no trip on there is no trip to lead) and only where a top-up is cheap, which is the
    // surface (a cook or a craft being next in a mine is not a cheap moment, the hunt is a climb either way), and once one starts
    // it carries on to the trip's stop. `topUp` is one already under way (nextTopUp), `cookBusy` a cook that picked its station and is
    // still loading it or somebody's furnace screen (FoodGate.cookBusy): the soft top-up waits for that, the meat in the station
    // is food too
    public boolean leads(boolean onSurface, boolean topUp, boolean cookBusy) {
        int held = held();
        if (held < floor) {
            return true;
        }
        if (held >= refillStop || cookBusy || covered() || !wantsRefill()) {
            return false;
        }
        return topUp || onSurface;
    }

    // the floor's hysteresis. raw meat only counts while a cook can happen, a standing smoker only counts while the plan is coming
    // back to it, and the plan only does that while food leads. so the hunt starts, the meat counts, the bag reads over the floor,
    // the lead lets go, the meat stops counting, and round we go. a hunt with the wheel keeps it until the floor is met without
    // the meat that only counts because we are hunting
    public boolean keepsLead(boolean led, int rawGap) {
        return led && counted() - rawGap < floor;
    }

    // a top-up that started on the soft rule keeps going until the trip's stop, it must not flip every tick as the bot walks up and
    // down or the count wobbles at the line. under the floor it was forced anyway, that does not start one. one that is covered
    // ends: leads() already says no for it, and a latch left on would wake up off the surface the next time the raw meat is eaten.
    // with no refill under way there is nothing to top up either
    public boolean nextTopUp(boolean topUp, boolean leads) {
        int held = held();
        if (held >= refillStop || covered() || !wantsRefill()) {
            return false;
        }
        return topUp || (leads && held >= floor);
    }

    // the food floor of the phases with no food need of their own (FoodFloor): starts under the floor and carries on to the
    // minimum, flipping at the line would stop the hunt halfway. no covered() here on purpose, those phases never run the cook,
    // so a hole is not closed by raw meat that only counts once it is cooked. (held is phase aware for the same reason: no raw
    // meat at the cooked value in a phase that never cooks, covered() just stays out of it)
    public boolean floorNext(boolean active) {
        if (held() >= minimum) {
            return false;
        }
        return active || held() < floor;
    }

    // the line KitRunner logs when the food need shows up in or drops out of the plan. `units` is what the need asks for (the
    // minimum when there is none). the numbers are the ones held is made of, so a flip is readable straight off two lines
    public String line(boolean on, int units, String running) {
        return "food: need " + (on ? "on" : "off") + ", held " + held() + " of " + units + " (station " + station + ", pending " + pending
                + ", raw left out " + rawLeftOut + "), running " + (running == null ? "nothing" : running)
                + (skipped > 0 ? ", " + skipped + " was already in the open screen, not counted" : "");
    }

    // ---- the furnace screen we have open (what facts.foodUnits() adds to the bag)

    // food in the slots of a furnace or smoker screen we have open. a load is the bag emptying into the station a stack at a time,
    // and for those ticks the count dipped under the floor and sent the bot for cows with the smoker half full. once the job is
    // recorded the pending sum has it, so it is not counted twice. only THIS station's job counts for that: smoker A being
    // recorded says nothing about the meat going into furnace B, and the any-job rule dropped B's meat from the sum (the bag read
    // as short and the top-up went hunting with a full furnace). `screenAt` null = we do not know which block the screen is, then
    // any food job still hides it (the safe answer for double counting)
    public static int inStation(int stationUnits, List<RunState.FurnaceJob> jobs, RunState.Pos screenAt, String dimension) {
        return inStation(stationUnits, jobs, screenAt, dimension, 0);
    }

    // `leftover` is what the station already held when the food task opened it (see leftover below), 0 for anybody else's screen.
    // that part is not food we have, it is the reason the task walked up, and counting it ended the need that opened the screen
    public static int inStation(int stationUnits, List<RunState.FurnaceJob> jobs, RunState.Pos screenAt, String dimension, int leftover) {
        if (stationUnits <= 0) {
            return 0;
        }
        for (RunState.FurnaceJob job : jobs) {
            if (job.count * job.unitsEach <= 0) {
                continue;
            }
            if (screenAt == null || (job.pos.equals(screenAt) && job.dimension.equals(dimension))) {
                return 0;
            }
        }
        return Math.max(0, stationUnits - Math.max(0, leftover));
    }

    // what an open station screen counts as food. cooked output always, raw input only once something is cooking it: the cook
    // loads the meat before the fuel, and meat sitting in a cold smoker counted as held food, which turned the need off before
    // the fuel went in (the fuel slot covers the tick or two before the lit flag syncs)
    public static int stationUnits(int rawUnits, int cookedUnits, boolean lit, boolean hasFuel) {
        return cookedUnits + (lit || hasFuel ? rawUnits : 0);
    }

    // the food in the slots of a screen the food task opened that is not the task's own work. the first look (`before` -1) is what
    // was there, after that it only goes down: the task takes the output out and the bag gets it, and what it puts in on top is
    // the bag emptying into the station, the dip inStation exists to hide. `synced` = the slots have arrived, an empty look at a
    // screen a tick after it opened would put the baseline at 0 and the cooked output under it would count after all. -1 = nothing
    // to subtract (no screen, not the food task's, slots not here yet)
    public static int leftover(int before, int stationUnits, boolean synced, boolean foodTaskRunning) {
        if (!synced || !foodTaskRunning) {
            return -1;
        }
        return before < 0 ? stationUnits : Math.min(before, stationUnits);
    }
}
