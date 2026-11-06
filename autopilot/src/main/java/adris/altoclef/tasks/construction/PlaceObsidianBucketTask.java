package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectBucketLiquidTask;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.gamer.portal.LavaHunt;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.GameClock;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PhantomScoops;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.Dimension;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Places obsidian at a position using buckets and a cast.
 */
public class PlaceObsidianBucketTask extends Task {

    public static final Vec3i[] CAST_FRAME = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, -1),
            new Vec3i(0, -1, 1),
            new Vec3i(-1, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(0, 0, -1),
            new Vec3i(0, 0, 1),
            new Vec3i(-1, 0, 0),
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0)
    };
    // a standing player's eyes over their feet
    private static final double EYE_HEIGHT = 1.62;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final BlockPos _pos;
    // cells cast before this one, whose water may still be sitting on top of them
    private final List<BlockPos> _earlierCells;

    private BlockPos _currentCastTarget;
    private BlockPos _currentDestroyTarget;
    // said once, and the ban handed out once
    private boolean _ruinSaid;
    // the lava click child is out there, so a pour we call off has a right click to let go of
    private boolean _pouring;
    // why the pour is on hold, so the log gets one line per reason and not one per tick
    private BucketPour.Verdict _heldFor;
    // the tick the lava cell started looking clear, MIN_VALUE while it doesn't
    private int _clearSince = Integer.MIN_VALUE;
    // we have SNEAK and MOVE_BACK down for the back-up next to the ledge, and whether this tick asked for them
    private boolean _backingUp;
    private boolean _backUpThisTick;
    // where lava of ours must never sit, and where a block we can't get through ruins the whole spot (see CastLava)
    private final List<BlockPos> _footprint;
    private final List<BlockPos> _ruinCells;
    // ticks in a row we've stood still and aimed, and whether this tick counted. a tick that didn't starts it over
    private int _stillTicks;
    private boolean _stillCountedThisTick;
    // the lava click child we hand out, kept so its click status is the one it really had last tick
    private InteractWithBlockTask _lavaClick;
    // the tick the lava click went out, MIN_VALUE when there's none in flight
    private int _lavaClickAt = Integer.MIN_VALUE;
    // the stray cleanup wants this tick, whatever it returned
    private boolean _strayHolds;
    // cleaning up our loose lava: since when, and how many sources were left the last time it got better
    private int _cleanSince = Integer.MIN_VALUE;
    private int _cleanSources = Integer.MAX_VALUE;
    // lava buckets in the bag the last tick we handed out the click, and the tick the click went out while we wait to
    // see one leave (MIN_VALUE when none is pending)
    private int _lavaBefore;
    private int _pendingAt = Integer.MIN_VALUE;
    // pours that really spent a bucket and left the cell dry, and clicks that spent nothing. a lava cell wipes the duds, the failed pours are a cap per cell
    private int _failedPours;
    private int _dudClicks;
    // PhantomScoops' count when we last looked. a phantom bucket poured nothing, so the misses since are not the cell's
    private int _phantomSeen = PhantomScoops.epoch();
    // the ban on this cell is out, the parent moves the frame
    private boolean _dropped;
    private Task _fetch;
    // the lava fetch stays on the surface band (LavaHunt) until it has been looking this long. lava 60 blocks down is a mob cave
    // and the bot walked into one for a bucket. when the band really has none, any depth is the last resort
    private static final int BAND_ONLY_TICKS = 20 * 60;
    private int _bandSince = Integer.MIN_VALUE;
    private int _bandPy;

    public PlaceObsidianBucketTask(BlockPos pos) {
        this(pos, List.of());
    }

    public PlaceObsidianBucketTask(BlockPos pos, List<BlockPos> earlierCells) {
        this(pos, earlierCells, List.of(), List.of());
    }

    // strayCells: cells around this cast that no lava of ours belongs in. ruinCells: cells that have to stay breakable
    // and not obsidian. only a parent that picked the spot with no lava or junk in it gets to hand those in
    public PlaceObsidianBucketTask(BlockPos pos, List<BlockPos> earlierCells, List<BlockPos> strayCells, List<BlockPos> ruinCells) {
        _pos = pos;
        _earlierCells = earlierCells;
        _footprint = CastLava.footprint(pos, strayCells);
        // obsidian in a cell the parent calls frame is the frame, the mold's ledge can be one of those
        _ruinCells = CastLava.ruinCells(pos, ruinCells);
        _ruinCells.removeAll(earlierCells);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Don't break cast
        mod.getBehaviour().avoidBlockBreaking(block -> {
            for (Vec3i castPosRelativeToLava : PlaceObsidianBucketTask.CAST_FRAME) {
                BlockPos castPos = _pos.offset(castPosRelativeToLava);
                if (block.equals(castPos)) {
                    return true;
                }
            }
            return false;
        });
        // Don't place blocks inside our cast water/lava
        mod.getBehaviour().avoidBlockPlacing(block -> {
            BlockPos waterTarget = _pos.above();
            return block.equals(_pos) || block.equals(waterTarget);
        });

        _progressChecker.reset();
        // the cast parent hands the same task back after it went off to do something else, so this is a fresh start for
        // everything a fresh task would have. the lava in flight keeps its clock, that one runs out on its own
        _clearSince = Integer.MIN_VALUE;
        _stillTicks = 0;
        _heldFor = null;
        _pouring = false;
        _lavaClick = null;
        _currentCastTarget = null;
        _currentDestroyTarget = null;
        _cleanSince = Integer.MIN_VALUE;
        _cleanSources = Integer.MAX_VALUE;
    }

    /**
     * This method is called periodically to perform a specific task.
     * It handles the logic for casting a spell using lava and water buckets.
     *
     * @param mod The mod instance
     * @return The next task to be executed
     */
    @Override
    protected Task onTick(AltoClef mod) {
        _bandPy = mod.getPlayer().blockPosition().getY();
        // a bucket in hand ends the hunt, the next fetch gets the band again
        if (mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) _bandSince = Integer.MIN_VALUE;
        _backUpThisTick = false;
        _stillCountedThisTick = false;
        Task next = castTick(mod);
        // the back-up below only let go in its own else, so standing right on the ledge (or any earlier return) kept the
        // keys down. any tick that didn't back up lets go of what we pressed
        if (_backingUp && !_backUpThisTick) {
            stopBackingUp(mod);
        }
        // backing up, walking, any hold but the ping wait: the still count starts over
        if (!_stillCountedThisTick) {
            _stillTicks = 0;
        }
        return next;
    }

    // the collect is told to leave these alone, or it scoops back the pour we just made
    public boolean scoopOff(BlockPos cell) {
        return CastLava.scoopOff(cell, _pos, _footprint, _earlierCells);
    }

    // the cast's own lava fetch, same rule as the parent's: not the cells we are casting in
    private Task ownFetch() {
        if (_bandSince == Integer.MIN_VALUE) _bandSince = WorldHelper.getTicks();
        if (_fetch == null) {
            _fetch = new CollectBucketLiquidTask.CollectLavaBucketTask(1).leaveAlone(p -> scoopOff(p) || offBand(p));
        }
        return _fetch;
    }

    // the parent's own early fetch starts the clock too, it is the same hunt
    public void startBandClock(AltoClef mod) {
        _bandPy = mod.getPlayer().blockPosition().getY();
        if (_bandSince == Integer.MIN_VALUE) _bandSince = WorldHelper.getTicks();
    }

    // overworld only, the nether has lava everywhere and no band to speak of
    public boolean offBand(BlockPos p) {
        return _bandSince != Integer.MIN_VALUE && GameClock.since(_bandSince, WorldHelper.getTicks()) < BAND_ONLY_TICKS
                && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && !LavaHunt.inBand(p.getY(), _bandPy);
    }

    // our lava just went out, or some of it is loose around the cast. either way it's ours to sort out, and a parent
    // that goes off for a fresh bucket now walks away from it
    public boolean ownsTheLava(AltoClef mod) {
        // the parent asks before we tick, so last tick's click hasn't been read yet
        watchLavaClick(mod);
        if (_pendingAt != Integer.MIN_VALUE || CastLava.inFlight(_lavaClickAt, WorldHelper.getTicks())) {
            return true;
        }
        if (!castFrameBuilt(mod)) {
            return false;
        }
        CastLava.Cells w = cells(mod);
        return CastLava.standBlocked(w, _pos) || !CastLava.sources(w, _footprint).isEmpty();
    }

    private void stopBackingUp(AltoClef mod) {
        _backingUp = false;
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_BACK);
    }

    private Task castTick(AltoClef mod) {
        watchLavaCell(mod);
        watchLavaClick(mod);
        // Reset progress if pathing
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }

        // Clear leftover water
        if (isObsidian(mod) && isWater(mod, _pos.above())) {
            return new ClearLiquidTask(_pos.above());
        }

        // a block in the way that no tool we hold gets through in time (obsidian, under anything short of a diamond pick)
        // ruins this origin before we have fetched a single bucket for it. the parent moves the frame when it sees the ban
        if (ruined(mod)) {
            setDebugState("Cast ruined, waiting for the frame to move");
            return null;
        }
        if (_dropped) {
            setDebugState("Cast spoiled, waiting for the frame to move");
            return null;
        }
        // same idea one ring out: junk on the ledge or around the frame. walking at it is 25 s of "couldn't reach" first
        if (CastLava.firstRuin(_ruinCells, c -> mod.getWorld().getBlockState(c).is(Blocks.OBSIDIAN),
                c -> WorldHelper.isSolid(mod, c) && WorldHelper.tooSlowToBreak(mod, c)) != null) {
            return dropOrigin(mod, "obsidian or a slow block round the cast");
        }

        // our own lava loose around the cast comes before everything, the bucket errands below would walk right past it
        if (castFrameBuilt(mod)) {
            _strayHolds = false;
            Task clean = strayLava(mod);
            if (_strayHolds) {
                return clean;
            }
        }
        // a click went out and the bag hasn't said whether the bucket left it. a pour we can't confirm isn't one
        if (_pendingAt != Integer.MIN_VALUE) {
            setDebugState("Waiting to see the lava bucket leave");
            _progressChecker.reset();
            return null;
        }
        // the lava click just went out and nothing shows yet. the bag says no lava bucket, which is the pour working, not
        // a reason to go to the lake for another
        if (CastLava.inFlight(_lavaClickAt, WorldHelper.getTicks())) {
            if (lavaCell(mod) != CastLava.Mat.LAVA) {
                setDebugState("Waiting for the lava to land");
                _progressChecker.reset();
                return null;
            }
            _lavaClickAt = Integer.MIN_VALUE;
            _dudClicks = 0;
        } else if (_lavaClickAt != Integer.MIN_VALUE) {
            // the window is over. a bucket went and the cell is dry: the lava landed somewhere that isn't ours to know
            _lavaClickAt = Integer.MIN_VALUE;
            if (CastLava.pourFailed(lavaCell(mod), isObsidian(mod))) {
                _failedPours++;
            } else {
                _dudClicks = 0;
            }
        }
        if (_phantomSeen != PhantomScoops.epoch()) {
            _phantomSeen = PhantomScoops.epoch();
            _failedPours = 0;
            _dudClicks = 0;
        }
        if (CastLava.spoiled(_failedPours, _dudClicks)) {
            return dropOrigin(mod, "the lava never lands in the cell");
        }

        // Make sure we have a water bucket
        // the cast in flight (lava down, water on top, bucket empty) is supposed to have no water bucket, the obsidian
        // is a tick away. asking for one here sent us wandering for a lake with the water sitting right above us
        boolean castInFlight = isWater(mod, _pos.above()) && mod.getWorld().getBlockState(_pos).getBlock() == Blocks.LAVA;
        if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET) && !castInFlight) {
            // the water we left on the cells before this one is a bucket we already own
            BlockPos leftover = leftoverWater(mod);
            if (leftover != null) {
                _progressChecker.reset();
                return new ClearLiquidTask(leftover);
            }
            _progressChecker.reset();
            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
        }

        // Make sure we have a lava bucket
        if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
            // The only excuse is that we have lava at our position. the world, the tracker's cache can be stale
            if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {
                _progressChecker.reset();
                return ownFetch();
            }
        }

        // Check progress
        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            _progressChecker.reset();
            return new TimeoutWanderTask(5);
        }

        // Build cast frame if not already built
        if (_currentCastTarget != null) {
            if (WorldHelper.isSolid(mod, _currentCastTarget)) {
                _currentCastTarget = null;
            } else {
                return new PlaceStructureBlockTask(_currentCastTarget);
            }
        }

        // Destroy block if needed
        if (_currentDestroyTarget != null) {
            if (!needsClearing(mod, _currentDestroyTarget)) {
                _currentDestroyTarget = null;
            } else {
                return new DestroyBlockTask(_currentDestroyTarget);
            }
        }

        // Build the cast frame if not already built
        if (_currentCastTarget != null && WorldHelper.isSolid(mod, _currentCastTarget)) {
            // Current cast frame already built.
            _currentCastTarget = null;
        }
        for (Vec3i castPosRelative : CAST_FRAME) {
            BlockPos castPos = _pos.offset(castPosRelative);
            if (!WorldHelper.isSolid(mod, castPos)) {
                _currentCastTarget = castPos;
                return null;
            }
        }

        // Place lava
        if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {
            // Don't place lava at our position!
            // Would lead to an embarrassing death.
            BlockPos targetPos = _pos.offset(-1, 1, 0);
            if (!mod.getPlayer().blockPosition().equals(targetPos) && mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                if (!mod.getClientBaritone().getPathingBehavior().isPathing()
                        && targetPos.closerThan(mod.getPlayer().blockPosition(), 2)) {
                    mod.getInputControls().hold(Input.SNEAK);
                    mod.getInputControls().hold(Input.MOVE_BACK);
                    _backingUp = true;
                    _backUpThisTick = true;
                }
                return new GetToBlockTask(targetPos, false);
            }
            if (needsClearing(mod, _pos)) {
                _currentDestroyTarget = _pos;
                return null;
            }
            // Clear the upper two as well, to make placing more reliable.
            if (needsClearing(mod, _pos.above())) {
                _currentDestroyTarget = _pos.above();
                return null;
            }
            if (WorldHelper.isSolid(mod, _pos.above(2))) {
                _currentDestroyTarget = _pos.above(2);
                return null;
            }
            // asked every tick the click child runs too, this onTick comes first. returning null is what calls it off
            BucketPour.Verdict pour = pourVerdict(mod);
            // the ping wait and the stand still wait run side by side, anything else starts the stillness over
            boolean still = (pour == BucketPour.Verdict.POUR || pour == BucketPour.Verdict.SETTLING) && countStill(mod);
            if (pour != BucketPour.Verdict.POUR) {
                return holdThePour(mod, pour);
            }
            if (!still) {
                letGoOfTheClick(mod);
                setDebugState("Holding the lava pour: settling down");
                return null;
            }
            // never click empty handed, the parent fetches the bucket first
            if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                letGoOfTheClick(mod);
                _progressChecker.reset();
                return ownFetch();
            }
            _lavaBefore = mod.getItemStorage().getItemCount(Items.LAVA_BUCKET);
            _heldFor = null;
            _pouring = true;
            if (_lavaClick == null) {
                _lavaClick = new InteractWithBlockTask(new ItemTarget(Items.LAVA_BUCKET, 1), Direction.WEST, _pos.offset(1, 0, 0), false);
            }
            return _lavaClick;
        }
        _pouring = false;
        _lavaClick = null;
        // a flow in the cell isn't our source, water on it makes cobble at best. the stray cleanup up top already waits
        // these out, this is belt and braces
        if (lavaCell(mod) != CastLava.Mat.LAVA) {
            setDebugState("Waiting for the lava in the cell to settle");
            _progressChecker.reset();
            return null;
        }
        // Lava placed, Now, place water.
        BlockPos waterCheck = _pos.above();
        if (mod.getWorld().getBlockState(waterCheck).getBlock() != Blocks.WATER) {
            // Get to position to avoid weird stuck scenario
            BlockPos targetPos = _pos.offset(-1, 1, 0);
            if (!mod.getPlayer().blockPosition().equals(targetPos) && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                return new GetToBlockTask(targetPos, false);
            }
            if (needsClearing(mod, waterCheck)) {
                _currentDestroyTarget = waterCheck;
                return null;
            }
            if (WorldHelper.isSolid(mod, waterCheck.above())) {
                _currentDestroyTarget = waterCheck.above();
                return null;
            }
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1), Direction.WEST, _pos.offset(1, 1, 0), true);
        }
        return null;
    }

    // the cells the cast clears before it can put anything down: the lava cell, the water cell and the air over that. the
    // same three cells the destroy steps below go for, so a cell that only has water or lava in it is not a question
    private boolean ruined(AltoClef mod) {
        BlockPos[] cells = {_pos, _pos.above(), _pos.above(2)};
        String[] roles = {"lava", "water", "air over the water"};
        for (int i = 0; i < cells.length; i++) {
            if (!WorldHelper.isSolid(mod, cells[i]) || !WorldHelper.tooSlowToBreak(mod, cells[i])) {
                continue;
            }
            // the lava cell turning to obsidian is the cast working, the tracker just hasn't caught up with it yet
            if (i == 0 && mod.getWorld().getBlockState(cells[i]).getBlock() == Blocks.OBSIDIAN) {
                continue;
            }
            if (!_ruinSaid) {
                _ruinSaid = true;
                Debug.logMessage("cast ruined: " + BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(cells[i]).getBlock()).getPath()
                        + " at " + cells[i].toShortString() + " where the " + roles[i] + " cell goes, moving the frame");
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                // the parent drops its origin when the frame cell is unreachable, and the pool task gives up on it the same way
                mod.getBlockTracker().requestBlockUnreachable(_pos, 0, "something in the cast takes too long to break");
            }
            return true;
        }
        return false;
    }

    // the lava cell and the one over it are on the pour's ray from the ledge, so "not a full block" isn't clear enough for
    // those two: grass, a slab or a snow layer in the lava cell stops the ray on its top, and the lava goes on top of it,
    // which is the cell beside our feet. the rest only have to be out of the way
    private boolean needsClearing(AltoClef mod, BlockPos cell) {
        if (cell.equals(_pos) || cell.equals(_pos.above())) {
            return !BucketPour.clearForPour(mod.getWorld(), cell);
        }
        return WorldHelper.isSolid(mod, cell);
    }

    // every tick, not just at pour time: a break the server turned down and we broke again while walking back would
    // otherwise still count from the first time
    private void watchLavaCell(AltoClef mod) {
        if (!BucketPour.clearForPour(mod.getWorld(), _pos)) {
            _clearSince = Integer.MIN_VALUE;
        } else if (_clearSince == Integer.MIN_VALUE) {
            _clearSince = WorldHelper.getTicks();
        }
    }

    private BucketPour.Verdict pourVerdict(AltoClef mod) {
        boolean clear = BucketPour.clearForPour(mod.getWorld(), _pos);
        int clearTicks = clear && _clearSince != Integer.MIN_VALUE ? WorldHelper.getTicks() - _clearSince : 0;
        boolean fallingIn = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos) || WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        return BucketPour.check(clear, clearTicks, BucketPour.settleTicks(pingMs(mod)), fallingIn, mod.getPlayer().onGround(),
                mod.getPlayer().getBoundingBox(), _pos);
    }

    // our round trip from the tab list, 0 when there's nobody to ask (singleplayer has no lag to wait out anyway)
    private static int pingMs(AltoClef mod) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return 0;
        }
        PlayerInfo info = connection.getPlayerInfo(mod.getPlayer().getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // not now. the click child goes (we return null), and so does a right click it may have had down. standing here doing
    // nothing for long enough is the progress checker's to end, like any other stall
    private Task holdThePour(AltoClef mod, BucketPour.Verdict why) {
        letGoOfTheClick(mod);
        if (why != _heldFor) {
            _heldFor = why;
            Debug.logInternal("cast: holding the lava pour into " + _pos.toShortString() + ", " + why);
        }
        setDebugState("Holding the lava pour: " + why);
        if (why == BucketPour.Verdict.IN_THE_COLUMN) {
            // on the ledge cell but hanging off its east edge, so nothing above walks us anywhere and the progress checker
            // would ban the whole frame for it. face the frame and back off it, crouched so the west edge can't drop us
            LookHelper.lookAt(mod, Vec3.atCenterOf(_pos.offset(1, 0, 0)));
            mod.getInputControls().hold(Input.SNEAK);
            mod.getInputControls().hold(Input.MOVE_BACK);
            _backingUp = true;
            _backUpThisTick = true;
        }
        return null;
    }

    private void letGoOfTheClick(AltoClef mod) {
        if (_pouring) {
            _pouring = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }
        _lavaClick = null;
    }

    // the child ticked after us last tick, so what it says now is what it did then
    private void watchLavaClick(AltoClef mod) {
        if (_lavaClick != null && _lavaClick.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED) {
            _pendingAt = WorldHelper.getTicks();
            _lavaClick = null;
            _pouring = false;
        }
        if (_pendingAt == Integer.MIN_VALUE) {
            return;
        }
        // only a bucket that left the bag starts the in flight clock, a click with a pickaxe in hand is not a pour
        if (CastLava.spent(_lavaBefore, mod.getItemStorage().getItemCount(Items.LAVA_BUCKET))) {
            _lavaClickAt = _pendingAt;
            _pendingAt = Integer.MIN_VALUE;
        } else if (WorldHelper.getTicks() - _pendingAt > CastLava.PENDING_TICKS) {
            _pendingAt = Integer.MIN_VALUE;
            _dudClicks++;
        }
    }

    // one more tick of standing still with the crosshair on the pour face, or the count goes back to 0. aims too: the
    // click child only turns once it's handed out, by then the count would never have started. the rotation that counts
    // is the one the server last got (what pourAimOff casts along), not the camera
    private boolean countStill(AltoClef mod) {
        _stillCountedThisTick = true;
        // a full bucket looks straight through liquids, and so should the aim. a scoop may have left this on SOURCE_ONLY
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
        Optional<Rotation> aim = LookHelper.getReach(_pos.offset(1, 0, 0), Direction.WEST);
        float yawOff = 180f;
        float pitchOff = 180f;
        if (aim.isPresent()) {
            LookHelper.lookAt(mod, aim.get());
            Rotation sent = mod.getClientBaritone().getPlayerContext().playerRotations();
            yawOff = CastLava.yawError(sent.getYaw(), aim.get().getYaw());
            pitchOff = Math.abs(sent.getPitch() - aim.get().getPitch());
        }
        Vec3 v = mod.getPlayer().getDeltaMovement();
        boolean still = CastLava.still(mod.getPlayer().onGround(), mod.getPlayer().getBoundingBox().intersects(BucketPour.column(_pos)),
                _backingUp, mod.getPlayer().getPose() == Pose.STANDING, v.x * v.x + v.z * v.z, yawOff, pitchOff);
        _stillTicks = CastLava.stillCount(_stillTicks, still);
        return CastLava.stillEnough(_stillTicks);
    }

    // same test as the cast frame loop: until every wall is up there's nowhere for lava to be "ours" yet, and a mold next to a
    // pool has pool lava right where a wall is about to go
    private boolean castFrameBuilt(AltoClef mod) {
        for (Vec3i c : CAST_FRAME) {
            if (!WorldHelper.isSolid(mod, _pos.offset(c))) {
                return false;
            }
        }
        return true;
    }

    private CastLava.Mat lavaCell(AltoClef mod) {
        return cells(mod).at(_pos);
    }

    // the live world as CastLava cells. unloaded reads as rock, so nothing stands there and nothing in it is a stray
    private static CastLava.Cells cells(AltoClef mod) {
        ClientLevel level = mod.getWorld();
        return pos -> {
            if (!level.isLoaded(pos)) {
                return CastLava.Mat.SOLID;
            }
            BlockState s = level.getBlockState(pos);
            if (s.is(Blocks.LAVA)) {
                return s.getFluidState().isSource() ? CastLava.Mat.LAVA : CastLava.Mat.LAVA_FLOW;
            }
            if (!s.getFluidState().isEmpty()) {
                return CastLava.Mat.WATER;
            }
            return s.getCollisionShape(level, pos).isEmpty() ? CastLava.Mat.OPEN : CastLava.Mat.SOLID;
        };
    }

    // lava of ours where it doesn't belong. scoop it from somewhere safe, wait out a flow, or give the spot up. sets
    // _strayHolds when it wants the tick
    private Task strayLava(AltoClef mod) {
        CastLava.Cells w = cells(mod);
        List<BlockPos> sources = CastLava.sources(w, _footprint);
        boolean blocked = CastLava.standBlocked(w, _pos);
        int now = WorldHelper.getTicks();
        // the clock restarts whenever a scoop worked, a mess of three sources gets 8 s per source and not 8 s total
        if (_cleanSince != Integer.MIN_VALUE && sources.size() < _cleanSources) {
            _cleanSince = now;
        }
        _cleanSources = sources.size();
        boolean outOfTime = _cleanSince != Integer.MIN_VALUE && now - _cleanSince > CastLava.CLEAN_TICKS;
        boolean canPourNext = CastLava.canPourNext(mod.getItemStorage().hasItem(Items.LAVA_BUCKET),
                mod.getItemStorage().hasItem(Items.WATER_BUCKET), w.at(_pos));
        CastLava.Action act = CastLava.decide(!sources.isEmpty(), blocked, mod.getItemStorage().getItemCount(Items.BUCKET) > 0,
                canPourNext, outOfTime);
        if (act == CastLava.Action.NONE || act == CastLava.Action.POUR_FIRST) {
            _cleanSince = Integer.MIN_VALUE;
            return null;
        }
        _strayHolds = true;
        // whatever landed, it's the cleanup's now, not the in flight wait's. a pour that spilled and got scooped back is still
        // a pour that missed, or click, spill, scoop would go round forever
        if (_lavaClickAt != Integer.MIN_VALUE && CastLava.pourFailed(lavaCell(mod), isObsidian(mod))) {
            _failedPours++;
        }
        _lavaClickAt = Integer.MIN_VALUE;
        letGoOfTheClick(mod);
        if (act == CastLava.Action.DROP) {
            return dropOrigin(mod, "our lava got loose round the cast");
        }
        if (_cleanSince == Integer.MIN_VALUE) {
            _cleanSince = now;
        }
        // standing about next to our own lava is the job right now, not a stall
        _progressChecker.reset();
        if (act == CastLava.Action.WAIT_DRAIN) {
            setDebugState("Waiting for the loose lava to drain");
            return backOff(mod, w);
        }
        return scoop(mod, w, sources);
    }

    // nearest source to us, from where we are if that's safe and the ray gets there, else from the nearest safe cell that
    // does. ClearLiquidTask can't do this one: its goal is the cell over the lava, i.e. standing in it
    private Task scoop(AltoClef mod, CastLava.Cells w, List<BlockPos> sources) {
        BlockPos me = mod.getPlayer().blockPosition();
        sources.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        // from here it's the same reach test the click child aims with, not our guess at it
        if (CastLava.safeStand(w, me)) {
            for (BlockPos lava : sources) {
                if (LookHelper.getReach(lava).isPresent()) {
                    setDebugState("Scooping our loose lava back up");
                    return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), lava, false).stayPut();
                }
            }
        }
        for (BlockPos lava : sources) {
            for (BlockPos stand : CastLava.safeStands(w, lava, 3, 2, 1, me, castColumn())) {
                // walking to where we already are finishes on the spot and goes nowhere, the clock would eat the frame
                if (!stand.equals(me) && sees(mod, Vec3.atBottomCenterOf(stand).add(0, EYE_HEIGHT, 0), lava)) {
                    setDebugState("Walking somewhere safe to scoop our loose lava");
                    return new GetToBlockTask(stand, false);
                }
            }
        }
        // no spot sees it. the clock in strayLava gives the frame up if that doesn't change
        setDebugState("Nowhere safe to scoop our loose lava from");
        return backOff(mod, w);
    }

    // out of the lava's way, or stay put if we already are
    private Task backOff(AltoClef mod, CastLava.Cells w) {
        BlockPos me = mod.getPlayer().blockPosition();
        if (CastLava.safeStand(w, me)) {
            return null;
        }
        List<BlockPos> spots = CastLava.safeStands(w, me, 3, 1, 1, me, castColumn());
        return spots.isEmpty() ? null : new GetToBlockTask(spots.get(0), false);
    }

    // the lava cell and the two over it: never somewhere to stand, whatever they look like right now
    private List<BlockPos> castColumn() {
        return List.of(_pos, _pos.above(), _pos.above(2));
    }

    // what the bucket's ray would hit from that eye: our stray's source, within reach, and nothing in front of it
    private static boolean sees(AltoClef mod, Vec3 eye, BlockPos lava) {
        Vec3 to = Vec3.atCenterOf(lava);
        if (eye.distanceTo(to) > CastLava.SCOOP_REACH) {
            return false;
        }
        BlockHitResult hit = mod.getWorld().clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mod.getPlayer()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(lava);
    }

    // ban the cell first, then the parent sees it: the cast parent drops the origin and its search skips any spot with a
    // banned frame cell, the mold gives the slow cast up. without the ban the same spot gets picked straight back
    private Task dropOrigin(AltoClef mod, String why) {
        if (!_dropped) {
            _dropped = true;
            letGoOfTheClick(mod);
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getBlockTracker().requestBlockUnreachable(_pos, 0, why);
        }
        setDebugState("Cast spoiled, waiting for the frame to move");
        return null;
    }

    private boolean isObsidian(AltoClef mod) {
        return mod.getWorld().getBlockState(_pos).getBlock() == Blocks.OBSIDIAN;
    }

    private static boolean isWater(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() == Blocks.WATER;
    }

    // the world, not the tracker: the tracker is a few ticks behind a cast and this runs right after one
    private BlockPos leftoverWater(AltoClef mod) {
        if (mod.getWorld().getBlockState(_pos).getBlock() == Blocks.OBSIDIAN && WorldHelper.isSourceBlock(mod, _pos.above(), true)) {
            return _pos.above();
        }
        for (BlockPos cell : _earlierCells) {
            if (cell.equals(_pos) || mod.getWorld().getBlockState(cell).getBlock() != Blocks.OBSIDIAN) {
                continue;
            }
            if (WorldHelper.isSourceBlock(mod, cell.above(), true)) {
                return cell.above();
            }
        }
        return null;
    }

    /**
     * This method is called when the task is interrupted.
     *
     * @param mod           The instance of the AltoClef class.
     * @param interruptTask The task that caused the interruption.
     */
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_pouring) {
            _pouring = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }
        if (_backingUp) {
            stopBackingUp(mod);
        }
        // Check if the mod's behaviour is not null
        if (mod.getBehaviour() != null) {
            // Pop the behaviour from the stack
            mod.getBehaviour().pop();
        }
    }

    /**
     * Check if the current task is finished.
     * The task is considered finished if the block at the specified position is obsidian
     * and there is no water block above it.
     *
     * @param mod The AltoClef mod instance.
     * @return True if the task is finished, False otherwise.
     */
    @Override
    public boolean isFinished(AltoClef mod) {
        // the world, not the tracker: its cache lags a cast
        return isObsidian(mod) && !isWater(mod, _pos.above());
    }

    /**
     * Checks if the given task is equal to this PlaceObsidianBucketTask.
     * Two PlaceObsidianBucketTasks are considered equal if their positions are equal.
     * Overrides the isEqual() method from the parent class.
     *
     * @param other the task to compare with
     * @return true if the tasks are equal, false otherwise
     */
    @Override
    protected boolean isEqual(Task other) {
        // Check if the other task is an instance of PlaceObsidianBucketTask
        if (other instanceof PlaceObsidianBucketTask task) {
            // Check if the positions are equal
            // same spot with a different footprint is a rebuilt cast (the frame moved), and the parent asks the new
            // one about its lava, so the old one can't be the one that keeps ticking
            boolean isEqual = CastLava.sameCast(task.getPos(), task._footprint, getPos(), _footprint);
            // Return the result
            return isEqual;
        }
        // Return false
        return false;
    }

    @Override
    protected String toHudString() {
        return "Making obsidian at " + HudText.pos(_pos);
    }

    @Override
    protected String toDebugString() {
        return "Placing obsidian at " + _pos + " with a cast";
    }

    /**
     * Retrieves the position of the object.
     *
     * @return The position of the object.
     */
    public BlockPos getPos() {

        return _pos;
    }
}
