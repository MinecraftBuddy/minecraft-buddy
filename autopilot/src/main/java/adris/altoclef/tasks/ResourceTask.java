package adris.altoclef.tasks;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.DropPatience;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * The parent for all "collect an item" tasks.
 * <p>
 * If the target item is on the ground or in a chest, will grab from those sources first.
 */
public abstract class ResourceTask extends Task implements ITaskCanForce {

    protected final ItemTarget[] _itemTargets;

    private final PickupDroppedItemTask _pickupTask;
    private final EnsureFreePlayerCraftingGridTask _ensureFreeCraftingGridTask = new EnsureFreePlayerCraftingGridTask();
    // one instance so its throw-next-tick memory lives through the swap
    private final EnsureFreeCursorSlotTask _cursorTask = new EnsureFreeCursorSlotTask();
    private ContainerCache _currentContainer;
    // Extra resource parameters
    private Block[] _mineIfPresent = null;
    private boolean _forceDimension = false;
    private Dimension _targetDimension;
    private BlockPos _mineLastClosest = null;
    // every item any target matches, built once. onTick used to rebuild this (twice, in two different ways) per tick
    // (lazy, a subclass is allowed to fill its targets in after super())
    private Item[] _allMatches;
    // and how many of each we're collecting, so the pathing side knows how much of it is ours to keep. same laziness
    private Map<Item, Integer> _reserve;
    // our own behaviour level. the reserve goes in here and not in whatever is on top, a child task pushes its own on every
    // tick of ours and the numbers used to land in it (and count twice)
    private BotBehaviour.State _level;
    // a throwaway dig (building blocks) must not hold its own targets back from the movements: the blocks exist to be placed,
    // and a hold of the full count on every one of them left the pathing unable to place anything while it dug
    private boolean _noReserve = false;
    private static final int CONTAINER_LOOKUP_TICKS = 20;
    private int _containerLookupCooldown = 0;

    public ResourceTask(ItemTarget[] itemTargets) {
        _itemTargets = itemTargets;
        _pickupTask = new PickupDroppedItemTask(_itemTargets, true);
    }

    public ResourceTask(ItemTarget target) {
        this(new ItemTarget[]{target});
    }

    public ResourceTask(Item item, int targetCount) {
        this(new ItemTarget(item, targetCount));
    }

    // construction time, like onlyWhere. skips the whole reserve call: writing zeros would still put the items in protectedItems
    public ResourceTask noReserve() {
        _noReserve = true;
        return this;
    }

    // what the reserve call gets. empty means the call is skipped altogether
    static <K> Map<K, Integer> reserveOrNone(boolean noReserve, Map<K, Integer> reserve) {
        return noReserve ? Map.of() : reserve;
    }

    // a target that matches several items (any planks) saves its count for each of them, over-saving is the safe way. the
    // infinite ones are 99999999 and the sum stops there, which is as good as all of it
    private static Map<Item, Integer> reserveFor(ItemTarget[] targets) {
        Map<Item, Integer> out = new HashMap<>();
        for (ItemTarget target : targets) {
            for (Item match : target.getMatches()) {
                out.merge(match, target.getTargetCount(), (a, b) -> (int) Math.min((long) a + b, 1L << 30));
            }
        }
        return out;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.itemTargetsMetInventoryNoCursor(mod, _itemTargets);
    }

    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        // We have an important item target in our cursor.
        return StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && !isFinished(mod)
                // This _should_ be redundant, but it'll be a guard just to make 100% sure.
                && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()));
    }

    @Override
    protected void onStart(AltoClef mod) {
        // back from a pause: our own level has the exclusions again
        mod.getBehaviour().releasePaused(this);
        _level = mod.getBehaviour().push();
        _containerLookupCooldown = 0;
        //removeThrowawayItems(_itemTargets);
        if (_mineIfPresent != null) {
            mod.getBlockTracker().trackBlock(_mineIfPresent);
        }
        onResourceStart(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_allMatches == null) {
            _allMatches = ItemTarget.getMatches(_itemTargets);
            _reserve = reserveFor(_itemTargets);
        }
        // protected, but only up to the count we're after: the walk out of the hole may build with cobble above it
        Map<Item, Integer> held = reserveOrNone(_noReserve, _reserve);
        if (!held.isEmpty()) {
            mod.getBehaviour().reserveProtectedItems(_level, held);
        }
        // If we have an item in an INACCESSIBLE inventory slot
        if (!ITaskUsesCraftingGrid.isUsingGrid(this) || _ensureFreeCraftingGridTask.isActive()) {
            for (ItemTarget target : _itemTargets) {
                if (StorageHelper.isItemInaccessibleToContainer(mod, target)) {
                    setDebugState("Moving from SPECIAL inventory slot");
                    return new MoveInaccessibleItemToInventoryTask(target);
                }
            }
        }
        // the garbage swap puts our item in the bag and lifts junk onto the cursor. junk is not a target, so the check below
        // would let go right there and leave it hanging. stay with the cursor task until it has thrown it
        if (_cursorTask.isActive() && !_cursorTask.isFinished(mod)) {
            setDebugState("Moving from cursor");
            return _cursorTask;
        }
        // We have enough items COUNTING the cursor slot, we just need to move an item from our cursor.
        if (StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()))) {
            setDebugState("Moving from cursor");
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                return null;
            }
            // full bag. the old tail ended in "throw the cursor", and the cursor here is the thing we came for (the fresh
            // diamond pickaxe, say). the cursor task throws junk for room first, and gear or important stuff only goes when the
            // bag is all keepers
            return _cursorTask;
        }

        if (!shouldAvoidPickingUp(mod)) {
            // Check if items are on the floor. If so, pick em up.
            if (mod.getEntityTracker().itemDropped(_itemTargets)) {

                // If we're picking up a pickaxe (we can't go far underground or mine much)
                if (PickupDroppedItemTask.isIsGettingPickaxeFirst(mod)) {
                    if (_pickupTask.isCollectingPickaxeForThis()) {
                        setDebugState("Picking up (pickaxe first!)");
                        // Our pickup task is the one collecting the pickaxe, keep it going.
                        return _pickupTask;
                    }
                    // Only get items that are CLOSE to us.
                    Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), _itemTargets);
                    if (closest.isPresent() && !closest.get().closerThan(mod.getPlayer(), 10)) {
                        return onResourceTick(mod);
                    }
                }

                double range = Baritone.settings().altoResourcePickupDropRange.value;
                // the tracker remembers drops from anywhere, only the ones worth the walk count (a far drop of something
                // we can craft right now is not worth leaving the hole for). asking for the closest of the OK ones also
                // means the pickup task stops chasing once the near drops are gone
                Vec3 me = mod.getPlayer().position();
                boolean craftable = craftableFromHeld(mod);
                // a miner below us sweeps its own drops (tree first, then the floor). grabbing those from up here stopped it,
                // and its restart forgot the tree, so every cobble that fell was a new path search. only the items it collects
                // though: the sticks a dead bush drops are not the bush miner's business, those are still ours. the price:
                // while it runs, drops of our other targets lying around wait for their own turn
                Item[] owned = ownedBelow();
                // one at our feet goes in the bag by itself, a path search for it is a waste (same skip as MineAndCollectTask's nearestDrop)
                Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(me,
                        drop -> !contains(owned, drop.getItem().getItem()) && !landsByItself(mod, me, drop)
                                && WalkCost.dropWorthWalking(drop.getX() - me.x, drop.getY() - me.y, drop.getZ() - me.z, craftable), _itemTargets);
                if (closest.isPresent() && (range < 0 || closest.get().closerThan(mod.getPlayer(), range) || (_pickupTask.isActive() && !_pickupTask.isFinished(mod)))) {
                    setDebugState("Picking up");
                    return _pickupTask;
                }
            }
        }

        // Check for chests and grab resources from them.
        // no containers known is the common case, and the lookup walks every cached one, so ask once a second at most
        if (_currentContainer == null && --_containerLookupCooldown <= 0 && mod.getItemStorage().hasAnyContainers()) {
            _containerLookupCooldown = CONTAINER_LOOKUP_TICKS;
            List<ContainerCache> containersWithItem = mod.getItemStorage().getContainersWithItem(_allMatches).stream()
                    .filter(cache -> cache.getContainerType().lootable()).toList();
            if (!containersWithItem.isEmpty()) {
                ContainerCache closest = containersWithItem.stream().min(StlHelper.compareValues(container -> container.getBlockPos().distToCenterSqr(mod.getPlayer().position()))).get();
                if (closest.getBlockPos().closerToCenterThan(mod.getPlayer().position(), Baritone.settings().altoResourceChestLocateRange.value)) {
                    _currentContainer = closest;
                }
            }
        }
        if (_currentContainer != null) {
            Optional<ContainerCache> container = mod.getItemStorage().getContainerAtPosition(_currentContainer.getBlockPos());
            if (container.isPresent()) {
                if (Arrays.stream(_itemTargets).noneMatch(target -> container.get().hasItem(target.getMatches()))) {
                    _currentContainer = null;
                } else {
                    // We have a current chest, grab from it.
                    setDebugState("Picking up from container");
                    return new PickupFromContainerTask(_currentContainer.getBlockPos(), _itemTargets);
                }
            } else {
                _currentContainer = null;
            }
        }

        // We may just mine if a block is found.
        // a miner that already breaks all of these would just hand out a second miner for the same blocks, and the outer
        // one's tools, requirement and dirt trip never ran
        if (_mineIfPresent != null && !minesOnItsOwn(_mineIfPresent)) {
            ArrayList<Block> satisfiedReqs = new ArrayList<>(Arrays.asList(_mineIfPresent));
            satisfiedReqs.removeIf(block -> !StorageHelper.miningRequirementMet(mod, MiningRequirement.getMinimumRequirementForBlock(block)));
            if (!satisfiedReqs.isEmpty()) {
                if (mod.getBlockTracker().anyFound(satisfiedReqs.toArray(Block[]::new))) {
                    // only one we may break: a furnace with our iron cooking in it is on the "do not mine" list, MineAndCollectTask
                    // refuses it, and picking it here anyway flipped between that refusal and the craft every tick
                    Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(),
                            p -> WorldHelper.canBreak(mod, p), _mineIfPresent);
                    // -1 means always mine, and closerToCenterThan would call that "nothing is close enough"
                    double mineRange = Baritone.settings().altoResourceMineRange.value;
                    if (closest.isPresent() && (mineRange < 0 || closest.get().closerToCenterThan(mod.getPlayer().position(), mineRange))) {
                        _mineLastClosest = closest.get();
                    }
                    if (_mineLastClosest != null) {
                        if (mineRange < 0 || _mineLastClosest.closerToCenterThan(mod.getPlayer().position(), mineRange * 1.5 + 20)) {
                            return new MineAndCollectTask(_itemTargets, _mineIfPresent, MiningRequirement.HAND);
                        }
                    }
                }
            }
        }
        // Make sure that items don't get stuck in the player crafting grid. May be an issue if a future task isn't a resource task.
        if (StorageHelper.isPlayerInventoryOpen()) {
            boolean gridHasItems = false;
            for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                if (!StorageHelper.getItemStackInSlot(slot).isEmpty()) {
                    gridHasItems = true;
                    break;
                }
            }
            if (EnsureFreePlayerCraftingGridTask.shouldClear(ITaskUsesCraftingGrid.isUsingGrid(this),
                    _ensureFreeCraftingGridTask.isActive(), gridHasItems, !StorageHelper.getItemStackInCursorSlot().isEmpty())) {
                return _ensureFreeCraftingGridTask;
            }
        }
        return onResourceTick(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // a pause keeps the blazes (or whatever a subclass excluded) ours until we are back, a real stop lets go
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), _level);
        } else {
            mod.getBehaviour().releasePaused(this);
        }
        mod.getBehaviour().pop();
        _level = null;
        if (_mineIfPresent != null) {
            mod.getBlockTracker().stopTracking(_mineIfPresent);
        }
        onResourceStop(mod, interruptTask);
    }

    // stopped while a chain had us paused, so onStop already ran and handed the exclusions over. nobody else takes them back
    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof ResourceTask t) {
            if (!isEqualResource(t)) return false;
            return Arrays.equals(t._itemTargets, _itemTargets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append(toDebugStringName()).append(": [");
        int c = 0;
        if (_itemTargets != null) {
            for (ItemTarget target : _itemTargets) {
                result.append(target != null ? target.toString() : "(null)");
                if (++c != _itemTargets.length) {
                    result.append(", ");
                }
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected String toHudString() {
        // the verb is a guess at this level, subclasses that mine or craft say so
        return "Getting " + HudText.items(_itemTargets);
    }

    protected boolean isInWrongDimension(AltoClef mod) {
        if (_forceDimension) {
            return WorldHelper.getCurrentDimension() != _targetDimension;
        }
        return false;
    }

    protected Task getToCorrectDimensionTask(AltoClef mod) {
        return new DefaultGoToDimensionTask(_targetDimension);
    }

    public ResourceTask mineIfPresent(Block[] toMine) {
        _mineIfPresent = toMine;
        return this;
    }

    public ResourceTask forceDimension(Dimension dimension) {
        _forceDimension = true;
        _targetDimension = dimension;
        return this;
    }

    // we could make the thing we are after right now from what is in the bag, so a drop of it is only worth a short walk
    // (WalkCost.dropWorthWalking). crafting tasks say yes when the materials are held
    protected boolean craftableFromHeld(AltoClef mod) {
        return false;
    }

    // this task sweeps up its own drops, so the resource tasks above it keep their hands off them while it runs. not the
    // same thing as shouldAvoidPickingUp: crafts and smelts say yes to that and pick up nothing, their parents still sweep
    public boolean ownsDrops() {
        return false;
    }

    // a miner says yes when these are blocks it breaks anyway (see the mineIfPresent branch in onTick)
    protected boolean minesOnItsOwn(Block[] blocks) {
        return false;
    }

    private static final Item[] NOTHING_OWNED = new Item[0];

    // the items a running miner somewhere below us is sweeping up itself, empty when there is none
    private Item[] ownedBelow() {
        Task owner = ownerBelow(subTask(), t -> t instanceof ResourceTask r ? r.subTask() : null,
                t -> t instanceof ResourceTask && t.isActive(), t -> ((ResourceTask) t).ownsDrops());
        return owner == null ? NOTHING_OWNED : ItemTarget.getMatches(((ResourceTask) owner).getItemTargets());
    }

    // walks down from first while the links are running resource tasks, the first one that owns its drops (or null).
    // anything else in between (a pickup, a goto, a wander) is not ours to see through, whatever is under it was not
    // asked for by us
    static <T> T ownerBelow(T first, UnaryOperator<T> next, Predicate<T> walkable, Predicate<T> owns) {
        // a chain this deep is a bug, not a reason to hang the tick
        int depth = 0;
        for (T t = first; t != null && depth < 32; t = next.apply(t), depth++) {
            if (!walkable.test(t)) {
                return null;
            }
            if (owns.test(t)) {
                return t;
            }
        }
        return null;
    }

    static <T> boolean contains(T[] in, T x) {
        for (T t : in) {
            if (t == x || (t != null && t.equals(x))) {
                return true;
            }
        }
        return false;
    }

    // every one of need is in have. tiny arrays, a set would cost more than it saves
    protected static <T> boolean coversAll(T[] need, T[] have) {
        for (T n : need) {
            if (!contains(have, n)) {
                return false;
            }
        }
        return true;
    }

    // vanilla picks this one up in a few ticks without us moving, as long as there is somewhere to put it (a full bag needs
    // the pickup task, it frees a slot)
    private static boolean landsByItself(AltoClef mod, Vec3 me, ItemEntity drop) {
        return DropPatience.alreadyInReach(drop.getX() - me.x, drop.getY() - me.y, drop.getZ() - me.z)
                && !mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(drop.getItem(), false).isEmpty();
    }

    protected abstract boolean shouldAvoidPickingUp(AltoClef mod);

    protected abstract void onResourceStart(AltoClef mod);

    protected abstract Task onResourceTick(AltoClef mod);

    protected abstract void onResourceStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqualResource(ResourceTask other);

    protected abstract String toDebugStringName();

    public ItemTarget[] getItemTargets() {
        return _itemTargets;
    }
}
