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
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
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
    // the buddy's fork, Tuning fr_trade: the barter for the first fire resistance, one per order (kept through interruptions);
    // null with the tuning off
    private FireResTrade _frTrade;

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

    public CollectBlazeRodsTask(int count) {
        this(count, List::of);
    }

    public CollectBlazeRodsTask(int count, Supplier<List<double[]>> deathSpots) {
        super(Items.BLAZE_ROD, count);
        _count = count;
        _deathSpots = deathSpots;
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
        if (_brewBox != null) _brewBox.noteHits(mod);
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
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        // (the buddy's fork, Tuning far_box: the clock of the strategy's lines starts with the first tick in the nether)
        if (_netherSince < 0 && farOn()) _netherSince = mod.getWorld().getGameTime();
        // the spawner is what starts the dry timer, finding one only between fights meant a long fight never found it
        pickSpawner(mod);
        noteRods(mod);
        campDry(mod);
        // (the buddy's fork, Tuning far_box: the far box's kills and the two rods, watched every tick, the fight's too)
        if (_farBegunFor != null) farWatch(mod);
        // (again: the goal the walk is judged against is the spawner we just picked, not last tick's)
        noteZoneInputs(mod);
        fireproofFallback(mod);
        // (the buddy's fork, Tuning fr_trade, off by default, steering 89: the first fire resistance bartered from piglins before
        // the fortress, FireResTrade. a splash potion of it is thrown at the feet when the drink would go down, a drinkable one
        // the food chain drinks as it does the brewed one)
        if (adris.altoclef.util.helpers.Tuning.get("fr_trade", 0) > 0) {
            if (_frTrade == null) _frTrade = new FireResTrade();
            Task splash = _frTrade.throwStep(mod);
            if (splash != null) {
                setDebugState("Throwing fire resistance at the feet");
                return splash;
            }
            Task trade = _frTrade.tick(mod, _foundBlazeSpawner);
            if (trade != null) {
                setDebugState(_frTrade.hud());
                return trade;
            }
        }
        // (the buddy's fork: the camp was picked with or without fire resistance; a potion brewed and drunk here, or one run
        // out, is a camp of the other kind, so the pick goes again and a box under way is dropped)
        if (_campSpotFor != null && BlazeFight.fireproofCamp(mod.getPlayer()) != _campFireproof) {
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
        // (the buddy's fork, Tuning camp_reentry, off by default: shut out of the camp, a pillbox where the buddy stands, another
        // corner or none, campReentry)
        if (adris.altoclef.util.helpers.CampReentry.on()) campReentry(mod);
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
        boolean brewBuilding = _brewBox != null && _brewBox.building();
        _fight.building(building || brewBuilding);
        java.util.Set<BlockPos> exempt = building ? _pillbox.blocks() : java.util.Set.of();
        if (brewBuilding) {
            exempt = new java.util.HashSet<>(exempt);
            exempt.addAll(_brewBox.blocks());
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
            return null;
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

        Task brew = brewStep(mod);
        if (brew != null) {
            return brew;
        }
        // If we have a blaze spawner, stand next to it in the open and let them come.
        if (_foundBlazeSpawner != null) {
            // a wither skeleton pack at the spawner: hold where we are, the rods watch counts it as dud time and moves us on
            // if they never leave. walking in is how the last two runs ended
            // (the buddy's fork, Tuning far_box: not with a far box standing, out of the pack's way: the hold would keep the buddy
            // wherever it stood, out on a spawn trip too. the trips stay home while the pack is there, farTripStep)
            boolean farStands = _far != null && _pillbox == _farBox && _pillboxCornered;
            int pack = farStands ? 0 : BlazeFight.witherSkeletonsNear(mod, Vec3.atCenterOf(_foundBlazeSpawner), BlazeFightRules.PACK_SPAWNER_RADIUS);
            if (!farStands && BlazeFightRules.packNear(pack) && packWaits(mod, pack)) {
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
            if (nearestFire.isPresent() && _pillbox != null && _pillboxCornered
                    && (fireInBox ? !_pillbox.holds(nearestFire.get())
                    : !nearestFire.get().closerToCenterThan(Vec3.atCenterOf(_pillbox.cell()), PILLBOX_RADIUS))) nearestFire = Optional.empty();
            // (the buddy's fork: a fire not put out after FIRE_TICKS spent on it is left for good. one out of reach was picked
            // again every tick, "No path found" over and over, and the camp's wait never came: three minutes at the spawner with
            // no rod, piece2side-2 run 2. ticks spent on it, not since it was first picked: a fight in between does not count)
            // (behind Tuning fire_limit, off by default, steering 78, with roof_centre under the same suspicion)
            boolean fireLimit = adris.altoclef.util.helpers.Tuning.get("fire_limit", 0) > 0;
            if (fireLimit && nearestFire.isPresent() && _fireLeft.contains(nearestFire.get())) nearestFire = Optional.empty();
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
            if (_lure != null && _fight.corner() != null) {
                // (the buddy's fork, Tuning lure_cap, off by default: looks that drew no blaze into reach for the cap drop this
                // corner (or standing pillbox), the camp picked again without it on the next tick: another corner, else as
                // with none)
                long cap = adris.altoclef.util.helpers.BlazeCapRules.lureCapTicks();
                BlockPos at = _fight.corner();
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
        var us = mod.getPlayer();
        // (Tunings cream_down and cream_up: how far below and above our feet a cube may be, 2 and 3 by default. in the fortress the
        // cubes in reach were nearly all on another level, brew2-s8-1, 5 Nov 2026)
        if (us == null || cube.getY() < us.getY() - adris.altoclef.util.helpers.Tuning.get("cream_down", 2)
                || cube.getY() > us.getY() + adris.altoclef.util.helpers.Tuning.get("cream_up", 3)) return "level";
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
            if (_brew.isFinished(mod) && !boxed) {
                _brewSince = -1;
            } else if (WorldHelper.getTicks() - _brewSince > BREW_BUDGET_TICKS) {
                Debug.logMessage("Brewing fire resistance took too long, back to the blazes");
                Debug.logInternal("brew: given up, too long");
                _brewSince = -1;
                _brewGaveUp = true;
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
        if (!cream) {
            logCreamCubes(mod);
            // a tiny one drops nothing
            var us = mod.getPlayer();
            double range = creamRange();
            if (mod.getEntityTracker().getClosestEntity(us.position(), e -> e instanceof MagmaCube c && c.isAlive() && c.getSize() > 1
                    && c.distanceTo(us) <= range && creamCube(mod, c), MagmaCube.class).isPresent()) {
                setDebugState("A magma cube for its cream");
                return new KillAndLootTask(MagmaCube.class, e -> e instanceof MagmaCube c && c.getSize() > 1 && c.distanceTo(us) <= range
                        && creamCube(mod, c), new ItemTarget(Items.MAGMA_CREAM, 1));
            }
            // the last cube dead and its cream on the floor: the gate above no longer holds, the drop is picked up all the same
            if (dropNear(mod, Items.MAGMA_CREAM, range)) {
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
        int[][] corner = _noCorner || campsOff ? null : cornerSpot(mod, cells, sx, sy, sz, _shutOut.isEmpty() ? null : notShut(), true);
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
                    + (box ? "pillbox at " : "camp's corner at ") + camp.toShortString() + " dropped, the camp picked again without it");
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

    private java.util.function.Predicate<BlockPos> notShut() {
        return p -> !_shutOut.contains(p);
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
            other = _noCorner ? null : cornerSpot(mod, cells, sx, sy, sz,
                    p -> adris.altoclef.util.helpers.CampReentryRules.cornerAllowed(p.getX(), p.getY(), p.getZ(), shut, reach), false);
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
        mod.getBlockTracker().stopTracking(Blocks.SPAWNER);
        mod.getBlockTracker().stopTracking(Blocks.NETHER_WART);
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
        return BlazeFightRules.holdPickup(_fight.mode() == BlazeFightRules.Mode.RUN, _fight.nearestBlaze()) || _fight.cornerHold();
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
