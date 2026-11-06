package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.BlazeDisengageTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.CookRawFoodTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.CookTrip;
import adris.altoclef.tasks.speedrun.gamer.DeathZones;
import adris.altoclef.tasks.speedrun.gamer.DetourSpec;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.EyeMath;
import adris.altoclef.tasks.speedrun.gamer.FoodFloor;
import adris.altoclef.tasks.speedrun.gamer.FoodPickup;
import adris.altoclef.tasks.speedrun.gamer.FoodTripRules;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.NetherGold;
import adris.altoclef.tasks.speedrun.gamer.NetherPick;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PiglinGold;
import adris.altoclef.tasks.speedrun.gamer.ResourceDetour;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.CampPinger;
import adris.altoclef.tasks.speedrun.gamer.tasks.EscapeRun;
import adris.altoclef.tasks.speedrun.gamer.tasks.FindNetherStructureTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherBlockRestock;
import adris.altoclef.tasks.speedrun.gamer.tasks.GhastWatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.HoldLatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.HomePortalWalk;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherGround;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherGoldTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherPickTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.tasks.speedrun.gamer.tasks.PearlHuntTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.RodsWatch;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasks.speedrun.gamer.tasks.WalkProgress;
import adris.altoclef.tasks.speedrun.gamer.tasks.WearFromBag;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.GameClock;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// fortress -> blaze rods -> ender pearls, all inside the Nether. the order matters: rods are the part that can be
// camped for, pearls are the part that can be hunted for. what we SAW (fortress, bastions, a warped forest) is kept in
// RunState so a relog or a retry does not forget it; the sub step this tick is derived from the inventory and that memory,
// not stored, so there is no step to get out of sync with
public class NetherPhase implements PhaseHandler {
    private static final Block[] FORTRESS = {Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS};
    // plain blackstone is not here on purpose: basalt deltas are full of it and would read as bastions all day
    private static final Block[] BASTION = {Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE};
    private static final Block[] WARPED = {Blocks.WARPED_NYLIUM};
    private static final Block[] TRACKED = {Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.WARPED_NYLIUM, Blocks.NETHER_GOLD_ORE};
    // blocks in #guarded_by_piglins that sit around the nether (the tag also has chests and gold blocks, nobody breaks those by accident)
    private static final Block[] GUARDED = {Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE};
    // the known-ore look is a walk over every tracked gold ore, twice a second is plenty
    private static final int ORE_LOOK_TICKS = 10;
    private static final int GUARD_HOLD_TICKS = 20;
    // 15 s, three chops' worth of smoker
    private static final int COOK_QUIET_TICKS = 300;
    // a knockback or a misstep away. two down as well: a floor that thin over lava is no floor
    private static final int COOK_LAVA_REACH = 2;

    private static final int SCAN_EVERY_TICKS = 6;
    // the nearest this many tracked blocks of a kind get asked about, the tracker can hold thousands of bricks
    private static final int SCAN_CANDIDATES = 40;
    private static final double CAMP_PING_SECONDS = 30;
    // an enderman that ducked out of view still counts for this long, so the pearl step does not flip every other tick
    private static final double ENDERMAN_HOLD_SECONDS = 6;
    // a known fortress further than this from us is worth a walk before the rods task starts wandering for bricks
    private static final double FORTRESS_NEAR_BLOCKS = 48;
    private static final double FORTRESS_WALK_SECONDS = 150;
    private static final double FLEE_SECONDS = 10;
    private static final int FLEE_BLOCKS = 48;

    private NetherSweepPlanner planner;
    private RodsWatch rodsWatch;
    private CampPinger campPinger;
    private GhastWatch ghastWatch;
    private HoldLatch endermanLatch;
    // the latch above as of this tick, read by everything that asks "is an enderman about" (updated once at the top of tick)
    private boolean endermanHere;
    // game time as of the last tick, for the hunt's freshness check (stallSeconds gets no context)
    private long lastGameTime;
    private GetToXZTask walkToFortress;
    private RunState.Pos fortressWalkTo;
    private double fortressWalkSince;
    private RunState.Pos fortressWalkGivenUp;
    // both walks (to a fortress we know, to a spawner we picked) are only worth it while they get closer, see WalkProgress
    private final WalkProgress fortressProgress = new WalkProgress();
    private final WalkProgress spawnerProgress = new WalkProgress();
    private FindNetherStructureTask findFortress;
    private FindNetherStructureTask findWarped;
    // the one block dig for the whole phase (walks to the fortress and the spawner spend the bag, not just the sweep)
    private NetherBlockRestock restock = new NetherBlockRestock();
    private CollectBlazeRodsTask rodsTask;
    // the game time rodsTask was last the task we returned. its camping and fight flags only update while it ticks, so a
    // reading older than a few ticks says nothing about now
    private long rodsTaskRanAt = Long.MIN_VALUE / 2;
    private int rodsTaskTarget;
    // the last-rod walk away from blazes that are still shooting, and whether it already ran for this batch of rods
    private BlazeDisengageTask disengage;
    private boolean disengageDone;
    // game time of the first tick the rods were in, MIN_VALUE while they are not
    private long disengageSince = Long.MIN_VALUE;
    // how long after the last rod a looking blaze still starts the walk away. the pick up lands mid volley, not on a calm tick
    private static final int DISENGAGE_WINDOW_TICKS = 40;
    private PearlHuntTask hunt;
    // the game tick huntStep last ran on, so a hunt left behind by another step doesn't keep the long leash
    private long huntTickedAt = Long.MIN_VALUE / 2;
    private static final long HUNT_FRESH_TICKS = 40;
    private int huntTarget;
    private final HomePortalWalk homeWalk = new HomePortalWalk();
    // the bag ran dry out here: home through the portal, the overworld's food floor, and back (FoodTripRules). rods, pearls and the
    // sweep all live in RunState, so none of it is lost on the way
    private final FoodFloor foodFloor = new FoodFloor();
    private DefaultGoToDimensionTask goHome;
    // the smoker food waiting up top is collected before the hunt, and the engine leaves the benches to us while that runs
    private final FoodPickup foodPickup = new FoodPickup();
    private boolean pickingUpFood;
    // bag units when the trip got up top, so the end of the overworld half can tell if it brought food (FoodTripRules.refundsTrip)
    private int tripStartUnits;
    private boolean foodTrip;
    // why the live trip started, and the trips used per reason: a blocks trip that came back empty handed leaves the food budget
    // alone and the other way round
    private FoodTripRules.Reason tripReason = FoodTripRules.Reason.FOOD;
    private int foodTrips;
    // refunds given this phase entry, so a trip that keeps coming back with a thin 12 units cannot restart for ever
    private int tripRefunds;
    private int blockTrips;
    // the walk home: progress mark, last tick it ran, and its own dig for blocks (fresh per trip so an old cooldown does not carry)
    private FoodTripRules.Mark homeMark;
    private long homeLastTick;
    private NetherBlockRestock homeRestock = new NetherBlockRestock();
    // last game tick a blaze could see us, we were burning or hurt (FoodTripRules.unsafe). a food trip waits for it to be 3 s old.
    // read off the blazes directly: the rods task is not always the one ticking, so BlazeFight's mode can be stale or absent
    private long lastUnsafeTick = -1;
    // the overworld half's clock, set by what was short when we got up there
    private double tripSeconds = FoodTripRules.MAX_SECONDS;
    // the overworld half also brings blocks: a blocks trip always, a food trip when the bag was empty of them on the way out (or it
    // would come back and go straight home again)
    private boolean tripBlocks;
    private double foodTripStart;
    // the trip's clocks run on game ticks: the phase clock stands still while holdsBudget is true, which is the whole trip
    private long tripStartTick;
    private long tripGainTick;
    private int tripBestUnits;
    // where this trip ends (FoodTripRules.tripEndUnits), 0 = the plan's stop
    private int tripEndUnits;
    // some part of a food trip is going on right now (the nether walk home, the overworld half, the walk back down)
    private boolean tripAway;
    // when the trip left the nether (foodTripStart is re-stamped once we are up top): the whole trip is paused out of the rods budget
    private double tripLeftAt;
    private boolean foodTripUp;
    // the game tick the last trip ended on a stalled walk home (-1 none): the next one waits FoodTripRules.COOLDOWN_TICKS
    private long tripEndedTick = -1;
    private long lastNoTripLog = -1;
    // the raw meat cooking right here instead of a trip (FoodTripRules.cookHere), null when none. the task is kept across a fight
    private CookRawFoodTask cook;
    private double cookStart;
    private int cookUnits;
    private long cookGainTick;
    // ticks at the open smoker with no meat left in the bag and nothing new coming out
    private int cookQuiet;
    // food units at the last look up top, so a gain pings the watchdog (-1 = no look yet)
    private int lastTripUnits = -1;
    // the live trip already came back from a watchdog retry once
    private boolean tripResumed;
    private int foodDeathsSeen;
    // flint from soul sand valley gravel, only while it is still owed (no flint and steel, fire charge or flint on us)
    private final ResourceDetour gravel = ResourceDetour.gravel();
    // a barter ticked this recently is still going (see gravelSide)
    private static final double TRADE_FRESH_SECONDS = 1;
    // a pick craft that fell over (an input went missing, or it hung) is not tried again for this long
    private static final double PICK_RETRY_SECONDS = 60;
    private static final int PICK_TRIES = 3;
    private static final double SWORD_BUSY_RANGE = 32;
    private Task boots;
    // the pick we are making from the bag (see pickSide) and whether we already said there is nothing to make one from
    private NetherPickTask pickTask;
    private double pickRetryAt;
    private int pickFails;
    private boolean noPickSaid;
    // same for the sword (see swordSide): a long pearl hunt wears one out, and nothing in the nether makes a new one
    private NetherPickTask swordTask;
    private double swordRetryAt;
    private int swordFails;
    private TradeWithPiglinsTask trade;
    private int tradeTarget;
    // the gold piece fallback (goldSide): up to NetherGold.MAX_TRIES tries a visit, a latched one re-arms NetherGold.RETRY_SECONDS later and a death resets
    // them. goldOver = this try is done or gave up, goldStartedAt is the try's clock (-1 =
    // not begun), guarding = gold ore is on baritone's don't-break list because a piglin is close enough to be angered by it
    private NetherGoldTask goldTask;
    private int goldNeed;
    private Task goldWear;
    private Item goldWearing;
    private double goldStartedAt;
    private boolean goldOver;
    private boolean guarding;
    private static final double PLACE_PENALTY = 20;
    private BotBehaviour.State placingLevel;
    private int guardHold;
    private BlockPos goldAnchor;
    private boolean oreLeft;
    private int lastNuggets;
    // when goldOver latched (-1 = not yet noted), tries begun this visit, and the death count the tries were counted under
    private double goldOverAt = -1;
    private int goldTries;
    private int goldDeathsSeen;

    private boolean tracking;
    private boolean failedAlready;
    private boolean warpedSearchOver;
    private int tickCounter;
    private int rotation;
    private int lastPearls;
    private double huntWanderSince = -1;
    private double huntSeen = -1;
    private double barterSpent;
    private double barterTickAt = -1;
    private int lastHurt;
    private Task flee;
    private double fleeUntil;
    private String hudState;
    // the bridge off a portal that floats over nothing, see EscapeRun
    private final EscapeRun escapeRun = new EscapeRun();

    @Override
    public GamerPhase phase() {
        return GamerPhase.NETHER;
    }

    // trips are not the nether's business: the walk home, the overworld half and the walk back have their own limits
    // (stall, HARD_SECONDS), and they used to eat half of the 40 minutes and restart the phase
    @Override
    public boolean holdsBudget() {
        return tripAway;
    }

    // only a food trip home may be cut by a night. a fresh portal at dusk or a respawn walk still goes on into the nether
    @Override
    public boolean nightHold() {
        return !foodTrip;
    }

    @Override
    public String hud() {
        return GamerPhase.NETHER.hud();
    }

    @Override
    public boolean ownsBenches() {
        return pickingUpFood;
    }

    @Override
    public void onRespawn() {
        foodPickup.respawned();
        pickingUpFood = false;
    }

    @Override
    public String hudState() {
        return hudState;
    }

    // pure: what we hold decides, plus "the budget is nearly over" which only needs the game clock
    @Override
    public ResourceDetour detour() {
        return gravel;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        // not with dinner in the smoker. cookStep's no gain clock is what stops this holding the phase for long
        return cook == null && EyeMath.canLeaveNether(facts, cfg, state, budgetOver(facts, state, cfg) || state.netherRodsGaveUp);
    }

    static boolean budgetOver(GamerFacts facts, RunState state, GamerConfig cfg) {
        if (state.phaseEnteredGameTime <= 0) {
            return false;
        }
        double limit = cfg.budgets.nether * 60 - cfg.nether.budgetGraceSeconds;
        return (facts.gameTime() - state.phaseEnteredGameTime) / 20.0 >= limit;
    }

    // a death in the Nether leaves us in the overworld with an empty bag: re-plan the kit instead of walking into the
    // Nether again with nothing
    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return NetherRegress.fromOverworld(facts, cfg);
    }

    // the budget or the stall timer fired: leave with the floor if we have it, else try again, else give up
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (EyeMath.canLeaveNether(ctx.facts(), ctx.cfg(), ctx.state(), true)) {
            return Timeout.SKIP;
        }
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        RunState s = ctx.state();
        NetherConfig n = ctx.cfg().nether;
        planner = new NetherSweepPlanner(s, n);
        planner.setGround(new NetherGround(mod, () -> n.waypointShiftBlocks));
        planner.setLog(Debug::logInternal);
        rodsWatch = new RodsWatch(n.spawnerCampMinutes * 60, n.spawnerDryMinutes * 60, n.rodsBudgetMinutes * 60);
        campPinger = new CampPinger(CAMP_PING_SECONDS);
        ghastWatch = new GhastWatch(2, n.ghastWindowSeconds);
        endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);
        endermanHere = false;
        walkToFortress = null;
        fortressWalkTo = null;
        fortressWalkGivenUp = null;
        fortressProgress.reset();
        spawnerProgress.reset();
        findFortress = new FindNetherStructureTask(planner, Goal.FORTRESS, ctx::secondsInPhase);
        findWarped = new FindNetherStructureTask(planner, Goal.WARPED, ctx::secondsInPhase);
        restock = new NetherBlockRestock();
        homeWalk.reset();
        foodFloor.reset();
        cook = null;
        goHome = null;
        // a retry that lands in the overworld with the hunt half of a trip in flight: that trip is not over, so it is kept
        // (below) instead of cleared and walked straight back down with an empty bag
        boolean upTop = ctx.facts().dimension() != Dimension.NETHER;
        boolean tripWasUp = foodTrip && foodTripUp && !FoodTripRules.countersReset(ctx.attempt());
        FoodTripRules.Reason keptReason = tripReason;
        boolean keptBlocks = tripBlocks;
        foodTrip = false;
        tripReason = FoodTripRules.Reason.FOOD;
        tripBlocks = false;
        // the trip budget is per visit: a watchdog retry is the same visit and keeps it, only a fresh entry refills it. the
        // overworld half of a trip runs inside this phase, so coming back down never lands here
        if (FoodTripRules.countersReset(ctx.attempt())) {
            foodTrips = 0;
            tripRefunds = 0;
            blockTrips = 0;
            tripEndedTick = -1;
            tripResumed = false;
        }
        lastUnsafeTick = -1;
        foodTripUp = false;
        lastTripUnits = -1;
        if (FoodTripRules.resume(upTop, tripWasUp, tripResumed, FoodFloor.tripFoodShort(ctx, tripEndUnits) || (keptBlocks && FoodFloor.blocksShort(ctx)))) {
            // same trip, same budget: nothing is counted. once per trip, so a hunt that stalls for some other reason still
            // gets handed back to the retry rules after one more go. the clock starts over because the phase's did
            tripResumed = true;
            goHome = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            homeMark = null;
            foodTrip = true;
            tripReason = keptReason;
            tripBlocks = keptBlocks;
            foodTripUp = true;
            foodTripStart = ctx.secondsInPhase();
            tripStartTick = ctx.facts().gameTime();
            tripGainTick = tripStartTick;
            tripBestUnits = FoodTripRules.units(ctx.food());
            tripLeftAt = foodTripStart;
            tripSeconds = FoodTripRules.seconds(FoodFloor.foodShort(ctx), tripBlocks && FoodFloor.blocksShort(ctx));
            tripStartUnits = FoodTripRules.units(ctx.food());
            foodPickup.reset();
        }
        foodDeathsSeen = ctx.state().deaths.size();
        rodsTask = null;
        disengage = null;
        disengageDone = false;
        disengageSince = Long.MIN_VALUE;
        hunt = null;
        trade = null;
        boots = null;
        pickTask = null;
        pickRetryAt = 0;
        pickFails = 0;
        noPickSaid = false;
        swordTask = null;
        swordRetryAt = 0;
        swordFails = 0;
        goldTask = null;
        goldWear = null;
        goldWearing = null;
        goldStartedAt = -1;
        goldOver = false;
        goldOverAt = -1;
        goldTries = 0;
        goldDeathsSeen = ctx.state().deaths.size();
        goldAnchor = null;
        oreLeft = false;
        lastNuggets = 0;
        unguard(mod);
        placingOff(mod);
        flee = null;
        fleeUntil = 0;
        escapeRun.reset();
        lastHurt = 0;
        lastPearls = 0;
        rotation = 0;
        failedAlready = false;
        warpedSearchOver = false;
        huntWanderSince = -1;
        huntSeen = -1;
        barterSpent = 0;
        barterTickAt = -1;
        hudState = null;
        gravel.reset();
        ensureTracking(mod);
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        unguard(mod);
        placingOff(mod);
        homeWalk.stopTracking(mod);
        gravel.reset();
        if (tracking) {
            mod.getBlockTracker().stopTracking(TRACKED);
            tracking = false;
        }
    }

    // a pearl hunt from the nook stands in one hole waiting for endermen, which is not standing still for nothing. kills ping
    // the watchdog (see PearlHuntTask), stares do not
    @Override
    public double stallSeconds() {
        // the whole hunt gets the nook's longer leash, not just the nook: a few minutes between kills while the endermen near us
        // all stand by lava is a normal stretch of a hunt, not a stuck bot (huntWanderMinutes still ends a hunt that gets nothing)
        boolean hunting = hunt != null && lastGameTime - huntTickedAt <= HUNT_FRESH_TICKS;
        return EndermanNook.stallSeconds(hunting, PhaseHandler.super.stallSeconds());
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (planner == null) {
            onEnter(mod, ctx);
        }
        noteRespawn(ctx);
        pickingUpFood = false;
        lastGameTime = ctx.facts().gameTime();
        tripAway = foodTrip || ctx.facts().dimension() != Dimension.NETHER;
        if (ctx.facts().dimension() != Dimension.NETHER) {
            // a death or a trip home does not run onExit, the gold ore must not stay off limits in the overworld
            unguard(mod);
            placingOff(mod);
            escapeRun.reset();
            // a smoker in the nether is no use from up here
            cook = null;
            if (foodTrip) {
                // the hunt's clock starts when we are up here, not when we left the fortress
                if (!foodTripUp) {
                    foodTripUp = true;
                    foodTripStart = ctx.secondsInPhase();
                    tripStartTick = ctx.facts().gameTime();
                    tripGainTick = tripStartTick;
                    tripBestUnits = FoodTripRules.units(ctx.food());
                    tripSeconds = FoodTripRules.seconds(FoodFloor.foodShort(ctx), tripBlocks && FoodFloor.blocksShort(ctx));
                    tripStartUnits = FoodTripRules.units(ctx.food());
                    foodPickup.reset();
                }
                // over when nothing is short any more: food in the bag at the overworld minimum (what is still cooking is not in
                // it) and, on a blocks trip, the entry stack held. a kill or a pickup raises the count, and the hunt is the one
                // thing up here that should keep the watchdog quiet
                int units = FoodTripRules.units(ctx.food());
                if (FoodTripRules.gained(lastTripUnits, units)) {
                    ctx.progress("food");
                }
                // the hold wants a new high, not the climb back from a meal (80 -> 72 -> 80 is no progress)
                if (units > tripBestUnits) {
                    tripBestUnits = units;
                    tripGainTick = ctx.facts().gameTime();
                }
                lastTripUnits = units;
                // what we cooked at home goes in the bag before anything is hunted for
                Task cooked = foodPickup.tick(mod, ctx);
                pickingUpFood = cooked != null;
                if (cooked != null) {
                    hudState = foodPickup.hud() == null ? "Collecting the food we cooked" : foodPickup.hud();
                    return cooked;
                }
                Task hunt = foodFloor.tickTrip(ctx, tripBlocks, tripEndUnits);
                long gameNow = ctx.facts().gameTime();
                if (!FoodTripRules.tripOver(hunt != null, GameClock.since(tripStartTick, gameNow) / 20.0, tripSeconds,
                        GameClock.since(tripGainTick, gameNow) / 20.0)) {
                    hudState = foodFloor.hud();
                    return hunt;
                }
                // a trip that came back with food did its job, so the next dry spell gets its trips too. a hunt still going when the
                // clock ran out counts as well: the bag is what says it worked, and a trip that gained plenty used to keep its
                // spot on the cap. a few refunds per entry, a thin trip over and over is not a bag that is getting somewhere
                if (tripRefunds < FoodTripRules.MAX_REFUNDS
                        && FoodTripRules.refundsTrip(tripReason, tripStartUnits, FoodTripRules.units(ctx.food()))) {
                    foodTrips = Math.max(0, foodTrips - 1);
                    tripRefunds++;
                }
                foodTrip = false;
                foodTripUp = false;
                // the minutes spent at home are not the rods step's fault
                rodsWatch.pause(ctx.secondsInPhase() - tripLeftAt);
                // a bag that just got filled should not start the next trip off the first dip (the under-floor bypass still can)
                tripEndedTick = ctx.facts().gameTime();
                foodFloor.reset();
                ctx.progress(tripBlocks ? "blocks trip done" : "food trip done");
            }
            return toTheNether(mod, ctx);
        }
        placingOn(mod);
        homeWalk.stopTracking(mod);
        ensureTracking(mod);
        ReturnPhase.recordPortal(mod, ctx.state());
        rememberOverworldPortal(mod, ctx);
        double now = ctx.secondsInPhase();
        // once per tick before anything reads it: the pick and gravel detours ask too, off the raw ray they flipped every tick
        endermanHere = endermanLatch.update(PearlHuntTask.endermanNear(mod), now);
        // before the cover, whatever path that one walks must not break gold either
        boolean piglins = guardGold(mod);
        noteUnsafe(mod, ctx);
        Task cover = ghastCover(mod, now, escapeRun.running());
        if (cover != null) {
            gravel.preempted(ctx.facts().gameTime());
            return cover;
        }
        // above the trips, the sweep and the side jobs: nothing out here is worth doing while we are stuck on a slab
        Task out = escapeRun.step(mod, true, ctx.facts().gameTime(), ctx::log, ctx::progress);
        if (escapeRun.lavaLake()) {
            // the machine exits us from inside this call, so nothing below runs
            ctx.giveUp("spawned over a lava lake", "Unlucky, we spawned over a lava lake, and I could not get any further");
            return null;
        }
        if (out != null) {
            hudState = escapeRun.hud();
            return out;
        }
        Task home = foodTripHome(mod, ctx);
        if (home != null) {
            return home;
        }
        if (tickCounter++ % SCAN_EVERY_TICKS == 0) {
            scanSights(mod, ctx);
        }
        Task away = disengageStep(mod, ctx);
        if (away != null) {
            return away;
        }
        Task flint = gravelSide(mod, ctx, now);
        if (flint != null) {
            return flint;
        }
        Task pick = pickSide(mod, ctx, now);
        if (pick != null) {
            return pick;
        }
        Task sword = swordSide(mod, ctx, now);
        if (sword != null) {
            return sword;
        }
        Task gold = goldSide(mod, ctx, now, piglins);
        if (gold != null) {
            return gold;
        }
        return nextStep(mod, ctx, now);
    }

    // flat blocks to the way home, 0 when it is not known (the trip does not start then anyway)
    private static double portalDistance(AltoClef mod, GamerContext ctx) {
        Player p = mod.getPlayer();
        RunState.Pos known = ctx.state().netherPortal;
        double px;
        double pz;
        if (known != null) {
            px = known.x;
            pz = known.z;
        } else {
            Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
            if (used.isEmpty()) {
                return 0;
            }
            px = used.get().getX();
            pz = used.get().getZ();
        }
        return Math.hypot(p.getX() - px, p.getZ() - pz);
    }

    // runs every tick off the trip so the "3 s calm" has something to measure. only the line crossed matters, but the blaze that
    // saw us a second before it was crossed counts. same call and range as BlazeFight's scan
    private void noteUnsafe(AltoClef mod, GamerContext ctx) {
        if (foodTrip || foodTrips >= FoodTripRules.MAX_TRIPS) {
            return;
        }
        Player p = mod.getPlayer();
        if (FoodTripRules.unsafe(blazeSeesUs(mod, p), p.isOnFire(), p.getHealth())) {
            lastUnsafeTick = ctx.facts().gameTime();
        }
    }

    private static boolean blazeSeesUs(AltoClef mod, Player p) {
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return false;
        }
        for (Blaze blaze : blazes) {
            if (blaze.isAlive() && p.distanceTo(blaze) <= BlazeFightRules.SIGHT_RANGE
                    && LookHelper.seesPlayer(blaze, p, BlazeFightRules.SIGHT_RANGE)) {
                return true;
            }
        }
        return false;
    }

    // no trip with the bag under the floor is the one thing that must never be silent: one line per 30 s with every gate
    private void logNoTrip(GamerContext ctx, long tick, int units, double portalDistance, boolean portalKnown, boolean deathTrip) {
        if (!FoodTripRules.underFloor(units, portalDistance) || (lastNoTripLog >= 0 && GameClock.since(lastNoTripLog, tick) < 600)) {
            return;
        }
        lastNoTripLog = tick;
        ctx.log("nether: no food trip with " + units + " units: portalKnown=" + portalKnown + " deathTrip=" + deathTrip + " trips=" + foodTrips
                + "/" + FoodTripRules.MAX_TRIPS + " dry=" + FoodTripRules.MAX_TRIPS_DRY + " cooling=" + FoodTripRules.coolingDown(tick, tripEndedTick)
                + " dist=" + (int) portalDistance);
    }

    // the walk home for food. the fight rules stay the combat chain's, this only picks the portal. no known home portal = today's behaviour
    private Task foodTripHome(AltoClef mod, GamerContext ctx) {
        double now = ctx.secondsInPhase();
        if (!foodTrip) {
            // ahead of everything below: with the meat in the slot the bag reads empty, and the line would send us home on that
            Task cooking = cookStep(mod, ctx, now);
            if (cooking != null) {
                return cooking;
            }
            // the nether side of the pair is the way home (ReturnPhase.recordPortal keeps state.netherPortal), the overworld one is
            // where we come out
            boolean portalKnown = ctx.state().netherPortal != null
                    || mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER).isPresent();
            boolean deathTrip = ctx.state().netherTrip != null;
            FoodTripRules.Reason why = null;
            double portalDistance = portalDistance(mod, ctx);
            int units = FoodTripRules.units(ctx.food());
            // due is not the same as go: walking out of cover mid fight is how the last trip died, so it waits for a calm spell
            // unless the bag is nearly empty. waiting touches nothing (no trip, no budget, no clock), it just falls through. a
            // blocks trip is the same walk home (and tops the food up on the way), so it waits too and a food trip that is
            // waiting does not hand over to it
            long tick = ctx.facts().gameTime();
            // a cook starts whatever the last trip's cooldown says, that one is about walking the same wall twice
            if (!deathTrip && cookWanted(ctx, units, portalDistance)
                    && FoodTripRules.mayStart(FoodTripRules.underFloor(units, portalDistance), tick, lastUnsafeTick)) {
                if (cookSpotOk(mod)) {
                    return startCook(ctx, units, portalDistance, now);
                }
                // keep moving and cook on the next decent floor, the floor line still sends us home if that never comes
                if (!FoodTripRules.underFloor(units, portalDistance)) {
                    if (lastNoTripLog < 0 || GameClock.since(lastNoTripLog, tick) >= 600) {
                        lastNoTripLog = tick;
                        ctx.log("nether: not cooking on a bridge or next to lava, food " + units + ", looking for better floor");
                    }
                    return null;
                }
            }
            // the last walk home stalled: the next try waits out the cooldown instead of walking the same wall again
            if (FoodTripRules.coolingDown(tick, tripEndedTick) && !FoodTripRules.underFloor(units, portalDistance)) {
                return null;
            }
            if (FoodTripRules.goHome(units, portalDistance, portalKnown, deathTrip, foodTrips)) {
                if (FoodTripRules.mayStart(FoodTripRules.underFloor(units, portalDistance), tick, lastUnsafeTick)) {
                    why = FoodTripRules.Reason.FOOD;
                }
            } else if (FoodTripRules.goHomeForBlocks(FindNetherStructureTask.heldThrowaways(mod),
                    restock.exhaustedEnds(), portalKnown, deathTrip, blockTrips)
                    && FoodTripRules.mayStart(false, tick, lastUnsafeTick)) {
                why = FoodTripRules.Reason.BLOCKS;
            }
            if (why == null) {
                logNoTrip(ctx, tick, units, portalDistance, portalKnown, deathTrip);
                return null;
            }
            foodTrip = true;
            tripReason = why;
            tripBlocks = why == FoodTripRules.Reason.BLOCKS || FindNetherStructureTask.heldThrowaways(mod) < FoodTripRules.BLOCKS_LOW;
            if (tripBlocks) {
                // a new bag: the dry digs counted so far say nothing about what the next dig finds. a food trip that brings no
                // blocks keeps the count, the band is just as empty when we come back
                restock.clearExhaustedEnds();
            }
            foodTripUp = false;
            lastTripUnits = -1;
            tripResumed = false;
            foodTripStart = now;
            tripStartTick = tick;
            tripEndUnits = FoodTripRules.tripEndUnits(portalDistance, ctx.food().overworldMinimum(), ctx.food().tripEnd());
            tripLeftAt = now;
            goHome = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            homeMark = null;
            homeLastTick = tick;
            homeRestock = new NetherBlockRestock();
            if (why == FoodTripRules.Reason.FOOD) {
                foodTrips++;
                ctx.log("nether: out of food, going home for more");
                ctx.progress("food trip");
            } else {
                blockTrips++;
                ctx.log("nether: out of building blocks, going home for more");
                ctx.progress("blocks trip");
            }
        }
        // the walk ends when it stops getting closer to the portal (or at the hard cap), not on a flat clock: a long honest walk
        // used to get killed right after it broke free of a pocket. a fight or a cover that had the wheel shows up as a gap in
        // lastTick and restarts the mark (same as NetherRecoverTask), the restock dig pushes it forward instead
        long tick = ctx.facts().gameTime();
        homeMark = FoodTripRules.advance(homeMark, portalDistance(mod, ctx), homeLastTick, tick, homeRestock.isActive());
        homeLastTick = tick;
        if (FoodTripRules.walkOver(FoodTripRules.stalled(homeMark, tick), GameClock.since(tripStartTick, tick) / 20.0)) {
            // the walk is stuck (the same lava with the same empty bag, say). the trip stays counted as used, nothing more is
            // taken from the budget, and the next try has to earn its own start
            foodTrip = false;
            rodsWatch.pause(now - tripLeftAt);
            tripEndedTick = tick;
            return null;
        }
        // stuck for a bit with the bag nearly empty: the bridges ate the blocks and the path home needs them. one top up per
        // stall, shorter when the food is what is nearly gone
        if (homeRestock.isActive() || FoodTripRules.restockDue(homeMark, tick, FindNetherStructureTask.heldThrowaways(mod))) {
            int stopAt = FoodTripRules.restockStopAt(FoodTripRules.underFloor(FoodTripRules.units(ctx.food()), portalDistance(mod, ctx)));
            Task dig = homeRestock.tick(mod, FoodTripRules.RESTOCK_BELOW, stopAt, tick / 20.0);
            homeMark = new FoodTripRules.Mark(homeMark.dist(), homeMark.tick(), true);
            if (dig != null) {
                hudState = "Digging blocks for the walk home";
                return dig;
            }
        }
        hudState = tripBlocks ? "Going home for blocks" : "Going home for food";
        return goHome;
    }

    private boolean cookWanted(GamerContext ctx, int units, double portalDistance) {
        GamerFacts f = ctx.facts();
        // with fire and forget on (a user setting, the phases leave it off out here) the smelt loads and leaves, and nothing out
        // here comes back for it. the trip it is then
        return units < FoodTripRules.goHomeLine(portalDistance) && !baritone.api.BaritoneAPI.getSettings().altoAsyncSmelting.value
                && FoodTripRules.cookHere(units, CookGate.rawGap(f), CookGate.raw(f), CookGate.fuelSmelts(f, ctx.cfg().overworld, ctx.cfg().end.beds),
                f.has(Items.SMOKER), f.cookSuspended(), portalDistance);
    }

    private Task startCook(GamerContext ctx, int units, double portalDistance, double now) {
        GamerFacts f = ctx.facts();
        cook = new CookRawFoodTask(true);
        cookStart = now;
        cookUnits = units;
        cookGainTick = f.gameTime();
        cookQuiet = 0;
        ctx.log("nether: cooking " + CookGate.raw(f) + " raw meat here, food " + units + ", line " + (int) FoodTripRules.goHomeLine(portalDistance)
                + ", fuel " + CookGate.fuelSmelts(f, ctx.cfg().overworld, ctx.cfg().end.beds) + ", smoker in the bag");
        ctx.progress("cook");
        hudState = "Cooking the raw meat";
        return cook;
    }

    // one tick of the cook in place, null when there is none or it just ended. a give up parks itself (CookTrip.suspend), so cookHere is
    // false for a while and the ordinary trip takes over. no new clock for that
    private Task cookStep(AltoClef mod, GamerContext ctx, double now) {
        if (cook == null) {
            return null;
        }
        int units = FoodTripRules.units(ctx.food());
        boolean gained = FoodTripRules.gained(cookUnits, units);
        cookUnits = units;
        long tick = ctx.facts().gameTime();
        if (gained) {
            cookGainTick = tick;
        }
        // the smelt ends itself once the slots run empty (keepToTheBag). this is for meat that sits in the slot and doesn't cook: a smoker
        // gives one every 5 s, so 15 s of nothing with the screen open and no raw meat in the bag is no batch. at the screen only: the
        // walk back from a fight has nothing to do with it, and if it never gets there the task's own give up ends it
        cookQuiet = gained || CookGate.raw(ctx.facts()) > 0 || !(mod.getPlayer().containerMenu instanceof SmokerMenu) ? 0 : cookQuiet + 1;
        if (cook.isFinished(mod) || cookQuiet >= COOK_QUIET_TICKS) {
            cook = null;
            rodsWatch.pause(now - cookStart);
            ctx.log("nether: cook done, food " + units + " in " + (int) (now - cookStart) + " s");
            return null;
        }
        // the task's own give up starts over after every interrupt, so a string of short fights would never reach it. game time, fights
        // included, and same length as that one
        if (GameClock.since(cookGainTick, tick) >= FurnacePlan.COOK_GIVE_UP_TICKS) {
            CookTrip.suspend(tick);
            cook = null;
            rodsWatch.pause(now - cookStart);
            ctx.log("nether: cook going nowhere, leaving it, food " + units);
            return null;
        }
        // every tick, not just on a gain: the watchdog is shorter than the clock above, and a retry from it costs the phase an attempt and
        // forgets the meat. the clock above is what bounds this
        ctx.progress("cook");
        hudState = "Cooking the raw meat";
        return cook;
    }

    // lava close enough to be knocked into, or our own one wide bridge
    private static boolean cookSpotOk(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-COOK_LAVA_REACH, -COOK_LAVA_REACH, -COOK_LAVA_REACH), feet.offset(COOK_LAVA_REACH, 1, COOK_LAVA_REACH))) {
            if (mod.getWorld().getFluidState(p).is(FluidTags.LAVA)) {
                return false;
            }
        }
        BlockPos under = feet.below();
        return !FoodTripRules.onABridge(floorAt(mod, under.east()), floorAt(mod, under.west()), floorAt(mod, under.south()), floorAt(mod, under.north()));
    }

    private static boolean floorAt(AltoClef mod, BlockPos p) {
        return mod.getWorld().getBlockState(p).isFaceSturdy(mod.getWorld(), p, Direction.UP);
    }

    private boolean blocksTrip() {
        return tripReason == FoodTripRules.Reason.BLOCKS;
    }

    // a respawn is a new body: whatever trip we were on went with the old one, wherever the death happened (the overworld half
    // included, it used to latch and walk the new body into a hunt it had no business in)
    private void noteRespawn(GamerContext ctx) {
        int deaths = ctx.state().deaths.size();
        if (deaths != foodDeathsSeen) {
            foodDeathsSeen = deaths;
            foodTrip = false;
            foodTripUp = false;
            cook = null;
            foodFloor.reset();
            restock.clearExhaustedEnds();
        }
    }

    // a piglin close enough to be angered by a gold block breaking (NetherGold.angerBox): the whole box around the player, no sight
    // check, so one behind a wall counts. checked on the client's entity list, which is what we can see of it
    private static boolean piglinInBox(AltoClef mod) {
        Player p = mod.getPlayer();
        return !mod.getWorld().getEntitiesOfClass(Piglin.class, NetherGold.angerBox(p.getBoundingBox()), Entity::isAlive).isEmpty();
    }

    // while a piglin is that close, nothing may break gold ore as a side block of some other dig (the gravel, the way through a wall):
    // the hit angers all of them. blocksToDisallowBreaking and not the avoid list, which only makes a break ten times dearer and
    // lets it through when the way round is dearer still. a piglin on the edge of the box would flip the list (and the path tables
    // that copy it) every few ticks, so it stays on for a second after the last one. returns whether it is on
    private boolean guardGold(AltoClef mod) {
        if (piglinInBox(mod)) {
            guardHold = GUARD_HOLD_TICKS;
            if (!guarding) {
                for (Block b : GUARDED) {
                    baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.add(b);
                }
                guarding = true;
            }
        } else if (guarding && --guardHold <= 0) {
            unguard(mod);
        }
        return guarding;
    }

    // placing a block in the nether costs 20 instead of 10 (fast mode caps it at 5 otherwise, which is about one block of
    // walking, so bridging and pillaring looked free). our own level so it comes off whole, and anything above it (the lava
    // escape sets 0) still wins
    private void placingOff(AltoClef mod) {
        if (placingLevel == null) {
            return;
        }
        if (mod.getBehaviour().isTop(placingLevel)) {
            mod.getBehaviour().pop();
        } else if (mod.getBehaviour().contains(placingLevel)) {
            // somebody's level is on top of ours and popping would take theirs. the ones above copied the 20 already and
            // pop on their own, ours comes out from under them
            mod.getBehaviour().remove(placingLevel);
        }
        placingLevel = null;
    }

    private void placingOn(AltoClef mod) {
        // (a child that pushed before us and pops blind can take our level, then it is not on the stack and we push again)
        if (placingLevel != null && mod.getBehaviour().contains(placingLevel)) {
            return;
        }
        placingLevel = mod.getBehaviour().push();
        mod.getBehaviour().setBlockPlacePenalties(PLACE_PENALTY);
    }

    private void unguard(AltoClef mod) {
        if (guarding) {
            for (Block b : GUARDED) {
                baritone.api.BaritoneAPI.getSettings().blocksToDisallowBreaking.value.remove(b);
            }
            guarding = false;
        }
    }

    // no gold piece on, and nothing to wear: piglins will jump us on sight, so make one out of nether gold ore. a try a visit (a few, see
    // NetherGold.mayRetry), each under the stall timer (NetherGold.CAP_SECONDS), and only from what the bag holds and ore the tracker already knows about.
    // a piglin in the box a block break would anger pauses the mining (guardGold keeps baritone off the ore meanwhile), the
    // crafts and the wearing go on. below the ghast cover and the other side jobs, it never walks into a fight
    private Task goldSide(AltoClef mod, GamerContext ctx, double now, boolean piglins) {
        GamerFacts f = ctx.facts();
        if (!ctx.cfg().overworld.goldSide) {
            return null;
        }
        int deaths = ctx.state().deaths.size();
        if (NetherGold.resetOnDeath(goldDeathsSeen, deaths)) {
            // a respawn is a new body with an empty bag, the visit's tries start over
            goldDeathsSeen = deaths;
            goldOver = false;
            goldOverAt = -1;
            goldStartedAt = -1;
            goldTask = null;
            goldWear = null;
            goldAnchor = null;
            goldTries = 0;
        }
        if (PiglinGold.worn(f)) {
            // not a latch: if the piece breaks or comes off there is a try waiting right away, not one 60 s out
            goldOver = false;
            goldTask = null;
            goldWear = null;
            return null;
        }
        if (PiglinGold.holdsPiece(f)) {
            List<Item> equip = new ArrayList<>();
            PiglinGold.addToEquip(f, equip);
            if (equip.isEmpty() || WearFromBag.held(mod, equip).isEmpty()) {
                return null;
            }
            if (goldWear == null || !equip.get(0).equals(goldWearing)) {
                goldWearing = equip.get(0);
                goldWear = WearFromBag.task(mod, equip);
            }
            hudState = "Putting on gold for piglins";
            return goldWear;
        }
        if (goldOver) {
            if (goldOverAt < 0) {
                goldOverAt = now;
            }
            // the latch is not for the whole visit: a wander, a dry anchor or the clock gets another go from where we stand now
            BlockPos here = mod.getPlayer().blockPosition();
            if (tickCounter % ORE_LOOK_TICKS != 0
                    || !NetherGold.mayRetry(now - goldOverAt, goldTries, NetherPick.hasUsablePick(f) && oreKnown(mod, here))) {
                return null;
            }
            goldOver = false;
            goldOverAt = -1;
            goldStartedAt = -1;
            goldTask = null;
        }
        if (goldStartedAt >= 0 && NetherGold.overTime(now - goldStartedAt)) {
            goldOver = true;
            goldTask = null;
            return null;
        }
        if (goldTask != null && goldTask.failed()) {
            goldOver = true;
            goldTask = null;
            return null;
        }
        // blazes and ghasts, a spawner, a barter and a pearl hunt come first for the digging, the same list the pick craft waits
        // for. folding a piece from gold the bag already holds is quick, and a helmet the endermen broke mid hunt is exactly when
        // the piglins stop leaving us alone
        boolean busy = pickBusy(mod, ctx, now);
        NetherGold.Bag bag = goldBag(f);
        if (goldTask == null) {
            String slot = PiglinGold.netherSlot(f, KitPlanner.armorNames(ctx.cfg().overworld));
            int need = PiglinGold.ingotsFor(slot);
            // only the digging angers them. a piece folded from what the bag already holds is the thing that calms the piglins
            // standing right there, waiting for them to leave first is backwards
            if ((piglins || busy) && NetherGold.stage(bag, need) == NetherGold.Stage.MINE) {
                return null;
            }
            BlockPos me = mod.getPlayer().blockPosition();
            // the cheap answers first, the walk over the tracker's gold ore is twice a second at most. digging takes a pick the bag
            // already has (pickSide makes one from the bag, the mining task would go and fetch one, and there is nothing to fetch)
            if (NetherGold.stage(bag, need) == NetherGold.Stage.ABORT || tickCounter % ORE_LOOK_TICKS != 0
                    || !NetherGold.mayStart(bag, need, NetherPick.hasUsablePick(f) && oreKnown(mod, me))) {
                return null;
            }
            goldTask = new NetherGoldTask("golden_" + slot, PiglinGold.piece(slot), need, () -> goldBag(ctx.facts()), me);
            goldNeed = need;
            goldAnchor = me;
            goldTries++;
            goldStartedAt = now;
            oreLeft = true;
            lastNuggets = bag.nuggets();
        }
        if (NetherGold.stage(bag, goldNeed) == NetherGold.Stage.MINE) {
            if (!NetherPick.hasUsablePick(f)) {
                goldOver = true;
                goldTask = null;
                return null;
            }
            if (piglins || busy) {
                // wait it out: the clock keeps running, the cap ends it
                return null;
            }
            if (tickCounter % ORE_LOOK_TICKS == 0) {
                oreLeft = oreKnown(mod, goldAnchor);
            }
            if (!oreLeft) {
                goldOver = true;
                goldTask = null;
                return null;
            }
        }
        if (bag.nuggets() > lastNuggets) {
            ctx.progress("nether gold nuggets");
        }
        lastNuggets = bag.nuggets();
        hudState = "Mining gold for piglins";
        return goldTask;
    }

    // a table in the bag or standing next to us. not the planner's memory of one back in the overworld
    private static NetherGold.Bag goldBag(GamerFacts f) {
        NetherPick.Bag nb = NetherPick.bagOf(f);
        boolean table = f.has(Items.CRAFTING_TABLE) || f.tablePlacedNearby();
        return new NetherGold.Bag(f.count(Items.GOLD_NUGGET), f.count(Items.GOLD_INGOT), nb.planks(), nb.logs(), table);
    }

    // nether gold ore the tracker already holds, still standing, within the try's reach of `from` (NetherGold.oreInReach)
    private static boolean oreKnown(AltoClef mod, BlockPos from) {
        for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.NETHER_GOLD_ORE)) {
            if (NetherGold.oreInReach(p.getX(), p.getY(), p.getZ(), from.getX(), from.getY(), from.getZ())
                    && mod.getWorld().getBlockState(p).is(Blocks.NETHER_GOLD_ORE)) {
                return true;
            }
        }
        return false;
    }

    // no pick that works and the bag can make one right now: make it, here, before the next netherrack is dug by hand. only from
    // what the bag holds (NetherPick checks every input, the task is dropped the tick one is short), so this never turns into a
    // collection trip in a dimension with no cobble and no oaks. not at a spawner, not with a blaze or a ghast in sight, not while a
    // trade or a pearl hunt is going: the same reasons the gravel detour stays out. a fight is the combat chain's and never asks us
    private Task pickSide(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        if (NetherPick.hasUsablePick(f)) {
            pickTask = null;
            noPickSaid = false;
            return null;
        }
        if (pickTask != null && pickTask.failed()) {
            // stuck or an input went missing under us: leave it alone for a while, the sweep goes on with what it has
            pickTask = null;
            pickRetryAt = now + PICK_RETRY_SECONDS;
            if (++pickFails >= PICK_TRIES) {
                ctx.log("nether: the pickaxe craft keeps failing, giving up on it");
            }
        }
        if (pickFails >= PICK_TRIES) {
            return null;
        }
        if (pickTask != null) {
            // under way: the task reads the bag itself every tick (and the bag reads odd for a moment when the table goes down)
            if (pickBusy(mod, ctx, now)) {
                return null;
            }
            hudState = "Making a pickaxe";
            return pickTask;
        }
        NetherPick.Bag bag = NetherPick.bagOf(f);
        NetherPick.Tier tier = NetherPick.choose(bag);
        if (tier == NetherPick.Tier.NONE) {
            if (!noPickSaid) {
                noPickSaid = true;
                ctx.log(NetherPick.shortLine(bag));
            }
            return null;
        }
        noPickSaid = false;
        if (pickBusy(mod, ctx, now) || now < pickRetryAt) {
            return null;
        }
        pickTask = new NetherPickTask(tier, () -> NetherPick.bagOf(ctx.facts()), () -> NetherPick.hasUsablePick(ctx.facts()));
        ctx.log(NetherPick.craftLine(tier));
        hudState = "Making a pickaxe";
        return pickTask;
    }

    // the sword is about to break (or is gone) and the bag can make one: diamond, iron, then cobble. same rules as pickSide (the
    // craft only ever eats what the bag holds, a short input drops it, 3 tries a minute apart) but it does not wait for the
    // pearl hunt to be over, the hunt is what wears the thing out. it waits for a spawner, a trade or a blaze/ghast fight
    private Task swordSide(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        if (NetherPick.hasUsableSword(f)) {
            swordTask = null;
            swordFails = 0;
            return null;
        }
        if (swordTask != null && swordTask.failed()) {
            swordTask = null;
            swordRetryAt = now + PICK_RETRY_SECONDS;
            if (++swordFails >= PICK_TRIES) {
                ctx.log("nether: the sword craft keeps failing, giving up on it");
            }
        }
        if (swordFails >= PICK_TRIES || swordBusy(mod, now)) {
            return null;
        }
        if (swordTask != null) {
            hudState = "Making a sword";
            return swordTask;
        }
        NetherPick.Bag bag = NetherPick.bagOf(f);
        // (every sword we still hold is a worn one here, see NetherPick.choose)
        NetherPick.Tier tier = NetherPick.choose(bag, NetherPick.Kind.SWORD, t -> f.has(NetherPick.swordItem(t)));
        if (tier == NetherPick.Tier.NONE || now < swordRetryAt) {
            return null;
        }
        swordTask = new NetherPickTask(tier, NetherPick.Kind.SWORD, () -> NetherPick.bagOf(ctx.facts()),
                () -> NetherPick.hasUsableSword(ctx.facts()));
        ctx.log(NetherPick.swordLine(tier));
        hudState = "Making a sword";
        return swordTask;
    }

    private boolean swordBusy(AltoClef mod, double now) {
        boolean atSpawner = rodsTask != null && (rodsTask.currentSpawner() != null || rodsTask.isCampingSpawner());
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        return atSpawner || trading || fightersNear(mod);
    }

    // a blaze or ghast close enough to be a fight. entityFound says yes for one anywhere in the loaded chunks, which is a lot
    // further than anything that could be swinging at us
    private static boolean fightersNear(AltoClef mod) {
        try {
            var tracker = mod.getEntityTracker();
            for (Entity e : tracker.getTrackedEntities(Blaze.class)) {
                if (e.isAlive() && mod.getPlayer().distanceTo(e) <= SWORD_BUSY_RANGE) return true;
            }
            for (Entity e : tracker.getTrackedEntities(Ghast.class)) {
                if (e.isAlive() && mod.getPlayer().distanceTo(e) <= SWORD_BUSY_RANGE) return true;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // one tick of stale mobs is fine
        }
        return false;
    }

    private boolean pickBusy(AltoClef mod, GamerContext ctx, double now) {
        boolean atSpawner = rodsTask != null && (rodsTask.currentSpawner() != null || rodsTask.isCampingSpawner());
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        boolean hunting = pearlsNext(ctx) && endermanHere;
        return atSpawner || trading || hunting || mod.getEntityTracker().entityFound(Blaze.class, Ghast.class);
    }

    // the gravel detour, between the ghast cover and the next step. a spawner we are on or a trade that is going wins outright
    private Task gravelSide(AltoClef mod, GamerContext ctx, double now) {
        boolean atSpawner = rodsTask != null && rodsTask.currentSpawner() != null;
        // barterTickAt is only put back when barterStep says no, and once the pearls are in nobody asks barterStep again. so a
        // trade is "going" only while it was ticked a moment ago, or one finished barter would keep gravel off for the phase
        boolean trading = barterTickAt >= 0 && now - barterTickAt < TRADE_FRESH_SECONDS;
        // the pearl hunt like a picked spawner: pearls are what nextStep is on and an enderman is in sight (angry or not, it is
        // what we walk at). asked every tick off the phase latch and not off a hunt tick, so one that shows up halfway through a detour ends it (it holds a few seconds after the ray loses it).
        // the wander with no enderman around is a walk, gravel on the way is fair there
        boolean hunting = pearlsNext(ctx) && endermanHere;
        if (!DetourSpec.netherMayDetour(ctx.facts().dimension() == Dimension.NETHER, atSpawner, trading, hunting)) {
            gravel.preempted(ctx.facts().gameTime());
            return null;
        }
        Task flint = gravel.tick(mod, ctx, null);
        if (flint != null) {
            hudState = gravel.hud();
        }
        return flint;
    }

    // back in the overworld (a regress from the stronghold, a death): walk to the portal we built first. the default task
    // only knows portals the block tracker has loaded and otherwise builds a brand new one, a thousand blocks from here
    // the portal we just came through is the way home, also when the one we remembered was gone and the default task built a
    // new one (PortalPhase only fills this in the first time)
    private static void rememberOverworldPortal(AltoClef mod, GamerContext ctx) {
        if (ctx.state().overworldPortal != null) {
            return;
        }
        mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD)
                .ifPresent(p -> {
                    ctx.state().overworldPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ());
                    ctx.save();
                });
    }

    private Task toTheNether(AltoClef mod, GamerContext ctx) {
        Task task = homeWalk.toTheNether(mod, ctx.state(), ctx::save);
        hudState = homeWalk.hud();
        return task;
    }

    private void ensureTracking(AltoClef mod) {
        if (!tracking) {
            mod.getBlockTracker().trackBlock(TRACKED);
            tracking = true;
        }
    }

    // what the tracker knows is out there. one kind per scan, rotating: each one sorts its blocks and the bastion and forest ones
    // count crowds too, and there is no rush on any of it
    private void scanSights(AltoClef mod, GamerContext ctx) {
        Sight sight = Sight.values()[rotation++ % Sight.values().length];
        if (planner.scan(s -> seen(mod, s, planner), sight)) {
            if (sight == Sight.WARPED) {
                logWarpedForest(mod, ctx.state().warpedForest);
            }
            ctx.progress("saw something");
            ctx.save();
        }
    }

    private void logWarpedForest(AltoClef mod, RunState.Pos at) {
        BlockPos spot = new BlockPos(at.x, at.y, at.z);
        int near = clusterCount(mod.getBlockTracker().getKnownLocations(WARPED), spot);
        long away = Math.round(Math.sqrt(mod.getPlayer().blockPosition().distSqr(spot)));
        Debug.logInternal("warped forest: " + at.x + " " + at.y + " " + at.z + ", " + near + " known near it, " + away + " from us");
    }

    // the nearest tracked blocks of this kind that are really still there. the tracker hands them out in scan order and keeps
    // unloaded ones, so sort by distance and check the block itself. no line of sight for any of them: the tracker has every
    // loaded chunk, and a fortress or a forest behind rock is as good as seen (the sweep was walking past ones it could not look
    // at). one that turns out to be walled off is what the give-ups are for. the ones of a fortress or a forest we gave up on are
    // dropped before the sort, or the dud's thousand blocks would be the nearest forty for ever
    private static Optional<RunState.Pos> seen(AltoClef mod, Sight sight, NetherSweepPlanner planner) {
        Block[] blocks = switch (sight) {
            case FORTRESS -> FORTRESS;
            case BASTION -> BASTION;
            case WARPED -> WARPED;
        };
        List<BlockPos> known = mod.getBlockTracker().getKnownLocations(blocks);
        // the cluster count wants every block, known bastion or not: a few of them can sit on the near side of the 64 line
        List<BlockPos> all = known;
        if (sight == Sight.FORTRESS) {
            known = known.stream().filter(p -> !planner.isGivenUp(new RunState.Pos(p.getX(), p.getY(), p.getZ()))).toList();
        } else if (sight == Sight.BASTION) {
            // the blocks of a bastion we know are always the nearest, they would eat the whole candidate list
            known = known.stream().filter(p -> !planner.isKnownBastion(new RunState.Pos(p.getX(), p.getY(), p.getZ()))).toList();
        } else if (sight == Sight.WARPED) {
            known = known.stream().filter(p -> !planner.isGivenUpWarped(new RunState.Pos(p.getX(), p.getY(), p.getZ()))).toList();
        }
        List<BlockPos> near = StrongholdScan.nearest(known, mod.getPlayer().blockPosition(), SCAN_CANDIDATES);
        for (BlockPos p : near) {
            BlockState state = mod.getWorld().getBlockState(p);
            if (isOneOf(state, blocks) && crowdedEnough(sight, all, p)) {
                return Optional.of(new RunState.Pos(p.getX(), p.getY(), p.getZ()));
            }
        }
        return Optional.empty();
    }

    // a bastion is hundreds of these blocks, a ruined portal or a blackstone blob a handful. only a crowd is a bastion
    static final int BASTION_MIN_BLOCKS = 20;
    static final int BASTION_CLUSTER_RADIUS = 16;

    // a sliver of warped biome at the edge of a crimson one is a few nylium and no endermen. lower than the bastion's 20 because
    // the tracker only keeps 25 of a block type, 20 of those would have to land within a hair of each other
    static final int WARPED_MIN_BLOCKS = 12;

    private static boolean crowdedEnough(Sight sight, List<BlockPos> all, BlockPos p) {
        return switch (sight) {
            case FORTRESS -> true;
            case BASTION -> bastionCluster(all, p);
            case WARPED -> bastionCluster(all, p, WARPED_MIN_BLOCKS);
        };
    }

    static boolean bastionCluster(List<BlockPos> known, BlockPos at) {
        return bastionCluster(known, at, BASTION_MIN_BLOCKS);
    }

    static boolean bastionCluster(List<BlockPos> known, BlockPos at, int minBlocks) {
        return clusterCount(known, at) >= minBlocks;
    }

    static int clusterCount(List<BlockPos> known, BlockPos at) {
        int count = 0;
        for (BlockPos p : known) {
            if (Math.abs(p.getX() - at.getX()) <= BASTION_CLUSTER_RADIUS && Math.abs(p.getY() - at.getY()) <= BASTION_CLUSTER_RADIUS
                    && Math.abs(p.getZ() - at.getZ()) <= BASTION_CLUSTER_RADIUS) {
                count++;
            }
        }
        return count;
    }

    private static boolean isOneOf(BlockState state, Block[] blocks) {
        for (Block b : blocks) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    // nextStep would go for pearls: the rods are in (or given up) and pearls are still short. same sums nextStep makes
    private static boolean pearlsNext(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        int eyes = EyeMath.eyes(f);
        int goal = EyeMath.targetGoal(ctx.cfg(), ctx.state());
        int rodGoal = EyeMath.rodGoal(ctx.cfg(), ctx.state());
        boolean rodsDone = f.count(Items.BLAZE_ROD) >= EyeMath.rodsNeeded(rodGoal, eyes, f.count(Items.BLAZE_POWDER)) || ctx.state().netherRodsGaveUp;
        return rodsDone && f.count(Items.ENDER_PEARL) < EyeMath.pearlsNeeded(goal, eyes);
    }

    private Task nextStep(AltoClef mod, GamerContext ctx, double now) {
        GamerFacts f = ctx.facts();
        RunState s = ctx.state();
        GamerConfig cfg = ctx.cfg();
        int eyes = EyeMath.eyes(f);
        int goal = EyeMath.targetGoal(cfg, s);
        int rodsNeed = EyeMath.rodsNeeded(EyeMath.rodGoal(cfg, s), eyes, f.count(Items.BLAZE_POWDER));
        int rods = f.count(Items.BLAZE_ROD);
        // before rods, pearls and the sweep: whichever of them is walking is what burns the bag. the first time here after a phase
        // entry it's the arrival dig, then NetherRestock.LOW decides. both sit behind the escapes and the side trips above.
        // the dig runs on the game clock like RodsWatch and FORTRESS_WALK_SECONDS do, so it eats into those too, accepted
        boolean atSpawner = rodsTask != null && ctx.facts().gameTime() - rodsTaskRanAt <= 5
                && !FoodTripRules.restockAllowed(rodsTask.isCampingSpawner(), rodsTask.currentSpawner() != null,
                        rodsTask.isBusyWithBlazes());
        if (!atSpawner) {
            Task dig = restock.tickPhase(mod, ctx.secondsInPhase());
            if (dig != null) {
                hudState = "Restocking building blocks";
                return dig;
            }
        }
        if (rods < rodsNeed && !s.netherRodsGaveUp) {
            return rodsStep(mod, ctx, now, rods, rodsNeed);
        }
        int pearlsNeed = EyeMath.pearlsNeeded(goal, eyes);
        int pearls = f.count(Items.ENDER_PEARL);
        if (pearls < pearlsNeed) {
            return pearlStep(mod, ctx, now, pearls, pearlsNeed);
        }
        if (rods < rodsNeed) {
            return fail(ctx, "only " + rods + " of " + rodsNeed + " blaze rods");
        }
        hudState = null;
        return null;
    }

    // the rods task finishes the instant the count is met, mid fight, and the blazes it was keeping mob defense off of go back to
    // being ordinary shooters (a run, then a heal-up stand in the open). so the fight is left properly first: back away until
    // nothing has seen us for a few seconds, capped, then the rest of the phase. decided in the first moments after the last rod
    // only, a blaze that spots us an hour later out by the portal is not this fight. ahead of the detours: a pick or a gold walk
    // taking the wheel with three blazes still shooting is the same death
    private Task disengageStep(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        int rodsNeed = EyeMath.rodsNeeded(EyeMath.rodGoal(ctx.cfg(), ctx.state()), EyeMath.eyes(f), f.count(Items.BLAZE_POWDER));
        if (f.count(Items.BLAZE_ROD) < rodsNeed) {
            // short of rods again (or still collecting): the next batch gets its own walk away
            disengage = null;
            disengageDone = false;
            disengageSince = Long.MIN_VALUE;
            return null;
        }
        long now = mod.getWorld().getGameTime();
        if (disengageSince == Long.MIN_VALUE) {
            disengageSince = now;
        }
        if (disengage == null) {
            // the window shut with nobody looking: that counts as done, whatever shows up later is the combat chain's
            boolean windowOpen = now >= disengageSince && now - disengageSince <= DISENGAGE_WINDOW_TICKS;
            if (!BlazeFightRules.disengageWanted(true, windowOpen && blazeSeesUs(mod, mod.getPlayer()), disengageDone)) {
                return null;
            }
            // no spawner on purpose: cover near the spawner is a walk back into the fight we are leaving, and blazes keep spawning
            // there. wherever we stand is where the cover search looks
            disengage = new BlazeDisengageTask();
            disengageDone = true;
        }
        if (disengage.isFinished(mod)) {
            return null;
        }
        hudState = "Backing away from the blazes";
        return disengage;
    }

    // ---- rods

    private Task rodsStep(AltoClef mod, GamerContext ctx, double now, int rods, int rodsNeed) {
        RunState s = ctx.state();
        if (sweepInstead(s.fortress.isEmpty(), rodsTask != null && rodsTask.currentSpawner() != null)) {
            return findStep(ctx, findFortress, "Looking for a fortress");
        }
        if (rodsTask == null || rodsTaskTarget != rodsNeed) {
            rodsTask = new CollectBlazeRodsTask(rodsNeed, () -> DeathZones.spots(ctx.state().deaths, ctx.facts().gameTime()));
            rodsTaskTarget = rodsNeed;
        }
        boolean camping = rodsTask.isCampingSpawner();
        BlockPos camped = rodsTask.currentSpawner();
        if (camped != null) {
            // the one the task picked, not whatever the tracker calls nearest: they are not always the same spawner
            RunState.Pos mine = new RunState.Pos(camped.getX(), camped.getY(), camped.getZ());
            if (!mine.equals(s.spawner)) {
                s.spawner = mine;
                rodsWatch.spawnerChanged();
                ctx.save();
            }
        }
        // moving on below the floor is no moving on: the pearls come, then the game has no way out of here anyway
        // (canLeaveNether wants floor rods whatever the flag says). so the budget only counts once the floor is in the bag
        int rodsFloor = EyeMath.rodsNeeded(EyeMath.floorGoal(ctx.cfg(), s), EyeMath.eyes(ctx.facts()), ctx.facts().count(Items.BLAZE_POWDER));
        RodsWatch.Verdict verdict = rodsWatch.update(now, rods, camping, camped != null, rods >= rodsFloor);
        if (verdict == RodsWatch.Verdict.GIVE_UP_RODS) {
            s.netherRodsGaveUp = true;
            ctx.log("blaze rods are taking too long, moving on with " + rods);
            ctx.save();
            return null;
        }
        if (verdict == RodsWatch.Verdict.GIVE_UP_SPAWNER) {
            return giveUpSpawner(mod, ctx, now);
        }
        // fighting or running from blazes at our spawner can stand still for a while too, and the rods watch has its
        // own clocks for a spawner that goes nowhere, so that is not a stall either. a stuck walk to the spawner still is
        if (campPinger.ping(now, camping || (camped != null && rodsTask.isBusyWithBlazes()))) {
            ctx.progress("waiting at the blaze spawner");
        }
        // the walk to the spawner goes nowhere (no path, across lava): the task only stands there, so the distance says it
        if (camped != null && rodsTask.takeWalkingToSpawner() && spawnerWalkStalled(mod, camped, now)) {
            // this spawner only: banned, and the task picks another if one is known. every fortress around it goes only when
            // there is no other spawner to go to
            boolean other = rodsTask.dropSpawner(mod);
            spawnerProgress.reset();
            if (RodsWatch.afterNoWay(other) == RodsWatch.Verdict.GIVE_UP_SPAWNER) {
                return giveUpSpawner(mod, ctx, now);
            }
        }
        Task toFortress = walkToKnownFortress(mod, ctx, now);
        if (toFortress != null) {
            hudState = "Walking to the fortress";
            return toFortress;
        }
        // standing still at a spawner looks exactly like a frozen bot, so say what we're doing
        // and "waiting to spawn" with six blazes in view reads like a bug, so say which kind of waiting it is
        hudState = rodsHud(camping, rodsTask.blazesAround()) + " (" + rods + "/" + rodsNeed + ")";
        rodsTaskRanAt = ctx.facts().gameTime();
        return rodsTask;
    }

    // giving up on a fortress walk can empty the list right as the task picks a real spawner. a spawner in hand beats any
    // sweep, and it can't hog the wheel: a dud goes through giveUpSpawner, which drops the task, and a walk that stalls gets banned
    static boolean sweepInstead(boolean noFortressKnown, boolean spawnerPicked) {
        return noFortressKnown && !spawnerPicked;
    }

    // no closer to the spawner than a while ago
    private boolean spawnerWalkStalled(AltoClef mod, BlockPos spawner, double now) {
        double dist = Math.sqrt(mod.getPlayer().distanceToSqr(spawner.getX() + 0.5, spawner.getY() + 0.5, spawner.getZ() + 0.5));
        return spawnerProgress.stalled(now, dist);
    }

    private static String rodsHud(boolean camping, int blazesAround) {
        if (!camping) return "Collecting blaze rods";
        return blazesAround > 0 ? "Waiting for blazes to come in reach" : "Waiting for blazes to spawn";
    }

    // a fortress we saw (an earlier visit, a relog, the next one after a dud spawner) and are nowhere near: the rods task
    // only wanders to whatever bricks are closest, so go to the one we know first. bounded, a fortress behind a lava sea
    // gets the wander instead of this walk for ever
    private Task walkToKnownFortress(AltoClef mod, GamerContext ctx, double now) {
        RunState s = ctx.state();
        if (rodsTask.currentSpawner() != null || s.fortress.isEmpty()) {
            return null;
        }
        RunState.Pos spot = fortressToWalkTo(mod, s);
        if (spot == null) {
            return null;
        }
        if (!spot.equals(fortressWalkTo)) {
            fortressWalkTo = spot;
            fortressWalkSince = now;
            fortressProgress.reset();
            walkToFortress = new GetToXZTask(spot.x, spot.z);
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        boolean there = dx * dx + dz * dz <= FORTRESS_NEAR_BLOCKS * FORTRESS_NEAR_BLOCKS;
        if (there || now - fortressWalkSince > FORTRESS_WALK_SECONDS) {
            fortressWalkGivenUp = spot;
            return null;
        }
        // a fortress we x-rayed through the rock is often not a place we can walk to, and the walk task never says so. no
        // gain for a while = blacklisted, the sweep goes looking for one we can reach
        if (fortressProgress.stalled(now, Math.sqrt(dx * dx + dz * dz))) {
            planner.giveUpFortress(spot);
            fortressWalkTo = null;
            fortressProgress.reset();
            ctx.save();
            return null;
        }
        return walkToFortress;
    }

    // the one we are already walking at while it is still on the list (two that trade places as nearest would restart the
    // walk every few steps), else the nearest one that has not had its walk already
    private RunState.Pos fortressToWalkTo(AltoClef mod, RunState s) {
        // one that had its walk (got there, or ran out of time) is where the search happens, the others wait until the list changes:
        // walking on to the next one and back is no search
        if (fortressWalkGivenUp != null && s.fortress.contains(fortressWalkGivenUp)) {
            return null;
        }
        if (fortressWalkTo != null && s.fortress.contains(fortressWalkTo)) {
            return fortressWalkTo;
        }
        RunState.Pos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (RunState.Pos p : s.fortress) {
            double dx = mod.getPlayer().getX() - p.x;
            double dz = mod.getPlayer().getZ() - p.z;
            if (dx * dx + dz * dz < bestDist) {
                bestDist = dx * dx + dz * dz;
                best = p;
            }
        }
        return best;
    }

    // the rods task remembers its spawner and would walk straight back to it, so the dud gets blacklisted in the block
    // tracker and the task is thrown away
    private Task giveUpSpawner(AltoClef mod, GamerContext ctx, double now) {
        RunState s = ctx.state();
        ctx.log("no rods from this spawner, looking for another fortress");
        // the spawner the task was really camping first, the saved one can be a stale pick from before it re-searched
        BlockPos dud = rodsTask != null ? rodsTask.currentSpawner() : null;
        if (dud == null) {
            dud = s.spawner != null ? new BlockPos(s.spawner.x, s.spawner.y, s.spawner.z)
                    : mod.getBlockTracker().getNearestTracking(Blocks.SPAWNER).orElse(null);
        }
        if (dud != null) {
            mod.getBlockTracker().requestBlockUnreachable(dud, 0);
        }
        // only the fortress this spawner is in, a second one we saw is the next place to try
        planner.giveUpFortress(dud == null ? null : new RunState.Pos(dud.getX(), dud.getY(), dud.getZ()));
        fortressWalkGivenUp = null;
        fortressWalkTo = null;
        rodsTask = null;
        rodsWatch.spawnerGivenUp(now);
        ctx.save();
        return findStep(ctx, findFortress, "Looking for a fortress");
    }

    // ---- pearls

    private Task pearlStep(AltoClef mod, GamerContext ctx, double now, int pearls, int pearlsNeed) {
        Task barter = barterStep(mod, ctx, now, pearls, pearlsNeed);
        if (barter != null) {
            return barter;
        }
        RunState s = ctx.state();
        // (endermanHere runs off the phase tick, a fight under the combat brain lets it lapse, and the pearl it dropped is still there)
        if (s.warpedForest == null && !endermanHere && !warpedSearchOver && !PearlHuntTask.pearlDropNear(mod)) {
            if (findWarped.failure() != null) {
                // swept what we are allowed to sweep, hunt whatever wanders by
                warpedSearchOver = true;
            } else {
                return findStep(ctx, findWarped, "Looking for a warped forest");
            }
        }
        return huntStep(mod, ctx, now, pearlsNeed);
    }

    // the walk to the forest didn't make it. findWarped holds on to its old failure until it restarts and pearlStep reads that
    // before it ever runs, so it takes a new one or the search just calls itself over again
    private void warpedWalkFailed(GamerContext ctx, RunState.Pos spot) {
        planner.giveUpWarped(spot);
        findWarped = new FindNetherStructureTask(planner, Goal.WARPED, ctx::secondsInPhase);
        warpedSearchOver = false;
        ctx.save();
    }

    // the no-pearl clock only counts time the hunt actually ran: food trips, the walk home, ghast cover and fights return
    // before huntStep, and a gap longer than a couple of seconds since the last hunt tick is pushed onto the start (pause, not reset)
    static double pausedClock(double since, double seen, double now) {
        if (since < 0 || seen < 0 || now - seen <= 2) {
            return since;
        }
        return since + (now - seen);
    }

    private Task huntStep(AltoClef mod, GamerContext ctx, double now, int pearlsNeed) {
        RunState s = ctx.state();
        if (hunt == null || huntTarget != pearlsNeed) {
            hunt = new PearlHuntTask(pearlsNeed, Dimension.NETHER, () -> s.warpedForest,
                    () -> ctx.progress("enderman down"), planner::nearBastion, spot -> warpedWalkFailed(ctx, spot));
            huntTarget = pearlsNeed;
        }
        int pearls = ctx.facts().count(Items.ENDER_PEARL);
        huntWanderSince = pausedClock(huntWanderSince, huntSeen, now);
        huntSeen = now;
        if (huntWanderSince < 0 || pearls > lastPearls) {
            huntWanderSince = now;
        }
        lastPearls = pearls;
        // runs whether or not a forest is known: a forest with no endermen in it is just as stuck
        if (now - huntWanderSince > ctx.cfg().nether.huntWanderMinutes * 60) {
            return fail(ctx, "no pearl gained in the Nether hunt for too long");
        }
        huntTickedAt = lastGameTime;
        hudState = hunt.step();
        return hunt;
    }

    // gold already in the bag and a piglin already in view: worth a few minutes. never walks into a bastion for it and
    // never opens a chest or breaks gold, TradeWithPiglinsTask only talks to piglins
    private Task barterStep(AltoClef mod, GamerContext ctx, double now, int pearls, int pearlsNeed) {
        NetherConfig n = ctx.cfg().nether;
        GamerFacts f = ctx.facts();
        int missing = pearlsNeed - pearls;
        int goldWanted = n.barterGoldPerPearl * (n.pearlSource == NetherConfig.PearlSource.BARTER ? 1 : missing);
        // v1: only when asked for. AUTO is endermen, the gold for a barter run rarely exists and the boots swap puts the
        // iron boots in the bag where nothing puts them back
        boolean goodToGo = n.pearlSource == NetherConfig.PearlSource.BARTER
                && barterSpent < n.barterMinutes * 60
                && f.count(Items.GOLD_INGOT) >= goldWanted + (PiglinGold.worn(f) || f.count(Items.GOLDEN_BOOTS) > 0 ? 0 : 4)
                && mod.getEntityTracker().entityFound(Piglin.class);
        if (!goodToGo) {
            barterTickAt = -1;
            return null;
        }
        if (barterTickAt >= 0) {
            barterSpent += Math.min(now - barterTickAt, 1.0);
        }
        barterTickAt = now;
        // piglins ignore us while ANY gold piece is on, the helmet from the kit counts as much as boots do
        if (!PiglinGold.worn(f)) {
            if (boots == null) {
                boots = new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            hudState = "Putting on golden boots";
            return boots;
        }
        if (trade == null || tradeTarget != pearlsNeed) {
            trade = new TradeWithPiglinsTask(goldWanted, Items.ENDER_PEARL, pearlsNeed);
            tradeTarget = pearlsNeed;
        }
        hudState = "Trading with piglins";
        return trade;
    }

    // ---- shared

    private Task findStep(GamerContext ctx, FindNetherStructureTask task, String hud) {
        boolean fortress = task == findFortress;
        String failure = task.failure();
        if (failure != null && fortress) {
            return fail(ctx, "no fortress: " + failure);
        }
        hudState = hud;
        return task;
    }

    // one report per attempt, the engine decides what happens next (and onEnter resets this)
    private Task fail(GamerContext ctx, String reason) {
        if (!failedAlready) {
            failedAlready = true;
            ctx.fail(reason);
        }
        return null;
    }

    // two fireball hits inside the window: stop standing in the open and put some distance between us and the ghast
    // (not while the escape bridge runs: a run along a 1 wide bridge is a swim, the bridge holds still for the ball instead)
    private Task ghastCover(AltoClef mod, double now, boolean escaping) {
        Player p = mod.getPlayer();
        boolean freshHit = p.hurtTime > lastHurt;
        lastHurt = p.hurtTime;
        if (escaping) {
            return null;
        }
        if (freshHit && hitByGhast(p) && ghastWatch.onHit(now)) {
            Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(Ghast.class);
            BlockPos from = ghast.map(Entity::blockPosition).orElse(p.blockPosition());
            flee = new RunAwayFromPositionTask(FLEE_BLOCKS, from);
            fleeUntil = now + FLEE_SECONDS;
        }
        if (flee != null && now < fleeUntil) {
            hudState = "Dodging a ghast";
            return flee;
        }
        return null;
    }

    private static boolean hitByGhast(Player p) {
        DamageSource source = p.getLastDamageSource();
        return source != null && (source.getEntity() instanceof Ghast || source.getDirectEntity() instanceof LargeFireball);
    }
}
