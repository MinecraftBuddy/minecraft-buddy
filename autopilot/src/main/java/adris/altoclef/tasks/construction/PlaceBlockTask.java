package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.input.Input;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Place a block type at a position
 */
public class PlaceBlockTask extends Task implements ITaskRequiresGrounded {

    private static final int MIN_MATERIALS = 1;
    // the obsidian portal and block replacing callers still ask for scaffolding. 32 of everything was a lot of dirt to dig for a few steps up
    private static final int PREFERRED_MATERIALS = 8;
    private final BlockPos _target;
    private final Block[] _toPlace;
    private final boolean _useThrowaways;
    private final boolean _autoCollectStructureBlocks;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5); // This can get stuck forever, so we increase the range.
    private Task _materialTask;
    private int _failCount = 0;
    // the builder (or our own look at the bag) found nothing to place with. set from the pathing thread too
    private volatile boolean _starved;
    // a starved task sits still this long before it asks again, so a missing table isn't one "Done building" in chat per tick
    private final TimerGame _starvedRetry = new TimerGame(1.0);

    public PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks) {
        _target = target;
        _toPlace = toPlace;
        _useThrowaways = useThrowaways;
        _autoCollectStructureBlocks = autoCollectStructureBlocks;
    }

    public PlaceBlockTask(BlockPos target, Block... toPlace) {
        this(target, toPlace, false, false);
    }

    public static int getMaterialCount(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE);
    }

    public static Task getMaterialTask(int count) {
        return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.DIRT, count), new ItemTarget(Items.COBBLESTONE,
                count), new ItemTarget(Items.NETHERRACK, count), new ItemTarget(Items.COBBLED_DEEPSLATE, count));
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(_toPlace));
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    // nothing in the bag to place with (or the builder said so). PlaceBlockNearbyTask reads this to give the spot a strike and
    // move on, where this used to place dirt over it and sit there
    public boolean isStarved() {
        return _starved;
    }

    private boolean haveMaterial(AltoClef mod) {
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_toPlace))) {
            return true;
        }
        if (_useThrowaways) {
            for (Item throwaway : mod.getClientBaritoneSettings().acceptableThrowawayItems.value) {
                if (mod.getItemStorage().hasItem(throwaway)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void starve() {
        _starved = true;
        _starvedRetry.reset();
        Debug.logInternal("Nothing to place " + Arrays.toString(_toPlace) + " with at " + _target.toShortString());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_starved) {
            // sit still for a second and then look again, the bag might have changed
            if (!_starvedRetry.elapsed()) {
                setDebugState("Nothing to place with.");
                _progressChecker.reset();
                if (mod.getClientBaritone().getBuilderProcess().isActive()) {
                    mod.getClientBaritone().getBuilderProcess().onLostControl();
                }
                return null;
            }
            _starved = false;
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                PortalExit.step(mod);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        // Perform timeout wander
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }
        if (_autoCollectStructureBlocks) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                setDebugState("No structure items, collecting cobblestone + dirt as default.");
                if (getMaterialCount(mod) < PREFERRED_MATERIALS) {
                    return _materialTask;
                } else {
                    _materialTask = null;
                }
            }

            //Item[] items = Util.toArray(Item.class, mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
            if (getMaterialCount(mod) < MIN_MATERIALS) {
                // TODO: Mine items, extract their resource key somehow.
                _materialTask = getMaterialTask(PREFERRED_MATERIALS);
                _progressChecker.reset();
                return _materialTask;
            }
        }


        // Check if we're approaching our point. If we fail, wander for a bit.
        if (!_progressChecker.check(mod)) {
            _failCount++;
            if (!tryingAlternativeWay()) {
                Debug.logMessage("Failed to place, wandering timeout.");
                return _wanderTask;
            } else {
                Debug.logMessage("Trying alternative way of placing block...");
            }
        }


        // Place block
        if (tryingAlternativeWay()) {
            setDebugState("Alternative way: Trying to go above block to place block.");
            return new GetToBlockTask(_target.above(), false);
        } else {
            setDebugState("Letting baritone place a block.");
            // Perform baritone placement
            if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                // no point waking the builder to tell us it has nothing (it says "Done building" in chat, every time)
                if (!haveMaterial(mod)) {
                    starve();
                    return null;
                }
                Debug.logInternal("Run Structure Build");
                ISchematic schematic = new PlaceStructureSchematic(mod);
                mod.getClientBaritone().getBuilderProcess().build("structure", schematic, _target);
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    //TODO: Place structure where a leaf block was???? Might need to delete the block first if it's not empty/air/water.

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockTask task) {
            return task._target.equals(_target) && task._useThrowaways == _useThrowaways && Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        assert Minecraft.getInstance().level != null;
        if (_useThrowaways) {
            return WorldHelper.isSolid(mod, _target);
        }
        BlockState state = mod.getWorld().getBlockState(_target);
        return ArrayUtils.contains(_toPlace, state.getBlock());
    }

    @Override
    protected String toHudString() {
        String what = "a block";
        if (_toPlace != null && _toPlace.length == 1) {
            what = HudText.one(HudText.block(_toPlace[0]));
        } else if (_toPlace != null && _toPlace.length > 1) {
            what = HudText.blocks(_toPlace);
        }
        return "Placing " + what + " at " + HudText.pos(_target);
    }

    @Override
    protected String toDebugString() {
        return "Place structure" + ArrayUtils.toString(_toPlace) + " at " + _target.toShortString();
    }

    private boolean tryingAlternativeWay() {
        return _failCount % 4 == 3;
    }

    private class PlaceStructureSchematic extends AbstractSchematic {

        private final AltoClef _mod;
        private volatile boolean _loggedNoThrowaway;

        public PlaceStructureSchematic(AltoClef mod) {
            super(1, 1, 1);
            _mod = mod;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                // already there is the answer. the builder asks again for the whole second between the block landing and
                // this task stopping, and with our only table gone from the bag the loop below found nothing and asked for
                // dirt over the table we just placed
                if (ArrayUtils.contains(_toPlace, blockState.getBlock())) {
                    return blockState;
                }
                // Place!!
                if (!available.isEmpty()) {
                    for (BlockState possible : available) {
                        if (possible == null) continue;
                        if (_useThrowaways && _mod.getClientBaritoneSettings().acceptableThrowawayItems.value.contains(possible.getBlock().asItem())) {
                            return possible;
                        }
                        if (Arrays.asList(_toPlace).contains(possible.getBlock())) {
                            return possible;
                        }
                    }
                }
                // the builder asks with an empty list when it is only checking its mask, that is not a verdict. answer with
                // the block we want so the position stays in the schematic
                if (available.isEmpty()) {
                    return _toPlace.length > 0 ? _toPlace[0].defaultBlockState() : blockState;
                }
                // the pathing thread asks this per node, 490 lines of it in one run. once per task is plenty
                if (!_loggedNoThrowaway) {
                    _loggedNoThrowaway = true;
                    Debug.logInternal("Failed to find throwaway block");
                }
                // we used to ask for dirt here, which put dirt where the table should go or sent the builder off to build
                // scaffolding for a block it was never going to have. no change wanted, and the task reports it's starved
                _starved = true;
                _starvedRetry.reset();
                return blockState;
            }
            // Don't care.
            return blockState;
        }
    }
}
