package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.DeathRules;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.util.helpers.GameClock;
import baritone.api.utils.Dimension;

// the pure half of the trip back for a nether death. we respawn in the overworld, the pile in the nether stops aging the
// moment nobody is there to load it, so it is still waiting when we come back through the portal. no world in here:
// NetherRecoverTask feeds it numbers, GamerTask asks it whether a death is worth a trip at all
public final class NetherTripRules {

    // the walk hands over to the pile task this close. the pile task has its own standoff for whatever killed us, and that
    // looks 16 blocks out, so the hand over has to be further out than that or we walk into the crowd before it can look
    public static final int ARRIVE_BLOCKS = 20;
    // how close the walk itself tries to get, a little inside ARRIVE_BLOCKS or baritone calls it done a hair outside the line
    public static final int WALK_RANGE = 16;
    // a failed trip that is still in the nether walks home for this long before we hand the run back as it is
    public static final int HOME_SECONDS = 120;
    // a short kit has nothing to do in the nether, so the walk home gets much longer before we let go of it
    public static final int HOME_SHORT_SECONDS = 600;
    // a second death this close to the last pile is the same hazard, not a new pile
    public static final int SAME_SPOT_BLOCKS = 8;
    // the overworld stages run on their own clocks, the trip budget only starts at the nether (the pile does not age before that).
    // the pick gets the same cap the pile task gives itself
    public static final int PICK_SECONDS = (int) RecoverRules.PICK_SECONDS;
    public static final int PORTAL_SECONDS = 300;
    // the portal walk has to close this many blocks (3d, a horizontal number lies when the portal is under a mountain) in this
    // long or it is not getting there
    public static final int PORTAL_PROGRESS_BLOCKS = 8;
    public static final int PORTAL_PROGRESS_SECONDS = 60;
    // the nether walk gets longer: going round a lava ocean is legit, a bridge that went nowhere is not
    public static final int WALK_PROGRESS_SECONDS = 120;
    // and standing on one spot (STILL_BLOCKS) with no path executing is not going round a lava ocean, it is a ledge with a
    // pathfinder that keeps saying no: that gets a much shorter bar. heal holds and digs are not standing, they reset it
    public static final int WALK_STILL_SECONDS = 30;
    public static final double STILL_BLOCKS = 1.5;

    // the trip goes into the nether with something to eat: under FOOD_MIN we stop for a meal first, the food task works up to
    // FOOD_WANTED, and after FOOD_SECONDS of it the trip goes with whatever it found (any food at all) or does not go
    public static final int FOOD_MIN = 20;
    public static final int FOOD_WANTED = 30;
    public static final int FOOD_SECONDS = 120;
    // and with the hearts to survive the first volley on the other side. regen without food is slow, so the wait is capped
    public static final float HEAL_TO = 18;
    public static final int HEAL_SECONDS = 60;
    public static final long HEAL_REGEN_GRACE_TICKS = 20;
    // when the heal minute runs out, under this the trip does not go: walking into the nether at 5 hp with no food is just a
    // second death. between this and HEAL_TO it goes anyway, the wait was only ever for comfort
    public static final float GO_HP = 14;
    // in the nether: under this, with nobody shooting at us, the walk stands still and heals (up to HEAL_TO). the cap is for
    // the whole trip, not per hold. food at 0 means regen is not happening and a minute of standing is all it would get
    public static final float WALK_HEAL_BELOW = 12;
    public static final int WALK_HEAL_SECONDS = 60;
    // and at this hp, with a mob having hit us this lately, the walk is over: heading for the pile at 8 hp under fire is the death
    public static final float ABORT_HP = 8;
    public static final long ABORT_HIT_TICKS = 200;

    // a pile further than this (flat) from the home portal is not a naked walk: the respawn kit has no armor and the last
    // naked trips that far all died on the way. the pile waits (it does not age unloaded) for a kit that is whole
    public static final int DEFER_BLOCKS = 64;
    // a deferred pile this close to where we stand in the nether is worth the walk with the kit on, further is not on the way
    public static final int DEFERRED_REACH_BLOCKS = 300;

    private NetherTripRules() {
    }

    // the pile is too far out to walk to naked
    public static boolean defers(double pileFlatDistanceFromHome) {
        return pileFlatDistanceFromHome > DEFER_BLOCKS;
    }

    // what a far pile has to hold to be put off for later instead of dropped: the stuff the eyes are made of. iron, armor and
    // the rest come back with the rebuild, rods and pearls are the hours
    // (no bag seen at all reads as nothing in it)
    public static boolean defersFor(DeathRules.Worth lastBag) {
        return lastBag != null && lastBag.eyeParts();
    }

    // in the nether with the kit on and a deferred pile in reach: this is the trip we put off
    public static boolean startsDeferred(Dimension now, boolean kitShort, double flatDistanceToPile) {
        return now == Dimension.NETHER && !kitShort && flatDistanceToPile <= DEFERRED_REACH_BLOCKS;
    }

    // in the nether with the kit on and the pile out of reach: it is not coming up on this trip, forget it
    public static boolean dropsDeferred(Dimension now, boolean kitShort, double flatDistanceToPile) {
        return now == Dimension.NETHER && !kitShort && flatDistanceToPile > DEFERRED_REACH_BLOCKS;
    }

    // the overworld food of ours (a smoker we loaded before the nether) is picked up once, in the stages after the gear and before
    // the portal: the FOOD stage then judges the bag with it in. the detour gets this long in all, then the trip goes without
    public static final int COLLECT_SECONDS = 180;

    public static boolean collectsFoodIn(Stage stage) {
        return stage == Stage.FOOD || stage == Stage.HEAL || stage == Stage.PORTAL;
    }

    // the pickup is still allowed: not done, and the detour has not used up its time (-1 = not started)
    public static boolean collectingFood(boolean done, long startedTick, long now) {
        return !done && (startedTick < 0 || now - startedTick <= COLLECT_SECONDS * 20L);
    }

    // the nether walk stands still to heal: hurt, no shooter has a line on us (a standing target is a dead one), and the
    // minute is not used up. holding = we are already standing for it, which keeps going to HEAL_TO instead of flip-flopping
    // at the line every time a heart comes back. no regen (hungry, nothing to eat) means standing heals nothing, so it walks
    public static boolean walkHold(float health, boolean shooterSees, boolean canRegen, boolean holding, long heldTicks) {
        if (shooterSees || !canRegen || heldTicks >= WALK_HEAL_SECONDS * 20L) {
            return false;
        }
        return health < (holding ? HEAL_TO : WALK_HEAL_BELOW);
    }

    // the walk is over: low and a mob hit us a moment ago. ticksSinceHit is how long since the last hit from a mob or an arrow
    public static boolean walkAbort(float health, long ticksSinceHit) {
        return health <= ABORT_HP && ticksSinceHit <= ABORT_HIT_TICKS;
    }

    // why we died, as far as the pile cares: lava eats item entities and the void takes them
    public enum Cause {
        LAVA,
        VOID,
        OTHER;
    }

    public static Cause cause(boolean inLava, boolean outOfTheWorld) {
        if (inLava) {
            return Cause.LAVA;
        }
        return outOfTheWorld ? Cause.VOID : Cause.OTHER;
    }

    public enum Stage {
        // by hand, in the overworld
        BLOCKS,
        // no stone pick or no sword on us: get them, the way down to the portal and the netherrack on the far side both want the
        // pick, and the sword is what the nether crowd gets answered with
        PICK,
        // under FOOD_MIN food on us: a meal first, in the overworld
        FOOD,
        // hurt: wait for the hearts, in the overworld
        HEAL,
        // to the home portal and through it
        PORTAL,
        // across the nether to the pile
        WALK,
        // at the pile, picking up
        RECOVER,
        // the pile gave armor back and it is still in the bag
        WEAR,
        // gave up while still in the nether without a kit: walk back to the overworld so the rebuild can start
        HOME;

        public static Stage parse(String name) {
            if (name != null) {
                for (Stage s : values()) {
                    if (s.name().equals(name)) {
                        return s;
                    }
                }
            }
            return null;
        }
    }

    // ---- eligibility

    // a nether death that left us in the overworld: the only shape of death this trip is for. a death where we respawned
    // in the nether (an anchor) is the plain same dimension recovery
    public static boolean applies(Dimension died, Dimension now) {
        return died == Dimension.NETHER && now == Dimension.OVERWORLD;
    }

    // a naked respawn at night does not walk the open surface for blocks and a pick: the trip waits in the hole or the bed. only
    // while it is still gathering (BLOCKS), later stages are already on their way and the portal is the shelter
    public static boolean hides(Stage stage, Dimension now, boolean darkAndBare) {
        return stage == Stage.BLOCKS && now == Dimension.OVERWORLD && darkAndBare;
    }

    // ticks the stage clock moves up for a hide tick: everything since the last hidden tick, so a fight in the middle does not
    // eat the stage either. nothing hidden yet, or a clock that went backwards, credits nothing
    public static long hiddenCredit(long lastHiddenTick, long now) {
        return lastHiddenTick == Long.MIN_VALUE ? 0 : Math.max(0, now - lastHiddenTick);
    }

    // null = go for it, otherwise the reason it is not worth it (said in the log, so it reads as a sentence)
    public static String refuse(Cause cause, boolean homeKnown, boolean enabled, boolean tripPending, boolean pileTried,
                                boolean kitIntact) {
        if (!enabled) {
            return "the trip is switched off";
        }
        if (tripPending) {
            return "already on a trip, this is a second death";
        }
        // keepInventory, or whatever else left the kit on us: nothing down there we need
        if (kitIntact) {
            return "the kit is still on us";
        }
        if (cause == Cause.LAVA) {
            return "it was lava";
        }
        if (cause == Cause.VOID) {
            return "it was the void";
        }
        if (!homeKnown) {
            return "no portal to go back through";
        }
        if (pileTried) {
            return "already went for this pile";
        }
        return null;
    }

    // the pile of the last trip, near enough that it is the same spot. nothing tried yet = false
    public static boolean tried(RunState.Pos last, int x, int z) {
        if (last == null) {
            return false;
        }
        long dx = (long) last.x - x;
        long dz = (long) last.z - z;
        return dx * dx + dz * dz <= (long) SAME_SPOT_BLOCKS * SAME_SPOT_BLOCKS;
    }

    // game time since we first stood in the nether, 0 until then. what the trip budget counts
    public static long netherTicks(long arrivedTick, long now) {
        return arrivedTick <= 0 ? 0 : now - arrivedTick;
    }

    // how far back the clock may jump before it is another world and not the server correcting the client (GameClock owns it)
    public static final long CLOCK_JITTER_TICKS = GameClock.JITTER_TICKS;

    // pushes the nether arrival back by a payback, never past now: a payback stamped under a clock that then got corrected
    // backwards would leave the trip arriving in the future
    public static long paidBack(long arrivedTick, long credit, long now) {
        return Math.min(arrivedTick + credit, now);
    }

    // the walk was not ticking for a while (combat or food had the wheel): start the no progress clock over
    public static boolean markStale(long lastSeenTick, long now) {
        return now - lastSeenTick > 40;
    }

    // a restock dig is not time the trip spent walking: ticks to push the nether arrival back by, 0 when the dig was not
    // running last tick either (first tick of a dig, or a fight in between)
    public static long restockCredit(long lastDigTick, long now) {
        long gap = now - lastDigTick;
        return gap > 0 && gap <= 40 ? gap : 0;
    }

    // the portal walk got far enough in since the mark to count as moving
    public static boolean closedIn(double markDistance, double distance) {
        return markDistance - distance >= PORTAL_PROGRESS_BLOCKS;
    }

    // ---- the stages

    // the numbers from the config, in ticks. homeTicks is fixed
    public record Limits(long tripTicks, int blocksWanted, long blocksTicks, long homeTicks, long pickTicks, long portalTicks,
                         long progressTicks) {
        public static Limits of(int tripSeconds, int blocksWanted, int blocksSeconds) {
            return new Limits(tripSeconds * 20L, blocksWanted, blocksSeconds * 20L, HOME_SECONDS * 20L, PICK_SECONDS * 20L,
                    PORTAL_SECONDS * 20L, PORTAL_PROGRESS_SECONDS * 20L);
        }
    }

    // everything one decision looks at. tripTicks is game time since we reached the nether (netherTicks, 0 before that),
    // stageTicks since this stage began, stuckTicks since the portal walk (or the nether walk, with its own bar) last closed PORTAL_PROGRESS_BLOCKS
    public record Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                         double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits,
                         boolean hasPick, long stuckTicks, int foodUnits, float health, long ticksSinceHit, boolean hasSword,
                         long stillTicks, boolean canRegen) {
        // healing is possible: the shape every test that does not care about it uses
        public Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                      double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits,
                      boolean hasPick, long stuckTicks, int foodUnits, float health, long ticksSinceHit, boolean hasSword,
                      long stillTicks) {
            this(stage, dim, tripTicks, stageTicks, buildBlocks, homeGone, pileDistance, recoverFinished, itemsGained, kitShort,
                    toWear, limits, hasPick, stuckTicks, foodUnits, health, ticksSinceHit, hasSword, stillTicks, true);
        }

        // a sword (or an axe) in the bag, and not standing still: nothing to say about either
        public Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                      double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits,
                      boolean hasPick, long stuckTicks, int foodUnits, float health, long ticksSinceHit) {
            this(stage, dim, tripTicks, stageTicks, buildBlocks, homeGone, pileDistance, recoverFinished, itemsGained, kitShort,
                    toWear, limits, hasPick, stuckTicks, foodUnits, health, ticksSinceHit, true, 0, true);
        }

        // the common shape: a pick in the bag and a walk that is going fine
        public Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                      double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits) {
            this(stage, dim, tripTicks, stageTicks, buildBlocks, homeGone, pileDistance, recoverFinished, itemsGained, kitShort,
                    toWear, limits, true, 0);
        }

        // and a body that needs nothing: fed, full, nobody has hit us
        public Inputs(Stage stage, Dimension dim, long tripTicks, long stageTicks, int buildBlocks, boolean homeGone,
                      double pileDistance, boolean recoverFinished, int itemsGained, boolean kitShort, int toWear, Limits limits,
                      boolean hasPick, long stuckTicks) {
            this(stage, dim, tripTicks, stageTicks, buildBlocks, homeGone, pileDistance, recoverFinished, itemsGained, kitShort,
                    toWear, limits, hasPick, stuckTicks, 100, 20, Long.MAX_VALUE);
        }
    }

    // stage = where the trip goes from here, null when it is over. giveUp = why, set exactly when this step is a give up
    // (the stage can still be HOME then). recovered = the pile came back with something and the trip is done
    public record Step(Stage stage, String giveUp, boolean recovered) {
    }

    public static Step next(Inputs in) {
        Stage st = in.stage();
        if (st == Stage.HOME) {
            long cap = in.kitShort() ? Math.max(in.limits().homeTicks(), HOME_SHORT_SECONDS * 20L) : in.limits().homeTicks();
            boolean back = in.dim() == Dimension.OVERWORLD || in.stageTicks() > cap;
            return back ? over() : stay(st);
        }
        // the clock going way backwards is another world, not a trip with all its time left. a tick or two is just the
        // server resyncing game time under a client that ran ahead
        if (in.tripTicks() < -CLOCK_JITTER_TICKS || in.stageTicks() < -CLOCK_JITTER_TICKS
                || in.tripTicks() > in.limits().tripTicks()) {
            return giveUp(in, "out of time");
        }
        return switch (st) {
            case BLOCKS -> blocks(in);
            case PICK -> pick(in);
            case FOOD -> food(in);
            case HEAL -> heal(in);
            case PORTAL -> portal(in);
            case WALK -> walk(in);
            case RECOVER -> recover(in);
            case WEAR -> wear(in);
            default -> over();
        };
    }

    private static Step blocks(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get blocks");
        }
        // short of the count after a minute is still worth going with: the blocks only make the walk safer
        if (in.buildBlocks() >= in.limits().blocksWanted() || in.stageTicks() > in.limits().blocksTicks()) {
            return new Step(geared(in) ? afterGear(in) : Stage.PICK, null, false);
        }
        return stay(Stage.BLOCKS);
    }

    // no height gate: an 88 block shaft through deepslate does not go with bare hands or wood, and the portal chunk may not
    // even be loaded from here to look at
    private static Step pick(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get a pickaxe");
        }
        if (geared(in)) {
            return new Step(afterGear(in), null, false);
        }
        if (in.stageTicks() > in.limits().pickTicks()) {
            return giveUp(in, "no pickaxe or sword in time");
        }
        return stay(Stage.PICK);
    }

    // a stone pick for the way down and a sword for whatever is waiting at the bottom
    private static boolean geared(Inputs in) {
        return in.hasPick() && in.hasSword();
    }

    // the overworld gear stages are done: a meal if the bag is short, hearts if we are hurt, then the portal
    private static Stage afterGear(Inputs in) {
        return in.foodUnits() < FOOD_MIN ? Stage.FOOD : afterFood(in);
    }

    private static Stage afterFood(Inputs in) {
        return in.health() < HEAL_TO ? Stage.HEAL : Stage.PORTAL;
    }

    // the food task works up to FOOD_WANTED. out of time with something to eat is a go (a short bag beats none: the bite chain
    // and the heal hold both need it), out of time with nothing is a trip into the nether to starve, so it does not go
    private static Step food(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to get food");
        }
        if (in.foodUnits() >= FOOD_WANTED) {
            return new Step(afterFood(in), null, false);
        }
        if (in.stageTicks() > FOOD_SECONDS * 20L) {
            return in.foodUnits() > 0 ? new Step(afterFood(in), null, false) : giveUp(in, "no food for the trip");
        }
        return stay(Stage.FOOD);
    }

    private static Step heal(Inputs in) {
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "not in the overworld to heal");
        }
        if (in.health() >= HEAL_TO) {
            return new Step(Stage.PORTAL, null, false);
        }
        // nothing to heal with: the minute would be spent standing, so decide. the food answer is cached and trails a fresh
        // pickup by a few ticks, so the first second of the stage does not get to give up on it
        if ((!in.canRegen() && in.stageTicks() > HEAL_REGEN_GRACE_TICKS) || in.stageTicks() > HEAL_SECONDS * 20L) {
            return in.health() < GO_HP ? giveUp(in, "too hurt to go back") : new Step(Stage.PORTAL, null, false);
        }
        return stay(Stage.HEAL);
    }

    private static Step portal(Inputs in) {
        if (in.dim() == Dimension.NETHER) {
            return new Step(Stage.WALK, null, false);
        }
        if (in.dim() != Dimension.OVERWORLD) {
            return giveUp(in, "ended up in the wrong dimension");
        }
        if (in.homeGone()) {
            return giveUp(in, "the portal is gone");
        }
        if (in.stageTicks() > in.limits().portalTicks()) {
            return giveUp(in, "the portal took too long");
        }
        if (in.stuckTicks() > in.limits().progressTicks()) {
            return giveUp(in, "no closer to the portal");
        }
        return stay(Stage.PORTAL);
    }

    private static Step walk(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        if (in.pileDistance() <= ARRIVE_BLOCKS) {
            return new Step(Stage.RECOVER, null, false);
        }
        // giveUp only walks home when the kit is gone, a geared trip (a deferred one) just ends where it stands
        if (walkAbort(in.health(), in.ticksSinceHit())) {
            return giveUp(in, "too hurt to keep walking");
        }
        if (in.stuckTicks() > WALK_PROGRESS_SECONDS * 20L || in.stillTicks() > WALK_STILL_SECONDS * 20L) {
            return giveUp(in, "no closer to the pile");
        }
        return stay(Stage.WALK);
    }

    private static Step recover(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        if (!in.recoverFinished()) {
            return stay(Stage.RECOVER);
        }
        // armor in the bag counts for nothing until it is on, so wear it before judging the kit. and before the "nothing came back"
        // check too: the gain is measured off the bag's low point, and a pile that only gave armor can read as nothing
        if (in.toWear() > 0) {
            return new Step(Stage.WEAR, null, false);
        }
        return pileVerdict(in);
    }

    private static Step wear(Inputs in) {
        if (in.dim() != Dimension.NETHER) {
            return giveUp(in, "left the nether");
        }
        // no clock of its own: a piece still in the bag is a piece we have, and the trip budget above ends this if it never goes on
        if (in.toWear() > 0) {
            return stay(Stage.WEAR);
        }
        return pileVerdict(in);
    }

    private static Step pileVerdict(Inputs in) {
        if (in.itemsGained() <= 0) {
            return giveUp(in, "nothing left at the pile");
        }
        return verdict(in);
    }

    // something came back but maybe not the kit: the phase machine only rebuilds from the overworld, so a pile without the
    // kit is the same as an empty one (giveUp walks home from the nether)
    private static Step verdict(Inputs in) {
        return in.kitShort() ? giveUp(in, "the pile did not have the kit") : new Step(null, null, true);
    }

    private static Step stay(Stage st) {
        return new Step(st, null, false);
    }

    private static Step over() {
        return new Step(null, null, false);
    }

    // still in the nether with the kit gone: the phase machine only rebuilds from the overworld, so go home first. a kit that
    // is fine (or the overworld already) just ends the trip and the machine carries on
    private static Step giveUp(Inputs in, String why) {
        boolean stranded = in.dim() == Dimension.NETHER && in.kitShort();
        return new Step(stranded ? Stage.HOME : null, why, false);
    }
}
