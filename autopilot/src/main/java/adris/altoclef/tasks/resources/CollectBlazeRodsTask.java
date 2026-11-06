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
    // 0, realfort-a-bent-i runs 2 and 4)
    private static final double PILLBOX_QUIET = 6;
    private final int _count;
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    // the fight logic lives in there. this task used to just camp, silently, while blazes it had decided not to see shot at it
    private final BlazeFight _fight = new BlazeFight();
    // fire resistance from the kit's water bottles: fire was over half the nether's damage, and the blazes' fireballs, lava and
    // the burning are all fire. the wart and the cream are taken as they come along, never a trip of their own
    private final BrewFireResistanceTask _brew = new BrewFireResistanceTask(BrewRules.BATCH);
    private static final double CREAM_RANGE = 24;
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
    // a fight has the brew's ticks now (logged once per take over)
    private boolean _brewCutOff;

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

    @Override
    protected Task onTick(AltoClef mod) {
        _rodWatch.tick(mod);
        return super.onTick(mod);
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
        // the spawner is what starts the dry timer, finding one only between fights meant a long fight never found it
        pickSpawner(mod);
        // (again: the goal the walk is judged against is the spawner we just picked, not last tick's)
        noteZoneInputs(mod);
        // (the buddy's fork: the camp was picked with or without fire resistance; a potion brewed and drunk here, or one run
        // out, is a camp of the other kind, so the pick goes again and a box under way is dropped)
        if (_campSpotFor != null && BlazeFight.fireproofCamp(mod.getPlayer()) != _campFireproof) {
            Debug.logInternal("blaze camp: fire resistance " + (_campFireproof ? "gone" : "on") + ", the camp picked again");
            _campSpotFor = null;
            _campSpot = null;
            _pillbox = null;
            _pillboxCornered = false;
            _lure = null;
            _fight.corner(null);
            _fight.pillboxPlanned(false);
            _fight.pillboxLookout(null);
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
        _fight.building(building);
        _fight.pillboxBlocks(building ? _pillbox.blocks() : java.util.Set.of());
        Task fight = _fight.tick(mod);
        if (_fight.mode() != BlazeFightRules.Mode.CAMP) {
            // the fight moves us, the next camp spot is a new question
            _campStep = null;
            _campStepTicks = 0;
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
        if (_fight.mode() == BlazeFightRules.Mode.RUN && !(building && adris.altoclef.util.helpers.Tuning.get("pillbox_heal_build", 1) > 0)) {
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
            if (BlazeFightRules.packNear(BlazeFight.witherSkeletonsNear(mod, Vec3.atCenterOf(_foundBlazeSpawner), BlazeFightRules.PACK_SPAWNER_RADIUS))) {
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
            if (_campStep == null && !camp.closerToCenterThan(mod.getPlayer().position(), campRadius)) {
                setDebugState("Going to blaze spawner (" + (RunZones.INSTANCE.active(_zoneNow) + _deathZones.active(_zoneNow)) + " zones)");
                _toSpawner = true;
                return new GetToBlockTask(camp, false);
            }
            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            // (not out of a standing pillbox for a fire by the spawner: the walk to it was out in every blaze's sight, bent-e
            // run 1; one in the box is still put out)
            if (nearestFire.isPresent() && _pillbox != null && _pillboxCornered
                    && !nearestFire.get().closerToCenterThan(Vec3.atCenterOf(_pillbox.cell()), PILLBOX_RADIUS)) nearestFire = Optional.empty();
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
                setDebugState("Luring the blazes round the corner");
                return _lure;
            }
            setDebugState(_fight.around() > 0 ? "Waiting for the blazes to come in reach" : "Waiting near blaze spawner for blazes to spawn");
            return null;
        }
        // We need to find our fortress.
        setDebugState("Searching for fortress/Traveling around fortress");
        return _searcher;
    }

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
        if (_brewGaveUp) {
            return null;
        }
        // (a stand with our bottles in it from an earlier order is the same brew, on its own clock from now)
        if (BrewFireResistanceTask.pending() && _brewSince < 0) {
            _brewSince = WorldHelper.getTicks();
        }
        if (_brewSince >= 0) {
            if (_brew.isFinished(mod)) {
                _brewSince = -1;
            } else if (WorldHelper.getTicks() - _brewSince > BREW_BUDGET_TICKS) {
                Debug.logMessage("Brewing fire resistance took too long, back to the blazes");
                Debug.logInternal("brew: given up, too long");
                _brewSince = -1;
                _brewGaveUp = true;
                return null;
            } else {
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
        setDebugState("Brewing fire resistance");
        _brewSince = WorldHelper.getTicks();
        Debug.logInternal("brew: begun, rods " + mod.getItemStorage().getItemCount(Items.BLAZE_ROD));
        return _brew;
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
            _campStep = here.offset(s[0], 0, s[1]);
        }
        // in the block is not off the rim yet, the cell's near half can still be inside the edge radius
        boolean arrived = here.equals(_campStep) && !LavaShore.edgeNear(cells, us.getX(), us.getY(), us.getZ());
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
        // (a fresh look at the world: Baritone's bsi is the last path search's copy, and read the room as all solid, so the
        // camp ring had "0 cells to stand on" in a real fortress and the camp stood beside the spawner, 4 Nov 2026)
        Cells cells = CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        var us = mod.getPlayer();
        int sx = _foundBlazeSpawner.getX(), sy = _foundBlazeSpawner.getY(), sz = _foundBlazeSpawner.getZ();
        // (the buddy's fork: a corner first. a camp with a line to the spawner has every blaze of it shooting from where it
        // hovers, and they never come: a blaze that sees us stays put, 4 Nov 2026)
        _fight.corner(null);
        _lure = null;
        _pillbox = null;
        _pillboxCornered = false;
        _fight.pillboxPlanned(false);
        _fight.pillboxLookout(null);
        if (BlazeFight.fireproofCamp(us)) {
            _campSpot = null;
            Debug.logInternal("blaze camp: fire-proof, by the spawner (" + _foundBlazeSpawner.toShortString() + ")");
            return;
        }
        int[][] corner = cornerSpot(mod, cells, sx, sy, sz);
        if (corner != null) {
            _campSpot = new BlockPos(corner[0][0], corner[0][1], corner[0][2]);
            BlockPos peek = new BlockPos(corner[1][0], corner[1][1], corner[1][2]);
            _fight.corner(_campSpot);
            _lure = BlazeLureTask.of(cells, _campSpot, peek);
            Debug.logInternal("blaze camp: a corner at " + _campSpot.toShortString() + " (closed in "
                    + BlazeCover.enclosure(mod.getWorld(), corner[0][0], corner[0][1], corner[0][2], mod.getPlayer()) + " of 24), looking out from " + peek.toShortString()
                    + " (spawner " + _foundBlazeSpawner.toShortString() + ")");
            return;
        }
        // (the buddy's fork: no corner, a pillbox built by the spawner, steering 21; its cell is the fight's corner once it
        // stands, and the looks start from it)
        if (adris.altoclef.util.helpers.Tuning.get("pillbox", 1) > 0) {
            adris.altoclef.util.helpers.BlazePillbox.Plan plan = pillboxPlan(mod, cells, sx, sy, sz);
            if (plan != null) {
                _pillbox = new BlazePillboxTask(plan);
                _fight.pillboxPlanned(true);
                _fight.pillboxLookout(_pillbox.lookout());
                _campSpot = _pillbox.cell();
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

    // a cell by the spawner to walk out from, then BlazeCover.corner with the spawn area's eyes (the ones inside a block left out)
    private static int[][] cornerSpot(AltoClef mod, Cells cells, int sx, int sy, int sz) {
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
            Debug.logInternal("blaze camp: no corner, no cell to stand on by the spawner");
            return null;
        }
        var level = mod.getWorld();
        java.util.List<Vec3> eyes = new java.util.ArrayList<>();
        for (Vec3 eye : BlazeCover.spawnEyes(sx, sy, sz)) {
            if (level.getBlockState(BlockPos.containing(eye)).getCollisionShape(level, BlockPos.containing(eye)).isEmpty()) eyes.add(eye);
        }
        var us = mod.getPlayer();
        int[][] c = BlazeCover.corner(cells, start, sx, sz, BlazeFight.witherSkeletonSpots(mod, Vec3.atCenterOf(new BlockPos(sx, sy, sz)), BlazeCover.CORNER_MAX + BlazeCover.WITHER_CLEAR),
                (x, y, z) -> BlazeCover.hidden(level, eyes, x, y, z, us), (x, y, z) -> BlazeCover.pocket(level, x, y, z, us));
        if (c == null) Debug.logInternal("blaze camp: no corner within " + (int) BlazeCover.CORNER_MAX + " of the spawner (" + eyes.size() + " spawn eyes)");
        return c;
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
        if (me != null && mod.getEntityTracker().getClosestItemDrop(me.position(), Items.BLAZE_ROD)
                .filter(d -> d.distanceTo(me) <= ROD_STEP).isPresent()) return false;
        // (and while the corner holds: the lure fetches the rods near it on a leash; the pickup's walk has none)
        return BlazeFightRules.holdPickup(_fight.mode() == BlazeFightRules.Mode.RUN, _fight.nearestBlaze()) || _fight.cornerHold();
    }

    // a rod this close is picked up at any time, see shouldAvoidPickingUp
    static final double ROD_STEP = 3;
}
