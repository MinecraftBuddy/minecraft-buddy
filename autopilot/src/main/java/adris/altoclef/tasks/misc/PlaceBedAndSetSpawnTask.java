package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.SystemChatEvent;
import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.movement.WanderGiveUp;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.ArrayUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class PlaceBedAndSetSpawnTask extends Task {

    private final TimerGame _regionScanTimer = new TimerGame(9);
    private final Vec3i BED_CLEAR_SIZE = new Vec3i(3, 2, 3);
    private final Vec3i[] BED_BOTTOM_PLATFORM = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(2, -1, 0),
            new Vec3i(0, -1, -1),
            new Vec3i(1, -1, -1),
            new Vec3i(2, -1, -1),
            new Vec3i(0, -1, 1),
            new Vec3i(1, -1, 1),
            new Vec3i(2, -1, 1)
    };
    // Kinda silly but who knows if we ever want to change it.
    private final Vec3i BED_PLACE_STAND_POS = new Vec3i(0, 0, 1);
    private final Vec3i BED_PLACE_POS = new Vec3i(1, 0, 1);
    private final Vec3i[] BED_PLACE_POS_OFFSET = new Vec3i[]{
            BED_PLACE_POS,
            BED_PLACE_POS.north(),
            BED_PLACE_POS.south(),
            BED_PLACE_POS.east(),
            BED_PLACE_POS.west(),
            BED_PLACE_POS.offset(-1, 0, 1),
            BED_PLACE_POS.offset(1, 0, 1),
            BED_PLACE_POS.offset(-1, 0, -1),
            BED_PLACE_POS.offset(1, 0, -1),
            BED_PLACE_POS.north(2),
            BED_PLACE_POS.south(2),
            BED_PLACE_POS.east(2),
            BED_PLACE_POS.west(2),
            BED_PLACE_POS.offset(-2, 0, 1),
            BED_PLACE_POS.offset(-2, 0, 2),
            BED_PLACE_POS.offset(2, 0, 1),
            BED_PLACE_POS.offset(2, 0, 2),
            BED_PLACE_POS.offset(-2, 0, -1),
            BED_PLACE_POS.offset(-2, 0, -2),
            BED_PLACE_POS.offset(2, 0, -1),
            BED_PLACE_POS.offset(2, 0, -2)
    };
    private final Direction BED_PLACE_DIRECTION = Direction.UP;
    private final TimerGame _bedInteractTimeout = new TimerGame(5);
    private final TimerGame _inBedTimer = new TimerGame(1);
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private boolean _stayInBed;
    // only the spawn matters, not the sleep: nearby monsters block sleeping but not the spawn set, so they are not worth a fight
    private boolean _spawnOnly;
    private BlockPos _currentBedRegion;
    // a spot the bed must keep clear of (the portal we just lit), and how far. null for no such spot
    private BlockPos _awayFrom;
    private int _awayDist;
    // spots dropped for a slow block in the clear box. the search only asks canBreak about part of that box, so without
    // this it would hand the same spot straight back
    private final java.util.Set<BlockPos> _slowRegions = new java.util.HashSet<>();
    private BlockPos _currentStructure, _currentBreak;
    private boolean _spawnSet;
    private Subscription<SystemChatEvent> _respawnPointSetMessageCheck;
    private Subscription<GameOverlayEvent> _respawnFailureMessageCheck;
    private boolean _sleepAttemptMade;
    private boolean _wasSleeping;
    private BlockPos _bedForSpawnPoint;
    // the search for a spot ended because the wander looking for one was stuck, not because a spawn got set. sticky
    // until resetSleep: onStart runs again after every interrupt and the wander (same instance) is still the stuck one
    private boolean _noBedSpot;
    // ticks spent in the search branch and how far we got from where it started, see the branch
    private int _searchTicks;
    private WanderGiveUp.Excursion _searchExcursion = new WanderGiveUp.Excursion();

    public PlaceBedAndSetSpawnTask() {

    }

    /**
     * Sets the _stayInBed flag to true and returns the current instance.
     *
     * @return the current instance
     */
    public PlaceBedAndSetSpawnTask stayInBed() {
        this._stayInBed = true;
        return this;
    }

    public PlaceBedAndSetSpawnTask spawnOnly() {
        this._spawnOnly = true;
        return this;
    }

    // the clear box and its floor stay awayDist blocks off this spot, the chebyshev way, on every axis
    public PlaceBedAndSetSpawnTask awayFrom(BlockPos spot, int dist) {
        this._awayFrom = spot;
        this._awayDist = dist;
        return this;
    }

    // the gap between a point and what the bed task touches: the clear box from origin, and the floor row under it, which
    // sits one block further out on the z side (BED_BOTTOM_PLATFORM runs z -1..1, the box runs z 0..2). 0 when the point is
    // inside either. static and pure so it can be tested without a world
    static int gapToBedBox(BlockPos origin, Vec3i clearSize, BlockPos spot) {
        int x0 = origin.getX(), x1 = origin.getX() + clearSize.getX() - 1;
        int box = Math.max(axisGap(spot.getX(), x0, x1), Math.max(
                axisGap(spot.getY(), origin.getY(), origin.getY() + clearSize.getY() - 1),
                axisGap(spot.getZ(), origin.getZ(), origin.getZ() + clearSize.getZ() - 1)));
        int floor = Math.max(axisGap(spot.getX(), x0, x1), Math.max(
                axisGap(spot.getY(), origin.getY() - 1, origin.getY() - 1),
                axisGap(spot.getZ(), origin.getZ() - 1, origin.getZ() + 1)));
        return Math.min(box, floor);
    }

    private static int axisGap(int v, int lo, int hi) {
        return v < lo ? lo - v : Math.max(v - hi, 0);
    }

    // a region that comes closer than dist to the spot is out. no spot, no rule
    static boolean tooCloseToSpot(BlockPos origin, Vec3i clearSize, BlockPos spot, int dist) {
        return spot != null && gapToBedBox(origin, clearSize, spot) < dist;
    }

    // blocks the clear loop never digs: a frame, or a portal. a bed is never worth either
    static boolean neverDigForBed(Block block) {
        return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.NETHER_PORTAL;
    }

    // the first protected block in a clear box, or null. the predicate is the world, the rest is just the box
    static BlockPos protectedInBox(BlockPos origin, Vec3i clearSize, Predicate<BlockPos> isProtected) {
        for (int dx = 0; dx < clearSize.getX(); ++dx) {
            for (int dz = 0; dz < clearSize.getZ(); ++dz) {
                for (int dy = 0; dy < clearSize.getY(); ++dy) {
                    BlockPos cell = origin.offset(dx, dy, dz);
                    if (isProtected.test(cell)) {
                        return cell;
                    }
                }
            }
        }
        return null;
    }

    /**
     * This method is called when the bot is started.
     * It tracks bed blocks, resets progress checkers, and sets up behaviors for placing and breaking blocks near beds.
     * It also resets variables for sleep handling and subscribes to events for respawn point and failure messages.
     */
    @Override
    protected void onStart(AltoClef mod) {
        try {
            // Track bed blocks
            mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(ItemHelper.BED));

            // Push the current behaviour
            mod.getBehaviour().push();

            // Reset progress checker
            _progressChecker.reset();

            // Reset current bed region
            _currentBedRegion = null;

            // Avoid placing blocks near bed
            mod.getBehaviour().avoidBlockPlacing(pos -> {
                if (_currentBedRegion != null) {
                    BlockPos start = _currentBedRegion;
                    BlockPos end = _currentBedRegion.offset(BED_CLEAR_SIZE);
                    return start.getX() <= pos.getX() && pos.getX() < end.getX()
                            && start.getZ() <= pos.getZ() && pos.getZ() < end.getZ()
                            && start.getY() <= pos.getY() && pos.getY() < end.getY();
                }
                return false;
            });

            // Avoid breaking blocks near bed
            mod.getBehaviour().avoidBlockBreaking(pos -> {
                if (_currentBedRegion != null) {
                    for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
                        BlockPos base = _currentBedRegion.offset(baseOffs);
                        if (base.equals(pos)) return true;
                    }
                }
                // Don't ever break beds. If one exists, we will sleep in it.
                if (mod.getWorld() != null) {
                    return mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
                }
                return false;
            });

            // Reset variables for sleep handling
            _spawnSet = false;
            _sleepAttemptMade = false;
            _wasSleeping = false;

            // Subscribe to respawn point set message event
            // (a system line, ChatMessageEvent only carries players' chat and this used to wait for a line that never came)
            _respawnPointSetMessageCheck = EventBus.subscribe(SystemChatEvent.class, evt -> {
                if (evt.message.contains("Respawn point set")) {
                    _spawnSet = true;
                    _inBedTimer.reset();
                }
            });

            // Subscribe to respawn failure message event
            _respawnFailureMessageCheck = EventBus.subscribe(GameOverlayEvent.class, evt -> {
                final String[] NEUTRAL_MESSAGES = new String[]{
                        "You can sleep only at night",
                        "You can only sleep at night",
                        "You may not rest now; there are monsters nearby"
                };
                for (String checkMessage : NEUTRAL_MESSAGES) {
                    if (evt.message.contains(checkMessage)) {
                        if (!_sleepAttemptMade) {
                            _bedInteractTimeout.reset();
                        }
                        _sleepAttemptMade = true;
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetSleep() {
        _noBedSpot = false;
        _searchTicks = 0;
        _searchExcursion = new WanderGiveUp.Excursion();
        _spawnSet = false;
        _sleepAttemptMade = false;
        _wasSleeping = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Summary:
        // If we find a bed nearby, sleep in it.
        // Otherwise, place bed:
        //      Collect bed if we don't have one.
        //      Find a 3x2x1 region and clear it
        //      Stand on the edge of the long (3) side
        //      Place on the middle block, reliably placing the bed.
        if (!_progressChecker.check(mod) && _currentBedRegion != null) {
            _progressChecker.reset();
            Debug.logMessage("Searching new bed region.");
            _currentBedRegion = null;
        }
        if (mod.getPlayer().isInWater() && mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("We are in water. Wandering");
            _currentBedRegion = null;
            return new TimeoutWanderTask();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            setDebugState("We are in nether portal. Wandering");
            _currentBedRegion = null;
            return new TimeoutWanderTask();
        }
        // We cannot do this anywhere but the overworld.
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld first.", "Heading to the Overworld");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen instanceof InBedChatScreen) {
            _progressChecker.reset();
            setDebugState("Sleeping...", "Sleeping");
            _wasSleeping = true;
            //Debug.logMessage("Closing sleeping thing");
            _spawnSet = true;
            return null;
        }

        if (_sleepAttemptMade) {
            if (_bedInteractTimeout.elapsed()) {
                Debug.logMessage("Failed to get \"Respawn point set\" message or sleeping, assuming that this bed already contains our spawn.");
                _spawnSet = true;
                return null;
            }
        }
        Block[] copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
        Optional<BlockPos> nearestBed = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        if (nearestBed.isPresent() && !WorldHelper.isAir(mod, nearestBed.get())) {
            Block bed = mod.getWorld().getBlockState(nearestBed.get()).getBlock();
            for (Block CopperBlock : copperBlocks) {
                Block blockBelow = mod.getWorld().getBlockState(nearestBed.get().below()).getBlock();
                if (blockBelow == CopperBlock) {
                    mod.getBlockTracker().requestBlockUnreachable(nearestBed.get(), 0);
                    return null;
                }
            }
            if (nearestBed.get().closerToCenterThan(mod.getPlayer().position(), 40)) {
                // Check if there are monsters nearby
                Vec3 vec3d = Vec3.atBottomCenterOf(nearestBed.get());
                // 1.21.2 made isPreventingPlayerRest want a ServerLevel we do not have. every monster says true except zombified piglins, who want to be angry at us
                List<Monster> list = mod.getWorld().getEntitiesOfClass(Monster.class, new AABB(vec3d.x() - 8.0, vec3d.y() - 5.0, vec3d.z() - 8.0, vec3d.x() + 8.0, vec3d.y() + 5.0, vec3d.z() + 8.0), (entity) -> !(entity instanceof ZombifiedPiglin) || EntityHelper.isAngryAtPlayer(mod, entity));
                if (!_spawnOnly && !list.isEmpty()) {
                    for (Monster entity : list) {
                        setDebugState("Killing monster nearby");
                        Predicate<Entity> valid = e -> e == entity;
                        return new KillEntitiesTask(valid, entity.getClass());
                    }
                }
                // Sleep in the nearest bed
                setDebugState("Going to bed to sleep...");
                return new DoToClosestBlockTask(toSleepIn -> {
                    boolean closeEnough = toSleepIn.closerToCenterThan(mod.getPlayer().position(), 3);
                    if (closeEnough) {
                        // why 0.2? I'm tired.
                        Vec3 centerBed = new Vec3(toSleepIn.getX() + 0.5, toSleepIn.getY() + 0.2, toSleepIn.getZ() + 0.5);
                        BlockHitResult hit = LookHelper.raycast(mod.getPlayer(), centerBed, 6);
                        // TODO: Kinda ugly, but I'm tired and fixing for the 2nd attempt speedrun so I will fix this block later
                        closeEnough = false;
                        if (hit.getType() != HitResult.Type.MISS) {
                            // At this poinAt, if we miss, we probably are close enough.
                            BlockPos p = hit.getBlockPos();
                            if (ArrayUtils.contains(new Block[]{bed}, mod.getWorld().getBlockState(p).getBlock())) {
                                // We have a bed!
                                closeEnough = true;
                            }
                        }
                    }
                    _bedForSpawnPoint = WorldHelper.getBedHead(mod, toSleepIn);
                    if (_bedForSpawnPoint == null) {
                        _bedForSpawnPoint = toSleepIn;
                    }
                    if (!closeEnough) {
                        try {
                            Direction face = mod.getWorld().getBlockState(toSleepIn).getValue(BedBlock.FACING);
                            Direction side = face.getClockWise();
                    /*
                    BlockPos targetMove = toSleepIn.offset(side).offset(side); // Twice, juust to make sure...
                     */
                            return new GetToBlockTask(_bedForSpawnPoint.offset(side.getUnitVec3i()));
                        } catch (IllegalArgumentException e) {
                            // If bed is not loaded, this will happen. In that case just get to the bed first.
                        }
                    } else {
                        _inBedTimer.reset();
                    }
                    if (closeEnough) {
                        _inBedTimer.reset();
                    }
                    // Keep track of where our spawn point is
                    _progressChecker.reset();
                    return new InteractWithBlockTask(_bedForSpawnPoint);
                }, bed);
            }
        }
        if (_currentBedRegion != null) {
            for (Vec3i BedPlacePos : BED_PLACE_POS_OFFSET) {
                Block getBlock = mod.getWorld().getBlockState(_currentBedRegion.offset(BedPlacePos)).getBlock();
                if (getBlock instanceof BedBlock) {
                    mod.getBlockTracker().addBlock(getBlock, _currentBedRegion.offset(BedPlacePos));
                    break;
                }
            }
        }
        // Get a bed if we don't have one.
        if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("Getting a bed first", "Getting a bed");
            return TaskCatalogue.getItemTask("bed", 1);
        }

        if (_currentBedRegion == null) {
            if (_regionScanTimer.elapsed()) {
                Debug.logMessage("Rescanning for nearby bed place position...");
                _regionScanTimer.reset();
                _currentBedRegion = this.locateBedRegion(mod, mod.getPlayer().blockPosition());
            }
        }
        if (_currentBedRegion != null) {
            // found one, the next search starts over
            if (_searchTicks != 0) {
                _searchTicks = 0;
                _searchExcursion = new WanderGiveUp.Excursion();
            }
        } else {
            setDebugState("Searching for spot to place bed, wandering...");
            // nowhere to put a bed and the search can't even leave its spot. setting a spawn is optional, so this ends
            // the task and gaveUp tells the reader it was not a spawn. the clock is ours and not the wander's: the two
            // escape wanders above (water, portal) are the same instance as this one (infinite wanders are equal), and
            // time spent stuck in a pond must not count against the search. same decision as the wander uses
            Vec3 pos = mod.getPlayer().position();
            _searchExcursion.note(pos.x(), pos.y(), pos.z());
            _searchTicks++;
            if (WanderGiveUp.decide(_searchTicks / 20.0, _searchExcursion.max()) == WanderGiveUp.Stage.FAILING) {
                _noBedSpot = true;
            }
            return new TimeoutWanderTask();
        }

        // Clear and make bed foundation

        for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
            BlockPos toPlace = _currentBedRegion.offset(baseOffs);
            if (!WorldHelper.isSolid(mod, toPlace)) {
                _currentStructure = toPlace;
                break;
            }
        }

        // a frame in the way is not a slow block, it is a no. same exit though: this spot goes and the search starts over.
        // skipping the block would leave a half cleared box and the bed placed into stone
        if (protectedInBox(_currentBedRegion, BED_CLEAR_SIZE, cell -> neverDigForBed(mod.getWorld().getBlockState(cell).getBlock())) != null) {
            _slowRegions.add(_currentBedRegion.immutable());
            _currentBedRegion = null;
            _currentBreak = null;
            _currentStructure = null;
            return null;
        }

        outer:
        for (int dx = 0; dx < BED_CLEAR_SIZE.getX(); ++dx) {
            for (int dz = 0; dz < BED_CLEAR_SIZE.getZ(); ++dz) {
                for (int dy = 0; dy < BED_CLEAR_SIZE.getY(); ++dy) {
                    BlockPos toClear = _currentBedRegion.offset(dx, dy, dz);
                    if (WorldHelper.isSolid(mod, toClear)) {
                        // the destroy task would refuse it and idle, so the spot goes and the search starts over
                        if (WorldHelper.tooSlowToBreak(mod, toClear)) {
                            _slowRegions.add(_currentBedRegion.immutable());
                            _currentBedRegion = null;
                            _currentBreak = null;
                            _currentStructure = null;
                            return null;
                        }
                        _currentBreak = toClear;
                        break outer;
                    }
                }
            }
        }

        if (_currentStructure != null) {
            if (WorldHelper.isSolid(mod, _currentStructure)) {
                _currentStructure = null;
            } else {
                setDebugState("Placing structure for bed");
                return new PlaceStructureBlockTask(_currentStructure);
            }
        }
        if (_currentBreak != null) {
            if (!WorldHelper.isSolid(mod, _currentBreak)) {
                _currentBreak = null;
            } else {
                setDebugState("Clearing region for bed");
                return new DestroyBlockTask(_currentBreak);
            }
        }

        BlockPos toStand = _currentBedRegion.offset(BED_PLACE_STAND_POS);
        // Our bed region is READY TO BE PLACED
        if (!mod.getPlayer().blockPosition().equals(toStand)) {
            return new GetToBlockTask(toStand);
        }

        BlockPos toPlace = _currentBedRegion.offset(BED_PLACE_POS);
        if (mod.getWorld().getBlockState(toPlace.relative(BED_PLACE_DIRECTION)).getBlock() instanceof BedBlock) {
            setDebugState("Waiting to rescan + find bed that we just placed. Should be almost instant.");
            _progressChecker.reset();
            return null;
        }
        setDebugState("Placing bed...", "Placing the bed");

        setDebugState("Filling in Portal");
        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            _progressChecker.reset();
        }

        // Scoot backwards if we're trying to place and fail
        if (thisOrChildSatisfies(task -> {
            if (task instanceof InteractWithBlockTask intr)
                return intr.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED;
            return false;
        })) {
            mod.getInputControls().tryPress(Input.MOVE_BACK);
        }
        return new InteractWithBlockTask(new ItemTarget("bed", 1), BED_PLACE_DIRECTION, toPlace.relative(BED_PLACE_DIRECTION.getOpposite()), false);
    }

    /**
     * Perform cleanup tasks when the task is stopped.
     *
     * @param mod The AltoClef mod instance
     * @param interruptTask The task that caused the interruption
     */
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Stop tracking beds
        if (mod.getBlockTracker() != null) {
            mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        }

        // Pop the behaviour stack
        if (mod.getBehaviour() != null) {
            mod.getBehaviour().pop();
        }

        // Unsubscribe from respawn point set message
        if (_respawnPointSetMessageCheck != null) {
            EventBus.unsubscribe(_respawnPointSetMessageCheck);
        }

        // Unsubscribe from respawn failure message
        if (_respawnFailureMessageCheck != null) {
            EventBus.unsubscribe(_respawnFailureMessageCheck);
        }
    }

    /**
     * Check if the given task is equal to this task.
     *
     * @param other The other task to compare with
     * @return True if the other task is equal to this task, false otherwise
     */
    @Override
    protected boolean isEqual(Task other) {
        if (other == null) {
            return false;
        }
        // Check if the other task is an instance of PlaceBedAndSetSpawnTask
        return other instanceof PlaceBedAndSetSpawnTask;
    }

    @Override
    protected String toHudString() {
        return "Sleeping in a bed";
    }

    /**
     * Returns a debug string representing the action of placing a bed nearby and resetting the spawn point.
     *
     * @return the debug string
     */
    @Override
    protected String toDebugString() {
        try {
            // Place a bed nearby and reset spawn point
            return "Placing a bed nearby + resetting spawn point";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error generating debug string";
        }
    }

    /**
     * Check if the player is finished with the sleeping task.
     *
     * @param mod the AltoClef mod
     * @return true if the player is finished, false otherwise
     */
    @Override
    public boolean isFinished(AltoClef mod) {
        // Check if we are in the overworld
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return true; // If not in the overworld, the player is considered finished
        }

        if (_noBedSpot) {
            return true;
        }

        // Check if player is sleeping
        boolean isSleeping = mod.getPlayer() != null && mod.getPlayer().isSleeping();

        // Check if timer has elapsed
        boolean timerElapsed = _inBedTimer != null && _inBedTimer.elapsed();

        // Check if spawnpoint is set, player is not sleeping, and timer has elapsed
        boolean isFinished = _spawnSet && !isSleeping && timerElapsed;

        return isFinished;
    }

    // finished, but only because there was nowhere to put a bed. a caller that reads finished as "spawn is set" has to ask
    public boolean gaveUp() {
        return _noBedSpot;
    }

    /**
     * Returns the position of the bed where the player last slept.
     * @return the position of the bed where the player last slept
     * @throws IllegalStateException if the bed position has not been set
     */
    public BlockPos getBedSleptPos() {
        if (_bedForSpawnPoint != null) {
            return _bedForSpawnPoint;
        } else {
            throw new IllegalStateException("Bed position has not been set");
        }
    }

    /**
     * Check if the spawn point is set.
     * @return true if the spawn point is set, false otherwise
     */
    public boolean isSpawnSet() {
        return _spawnSet;
    }

    /**
     * Locates the closest good position within a certain range of the origin.
     *
     * @param mod the mod instance
     * @param origin the origin position
     * @return the closest good position within the range of the origin
     */
    private BlockPos locateBedRegion(AltoClef mod, BlockPos origin) {
        final int SCAN_RANGE = 10;

        BlockPos closestGood = null; // The closest good position found so far
        double closestDist = Double.POSITIVE_INFINITY; // The closest distance found so far

        for (int x = origin.getX() - SCAN_RANGE; x < origin.getX() + SCAN_RANGE; ++x) {
            for (int z = origin.getZ() - SCAN_RANGE; z < origin.getZ() + SCAN_RANGE; ++z) {
                for (int y = origin.getY() - SCAN_RANGE; y < origin.getY() + SCAN_RANGE; ++y) {
                    BlockPos attemptPos = new BlockPos(x, y, z);
                    double distance = attemptPos.distToCenterSqr(mod.getPlayer().position());

                    if (distance > closestDist) {
                        continue; // Skip if the distance is greater than the closest distance found so far
                    }

                    if (!_slowRegions.contains(attemptPos) && !tooCloseToSpot(attemptPos, BED_CLEAR_SIZE, _awayFrom, _awayDist) && isGoodPosition(mod, attemptPos)) {
                        closestGood = attemptPos;
                        closestDist = distance;
                    }
                }
            }
        }

        return closestGood;
    }

    /**
     * Checks if the position is suitable for placing an object.
     *
     * @param mod the AltoClef object
     * @param pos the position to check
     * @return true if the position is suitable, false otherwise
     */
    private boolean isGoodPosition(AltoClef mod, BlockPos pos) {
        final BlockPos BED_CLEAR_SIZE = new BlockPos(2, 1, 2);

        // Iterate over the area around the position
        for (int x = 0; x < BED_CLEAR_SIZE.getX(); ++x) {
            for (int y = 0; y < BED_CLEAR_SIZE.getY(); ++y) {
                for (int z = 0; z < BED_CLEAR_SIZE.getZ(); ++z) {
                    BlockPos checkPos = pos.offset(x, y, z);
                    if (!isGoodToPlaceInsideOrClear(mod, checkPos)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Check if it is good to place inside or clear at the given position
     * @param mod the AltoClef object
     * @param pos the position to check
     * @return true if it is good to place inside or clear, false otherwise
     */
    private boolean isGoodToPlaceInsideOrClear(AltoClef mod, BlockPos pos) {
        // Define the offsets to check around the position
        final Vec3i[] CHECK = {
                new Vec3i(0, 0, 0),
                new Vec3i(-1, 0, 0),
                new Vec3i(1, 0, 0),
                new Vec3i(0, 1, 0),
                new Vec3i(0, -1, 0),
                new Vec3i(0, 0, 1),
                new Vec3i(0, 0, -1)
        };

        // Check each offset
        for (Vec3i offset : CHECK) {
            BlockPos newPos = pos.offset(offset);
            if (!isGoodAsBorder(mod, newPos)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if the given position is suitable as a border.
     *
     * @param mod The AltoClef object
     * @param pos The position to check
     * @return true if the position is suitable as a border, false otherwise
     */
    private boolean isGoodAsBorder(AltoClef mod, BlockPos pos) {
        // Check if the position is solid
        if (WorldHelper.isSolid(mod, pos)) {
            // If solid, check if it can be broken
            return WorldHelper.canBreak(mod, pos);
        } else {
            // If not solid, check if it is air
            return WorldHelper.isAir(mod, pos);
        }
    }
}
