package adris.altoclef.tasks.container;

import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.BlastFurnaceSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a blast furnace, placing a blast furnace and collecting fuel as needed.
 */
public class SmeltInBlastFurnaceTask extends ResourceTask {

    private final SmeltTarget[] _targets;

    private final DoSmeltInBlastFurnaceTask _doTask;

    public SmeltInBlastFurnaceTask(SmeltTarget[] targets) {
        this(targets, null);
    }

    // existing != null means use that blast furnace and never craft or place one, see canMakeNew below
    public SmeltInBlastFurnaceTask(SmeltTarget[] targets, BlockPos existing) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInBlastFurnaceTask(targets[0], existing);
    }

    public SmeltInBlastFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    public SmeltInBlastFurnaceTask(SmeltTarget target, BlockPos existing) {
        this(new SmeltTarget[]{target}, existing);
    }

    // has anything gone in the blast furnace (as far as the last open screen showed us)
    public boolean hasStartedSmelting() {
        return _doTask.hasStartedSmelting();
    }

    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        _doTask.ignoreMaterials();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.BLAST_FURNACE);
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Optional<BlockPos> blastFurnacePos = mod.getBlockTracker().getNearestTracking(Blocks.BLAST_FURNACE);
        blastFurnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.BLAST_FURNACE);
        mod.getBehaviour().pop();
        // Close blast furnace screen
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) || _doTask.isFinished(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInBlastFurnaceTask task) {
            return task._doTask.isEqual(_doTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _doTask.toDebugString();
    }

    @Override
    protected String toHudString() {
        return _doTask.getHudName();
    }

    public SmeltTarget[] getTargets() {
        return _targets;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    static class DoSmeltInBlastFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final BlastFurnaceCache _blastFurnaceCache = new BlastFurnaceCache();
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;
        // non null: the one blast furnace we were sent to, and we never make our own
        private final BlockPos _existing;
        // async smelting: everything is in the blast furnace and we walked away, this task is done and must not walk back
        private boolean _loaded;
        // the slot is full of one fuel, the bag has only another, see stuckShort
        private final FuelShortage _cannotAdd = new FuelShortage();

        public DoSmeltInBlastFurnaceTask(SmeltTarget target, BlockPos existing) {
            super(Blocks.BLAST_FURNACE, new ItemTarget(Items.BLAST_FURNACE));
            _existing = existing;
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInBlastFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials && Objects.equals(task._existing, _existing);
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof BlastFurnaceMenu);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _loaded;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (_loaded) {
                return null;
            }
            mod.getBehaviour().addProtectedItems(ItemHelper.PLANKS);
            mod.getBehaviour().addProtectedItems(Items.COAL);
            mod.getBehaviour().addProtectedItems(_allMaterials.getMatches());
            mod.getBehaviour().addProtectedItems(_target.getMaterial().getMatches());
            tryUpdateOpenBlastFurnace(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (materialTarget.matches(_blastFurnaceCache.materialSlot.getItem()) ? _blastFurnaceCache.materialSlot.getCount() : 0)
                    - (outputTarget.matches(_blastFurnaceCache.outputSlot.getItem()) ? _blastFurnaceCache.outputSlot.getCount() : 0);
            double totalFuelInBlastFurnace = ItemHelper.getFuelAmount(_blastFurnaceCache.fuelSlot) + _blastFurnaceCache.burningFuelCount + _blastFurnaceCache.burnPercentage;
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            // the fuel already in the blast furnace comes off in both modes, same fix as the smoker
            double fuelNeeded = FuelShortage.needed(_ignoreMaterials,
                    materialTarget.matches(_blastFurnaceCache.materialSlot.getItem()) ? _blastFurnaceCache.materialSlot.getCount() : 0,
                    materialTarget.getTargetCount(), mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches()),
                    outputTarget.matches(_blastFurnaceCache.outputSlot.getItem()) ? _blastFurnaceCache.outputSlot.getCount() : 0, totalFuelInBlastFurnace);

            // We don't have enough materials...
            if (mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                // the shortfall, not the whole target, same as the furnace
                return getMaterialTask(new ItemTarget(_target.getMaterial(), materialsNeeded));
            }

            // We don't have enough fuel...
            if (_blastFurnaceCache.burningFuelCount <= 0 && StorageHelper.calculateInventoryFuelCount(mod) < fuelNeeded) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(fuelNeeded);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick(mod);
        }

        // Override this if our materials must be acquired in a special way.
        // virtual
        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            // the station pickup reads this, it must not break a table under a half done load
            AsyncSmelting.working(mod.getWorld().getGameTime());
            // We have appropriate materials/fuel.
            /*
             * - If output slot has something, receive it.
             * - Calculate needed material input. If we don't have, put it in.
             * - Calculate needed fuel input. If we don't have, put it in.
             * - Wait lol
             */
            ItemStack output = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.INPUT_SLOT_FUEL);

            // Receive from output if present
            double currentlyCachedWhileCooking = StorageHelper.getBlastFurnaceFuel() + StorageHelper.getBlastFurnaceCookPercent();
            double needsWhileCooking = material.getCount() - currentlyCachedWhileCooking;
            if (needsWhileCooking <= 0) {
                if (!fuel.isEmpty()) {
                    ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                    if (!ItemHelper.canStackTogether(fuel, cursor)) {
                        Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                        if (toFit.isPresent()) {
                            mod.getSlotHandler().clickSlot(toFit.get(), 0, ContainerInput.PICKUP);
                            return null;
                        } else {
                            // Eh screw it
                            if (ItemHelper.canDropStack(mod, cursor)) {
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                                return null;
                            }
                        }
                    }
                    mod.getSlotHandler().clickSlot(BlastFurnaceSlot.INPUT_SLOT_FUEL, 0, ContainerInput.PICKUP);
                    return null;
                }
            }
            if (!output.isEmpty()) {
                setDebugState("Receiving Output");
                // Ensure our cursor is empty/can receive our item
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                if (!ItemHelper.canStackTogether(output, cursor)) {
                    Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                    if (toFit.isPresent()) {
                        mod.getSlotHandler().clickSlot(toFit.get(), 0, ContainerInput.PICKUP);
                        return null;
                    } else {
                        // Eh screw it
                        if (ItemHelper.canDropStack(mod, cursor)) {
                            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                            return null;
                        }
                    }
                }
                // Pick up
                mod.getSlotHandler().clickSlot(BlastFurnaceSlot.OUTPUT_SLOT, 0, ContainerInput.PICKUP);
                return null;
                // return new MoveItemToSlotTask(new ItemTarget(output.getItem(), output.getCount()), toMoveTo.get(), mod -> FurnaceSlot.OUTPUT_SLOT);
            }

            // Fill in input if needed
            // Materials needed in slot = (mat_target - out_in_inventory - out_in_furnace)
            ItemTarget materialTarget = _allMaterials;

            int neededMaterialsInSlot = materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(_target.getItem().getMatches())
                    - (_target.getItem().matches(output.getItem()) ? output.getCount() : 0);
            // We don't have the right material or we need more
            if (!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount()) {
                int materialsAlreadyIn = (materialTarget.matches(material.getItem()) ? material.getCount() : 0);
                setDebugState("Moving Materials");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), BlastFurnaceSlot.INPUT_SLOT_MATERIALS);
            }

            /*
            double currentFuel = _ignoreMaterials
                    ? (Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                    : materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;
             */
            // Fill in fuel if needed. the slot counts toward the job and what is in it stays (FuelPolicy.chooseFor), FuelPolicy also
            // keeps the wood the run still has plans for. lit is /100 like the smoker's (StorageHelper.litItems)
            double lit = StorageHelper.getBlastFurnaceFuel();
            double progress = StorageHelper.getBlastFurnaceCookPercent();
            var bag = mod.getItemStorage().getItemStacksPlayerInventory(true);
            var pick = FuelShortage.fill(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
            if (pick != null) {
                setDebugState("Filling fuel");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(pick.stack().getItem(), pick.count()), BlastFurnaceSlot.INPUT_SLOT_FUEL);
            }

            // a slot full of one fuel with only another in the bag: swap it out when that covers the whole job alone, otherwise leave
            // short and the job is due when the fuel is (the story is in the furnace task, AsyncSmelting.cookable)
            BlockPos at = getTargetContainerPosition();
            double slotFuel = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
            boolean covered = AsyncSmelting.fuelCovers(slotFuel, lit, progress, material.getCount());
            boolean stuck = _cannotAdd.confirmed(FuelShortage.stuckShort(material.getCount(), lit, progress, slotFuel,
                    StorageHelper.calculateInventoryFuelCount(mod)), mod.getWorld().getGameTime());
            var swap = stuck ? FuelShortage.swap(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount) : null;
            if (swap != null && AsyncSmelting.emptyFuelSlot(mod, BlastFurnaceSlot.INPUT_SLOT_FUEL)) {
                // the slot comes empty first, the fill above puts the covering fuel in next tick (AsyncSmelting.emptyFuelSlot)
                setDebugState("Taking the slot's fuel out for one that covers the job");
                return null;
            }
            // what came out of the slot goes back in the bag first
            if (AsyncSmelting.clearCursor(mod)) {
                return null;
            }

            // loaded: the cook does not need us. the screen closes and whoever asked comes back later
            if (at != null && !material.isEmpty() && AsyncSmelting.wants(_target.getItem()) && (covered || stuck)) {
                setDebugState(covered ? "Loaded, leaving it to cook" : "Slot is as full as it gets, leaving it to cook");
                AsyncSmelting.loaded(mod, at, Blocks.BLAST_FURNACE, material, _target.getItem(),
                        AsyncSmelting.cookable(slotFuel, lit, progress, material.getCount()));
                _loaded = true;
                return null;
            }
            setDebugState("Waiting...");
            return null;
        }

        // stay on the one we are walking to. before we have one, go to the one we were sent to instead of whichever is nearest
        // (they're the same unless two are close, but the router picked THIS one)
        @Override
        protected BlockPos pinnedStation(AltoClef mod) {
            BlockPos kept = getTargetContainerPosition();
            return kept != null ? kept : _existing;
        }

        // a blast furnace is 5 iron, a furnace and three smooth stone, so a tracked one is worth the walk from anywhere. the old
        // cost function said so by accident (it compared the cache slots to null, which they never are) and plain alto leans on it
        @Override
        protected double worldReach() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        protected boolean canMakeNew(AltoClef mod) {
            return _existing == null;
        }

        // the caches start as EMPTY stacks and only change while the screen is open, so anything in them means we
        // really did put stuff in (or take stuff out of) a blast furnace
        public boolean hasStartedSmelting() {
            return !_blastFurnaceCache.materialSlot.isEmpty() || !_blastFurnaceCache.fuelSlot.isEmpty()
                    || !_blastFurnaceCache.outputSlot.isEmpty() || _blastFurnaceCache.burningFuelCount > 0;
        }

        private void tryUpdateOpenBlastFurnace(AltoClef mod) {
            if (isContainerOpen(mod)) {
                // Update current furnace cache
                _blastFurnaceCache.burnPercentage = StorageHelper.getBlastFurnaceCookPercent();
                _blastFurnaceCache.burningFuelCount = StorageHelper.getBlastFurnaceFuel();
                _blastFurnaceCache.fuelSlot = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.INPUT_SLOT_FUEL);
                _blastFurnaceCache.materialSlot = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.INPUT_SLOT_MATERIALS);
                _blastFurnaceCache.outputSlot = StorageHelper.getItemStackInSlot(BlastFurnaceSlot.OUTPUT_SLOT);
            }
        }

        @Override
        protected String toHudString() {
            return "Smelting " + HudText.items(_target.getItem());
        }

    }

    static class BlastFurnaceCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
