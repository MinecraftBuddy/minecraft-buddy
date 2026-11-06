package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.GameClock;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

// every decision about the tables, furnaces and smokers this run put down, with no game in sight (Workbenches is the world half
// that feeds these and acts on what they say). the old rules lived in four places and each priced the walk back differently
// (a path cost of 20, a stretched one of 60, an infinite one, a hand picked 75), which is how a table five blocks away up a
// shaft got forgotten and another one crafted next to it. now there is one distance, NEAR, a straight line in all three axes
public final class WorkbenchRules {
    // "ours is near enough to reuse" and "we are still in its area" are the same number on purpose. height counts: a table 15
    // across and 15 up is 21.2 away and not near, 10 / 10 / 10 is 17.3 and is
    public static final double NEAR = WalkCost.STATION_NEAR;
    // past this (straight line) a station is a lost cause and gets forgotten
    public static final double FORGET_DISTANCE = WalkCost.STATION_FORGET;
    // the next needs of the plan that count as "soon", on top of the current one
    public static final int LOOKAHEAD = 3;
    // outside NEAR this long without a break (5 s) and the station is picked up even if the plan still wants it. a short step
    // outside, to a tree or around a corner, is not a trip back
    public static final long OUTSIDE_TICKS = 100;
    // a block that went down a moment ago gets a second before anything may take it back (the hook that records it, the first
    // tick of the craft)
    public static final long PLACE_GUARD_TICKS = 20;
    // the screen has to have been shut this long. CraftInTableTask closes and reopens it between the steps of one chain
    public static final long SETTLE_TICKS = 20;
    // after a pickup that could not finish, the next one waits this long
    public static final long RETRY_GAP_TICKS = 100;
    // pickups that ran out of time (or could not even start) before the entry is written off
    public static final int MAX_TRIES = 3;
    // one try gets this long (30 s) to break the block. it was the tablePickupSeconds config knob, nobody ever turned it
    public static final long PICKUP_TRY_TICKS = 600;
    // being in another dimension has to last this long before we call the station left behind (a loading screen is not leaving)
    public static final long DIMENSION_TICKS = 20;
    // a pickup nobody ran for this long is not in flight any more: it stops vetoing the placers, and goes back to standing
    public static final long DRIVE_GRACE_TICKS = 100;
    // ...unless the block is already down and only the drop is left, that one gets a good while longer before it is given up
    public static final long DROP_GIVE_UP_TICKS = 1200;
    // a drop pickup that has had nothing to chase for this long (2 s) is exploring for it, and the explore does not stop for
    // anything. the drop is right where the block was or it is gone, a minute of that walks us out to sea
    public static final int DROP_WANDER_TICKS = 40;
    // a station that comes back into NEAR after it was out of it only counts for the planner again once we are well inside
    public static final double RETURN_SHARE = 0.75;
    // furnace and smoker get a smaller latch than the table: once out of NEAR they only count for the planner again this many
    // blocks inside it, so a bot dithering over the line flips the flag once and not every step, and a smoker at 20 blocks is a
    // smoker whichever way we got there. it only ever reaches inward: the planner may say "not held" where the container task would
    // walk to ours (a few cobble too many), but never "held" where the task would make a second one the plan did not budget for
    public static final double LATCH_BAND = 1.0;
    // a table this far away (a straight line) is not worth the walk back when we can craft another one: it is forgotten with a
    // log line instead. a furnace or smoker keeps the long trip up to FORGET_DISTANCE, it cost stone and a table to make
    // (the same line the craft walks back to it, StationChoice.WALK_BACK: past it the task makes a new one, so the old is let go)
    public static final double FAR_TABLE_DISTANCE = StationChoice.WALK_BACK;
    // a block that shows up further away than we can place from did not come from us (another player, a chunk update)
    public static final double PLACE_REACH = 8.0;
    // "never happened" for the tick stamps (game time starts at 0, so -1 is safely before everything)
    public static final long NEVER = -1;
    // two runs of the pickup this close together are one stretch of it (a skipped tick is not a fight)
    public static final long DRIVE_STEP_TICKS = 2;
    // a smelt task had its screen in hand this recently (AsyncSmelting.working) counts as a load in flight
    public static final long LOAD_GRACE_TICKS = 20;
    // "coming back" and the anchor let go only after saying no this long in a row (3 s). the plan leaves FOOD for a tick now and
    // then (a craft slots in, the planner rereads the bag) and that one tick used to start a smoker pickup mid hunt
    public static final long LET_GO_TICKS = 60;

    // a container task says "this station is mine right now" every tick it works at or walks to it (StationHook.touch), and the book
    // believes it for this long after the last time. 40 is two LOAD_GRACE: the tree can be empty for a tick or two between a task and
    // the next one it hands the wheel to (a hold, a chain with the wheel for a fight or a meal), and that gap must not read as the
    // work being over. it is well under DRIVE_GRACE (a pickup nobody drives). the SETTLE after a screen shut runs alongside it, not
    // on top, so an owed bench goes one tick after the lease lapses. it only ever adds to "in use" (idle below)
    public static final long LEASE_TICKS = 40;

    private WorkbenchRules() {
    }

    // somebody touched this bench lately. a clock that went backwards (another world) is no lease, it can not hold a bench for ever
    public static boolean leased(Bench b, long now) {
        return b.leasedTick != NEVER && GameClock.since(b.leasedTick, now) <= LEASE_TICKS;
    }

    // nothing is using the bench: no lease, no screen of its kind open, no furnace load in flight and (the inference this slice does
    // not replace, the lease is OR'd onto it) no container task of its kind anywhere in the tree. the tree scan is per KIND, so with
    // two furnaces standing every one of them is held by a smelt at either; the lease is per station, narrowing that is a follow up
    public static boolean idle(Bench b, long now, boolean screenOpen, boolean loadInFlight, boolean treeWorksAtKind) {
        return !leased(b, now) && !screenOpen && !loadInFlight && !treeWorksAtKind;
    }

    // ---- where the next station comes from (rule 1)
    // ours standing within NEAR, else a world one within NEAR, else the one in the bag, else craft. the container tasks are alto
    // and can't call this class, so the decision itself is util/helpers/StationChoice (fed by StationHook, which Workbenches
    // answers), and the NEAR test itself is in WalkCost

    // blocks in the world (not ours) can pop into existence near us too, so "near" is the only evidence we have of who placed
    // it. measured from the player to the middle of the block
    public static boolean placedByUs(double px, double py, double pz, RunState.Pos block) {
        double dx = block.x + 0.5 - px;
        double dy = block.y + 0.5 - py;
        double dz = block.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz <= PLACE_REACH * PLACE_REACH;
    }

    // the planner counts a station of ours as held inside NEAR, and once it has been out of it only inside a share of NEAR, or the
    // plan flips every time we cross the line (a log trip up, a cobble trip down)
    public static double returnRadius(boolean wasFar) {
        return wasFar ? NEAR * RETURN_SHARE : NEAR;
    }

    // the same latch for a furnace or smoker, a band instead of a share: `held` is last look's answer (null before the first one,
    // where the plain line is the only fair test). held stays held to the line, and not held needs NEAR - band to come back. never
    // outward: a band past the line says "held" where the task puts the bag's one down or skips a parked one and makes another
    public static double bandRadius(Boolean held) {
        return held == null || held ? NEAR : NEAR - LATCH_BAND;
    }

    // the planner's word on a station of ours (the one flag stoneWanted, the kit's furnace skip, CookGate and cookFeasible read) is
    // held inside the band, or Workbenches.plannerWalksBack: anywhere the container task would walk back to it. without the second
    // half the plan asked for 8 cobble for a furnace the smelt task then never made, it walked back to ours

    // the smoker's flag also says "smoker first" to CookGate, FurnaceWatch and comingBack, and that rule is about the smoker next to
    // us: a far one (`walkBack`, plannerWalksBack past the band) never beats a furnace of ours within the band or in the bag. with no
    // furnace at hand either, the smoker is the walk worth making
    public static boolean plannerSmoker(boolean near, boolean furnaceAtHand, boolean walkBack) {
        return near || !furnaceAtHand && walkBack;
    }

    // a table past FAR_TABLE_DISTANCE that the bag can make again: 4 planks, or a log to make them from
    public static boolean canRecraftTable(int planks, int logs) {
        return planks >= 4 || logs >= 1;
    }

    // ---- what needs which station

    // some needs craft inside themselves (the planner never sees those crafts, they are not KitNeeds), so a plan with no craft in
    // it can still want a table. food: hoe, wheat, bread. anything that mines with a tool we do not hold yet: the mining task
    // crafts the pick itself at the table. `holdsPick` / `holdsStonePick` are "any pickaxe of at least that tier in the bag"
    public static boolean needCraftsInside(String need, boolean holdsPick, boolean holdsStonePick) {
        if (need == null) {
            return false;
        }
        return switch (need) {
            case KitNeed.FOOD -> true;
            // wooden pick tier: stone and coal
            case KitPlanner.COBBLE, "coal" -> !holdsPick;
            // the ore needs a stone pick, and that is made at a table
            case "iron_ingot" -> !holdsStonePick;
            default -> false;
        };
    }

    // does this one need use that station: a craft needs a table, a smelt a furnace, a cook a smoker or a furnace (food cooks what
    // it hunts, so it wants the smoker too)
    public static boolean needsStation(Kind kind, String need, boolean holdsPick, boolean holdsStonePick) {
        if (need == null) {
            return false;
        }
        return switch (kind) {
            case TABLE -> KitNeed.isCraftName(need) || needCraftsInside(need, holdsPick, holdsStonePick);
            case FURNACE -> "iron_ingot".equals(need) || KitNeed.COOK_FURNACE.equals(need);
            case SMOKER -> KitNeed.COOK_SMOKER.equals(need) || KitNeed.FOOD.equals(need);
        };
    }

    // the current need or one of the LOOKAHEAD after it wants the station. `plan` is the phase's need list in order, names only
    public static boolean neededSoon(Kind kind, List<String> plan, boolean holdsPick, boolean holdsStonePick) {
        int last = Math.min(plan.size(), 1 + LOOKAHEAD);
        for (int i = 0; i < last; i++) {
            if (needsStation(kind, plan.get(i), holdsPick, holdsStonePick)) {
                return true;
            }
        }
        return false;
    }

    // ---- rule 2 and 4: keep, pick up, forget

    // what the world half saw this tick. distance is the straight line from us to the middle of the block
    // `canRecraft`: the bag can make this kind again right now (only a table asks, see FAR_TABLE_DISTANCE)
    public record Look(long now, double distance, boolean sameDimension, boolean blockGone, boolean idle, boolean holdsStuff, boolean jobHere,
                       boolean neededSoon, boolean canBreak, long pickupLimitTicks, boolean canRecraft) {
        public Look(long now, double distance, boolean sameDimension, boolean blockGone, boolean idle, boolean holdsStuff, boolean jobHere,
                    boolean neededSoon, boolean canBreak, long pickupLimitTicks) {
            this(now, distance, sameDimension, blockGone, idle, holdsStuff, jobHere, neededSoon, canBreak, pickupLimitTicks, false);
        }
    }

    public enum Call {
        // leave it where it is
        KEEP,
        // a job or our items are in it, it is never taken
        BUSY,
        // it should come down but something short holds it (a screen is open, it was just placed, the retry gap)
        WAIT,
        // start taking it back now
        PICK_UP,
        // the block cannot be broken at all (Workbenches.breakable), that is a failed try and three of them forget it
        UNREACHABLE,
        // a pickup is running, keep driving it
        CONTINUE,
        // a pickup is running and the station turned out to hold our stuff
        ABORT_BUSY,
        // the block pickup ran out of its time
        TIMED_OUT,
        // the block is down and the drop never made it into the bag
        DROP_LOST,
        FORGET_GONE,
        FORGET_TOO_FAR,
        // a table out past FAR_TABLE_DISTANCE and we can craft another
        FORGET_FAR_TABLE,
        FORGET_LEFT_DIMENSION,
        // busy in a dimension we left: it stays registered, a visit when we are back takes it down
        ELSEWHERE
    }

    // one station, one tick. moves the bookkeeping on the bench (the outside clock, the dimension clock, BUSY / STANDING) and
    // says what to do about it. the pickup bookkeeping (start, finish, a failed try) is below
    public static Call decide(Bench b, Look in) {
        long now = in.now();
        if (!in.sameDimension()) {
            if (b.otherDimensionSince == NEVER) {
                b.otherDimensionSince = now;
            }
            // a furnace or smoker with something of ours in it stays registered for the visit that takes it down when we are back
            // (the job is what brings us, the entry only has to still be there). the state is last seen's, or a recorded job in
            // that dimension after a relog rebuilt every entry as standing. nothing here forgets it, the run ending does
            if (b.state == Bench.State.BUSY || in.jobHere()) {
                b.state = Bench.State.BUSY;
                return Call.ELSEWHERE;
            }
            // not forgotten at once: the dimension reads wrong for a tick or two around a portal and a loading screen
            return now - b.otherDimensionSince >= DIMENSION_TICKS ? Call.FORGET_LEFT_DIMENSION : Call.KEEP;
        }
        b.otherDimensionSince = NEVER;
        boolean pickingUp = b.state == Bench.State.PICKING_UP;
        // our own break makes the block go away, that is progress and not a forget
        if (in.blockGone() && !pickingUp) {
            return Call.FORGET_GONE;
        }
        // a station with a job in it is not forgotten by distance: the job is what brings us back, and the visit takes the station.
        // holdsStuff counts too, the state is last tick's (and a relog rebuilds every bench as STANDING). so does being in use (a
        // screen open, a craft or smelt of its kind in the tree, a load in flight): it is decided again when that ends, and a job
        // that went stale is dropped on its own clock (FurnacePlan.STALE_TICKS), so nothing holds a bench for ever
        if (in.distance() > FORGET_DISTANCE && b.state != Bench.State.BUSY && !in.holdsStuff() && !in.jobHere() && in.idle()
                && b.anchor == null && b.comingBack == null) {
            return Call.FORGET_TOO_FAR;
        }
        if (pickingUp) {
            return decidePickup(b, in);
        }
        // the outside clock runs whatever the station is doing, so a job that finishes while we were away for a minute does not
        // get another five seconds of grace
        if (in.distance() <= NEAR) {
            b.outsideSince = NEVER;
        } else if (b.outsideSince == NEVER) {
            b.outsideSince = now;
        }
        // seen empty, or with a real job in it again: whatever was given up on is over
        if (!in.holdsStuff() || in.jobHere()) {
            b.clearGivenUp();
        }
        if (in.holdsStuff()) {
            b.state = Bench.State.BUSY;
            return Call.BUSY;
        }
        b.state = Bench.State.STANDING;
        // a parked one remembers we walked off even while it is anchored, so the anchor letting go later does not need a second trip
        if (b.pickupFailed && in.distance() > NEAR) {
            b.leftSinceFail = true;
        }
        // the furnace next to it is cooking (FurnacePlan brings us back) or the plan is about to cook or smelt there, so this one waits
        // for that visit: no far table, no outside clock, no "done with it". the furnace or smoker we are coming back to is kept for
        // the same reason, however far the hunt takes us. when the reason ends this one is decided again (anchorOf / updateAnchor)
        if (b.anchor != null || b.comingBack != null) {
            return Call.KEEP;
        }
        // the tries ran out on time and it still stands. trying again right here is the same walk that just failed three times, so it
        // waits for us to have been out of NEAR and back (the next trip past it). the phase does not wait on it (phaseMayEnd)
        if (b.pickupFailed) {
            if (in.distance() > NEAR) {
                b.leftSinceFail = true;
                return Call.KEEP;
            }
            if (!b.leftSinceFail) {
                return Call.KEEP;
            }
            b.pickupFailed = false;
            b.leftSinceFail = false;
        }
        // out past the far line for the usual five seconds with the wood to craft another: not worth the walk back, whatever the plan
        // wants (the planner no longer counts it as held out there, so it asks for the planks). not while a craft is using it, that
        // craft may be walking to this very table (the task keeps its target out to 48 + HOLD), it goes when the craft ends
        if (b.kind == Kind.TABLE && in.distance() > FAR_TABLE_DISTANCE && in.canRecraft() && in.idle() && outsideLongEnough(b, now)) {
            return Call.FORGET_FAR_TABLE;
        }
        // done with it and the next job is a walk away from it: the plan still wants one of these but not this one, so it comes down
        // while we stand next to it instead of after the walk (doneWith)
        if (in.neededSoon() && !outsideLongEnough(b, now) && !b.leaveNow) {
            return Call.KEEP;
        }
        return gates(b, in);
    }

    // a furnace or smoker of ours with our items in it and no job pointing at it (a load cut off before its job was recorded, a job
    // dropped as stale): nothing would ever visit it and a phase would end around it. it is adopted as a stranded job so the normal
    // collect visit empties it, and the empty-after-visit rule takes it down. only when nothing is working at it: a load in flight
    // records its own job a tick later
    public static boolean adoptable(Bench b, Look in) {
        if (b.kind == Kind.TABLE || b.state == Bench.State.PICKING_UP || !in.sameDimension() || in.blockGone()) {
            return false;
        }
        if (!in.holdsStuff() || in.jobHere() || !in.idle() || b.givenUp) {
            return false;
        }
        long now = in.now();
        if (now - b.placedTick < PLACE_GUARD_TICKS) {
            return false;
        }
        return b.lastUsedTick == NEVER || now - b.lastUsedTick >= SETTLE_TICKS;
    }

    private static Call decidePickup(Bench b, Look in) {
        // only the ticks it had the wheel: off fighting a zombie for a minute does not burn the try
        long running = b.ranTicks;
        if (b.broken) {
            // only the drop is left
            return running > DROP_GIVE_UP_TICKS ? Call.DROP_LOST : Call.CONTINUE;
        }
        // a table pickup that has not broken anything yet and is out past the far line, with the wood to make another (the bag got
        // some since it started): not worth the rest of the walk
        if (b.kind == Kind.TABLE && !in.blockGone() && in.distance() > FAR_TABLE_DISTANCE && in.canRecraft()) {
            return Call.FORGET_FAR_TABLE;
        }
        if (!in.blockGone() && in.jobHere()) {
            return Call.ABORT_BUSY;
        }
        // a block that is already gone is our break landing, the world half notices and moves on to the drop
        if (!in.blockGone() && running > in.pickupLimitTicks()) {
            return Call.TIMED_OUT;
        }
        return Call.CONTINUE;
    }

    // the things that only delay a pickup that is owed. none of them counts as a failed try
    private static Call gates(Bench b, Look in) {
        long now = in.now();
        if (!in.idle()) {
            return Call.WAIT;
        }
        if (now - b.placedTick < PLACE_GUARD_TICKS) {
            return Call.WAIT;
        }
        if (b.lastUsedTick != NEVER && now - b.lastUsedTick < SETTLE_TICKS) {
            return Call.WAIT;
        }
        if (b.retryAt != NEVER && now < b.retryAt) {
            return Call.WAIT;
        }
        return in.canBreak() ? Call.PICK_UP : Call.UNREACHABLE;
    }

    // a pickup that is owed but would START from further than NEAR: not mid-work. that is a dedicated walk back across the map while
    // the work (and the smelt we just loaded) waits, and "nothing wants it" is no reason for a walk. it stays standing with redecide
    // set, gets decided the next time we pass within NEAR, and the 128 forget (or the far-table one) has it if we never do.
    // `lastChance`: the phase has nothing left in its plan and nothing cooking, only the bench holds it (phaseMayEnd), so the walk
    // is the last thing to do anyway. true = hold it this tick
    public static boolean holdFar(Bench b, Look in, boolean lastChance) {
        if (in.distance() <= NEAR || lastChance) {
            b.farHeldLogged = false;
            return false;
        }
        b.redecide = true;
        return true;
    }

    // the phase-end trip has a cap too: past StationChoice.WALK_BACK a station only the phase end wants is not worth the walk across
    // the map, it is forgotten instead (and so stops holding phaseMayEnd). a pickup already under way is not asked
    public static boolean tooFarForLastTrip(Look in, boolean lastChance) {
        return lastChance && in.distance() > StationChoice.WALK_BACK;
    }

    // the phase is only waiting on the benches: no need left in the plan, no job cooking anywhere. a far pickup is allowed then
    public static boolean lastChance(boolean planEmpty, boolean anyJob) {
        return planEmpty && !anyJob;
    }

    public static boolean outsideLongEnough(Bench b, long now) {
        return b.outsideSince != NEVER && now - b.outsideSince >= OUTSIDE_TICKS;
    }

    // plain words for why a pickup started, they go in the log
    public static String reason(Bench b, Look in) {
        if (!in.neededSoon()) {
            return "nothing in the next " + (LOOKAHEAD + 1) + " needs wants the " + b.kind.word();
        }
        if (b.leaveNow && !outsideLongEnough(b, in.now())) {
            return "we are done with it and the next job is more than " + Math.round(NEAR) + " blocks from it";
        }
        return "we have been outside " + Math.round(NEAR) + " blocks of it for " + OUTSIDE_TICKS / 20 + " s";
    }

    // the portal phase's hold: nothing runs while a station of ours is standing and the plan has nothing left to craft at it. but once
    // the gate is done the old cast tree is what runs, and a station it placed itself is in use by that very tree: holding it (a null
    // task) empties the tree, which makes the station idle, which brings it down, and the cast starts over and places it again
    public static boolean portalHold(boolean gateEmpty, boolean mayEnd, boolean gateDone, boolean castInUse) {
        return gateEmpty && !mayEnd && !(gateDone && castInUse);
    }

    // ---- pickup bookkeeping

    public static void beginPickup(Bench b, long now, int bagBefore, String why) {
        b.state = Bench.State.PICKING_UP;
        b.pickupStart = now;
        b.drivenTick = now;
        b.ranTicks = 0;
        b.broken = false;
        b.bagBefore = bagBefore;
        b.why = why;
    }

    // the break phase is over (the block is down), what is left is the drop
    public static void blockBroken(Bench b, long now) {
        b.broken = true;
        // the drop gets its own clock
        b.pickupStart = now;
        b.ranTicks = 0;
    }

    // the world half ran the pickup this tick. a gap since the last run is somebody else's time (a fight, the kit task, a menu), so
    // only back to back ticks add up
    public static void drove(Bench b, long now) {
        if (b.drivenTick != NEVER && now > b.drivenTick && now - b.drivenTick <= DRIVE_STEP_TICKS) {
            b.ranTicks += now - b.drivenTick;
        }
        b.drivenTick = now;
    }

    // the tries ran out on time alone and the block still stands: not forgotten, parked until we come back near it (decide)
    public static void parkFailed(Bench b) {
        b.pickupFailed = true;
        b.leftSinceFail = false;
        b.tries = 0;
    }

    // a try that did not work out (timed out, or could not start). true = that was the last one, forget the station
    public static boolean failedTry(Bench b, long now) {
        b.tries++;
        b.state = Bench.State.STANDING;
        b.pickupStart = NEVER;
        b.broken = false;
        b.retryAt = now + RETRY_GAP_TICKS;
        return b.tries >= MAX_TRIES;
    }

    // the wander streak after one more run of the drop pickup. one run with something to chase starts it over
    public static int dropWanderStreak(int streak, boolean wandering) {
        return wandering ? streak + 1 : 0;
    }

    // why the drop is a lost cause, null = keep collecting. a pickup that gave up on our drop (no room, no path, in water) is not
    // going to change its mind before the ban runs out, and one that keeps wandering found nothing to give up on
    public static String dropGone(String gaveUpReason, int wanderStreak) {
        if (gaveUpReason != null) {
            return "drop gave up (" + gaveUpReason + ")";
        }
        return wanderStreak >= DROP_WANDER_TICKS ? "drop-wandering, nothing to chase for " + wanderStreak + " ticks" : null;
    }

    // the item is back once there is one more than when we began
    public static boolean pickupDone(boolean broken, int bagNow, int bagBefore) {
        return broken && bagNow > bagBefore;
    }

    // ---- done with it: keep or take it, decided standing next to it
    // the outside clock alone meant: place it, use it, walk 21 blocks to the next job, then turn round and walk back for it. now the
    // moment we are done with a station (its screen shut for SETTLE_TICKS and nothing working at it) we look at where the next job
    // is. the outside clock stays as the fallback for when we do not know

    // the distance for "no idea where the next job is"
    public static final double UNKNOWN_SITE = Double.NaN;

    public enum Done {
        // the next job is within NEAR of it, or uses it
        KEEP,
        // we do not know where the next job is: keep it, the outside clock decides later
        KEEP_UNKNOWN,
        // the next job is past NEAR from it, it comes down now
        PICK_UP_FAR,
        // nothing in the next LOOKAHEAD + 1 needs wants this kind, it comes down now
        PICK_UP_UNWANTED,
        // the furnace we kept it for is done and we do not know where the next job is: we are standing next to both, it comes along
        PICK_UP_RELEASED
    }

    // where a need's work happens, as far as the trackers can tell. the world half turns these into blocks or mobs
    public enum Site {
        LOGS, STONE, COAL, IRON, GRAVEL, DIAMOND, SHEEP, ANIMALS,
        // a craft somewhere else, armor, a cook, a name we do not map: unknown
        NONE
    }

    public static Site siteOf(String need) {
        return switch (need == null ? "" : need) {
            // planks come from logs, the bag's own logs are a craft right here, which only makes the answer "keep" a bit early
            case "log", "planks" -> Site.LOGS;
            case KitPlanner.COBBLE, KitNeed.BUILD_BLOCKS -> Site.STONE;
            case "coal" -> Site.COAL;
            case "iron_ingot", "raw_iron" -> Site.IRON;
            case "flint" -> Site.GRAVEL;
            case "diamond" -> Site.DIAMOND;
            case "wool" -> Site.SHEEP;
            case KitNeed.FOOD -> Site.ANIMALS;
            default -> Site.NONE;
        };
    }

    // a need whose work is further than a furnace job may cut in from (FurnacePlan.COLLECT_CUT_IN), or nobody has seen where it is: the
    // walk out and back is what a batch left behind costs. wool only, the sheep are the one site that is both far and a whole outing
    public static boolean farTrip(String need, double siteDistance) {
        return siteOf(need) == Site.SHEEP && (Double.isNaN(siteDistance) || siteDistance > FurnacePlan.COLLECT_CUT_IN);
    }

    // the first look after a use of it settled: same dimension, standing, empty, idle, its screen shut long enough, and this use was
    // not decided yet (one decision per time we used it)
    public static boolean doneUsing(Bench b, Look in) {
        if (!in.sameDimension() || in.blockGone() || in.holdsStuff() || in.jobHere() || !in.idle()) {
            return false;
        }
        // only decided standing near it: from far off any "take it" answer is a trip back across the map. the redecide (or the
        // use) waits for the next time we pass within NEAR
        if (in.distance() > NEAR) {
            return false;
        }
        if (b.state == Bench.State.PICKING_UP || b.state == Bench.State.BUSY || b.anchor != null || b.comingBack != null) {
            return false;
        }
        // the anchor let go: decided again on this look, used or not
        if (b.redecide) {
            return true;
        }
        if (b.lastUsedTick == NEVER || b.doneFor == b.lastUsedTick) {
            return false;
        }
        return in.now() - b.lastUsedTick >= SETTLE_TICKS;
    }

    // `neededSoon` is Look.neededSoon (the current need or one of the LOOKAHEAD after it wants this kind), `nextUsesThis` the next need
    // itself is done at this kind of station (a craft at the table we just used), `nextSite` the straight line from the station to the
    // nearest place the next need's work happens, UNKNOWN_SITE when the trackers have nothing
    public static Done doneWith(boolean neededSoon, boolean nextUsesThis, double nextSite) {
        return doneWith(neededSoon, nextUsesThis, nextSite, false);
    }

    // `released`: the anchor just let go (redecide). we are standing at both, so it stays only when the next job is near and wants it,
    // an unknown next job is not a reason to come back a second time
    public static Done doneWith(boolean neededSoon, boolean nextUsesThis, double nextSite, boolean released) {
        if (!neededSoon) {
            return Done.PICK_UP_UNWANTED;
        }
        if (nextUsesThis) {
            return Done.KEEP;
        }
        if (Double.isNaN(nextSite)) {
            return released ? Done.PICK_UP_RELEASED : Done.KEEP_UNKNOWN;
        }
        return nextSite <= NEAR ? Done.KEEP : Done.PICK_UP_FAR;
    }

    // remember the answer for this use. the far one and the released one change what decide says, the others are what it says anyway
    public static void settleDone(Bench b, Done done) {
        b.doneFor = b.lastUsedTick;
        b.leaveNow = done == Done.PICK_UP_FAR || done == Done.PICK_UP_RELEASED;
        b.redecide = false;
    }

    // ---- anchored: a table next to a furnace that is cooking
    // smelting and off doing other things means we come back to the furnace anyway, so the table beside it is not worth breaking and
    // carrying. one station per kind per anchor (the nearest to it), and the anchor has to be ours, cooking a real job (not a given
    // up one) and in the same dimension

    // a furnace or smoker we are coming back to: cooking a real job (FurnacePlan sends us), or standing with the plan about to cook or
    // smelt in it (comingBack)
    public static boolean anchors(Bench a, Predicate<Bench> hasJob) {
        if (a.kind == Kind.TABLE) {
            return false;
        }
        return a.state == Bench.State.BUSY ? !a.givenUp && hasJob.test(a) : a.state == Bench.State.STANDING && a.comingBack != null;
    }

    // why the plan brings us back to a furnace or smoker of ours soon, null when it does not. the window is the current need and the
    // LOOKAHEAD after it, same as neededSoon. a hunt for food is a cook coming (the meat goes in the smoker, or in the furnace when we
    // have no smoker standing or in the bag, same order as CookGate), an iron need is a smelt
    public static String comingBack(Kind kind, List<String> plan, boolean smokerOfOurs) {
        if (kind == Kind.TABLE) {
            return null;
        }
        int last = Math.min(plan.size(), 1 + LOOKAHEAD);
        for (int i = 0; i < last; i++) {
            String need = plan.get(i);
            if (kind == Kind.SMOKER ? KitNeed.COOK_SMOKER.equals(need) : KitNeed.COOK_FURNACE.equals(need)) {
                return "to cook";
            }
            if (kind == Kind.FURNACE && "iron_ingot".equals(need)) {
                return "to smelt";
            }
        }
        boolean hunting = !plan.isEmpty() && KitNeed.FOOD.equals(plan.get(0));
        return hunting && (kind == Kind.SMOKER || !smokerOfOurs) ? "to cook" : null;
    }

    // the busy station this one waits beside, null for none. only a standing one is anchored, and only the nearest of its kind to
    // that anchor: a second table next to the same furnace follows the normal rules
    public static Bench anchorOf(Bench b, Collection<Bench> benches, Predicate<Bench> hasJob) {
        // tables only: a furnace or smoker is kept by its own job or by comingBack
        if (b.kind != Kind.TABLE || b.state != Bench.State.STANDING) {
            return null;
        }
        Bench best = null;
        for (Bench a : benches) {
            if (a == b || !a.dimension.equals(b.dimension) || !anchors(a, hasJob) || apart(a, b) > NEAR) {
                continue;
            }
            if (nearestOfKindTo(a, b.kind, benches) != b) {
                continue;
            }
            if (best == null || apart(a, b) < apart(best, b)) {
                best = a;
            }
        }
        return best;
    }

    private static Bench nearestOfKindTo(Bench anchor, Kind kind, Collection<Bench> benches) {
        Bench best = null;
        for (Bench c : benches) {
            if (c == anchor || c.kind != kind || c.state != Bench.State.STANDING || !c.dimension.equals(anchor.dimension) || apart(anchor, c) > NEAR) {
                continue;
            }
            if (best == null || apart(anchor, c) < apart(anchor, best)) {
                best = c;
            }
        }
        return best;
    }

    private static double apart(Bench a, Bench b) {
        return WalkCost.distance3d(a.pos.x - b.pos.x, a.pos.y - b.pos.y, a.pos.z - b.pos.z);
    }

    public enum Anchor {
        // nothing changed
        SAME,
        // it waits beside a cooking furnace now
        ANCHORED,
        // the furnace it waited for is done (collected and coming down, gone, forgotten, given up): decide it again on this look
        RELEASED
    }

    // the same for a furnace or smoker's own reason to stay. when it ends the station is decided again on the spot, like a table
    // whose anchor let go
    private static boolean lostLongEnough(long since, long now) {
        return now - since >= LET_GO_TICKS;
    }

    // a "no" only counts once it has lasted LET_GO_TICKS, a yes is believed at once
    public static Anchor updateComingBack(Bench b, String back, long now) {
        String before = b.comingBack;
        if (back != null) {
            b.comingBackLostSince = NEVER;
            b.comingBack = back;
            if (before != null) {
                return Anchor.SAME;
            }
            b.leaveNow = false;
            b.redecide = false;
            return Anchor.ANCHORED;
        }
        if (before == null) {
            return Anchor.SAME;
        }
        if (b.comingBackLostSince == NEVER) {
            b.comingBackLostSince = now;
        }
        if (!lostLongEnough(b.comingBackLostSince, now)) {
            return Anchor.SAME;
        }
        b.comingBack = null;
        b.comingBackLostSince = NEVER;
        b.redecide = true;
        return Anchor.RELEASED;
    }

    // nothing in the bag or the plan's facts has a use for this furnace or smoker: no iron owed (a furnace), no raw meat that would go
    // in it and not short of food (a hunt fills the smoker next; the caller asks against FoodPlan.huntsTo, the highest line the
    // plan hunts to outside a stock-up). these are bag counts, they do not flicker the way the plan's head does
    public static boolean nothingAhead(Kind kind, boolean ironOwed, int rawMeat, boolean smokerOfOurs, boolean foodShort) {
        boolean meatHere = (rawMeat > 0 || foodShort) && (kind == Kind.SMOKER || !smokerOfOurs);
        return kind == Kind.SMOKER ? !meatHere : !ironOwed && !meatHere;
    }

    // the visit that emptied a station keeps it standing for the comingBack reason while the plan still says so, or while it is only
    // being held (the plan said no, the 3 s in updateComingBack are running) and the bag still has something ahead for it: that is
    // the flicker the hold is for, the plan says no for a tick then and the bag does not. held with nothing ahead is the real end,
    // the visit took the last of it, and we are standing right at it, so it comes down now instead of after the hold (by when we
    // may have walked out of NEAR). a plan that still says yes (a stock-up hunt the bag can't see) always keeps it
    public static boolean keepForComingBack(Bench b, boolean nothingAhead) {
        if (b.comingBack == null) {
            return false;
        }
        return b.comingBackLostSince == NEVER || !nothingAhead;
    }

    // the anchor is not flickering, it is done: coming down, in the bag, out of the registry. so is a standing one whose comingBack
    // let go, that answer already sat out its own 3 s, and a second 3 s here walks us out of NEAR before the table is decided again
    public static boolean anchorLeft(Bench was, Collection<Bench> benches) {
        if (was == null) {
            return false;
        }
        return was.state == Bench.State.PICKING_UP || was.state == Bench.State.IN_BAG || !benches.contains(was)
                || was.state == Bench.State.STANDING && was.comingBack == null;
    }

    // moves the anchor along and says what happened, for the one log line each way. a flicker in the furnace's answer is held for
    // LET_GO_TICKS like comingBack, but `anchorLeft` (the furnace is coming down, in the bag, or out of the registry) is not a
    // flicker, the collect visit wants the table decided while we still stand at it
    public static Anchor updateAnchor(Bench b, Bench anchor, long now, boolean anchorLeft) {
        Bench before = b.anchor;
        if (anchor == null && before != null && !anchorLeft) {
            if (b.anchorLostSince == NEVER) {
                b.anchorLostSince = now;
            }
            if (!lostLongEnough(b.anchorLostSince, now)) {
                return Anchor.SAME;
            }
        }
        b.anchorLostSince = NEVER;
        b.anchor = anchor;
        if (before == anchor) {
            return Anchor.SAME;
        }
        if (anchor == null) {
            b.redecide = true;
            return Anchor.RELEASED;
        }
        // a "done, the next job is far" from before it was anchored is not this visit's answer any more
        b.leaveNow = false;
        b.redecide = false;
        return Anchor.ANCHORED;
    }

    // ---- rule 3: the visit that empties a station

    public enum Visit {
        // leave it, something is still cooking in it (or it is not ours to take)
        LEAVE,
        // empty and ours: it comes down now, before we leave it
        PICK_UP,
        // empty, ours, and we carry meat the free station can cook first. the pickup follows the cook
        COOK_THEN_PICK_UP
    }

    public static Visit afterVisit(int inputLeft, boolean ours, boolean otherJobHere, boolean cookHere) {
        if (inputLeft > 0 || !ours || otherJobHere) {
            return Visit.LEAVE;
        }
        return cookHere ? Visit.COOK_THEN_PICK_UP : Visit.PICK_UP;
    }

    // ---- cooking: the smoker wins

    // raw meat goes in a smoker of ours when one stands within NEAR or sits in the bag (walk to it, or put it down). twice as fast
    // as a furnace, and the furnace was busy with the iron anyway
    public static boolean cookInSmoker(boolean smokerStandingNear, boolean smokerInBag) {
        return smokerStandingNear || smokerInBag;
    }

    // the furnace a visit just emptied of iron only takes meat when no smoker of ours is within NEAR or in the bag. a smoker that was
    // just emptied is the smoker, it can always take the next batch. either way the emptied station comes down after (afterVisit)
    public static boolean emptiedStationMayCook(boolean visitedIsSmoker, boolean smokerStandingNear, boolean smokerInBag) {
        return visitedIsSmoker || !cookInSmoker(smokerStandingNear, smokerInBag);
    }

    // ---- rule 5: a pickup and a placement of the same kind never overlap

    // a pickup is in flight and somebody is still driving it
    public static boolean inFlight(Bench b, long now) {
        return b.state == Bench.State.PICKING_UP && b.drivenTick != NEVER && now - b.drivenTick <= DRIVE_GRACE_TICKS;
    }

    // no container task may place or craft a station of this kind while one of them is coming down: the block it would find
    // missing is the one we are breaking, and a new one would stand right where the old one was
    public static boolean placeVetoed(Collection<Bench> benches, Kind kind, long now) {
        for (Bench b : benches) {
            if (b.kind == kind && inFlight(b, now)) {
                return true;
            }
        }
        return false;
    }

    // ...and none may walk to the block that is coming down
    public static boolean targetVetoed(Collection<Bench> benches, RunState.Pos pos, long now) {
        for (Bench b : benches) {
            if (b.pos.equals(pos) && inFlight(b, now)) {
                return true;
            }
        }
        return false;
    }

    // a pickup nobody has run for a while while the block still stands goes back to standing, so a phase that never drives it
    // (or a run that was interrupted for good) cannot hold the veto forever. true when it changed
    public static boolean dropStaleDrive(Bench b, long now) {
        if (b.state != Bench.State.PICKING_UP || b.broken || b.drivenTick == NEVER || now - b.drivenTick <= DRIVE_GRACE_TICKS) {
            return false;
        }
        b.state = Bench.State.STANDING;
        b.pickupStart = NEVER;
        return true;
    }

    // ---- rule 6: a phase may end

    // nothing of ours is standing or coming down in this dimension. called with an empty plan (a phase that is out of needs):
    // an idle station left standing at that point is exactly the one that gets left behind. a busy one with a job is the job's
    // business (the phase waits for those on its own), a busy one with no job is an interrupted load that has not been adopted yet
    // (adoptable) and the phase waits for that. what stands in another dimension stays registered and does not hold this one
    public static boolean phaseMayEnd(Collection<Bench> benches, String dimension, Predicate<Bench> hasJob) {
        for (Bench b : benches) {
            if (!b.dimension.equals(dimension)) {
                continue;
            }
            // a parked one (tries ran out on time, the block still stands) does not hold it either: three tries already failed and
            // the next trip past it gets another go
            if (b.state == Bench.State.STANDING && b.pickupFailed) {
                continue;
            }
            // (a load the stale rule gave up on is not adopted either, so it can't hold the phase: it was never worth a walk)
            if (b.state == Bench.State.STANDING || b.state == Bench.State.PICKING_UP
                    || (b.state == Bench.State.BUSY && !b.givenUp && !hasJob.test(b))) {
                return false;
            }
        }
        return true;
    }

    // the ones phaseMayEnd lets go on purpose: busy in this dimension, no job, and the stale rule gave up on them. they stay standing
    // with our items in them, the world half says so in the log
    public static List<Bench> leftGivenUp(Collection<Bench> benches, String dimension, Predicate<Bench> hasJob) {
        List<Bench> out = new java.util.ArrayList<>();
        for (Bench b : benches) {
            if (b.dimension.equals(dimension) && b.state == Bench.State.BUSY && b.givenUp && !hasJob.test(b)) {
                out.add(b);
            }
        }
        return out;
    }

    // ---- loads

    // a pickup preempts the kit task, and a furnace load is a handful of clicks: ore, then fuel, then the job is recorded. a
    // pickup that started between the ore and the fuel closed the screen on a furnace holding 37 raw iron and no job. so while
    // some container screen is open, or a smelt task touched one a moment ago, no pickup STARTS
    public static boolean loadInFlight(boolean containerScreenOpen, long lastSmeltWork, long now) {
        if (containerScreenOpen) {
            return true;
        }
        return lastSmeltWork >= 0 && now >= lastSmeltWork && now - lastSmeltWork <= LOAD_GRACE_TICKS;
    }
}
