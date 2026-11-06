package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.LadderClutchFallTask;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BucketFillRules;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WaterUpkeep;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.Dimension;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Surfacing;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("UnnecessaryLocalVariable")
public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    // how long after landing we keep trying to get our water back, timed from the first tick on the ground
    // (5 s: the walk back to a cell we drifted off of fits in it, and not much more than that)
    private final TimerGame _cleanupTimer = new TimerGame(5);
    // the cells we really poured into (MLGBucketTask.placedCells), copied every tick so a restarted task loses nothing
    private final List<BlockPos> _ourWater = new ArrayList<>();
    // the ones we've seen hold water on our side. a waterlogged block only fills on the server, so ours lags a round
    // trip behind the click, and "no water there" means nothing until it showed up once
    private final Set<BlockPos> _seenWater = new HashSet<>();
    private int _landedAt;
    private final PickUpOurWaterTask _pickup = new PickUpOurWaterTask();
    private MLGBucketTask _lastMLG = null;
    // the mlg task had the fall last tick. only this blocks defense and food, the cleanup after it doesn't
    private boolean _clutching = false;
    private boolean _cleanupLive = false;
    private int _lastWaterBuckets;
    private boolean _doingChorusFruit = false;
    // the mlg or the ladder clutch had this fall, and both cancelled the path doing it. sticky until we're down, a ladder
    // clutch that saw water coming lets go mid air and the splash is a few ticks later
    private boolean _tookFall = false;
    // a fall we took just ended in water, see surface()
    private boolean _surfacing = false;
    private boolean _wentUnder = false;
    private int _surfaceFrom;
    // 20 blocks under at about 0.135 a tick of jump is 150 ticks, with some slack. full air lasts 15 seconds
    private final TimerGame _surfaceTimer = new TimerGame(10);
    private final SurfaceTask _surface = new SurfaceTask();

    // the water top up (see waterUpkeep). tick stamps, not timers, so the pure WaterUpkeep rules can be tested bare
    private WaterUpkeepTask _upkeep = null;
    private BlockPos _upkeepSource = null;
    private int _upkeepStart;
    private int _upkeepFailedAt = Integer.MIN_VALUE / 2;
    private int _upkeepLookAt = Integer.MIN_VALUE / 2;
    // ponds a go already timed out on, so a pond behind a ravine does not win every minute. forgotten with the chain
    private final Set<BlockPos> _upkeepFailedPonds = new HashSet<>();
    // the level we last ticked in, same check as WorldSurvivalChain.trackArrival
    private Level _lastWorld;
    private Dimension _lastDim;

    // a few ticks for our eyes to go under after the splash, the first tick in the water can still have them above it
    static final int SPLASH_TICKS = 10;

    // a cell this close gets clicked from where we stand
    static final double CLEANUP_REACH = 3;
    // further than this from a cell and we walked off (or got knocked off), the water's not worth a walk back. between the
    // two the pickup walks, inside the cleanup clock
    static final double CLEANUP_WALK = 8;
    // a pour that never showed gets looked for this far around the cell (the server may have put it a block or two over)
    static final int RELOCATE_RADIUS = 2;

    // below this much hp left after the landing, or from this many blocks up, the water is worth spending. in between
    // it just costs the only bucket for a few hp we heal back by walking. 6 is three hearts: the damage number already has
    // armor and feather falling in it, so the margin only covers the landing block being a bit off
    static final double CLUTCH_FLOOR_HP = 6;
    static final double CLUTCH_LONG_FALL = 12;
    // under the food chain (55), over the user task (50)
    static final float UPKEEP_PRIORITY = 54;

    enum CellEnd {
        KEEP, PICKED_UP, GONE, TOO_FAR
    }

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        //_lastMLG = null;
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        levelChanged(mod);
        // dropping into an end portal is a teleport, not a fall. a clutch started on the way in clicks on the other side,
        // and the water lands on the spawn platform with us in it
        boolean portal = inEndPortalColumn(mod);
        boolean falling = isFallingOhNo(mod) && !baritoneHasTheFall(mod) && !portal;
        // a ladder clutch from higher up does not get to keep clicking on the way through either. levelChanged drops it once
        // we are across
        LadderClutchFallTask ladder = portal ? null : falling ? ladderClutch(mod) : ladderTask();
        if (ladder != null) {
            // in the air it's the clutch, and once we're down it stays until the ladder is back in the inventory
            setTask(ladder);
            _lastMLG = null;
            _clutching = false;
            if (falling) {
                tookTheFall();
            }
            return falling ? 100 : 60;
        }
        // no water, hay or vines dropped into a build, and nothing to save in creative. a survival fall that kills us
        // still gets the task, losing the whole inventory is worse than one wrong block the builder breaks again
        boolean hasClutchItem = falling && mod.getClientBaritone().getInventoryBehavior().clutchWanted() && MLGBucketTask.hasClutchItem(mod);
        boolean deadly = falling && !hasClutchItem && !mod.getPlayer().getAbilities().invulnerable && MLGBucketTask.fallIsDeadly(mod);
        // a fall we'd walk away from keeps the bucket. once the clutch has the fall it keeps it, the forecast wobbles as the
        // landing spot is re-cast and flipping out of the task mid air is worse than either answer
        boolean worthIt = hasClutchItem && (_clutching || clutchWorth(mod));
        if (claimsFall(falling, worthIt, deadly)) {
            setTask(new MLGBucketTask());
            _lastMLG = (MLGBucketTask) _mainTask;
            _clutching = true;
            // a new fall restarts the clock, it only starts once we're down again
            _cleanupLive = false;
            tookTheFall();
            takePours();
            return 100;
        }
        _clutching = false;
        // the click on the tick we landed shows up here, one tick after the task's last
        takePours();
        // a vine on the way down is not the bottom yet, and an elytra means whatever fall it was is somebody else's now
        boolean down = MLGBucketTask.landed(mod) && (!mod.getPlayer().onClimbable() || mod.getPlayer().isInWater());
        if (_tookFall && (down || mod.getPlayer().isFallFlying())) {
            _tookFall = false;
            if (mod.getPlayer().isInWater()) {
                // poured or a pool, either way we're in water now. only a pool is deep enough to matter, surface() sorts that
                _surfacing = true;
                _wentUnder = false;
                _surfaceTimer.reset();
                _surfaceFrom = WorldHelper.getTicks();
            }
        }
        _lastMLG = null;
        float surface = surface(mod);
        if (surface != Float.NEGATIVE_INFINITY) {
            return surface;
        }
        float cleanup = cleanUp(mod);
        if (cleanup != Float.NEGATIVE_INFINITY) {
            return cleanup;
        }
        float upkeep = waterUpkeep(mod);
        if (upkeep != Float.NEGATIVE_INFINITY) {
            return upkeep;
        }
        if (mod.getPlayer().hasEffect(MobEffects.LEVITATION) &&
                !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(Items.CHORUS_FRUIT)) &&
                mod.getPlayer().getActiveEffectsMap().get(MobEffects.LEVITATION).getDuration() <= 70 &&
                mod.getItemStorage().hasItemInventoryOnly(Items.CHORUS_FRUIT) &&
                !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            _doingChorusFruit = true;
            mod.getSlotHandler().forceEquipItem(Items.CHORUS_FRUIT);
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else if (_doingChorusFruit) {
            stopChorusFruit(mod);
        }
        return Float.NEGATIVE_INFINITY;
    }

    private void stopChorusFruit(AltoClef mod) {
        _doingChorusFruit = false;
        mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    // a portal or a respawn moved us to another dimension: the fall, the water we poured and the pond we were walking to are
    // all on the other side. only our own state goes, the keys and the path belong to whoever comes next. a respawn in the
    // same dimension keeps the level, so it never gets here
    private void levelChanged(AltoClef mod) {
        Level world = mod.getWorld();
        Dimension dim = WorldHelper.getCurrentDimension();
        if (world == _lastWorld && dim == _lastDim) {
            return;
        }
        boolean first = _lastWorld == null;
        _lastWorld = world;
        _lastDim = dim;
        if (first) {
            return;
        }
        Debug.logInternal("mlg: level changed to " + dim + ", dropping the fall state (clutching " + _clutching + ", took fall " + _tookFall
                + ", surfacing " + _surfacing + ", our water " + _ourWater.size() + ", upkeep " + (_upkeep != null) + ")");
        _tookFall = false;
        _clutching = false;
        _surfacing = false;
        _wentUnder = false;
        _lastMLG = null;
        endCleanup();
        endUpkeep(false);
        // block positions from the other level, they would only black out the same coordinates over here
        _upkeepFailedPonds.clear();
        if (_doingChorusFruit) {
            stopChorusFruit(mod);
        }
        setTask(null);
    }

    private static boolean inEndPortalColumn(AltoClef mod) {
        AABB box = mod.getPlayer().getBoundingBox();
        return inEndPortalColumn(box.minX, box.maxX, box.minZ, box.maxZ, mod.getPlayer().getY(),
                p -> mod.getWorld().getBlockState(p).is(Blocks.END_PORTAL));
    }

    // an end portal block in any column our box covers (four at most), from just under the feet to above the head. the
    // portal is a thin slab and we can be a tick above it or already in it, so the window is generous. pure for the tests
    static boolean inEndPortalColumn(double minX, double maxX, double minZ, double maxZ, double y, Predicate<BlockPos> isEndPortal) {
        int feet = (int) Math.floor(y);
        // a box edge sitting right on a block line does not reach into the next column
        int x1 = (int) Math.floor(maxX - 1.0E-7);
        int z1 = (int) Math.floor(maxZ - 1.0E-7);
        for (int x = (int) Math.floor(minX); x <= x1; x++) {
            for (int z = (int) Math.floor(minZ); z <= z1; z++) {
                for (int by = feet - 1; by <= feet + 3; by++) {
                    if (isEndPortal.test(new BlockPos(x, by, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void tookTheFall() {
        _tookFall = true;
        _surfacing = false; // a new fall, whatever we were swimming up from is over
    }

    private void takePours() {
        if (_lastMLG == null) {
            return;
        }
        for (BlockPos cell : _lastMLG.placedCells()) {
            if (!_ourWater.contains(cell)) {
                _ourWater.add(cell);
            }
        }
    }

    // after landing: pick up every cell we poured into, nearest first. a tick where we can't (no aim yet, no empty bucket,
    // somebody else has the wheel) is just a tick, only the per cell ends in cellEnd or the clock finish it. the clicking
    // lives in a task so it only runs when we win: mob defense (65 and up) and world survival's own pickup (60, ahead of
    // our 59) never get a camera fight out of us
    private float cleanUp(AltoClef mod) {
        if (_ourWater.isEmpty()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        if (!MLGBucketTask.landed(mod)) {
            return Float.NEGATIVE_INFINITY; // baritone took the rest of the fall, or a bounce. the clock waits for the floor
        }
        int buckets = mod.getItemStorage().getItemCount(Items.WATER_BUCKET);
        if (!_cleanupLive) {
            _cleanupLive = true;
            _cleanupTimer.reset();
            _landedAt = WorldHelper.getTicks();
            _lastWaterBuckets = buckets;
        }
        if (_cleanupTimer.elapsed()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        boolean gained = buckets > _lastWaterBuckets;
        _lastWaterBuckets = buckets;
        boolean settled = WorldHelper.getTicks() - _landedAt >= BucketPour.settleTicks(pingMs(mod));
        Vec3 me = mod.getPlayer().position();
        List<BlockPos> found = new ArrayList<>();
        _ourWater.removeIf(cell -> {
            boolean source = BucketPour.isWaterSource(mod.getWorld(), cell);
            if (source) {
                _seenWater.add(cell);
            }
            boolean seen = _seenWater.contains(cell);
            CellEnd end = cellEnd(source, seen, settled, gained, Math.sqrt(cell.distToCenterSqr(me)));
            if (end == CellEnd.GONE && !seen) {
                // the click went through (we have the pour on record) but the cell never held water, so it landed a block or
                // two off. whatever source is near it is ours to fetch, the walk back handles the distance
                BlockPos near = sourceNear(cell, RELOCATE_RADIUS, p -> FluidSources.exposedSource(mod.getWorld().getFluidState(p),
                        mod.getWorld().getFluidState(p.above())) && BucketPour.isWaterSource(mod.getWorld(), p));
                if (near != null && !_ourWater.contains(near) && !found.contains(near)) {
                    found.add(near);
                }
            }
            return end != CellEnd.KEEP;
        });
        for (BlockPos near : found) {
            _ourWater.add(near);
            _seenWater.add(near);
        }
        if (_ourWater.isEmpty()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        // eating holds right click with the food in hand, a bucket swap mid bite wastes the bite and maybe the water
        if (!mod.getItemStorage().hasItem(Items.BUCKET) || mod.getFoodChain().isTryingToEat()) {
            return Float.NEGATIVE_INFINITY;
        }
        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos cell : _ourWater) {
            double d = cell.distToCenterSqr(me);
            // a cell still dry on our side has nothing to aim at, it stays on the list without holding the wheel
            if (d < best && BucketPour.isWaterSource(mod.getWorld(), cell) && !baritonePicksUp(mod, cell)) {
                best = d;
                target = cell;
            }
        }
        if (target == null) {
            return Float.NEGATIVE_INFINITY;
        }
        _pickup.target = target;
        setTask(_pickup);
        return 59;
    }

    // the bucket the clutch spent, filled back up from a pond we can see. a bag with an empty bucket and no water is one lava
    // pit away from a bad time, and the next pond is rarely close when that happens. everything about it is capped: a pond
    // already in sight (no search, no wander), one go of WaterUpkeep.TIMEOUT_TICKS, a rest after any failure. it never hands
    // off to the water collector (that one wanders forever), and the gamer phase says where it stays out of the way
    private float waterUpkeep(AltoClef mod) {
        int now = WorldHelper.getTicks();
        boolean haveWater = mod.getItemStorage().hasItem(Items.WATER_BUCKET);
        if (_upkeep != null) {
            if (haveWater) {
                endUpkeep(false);
                return Float.NEGATIVE_INFINITY;
            }
            boolean stuck = WaterUpkeep.timedOut(now, _upkeepStart) || mod.getBlockTracker().unreachable(_upkeepSource);
            if (stuck || threatened(mod) || !WaterUpkeep.phaseAllows(gamerPhase(mod)) || !pondSource(mod, _upkeepSource)) {
                // only a pond that ate the whole clock is written off, a fight or a phase change says nothing about it
                if (stuck) {
                    if (_upkeepFailedPonds.size() > 32) {
                        _upkeepFailedPonds.clear();
                    }
                    _upkeepFailedPonds.add(_upkeepSource);
                }
                endUpkeep(true);
                return Float.NEGATIVE_INFINITY;
            }
            setTask(_upkeep);
            return UPKEEP_PRIORITY;
        }
        if (WaterUpkeep.coolingDown(now, _upkeepFailedAt) || WaterUpkeep.lookedRecently(now, _upkeepLookAt)) {
            return Float.NEGATIVE_INFINITY;
        }
        LocalPlayer player = mod.getPlayer();
        boolean calm = (player.onGround() || player.isInWater()) && !_clutching && !_surfacing && !_tookFall && !_doingChorusFruit && _ourWater.isEmpty()
                && !mod.getFoodChain().isTryingToEat() && !player.isOnFire() && !player.isInLava();
        if (!WaterUpkeep.wanted(mod.getItemStorage().hasItem(Items.BUCKET), haveWater, WorldHelper.waterEvaporates(mod.getWorld()),
                threatened(mod), calm, fillHasRoom(mod), gamerPhase(mod))) {
            return Float.NEGATIVE_INFINITY;
        }
        _upkeepLookAt = now;
        BlockPos pond = nearestPond(mod);
        if (pond == null) {
            return Float.NEGATIVE_INFINITY;
        }
        _upkeepSource = pond;
        _upkeepStart = now;
        _upkeep = new WaterUpkeepTask(pond);
        setTask(_upkeep);
        return UPKEEP_PRIORITY;
    }

    // a failure earns the rest, getting the water (or never starting) does not
    private void endUpkeep(boolean failed) {
        if (failed) {
            _upkeepFailedAt = WorldHelper.getTicks();
        }
        if (_mainTask == _upkeep) {
            setTask(null);
        }
        _upkeep = null;
        _upkeepSource = null;
    }

    private static GamerPhase gamerPhase(AltoClef mod) {
        return mod.getUserTaskChain().getCurrentTask() instanceof GamerTask gamer ? gamer.phase() : null;
    }

    // a fight, a run, or somebody lining us up. the defense chain would win the wheel anyway, this is so we don't even start
    private static boolean threatened(AltoClef mod) {
        MobDefenseChain defense = mod.getMobDefenseChain();
        return defense.combatMode() != CombatCommit.Mode.NONE || defense.getCombatStance(mod) != CombatRules.Stance.CALM || defense.isShotAt(mod);
    }

    // the full bucket needs a slot of its own when we carry a stack of empties and the bag is full (see BucketFillRules)
    public static boolean fillHasRoom(AltoClef mod) {
        NonNullList<ItemStack> slots = mod.getPlayer().getInventory().getNonEquipmentItems();
        int[] bucketStacks = new int[slots.size()];
        boolean freeSlot = false;
        for (int i = 0; i < bucketStacks.length; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                freeSlot = true;
            } else if (stack.is(Items.BUCKET)) {
                bucketStacks[i] = stack.getCount();
            }
        }
        return !BucketFillRules.needsFreeSlot(bucketStacks, freeSlot);
    }

    private static boolean pondSource(AltoClef mod, BlockPos p) {
        return WaterUpkeep.pondSource(mod, p);
    }

    private BlockPos nearestPond(AltoClef mod) {
        return WaterUpkeep.nearestPond(mod, (int) WaterUpkeep.RANGE, p -> _upkeepFailedPonds.contains(p));
    }

    // the walk and the scoop are the interact task's. this wrapper is only here so the source-only ray (a bucket ray sees
    // through water otherwise) is on while we run and off again when somebody interrupts us, same as the collector does it
    public static final class WaterUpkeepTask extends Task {
        private final BlockPos source;
        private final InteractWithBlockTask scoop;

        public WaterUpkeepTask(BlockPos source) {
            this.source = source;
            this.scoop = InteractWithBlockTask.scoop(new ItemTarget(Items.BUCKET, 1), source, true);
        }

        @Override
        protected void onStart(AltoClef mod) {
            mod.getBehaviour().push();
            mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return scoop;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            mod.getBehaviour().pop();
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false; // the chain decides, by water in the bag, the clock, or a reason to stop
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof WaterUpkeepTask task && task.source.equals(source);
        }

        @Override
        protected String toDebugString() {
            return "Topping up the water bucket at " + source;
        }
    }

    private void endCleanup() {
        _ourWater.clear();
        _seenWater.clear();
        _cleanupLive = false;
        _pickup.target = null;
        if (_mainTask == _pickup) {
            setTask(null);
        }
    }

    // after a fall we took ends in water: a pool hit at full speed goes deep, and we cancelled the path on the way in, so nobody
    // else is coming to swim us up. altoclef's jump key works here for the same reason, baritone has nothing to drive.
    // runs ahead of the cleanup, the pickup can wait until we're breathing
    private float surface(AltoClef mod) {
        if (!_surfacing) {
            return Float.NEGATIVE_INFINITY;
        }
        LocalPlayer player = mod.getPlayer();
        boolean under = player.isUnderWater();
        _wentUnder |= under;
        boolean open = under && Surfacing.openToAir(mod.getWorld(), BlockPos.containing(player.getEyePosition()));
        boolean early = WorldHelper.getTicks() - _surfaceFrom < SPLASH_TICKS;
        Surface step = surfaceStep(_surfaceTimer.elapsed(), early, player.isInWater(), under, _wentUnder, open);
        if (step == Surface.GO) {
            setTask(_surface);
            return 59;
        }
        if (step == Surface.DONE) {
            endSurfacing();
        } else if (_mainTask == _surface) {
            setTask(null);
        }
        return Float.NEGATIVE_INFINITY;
    }

    enum Surface {
        GO, WAIT, DONE
    }

    // one tick of it. under with a straight shot up: go. under a ceiling: holding jump just pins us there, so hand the
    // wheel back for now (Movement.update goes up the moment there's a way) and take it again if we drift out from under
    // it. eyes out after being under is the job done, eyes never under is a shallow landing once the splash is over.
    // pure for the tests
    static Surface surfaceStep(boolean timedOut, boolean splashing, boolean inWater, boolean underWater, boolean wentUnder, boolean openAbove) {
        if (timedOut || !inWater) {
            return Surface.DONE;
        }
        if (underWater) {
            return openAbove ? Surface.GO : Surface.WAIT;
        }
        return splashing && !wentUnder ? Surface.WAIT : Surface.DONE;
    }

    private void endSurfacing() {
        _surfacing = false;
        _wentUnder = false;
        if (_mainTask == _surface) {
            setTask(null);
        }
    }

    // same as PlaceObsidianBucketTask.pingMs
    private static int pingMs(AltoClef mod) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return 0;
        }
        PlayerInfo info = connection.getPlayerInfo(mod.getPlayer().getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // what happens to one of our cells this tick. the source going away with a bucket gained is the pickup, going away
    // without one is somebody else (or it flowed off a ledge), either way there's nothing left to fetch. a cell that never
    // held water on our side gets a round trip or so (settled) to show up, then it's a click the server turned down.
    // pure for the tests
    static CellEnd cellEnd(boolean stillSource, boolean seenSource, boolean settled, boolean gainedBucket, double distance) {
        if (!stillSource && (seenSource || settled)) {
            return seenSource && gainedBucket ? CellEnd.PICKED_UP : CellEnd.GONE;
        }
        return distance > CLEANUP_WALK ? CellEnd.TOO_FAR : CellEnd.KEEP;
    }

    // the source closest to a cell, within r of it on every axis. null when there is none. pure for the tests
    static BlockPos sourceNear(BlockPos cell, int r, Predicate<BlockPos> isSource) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = cell.offset(dx, dy, dz);
                    double d = p.distSqr(cell);
                    if (d < bestDist && isSource.test(p)) {
                        best = p;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    // an mlg with nothing to place is a task that stares at the ground saying "no clutch item" while it cancels the path
    // at priority 100, so all it buys us is a replan. no bucket and no ladder (that case is handled before this) means
    // there's nothing to take over for. except a fall that would kill us: the task still steers toward lava or water then,
    // and that's worth the interrupt. planned falls never get here, FallCover hands them to baritone first.
    // pure so it's testable without a game, same idea as FallCover
    static boolean claimsFall(boolean falling, boolean hasClutchItem, boolean deadly) {
        return falling && (hasClutchItem || deadly);
    }

    private static boolean clutchWorth(AltoClef mod) {
        if (mod.getPlayer().getAbilities().invulnerable) {
            return false;
        }
        // no landing to read (void, too far down for the cast) is not a fall to gamble on
        return MLGBucketTask.forecast(mod)
                .map(f -> clutchWorthIt(mod.getPlayer().getHealth(), f.damage(), f.blocks(), f.hazard()))
                .orElse(true);
    }

    // is the water worth spending on this fall. a hazard landing (lava) always is, a long fall is, and so is one that would
    // leave us on the edge. pure for the tests
    static boolean clutchWorthIt(double health, double damage, double blocks, boolean hazard) {
        return hazard || blocks >= CLUTCH_LONG_FALL || health - damage <= CLUTCH_FLOOR_HP;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket Fall Chain";
    }

    @Override
    public String getHudName() {
        return "Breaking a fall";
    }

    @Override
    public boolean isActive() {
        // We're always checking for mlg.
        return true;
    }

    // false only while the clutch itself runs. the pickup after it doesn't count, a zombie at the landing is a bigger
    // problem than a bucket of water
    public boolean doneMLG() {
        return !_clutching && ladderTask() == null;
    }

    public boolean isChorusFruiting() {
        return _doingChorusFruit;
    }

    // the ladder clutch we're in the middle of, unfinished, if any
    private LadderClutchFallTask ladderTask() {
        return _mainTask instanceof LadderClutchFallTask task && !task.finished() ? task : null;
    }

    // what to do about an unplanned fall with a ladder or vine: the one already going, or a new one if there's no water
    // to do it with (water doesn't need a wall, so it wins) and something to hang it on in time. null means the plain mlg
    private LadderClutchFallTask ladderClutch(AltoClef mod) {
        LadderClutchFallTask going = ladderTask();
        if (going != null) {
            return going;
        }
        boolean water = !WorldHelper.waterEvaporates(mod.getWorld()) && mod.getItemStorage().hasItem(Items.WATER_BUCKET);
        return water ? null : LadderClutchFallTask.probe(mod);
    }

    // the fall movement baritone is running right now, if it is one
    private MovementFall currentFall(AltoClef mod) {
        PathingBehavior pathing = mod.getClientBaritone().getPathingBehavior();
        PathExecutor executor = pathing.getCurrent();
        if (executor == null || !pathing.isPathing()) {
            return null; // paused or no path, nobody is driving
        }
        List<IMovement> movements = executor.getPath().movements();
        int at = executor.getPosition();
        return at >= 0 && at < movements.size() && movements.get(at) instanceof MovementFall fall ? fall : null;
    }

    // a fall baritone planned (a safe one, a clutch, or a bucket it has on the hotbar) is baritone's. our mlg task cancels
    // the path and fights it for the rotation, which turns a free fall into a replan. isFallingOhNo stays as is, the
    // other chains still want to know we're in the air (no eating mid fall)
    private boolean baritoneHasTheFall(AltoClef mod) {
        MovementFall fall = currentFall(mod);
        return fall != null && fall.ownsFall(mod.getPlayer().position(), mod.getPlayer().getDeltaMovement());
    }

    // the water baritone's fall movement is about to pick up itself. our click on top of its click places it again
    private boolean baritonePicksUp(AltoClef mod, BlockPos placed) {
        MovementFall fall = currentFall(mod);
        return fall != null && fall.picksUpWaterAt(placed);
    }

    // the right click that picks our water back up. the chain picks the cell, this just aims and clicks, and only on the
    // ticks the chain wins (that's the whole reason it's a task and not more getPriority)
    private static final class PickUpOurWaterTask extends Task {
        private final TimerGame _repeat = new TimerGame(0.25);
        private BlockPos target;

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (target == null) {
                return null;
            }
            // the ray has to see source water for isLookingAt to mean anything
            mod.getBehaviour().push();
            mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
            Optional<Rotation> reach = LookHelper.getReach(target, Direction.UP);
            if (reach.isEmpty()) {
                // drifted off the pour: walk back. the chain only keeps cells within CLEANUP_WALK and its clock bounds the
                // trip, so this can't turn into the old wander to some other pond
                mod.getBehaviour().pop();
                return new GetWithinRangeOfBlockTask(target, 2);
            }
            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
            if (mod.getClientBaritone().getPlayerContext().isLookingAt(target) && mod.getSlotHandler().forceEquipItem(Items.BUCKET) && _repeat.elapsed()) {
                _repeat.reset();
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            }
            mod.getBehaviour().pop();
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false; // the chain decides, by not asking anymore
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PickUpOurWaterTask;
        }

        @Override
        protected String toDebugString() {
            return "Picking up our water at " + target;
        }
    }

    // jump with the camera level. the mlg left us staring straight down, and a swimmer looking down gets pulled down
    private static final class SurfaceTask extends Task {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            mod.getInputControls().hold(Input.JUMP);
            mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(mod.getPlayer().getYRot(), 0), true);
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            // the jump is ours (held from onTick), letGo lifts it after this
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false; // the chain decides, same as the pickup
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof SurfaceTask;
        }

        @Override
        protected String toDebugString() {
            return "Swimming up for air";
        }
    }

    public boolean isFallingOhNo(AltoClef mod) {
        if (!Baritone.settings().altoAutoMLGBucket.value) {
            return false;
        }
        if (mod.getPlayer().isSwimming() || mod.getPlayer().isInWater() || mod.getPlayer().onGround() || mod.getPlayer().onClimbable()) {
            // We're grounded.
            return false;
        }
        double ySpeed = mod.getPlayer().getDeltaMovement().y;
        return ySpeed < -0.7;
    }
}
