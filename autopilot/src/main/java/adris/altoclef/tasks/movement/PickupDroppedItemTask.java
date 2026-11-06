package adris.altoclef.tasks.movement;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.DropRoom;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.progresscheck.WaterPickupWatchdog;
import adris.altoclef.ui.HudText;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.phys.Vec3;
import java.util.Arrays;
import java.util.Optional;

public class PickupDroppedItemTask extends AbstractDoToClosestObjectTask<ItemEntity> implements ITaskRequiresGrounded {
    private static final Task getPickaxeFirstTask = new SatisfyMiningRequirementTask(MiningRequirement.STONE);
    // Not clean practice, but it helps keep things self contained I think.
    private static boolean isGettingPickaxeFirstFlag = false;
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final WaterPickupWatchdog _waterWatchdog = new WaterPickupWatchdog();
    private final ItemTarget[] _itemTargets;

    private final boolean _freeInventoryIfFull;
    private Task _unstuckTask = null;
    // Am starting to regret not making this a singleton
    private AltoClef _mod;
    private boolean _collectingPickaxeForThisResource = false;
    private ItemEntity _currentDrop = null;
    // set once we ban/skip a drop, so a parent on a clock can stop waiting instead of wandering until its timer runs out
    private String _gaveUpReason = null;

    public PickupDroppedItemTask(ItemTarget[]itemTargets, boolean freeInventoryIfFull) {
        _itemTargets = itemTargets;
        _freeInventoryIfFull = freeInventoryIfFull;
    }

    public PickupDroppedItemTask(ItemTarget target, boolean freeInventoryIfFull) {
        this(new ItemTarget[]{target}, freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount, boolean freeInventoryIfFull) {
        this(new ItemTarget(item, targetCount), freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount) {
        this(item, targetCount, true);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.offset(1, 0, 0),
                pos.offset(-1, 0, 0),
                pos.offset(0, 0, 1),
                pos.offset(0, 0, -1),
                pos.offset(1, 0, -1),
                pos.offset(1, 0, 1),
                pos.offset(-1, 0, -1),
                pos.offset(-1, 0, 1)
        };
    }

    public static boolean isIsGettingPickaxeFirst(AltoClef mod) {
        return isGettingPickaxeFirstFlag && Baritone.settings().altoCollectPickaxeFirst.value;
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().blockPosition();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.above())) return p.above();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.above());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    // null until a drop got banned or skipped ("water" / "unreachable")
    public String gaveUpReason() {
        return _gaveUpReason;
    }

    public boolean isCollectingPickaxeForThis() {
        return _collectingPickaxeForThisResource;
    }

    // virtual. the progress checker ran out on this drop. this used to go in a set of the task's own that the tracker never
    // heard of, now it is a short ban everybody honours
    protected void gaveUpOnDrop(AltoClef mod, ItemEntity drop) {
        BanPolicy.pickupStalled(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
    }

    // virtual. the search to the drop came back empty twice. the stock answer is the global no path ban
    protected void noPathToDrop(AltoClef mod, ItemEntity drop) {
        _gaveUpReason = "no path";
        BanPolicy.noPath(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
    }

    // virtual. false for a pickup on a clock that can't afford a stone pickaxe detour. bailing out of one halfway leaves
    // the static flag up until we own a stone pick, and every ResourceTask ignores far drops until then
    protected boolean mayGetPickaxeFirst() {
        return true;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _wanderTask.reset();
        _progressChecker.reset();
        stuckCheck.reset();
        _waterWatchdog.reset();
        _gaveUpReason = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            return _wanderTask;
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_progressChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        _mod = mod;

        // wet and not getting closer to the thing we are chasing: let it go, whatever the reason. this stays on even
        // with altoPickupItemsInWater, that setting only says whether we try at all
        if (_currentDrop != null && isValid(mod, _currentDrop)
                && _waterWatchdog.update(_currentDrop, mod.getPlayer().isInWater(), mod.getPlayer().distanceTo(_currentDrop), WorldHelper.getTicks())) {
            Debug.logInternal("Giving up on " + _currentDrop.getItem().getItem().getDescriptionId() + ", in water and not getting closer");
            BanPolicy.wetDrop(mod.getBans(), _currentDrop.getId(), EntityTracker.describe(_currentDrop));
            _gaveUpReason = "water";
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _currentDrop = null;
            _waterWatchdog.reset();
            _progressChecker.reset();
            setDebugState("Skipping items in water");
            return null;
        }

        // If we're getting a pickaxe for THIS resource...
        if (isIsGettingPickaxeFirst(mod) && _collectingPickaxeForThisResource && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            _progressChecker.reset();
            setDebugState("Collecting pickaxe first");
            return getPickaxeFirstTask;
        } else {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                isGettingPickaxeFirstFlag = false;
            }
            _collectingPickaxeForThisResource = false;
        }

        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            if (_currentDrop != null && !_currentDrop.getItem().isEmpty()) {
                // We might want to get a pickaxe first.
                if (mayGetPickaxeFirst() && !isGettingPickaxeFirstFlag && Baritone.settings().altoCollectPickaxeFirst.value && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                    Debug.logMessage("Failed to pick up drop, will try to collect a stone pickaxe first and try again!");
                    _collectingPickaxeForThisResource = true;
                    isGettingPickaxeFirstFlag = true;
                    return getPickaxeFirstTask;
                }
                Debug.logMessage("Failed to pick up drop, suggesting it's unreachable.");
                // the strike stays too, the fourth one is the longer ban
                gaveUpOnDrop(mod, _currentDrop);
                _gaveUpReason = "unreachable";
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                _progressChecker.reset();
                return _wanderTask;
            }
            // no drop to blame. a failed checker stays failed until somebody resets it, and the cancel above would run on
            // every tick from here on, killing the search the goal task below starts each time
            _progressChecker.reset();
        }

        return super.onTick(mod);
    }


    // the drop we were walking to is gone because it went into our bag. that is the happy ending, but the search below reads
    // "nothing left to pick up" as lost, wanders for a tick and the parent takes the wander for a failure (the log showed a
    // Wander task start and die after every single kill, the pickups all worked). a parent that asks gets a plain yes instead
    @Override
    public boolean isFinished(AltoClef mod) {
        if (_currentDrop == null || !mod.getEntityTracker().isEntityReachable(_currentDrop)) {
            return false;
        }
        boolean gone = !_currentDrop.isAlive() || _currentDrop.getItem().isEmpty();
        return ItemPickupRules.collected(gone, mod.getPlayer().distanceTo(_currentDrop)) && getClosestTo(mod, getOriginPos(mod)).isEmpty();
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof PickupDroppedItemTask task) {
            return Arrays.equals(task._itemTargets, _itemTargets) && task._freeInventoryIfFull == _freeInventoryIfFull;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append("Pickup Dropped Items: [");
        int c = 0;
        for (ItemTarget target : _itemTargets) {
            result.append(target.toString());
            if (++c != _itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected String toHudString() {
        return "Picking up " + HudText.some(_itemTargets);
    }

    @Override
    protected Vec3 getPos(AltoClef mod, ItemEntity obj) {
        if (!obj.onGround() && !obj.isInWater()) {
            // Assume we'll land down one or two blocks from here. We could do this more advanced but whatever.
            BlockPos p = obj.blockPosition();
            if (!WorldHelper.isSolid(mod, p.below(3))) {
                return obj.position().subtract(0, 2, 0);
            }
            return obj.position().subtract(0, 1, 0);
        }
        return obj.position();
    }

    @Override
    protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
        return mod.getEntityTracker().getClosestItemDrop(
                pos,
                _itemTargets);
    }

    @Override
    protected Vec3 getOriginPos(AltoClef mod) {
        return mod.getPlayer().position();
    }

    @Override
    protected Task getGoalTask(ItemEntity itemEntity) {
        if (!itemEntity.equals(_currentDrop)) {
            _currentDrop = itemEntity;
            _progressChecker.reset();
            if (isGettingPickaxeFirstFlag && _collectingPickaxeForThisResource) {
                Debug.logMessage("New goal, no longer collecting a pickaxe.");
                _collectingPickaxeForThisResource = false;
                isGettingPickaxeFirstFlag = false;
            }
        }
        // Ensure our inventory is free if we're close
        boolean touching = _mod.getEntityTracker().isCollidingWithPlayer(itemEntity);
        if (touching) {
            if (_freeInventoryIfFull) {
                // same answer the death pile filters by, so a drop it walked us to is never one we refuse here
                StorageHelper.Room room = StorageHelper.garbageView(_mod).room(itemEntity.getItem());
                if (room.kind() == DropRoom.Kind.EVICT) {
                    return new EnsureFreeInventorySlotTask(room.slot().get(), room.victim());
                }
                if (room.kind() == DropRoom.Kind.NONE) {
                    Debug.logMessage("no room for " + itemEntity.getItem().getItem().getDescriptionId() + ", nothing in the bag may go");
                    _gaveUpReason = "no room";
                    BanPolicy.noRoom(_mod.getBans(), itemEntity.getId(), EntityTracker.describe(itemEntity));
                    _currentDrop = null;
                    _progressChecker.reset();
                    return null;
                }
            }
        }
        return new GetToEntityTask(itemEntity, 1, () -> noPathToDrop(_mod, itemEntity));
    }

    // public for RecoverItemsTask, which skips a drop that would only be thrown again
    public static boolean hasRoomFor(AltoClef mod, ItemStack drop) {
        return StorageHelper.hasRoomFor(mod, drop);
    }

    @Override
    protected boolean isValid(AltoClef mod, ItemEntity obj) {
        if (!obj.isAlive() || !mod.getEntityTracker().isEntityReachable(obj)) return false;
        // a drop we were already walking to can slide next to lava after the tracker picked it, and the tracker's own
        // skip only keeps new targets out. no setting for this one
        if (ItemPickupRules.lavaBlocksPickup(obj)) return false;
        // it can end up in the water after we picked it as the target (broke the block over a lake), the tracker has
        // already forgotten it by then so we have to drop it ourselves
        return Baritone.settings().altoPickupItemsInWater.value || ItemPickupRules.worthSwimming(obj) || ItemPickupRules.isPickupSafe(obj);
    }

}
