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
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmokerSlot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a smoker, placing a smoker and collecting fuel as needed.
 */
public class SmeltInSmokerTask extends ResourceTask implements AsyncSmelting.Handoff {

    private final SmeltTarget[] _targets;

    private final DoSmeltInSmokerTask _doTask;

    public SmeltInSmokerTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        boolean ignoreMaterials = false;
        _doTask = new DoSmeltInSmokerTask(targets[0], ignoreMaterials);
    }

    public SmeltInSmokerTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
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

    // only what is in the bag gets cooked. the target is fixed when the task is made, so one cooked chop eaten mid batch reads as
    // meat we are short of, and "go get it" walked off to hunt with the rest of the batch still in the smoker
    public void keepToTheBag() {
        _doTask._keepToTheBag = true;
    }

    // the last look at the screen had meat in the slots. coal alone doesn't count, that one has nothing to come back to
    public boolean holdsMeat() {
        return !_doTask._smokerCache.materialSlot.isEmpty() || !_doTask._smokerCache.outputSlot.isEmpty();
    }

    // short of fuel with nowhere to get it (see canFetchFuel)
    public boolean outOfFuel() {
        return _doTask._outOfFuel;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.SMOKER);
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Optional<BlockPos> smokerPos = mod.getBlockTracker().getNearestTracking(Blocks.SMOKER);
        smokerPos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.SMOKER);
        mod.getBehaviour().pop();
        // Close smoker screen
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
    public boolean handedOff() {
        return _doTask._loaded;
    }

    @Override
    public boolean recordLeftBehind(AltoClef mod) {
        return _doTask.recordStranded(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInSmokerTask task) {
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
    static class DoSmeltInSmokerTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final SmokerCache _smokerCache = new SmokerCache();
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;
        private boolean _keepToTheBag;
        // keepToTheBag: slots and bag both empty with the target still short, because some of it got eaten. the target never fills
        private boolean _batchOver;
        private boolean _outOfFuel;
        // async cooking: everything is in the smoker and we walked away, this task is done and must not walk back to it
        private boolean _loaded;
        // recordStranded left a job for what the screen last showed: once per look, a second stop (an interrupt, then the end) must not
        // queue it again. the screen opening again is a new look
        private boolean _recorded;
        // the outer check (bag short of what the smoker still needs) and the one inside the open screen, see FuelShortage
        private final FuelShortage _fuelShort = new FuelShortage();
        private final FuelShortage _dryInside = new FuelShortage();
        // the slot is full of one fuel, the bag has only another, see stuckShort
        private final FuelShortage _cannotAdd = new FuelShortage();

        public DoSmeltInSmokerTask(SmeltTarget target, boolean ignoreMaterials) {
            super(Blocks.SMOKER, new ItemTarget(Items.SMOKER));
            _target = target;
            _ignoreMaterials = ignoreMaterials;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInSmokerTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof SmokerMenu);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _loaded || _batchOver;
        }

        // CollectFuelTask out of the overworld is a walk home, and a cook that keeps to the bag is here so we don't have to do that.
        // the cook gets told instead, and it gives up the normal way
        private boolean canFetchFuel() {
            return !_keepToTheBag || WorldHelper.getCurrentDimension() == Dimension.OVERWORLD;
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
            tryUpdateOpenSmoker(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - materialsKnownInSmoker(mod)
                    - (outputTarget.matches(_smokerCache.outputSlot.getItem()) ? _smokerCache.outputSlot.getCount() : 0);
            double totalFuelInSmoker = ItemHelper.getFuelAmount(_smokerCache.fuelSlot) + _smokerCache.burningFuelCount + _smokerCache.burnPercentage
                    + fuelKnownInSmoker(mod);
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            // the fuel already in the smoker comes off in both modes. the cook mode (ignoreMaterials) used to skip it, so the coal
            // that had just moved into the slot (not lit yet, bag empty) still read as the whole batch short: "Getting Fuel" the
            // tick after "Filling fuel", twice in the live log, and a full batch of coal asked for with the slot already covering it
            // the cook mode fuels what is in the slot, and before the first load the slot is empty: the meat still in the bag counts
            // too, or the fuel was only missed after the walk and the load (and the coal trip ran with the meat already in)
            int meatToLoad = _ignoreMaterials ? mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) : 0;
            double fuelNeeded = FuelShortage.needed(_ignoreMaterials,
                    (materialTarget.matches(_smokerCache.materialSlot.getItem()) ? _smokerCache.materialSlot.getCount() : 0) + meatToLoad,
                    materialTarget.getTargetCount(), mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches()),
                    outputTarget.matches(_smokerCache.outputSlot.getItem()) ? _smokerCache.outputSlot.getCount() : 0, totalFuelInSmoker);

            // We don't have enough materials...
            if (!_keepToTheBag && mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                // the shortfall, not the whole target, same as the furnace
                return getMaterialTask(new ItemTarget(_target.getMaterial(), materialsNeeded));
            }

            // We don't have enough fuel...
            // not asked while our own smoker is being made: a smoker is a furnace and four logs, and logs are fuel. with them out of
            // the bag for the craft it reads short, the fuel trip cancels the craft, the logs come back, and round it goes for ever.
            // the question waits for the smoker to land in the bag
            if (!makingContainer()) {
                double bagFuel = StorageHelper.calculateInventoryFuelCount(mod);
                double trip = _dryInside.fetchTarget(bagFuel);
                if (trip > 0) {
                    if (!canFetchFuel()) {
                        setDebugState("Out of fuel and none to find here");
                        _outOfFuel = true;
                        return null;
                    }
                    setDebugState("Getting Fuel");
                    return new CollectFuelTask(trip);
                }
                boolean lacking = _smokerCache.burningFuelCount <= 0 && bagFuel < fuelNeeded;
                // can't fetch: this one is a guess from outside (a new smelt has seen no fire yet), so go and look. the check inside the
                // open screen reads the real slots and lands in the branch above
                if (_fuelShort.confirmed(lacking, mod.getWorld().getGameTime()) && canFetchFuel()) {
                    setDebugState("Getting Fuel");
                    // the exact shortfall, no + 1: the go-back test above and CollectFuelTask's finish test are the same number now, so
                    // there is nothing to round up for. 8 items is one coal, not two
                    return new CollectFuelTask(fuelNeeded);
                }
            } else {
                // and it starts from zero afterwards, a clock started on a half crafted bag is not worth keeping
                _fuelShort.reset();
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick(mod);
        }

        // dropped with the meat in the smoker and no job (the coal trip outlasted the cook's patience): leave the job for the
        // gamer so somebody goes back and takes it out. the cache is the last look at the slots, the visit reads the real ones
        boolean recordStranded(AltoClef mod) {
            if (_recorded) {
                return false;
            }
            // the screen may still be open with the cache a tick behind it: the real slots win
            tryUpdateOpenSmoker(mod);
            BlockPos at = getTargetContainerPosition();
            ItemStack material = _smokerCache.materialSlot;
            double slotFuel = _smokerCache.fuelSlot.isEmpty() ? 0 : ItemHelper.getFuelAmount(_smokerCache.fuelSlot);
            double burning = _smokerCache.burningFuelCount;
            if (material.isEmpty() && !isContainerOpen(mod)) {
                StationMemory.Look look = StationMemory.restartedLook(mod, at != null ? at : stationInUse(mod), _allMaterials);
                if (look != null) {
                    at = look.at();
                    material = look.material();
                    slotFuel = look.slotFuel();
                    burning = look.burning();
                }
            }
            if (_loaded || at == null || material.isEmpty() || !AsyncSmelting.wants(_target.getItem())) {
                return false;
            }
            // lit with enough in it for all of it is a cook, anything less is meat sitting cold (the 5 beef that were never lit and
            // still got a "~25 s" timer, and a stand-by that walked up and took them back out)
            boolean lit = AsyncSmelting.fuelCovers(slotFuel, burning, 0, material.getCount());
            Debug.logInternal("smoker at " + at.toShortString() + " still holds " + material.getCount() + " of our meat and the cook is leaving, "
                    + "recording it so it gets picked up (" + (lit ? "lit" : "not lit") + ")");
            AsyncSmelting.leftBehind(mod, at, Blocks.SMOKER, material, _target.getItem(), lit);
            _recorded = true;
            return true;
        }

        // what the screen showed, or when this task never had it open (an interrupt restarts us with empty caches) what the
        // container tracker saw last time. same story as the furnace, see StationMemory
        private int materialsKnownInSmoker(AltoClef mod) {
            int shown = _allMaterials.matches(_smokerCache.materialSlot.getItem()) ? _smokerCache.materialSlot.getCount() : 0;
            if (shown > 0 || isContainerOpen(mod)) {
                return shown;
            }
            return (int) StationMemory.known(false, shown, StationMemory.materialsRemembered(mod, stationInUse(mod), _allMaterials));
        }

        private double fuelKnownInSmoker(AltoClef mod) {
            boolean seen = !_smokerCache.fuelSlot.isEmpty() || _smokerCache.burningFuelCount > 0;
            if (seen || isContainerOpen(mod)) {
                return 0;
            }
            return StationMemory.fuelRemembered(mod, stationInUse(mod), _allMaterials);
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
            ItemStack output = StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_FUEL);

            // Receive from output if present
            double currentlyCachedWhileCooking = StorageHelper.getSmokerFuel() + StorageHelper.getSmokerCookPercent();
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
                    mod.getSlotHandler().clickSlot(SmokerSlot.INPUT_SLOT_FUEL, 0, ContainerInput.PICKUP);
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
                mod.getSlotHandler().clickSlot(SmokerSlot.OUTPUT_SLOT, 0, ContainerInput.PICKUP);
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
            // the bag has none left to add, so asking for more only stands in front of the fuel below. the cursor counts: a whole
            // stack on its way to the slot is not in the bag for the one tick it takes
            boolean bagDry = _keepToTheBag && mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) == 0
                    && !materialTarget.matches(StorageHelper.getItemStackInCursorSlot().getItem());
            // the cursor has to be clear as well, the last cooked one rides it on the way to the bag. and the menu has to have heard from
            // the server: a screen just opened shows every slot empty for a tick, with the whole batch sitting in there
            if (bagDry && material.isEmpty() && StorageHelper.getItemStackInCursorSlot().isEmpty() && mod.getPlayer().containerMenu.getStateId() > 0) {
                _batchOver = true;
                return null;
            }
            if (!bagDry && (!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount())) {
                int materialsAlreadyIn = (materialTarget.matches(material.getItem()) ? material.getCount() : 0);
                setDebugState("Moving Materials");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), SmokerSlot.INPUT_SLOT_MATERIALS);
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
            // keeps the wood the run still has plans for. the smoker's lit reading is /100 (StorageHelper.litItems), so a coal is 8
            // here like it is in the furnace
            double lit = StorageHelper.getSmokerFuel();
            double progress = StorageHelper.getSmokerCookPercent();
            double slotFuel = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
            var bag = mod.getItemStorage().getItemStacksPlayerInventory(true);
            var pick = FuelShortage.fill(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
            if (pick != null) {
                setDebugState("Filling fuel");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(pick.stack().getItem(), pick.count()), SmokerSlot.INPUT_SLOT_FUEL);
            }

            // nothing in the bag may burn and the smoker is short: standing here saying "Waiting..." never ends (the burning check
            // out in onTick only fires with nothing lit, and a smoker with a bit of fire left is lit). shut the screen and go get
            // it, and the trip is not talked out of by a lit reading from the last look (FuelShortage.fetchUntil)
            double bagFuel = StorageHelper.calculateInventoryFuelCount(mod);
            long now = mod.getWorld().getGameTime();
            if (_dryInside.confirmed(FuelShortage.dry(material.getCount(), lit, progress, slotFuel, bagFuel), now)) {
                setDebugState("Out of fuel, going to get some");
                _dryInside.fetchUntil(FuelShortage.missing(material.getCount(), lit, progress, slotFuel));
                _dryInside.reset();
                StorageHelper.closeScreen();
                return null;
            }

            // a slot full of one fuel with only another in the bag: swap it out when that covers the whole job alone, otherwise leave
            // short (the story is in the furnace task)
            BlockPos at = getTargetContainerPosition();
            boolean covered = AsyncSmelting.fuelCovers(slotFuel, lit, progress, material.getCount());
            boolean stuck = _cannotAdd.confirmed(FuelShortage.stuckShort(material.getCount(), lit, progress, slotFuel, bagFuel), now);
            var swap = stuck ? FuelShortage.swap(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount) : null;
            if (swap != null && AsyncSmelting.emptyFuelSlot(mod, SmokerSlot.INPUT_SLOT_FUEL)) {
                // the slot comes empty first, the fill above puts the covering fuel in next tick (AsyncSmelting.emptyFuelSlot)
                setDebugState("Taking the slot's fuel out for one that covers the job");
                return null;
            }
            // what came out of the slot goes back in the bag first
            if (AsyncSmelting.clearCursor(mod)) {
                return null;
            }

            // loaded: 35 seconds of mutton does not need us staring at the gui. the screen closes and the gamer comes back for it (the
            // same trick as the furnace, AsyncSmelting has the story, and so does the slot that is as full as it gets)
            if (at != null && !material.isEmpty() && AsyncSmelting.wants(_target.getItem()) && (covered || stuck)) {
                setDebugState(covered ? "Loaded, leaving it to cook" : "Slot is as full as it gets, leaving it to cook");
                AsyncSmelting.loaded(mod, at, Blocks.SMOKER, material, _target.getItem(),
                        AsyncSmelting.cookable(slotFuel, lit, progress, material.getCount()));
                _loaded = true;
                return null;
            }
            setDebugState("Waiting...");
            return null;
        }

        // the caches start as EMPTY stacks and only change while the screen is open, so anything in them means we really did
        // put stuff in (or take stuff out of) a smoker
        public boolean hasStartedSmelting() {
            return !_smokerCache.materialSlot.isEmpty() || !_smokerCache.fuelSlot.isEmpty()
                    || !_smokerCache.outputSlot.isEmpty() || _smokerCache.burningFuelCount > 0;
        }

        // same as the furnace: our meat in it pins it, from any distance, and a new smoker never goes down over it
        @Override
        protected BlockPos pinnedStation(AltoClef mod) {
            BlockPos kept = getTargetContainerPosition();
            if (kept != null && (hasStartedSmelting() || _smokerCache.burnPercentage > 0 || StationMemory.holdsOurStuff(mod, kept))) {
                return kept;
            }
            return StationMemory.ourLoaded(mod, Blocks.SMOKER);
        }

        private void tryUpdateOpenSmoker(AltoClef mod) {
            if (isContainerOpen(mod)) {
                _recorded = false;
                // Update current furnace cache
                _smokerCache.burnPercentage = StorageHelper.getSmokerCookPercent();
                _smokerCache.burningFuelCount = StorageHelper.getSmokerFuel();
                _smokerCache.fuelSlot = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_FUEL);
                _smokerCache.materialSlot = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS);
                _smokerCache.outputSlot = StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT);
            }
        }

        @Override
        protected String toHudString() {
            return "Cooking " + HudText.items(_target.getItem());
        }

    }

    static class SmokerCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
