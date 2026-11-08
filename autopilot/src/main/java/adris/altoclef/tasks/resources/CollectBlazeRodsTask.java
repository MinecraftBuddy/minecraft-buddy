package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.BrewFireResistanceTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.ui.HudText;
import adris.altoclef.tasks.speedrun.gamer.DeathZones;
import adris.altoclef.util.helpers.BlazeCover;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.FightOpenRules;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.SafeStand;
import adris.altoclef.util.helpers.PotionHelper;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BrewRules;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import adris.altoclef.util.helpers.RunZones;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectBlazeRodsTask extends ResourceTask {

    // blazes spawn within 4 sideways of the spawner, standing this close keeps every new one in reach
    private static final double CAMP_RADIUS = 2;
    // close enough to the camp cell (it is a standable cell, not a spawner block)
    private static final double CAMP_SPOT_RADIUS = 1.5;
    private static final double PILLBOX_RADIUS = 2.5;
    // (at 12 the rods outside the box lay till they despawned, a blaze was always that near: 12 blazes killed for 1 rod and for
    // 0, realfort-a-bent-i runs 2 and 4. Tuning pillbox_quiet, the default the rod drills' yardstick plays)
    private static final double PILLBOX_QUIET = 3;
    private final int _count;
    // by bricks that show (an anti-xray fakes hidden ones in nearly every chunk: from 8 Nov 2026 on the rig, "New searcher"
    // at a median 16 blocks from the buddy against 161 before)
    private final Task _searcher = SearchChunkForBlockTask.exposed(Blocks.NETHER_BRICKS);
    // the fight logic lives in there. this task used to just camp, silently, while blazes it had decided not to see shot at it
    private final BlazeFight _fight = new BlazeFight();
    // fire resistance from the kit's water bottles: fire was over half the nether's damage, and the blazes' fireballs, lava and
    // the burning are all fire. the wart and the cream are taken as they come along, never a trip of their own
    private final BrewFireResistanceTask _brew = new BrewFireResistanceTask(BrewRules.BATCH);
    // (Tuning cream_range, here and in CollectMagmaCreamTask: one default, the one the brew drills played)
    static final double CREAM_RANGE = 48;
    // (a count of the cubes about while the cream is wanted, every 20 s: how many, how far, why each was left; game ticks)
    private static final int CREAM_LOG_TICKS = 400;
    private int _creamLogAt = -1;
    private static final double WART_RANGE = 48;
    // once begun the brew is seen through: the stand and its powder take the two rods, and a gate that counted them again let go
    // of it with the stand standing and the bottles still in the bag. a brew that takes this long (the fights in between
    // count) is given up, the rods matter more (game ticks)
    private static final int BREW_BUDGET_TICKS = 8 * 60 * 20;
    private int _brewSince = -1;
    private boolean _brewGaveUp;
    // the buddy's fork, Tuning brew_box: the brew walled in (BrewBoxTask), made when a brew begins; null with the tuning off
    private BrewBoxTask _brewBox;
    // a fight has the brew's ticks now (logged once per take over)
    private boolean _brewCutOff;
    // the buddy's fork, Tuning box_home (BoxHomeRules): the standing box holds the buddy this tick (it stands and the order's
    // rods are not all in), the sealed heal under way (a BrewBoxTask in heal mode: the look out walled, the wait in the back
    // cell, the wall broken), the tick a failed seal may be tried again, the errands said skipped for this box, the spawner
    // whose pack was said not waited for, and the rod trip (homeRodFetch)
    private boolean _homeHolds;
    private BrewBoxTask _healBox;
    private long _healRetryAt = Long.MIN_VALUE;
    private float _healBeganHp;
    private final java.util.Set<String> _homeSaid = new java.util.HashSet<>();
    private BlazePillboxTask _homeSaidFor;
    private BlockPos _homePackSaidFor;
    // the rod a trip out is for (null with none), the tick it began, the buddy's best health on it and the rods in the bag
    // then, whether the buddy was seen out of the box on it; a trip over whose way back is still to be said (the tick it
    // began, -1 for none); the tick the last trip ended by a hit; the rods let go for this box and the rods said left (id
    // and reason)
    private net.minecraft.world.entity.item.ItemEntity _trip;
    private long _tripAt = -1;
    private float _tripBestHp;
    private int _tripRods;
    private boolean _tripOut;
    private long _tripBackFrom = -1;
    private long _tripHitAt = Long.MIN_VALUE / 2;
    private final java.util.Set<Integer> _rodsLetGo = new java.util.HashSet<>();
    private final java.util.Set<String> _rodLeftSaid = new java.util.HashSet<>();
    // a seal that failed waits this long before the next (ticks)
    static final long HEAL_RETRY_TICKS = 600;
    // the buddy's fork, Tuning fr_trade: the barter for the first fire resistance, one per order (kept through interruptions);
    // null with the tuning off
    private FireResTrade _frTrade;
    // the buddy's fork, Tuning brew_errand (on by default since the route, steering 118): the cream and the wart fetched on an errand of their own before the
    // spawner fight, brewErrand. under way, ended (one per order, done or given up), the step it is on, when the errand began,
    // its last tick, the step's own ticks and its waiting ticks with nothing in sight (BrewErrandRules.own, noSight), its
    // tasks, the tick the trade was seen over (-1: not yet), and this tick's word to the rest of the tick: brewStep's chance
    // errands held off, the camp not taken (the buddy waits). all of it unused with the switch off
    private boolean _errandOn;
    private boolean _errandEnded;
    private adris.altoclef.util.helpers.BrewErrandRules.Step _errandStep;
    private long _errandAt;
    private long _errandLastTick;
    private long _errandStepTicks;
    private long _errandNoSight;
    private CollectMagmaCreamTask _errandCream;
    private Task _errandWart;
    private long _tradeOverAt = -1;
    private boolean _errandHolds;
    private boolean _errandWaits;
    // ---- the route through the nether (RouteRules, steering 118 to 120): the last phase said; whether this task is an order of
    // rods of its own (the catalogue's: the gamer's nether phase goes on to a plan of its own, with no exit here); the no-potion
    // line asked; the cream hunt on the trade's search and whether one is under way; the brew's pillbox not to be planned again
    // at this spawner, and whether its cornering is awaited; the portals seen in the nether (feet cells, oldest first) and the
    // next look for one; the exit's tasks, where its walk goes and when it began
    private int _routePhase;
    private final boolean _bareOrder;
    private boolean _noPotionSaid;
    private CollectMagmaCreamTask _tradeCream;
    private boolean _tradeCreamOn;
    private BlockPos _routeBoxFailedFor;
    private boolean _routeBoxWaits;
    private final java.util.List<BlockPos> _portalsSeen = new java.util.ArrayList<>();
    private long _portalNoteAt = Long.MIN_VALUE / 2;
    private Task _exitEnter;
    private BlockPos _exitWalkTo;
    private Task _exitWalk;
    private long _exitAt = -1;

    private BlockPos _foundBlazeSpawner = null;
    // where we camp: a cell out past the spawn area (LavaShore.campSpot), picked once per spawner. null = the room had none, so
    // the old stand-by-the-spawner camp is the fallback
    private BlockPos _campSpot;
    private BlockPos _campSpotFor;
    // whether the camp was picked fire-proof (BlazeFight.fireproofCamp): a potion drunk or run out picks it again
    private boolean _campFireproof;
    // the buddy's fork: the corner camp, when the room has one (BlazeCover.corner): the camp spot is its corner, and the lure
    // looks round it from the peek cell
    private BlazeLureTask _lure;
    // the buddy's fork: a pillbox where no corner is (BlazePillboxTask), and whether its cell was made the camp's corner
    private BlazePillboxTask _pillbox;
    // the last corner the pillbox gave the fight (a raised box moves it up once B's floor is in)
    private BlockPos _pillboxCornerSet;
    // when the standing box was last looked over for blocks gone (steering 36)
    private long _repairCheckedAt = Long.MIN_VALUE / 2;
    static final long REPAIR_CHECK = 40;
    private boolean _pillboxCornered;
    // the spawner whose pillbox was given up on, more than SHORT_OK of its blocks open: its camp is picked again without a box
    private BlockPos _pillboxGaveUpFor;

    // ---- the buddy's fork, Tuning far_box (off by default, steering 89): the far pillbox, planned outside the spawner's range
    // (BlazePillbox.planFar), and its spawn trips (FarTrip). _far is the plan of _farBox, the pillbox it gave; any other box,
    // or none, and the far plan is gone. all of it stays null and unused with the switch off
    private adris.altoclef.util.helpers.BlazePillbox.Far _far;
    private BlazePillboxTask _farBox;
    private BlockPos _farTripCell;
    private GetToBlockTask _farWalk;
    private final adris.altoclef.util.helpers.FarTrip _farTrip = new adris.altoclef.util.helpers.FarTrip();
    // the spawner the far box was given up at, the one the strategy's begun line was said for, and the clocks of its lines
    private BlockPos _farGaveUpFor;
    private BlockPos _farBegunFor;
    private long _netherSince = -1;
    private long _farBegunAt = -1;
    private long _farPlannedAt = -1;
    private long _farBackSince = -1;
    private boolean _farTwoRods;
    private boolean _farDone;
    private int _farTrips;
    private int _farKills;
    // the live blazes within FAR_KILL_NEAR of the box's cell, watched for their death: a kill at the box
    private final java.util.Map<Integer, Blaze> _farWatched = new java.util.HashMap<>();
    static final double FAR_KILL_NEAR = 8;
    // the fight's run is into the box from as far as the trip's walk and this many steps more (BlazeFight.farRun)
    static final int FAR_RUN_SLACK = 4;
    // the far plan places no more than the bag's blocks less this many (Tuning far_box_slack): the walk to it may tower or bridge
    static final double FAR_BAG_SLACK = 2;
    // the blocks a far box's floor may be: the fortress's own, never netherrack or a block the buddy placed
    private static final java.util.Set<Block> FORTRESS_FLOOR = java.util.Set.of(Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS,
            Blocks.CHISELED_NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_STAIRS);

    // true on ticks where we stand by the spawner waiting for blazes, so the gamer can give up on a dud spawner
    private boolean _camping;
    // the cell we step to when the camp spot is a rim, and how many ticks that walk has had
    private BlockPos _campStep;
    private int _campStepTicks;
    // true on ticks where the walk to the spawner is what we are doing (not the fight, not the camp, not the fire)
    private boolean _toSpawner;

    // nether death spots, asked on the main thread (the gamer hands in its death book, a bare task has none)
    private final Supplier<List<double[]>> _deathSpots;
    // the ender pearls the goal still asks for (the gamer's EyeMath.pearlsNeeded; a bare order of rods asks none): the
    // fire resistance trade picks up a piglin's pearls while the bag holds fewer (FireResTradeRules.takesPearls)
    private final IntSupplier _pearlsNeeded;
    private final RunZones _deathZones = new RunZones();
    // what the path predicate sees, from baritone's thread: where we stand, the game clock and the spawner we walk to
    // (NaN = none). written every tick, same idea as FindNetherStructureTask
    private volatile int _zoneX;
    private volatile int _zoneZ;
    private volatile long _zoneNow;
    private volatile double _goalX = Double.NaN;
    private volatile double _goalZ = Double.NaN;
    // the zones are nether coordinates, the overworld walk to the portal must not see them as walls
    private volatile boolean _zoneNether;

    // an order of rods of its own (the catalogue's): the route's exit plays after the rods
    public CollectBlazeRodsTask(int count) {
        this(count, List::of, () -> 0, true);
    }

    public CollectBlazeRodsTask(int count, Supplier<List<double[]>> deathSpots) {
        this(count, deathSpots, () -> 0, false);
    }

    public CollectBlazeRodsTask(int count, Supplier<List<double[]>> deathSpots, IntSupplier pearlsNeeded) {
        this(count, deathSpots, pearlsNeeded, false);
    }

    private CollectBlazeRodsTask(int count, Supplier<List<double[]>> deathSpots, IntSupplier pearlsNeeded, boolean bareOrder) {
        super(Items.BLAZE_ROD, count);
        _count = count;
        _deathSpots = deathSpots;
        _pearlsNeeded = pearlsNeeded;
        _bareOrder = bareOrder;
        if (bareOrder) adris.altoclef.util.helpers.RouteRules.reset();
    }

    // the route's phase line, once, when the route first reaches it (RouteRules)
    private void routePhase(int phase) {
        if (!adris.altoclef.util.helpers.RouteRules.says(_routePhase, phase)) return;
        _routePhase = phase;
        Debug.logInternal(adris.altoclef.util.helpers.RouteRules.line(phase));
    }

    // (the route's exit, Tuning route_exit: an order of rods of its own is done only once out of the nether, or with the exit
    // given up; the arrival is said here, where it is first seen)
    @Override
    public boolean isFinished(AltoClef mod) {
        if (!super.isFinished(mod)) return false;
        if (!_bareOrder) return true;
        boolean nether = WorldHelper.getCurrentDimension() == Dimension.NETHER;
        if (!nether) adris.altoclef.util.helpers.RouteRules.out();
        return !adris.altoclef.util.helpers.RouteRules.exitHolds(adris.altoclef.util.helpers.RouteRules.exitOn(), nether,
                adris.altoclef.util.helpers.RouteRules.exitGaveUp());
    }

    // the middle of the wither pack around a spot, null when there is no pack there
    public static double[] witherPackCenter(AltoClef mod, Vec3 at, double radius) {
        return BlazeFightRules.packCenter(BlazeFight.witherSkeletonSpots(mod, at, radius), BlazeFightRules.ZONE_PACK_SIZE);
    }

    private void noteZoneInputs(AltoClef mod) {
        _zoneX = mod.getPlayer().blockPosition().getX();
        _zoneZ = mod.getPlayer().blockPosition().getZ();
        _zoneNow = mod.getWorld().getGameTime();
        _zoneNether = WorldHelper.getCurrentDimension() == Dimension.NETHER;
        _goalX = _foundBlazeSpawner == null ? Double.NaN : _foundBlazeSpawner.getX();
        _goalZ = _foundBlazeSpawner == null ? Double.NaN : _foundBlazeSpawner.getZ();
        _deathZones.replaceAll(_deathSpots.get(), _zoneNow, DeathZones.RADIUS);
    }

    public boolean isCampingSpawner() {
        return _camping;
    }

    // read once per phase tick and cleared, so a tick this task did not get to run (a rod pickup took the wheel) is not a walk
    public boolean takeWalkingToSpawner() {
        boolean was = _toSpawner;
        _toSpawner = false;
        return was;
    }

    // every live blaze within range, in reach or not. only for saying "they are here, just not in reach" on the hud
    public int blazesAround() {
        return _fight.around();
    }

    // hitting or running: not waiting on the spawner
    public boolean isBusyWithBlazes() {
        return _fight.mode() != BlazeFightRules.Mode.CAMP;
    }

    // the spawner this task is working on right now, null while it is still looking. the gamer blacklists this one when
    // it gives up, not whatever the tracker would call nearest
    public BlockPos currentSpawner() {
        return _foundBlazeSpawner;
    }

    // the walk to this spawner goes nowhere. only this one block is banned (the ban book honours it, so the pick below
    // skips it), true when another spawner took its place. none = the caller decides what a fortress with no way in is worth
    public boolean dropSpawner(AltoClef mod) {
        if (_foundBlazeSpawner == null) {
            return false;
        }
        mod.getBlockTracker().requestBlockUnreachable(_foundBlazeSpawner, 0, "no way to the blaze spawner");
        _foundBlazeSpawner = null;
        pickSpawner(mod);
        return _foundBlazeSpawner != null;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        // (the hiding numbers start over for a new order only, which starts away from the corner: the task starts again after
        // every interruption too, and a reset each time left a run's numbers with its last stretch alone)
        BlockPos was = adris.altoclef.util.helpers.BlazeShelter.fresh(mod.getWorld().getGameTime());
        if (was == null || mod.getPlayer().position().distanceTo(Vec3.atBottomCenterOf(was)) > adris.altoclef.util.helpers.BlazeShelter.RANGE) {
            adris.altoclef.util.helpers.BlazeShelter.resetHide();
        }
        mod.getBlockTracker().trackBlock(Blocks.SPAWNER);
        mod.getBlockTracker().trackBlock(Blocks.NETHER_WART);
        // (the route's exit: the portals met, the one the buddy came through first, kept in mind for the way out)
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        // the fight is ours: mob defense chasing a hovering blaze with a sword is how this used to go wrong. its projectile
        // shield and dodge do not look at this list, and the combat stance (no eating mid volley) still counts them
        mod.getBehaviour().addMobDefenseExclusion(entity -> entity instanceof Blaze);
        // the aura swinging and shielding at a blaze 6 blocks off pauses the path we need to get out of its sight, only
        // the ones next to us are for it
        mod.getBehaviour().addForceFieldExclusion(entity -> entity instanceof Blaze && mod.getPlayer().distanceTo(entity) > 3.5);
        // no bridging or pillaring while fighting or running, see BlazeFight.noPlacing
        mod.getBehaviour().avoidBlockPlacing(_fight::noPlacing);
        // (the buddy's fork: the pocket's walls stay: the walk at a blaze behind one broke it, and the hole let the room see in,
        // 4 Nov 2026)
        mod.getBehaviour().avoidBlockBreaking(_fight::pocketWall);
        // the base class pushed our behaviour level and pops it on stop. the pack closets and where we died lately are walls for
        // the walk in (the player and the spawner being inside one still keeps it open, see RunZones)
        noteZoneInputs(mod);
        mod.getBehaviour().avoidWalkingThrough(p -> _zoneNether
                && (RunZones.INSTANCE.blocks(p.getX(), p.getZ(), _zoneNow, _zoneX, _zoneZ, _goalX, _goalZ)
                || _deathZones.blocks(p.getX(), p.getZ(), _zoneNow, _zoneX, _zoneZ, _goalX, _goalZ)));
    }

    // the buddy's fork: the rods lost on the ground, counted every tick (a pickup's ticks skip onResourceTick)
    private final RodWatch _rodWatch = new RodWatch();
    // the buddy's fork, Tuning rod_snowball, off by default: the snowball strategy's state, one per rods task, so each rods order
    // begins it anew (not in onResourceStart: the task starts again after every interruption, a meal or a mob defense's run)
    private final RodSnowball _snow = new RodSnowball();

    @Override
    protected Task onTick(AltoClef mod) {
        _rodWatch.tick(mod);
        rushTick(mod);
        if (_brewBox != null) _brewBox.noteHits(mod);
        if (_healBox != null) _healBox.noteHits(mod);
        // (the buddy's fork, Tuning rod_snowball: the blazes' points for the lead, the kills and the strategy's two ends, on a
        // pickup's ticks too)
        if (RodSnowball.on()) _snow.watch(mod);
        return super.onTick(mod);
    }

    // the camp's point the snowballs are thrown from (RodSnowball): the crowd's hiding place, a standing pillbox's cell, the camp
    // spot (a corner's cell among them), else the spawner's top
    private BlockPos snowStand() {
        if (_fight.hidePlace() != null) return _fight.hidePlace();
        if (_pillbox != null && _pillboxCornered) return _pillbox.cell();
        if (_campSpot != null) return _campSpot;
        return _foundBlazeSpawner == null ? null : _foundBlazeSpawner.above();
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        _camping = false;
        _toSpawner = false;
        noteZoneInputs(mod);
        // the fight only sets this on ticks it runs, the walk to the nether or a rod pickup should not inherit it
        _fight.idle();
        routePhase(adris.altoclef.util.helpers.RouteRules.NETHER);
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        notePortals(mod);
        // (the buddy's fork, Tuning far_box: the clock of the strategy's lines starts with the first tick in the nether)
        if (_netherSince < 0 && farOn()) _netherSince = mod.getWorld().getGameTime();
        // the spawner is what starts the dry timer, finding one only between fights meant a long fight never found it
        pickSpawner(mod);
        // (Tuning fr_hold: by this spawner the held potion goes down at the first fire; the open attack counts at it)
        if (_foundBlazeSpawner != null) adris.altoclef.util.helpers.FireRes.spawnerSeen(mod.getWorld().getGameTime(), _foundBlazeSpawner);
        _fight.spawner(_foundBlazeSpawner);
        noteRods(mod);
        campDry(mod);
        // (the buddy's fork, Tuning far_box: the far box's kills and the two rods, watched every tick, the fight's too)
        if (_farBegunFor != null) farWatch(mod);
        // (again: the goal the walk is judged against is the spawner we just picked, not last tick's)
        noteZoneInputs(mod);
        fireproofFallback(mod);
        // (the buddy's fork, Tuning fr_trade, on by default since the route (steering 118; steering 89): the first fire resistance bartered from piglins before
        // the fortress, FireResTrade. a splash potion of it is thrown at the feet when the drink would go down, a drinkable one
        // the food chain drinks as it does the brewed one)
        if (adris.altoclef.util.helpers.Tuning.get("fr_trade", 0) > 0) {
            if (_frTrade == null) _frTrade = new FireResTrade(_pearlsNeeded);
            Task splash = _frTrade.throwStep(mod);
            if (splash != null) {
                setDebugState("Throwing fire resistance at the feet");
                return splash;
            }
            Task trade = _frTrade.tick(mod, _foundBlazeSpawner, within -> _fight.takesWithin(mod, within));
            if (_frTrade.begun()) routePhase(adris.altoclef.util.helpers.RouteRules.TRADE);
            if (!_frTrade.live()) noteNoPotion(mod);
            if (trade != null) {
                // (Tuning route_cream_trade: a magma cube in sight on the search's walk is taken along for its cream)
                Task cream = tradeCream(mod);
                if (cream != null) return cream;
                setDebugState(_frTrade.hud());
                return trade;
            }
            _tradeCreamOn = false;
        }
        // (the buddy's fork, Tuning box_home, off by default: whether the standing box holds the buddy this tick, BoxHomeRules)
        homeTick(mod);
        // (the buddy's fork: the camp was picked with or without fire resistance; a potion brewed and drunk here, or one run
        // out, is a camp of the other kind, so the pick goes again and a box under way is dropped)
        // (box_home: not while the box holds; the camp of the potion is the box's open side, a step out of it)
        if (_campSpotFor != null && BlazeFight.fireproofCamp(mod.getPlayer()) != _campFireproof && homeHolds()) {
            homeSkip("the fire-proof camp at the box's open side");
        } else if (_campSpotFor != null && BlazeFight.fireproofCamp(mod.getPlayer()) != _campFireproof) {
            Debug.logInternal("blaze camp: fire resistance " + (_campFireproof ? "gone" : "on") + ", the camp picked again");
            notePillboxSpot();
            _campSpotFor = null;
            _campSpot = null;
            _pillbox = null;
            _pillboxCornered = false;
            _lure = null;
            _fight.corner(null);
            _fight.pillboxPlanned(false);
            _fight.pillboxLookout(null);
            // (the buddy's fork, Tuning fireproof_repick, off by default, steering 84: the camp picked again at once. it was
            // picked only while camping, and under the potion the fight always had a blaze to chase, so the camp of the potion
            // (the pillbox's open side) was never taken and the rods lay out of reach, Kai in piece2side-2 run 1)
            if (adris.altoclef.util.helpers.Tuning.get("fireproof_repick", 0) > 0 && _foundBlazeSpawner != null) {
                pickCampSpot(mod);
                Debug.logInternal("blaze camp: picked again at once (fireproof_repick), " + (_campSpot != null ? "at " + _campSpot.toShortString() : "by the spawner"));
            }
        }
        // (the buddy's fork: a pillbox given up on with more than SHORT_OK of its blocks open is no cover. kept, its bare cell
        // stayed the fight's corner and the camp, runs into it burned there, and nothing picked the camp again (the dry watch
        // passes over a camp with a box): the camp is picked again with no box at this spawner, the open camp as before the box)
        if (_pillbox != null && _pillbox.isFinished(mod) && !_pillbox.nearlyBuilt(mod)) {
            // (the far box given up leaves the near pillbox its turn, the camp as without the switch: with _pillboxGaveUpFor set
            // too, a far box whose walk never arrived left the open camp by the spawner, strat3 t1)
            boolean farBox = _far != null && _pillbox == _farBox;
            if (farBox) {
                Debug.logInternal("strategy far: given up, the box at " + _pillbox.cell().toShortString() + " was not built"
                        + (_pillbox.walkStuck() ? " (the walk to it came no closer)" : "") + ", the camp as without it");
                _farGaveUpFor = _foundBlazeSpawner;
            }
            Debug.logInternal("blaze camp: the pillbox at " + _pillbox.cell().toShortString() + " given up, the camp picked again without " + (farBox ? "the far box" : "one"));
            if (!farBox) _pillboxGaveUpFor = _foundBlazeSpawner;
            // (the buddy's fork, Tuning box_home: a box given up is not the end of boxes at this spawner, the next goes up where
            // the buddy stands, at most BoxHomeRules.RETRIES more here, homeRetry; the bar above keeps the same pick off)
            if (adris.altoclef.util.helpers.BoxHomeRules.on() && !farBox && _foundBlazeSpawner != null) {
                int k = adris.altoclef.util.helpers.BoxHomeRules.retry(false, _homeTries.getOrDefault(_foundBlazeSpawner, 0));
                _homeRetryFor = k > 0 ? _foundBlazeSpawner : null;
                _homeRetryWaitSaid = false;
                Debug.logInternal("box home: the box at " + _pillbox.cell().toShortString() + " given up, "
                        + (k > 0 ? "a new box where the buddy stands next, try " + k + " of " + adris.altoclef.util.helpers.BoxHomeRules.RETRIES
                        : "no tries left at the spawner " + _foundBlazeSpawner.toShortString() + " (" + adris.altoclef.util.helpers.BoxHomeRules.RETRIES
                        + " made), the camp as without one"));
            }
            _campSpotFor = null;
            _campSpot = null;
            _pillbox = null;
            _pillboxCornered = false;
            _pillboxCornerSet = null;
            _lure = null;
            _fight.corner(null);
            _fight.pillboxPlanned(false);
            _fight.pillboxLookout(null);
        }
        // (the buddy's fork, Tuning brew_box: shut in the brew box with nothing hostile inside, no fire and the health at
        // BrewBox.LOW_HP or more, the fight sits out and the brew goes on (BrewBoxTask.holds); the stamp stands the mob defense
        // down too. the open brew was cut off by a fight five times in ten runs and never finished)
        if (_brewBox != null && _brewBox.holds(mod)) {
            adris.altoclef.util.helpers.BlazeShelter.brewBox(mod.getWorld().getGameTime());
            Task boxed = brewStep(mod);
            if (boxed != null) return boxed;
        }
        // (the buddy's fork: a shut brew box being opened, with the buddy inside, before the fight. the fight cut the door's break
        // off, the box was done with its door standing, and the buddy stayed shut in its own box, the fight's corner, whose
        // blocks no walk may break: twelve game minutes to the stall, piece2in-1 run 8)
        if (_brewBox != null && _brewBox.breakingOut(mod)) {
            Task out = brewStep(mod);
            if (out != null) return out;
        }
        // (the buddy's fork: the brew begun outside its pillbox: in first, before the fight, BrewBoxTask.goingIn)
        if (_brewBox != null && _brewBox.goingIn(mod)) {
            Task in = brewStep(mod);
            if (in != null) {
                setDebugState("Into the pillbox for the brew");
                return in;
            }
        }
        // (the buddy's fork, Tuning box_home: a heal sealed in the box, before the fight while the wall holds)
        if (_healBox != null) {
            Task heal = healStep(mod);
            if (heal != null) return heal;
        }
        // (the buddy's fork, Tuning camp_reentry, off by default: shut out of the camp, a pillbox where the buddy stands, another
        // corner or none, campReentry. box_home turns it on: a box not got back into is replaced where the buddy stands)
        if (adris.altoclef.util.helpers.CampReentry.on()) campReentry(mod);
        // (the buddy's fork, Tuning box_home: a try owed after a box given up, a box where the buddy stands, homeRetry)
        if (_homeRetryFor != null) homeRetry(mod);
        // (the buddy's fork, Tuning box_home: at a known spawner the camp, so the box, is picked before anything else, the cream
        // hunt and the hiding place among them; the build below then comes first. a pack at the spawner is no reason to wait:
        // a wither skeleton does not fit the box)
        if (adris.altoclef.util.helpers.BoxHomeRules.on() && _foundBlazeSpawner != null && !_foundBlazeSpawner.equals(_campSpotFor)
                && mod.getClientBaritone().bsi != null) {
            pickCampSpot(mod);
            if (_foundBlazeSpawner.equals(_campSpotFor)) {
                Debug.logInternal("box home: the camp first at the spawner " + _foundBlazeSpawner.toShortString() + ", "
                        + (_pillbox != null ? "a pillbox at " + _pillbox.cell().toShortString() + ", its clock from the cell"
                        : _campSpot != null ? "no box fits, the camp at " + _campSpot.toShortString() : "no box fits, by the spawner"));
            }
        }
        // blazes first: kill the one we picked, run when it goes badly. null is "nobody to fight"
        boolean building = _pillbox != null && !_pillbox.isFinished(mod);
        // (the cell is the fight's corner from its first block on, the wall nearest the spawner: a run then goes into it, not out
        // over the open bridge where the run of every block died; bare, it was no cover and runs stood in the fire there.
        // with nowhere to stand out of the blazes' fire there was nowhere to go, 4 Nov 2026)
        // (a raised box's corner is the cell on the ground under it till B's floor is in, then the cell up there)
        if (building && (_fight.corner() == null || _fight.corner().equals(_pillboxCornerSet)) && _pillbox.begun(mod)
                && !_pillbox.corner(mod).equals(_fight.corner())) {
            _pillboxCornerSet = _pillbox.corner(mod);
            _fight.corner(_pillboxCornerSet);
            Debug.logInternal("blaze camp: the pillbox's first wall stands, its cell the corner at " + _pillboxCornerSet.toShortString());
        }
        if (building) adris.altoclef.util.helpers.BlazeShelter.building(mod.getWorld().getGameTime());
        if (_pillbox != null && _pillboxCornered) adris.altoclef.util.helpers.BlazeShelter.boxStands(mod.getWorld().getGameTime());
        // (the standing box's way in, for every walk into the corner: round by its door, BoxWay)
        BlazePillboxTask standing = _pillbox != null && _pillboxCornered ? _pillbox : null;
        adris.altoclef.util.helpers.BlazeShelter.box(standing == null ? null : standing.cell(), standing == null ? null : standing.door(),
                standing == null ? null : standing::holds);
        // (a brew box going up is built as a pillbox is: only a blaze that bites is fought meanwhile, its blocks may be placed)
        // (box_home's sealed heal walls the look out as the brew box does: the same)
        boolean healBuilding = _healBox != null && _healBox.building();
        boolean brewBuilding = (_brewBox != null && _brewBox.building()) || healBuilding;
        _fight.building(building || brewBuilding);
        java.util.Set<BlockPos> exempt = building ? _pillbox.blocks() : java.util.Set.of();
        if (brewBuilding) {
            exempt = new java.util.HashSet<>(exempt);
            if (_brewBox != null) exempt.addAll(_brewBox.blocks());
            if (healBuilding) exempt.addAll(_healBox.blocks());
        }
        _fight.pillboxBlocks(exempt);
        Task fight = _fight.tick(mod);
        if (_fight.mode() != BlazeFightRules.Mode.CAMP) {
            // the fight moves us, the next camp spot is a new question
            _campStep = null;
            _campStepTicks = 0;
        }
        // (the buddy's fork, Tuning rod_snowball, off by default: the first two rods by snowballs thrown from the camp, RodSnowball.
        // at the camp with a blaze to throw at, the throws; in KILL mode with nothing to throw at and the sword's blaze out past
        // the melee's reach, no chase: the camp goes on and its looks bring the blaze into a line)
        if (RodSnowball.on() && _foundBlazeSpawner != null) {
            Task thrown = _snow.step(mod, _fight, fight, snowStand(), building || brewBuilding);
            if (thrown != null) {
                setDebugState("Blaze fight: snowballs");
                return thrown;
            }
            if (_snow.holdsMelee()) fight = null;
        }
        if (fight != null) {
            // (a brew under way that a fight takes over, counted for steering 49: how often the open brew is cut off)
            if (_brewSince >= 0 && !_brewCutOff) {
                _brewCutOff = true;
                Debug.logInternal("brew: cut off by the fight (" + _fight.mode().name().toLowerCase() + ")");
            }
            setDebugState("Blaze fight: " + _fight.mode().name().toLowerCase());
            return fight;
        }
        _brewCutOff = false;
        // (the buddy's fork: with a pillbox going up, the heal wait in its cell may go on building, Tuning pillbox_heal_build: in
        // the drill room the wait stood in the bare cell under fire and the box was never finished, bent-f; in real fortresses
        // realfort-a-bent-h without it got 10 rods against bent-g's 22, so on by default)
        // (Tuning heal_build_near, off by default: that only within the leash of the box's cell. in a cover elsewhere the build's
        // walk left it and the run's walked back, every half second, on fire, realfort-ab-leash-3 run 4)
        boolean healBuild = building && adris.altoclef.util.helpers.Tuning.get("pillbox_heal_build", 1) > 0
                && (adris.altoclef.util.helpers.Tuning.get("heal_build_near", 0) <= 0 || (_pillbox != null
                && mod.getPlayer().position().distanceTo(Vec3.atBottomCenterOf(_pillbox.cell())) <= BlazeFight.PILLBOX_LEASH));
        if (_fight.mode() == BlazeFightRules.Mode.RUN && !healBuild) {
            // out of their sight and healing, the spawner can wait. standing here is the plan so it counts as camping, unless
            // there are blazes we could go get, then it is just a breather and not a dud spawner
            setDebugState("Recovering away from the blazes");
            // a run that stopped healing is dead time just like a dud spawner, the rods watch clocks have to see it
            _camping = _fight.reachable() == 0 || _fight.runGaveUp();
            // (the buddy's fork, Tuning box_home: the heal wait in the box is sealed, BoxHomeRules.sealHeal)
            Task seal = sealHealStart(mod);
            if (seal != null) return seal;
            return null;
        }

        // (the route's exit, Tuning route_exit: an order of rods of its own with its count in the bag goes back through a portal;
        // the fight and the heal above come first)
        if (_bareOrder && adris.altoclef.util.helpers.RouteRules.exitOn() && !adris.altoclef.util.helpers.RouteRules.exitGaveUp()
                && super.isFinished(mod)) {
            Task out = exitStep(mod);
            if (out != null) return out;
        }

        // (the buddy's fork: a pillbox under way goes up before the hiding and the looks; the fight above comes first)
        if (_pillbox != null && !_pillbox.isFinished(mod)) {
            setDebugState("Building a pillbox by the spawner");
            return _pillbox;
        }
        if (_pillbox != null && _pillbox.nearlyBuilt(mod) && !_pillboxCornered && mod.getClientBaritone().getPlayerContext() != null) {
            _pillboxCornered = true;
            // (the cell is the fight's corner only now it stands: as the corner while bare, every run went into it and stood
            // there in the fire, corner23's first check, both runs dead at the cell)
            _fight.corner(_pillbox.cell());
            // (the looks from the planned look out alone: the cells read here still had the room without the box's blocks, and the
            // looks went to cells through its walls, behind the back wall 108 times and never to the look out, roof-a run 4)
            _lure = new BlazeLureTask(_pillbox.cell(), _pillbox.lookout(), java.util.List.of(_pillbox.lookout()), java.util.List.of()).centred();
            Debug.logInternal("blaze camp: the pillbox stands, its cell the corner at " + _pillbox.cell().toShortString());
            if (_far != null && _pillbox == _farBox) {
                _farTrip.reset();
                Debug.logInternal("strategy far: the box stands at " + _pillbox.cell().toShortString() + ", " + secs(mod.getWorld().getGameTime() - _farPlannedAt)
                        + " s after it was planned");
            }
        }
        // (the box kept closed, steering 36: a block of it gone is put back in the next lull, from inside: every REPAIR_CHECK
        // ticks, camping, with no blaze within pillbox_quiet. off unless pillbox_repair)
        if (_pillbox != null && _pillboxCornered && adris.altoclef.util.helpers.Tuning.get("pillbox_repair", 0) > 0) {
            long now = mod.getWorld().getGameTime();
            if (now - _repairCheckedAt >= REPAIR_CHECK || now < _repairCheckedAt) {
                _repairCheckedAt = now;
                java.util.List<BlockPos> holes = _pillbox.holes(mod);
                if (!holes.isEmpty() && _fight.mode() == BlazeFightRules.Mode.CAMP
                        && _fight.nearestBlaze() > adris.altoclef.util.helpers.Tuning.get("pillbox_quiet", PILLBOX_QUIET)) {
                    _pillbox.repair(mod, holes);
                    setDebugState("Putting the pillbox's blocks back");
                    return _pillbox;
                }
            }
        }

        // (the buddy's fork: a crowd about, so into a hiding place of the fight's and the looks from it, see BlazeFight.noteCrowd)
        Task hide = _fight.hideTask(mod);
        // (the buddy's fork, Tuning lure_cap, off by default: a hide that drew no blaze into reach for the cap is over, its
        // pocket barred, BlazeFight.hideCapped; the camp's own corner as the hiding place is dropped too, and the tick goes on
        // as with no hide)
        // (the buddy's fork, Tuning box_home: no hiding place elsewhere while the box holds; one in the box is the box)
        if (hide != null && homeHolds() && _fight.hidePlace() != null && !_pillbox.holds(_fight.hidePlace())) {
            homeSkip("the hiding place at " + _fight.hidePlace().toShortString());
            hide = null;
        }
        if (hide != null) {
            BlockPos hidAt = _fight.hidePlace();
            if (_fight.hideCapped(mod)) {
                if (hidAt != null && hidAt.equals(_fight.corner())) dropEmptyCamp(mod, hidAt, -1, "hiding there");
                hide = null;
            }
        }
        if (hide != null) {
            setDebugState("Hiding from the blazes");
            _camping = true;
            return hide;
        }

        // (the buddy's fork, Tuning brew_errand, on by default since the route (steering 118): the cream and the wart on an errand of their own before the
        // spawner fight, after the fight's own tick, the boxes and the hiding place; while it is under way brewStep's chance
        // errands are held off, and by the spawner with nothing in sight the buddy waits rather than taking the camp)
        Task errand = brewErrand(mod);
        // (Tuning route_hold: the route's phase 3 under way, any fire resistance potion waits for the blazes, FireRes.holdsRoute)
        if (_errandOn) adris.altoclef.util.helpers.FireRes.routeHolds(mod.getWorld().getGameTime());
        if (errand != null) {
            return errand;
        }
        if (_errandWaits) {
            setDebugState(_errandStep == adris.altoclef.util.helpers.BrewErrandRules.Step.CREAM ? "Brew errand: waiting for a magma cube"
                    : "Brew errand: looking for a nether wart");
            return null;
        }
        Task brew = _errandHolds ? null : brewStep(mod);
        if (brew != null) {
            return brew;
        }
        // If we have a blaze spawner, stand next to it in the open and let them come.
        if (_foundBlazeSpawner != null) {
            // (the route: the trade and the errand over, the blazes now)
            if (_frTrade == null || !_frTrade.live()) routePhase(adris.altoclef.util.helpers.RouteRules.RODS2);
            // a wither skeleton pack at the spawner: hold where we are, the rods watch counts it as dud time and moves us on
            // if they never leave. walking in is how the last two runs ended
            // (the buddy's fork, Tuning far_box: not with a far box standing, out of the pack's way: the hold would keep the buddy
            // wherever it stood, out on a spawn trip too. the trips stay home while the pack is there, farTripStep)
            boolean farStands = _far != null && _pillbox == _farBox && _pillboxCornered;
            int pack = farStands ? 0 : BlazeFight.witherSkeletonsNear(mod, Vec3.atCenterOf(_foundBlazeSpawner), BlazeFightRules.PACK_SPAWNER_RADIUS);
            // (the buddy's fork, Tuning box_home: no wait for the pack; a wither skeleton is 2.4 high and does not fit the box, it is
            // fought one at a time at the opening)
            if (!farStands && BlazeFightRules.packNear(pack) && adris.altoclef.util.helpers.BoxHomeRules.on()) {
                if (!_foundBlazeSpawner.equals(_homePackSaidFor)) {
                    _homePackSaidFor = _foundBlazeSpawner;
                    Debug.logInternal("box home: " + pack + " wither skeletons within " + (int) BlazeFightRules.PACK_SPAWNER_RADIUS + " of the spawner "
                            + _foundBlazeSpawner.toShortString() + ", no wait, the box is home");
                }
            } else if (!farStands && BlazeFightRules.packNear(pack) && packWaits(mod, pack)) {
                setDebugState("wither skeletons at the spawner, waiting");
                _camping = true;
                return null;
            }
            // (the inland step may end past the camp radius, walking back to the spawner would undo it)
            pickCampSpot(mod);
            BlockPos camp = _campSpot != null ? _campSpot : _foundBlazeSpawner.above();
            // (a standing pillbox's look out is two steps from its cell, 1.5 from the cell's middle: at CAMP_SPOT_RADIUS every
            // look out to it was cut short by the walk back, "Going to blaze spawner" 23 times and 3 looks in six game minutes,
            // a stall with the box built whole, bent-d run 1)
            double campRadius = _campSpot != null ? (_pillbox != null && _pillboxCornered ? PILLBOX_RADIUS : CAMP_SPOT_RADIUS) : CAMP_RADIUS;
            // (the buddy's fork, Tuning far_box, off by default, steering 89: the far box's spawn trips, before the walk back into
            // it. out of the box into the spawner's range till a blaze has spawned and seen the buddy, then the walk below and the
            // lure take over)
            // (again after the pick: a new spawner's camp is a new box, or none)
            if (_far != null && _pillbox == _farBox && _pillboxCornered) {
                int trip = farTripStep(mod);
                if (trip == 1) {
                    setDebugState("Spawn trip: into the spawner's range");
                    _camping = true;
                    return _farWalk;
                }
                if (trip == 2) {
                    setDebugState("Spawn trip: waiting for a blaze to spawn and see the buddy");
                    _camping = true;
                    return null;
                }
            }
            // (the buddy's fork: outside a standing pillbox, round to its open side first. aimed at the cell itself, the walk from
            // a walled side drew the buddy to the wall nearest the cell, every block of the box protected, and the walk and the
            // lure took turns there till the stall: twelve game minutes, about eight stalls in the blocks of 6 Nov 2026 morning,
            // the yardstick's bent-n-ap-3 on 8 rods among them. from the door the way in through the look out works)
            BlockPos door = _campStep == null && _pillbox != null && _pillboxCornered && camp.equals(_pillbox.cell()) && _doorGaveUp != _pillbox ? _pillbox.door() : null;
            if (door != null && !_pillbox.holds(mod.getPlayer().blockPosition()) && !door.equals(mod.getPlayer().blockPosition())) {
                // (the door itself out of reach: "No path found" 49 times in three minutes on the way to it, rod-1 run 5. after
                // DOOR_WALK_TICKS this box gets the walk at its cell again)
                long now = mod.getWorld().getGameTime();
                if (_doorWalkSince < 0 || now < _doorWalkSince) _doorWalkSince = now;
                if (now - _doorWalkSince > DOOR_WALK_TICKS) {
                    _doorGaveUp = _pillbox;
                    Debug.logInternal("blaze camp: the pillbox's open side " + door.toShortString() + " not reached in " + DOOR_WALK_TICKS / 20 + " s, the walk at its cell");
                } else {
                    setDebugState("Round to the pillbox's open side");
                    _toSpawner = true;
                    return new GetToBlockTask(door, false);
                }
            } else {
                _doorWalkSince = -1;
            }
            if (_campStep == null && !camp.closerToCenterThan(mod.getPlayer().position(), campRadius) && rushWalk()) {
                camp = _campSpot != null ? _campSpot : _foundBlazeSpawner.above();
                campRadius = _campSpot != null ? CAMP_SPOT_RADIUS : CAMP_RADIUS;
            }
            if (_campStep == null && camp.closerToCenterThan(mod.getPlayer().position(), campRadius)) _rushWalkTicks = 0;
            if (_campStep == null && !camp.closerToCenterThan(mod.getPlayer().position(), campRadius)) {
                setDebugState("Going to blaze spawner (" + (RunZones.INSTANCE.active(_zoneNow) + _deathZones.active(_zoneNow)) + " zones)");
                _toSpawner = true;
                return new GetToBlockTask(camp, false);
            }
            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            // (not out of a standing pillbox for a fire by the spawner: the walk to it was out in every blaze's sight, bent-e
            // run 1; one in the box is still put out)
            // (Tuning fire_in_box, off by default: in the box means on its own cells. a fire outside the wall, 2.45 from the
            // cell's middle, was "in the box", its walk found no path, the wander put the buddy on the wall and out, and the box
            // was never got back into: dead outside it, realfort-ab-leash-3 run 1)
            boolean fireInBox = adris.altoclef.util.helpers.Tuning.get("fire_in_box", 0) > 0;
            // (the buddy's fork, Tuning box_home: while the box holds only a fire in its cells or its door is put out, from
            // inside; any other is left)
            if (homeHolds()) {
                BlockPos boxDoor = _pillbox.door();
                Optional<BlockPos> inBox = mod.getBlockTracker().getNearestWithinRange(_pillbox.cell(), 3, Blocks.FIRE)
                        .filter(f -> adris.altoclef.util.helpers.BoxHomeRules.fireOut(_pillbox.holds(f), f.equals(boxDoor)));
                if (inBox.isEmpty() && nearestFire.isPresent()) homeSkip("the fire walk");
                nearestFire = inBox;
            } else if (nearestFire.isPresent() && _pillbox != null && _pillboxCornered
                    && (fireInBox ? !_pillbox.holds(nearestFire.get())
                    : !nearestFire.get().closerToCenterThan(Vec3.atCenterOf(_pillbox.cell()), PILLBOX_RADIUS))) nearestFire = Optional.empty();
            // (the buddy's fork: a fire not put out after FIRE_TICKS spent on it is left for good. one out of reach was picked
            // again every tick, "No path found" over and over, and the camp's wait never came: three minutes at the spawner with
            // no rod, piece2side-2 run 2. ticks spent on it, not since it was first picked: a fight in between does not count)
            // (behind Tuning fire_limit, off by default, steering 78, with roof_centre under the same suspicion)
            boolean fireLimit = adris.altoclef.util.helpers.Tuning.get("fire_limit", 0) > 0;
            if (fireLimit && nearestFire.isPresent() && _fireLeft.contains(nearestFire.get())) nearestFire = Optional.empty();
            // (Tuning fireproof_rush: no fire put out while the effect has more than ten seconds left; the rods are picked up
            // at once, and the blazes light fires faster than they go out)
            if (nearestFire.isPresent() && FightOpenRules.fireWalkable(_rush)) nearestFire = Optional.empty();
            if (fireLimit && nearestFire.isPresent()) {
                if (!nearestFire.get().equals(_fireAt)) {
                    _fireAt = nearestFire.get();
                    _fireTicks = 0;
                }
                if (++_fireTicks > FIRE_TICKS) {
                    _fireLeft.add(_fireAt);
                    Debug.logInternal("blaze camp: the fire at " + _fireAt.toShortString() + " not put out in " + FIRE_TICKS / 20 + " s, left");
                    _fireAt = null;
                    nearestFire = Optional.empty();
                }
            }
            if (nearestFire.isPresent()) {
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(nearestFire.get());
            }
            // the camp spot may be a rim, one step inland first. no new mode, it is the same wait with a short walk in front
            Task inland = stepInland(mod);
            if (inland != null) {
                setDebugState("Stepping off the edge before camping");
                return inland;
            }
            // nothing is in reach or we would be fighting it, so this is a dud spawner as far as the relocate timer goes
            _camping = true;
            // (Tuning open_attack: no lure with fewer than three blazes about, the wait as without one)
            if (_lure != null && _fight.corner() != null && !_fight.open()) {
                // (the buddy's fork, Tuning lure_cap, off by default: looks that drew no blaze into reach for the cap drop this
                // corner (or standing pillbox), the camp picked again without it on the next tick: another corner, else as
                // with none)
                long cap = adris.altoclef.util.helpers.BlazeCapRules.lureCapTicks();
                BlockPos at = _fight.corner();
                // (box_home: the box is home while it holds, no lure cap drops it)
                if (cap > 0 && homeHolds() && _fight.corner().equals(_pillbox.cell())) cap = 0;
                if (cap > 0 && _campLureWatch.tick(BlazeCover.key(at.getX(), at.getY(), at.getZ()), mod.getWorld().getGameTime(), _fight.killAt(), cap)) {
                    dropEmptyCamp(mod, at, _lure.looks(), "looking out from it");
                    setDebugState("Waiting near blaze spawner for blazes to spawn");
                    return null;
                }
                setDebugState("Luring the blazes round the corner");
                return _lure;
            }
            setDebugState(_fight.around() > 0 ? "Waiting for the blazes to come in reach" : "Waiting near blaze spawner for blazes to spawn");
            return null;
        }
        // (the buddy's fork, Tuning nether_restock, steering 70 point 2: the throwaways topped up with netherrack on the way, as
        // #gamer's NetherPhase does, so a lava bridge or a pillbox never stops for blocks; never by the spawner: no spawner is
        // known here)
        if (adris.altoclef.util.helpers.Tuning.get("nether_restock", 0) > 0) {
            Task dig = _restock.tickPhase(mod, WorldHelper.getTicks() / 20.0);
            if (dig != null) {
                setDebugState("Restocking building blocks");
                return dig;
            }
        }
        // We need to find our fortress.
        // (the buddy's fork, Tuning fortress_sweep, steering 68: the region sweep with a memory per server and dimension, a
        // fortress a structure cell; the old search takes over once one is known near, or when the sweep gave up)
        if (adris.altoclef.util.helpers.Tuning.get("fortress_sweep", 0) > 0 && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            if (!_sweepTracking) {
                ObtainSweep.track(mod, true);
                _sweepTracking = true;
            }
            ObtainSweep sweep = ObtainSweep.of(mod);
            sweep.scan(mod);
            Task walk = sweep.step(mod);
            if (walk != null) {
                setDebugState("Sweeping the nether's structure cells for a fortress");
                return walk;
            }
            if (sweep.failed() && !_sweepFailLogged) {
                _sweepFailLogged = true;
                Debug.logInternal("fortress sweep: gave up (" + sweep.failure() + "), the old search");
            }
        }
        setDebugState("Searching for fortress/Traveling around fortress");
        return _searcher;
    }

    // brew_box 2: a failed box waits this long before the next (ticks), and the open brew's line once a brew
    static final int BREW_WAIT_TICKS = 1200;
    private int _brewWaitUntil = Integer.MIN_VALUE;
    private boolean _openLogged;

    private final adris.altoclef.tasks.speedrun.gamer.tasks.NetherBlockRestock _restock = new adris.altoclef.tasks.speedrun.gamer.tasks.NetherBlockRestock();

    private boolean _sweepTracking;
    private boolean _sweepFailLogged;

    // (the buddy's fork: a cube for the cream only in the fortress, on our own level, clear of lava and of any drop. one eleven
    // blocks down by a lava sea drew the hunt off a ledge into it, 24 seconds into the nether (attempt 223); with lava only
    // looked for round the cube, the next hunt went along a ledge over the sea, a skeleton's hit knocked the buddy off and it
    // fell eighteen blocks into it (227), 4 Nov 2026. a cube on the fortress's bricks is on its floor)
    private static boolean creamCube(AltoClef mod, MagmaCube cube) {
        return creamCubeWhyNot(mod, cube) == null;
    }

    // null when the cube may be hunted for its cream, else the first rule it fails (the count line's words)
    // (package-wide: CollectMagmaCreamTask hunts by the same rules)
    static String creamCubeWhyNot(AltoClef mod, MagmaCube cube) {
        return creamCubeWhyNot(mod, cube, false);
    }

    // anyLevel: the brew errand's hunt (Tuning brew_errand), a cube on any level of the fortress, the path finds the way
    static String creamCubeWhyNot(AltoClef mod, MagmaCube cube, boolean anyLevel) {
        var us = mod.getPlayer();
        if (us == null) return "level";
        // (Tunings cream_down and cream_up: how far below and above our feet a cube may be, 2 and 3 by default. in the fortress the
        // cubes in reach were nearly all on another level, brew2-s8-1, 5 Nov 2026)
        if (!anyLevel && (cube.getY() < us.getY() - adris.altoclef.util.helpers.Tuning.get("cream_down", 2)
                || cube.getY() > us.getY() + adris.altoclef.util.helpers.Tuning.get("cream_up", 3))) return "level";
        BlockPos at = cube.blockPosition();
        Block floor = mod.getWorld().getBlockState(at.below()).getBlock();
        // (Tuning cream_wild: also a cube met on the way to the fortress, on any sturdy ground but magma; the rules for lava, the
        // sea and drops below still hold. Off by default: in the fortress the floor is its bricks)
        boolean bricks = floor == Blocks.NETHER_BRICKS || floor == Blocks.NETHER_BRICK_SLAB || floor == Blocks.NETHER_BRICK_STAIRS;
        boolean wild = adris.altoclef.util.helpers.Tuning.get("cream_wild", 0) > 0 && floor != Blocks.MAGMA_BLOCK
                && mod.getWorld().getBlockState(at.below()).isFaceSturdy(mod.getWorld(), at.below(), net.minecraft.core.Direction.UP);
        if (!bricks && !wild) return "floor";
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -3, -3), at.offset(3, 1, 3))) {
            if (mod.getWorld().getBlockState(p).getBlock() == Blocks.LAVA) return "lava";
        }
        var cells = baritone.pathing.movement.CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        if (baritone.pathing.movement.CliffAvoidance.overSea(cells, at.getX(), at.getY(), at.getZ())) return "sea";
        if (baritone.pathing.movement.CliffAvoidance.dropNear(cells, at.getX(), at.getY(), at.getZ())) return "drop";
        // the kill task's own lava check (lava by us, by the cube, or on the line between): without it a cube passed here, the
        // kill held 8 s and banned it, and the ban's end picked it again, eleven times in three minutes (live watch, 7 Nov 2026)
        if (adris.altoclef.util.helpers.LavaShore.approachBlocked(cells, us.getX(), us.getY(), us.getZ(), cube.getX(), cube.getY(), cube.getZ())) return "shore";
        return null;
    }

    private static double creamRange() {
        return adris.altoclef.util.helpers.Tuning.get("cream_range", CREAM_RANGE);
    }

    // the count line: cubes that drop cream (size 2 and up) within the hunt's range and within 64, and per rule how many were left
    private void logCreamCubes(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_creamLogAt >= 0 && now - _creamLogAt < CREAM_LOG_TICKS) return;
        _creamLogAt = now;
        var us = mod.getPlayer();
        double range = creamRange();
        int near = 0, fit = 0, far = 0, small = 0;
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        java.util.List<Integer> dys = new java.util.ArrayList<>();
        for (MagmaCube c : mod.getEntityTracker().getTrackedEntities(MagmaCube.class)) {
            if (!c.isAlive()) continue;
            double d = c.distanceTo(us);
            if (d > 64) continue;
            if (c.getSize() <= 1) {
                small++;
                continue;
            }
            if (d > range) {
                far++;
                continue;
            }
            near++;
            String no = creamCubeWhyNot(mod, c);
            if (no == null) fit++;
            else why.merge(no, 1, Integer::sum);
            if ("level".equals(no) && dys.size() < 8) dys.add((int) Math.round(c.getY() - us.getY()));
        }
        Debug.logInternal("cream: " + near + " cubes within " + (int) range + " (" + fit + " fit" + (why.isEmpty() ? "" : ", left " + why)
                + (dys.isEmpty() ? "" : ", dy " + dys) + "), " + far + " more within 64, " + small + " tiny; rods " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD));
    }

    // the buddy's fork, Tuning brew_errand, on by default since the route (steering 118): the cream and the wart before the spawner fight, so the first two
    // rods make the stand and its powder, brewStep brews at once beside the spawner (the bag then holds what the fortress
    // start's cream kit gives) and the rest of the rods follow without leaving again. it begins once the fire resistance trade
    // is over (or at once with no trade) with bottles in the bag and the cream or the wart missing, a traded potion or none
    // (BrewErrandRules). the cream first, by CollectMagmaCreamTask's errand hunt (a cube at any height the path reaches, no
    // walk to the basalt deltas) within Tuning brew_errand_cream_s; then the wart the block tracker knows, at any distance,
    // within brew_errand_wart_s. on the way to the fortress what comes in sight is taken and no clock runs; once the spawner is
    // known the buddy goes to within BrewErrandRules.HOLD_NEAR of it and waits there, the camp not taken, for at most
    // brew_errand_seek_s with no cube in sight (WART_LOOK_S with no wart known). the fight's own tick comes first throughout.
    // given up, the chance errands of brewStep come back. null is no task of the errand's this tick; _errandHolds and
    // _errandWaits say what the rest of the tick does
    private Task brewErrand(AltoClef mod) {
        _errandHolds = false;
        _errandWaits = false;
        if (_errandEnded || adris.altoclef.util.helpers.Tuning.get("brew_errand", 0) <= 0) return null;
        var player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        boolean trade = adris.altoclef.util.helpers.Tuning.get("fr_trade", 0) > 0 && _frTrade != null;
        boolean tradeLive = trade && _frTrade.live();
        if (trade && !tradeLive && _tradeOverAt < 0) _tradeOverAt = now;
        int water = PotionHelper.count(mod, Potions.WATER);
        boolean kit = adris.altoclef.util.helpers.BrewErrandRules.kit(water + PotionHelper.count(mod, Potions.AWKWARD));
        int cream = mod.getItemStorage().getItemCount(Items.MAGMA_CREAM);
        int wart = mod.getItemStorage().getItemCount(Items.NETHER_WART);
        adris.altoclef.util.helpers.BrewErrandRules.Step step = adris.altoclef.util.helpers.BrewErrandRules.step(cream > 0, wart > 0, water);
        if (!_errandOn) {
            boolean brewing = _brewSince >= 0 || _brewGaveUp || BrewFireResistanceTask.pending();
            if (adris.altoclef.util.helpers.BrewErrandRules.whyNot(tradeLive, kit, brewing, step) != null) return null;
            _errandOn = true;
            _errandAt = now;
            routePhase(adris.altoclef.util.helpers.RouteRules.WART);
            _errandStep = step;
            _errandLastTick = now;
            _errandStepTicks = 0;
            _errandNoSight = 0;
            Debug.logInternal("brew errand: begun (rods " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD) + ", cream " + cream + ", wart " + wart
                    + "), " + (!trade ? "no trade" : secs(Math.max(0, now - _tradeOverAt)) + " s after the trade's end")
                    + (_foundBlazeSpawner == null ? ", no spawner yet" : ", the spawner " + (int) Math.round(player.position().distanceTo(Vec3.atCenterOf(_foundBlazeSpawner))) + " blocks off"));
        }
        // (a clock that went back, a new world: the errand's seconds from now on)
        if (now < _errandAt) _errandAt = now;
        if (now < _errandLastTick) _errandLastTick = now;
        if (step != _errandStep) {
            if (_errandStep == adris.altoclef.util.helpers.BrewErrandRules.Step.CREAM && cream > 0) {
                Debug.logInternal("brew errand: cream got after " + secs(now - _errandAt) + " s");
            } else if (_errandStep == adris.altoclef.util.helpers.BrewErrandRules.Step.WART && wart > 0) {
                Debug.logInternal("brew errand: wart got after " + secs(now - _errandAt) + " s");
            }
            _errandStep = step;
            _errandStepTicks = 0;
            _errandNoSight = 0;
        }
        long gap = now - _errandLastTick;
        _errandLastTick = now;
        if (step == adris.altoclef.util.helpers.BrewErrandRules.Step.DONE) {
            // the cream and the wart in the bag: brewStep brews once the first two rods are in
            _errandOn = false;
            _errandEnded = true;
            return null;
        }
        boolean creamStep = step == adris.altoclef.util.helpers.BrewErrandRules.Step.CREAM;
        // (the cream step: a cube to hunt or its cream on the ground; the wart step: the fortress's own wart as the tracker
        // knows it, or a wart lying on the ground, at any distance)
        boolean inSight = creamStep ? CollectMagmaCreamTask.inSight(mod, true)
                : mod.getBlockTracker().anyFound(Blocks.NETHER_WART) || mod.getEntityTracker().getClosestItemDrop(player.position(), Items.NETHER_WART).isPresent();
        boolean near = _foundBlazeSpawner != null
                && player.position().distanceTo(Vec3.atCenterOf(_foundBlazeSpawner)) <= adris.altoclef.util.helpers.BrewErrandRules.HOLD_NEAR;
        adris.altoclef.util.helpers.BrewErrandRules.Act act = adris.altoclef.util.helpers.BrewErrandRules.act(step, inSight, _foundBlazeSpawner != null, near);
        if (act != adris.altoclef.util.helpers.BrewErrandRules.Act.SEARCH) _errandStepTicks = adris.altoclef.util.helpers.BrewErrandRules.own(_errandStepTicks, gap);
        _errandNoSight = adris.altoclef.util.helpers.BrewErrandRules.noSight(_errandNoSight, act, gap);
        if (creamStep) logCreamCubes(mod);
        String why = adris.altoclef.util.helpers.BrewErrandRules.giveUp(step, kit, _errandStepTicks, _errandNoSight,
                adris.altoclef.util.helpers.BrewErrandRules.ticks(adris.altoclef.util.helpers.Tuning.get("brew_errand_cream_s", adris.altoclef.util.helpers.BrewErrandRules.CREAM_S)),
                adris.altoclef.util.helpers.BrewErrandRules.ticks(adris.altoclef.util.helpers.Tuning.get("brew_errand_seek_s", adris.altoclef.util.helpers.BrewErrandRules.SEEK_S)),
                adris.altoclef.util.helpers.BrewErrandRules.ticks(adris.altoclef.util.helpers.Tuning.get("brew_errand_wart_s", adris.altoclef.util.helpers.BrewErrandRules.WART_S)));
        if (why != null) {
            Debug.logInternal("brew errand: given up (" + why + ") after " + secs(now - _errandAt) + " s");
            _errandOn = false;
            _errandEnded = true;
            return null;
        }
        _errandHolds = true;
        // (box_home: no errand while the box holds the buddy; the box is the camp, picked only once the errand is over unless
        // box_home picked it at the spawner first)
        if (homeHolds()) {
            homeSkip("the brew errand");
            return null;
        }
        switch (act) {
            case HUNT -> {
                if (_errandCream == null) _errandCream = new CollectMagmaCreamTask(1, true);
                setDebugState("Brew errand: a magma cube for its cream");
                return _errandCream;
            }
            case FETCH -> {
                if (_errandWart == null) _errandWart = TaskCatalogue.getItemTask("nether_wart", 1);
                setDebugState("Brew errand: a nether wart");
                return _errandWart;
            }
            case APPROACH -> {
                // (two blocks inside the line: the walk's goal counts by blocks, the line from the buddy's middle)
                setDebugState("Brew errand: towards the spawner, not into the fight");
                return new adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask(_foundBlazeSpawner, (int) adris.altoclef.util.helpers.BrewErrandRules.HOLD_NEAR - 2);
            }
            case WAIT -> {
                _errandWaits = true;
                return null;
            }
            default -> {
                // (no spawner yet and nothing in sight: the fortress search goes on)
                return null;
            }
        }
    }

    // between fights: the brew that is under way, a magma cube or a nether wart close by while the bottles want them, and the
    // brew itself once the bag holds both and two rods (the stand and its powder). null is nothing of the brew's to do now
    private Task brewStep(AltoClef mod) {
        // (the buddy's fork: a brew box being opened, after its brew or with the brew given up inside it, is seen through first)
        if (_brewBox != null && _brewBox.opening()) {
            setDebugState("Opening the brew box");
            return _brewBox;
        }
        if (_brewGaveUp) {
            return null;
        }
        // (box_home: no brew while a heal is sealed in the box; the two wall the same look out)
        if (_healBox != null) {
            return null;
        }
        // (a stand with our bottles in it from an earlier order is the same brew, on its own clock from now)
        if (BrewFireResistanceTask.pending() && _brewSince < 0) {
            _brewSince = WorldHelper.getTicks();
        }
        if (_brewSince >= 0) {
            // (a brew box still to do keeps the brew's turn after the potions are in: the drink and the door. one that failed
            // leaves the open brew below, as before)
            boolean boxed = _brewBox != null && _brewBox.active();
            if (!boxed && _brewBox != null && _brewBox.waitOnFail && _brewBox.failed() && !BrewFireResistanceTask.pending()) {
                Debug.logInternal("brew: the box failed, the brew waits a minute in cover (no station outside a shut box at a fortress)");
                _brewSince = -1;
                _brewBox = null;
                _brewWaitUntil = WorldHelper.getTicks() + BREW_WAIT_TICKS;
                return null;
            }
            // (a brew begun in the nether that has lost its cream: no far search for one, back to the blazes)
            boolean creamGone = !_brew.isFinished(mod) && WorldHelper.getCurrentDimension() == Dimension.NETHER
                    && BrewFireResistanceTask.wantsCream(mod);
            if (_brew.isFinished(mod) && !boxed) {
                _brewSince = -1;
                routePhase(adris.altoclef.util.helpers.RouteRules.RODS);
            } else if (WorldHelper.getTicks() - _brewSince > BREW_BUDGET_TICKS || creamGone) {
                Debug.logMessage(creamGone ? "No magma cream left for the brew, back to the blazes" : "Brewing fire resistance took too long, back to the blazes");
                Debug.logInternal(creamGone ? "brew: given up, no magma cream for the stand" : "brew: given up, too long");
                _brewSince = -1;
                _brewGaveUp = true;
                routePhase(adris.altoclef.util.helpers.RouteRules.RODS);
                if (boxed) {
                    _brewBox.giveUp("the brew given up inside");
                    if (_brewBox.opening()) return _brewBox;
                }
                return null;
            } else if (boxed) {
                setDebugState("Brewing fire resistance walled in");
                return _brewBox;
            } else {
                // (said once a brew: a station used outside a shut box, steering 69's kind for bin/odd.py)
                if (!_openLogged) {
                    _openLogged = true;
                    Debug.logInternal("brew: in the open at " + mod.getPlayer().blockPosition().toShortString());
                }
                setDebugState(BrewFireResistanceTask.pending() ? "Back to the brewing stand" : "Brewing fire resistance");
                return _brew;
            }
        }
        int water = PotionHelper.count(mod, Potions.WATER);
        int bases = water + PotionHelper.count(mod, Potions.AWKWARD);
        // (nothing to brew, or a batch's worth already in the bag)
        if (bases == 0 || BrewFireResistanceTask.fireRes(mod) >= BrewRules.BATCH) {
            return null;
        }
        boolean wart = water == 0 || mod.getItemStorage().hasItem(Items.NETHER_WART);
        boolean cream = mod.getItemStorage().hasItem(Items.MAGMA_CREAM);
        // (the buddy's fork, Tuning box_home: no cream hunt and no wart errand while the box holds: each is a walk out of it,
        // and the cream hunt was the leave never undone in 18 of 43 boxes left before the end since 6 Nov 2026)
        if (!cream) {
            logCreamCubes(mod);
            // a tiny one drops nothing
            var us = mod.getPlayer();
            double range = creamRange();
            if (mod.getEntityTracker().getClosestEntity(us.position(), e -> e instanceof MagmaCube c && c.isAlive() && c.getSize() > 1
                    && c.distanceTo(us) <= range && creamCube(mod, c), MagmaCube.class).isPresent()) {
                if (homeHolds()) {
                    homeSkip("the cream hunt");
                    return null;
                }
                setDebugState("A magma cube for its cream");
                return new KillAndLootTask(MagmaCube.class, e -> e instanceof MagmaCube c && c.getSize() > 1 && c.distanceTo(us) <= range
                        && creamCube(mod, c), new ItemTarget(Items.MAGMA_CREAM, 1));
            }
            // the last cube dead and its cream on the floor: the gate above no longer holds, the drop is picked up all the same
            if (dropNear(mod, Items.MAGMA_CREAM, range)) {
                if (homeHolds()) {
                    homeSkip("the cream pickup");
                    return null;
                }
                setDebugState("Picking up the magma cream");
                return new PickupDroppedItemTask(new ItemTarget(Items.MAGMA_CREAM, 1), true);
            }
        }
        // (the wart lying where its block was is the same errand: with only the block asked about, the dig broke it and the walk to
        // the spawner left the drop behind)
        // (the buddy's fork: and only with the cream in the bag. the cream is the rare half, a cube on the fortress's floor; the
        // wart was looked for 7 to 60 times an order while no cream ever came, attempts 224 to 229, 4 Nov 2026)
        if (!wart && cream && (dropNear(mod, Items.NETHER_WART, WART_RANGE)
                || mod.getBlockTracker().getNearestWithinRange(mod.getPlayer().position(), WART_RANGE, Blocks.NETHER_WART).isPresent())) {
            if (homeHolds()) {
                homeSkip("the wart errand");
                return null;
            }
            setDebugState("A nether wart for the brew");
            return TaskCatalogue.getItemTask("nether_wart", 1);
        }
        if (!wart || !cream) {
            return null;
        }
        int rodsWanted = (mod.getItemStorage().hasItem(Items.BREWING_STAND) ? 0 : 1) + (mod.getItemStorage().hasItem(Items.BLAZE_POWDER) ? 0 : 1);
        if (mod.getItemStorage().getItemCount(Items.BLAZE_ROD) < rodsWanted) {
            return null;
        }
        if (WorldHelper.getTicks() < _brewWaitUntil) return null;
        routePhase(adris.altoclef.util.helpers.RouteRules.BREW);
        // (Tuning route_brew_pillbox: the brew shut in the fight's pillbox by the spawner, built first when none stands)
        Task routeBox = routeBrewBox(mod);
        if (routeBox != null) return routeBox;
        if (_routeBoxWaits) return null;
        setDebugState("Brewing fire resistance");
        _brewSince = WorldHelper.getTicks();
        _openLogged = false;
        Debug.logInternal("brew: begun, rods " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD));
        // (the buddy's fork, Tuning brew_box: the brew walled in, BrewBoxTask; off, the open brew as before)
        double boxTuning = adris.altoclef.util.helpers.Tuning.get("brew_box", 0);
        // (brew_box 2: in the fight's standing pillbox, its look out walled: the box of its own went up in 2 runs of 30, and the
        // open brew by the spawner died during the brew in half the brews begun. no standing bent box: the open brew)
        if (adris.altoclef.util.helpers.BrewBox.inPillbox(boxTuning)) {
            int[][] cells = _pillbox != null && _pillboxCornered ? _pillbox.brewCells() : null;
            if (cells != null) {
                _brewBox = new BrewBoxTask(_brew, _foundBlazeSpawner, () -> java.util.Set.of(), cells);
                Debug.logInternal("brew: in the pillbox at " + _pillbox.cell().toShortString());
            } else {
                // (steering 69: never the open brew at a fortress. with no standing pillbox (a corner camp) a box of its own
                // near the buddy, the stand made inside it; the open brew at a one-cell box's camp killed Zuri)
                _brewBox = new BrewBoxTask(_brew, _foundBlazeSpawner, this::brewBoxKeepClear);
                Debug.logInternal("brew: no standing pillbox, a box of its own");
            }
            _brewBox.waitOnFail = true;
            return _brewBox;
        } else if (adris.altoclef.util.helpers.BrewBox.on(boxTuning)) {
            _brewBox = new BrewBoxTask(_brew, _foundBlazeSpawner, this::brewBoxKeepClear);
            return _brewBox;
        }
        return _brew;
    }

    // Tuning route_brew_pillbox, on by default (the route's phase 5, steering 118): the brew shut in the fight's pillbox by the
    // spawner, never in a box of its own beside it. under the traded potion the camp is the fire-proof one and no pillbox
    // stands when the first two rods are in, so the pillbox is planned here as pickCampSpot plans one (the camp is its cell),
    // and built; once it stands brewStep brews in it with its look out walled (brew_box 2). a pillbox that does not fit, or
    // was given up at this spawner, leaves the box of its own as before. null is no task of the box's this tick;
    // _routeBoxWaits says the box is built and its cornering (the next tick's) is awaited
    private Task routeBrewBox(AltoClef mod) {
        _routeBoxWaits = false;
        if (adris.altoclef.util.helpers.Tuning.get("route_brew_pillbox", 0) <= 0 || _foundBlazeSpawner == null
                || !adris.altoclef.util.helpers.BrewBox.inPillbox(adris.altoclef.util.helpers.Tuning.get("brew_box", 0))) return null;
        if (_pillbox != null && _pillboxCornered) return null;
        if (_pillbox != null && !_pillbox.isFinished(mod)) {
            setDebugState("Building the pillbox for the brew");
            return _pillbox;
        }
        if (_pillbox != null && _pillbox.nearlyBuilt(mod)) {
            _routeBoxWaits = true;
            return null;
        }
        if (_foundBlazeSpawner.equals(_pillboxGaveUpFor) || _foundBlazeSpawner.equals(_routeBoxFailedFor)
                || mod.getClientBaritone().bsi == null || mod.getClientBaritone().getPlayerContext() == null) return null;
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        int sx = _foundBlazeSpawner.getX(), sy = _foundBlazeSpawner.getY(), sz = _foundBlazeSpawner.getZ();
        adris.altoclef.util.helpers.BlazePillbox.Plan plan = pillboxPlan(mod, cells, sx, sy, sz);
        if (plan == null) {
            _routeBoxFailedFor = _foundBlazeSpawner;
            Debug.logInternal("brew: no pillbox fits by the spawner " + _foundBlazeSpawner.toShortString() + ", the brew in a box of its own");
            return null;
        }
        // (as pickCampSpot's pillbox: the camp is the box's cell, picked for this spawner now, so the pick does not drop it)
        _campSpotFor = _foundBlazeSpawner;
        _campFireproof = BlazeFight.fireproofCamp(mod.getPlayer());
        _campPickedAt = mod.getWorld().getGameTime();
        _fight.corner(null);
        _lure = null;
        notePillboxSpot();
        _rushStands = null;
        _rushStand = 0;
        _rushWalkTicks = 0;
        _pillbox = new BlazePillboxTask(plan);
        _pillboxCornered = false;
        _pillboxCornerSet = null;
        _fight.pillboxPlanned(true);
        _fight.pillboxLookout(_pillbox.lookout());
        _campSpot = _pillbox.cell();
        Debug.logInternal("brew: the pillbox for the brew at " + _campSpot.toShortString() + " (" + plan.blocks().size() + " blocks), looking out from "
                + _pillbox.lookout().toShortString() + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
        setDebugState("Building the pillbox for the brew");
        return _pillbox;
    }

    // Tuning route_cream_trade, on by default (the route's phase 2): with no cream in the bag and bottles to brew, a magma cube
    // in sight while the trade searches for a piglin (a walk, no ingot being admired) is hunted for its cream, by the brew
    // errand's rules (CollectMagmaCreamTask's errand hunt: any level the path reaches, never by lava, a drop or the sea). a
    // piglin in sight takes the tick back at once: the trade's verdict is asked first every tick. null is no hunt now
    private Task tradeCream(AltoClef mod) {
        boolean on = adris.altoclef.util.helpers.Tuning.get("route_cream_trade", 0) > 0 && _frTrade.searching()
                && !mod.getItemStorage().hasItem(Items.MAGMA_CREAM)
                && PotionHelper.count(mod, Potions.WATER) + PotionHelper.count(mod, Potions.AWKWARD) > 0
                && CollectMagmaCreamTask.inSight(mod, true);
        if (!on) {
            _tradeCreamOn = false;
            return null;
        }
        if (!_tradeCreamOn) {
            _tradeCreamOn = true;
            Debug.logInternal("cream: a magma cube on the trade's search, its cream taken along");
        }
        if (_tradeCream == null) _tradeCream = new CollectMagmaCreamTask(1, true);
        setDebugState("A magma cube on the way, for its cream");
        return _tradeCream;
    }

    // the route's no-potion line, once: on the first tick the trade is seen over, without a potion from it, in the bag or on
    // the buddy (all the gold handed, a dead end, or no trade begun): the cream and the wart, the first two rods without the
    // potion, then the brew. asked once, so a potion drunk later is not taken for none
    private void noteNoPotion(AltoClef mod) {
        if (_noPotionSaid) return;
        _noPotionSaid = true;
        if (_frTrade.succeeded()) return;
        if (FireResTrade.count(mod, adris.altoclef.util.helpers.FireResTradeRules.Form.DRINK)
                + FireResTrade.count(mod, adris.altoclef.util.helpers.FireResTradeRules.Form.THROW) > 0
                || mod.getPlayer().hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) return;
        Debug.logInternal(adris.altoclef.util.helpers.RouteRules.NO_POTION);
    }

    // the portals met in the nether, kept for the route's exit: every PORTAL_NOTE_TICKS the nearest one the tracker knows a
    // way into (its bottom cell), and the one the buddy came through (MiscBlockTracker), at most PORTALS_KEPT
    private void notePortals(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now - _portalNoteAt < adris.altoclef.util.helpers.RouteRules.PORTAL_NOTE_TICKS && now >= _portalNoteAt) return;
        _portalNoteAt = now;
        notePortal(mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER).orElse(null));
        notePortal(adris.altoclef.tasks.movement.EnterNetherPortalTask.usablePortal(mod, adris.altoclef.tasks.movement.EnterNetherPortalTask.ANY_PORTAL,
                mod.getPlayer().position()).orElse(null));
    }

    private void notePortal(BlockPos feet) {
        if (feet == null || _portalsSeen.contains(feet)) return;
        if (_portalsSeen.size() >= adris.altoclef.util.helpers.RouteRules.PORTALS_KEPT) _portalsSeen.remove(0);
        _portalsSeen.add(feet);
        Debug.logInternal("exit: a portal kept for the way out at " + feet.toShortString() + " (" + _portalsSeen.size() + " known)");
    }

    // the nearest portal kept that is still one as far as the loaded world shows (one in an unloaded chunk is trusted)
    private BlockPos nearestPortalSeen(AltoClef mod) {
        Vec3 me = mod.getPlayer().position();
        BlockPos best = null;
        for (java.util.Iterator<BlockPos> it = _portalsSeen.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (mod.getChunkTracker().isChunkLoaded(p) && mod.getWorld().getBlockState(p).getBlock() != Blocks.NETHER_PORTAL) {
                Debug.logInternal("exit: the portal kept at " + p.toShortString() + " is gone");
                it.remove();
                continue;
            }
            if (best == null || p.distToCenterSqr(me) < best.distToCenterSqr(me)) best = p;
        }
        return best;
    }

    // Tuning route_exit, on by default (the route's phase 7): the rods in the bag, back to the overworld. a portal the tracker
    // knows a way into is entered (EnterNetherPortalTask, which would build one when it knows none: here it idles instead,
    // and the next tick decides again); else the walk to the nearest portal kept (notePortals), where the tracker sees it
    // again. no portal known at all, or still in the nether Tuning route_exit_s after the exit began: given up with a line,
    // the order then ends in the nether (isFinished). null is nothing of the exit's this tick
    private Task exitStep(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        var me = mod.getPlayer();
        if (_exitAt < 0 || now < _exitAt) {
            _exitAt = now;
            routePhase(adris.altoclef.util.helpers.RouteRules.EXIT);
            adris.altoclef.util.helpers.RouteRules.exitBegins();
            notePortals(mod);
        }
        Optional<BlockPos> tracked = adris.altoclef.tasks.movement.EnterNetherPortalTask.usablePortal(mod,
                adris.altoclef.tasks.movement.EnterNetherPortalTask.ANY_PORTAL, me.position());
        BlockPos kept = tracked.isPresent() ? null : nearestPortalSeen(mod);
        String why = adris.altoclef.util.helpers.RouteRules.exitGiveUp(tracked.isPresent() || kept != null, now - _exitAt,
                Math.round(adris.altoclef.util.helpers.Tuning.get("route_exit_s", adris.altoclef.util.helpers.RouteRules.EXIT_S) * 20));
        if (why != null) {
            Debug.logInternal("exit: given up (" + why + "), the order ends in the nether at " + me.blockPosition().toShortString()
                    + " with " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD) + " rods");
            adris.altoclef.util.helpers.RouteRules.exitGivesUp();
            return null;
        }
        if (tracked.isPresent()) {
            if (_exitEnter == null) {
                _exitEnter = new adris.altoclef.tasks.movement.EnterNetherPortalTask(new adris.altoclef.tasks.movement.IdleTask(), Dimension.OVERWORLD);
                Debug.logInternal("exit: through the portal at " + tracked.get().toShortString() + ", "
                        + (int) Math.round(me.position().distanceTo(Vec3.atBottomCenterOf(tracked.get()))) + " blocks off, "
                        + mod.getItemStorage().getItemCount(Items.BLAZE_ROD) + " rods");
            }
            setDebugState("Out of the nether through the portal");
            return _exitEnter;
        }
        if (!kept.equals(_exitWalkTo)) {
            _exitWalkTo = kept;
            _exitWalk = new GetToBlockTask(kept, false);
            Debug.logInternal("exit: the walk to the portal kept at " + kept.toShortString() + ", "
                    + (int) Math.round(me.position().distanceTo(Vec3.atBottomCenterOf(kept))) + " blocks off");
        }
        setDebugState("Back to the portal");
        return _exitWalk;
    }

    // the cells a brew box may not take: the fight's pillbox, its blocks and its cells
    private java.util.Set<BlockPos> brewBoxKeepClear() {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        if (_pillbox == null) return out;
        out.addAll(_pillbox.blocks());
        for (BlockPos c : new BlockPos[]{_pillbox.cell(), _pillbox.front(), _pillbox.lookout()}) {
            if (c == null) continue;
            out.add(c);
            out.add(c.above());
        }
        return out;
    }

    private static boolean dropNear(AltoClef mod, net.minecraft.world.item.Item item, double range) {
        return mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), item)
                .filter(drop -> drop.distanceTo(mod.getPlayer()) <= range).isPresent();
    }

    // one bounded step off a lava rim or ledge to the best neighbour cell, null when we are fine, there or out of budget
    private Task stepInland(AltoClef mod) {
        if (_campStepTicks >= BlazeFightRules.CAMP_STEP_TICKS || mod.getClientBaritone().bsi == null) return null;
        var us = mod.getPlayer();
        BlockPos here = us.blockPosition();
        // (a fresh look at the world: Baritone's bsi is the last path search's copy, and read the room as all solid, so the
        // camp ring had "0 cells to stand on" in a real fortress and the camp stood beside the spawner, 4 Nov 2026)
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        if (_campStep == null) {
            int[] s = LavaShore.edgeStep(cells, us.getX(), us.getY(), us.getZ());
            if (s == null) {
                // fine where we are, or nowhere better
                _campStepTicks = BlazeFightRules.CAMP_STEP_TICKS;
                return null;
            }
            _campStep = here.offset(s[0], s.length > 2 ? s[2] : 0, s[1]);
        }
        boolean arrived;
        if (SafeStand.on()) {
            // the step is the first of a walk to a safe cell: in it, the next one, until there is none (there, or nowhere better)
            arrived = false;
            if (here.equals(_campStep)) {
                int[] s = LavaShore.edgeStep(cells, us.getX(), us.getY(), us.getZ());
                if (s == null) arrived = true;
                else _campStep = here.offset(s[0], s[2], s[1]);
            }
        } else {
            // in the block is not off the rim yet, the cell's near half can still be inside the edge radius
            arrived = here.equals(_campStep) && !LavaShore.edgeNear(cells, us.getX(), us.getY(), us.getZ());
        }
        if (!BlazeFightRules.wantsCampStep(true, arrived, _campStepTicks)) {
            _campStepTicks = BlazeFightRules.CAMP_STEP_TICKS;
            return null;
        }
        _campStepTicks++;
        return new GetToBlockTask(_campStep);
    }

    // once per spawner: a cell six to eight out with a line to it, so the blazes come to us in ones and twos
    private void pickCampSpot(AltoClef mod) {
        if (_foundBlazeSpawner.equals(_campSpotFor) || mod.getClientBaritone().bsi == null) return;
        _campSpotFor = _foundBlazeSpawner;
        _campFireproof = BlazeFight.fireproofCamp(mod.getPlayer());
        _campPickedAt = mod.getWorld().getGameTime();
        // (a fresh look at the world: Baritone's bsi is the last path search's copy, and read the room as all solid, so the
        // camp ring had "0 cells to stand on" in a real fortress and the camp stood beside the spawner, 4 Nov 2026)
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        var us = mod.getPlayer();
        int sx = _foundBlazeSpawner.getX(), sy = _foundBlazeSpawner.getY(), sz = _foundBlazeSpawner.getZ();
        // (the buddy's fork: a corner first. a camp with a line to the spawner has every blaze of it shooting from where it
        // hovers, and they never come: a blaze that sees us stays put, 4 Nov 2026)
        _fight.corner(null);
        _lure = null;
        notePillboxSpot();
        _pillbox = null;
        _pillboxCornered = false;
        _fight.pillboxPlanned(false);
        _fight.pillboxLookout(null);
        _rushStands = null;
        _rushStand = 0;
        _rushWalkTicks = 0;
        if (BlazeFight.fireproofCamp(us) && FightOpenRules.rushOn()) {
            // (Tuning fireproof_rush: by the spawner, its top first, then beside it, FightOpenRules.stands; a stand not reached in
            // RUSH_WALK_TICKS gives way to the next, rushWalk)
            _rushStands = FightOpenRules.stands(cells, sx, sy, sz);
            _campSpot = rushStandSpot();
            Debug.logInternal("rush: the camp by the spawner " + _foundBlazeSpawner.toShortString() + ", at " + rushStandPos().toShortString()
                    + " (stand 1 of " + _rushStands.size() + ")");
            return;
        }
        if (BlazeFight.fireproofCamp(us)) {
            // (the buddy's fork: with the potion drunk by the pillbox the brew stood in, the camp is that box's open side,
            // where the blazes flew in and every kill under the potion came. by the spawner the walk aimed at the block on top
            // of it, behind the buddy's own tunnels, and the walk and the chase took turns for eleven minutes, piece2fix-4 run
            // 2. no box before (the rig's potion): by the spawner as before, fpcamp2's camp)
            // (steering 71: the spawner's top first, where they spawn within reach, as fpcamp2's camp; the box's open side, eight
            // off and three down, gave no blaze in reach for minutes, Makena. only a top not reached in FIREPROOF_WALK_TICKS
            // falls back to that side, campStepFireproof. Tuning fireproof_side 1: the open side at once, dbec29a's camp: the
            // two measured as many rods a minute under the potion, each better at other fortresses, 6 Nov 2026 afternoon)
            // (steering 78: the spawner's top first only with Tuning fireproof_top, piece2top-2 rejected it; off, the open side
            // is the camp again, as fireproof_side asks)
            BlockPos side = _fireproofSpot;
            boolean top = adris.altoclef.util.helpers.Tuning.get("fireproof_top", 0) > 0;
            if ((!top || adris.altoclef.util.helpers.Tuning.get("fireproof_side", 0) > 0) && side != null
                    && Math.hypot(side.getX() - sx, side.getZ() - sz) <= FIREPROOF_SPOT_RANGE) {
                _campSpot = side;
                Debug.logInternal("blaze camp: fire-proof, at the pillbox's open side " + side.toShortString() + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
                return;
            }
            _campSpot = null;
            Debug.logInternal("blaze camp: fire-proof, by the spawner (" + _foundBlazeSpawner.toShortString() + ")");
            return;
        }
        // (the buddy's fork, Tuning far_box, off by default, steering 89: the far box first, outside the spawner's range and no
        // corner, the blazes drawn to it by spawn trips. none fits, or given up here: the camp as without the switch)
        // (Tuning camp_reentry: no corner and no box at a spawner whose camp was left with none to take its place, and never a
        // corner shut out of; both empty with the switch off)
        boolean campsOff = _foundBlazeSpawner.equals(_campsOffFor);
        if (farOn() && !_foundBlazeSpawner.equals(_farGaveUpFor) && !_foundBlazeSpawner.equals(_pillboxGaveUpFor) && !campsOff
                && farPick(mod, cells, sx, sy, sz)) return;
        // (Tuning box_home: the box before a corner, the corner only when no box fits, below)
        boolean homeFirst = adris.altoclef.util.helpers.BoxHomeRules.on();
        int[][] corner = _noCorner || campsOff || homeFirst ? null : cornerSpot(mod, cells, sx, sy, sz, _shutOut.isEmpty() && !_fight.nooksBarred() ? null : notShut(), true);
        if (corner != null) {
            _campSpot = new BlockPos(corner[0][0], corner[0][1], corner[0][2]);
            BlockPos peek = new BlockPos(corner[1][0], corner[1][1], corner[1][2]);
            adris.altoclef.util.snapshot.Snapshots.chose("blaze.corner", corner[0]).with("peek", corner[1]).spawner(sx, sy, sz);
            _fight.corner(_campSpot);
            _lure = BlazeLureTask.of(cells, _campSpot, peek);
            Debug.logInternal("blaze camp: a corner at " + _campSpot.toShortString() + " (closed in "
                    + BlazeCover.enclosure(mod.getWorld(), corner[0][0], corner[0][1], corner[0][2], mod.getPlayer()) + " of 24), looking out from " + peek.toShortString()
                    + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
            return;
        }
        // (the buddy's fork: no corner, a pillbox built by the spawner, steering 21; its cell is the fight's corner once it
        // stands, and the looks start from it)
        if (adris.altoclef.util.helpers.Tuning.get("pillbox", 1) > 0 && !_foundBlazeSpawner.equals(_pillboxGaveUpFor) && !campsOff) {
            adris.altoclef.util.helpers.BlazePillbox.Plan plan = pillboxPlan(mod, cells, sx, sy, sz);
            if (plan != null) {
                _pillbox = new BlazePillboxTask(plan);
                // (Tuning box_home: the box is picked on arrival at the spawner, maybe 120 blocks off; its clock starts at
                // the cell, as the far box's, or the walk ate it, realfort-pair-home2 B:1)
                if (homeFirst) _pillbox.clockAtCell();
                _fight.pillboxPlanned(true);
                _fight.pillboxLookout(_pillbox.lookout());
                _campSpot = _pillbox.cell();
                adris.altoclef.util.snapshot.Snapshots.chose("pillbox", plan.cell()).with("lookout", plan.lookout()).with("front", plan.front()).blocks(plan.blocks())
                        .spawner(sx, sy, sz);
                Debug.logInternal("blaze camp: no corner, a " + (plan.front() != null ? "bent " : "") + "pillbox at " + _campSpot.toShortString() + " (" + plan.blocks().size()
                        + " blocks), looking out from " + _pillbox.lookout().toShortString() + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
                return;
            }
            Debug.logInternal("blaze camp: no corner and no pillbox (spawner " + _foundBlazeSpawner.toShortString() + ")");
        }
        // (Tuning box_home: no box fits, so a corner after all)
        corner = homeFirst && !_noCorner && !campsOff ? cornerSpot(mod, cells, sx, sy, sz, _shutOut.isEmpty() && !_fight.nooksBarred() ? null : notShut(), true) : null;
        if (corner != null) {
            _campSpot = new BlockPos(corner[0][0], corner[0][1], corner[0][2]);
            BlockPos peek = new BlockPos(corner[1][0], corner[1][1], corner[1][2]);
            adris.altoclef.util.snapshot.Snapshots.chose("blaze.corner", corner[0]).with("peek", corner[1]).spawner(sx, sy, sz);
            _fight.corner(_campSpot);
            _lure = BlazeLureTask.of(cells, _campSpot, peek);
            Debug.logInternal("blaze camp: no pillbox fits, a corner at " + _campSpot.toShortString() + " (closed in "
                    + BlazeCover.enclosure(mod.getWorld(), corner[0][0], corner[0][1], corner[0][2], mod.getPlayer()) + " of 24), looking out from " + peek.toShortString()
                    + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
            return;
        }
        int[] spot = LavaShore.campSpot(cells, sx, sy, sz, us.getX(), us.getZ());
        String how = "six to eight out";
        if (spot == null) {
            spot = LavaShore.campSpot(cells, sx, sy, sz, us.getX(), us.getZ(), LavaShore.WIDE_MIN, LavaShore.WIDE_MAX);
            how = "five to ten out";
        }
        _campSpot = spot == null ? null : new BlockPos(spot[0], spot[1], spot[2]);
        adris.altoclef.util.snapshot.Snapshots.chose("blaze.camp", spot).spawner(sx, sy, sz);
        // which it was: the room's shape decides it and nothing said so (attempt 177 stood beside the spawner)
        Debug.logInternal("blaze camp: " + (_campSpot == null ? "no spot with a line, standing by the spawner"
                : _campSpot.toShortString() + ", " + how) + " (spawner " + _foundBlazeSpawner.toShortString() + "; the last ring had "
                + LavaShore.lastStandable + " cells to stand on, " + LavaShore.lastWithLine + " with a line)");
    }

    // the pillbox's plan (BlazePillbox) from the cell by the spawner that cornerSpot walks out from, the spawn eyes the same
    private static adris.altoclef.util.helpers.BlazePillbox.Plan pillboxPlan(AltoClef mod, Cells cells, int sx, int sy, int sz) {
        int[] start = null;
        double best = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!BlazeCover.walkable(cells, sx + dx, sy + dy, sz + dz)) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < best) {
                        best = d;
                        start = new int[]{sx + dx, sy + dy, sz + dz};
                    }
                }
            }
        }
        if (start == null) return null;
        var level = mod.getWorld();
        java.util.List<Vec3> eyes = new java.util.ArrayList<>();
        for (Vec3 eye : BlazeCover.spawnEyes(sx, sy, sz)) {
            if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) eyes.add(eye);
        }
        // (the bent box first, its back cell round a corner from the opening: the one-cell box was no cover on an open platform,
        // merged-e. the one-cell box where no bent one fits)
        if (adris.altoclef.util.helpers.Tuning.get("pillbox_bent", 1) > 0) {
            // (the raised box first where pillbox_raise asks for one, the bent box on the ground where none fits)
            int raise = (int) Math.round(adris.altoclef.util.helpers.Tuning.get("pillbox_raise", 0));
            if (raise > 0) {
                // (its look out judged on the finer grid of spawn eyes, BlazePillbox.RAISED_SEEN_MIN)
                java.util.List<Vec3> fineUp = new java.util.ArrayList<>();
                for (Vec3 eye : adris.altoclef.util.helpers.BlazePillbox.fineEyes(sx, sy, sz)) {
                    if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) fineUp.add(eye);
                }
                adris.altoclef.util.helpers.BlazePillbox.Plan up = adris.altoclef.util.helpers.BlazePillbox.planBent(cells, start, sx, sy, sz, eyes, raise, fineUp);
                if (up != null) {
                    Debug.logInternal("blaze camp: the bent pillbox's " + adris.altoclef.util.helpers.BlazePillbox.describe(cells, up, fineUp, sx, sy, sz));
                    return up;
                }
                Debug.logInternal("blaze camp: no raised pillbox fits, the bent one on the ground (turned down: " + adris.altoclef.util.helpers.BlazePillbox.whyNotRaised() + ")");
            }
            adris.altoclef.util.helpers.BlazePillbox.Plan bent = adris.altoclef.util.helpers.BlazePillbox.planBent(cells, start, sx, sy, sz, eyes);
            if (bent != null) {
                Debug.logInternal("blaze camp: the bent pillbox's " + adris.altoclef.util.helpers.BlazePillbox.describe(cells, bent, eyes, sx, sy, sz));
                // (the raised box's measure on the finer grid, the log only: the plan is bent-n's)
                java.util.List<Vec3> fine = new java.util.ArrayList<>();
                for (Vec3 eye : adris.altoclef.util.helpers.BlazePillbox.fineEyes(sx, sy, sz)) {
                    if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) fine.add(eye);
                }
                Debug.logInternal("blaze camp: " + adris.altoclef.util.helpers.BlazePillbox.raisedSight(cells, bent, start, fine, sx, sy, sz));
                return bent;
            }
            Debug.logInternal("blaze camp: no bent pillbox fits, the one-cell one");
        }
        return adris.altoclef.util.helpers.BlazePillbox.plan(cells, start, sx, sy, sz, eyes);
    }

    // ---- the far box (the buddy's fork, Tuning far_box, off by default, steering 89)

    static boolean farOn() {
        return adris.altoclef.util.helpers.Tuning.get("far_box", 0) > 0;
    }

    // the far plan's numbers: Tuning far_box_min, far_box_max (blocks from the spawner's middle), far_box_seen (the share of the
    // spawn eyes that see the look out), far_trip_depth (the trip's cell this near the spawner), far_trip_walk (steps)
    static adris.altoclef.util.helpers.BlazePillbox.FarRule farRule() {
        adris.altoclef.util.helpers.BlazePillbox.FarRule d = adris.altoclef.util.helpers.BlazePillbox.FarRule.DEFAULT;
        return new adris.altoclef.util.helpers.BlazePillbox.FarRule(adris.altoclef.util.helpers.Tuning.get("far_box_min", d.min()),
                adris.altoclef.util.helpers.Tuning.get("far_box_max", d.max()), adris.altoclef.util.helpers.Tuning.get("far_box_seen", d.seenMin()),
                adris.altoclef.util.helpers.Tuning.get("far_trip_depth", d.depth()), (int) Math.round(adris.altoclef.util.helpers.Tuning.get("far_trip_walk", d.tripWalk())));
    }

    private static String secs(long ticks) {
        return Long.toString(Math.round(ticks / 20.0));
    }

    // the far box's plan, made the camp: true with one, false with none (the camp is then picked as without the switch, and
    // this spawner gets no far box again)
    private boolean farPick(AltoClef mod, Cells cells, int sx, int sy, int sz) {
        long now = mod.getWorld().getGameTime();
        if (!_foundBlazeSpawner.equals(_farBegunFor)) {
            _farBegunFor = _foundBlazeSpawner;
            if (_farBegunAt < 0) _farBegunAt = now;
            Debug.logInternal("strategy far: begun at the spawner " + _foundBlazeSpawner.toShortString()
                    + (_netherSince >= 0 ? ", " + secs(now - _netherSince) + " s into the nether" : ""));
        }
        int[] start = null;
        double best = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!BlazeCover.walkable(cells, sx + dx, sy + dy, sz + dz)) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < best) {
                        best = d;
                        start = new int[]{sx + dx, sy + dy, sz + dz};
                    }
                }
            }
        }
        adris.altoclef.util.helpers.BlazePillbox.FarRule rule = farRule();
        var level = mod.getWorld();
        adris.altoclef.util.helpers.BlazePillbox.Far far = null;
        if (start != null) {
            java.util.List<Vec3> eyes = new java.util.ArrayList<>();
            for (Vec3 eye : BlazeCover.spawnEyes(sx, sy, sz)) {
                if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) eyes.add(eye);
            }
            // (Tuning far_box_floor, on by default: only on the fortress's own floor)
            adris.altoclef.util.helpers.BlazePillbox.Floor floor = adris.altoclef.util.helpers.Tuning.get("far_box_floor", 1) > 0
                    ? (x, y, z) -> FORTRESS_FLOOR.contains(level.getBlockState(new BlockPos(x, y - 1, z)).getBlock()) : null;
            // (the plan checked against the blocks themselves, BlazePillbox.Sound: the first live far box stood on a wall cell that
            // was no block, two of its blocks found nothing to go against, and it was given up two short)
            adris.altoclef.util.helpers.BlazePillbox.Sound sound = new adris.altoclef.util.helpers.BlazePillbox.Sound() {
                @Override
                public boolean wall(int x, int y, int z) {
                    BlockPos p = new BlockPos(x, y, z);
                    return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
                }

                @Override
                public boolean face(int x, int y, int z) {
                    BlockPos p = new BlockPos(x, y, z);
                    return level.getBlockState(p).isCollisionShapeFullBlock(level, p);
                }
            };
            // (no more blocks than the bag holds, less far_box_slack for the walk's own: a far box of 15 was built to 13 and given
            // up, "Nothing to place with", strat4 t8)
            int bag = BlazePillboxTask.blocksInBag(mod) - (int) Math.round(adris.altoclef.util.helpers.Tuning.get("far_box_slack", FAR_BAG_SLACK));
            far = adris.altoclef.util.helpers.BlazePillbox.planFar(cells, start, sx, sy, sz, eyes, rule, floor, sound, bag);
            if (far != null) {
                adris.altoclef.util.helpers.BlazePillbox.Plan plan = far.plan();
                _far = far;
                _pillbox = new BlazePillboxTask(plan);
                _pillbox.clockAtCell();
                _farBox = _pillbox;
                _fight.pillboxPlanned(true);
                _fight.pillboxLookout(_pillbox.lookout());
                _fight.farRun(far.tripSteps() + FAR_RUN_SLACK);
                _campSpot = _pillbox.cell();
                _farTripCell = new BlockPos(far.trip()[0], far.trip()[1], far.trip()[2]);
                _farWalk = null;
                _farTrip.reset();
                _farTrips = 0;
                _farKills = 0;
                _farWatched.clear();
                _farBackSince = -1;
                _farPlannedAt = now;
                adris.altoclef.util.snapshot.Snapshots.chose("pillbox.far", plan.cell()).with("lookout", plan.lookout()).with("front", plan.front())
                        .with("trip", far.trip()).blocks(plan.blocks()).spawner(sx, sy, sz);
                Debug.logInternal(String.format(java.util.Locale.ROOT, "strategy far: box planned at %s, %.1f from the spawner (%d blocks, looking out from %s; %s; spawn trips to %s, %d steps, %.1f from the spawner)",
                        _campSpot.toShortString(), far.distance(), plan.blocks().size(), _pillbox.lookout().toShortString(),
                        adris.altoclef.util.helpers.BlazePillbox.describe(cells, plan, eyes, sx, sy, sz), _farTripCell.toShortString(), far.tripSteps(),
                        adris.altoclef.util.helpers.BlazePillbox.spawnerDistance(far.trip()[0], far.trip()[1], far.trip()[2], sx, sy, sz)));
                return true;
            }
        }
        _farGaveUpFor = _foundBlazeSpawner;
        Debug.logInternal(String.format(java.util.Locale.ROOT, "strategy far: given up, no box fits %.0f to %.0f from the spawner %s (%s), the camp as without it",
                rule.min(), rule.max(), _foundBlazeSpawner.toShortString(),
                start == null ? "no cell to stand on by the spawner" : adris.altoclef.util.helpers.BlazePillbox.whyNotFar()));
        return false;
    }

    // the far box's spawn trip this tick (FarTrip): 0 not on one (the camp as with any standing box: the walk in, the lure),
    // 1 walking out (_farWalk), 2 waiting at the trip's cell
    private int farTripStep(AltoClef mod) {
        var me = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        adris.altoclef.util.helpers.FarTrip.Numbers n = adris.altoclef.util.helpers.FarTrip.Numbers.tuned();
        float hp = me.getHealth();
        boolean burning = me.isOnFire() && !adris.altoclef.util.helpers.FireRes.has(me);
        boolean inBox = _pillbox.holds(me.blockPosition());
        Vec3 box = Vec3.atBottomCenterOf(_pillbox.cell());
        int near = 0, drawn = 0;
        boolean seen = false;
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (!b.isAlive()) continue;
                if (b.position().distanceTo(box) <= adris.altoclef.util.helpers.FarTrip.NEAR_BOX) near++;
                double d = b.distanceTo(me);
                boolean line = d <= BlazeCover.SIGHT && adris.altoclef.util.helpers.GhastCover.clear(mod.getWorld(), b.getEyePosition(), me.getEyePosition(), me);
                seen |= line;
                // (drawn: charged with a line on the buddy, so it has the buddy for its target; or close, line or not)
                if ((line && b.isOnFire()) || d <= adris.altoclef.util.helpers.FarTrip.CLOSE) drawn++;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes: this tick as the last
            return switch (_farTrip.stage()) {
                case HOME -> 0;
                case OUT -> 1;
                case WAIT -> 2;
            };
        }
        if (_farTrip.stage() == adris.altoclef.util.helpers.FarTrip.Stage.HOME) {
            if (_farBackSince >= 0 && inBox) {
                Debug.logInternal("strategy far: back in the box after spawn trip " + _farTrips + ", " + secs(now - _farBackSince) + " s from its end");
                _farBackSince = -1;
            }
            // (a wither skeleton pack at the spawner counts as a blaze about: no trip into it)
            boolean about = near > 0 || seen || witherPackAtSpawner(mod);
            if (!_farTrip.wantsTrip(now, about, hp, burning, inBox, n)) return 0;
            _farTrip.begin(now);
            _farTrips++;
            _farWalk = new GetToBlockTask(_farTripCell, false);
            Debug.logInternal(String.format(java.util.Locale.ROOT, "strategy far: spawn trip %d begun, to %s (%d steps, %.1f from the spawner) at %.0f hp",
                    _farTrips, _farTripCell.toShortString(), _far.tripSteps(), adris.altoclef.util.helpers.BlazePillbox.spawnerDistance(_farTripCell.getX(),
                    _farTripCell.getY(), _farTripCell.getZ(), _foundBlazeSpawner.getX(), _foundBlazeSpawner.getY(), _foundBlazeSpawner.getZ()), hp));
        }
        String back = _farTrip.turnBack(now, drawn, hp, burning, n);
        if (back == null && witherPackAtSpawner(mod)) back = "wither skeletons at the spawner";
        if (back != null) {
            long out = _farTrip.out(now);
            _farTrip.end(now);
            _farBackSince = now;
            _farWalk = null;
            Debug.logInternal("strategy far: spawn trip " + _farTrips + " over after " + secs(out) + " s, " + _farTrip.drawnMost() + " drawn, back to the box (" + back + ")");
            if (_farTrip.dried(n)) farGiveUp(_farTrip.dryRun() + " spawn trips in a row drew no blaze");
            return 0;
        }
        // (there: in the cell, or the walk done; a walk that never gets there is the trip's cap)
        if (_farTrip.stage() == adris.altoclef.util.helpers.FarTrip.Stage.OUT
                && (BlazeLureTask.in(me.position(), _farTripCell) || (_farWalk != null && _farWalk.isFinished(mod)))) {
            _farTrip.arrived(now);
            Debug.logInternal("strategy far: spawn trip " + _farTrips + " at " + _farTripCell.toShortString() + " after " + secs(_farTrip.out(now)) + " s, waiting");
        }
        return _farTrip.stage() == adris.altoclef.util.helpers.FarTrip.Stage.OUT ? 1 : 2;
    }

    private boolean witherPackAtSpawner(AltoClef mod) {
        return BlazeFightRules.packNear(BlazeFight.witherSkeletonsNear(mod, Vec3.atCenterOf(_foundBlazeSpawner), BlazeFightRules.PACK_SPAWNER_RADIUS));
    }

    // the far box given up at this spawner: the camp picked again as without the switch (a corner, the near pillbox, the open camp)
    private void farGiveUp(String why) {
        Debug.logInternal("strategy far: given up, " + why + ", the camp as without it");
        _farGaveUpFor = _foundBlazeSpawner;
        notePillboxSpot();
        _campSpotFor = null;
        _campSpot = null;
        _pillbox = null;
        _pillboxCornered = false;
        _pillboxCornerSet = null;
        _lure = null;
        _fight.corner(null);
        _fight.pillboxPlanned(false);
        _fight.pillboxLookout(null);
        farDrop();
    }

    private void farDrop() {
        _far = null;
        _farBox = null;
        _farWalk = null;
        _farTrip.reset();
        _farWatched.clear();
        _fight.farRun(0);
    }

    // every tick once the strategy began: the far plan dropped when its box is no longer the camp's, the two rods, the kills
    // at the box (a blaze within FAR_KILL_NEAR of its cell that dies)
    private void farWatch(AltoClef mod) {
        if (_far != null && _pillbox != _farBox) farDrop();
        long now = mod.getWorld().getGameTime();
        var me = mod.getPlayer();
        if (!_farTwoRods && mod.getItemStorage().getItemCount(Items.BLAZE_ROD) >= 2) {
            _farTwoRods = true;
            boolean inBox = _far != null && me != null && _pillbox.holds(me.blockPosition());
            Debug.logInternal("strategy far: two rods after " + secs(now - (_netherSince >= 0 ? _netherSince : _farBegunAt)) + " seconds in the nether ("
                    + secs(now - _farBegunAt) + " since the strategy began, " + _farKills + " kills at the box, " + _farTrips + " spawn trips"
                    + (_far == null ? ", the far box given up" : inBox ? ", in the box" : ", out of the box") + ")");
        }
        // (done: the two rods in the bag and the buddy safe in the box, or with no far box left at once)
        if (_farTwoRods && !_farDone && (_far == null || (me != null && _pillbox.holds(me.blockPosition())))) {
            _farDone = true;
            Debug.logInternal("strategy far: done after " + secs(now - (_netherSince >= 0 ? _netherSince : _farBegunAt)) + " seconds in the nether, "
                    + mod.getItemStorage().getItemCount(Items.BLAZE_ROD) + " rods" + (_far == null ? ", no far box" : ", in the box"));
        }
        if (_far == null || me == null) return;
        Vec3 c = Vec3.atBottomCenterOf(_pillbox.cell());
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive() && b.position().distanceTo(c) <= FAR_KILL_NEAR) _farWatched.putIfAbsent(b.getId(), b);
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes, the next tick sees them
        }
        for (java.util.Iterator<Blaze> it = _farWatched.values().iterator(); it.hasNext(); ) {
            Blaze b = it.next();
            if (b.isDeadOrDying()) {
                _farKills++;
                it.remove();
                Debug.logInternal(String.format(java.util.Locale.ROOT, "strategy far: a kill at the box, %d so far, %.1f from its cell (%d spawn trips, %s s since the box was planned)",
                        _farKills, b.position().distanceTo(c), _farTrips, secs(now - _farPlannedAt)));
            } else if (b.isRemoved() || b.position().distanceTo(c) > FAR_KILL_NEAR + 4) {
                it.remove();
            }
        }
    }

    // a cell by the spawner to walk out from, then BlazeCover.corner with the spawn area's eyes (the ones inside a block left out).
    // allow (Tuning camp_reentry): the corner cells that may be taken, null for all; say: the lines when there is none
    private static int[][] cornerSpot(AltoClef mod, Cells cells, int sx, int sy, int sz, java.util.function.Predicate<BlockPos> allow, boolean say) {
        int[] start = null;
        double best = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!BlazeCover.walkable(cells, sx + dx, sy + dy, sz + dz)) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < best) {
                        best = d;
                        start = new int[]{sx + dx, sy + dy, sz + dz};
                    }
                }
            }
        }
        if (start == null) {
            if (say) Debug.logInternal("blaze camp: no corner, no cell to stand on by the spawner");
            return null;
        }
        var level = mod.getWorld();
        java.util.List<Vec3> eyes = new java.util.ArrayList<>();
        for (Vec3 eye : BlazeCover.spawnEyes(sx, sy, sz)) {
            if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) eyes.add(eye);
        }
        var us = mod.getPlayer();
        // (a corner allow says no to is no pocket: BlazeCover.best passes over one below 0)
        BlazeCover.Enclosure pocket = (x, y, z) -> BlazeCover.pocket(level, x, y, z, us);
        BlazeCover.Enclosure enclosure = allow == null ? pocket : (x, y, z) -> allow.test(new BlockPos(x, y, z)) ? pocket.at(x, y, z) : -1;
        int[][] c = BlazeCover.corner(cells, start, sx, sz, BlazeFight.witherSkeletonSpots(mod, Vec3.atCenterOf(new BlockPos(sx, sy, sz)), BlazeCover.CORNER_MAX + BlazeCover.WITHER_CLEAR),
                (x, y, z) -> BlazeCover.hidden(level, eyes, x, y, z, us), enclosure);
        if (c == null && say) Debug.logInternal("blaze camp: no corner within " + (int) BlazeCover.CORNER_MAX + " of the spawner (" + eyes.size() + " spawn eyes)");
        return c;
    }

    // ---- the buddy's fork, Tuning camp_reentry (off by default): shut out of the camp (CampReentry). most nether-start runs
    // that died before the first rod stood for minutes a few blocks off their own camp in the blazes' fire: the walk back into
    // the corner or to the standing pillbox's open side found no path again and again (the corner's protected walls, the
    // corner a block lower, fire in the door), the goal task wandered off, and the fight still held to the camp. once the
    // watch says shut out, the camp is replaced: a pillbox round the cell the buddy stands on, else another corner a walk
    // from there reaches, else none at this spawner (the camp as without one). one line per decision, "camp: shut out of"

    // the camps the buddy was shut out of (camp_reentry), or whose looks drew nothing for lure_cap: never a corner again.
    // empty with both switches off
    private final java.util.Set<BlockPos> _shutOut = new java.util.HashSet<>();

    // (the buddy's fork, Tuning spawner_wait_cap, off by default, row L12: the hold for a wither skeleton pack at the spawner
    // had no end, and in a fortress they keep spawning: twelve game minutes below the spawner at 20 hp, 11 blazes within 16,
    // realfort-pair-trade A:7. the ticks waited at one spawner are capped, in all; after that the camp goes on there as with
    // no pack, the fight's own back off from a pack within eight still on. true: wait this tick)
    private final adris.altoclef.util.helpers.BlazeCapRules.SpawnerWait _packWait = new adris.altoclef.util.helpers.BlazeCapRules.SpawnerWait();

    private boolean packWaits(AltoClef mod, int pack) {
        long cap = adris.altoclef.util.helpers.BlazeCapRules.spawnerWaitCapTicks();
        if (cap <= 0) return true;
        long key = BlazeCover.key(_foundBlazeSpawner.getX(), _foundBlazeSpawner.getY(), _foundBlazeSpawner.getZ());
        adris.altoclef.util.helpers.BlazeCapRules.Wait wait = _packWait.tick(key, mod.getWorld().getGameTime(), cap);
        if (_packWait.firstLine(key)) {
            Debug.logInternal("spawner wait: " + pack + " wither skeletons within " + (int) BlazeFightRules.PACK_SPAWNER_RADIUS + " of the spawner "
                    + _foundBlazeSpawner.toShortString() + ", waiting (at most " + cap / 20 + " s here)");
        }
        if (wait == adris.altoclef.util.helpers.BlazeCapRules.Wait.CAP) {
            Debug.logInternal("spawner wait: " + _packWait.ticks(key) / 20 + " s waited at the spawner " + _foundBlazeSpawner.toShortString() + ", "
                    + pack + " wither skeletons still within " + (int) BlazeFightRules.PACK_SPAWNER_RADIUS + ", the camp goes on without waiting");
        }
        return wait == adris.altoclef.util.helpers.BlazeCapRules.Wait.HOLD;
    }

    // (Tuning lure_cap) the camp lure's clock: the ticks its looks had the wheel at the camp's corner since a blaze last came
    // within reach (BlazeCapRules.LureWatch)
    private final adris.altoclef.util.helpers.BlazeCapRules.LureWatch _campLureWatch = new adris.altoclef.util.helpers.BlazeCapRules.LureWatch();

    // (Tuning lure_cap) the camp's corner, or a standing pillbox's cell, that drew no blaze into reach: never a corner or a
    // hiding place again (_shutOut, campShutOut), a box given up at this spawner, and the camp picked again without it on the
    // next tick (pickCampSpot: another corner, else as with none). looks below 0: the hide's cap, which said its own line
    private void dropEmptyCamp(AltoClef mod, BlockPos camp, int looks, String how) {
        boolean box = _pillbox != null && _pillboxCornered && camp.equals(_pillbox.cell());
        if (looks >= 0) {
            Debug.logInternal("lure cap: " + _campLureWatch.ticks() / 20 + " s with no blaze in reach, " + how + " (" + looks + " looks), the "
                    + (box ? "pillbox at " : "camp's corner at ") + camp.toShortString() + " dropped, the camp picked again without it, the nook barred, no new hide for "
                    + adris.altoclef.util.helpers.BlazeCapRules.HOLD_OFF_TICKS / 20 + " s");
            // (the hide's cap barred its nook and started the hold-off itself)
            _fight.capNook(mod.getPlayer(), camp, mod.getWorld().getGameTime());
        } else {
            Debug.logInternal("lure cap: the " + (box ? "pillbox at " : "camp's corner at ") + camp.toShortString() + " dropped (" + how
                    + "), the camp picked again without it");
        }
        _campLureWatch.reset();
        _shutOut.add(camp);
        _fight.campShutOut(camp);
        notePillboxSpot();
        // (a far box (far_box) given up leaves the near pillbox its turn, as when it is not built)
        if (box && _far != null && _pillbox == _farBox) _farGaveUpFor = _foundBlazeSpawner;
        else if (box) _pillboxGaveUpFor = _foundBlazeSpawner;
        _doorWalkSince = -1;
        _pillbox = null;
        _pillboxCornered = false;
        _pillboxCornerSet = null;
        _lure = null;
        _fight.corner(null);
        _fight.pillboxPlanned(false);
        _fight.pillboxLookout(null);
        _campSpotFor = null;
        _campSpot = null;
    }

    // the spawner whose camp was left with nothing to take its place: no corner and no box there any more
    private BlockPos _campsOffFor;

    // (and Tuning lure_cap: never in a nook a cap barred, BlazeFight.nookBarred)
    private java.util.function.Predicate<BlockPos> notShut() {
        return p -> !_shutOut.contains(p) && !_fight.nookBarred(p);
    }

    // the spawn eyes in an open cell, as the camp's pick reads them
    private static java.util.List<Vec3> freeSpawnEyes(AltoClef mod, int sx, int sy, int sz) {
        var level = mod.getWorld();
        java.util.List<Vec3> eyes = new java.util.ArrayList<>();
        for (Vec3 eye : BlazeCover.spawnEyes(sx, sy, sz)) {
            if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) eyes.add(eye);
        }
        return eyes;
    }

    // ---- the buddy's fork, Tuning box_home: a box given up at a spawner leaves a try (BoxHomeRules.retry): the next box
    // goes up where the buddy stands (camp_reentry's pillboxHere), looked for once a second till one fits, with its clock
    // from the cell. it replaces the camp picked meanwhile (a corner, the open camp); neither the give-up's bar
    // (_pillboxGaveUpFor) nor camp_reentry's "none" (_campsOffFor) stops it. realfort-pair-home2 B:1: the box given up on
    // the walk barred every box at the spawner, and the buddy fought in the open and died

    // the boxes tried again per spawner, and the spawner a try is owed at (null: none)
    private final java.util.Map<BlockPos, Integer> _homeTries = new java.util.HashMap<>();
    private BlockPos _homeRetryFor;
    private long _homeRetryLookAt = Long.MIN_VALUE / 2;
    private boolean _homeRetryWaitSaid;

    private void homeRetry(AltoClef mod) {
        var me = mod.getPlayer();
        if (!adris.altoclef.util.helpers.BoxHomeRules.on()) {
            _homeRetryFor = null;
            return;
        }
        // (a box up or going up, a heal or brew box, the potion's camp, another spawner, in the air: not now)
        if (me == null || !_homeRetryFor.equals(_foundBlazeSpawner) || _pillbox != null || _healBox != null
                || (_brewBox != null && !_brewBox.isFinished(mod)) || _campFireproof || BlazeFight.fireproofCamp(me) || !me.onGround()
                || mod.getClientBaritone().getPlayerContext() == null) return;
        long now = mod.getWorld().getGameTime();
        if (now - _homeRetryLookAt < adris.altoclef.util.helpers.BoxHomeRules.RETRY_LOOK_TICKS) return;
        _homeRetryLookAt = now;
        int k = _homeTries.getOrDefault(_foundBlazeSpawner, 0) + 1;
        int sx = _foundBlazeSpawner.getX(), sy = _foundBlazeSpawner.getY(), sz = _foundBlazeSpawner.getZ();
        BlockPos feet = me.blockPosition();
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        adris.altoclef.util.helpers.BlazePillbox.Plan here = adris.altoclef.util.helpers.Tuning.get("pillbox", 1) <= 0 ? null
                : adris.altoclef.util.helpers.CampReentryRules.pillboxHere(cells, feet.getX(), feet.getY(), feet.getZ(), sx, sy, sz,
                freeSpawnEyes(mod, sx, sy, sz), adris.altoclef.util.helpers.Tuning.get("pillbox_bent", 1) > 0, BlazePillboxTask.blocksInBag(mod));
        if (here == null) {
            if (!_homeRetryWaitSaid) {
                _homeRetryWaitSaid = true;
                Debug.logInternal(String.format(java.util.Locale.ROOT, "box home: no box fits where the buddy stands at %s (%.0f from the spawner, %d blocks in the bag), try %d of %d waits for a cell that takes one",
                        feet.toShortString(), Math.sqrt(feet.distSqr(_foundBlazeSpawner)), BlazePillboxTask.blocksInBag(mod), k,
                        adris.altoclef.util.helpers.BoxHomeRules.RETRIES));
            }
            return;
        }
        _homeTries.put(_foundBlazeSpawner, k);
        _homeRetryFor = null;
        _campPickedAt = now;
        _doorWalkSince = -1;
        _lure = null;
        _pillbox = new BlazePillboxTask(here);
        _pillbox.clockAtCell();
        _pillboxCornered = false;
        _fight.pillboxPlanned(true);
        _fight.pillboxLookout(_pillbox.lookout());
        _campSpot = _pillbox.cell();
        _campSpotFor = _foundBlazeSpawner;
        // (its cell the fight's corner at once, as camp_reentry's box)
        _pillboxCornerSet = _pillbox.corner(mod);
        _fight.corner(_pillboxCornerSet);
        adris.altoclef.util.snapshot.Snapshots.chose("pillbox", here.cell()).with("lookout", here.lookout()).blocks(here.blocks()).spawner(sx, sy, sz);
        Debug.logInternal("box home: a new box where the buddy stands at " + _campSpot.toShortString() + " (" + here.blocks().size() + " blocks"
                + (here.front() != null ? ", bent" : "") + "), looking out from " + _pillbox.lookout().toShortString() + ", try " + k + " of "
                + adris.altoclef.util.helpers.BoxHomeRules.RETRIES + " after a give-up, its clock from the cell (spawner " + _foundBlazeSpawner.toShortString() + ")");
    }

    private void campReentry(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        var me = mod.getPlayer();
        // (the camp to get back into: the fight's corner, a corner or a standing box's cell; not while a box goes up, whose own
        // walk and clock decide, nor under the potion, whose camp has no corner)
        boolean building = _pillbox != null && !_pillbox.isFinished(mod);
        BlockPos camp = building || _campFireproof || _foundBlazeSpawner == null || me == null ? null : _fight.corner();
        boolean standing = camp != null && _pillbox != null && _pillboxCornered && camp.equals(_pillbox.cell());
        boolean in = camp != null && (BlazeLureTask.in(me.position(), camp) || (standing && _pillbox.holds(me.blockPosition())));
        adris.altoclef.util.helpers.CampReentry.mark(camp, standing ? _pillbox.door() : null, in, now);
        if (camp == null || mod.getClientBaritone().getPlayerContext() == null) return;
        String why = adris.altoclef.util.helpers.CampReentry.shutOut(now);
        if (why == null) return;
        _shutOut.add(camp);
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        int sx = _foundBlazeSpawner.getX(), sy = _foundBlazeSpawner.getY(), sz = _foundBlazeSpawner.getZ();
        BlockPos feet = me.blockPosition();
        String head = "camp: shut out of " + camp.toShortString() + " (" + why + "), ";
        // 1. a pillbox round the cell it stands on, with the blocks in the bag
        adris.altoclef.util.helpers.BlazePillbox.Plan here = adris.altoclef.util.helpers.Tuning.get("pillbox", 1) <= 0 ? null
                : adris.altoclef.util.helpers.CampReentryRules.pillboxHere(cells, feet.getX(), feet.getY(), feet.getZ(), sx, sy, sz,
                freeSpawnEyes(mod, sx, sy, sz), adris.altoclef.util.helpers.Tuning.get("pillbox_bent", 1) > 0, BlazePillboxTask.blocksInBag(mod));
        // 2. else another corner, not one shut out of, that a walk from here reaches
        int[][] other = null;
        BlazeLureTask otherLure = null;
        if (here == null) {
            java.util.Set<Long> reach = adris.altoclef.util.helpers.CampReentryRules.reachable(cells, feet.getX(), feet.getY(), feet.getZ(),
                    adris.altoclef.util.helpers.CampReentryRules.REACH_STEPS);
            java.util.Set<Long> shut = new java.util.HashSet<>();
            for (BlockPos p : _shutOut) shut.add(BlazeCover.key(p.getX(), p.getY(), p.getZ()));
            // (Tuning box_home: a box where the buddy stands or none, never a walk round to another corner)
            other = _noCorner || adris.altoclef.util.helpers.BoxHomeRules.on() ? null : cornerSpot(mod, cells, sx, sy, sz,
                    p -> adris.altoclef.util.helpers.CampReentryRules.cornerAllowed(p.getX(), p.getY(), p.getZ(), shut, reach) && !_fight.nookBarred(p), false);
            if (other != null) {
                otherLure = BlazeLureTask.of(cells, new BlockPos(other[0][0], other[0][1], other[0][2]), new BlockPos(other[1][0], other[1][1], other[1][2]));
                if (otherLure == null) other = null;
            }
        }
        notePillboxSpot();
        _fight.campShutOut(camp);
        _doorWalkSince = -1;
        _pillbox = null;
        _pillboxCornered = false;
        _pillboxCornerSet = null;
        _lure = null;
        _fight.pillboxPlanned(false);
        _fight.pillboxLookout(null);
        switch (adris.altoclef.util.helpers.CampReentryRules.choose(here != null, other != null)) {
            case PILLBOX -> {
                _campPickedAt = now;
                _pillbox = new BlazePillboxTask(here);
                _fight.pillboxPlanned(true);
                _fight.pillboxLookout(_pillbox.lookout());
                _campSpot = _pillbox.cell();
                // (its cell the fight's corner at once, not from the first wall: a heal run then stays at it and builds, as
                // pillbox_heal_build does, instead of a run off into the blazes' fire again)
                _pillboxCornerSet = _pillbox.corner(mod);
                _fight.corner(_pillboxCornerSet);
                adris.altoclef.util.snapshot.Snapshots.chose("pillbox", here.cell()).with("lookout", here.lookout()).blocks(here.blocks()).spawner(sx, sy, sz);
                Debug.logInternal(head + "a pillbox where it stands at " + _campSpot.toShortString() + " (" + here.blocks().size() + " blocks"
                        + (here.front() != null ? ", bent" : "") + "), looking out from " + _pillbox.lookout().toShortString());
            }
            case CORNER -> {
                _campPickedAt = now;
                _campSpot = new BlockPos(other[0][0], other[0][1], other[0][2]);
                _fight.corner(_campSpot);
                _lure = otherLure;
                adris.altoclef.util.snapshot.Snapshots.chose("blaze.corner", other[0]).with("peek", other[1]).spawner(sx, sy, sz);
                Debug.logInternal(head + "another corner at " + _campSpot.toShortString() + " (closed in "
                        + BlazeCover.enclosure(mod.getWorld(), other[0][0], other[0][1], other[0][2], me) + " of 24), looking out from "
                        + new BlockPos(other[1][0], other[1][1], other[1][2]).toShortString());
            }
            case NONE -> {
                _campsOffFor = _foundBlazeSpawner;
                _campSpotFor = null;
                _campSpot = null;
                _fight.corner(null);
                Debug.logInternal(head + "none, the run without one");
            }
        }
        adris.altoclef.util.helpers.CampReentry.left(camp, now);
    }

    private void pickSpawner(AltoClef mod) {
        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            _foundBlazeSpawner = null;
        }
        if (_foundBlazeSpawner != null || !mod.getBlockTracker().isTracking(Blocks.SPAWNER)) return;
        // nearest one we can actually look at: asking for the single nearest and then throwing it out for being in an
        // unloaded chunk meant a perfectly good loaded spawner one tile further never got a look
        Optional<BlockPos> spawner = mod.getBlockTracker().getNearestTracking(p -> isValidBlazeSpawner(mod, p), Blocks.SPAWNER);
        spawner.ifPresent(found -> {
            _foundBlazeSpawner = found;
            // (the drills time the way to the fortress by this line)
            Debug.logInternal("blaze spawner found at " + found.toShortString());
        });
    }

    private boolean isValidBlazeSpawner(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            // If unloaded, go to it. Unless it's super far away.
            return false;
            //return pos.isWithinDistance(mod.getPlayer().getPos(),3000);
        }
        return WorldHelper.getSpawnerEntity(mod, pos) instanceof Blaze;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // (Tuning fireproof_rush: no fire walk outside the rods task; it runs out by itself in a second and a half anyway)
        baritone.altoclef.AltoClefSettings.getInstance().walkThroughFire(false);
        mod.getBlockTracker().stopTracking(Blocks.SPAWNER);
        mod.getBlockTracker().stopTracking(Blocks.NETHER_WART);
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
        if (_sweepTracking) {
            ObtainSweep.track(mod, false);
            _sweepTracking = false;
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBlazeRodsTask;
    }

    @Override
    protected String toHudString() {
        return "Hunting Blazes for " + HudText.count(_count, "Blaze Rod");
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blaze rods";
    }

    // the buddy's fork: not while running or with a blaze close by, see BlazeFightRules.holdPickup
    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // (a rod a step or two off is taken whatever is about: two lay 2.4 off in the door for six game minutes, the corner holding
        // the pickup back and the fight never giving the lure its turn, corner19 run 6)
        var me = mod.getPlayer();
        // (the buddy's fork: none while shut in the brew box, a walk to a rod is a way out through its wall)
        if (_brewBox != null && _brewBox.holds(mod)) return true;
        if (_healBox != null && _healBox.holds(mod)) return true;
        // (the buddy's fork, Tuning box_home: while the box holds, a rod in it always, one outside by a trip out of it,
        // BoxHomeRules.rodLeft and tripEnd; the camp's walk takes the buddy straight back)
        if (me != null && homeHolds()) return !homeRodFetch(mod, me);
        // (Tuning fireproof_rush: each rod at once under the rush, only a blaze in bite's reach or a run holds it. the nearest
        // blaze read now: the fight does not scan while the pickup walks)
        if (me != null && BlazeFight.rush(me) != FightOpenRules.Rush.OFF) {
            return FightOpenRules.holdsPickup(_fight.mode() == BlazeFightRules.Mode.RUN, nearestBlazeNow(mod, me));
        }
        // (from a standing pillbox: a rod in the box always, one outside it only with no blaze within PILLBOX_QUIET. six walks out
        // for rods in one run, the last two 25 hp, straight-bent-g run 4)
        if (me != null && _pillbox != null && _pillboxCornered) {
            Vec3 c = Vec3.atBottomCenterOf(_pillbox.cell());
            boolean inBox = mod.getEntityTracker().getClosestItemDrop(me.position(), Items.BLAZE_ROD)
                    .filter(d -> d.position().distanceTo(c) <= PILLBOX_RADIUS).isPresent();
            if (inBox) return false;
            if (_fight.nearestBlaze() <= adris.altoclef.util.helpers.Tuning.get("pillbox_quiet", PILLBOX_QUIET)) return true;
            // (and none out there once a trip out has run its time, steering 35: the pickup carried two of those trips on)
            if (_fight.tripOver()) return true;
        }
        // (Tuning rod_fetch: only with no climb to it. the near rods lay on a ledge two up where the blazes die, and the one trip
        // up there that went through this rule died at the rod's own spot, piece2fix-4 run 4; the lure's fetch has the tests)
        boolean noClimb = adris.altoclef.util.helpers.Tuning.get("rod_fetch", 0) <= 0;
        if (me != null && mod.getEntityTracker().getClosestItemDrop(me.position(), Items.BLAZE_ROD)
                .filter(d -> d.distanceTo(me) <= ROD_STEP && (noClimb || d.getY() <= me.getY() + ROD_CLIMB)).isPresent()) return false;
        // (the buddy's fork, Tuning fireproof_reach: under its own potion the pickup waits only for a blaze within bite's reach. the
        // balls do nothing then, and rods lay out while a blaze hovered within five, piece2side-2 run 1)
        if (me != null && BlazeFight.fireproofReach(me) > 0 && _fight.mode() != BlazeFightRules.Mode.RUN) {
            return _fight.nearestBlaze() <= BlazeFight.BITE_RANGE;
        }
        // (and while the corner holds: the lure fetches the rods near it on a leash; the pickup's walk has none)
        // (Tuning open_attack: not while it holds, there is no lure to fetch them)
        return BlazeFightRules.holdPickup(_fight.mode() == BlazeFightRules.Mode.RUN, _fight.nearestBlaze()) || (_fight.cornerHold() && !_fight.open());
    }

    // the buddy's fork, Tuning camp_dry (game minutes, 0 off): a corner camp with no rod in that long while a blaze is within
    // CAMP_DRY_NEAR is picked again without a corner, so a pillbox. the corners at two fortresses of set a gave no rod in
    // twelve minutes with five blazes within 8, run after run and block after block (6 Nov 2026: bent-n-ap-1, door-2,
    // piece2ap-1, piece2pill-1 at 3553 67 1211; bent-n-rm-1, door-1 at 4482 64 -1198)
    static final double CAMP_DRY_NEAR = 16;
    private long _campPickedAt = -1;
    private long _lastRodAt = -1;
    private int _lastRodCount;
    private boolean _noCorner;

    private void noteRods(AltoClef mod) {
        int n = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        if (n > _lastRodCount) _lastRodAt = mod.getWorld().getGameTime();
        _lastRodCount = n;
    }

    private void campDry(AltoClef mod) {
        double dry = adris.altoclef.util.helpers.Tuning.get("camp_dry", 0);
        if (dry <= 0 || _noCorner || _campSpotFor == null || _pillbox != null || _fight.corner() == null || _campFireproof) return;
        long now = mod.getWorld().getGameTime();
        long since = Math.max(_lastRodAt, _campPickedAt);
        if (since < 0 || now - since < dry * 1200 || now < since) return;
        if (_fight.nearestBlaze() > CAMP_DRY_NEAR) return;
        Debug.logInternal("blaze camp: no rod at the corner " + (_campSpot == null ? "" : _campSpot.toShortString() + " ") + "in " + dry
                + " game min with a blaze within " + String.format(java.util.Locale.ROOT, "%.0f", _fight.nearestBlaze()) + ", the camp picked again without a corner");
        _noCorner = true;
        _campSpotFor = null;
        _campSpot = null;
        _lure = null;
        _fight.corner(null);
    }

    // the walk round to a standing pillbox's open side, at most this long in one go (ticks), and the box it gave up on
    static final long DOOR_WALK_TICKS = 400;
    private long _doorWalkSince = -1;

    // the fire by the spawner that is being put out, the ticks spent on it, and the fires left as out of reach
    static final int FIRE_TICKS = 200;
    private BlockPos _fireAt;
    private int _fireTicks;
    private final java.util.Set<BlockPos> _fireLeft = new java.util.HashSet<>();
    private BlazePillboxTask _doorGaveUp;

    // the last standing pillbox's open side (its door, else its look out), the fire-proof camp once its potion is drunk
    private BlockPos _fireproofSpot;
    // a box's open side farther than this from the spawner (flat) is no camp of that spawner
    static final double FIREPROOF_SPOT_RANGE = 12;

    // the fire-proof camp's walk to the spawner's top at most this long before the box's open side (ticks): the top behind the
    // buddy's own tunnels kept the walk and the chase in turns for eleven minutes, Ari
    static final long FIREPROOF_WALK_TICKS = 600;

    private void fireproofFallback(AltoClef mod) {
        if (adris.altoclef.util.helpers.Tuning.get("fireproof_top", 0) <= 0) return;
        if (!_campFireproof || _campSpot != null || _fireproofSpot == null || _foundBlazeSpawner == null || _campPickedAt < 0) return;
        if (_foundBlazeSpawner.above().closerToCenterThan(mod.getPlayer().position(), CAMP_RADIUS)) return;
        long now = mod.getWorld().getGameTime();
        if (now - _campPickedAt < FIREPROOF_WALK_TICKS || now < _campPickedAt) return;
        if (Math.hypot(_fireproofSpot.getX() - _foundBlazeSpawner.getX(), _fireproofSpot.getZ() - _foundBlazeSpawner.getZ()) > FIREPROOF_SPOT_RANGE) return;
        _campSpot = _fireproofSpot;
        Debug.logInternal("blaze camp: fire-proof, the spawner's top not reached in " + FIREPROOF_WALK_TICKS / 20 + " s, at the pillbox's open side " + _campSpot.toShortString());
    }

    // ---- the buddy's fork, Tuning fireproof_rush (FightOpenRules, off by default, steering 94): the rush under fire resistance

    // the rush as of the last tick, the rods in the bag then, and the stands by the spawner (null when the camp is not the
    // rush's), the one taken and the ticks spent walking to it (a fight or a pickup in between does not count)
    private FightOpenRules.Rush _rush = FightOpenRules.Rush.OFF;
    private int _rushRods = -1;
    private List<int[]> _rushStands;
    private int _rushStand;
    private long _rushWalkTicks;

    // every tick of the task, a pickup's too: the rush's state and its lines, and fire walkable for the pathfinder while it is on
    private void rushTick(AltoClef mod) {
        var me = mod.getPlayer();
        if (me == null || mod.getWorld() == null) return;
        boolean on = FightOpenRules.rushOn();
        FightOpenRules.Rush r = BlazeFight.rush(me);
        baritone.altoclef.AltoClefSettings.getInstance().walkThroughFire(FightOpenRules.fireWalkable(r));
        int rods = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        if (r != _rush) {
            int left = BlazeFight.fireResTicks(me);
            int potions = BlazeFight.fireResPotions(me);
            if (r == FightOpenRules.Rush.OFF) {
                Debug.logInternal("rush: off (" + FightOpenRules.offWhy(on, left, potions) + "), " + rods + " rods");
            } else if (r == FightOpenRules.Rush.REDRINK) {
                Debug.logInternal("rush: " + BlazeFight.effectLeft(me) + " of the effect left, the potion goes down again as before ("
                        + potions + " left), fire not walked through meanwhile");
            } else if (_rush == FightOpenRules.Rush.OFF) {
                Debug.logInternal("rush: on, " + BlazeFight.effectLeft(me) + " of fire resistance, " + potions + " potions left, " + rods + " rods"
                        + (_foundBlazeSpawner != null ? ", the spawner at " + _foundBlazeSpawner.toShortString() : ", no spawner known yet"));
            } else {
                Debug.logInternal("rush: on again, " + BlazeFight.effectLeft(me) + " of fire resistance, " + potions + " potions left");
            }
            _rush = r;
        }
        if (r != FightOpenRules.Rush.OFF && _rushRods >= 0 && rods > _rushRods) {
            Debug.logInternal("rush: a rod, " + rods + " in the bag, " + BlazeFight.effectLeft(me) + " of the effect left");
        }
        _rushRods = rods;
    }

    // the nearest live blaze to the buddy as the tracker has it now (the fight's last scan when the tracker is being rebuilt)
    private double nearestBlazeNow(AltoClef mod, net.minecraft.world.entity.player.Player me) {
        double best = Double.POSITIVE_INFINITY;
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive()) best = Math.min(best, b.distanceTo(me));
            }
        } catch (java.util.ConcurrentModificationException e) {
            return _fight.nearestBlaze();
        }
        return best;
    }

    private int[] rushStandCell() {
        return _rushStands.get(Math.min(_rushStand, _rushStands.size() - 1));
    }

    private BlockPos rushStandPos() {
        int[] c = rushStandCell();
        return new BlockPos(c[0], c[1], c[2]);
    }

    // the camp spot of the stand taken: null for the spawner's top (the camp by the spawner as fpcamp's), else the cell
    private BlockPos rushStandSpot() {
        BlockPos p = rushStandPos();
        return p.equals(_foundBlazeSpawner.above()) ? null : p;
    }

    // the walk to the rush's stand, each tick it runs: true when it was given up this tick and the next stand taken
    private boolean rushWalk() {
        if (_rushStands == null || !_campFireproof || _foundBlazeSpawner == null || !_foundBlazeSpawner.equals(_campSpotFor)) return false;
        if (++_rushWalkTicks < FightOpenRules.RUSH_WALK_TICKS) return false;
        _rushWalkTicks = 0;
        if (_rushStand + 1 >= _rushStands.size()) {
            Debug.logInternal("rush: the stand at " + rushStandPos().toShortString() + " not reached in " + FightOpenRules.RUSH_WALK_TICKS / 20
                    + " s, no other stand by the spawner, the walk goes on");
            return false;
        }
        BlockPos was = rushStandPos();
        _rushStand++;
        _campSpot = rushStandSpot();
        Debug.logInternal("rush: the stand at " + was.toShortString() + " not reached in " + FightOpenRules.RUSH_WALK_TICKS / 20 + " s, at "
                + rushStandPos().toShortString() + " next (stand " + (_rushStand + 1) + " of " + _rushStands.size() + ")");
        return true;
    }

    // ---- the buddy's fork, Tuning box_home (BoxHomeRules): the box as home

    // each tick: whether the standing box holds the buddy; while it does its cell is marked for the wander guard (BoxGuard, on
    // with box_home) and a rod trip that is back in the box is said
    private void homeTick(AltoClef mod) {
        boolean stands = _pillbox != null && _pillboxCornered;
        _homeHolds = adris.altoclef.util.helpers.BoxHomeRules.on()
                && adris.altoclef.util.helpers.BoxHomeRules.holds(stands, mod.getItemStorage().getItemCount(Items.BLAZE_ROD), _count);
        BlazePillboxTask box = stands ? _pillbox : null;
        if (_homeSaidFor != box) {
            _homeSaidFor = box;
            _homeSaid.clear();
            _rodsLetGo.clear();
            _rodLeftSaid.clear();
        }
        if (!homeHolds() || mod.getPlayer() == null) {
            _trip = null;
            _tripAt = -1;
            _tripBackFrom = -1;
            return;
        }
        long now = mod.getWorld().getGameTime();
        adris.altoclef.util.helpers.BoxGuard.mark(_pillbox.cell(), now);
        homeBack(mod, now);
    }

    // a trip over and the buddy in the box again: said once, when it was seen out of it
    private void homeBack(AltoClef mod, long now) {
        if (_tripBackFrom < 0 || _trip != null || mod.getPlayer() == null || !_pillbox.holds(mod.getPlayer().blockPosition())) return;
        if (_tripOut) Debug.logInternal("box home: back in the box after the rod, " + Math.max(0, now - _tripBackFrom) / 20 + " s out");
        _tripBackFrom = -1;
        _tripOut = false;
    }

    // the box holds the buddy: as of this tick's homeTick, and the box still standing (a box given up or replaced later in the
    // tick, or since the last one, holds nothing)
    private boolean homeHolds() {
        return _homeHolds && _pillbox != null && _pillboxCornered;
    }

    // an errand of the camp skipped while the box holds, said once per box and errand (the words before " at ")
    private void homeSkip(String what) {
        if (_pillbox == null) return;
        int at = what.indexOf(" at ");
        if (_homeSaid.add(at < 0 ? what : what.substring(0, at))) {
            Debug.logInternal("box home: " + what + " skipped, the box at " + _pillbox.cell().toShortString() + " holds");
        }
    }

    // the pickup's rods while the box holds (asked once a tick by shouldAvoidPickingUp, the pickup's ticks too): the pickup walks
    // to the closest rod, so that one is judged. one in the box's cells is always taken; one outside by a trip out of the box
    // (BoxHomeRules.rodLeft), which goes on till BoxHomeRules.tripEnd says over, whatever the next tick's gate says (B:2's
    // pickup and lure took turns every tick). one rod per trip; a trip over leaves the camp's walk to take the buddy home
    private boolean homeRodFetch(AltoClef mod, net.minecraft.world.entity.LivingEntity me) {
        long now = mod.getWorld().getGameTime();
        int rods = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        boolean in = _pillbox.holds(me.blockPosition());
        Optional<net.minecraft.world.entity.item.ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(me.position(), Items.BLAZE_ROD);
        if (_trip != null) {
            if (!in) _tripOut = true;
            _tripBestHp = Math.max(_tripBestHp, me.getHealth());
            boolean gone = !_trip.isAlive() || _trip.getItem().isEmpty();
            String end = adris.altoclef.util.helpers.BoxHomeRules.tripEnd(gone, rods > _tripRods, !gone && rodBurning(mod, _trip),
                    gone || mod.getEntityTracker().isEntityReachable(_trip),
                    me.getHealth() < _tripBestHp - adris.altoclef.util.helpers.BoxHomeRules.HIT, now - _tripAt);
            if (end == null) {
                // (on to this rod, or to one in the box on the way; another closer rod outside waits this tick)
                return closest.isEmpty() || closest.get().getId() == _trip.getId() || _pillbox.holds(BlockPos.containing(closest.get().position()));
            }
            Debug.logInternal(String.format(java.util.Locale.ROOT, "box home: the rod trip over (%s) after %.1f s at %.1f hp, %s",
                    end, Math.max(0, now - _tripAt) / 20.0, me.getHealth(), in ? "in the box" : "back to the box"));
            if (adris.altoclef.util.helpers.BoxHomeRules.letGoAfter(end)) _rodsLetGo.add(_trip.getId());
            if (end.equals("hit")) _tripHitAt = now;
            _tripBackFrom = _tripAt;
            _trip = null;
            _tripAt = -1;
            homeBack(mod, now);
        }
        if (closest.isEmpty()) return true;
        net.minecraft.world.entity.item.ItemEntity rod = closest.get();
        Vec3 at = rod.position();
        if (_pillbox.holds(BlockPos.containing(at))) return true;
        BlockPos cell = _pillbox.cell();
        double flat = Math.hypot(at.x - (cell.getX() + 0.5), at.z - (cell.getZ() + 0.5));
        double up = at.y - cell.getY();
        double blaze = Double.POSITIVE_INFINITY;
        for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
            if (b.isAlive()) blaze = Math.min(blaze, Math.min(b.distanceTo(me), b.distanceTo(rod)));
        }
        String why = adris.altoclef.util.helpers.BoxHomeRules.rodLeft(flat, up, rodBurning(mod, rod), _rodsLetGo.contains(rod.getId()), in,
                me.getHealth(), blaze, adris.altoclef.util.helpers.FireRes.has(me), now - _tripHitAt);
        if (why != null) {
            homeRodLeft(rod, why, flat, up, me.getHealth(), blaze, now);
            return false;
        }
        _trip = rod;
        _tripAt = now;
        // (the fight's ban on placing at the feet and up is its own: a rod two up is a pillar step, and the fight's last tick
        // may have been a KILL)
        _fight.idle();
        _tripBestHp = me.getHealth();
        _tripRods = rods;
        _tripOut = false;
        _tripBackFrom = -1;
        Debug.logInternal(String.format(java.util.Locale.ROOT, "box home: out for a rod at %s, %.1f from the box, %.1f up, %.1f hp, the nearest blaze %s",
                BlockPos.containing(at).toShortString(), flat, up, me.getHealth(), blaze == Double.POSITIVE_INFINITY ? "none" : String.format(java.util.Locale.ROOT, "%.1f off", blaze)));
        return true;
    }

    // why a rod outside the box is left, said once per rod and reason (the box's own state, out of it, is not said)
    private void homeRodLeft(net.minecraft.world.entity.item.ItemEntity rod, String why, double flat, double up, float hp, double blaze, long now) {
        if (why.equals("out of the box") || !_rodLeftSaid.add(rod.getId() + " " + why)) return;
        String detail = switch (why) {
            case "on fire" -> "on fire, it burns";
            case "let go" -> "let go after its trip";
            case "far" -> String.format(java.util.Locale.ROOT, "%.1f from the box", flat);
            case "high" -> String.format(java.util.Locale.ROOT, "%.1f up", up);
            case "low" -> String.format(java.util.Locale.ROOT, "%.1f down", -up);
            case "hp" -> String.format(java.util.Locale.ROOT, "%.1f hp", hp);
            case "blaze" -> String.format(java.util.Locale.ROOT, "a blaze %.1f off", blaze);
            case "hit" -> "a hit " + Math.max(0, now - _tripHitAt) / 20 + " s ago";
            default -> why;
        };
        Debug.logInternal("box home: a rod at " + BlockPos.containing(rod.position()).toShortString() + " left (" + detail + ")");
    }

    // a rod burning, or in fire or lava: it is gone within ticks
    private static boolean rodBurning(AltoClef mod, net.minecraft.world.entity.item.ItemEntity rod) {
        if (rod.isOnFire() || rod.isInLava()) return true;
        BlockPos p = BlockPos.containing(rod.position());
        var state = mod.getWorld().getBlockState(p);
        return state.is(net.minecraft.tags.BlockTags.FIRE) || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    // the heal wait in the box sealed (BoxHomeRules.sealHeal): a BrewBoxTask in heal mode walls the look out from inside, the
    // buddy waits in the back cell while the food chain eats, and the wall is broken again (BrewBoxTask.Stage.HEAL)
    private Task sealHealStart(AltoClef mod) {
        if (_healBox != null || !homeHolds()) return null;
        var me = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        if (me == null || (now < _healRetryAt && _healRetryAt - now <= HEAL_RETRY_TICKS)) return null;
        boolean brewBusy = _brewBox != null && _brewBox.active();
        int[][] cells = _pillbox.brewCells();
        if (cells == null) return null;
        // (the look out's column, two blocks at least; the plan's own count is checked as it is made)
        if (!adris.altoclef.util.helpers.BoxHomeRules.sealHeal(true, true, _pillbox.holds(me.blockPosition()), brewBusy,
                BlazePillboxTask.blocksInBag(mod), 2)) return null;
        _healBox = BrewBoxTask.heal(_foundBlazeSpawner, cells);
        _healBeganHp = me.getHealth();
        Debug.logInternal(String.format(java.util.Locale.ROOT, "box home: sealed heal begun at %s, %.1f hp", _pillbox.cell().toShortString(), me.getHealth()));
        setDebugState("Sealing the box to heal");
        return _healBox;
    }

    // the sealed heal's turn, before the fight: its plan, its wall and its wait while the box holds, its wall broken while
    // the buddy is still inside; null gives the tick to the fight (a mob inside, the box broken, low health)
    private Task healStep(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        var me = mod.getPlayer();
        if (_healBox.isFinished(mod)) {
            Debug.logInternal(String.format(java.util.Locale.ROOT, "box home: sealed heal done (%s), %.1f hp from %.1f",
                    _healBox.failed() ? "no seal" : _healBox.healWhy(), me == null ? 0 : me.getHealth(), _healBeganHp));
            if (_healBox.failed()) _healRetryAt = now + HEAL_RETRY_TICKS;
            _healBox = null;
            return null;
        }
        // (the box given up or replaced meanwhile: the seal was its look out's)
        if (!(_pillbox != null && _pillboxCornered) && !_healBox.opening()) {
            _healBox.giveUp("the box gone");
            if (!_healBox.opening()) {
                _healBox = null;
                return null;
            }
        }
        if (_healBox.preparing()) {
            setDebugState("Sealing the box to heal");
            return _healBox;
        }
        if (_healBox.holds(mod)) {
            adris.altoclef.util.helpers.BlazeShelter.brewBox(now);
            setDebugState("Healing sealed in the box");
            return _healBox;
        }
        if (_healBox.breakingOut(mod)) {
            setDebugState("Opening the box after the heal");
            return _healBox;
        }
        // (a failed seal breaks what it placed wherever the buddy is: its blocks left standing shut the look out, B:2)
        if (_healBox.unsealing()) {
            setDebugState("Opening the box after a failed seal");
            return _healBox;
        }
        return null;
    }

    private void notePillboxSpot() {
        if (_pillbox == null) return;
        BlockPos door = _pillbox.door();
        _fireproofSpot = door != null ? door : _pillbox.lookout();
    }

    // a rod this close is picked up at any time, see shouldAvoidPickingUp; with rod_fetch on, only one at most this far above
    // the feet (a slab, a step), not on a ledge
    static final double ROD_STEP = 3;
    static final double ROD_CLIMB = 0.6;
}
