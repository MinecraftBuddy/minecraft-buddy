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
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a furnace, placing a furnace and collecting fuel as needed.
 */
public class SmeltInFurnaceTask extends ResourceTask implements AsyncSmelting.Handoff {
    private final SmeltTarget[] _targets;

    private final DoSmeltInFurnaceTask _doTask;

    public SmeltInFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInFurnaceTask(targets[0]);
    }

    public SmeltInFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    // one load of a split batch (SmeltSplit): the target stays the whole need, so the bag filling up from another furnace's collect
    // doesn't read as this load being done, and at most `cap` goes in. it never goes into a furnace that is already busy with
    // ours, and is not pinned to one (StationMemory.ourLoaded would send load 2 straight back into load 1's furnace)
    // `index` is which load this is: 13/12/12's last two have the same cap, and without it load 3 was "equal" to the finished load 2,
    // so the task system kept ticking the finished one and the batch sat there with 12 raw iron in the bag
    public static SmeltInFurnaceTask splitLoad(SmeltTarget target, SmeltSplit.Batch batch, int index, int cap) {
        SmeltInFurnaceTask task = new SmeltInFurnaceTask(target);
        task._doTask._split = batch;
        task._doTask._loadIndex = index;
        task._doTask._loadCap = Math.max(1, cap);
        return task;
    }

    // the furnace this load has started putting things in, null before it has
    public BlockPos loadingAt() {
        return _doTask.hasStartedSmelting() ? _doTask.getTargetContainerPosition() : null;
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

    // has anything gone in the furnace (as far as the last open screen showed us)
    public boolean hasStartedSmelting() {
        return _doTask.hasStartedSmelting();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.FURNACE);
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Optional<BlockPos> furnacePos = mod.getBlockTracker().getNearestTracking(Blocks.FURNACE);
        furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.FURNACE);
        mod.getBehaviour().pop();
        // Close furnace screen
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
        if (other instanceof SmeltInFurnaceTask task) {
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
    static class DoSmeltInFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final FurnaceCache _furnaceCache = new FurnaceCache();
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;
        // async smelting: everything is in the furnace and we walked away, this task is done and must not walk back to it
        private boolean _loaded;
        // recordStranded left a job for what the screen last showed: once per look, a second stop (an interrupt, then the end) must not
        // queue it again. the screen opening again is a new look
        private boolean _recorded;
        // the outer check (bag short of what the furnace still needs) and the one inside the open screen, see FuelShortage
        private final FuelShortage _fuelShort = new FuelShortage();
        private final FuelShortage _dryInside = new FuelShortage();
        // the slot is full of one fuel, the bag has only another, see stuckShort
        private final FuelShortage _cannotAdd = new FuelShortage();
        // a split load (SmeltInFurnaceTask.splitLoad): the batch it belongs to and how much of the need goes in this furnace
        private SmeltSplit.Batch _split;
        private int _loadCap = Integer.MAX_VALUE;
        private int _loadIndex = -1;

        public DoSmeltInFurnaceTask(SmeltTarget target) {
            super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials
                        && sameLoad(task._split, task._loadIndex, task._loadCap, _split, _loadIndex, _loadCap);
            }
            return false;
        }

        // two split loads are one task only if they are the same load of the same batch (see splitLoad, the index is what tells
        // 13/12/12's last two apart)
        static boolean sameLoad(SmeltSplit.Batch a, int indexA, int capA, SmeltSplit.Batch b, int indexB, int capB) {
            return a == b && indexA == indexB && capA == capB;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof FurnaceMenu);
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
            tryUpdateOpenFurnace(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int outInBag = mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches());
            int outInSlot = outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0;
            // what this furnace still has to make: the target less every other furnace's load (a split batch has two or three
            // cooking) and capped at one load for a split. the station itself is read off its slots, so it comes off below
            int owed = owedHere(mod, stationHere(mod), outInBag, outInSlot);
            int materialsNeeded = owed - materialsKnownInFurnace(mod);
            double totalFuelInFurnace = ItemHelper.getFuelAmount(_furnaceCache.fuelSlot) + _furnaceCache.burningFuelCount + _furnaceCache.burnPercentage
                    + fuelKnownInFurnace(mod);
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            // the fuel already in the furnace comes off in both modes (the cook mode skipped it, see the smoker: coal in the slot, not
            // lit yet, read as the whole batch short)
            // the cook mode fuels what is in the slot, and before the first load the slot is empty: the meat still in the bag counts
            // too, or the fuel was only missed after the walk and the load (see the smoker)
            int meatToLoad = _ignoreMaterials ? mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) : 0;
            double fuelNeeded = FuelShortage.needed(_ignoreMaterials,
                    (materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0) + meatToLoad,
                    _ignoreMaterials ? materialTarget.getTargetCount() : owed + outInBag + outInSlot, outInBag, outInSlot, totalFuelInFurnace);

            // We don't have enough materials...
            if (mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                // fetch the shortfall, not the whole target: with 37 already cooking and 40 owed that is 3 ore, and asking for 40
                // went back down the mine for another 37 the furnace was already doing
                return getMaterialTask(new ItemTarget(_target.getMaterial(), materialsNeeded));
            }

            // We don't have enough fuel...
            double bagFuel = StorageHelper.calculateInventoryFuelCount(mod);
            double trip = _dryInside.fetchTarget(bagFuel);
            if (trip > 0) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(trip);
            }
            boolean lacking = _furnaceCache.burningFuelCount <= 0 && bagFuel < fuelNeeded;
            if (_fuelShort.confirmed(lacking, mod.getWorld().getGameTime())) {
                setDebugState("Getting Fuel");
                // the exact shortfall, no + 1: the go-back test above and CollectFuelTask's finish test are the same number now
                return new CollectFuelTask(fuelNeeded);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick(mod);
        }

        // dropped with the input in the furnace and no job (the fuel trip outlasted the cook's patience): leave the job for the
        // gamer so somebody goes back and takes it out. the cache is the last look at the slots, the visit reads the real ones
        boolean recordStranded(AltoClef mod) {
            if (_recorded) {
                return false;
            }
            tryUpdateOpenFurnace(mod);
            BlockPos at = getTargetContainerPosition();
            ItemStack material = _furnaceCache.materialSlot;
            double slotFuel = _furnaceCache.fuelSlot.isEmpty() ? 0 : ItemHelper.getFuelAmount(_furnaceCache.fuelSlot);
            double burning = _furnaceCache.burningFuelCount;
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
            // same split as the smoker: only a lit one with enough in it is a cook
            boolean lit = AsyncSmelting.fuelCovers(slotFuel, burning, 0, material.getCount());
            Debug.logInternal("furnace at " + at.toShortString() + " still holds " + material.getCount() + " of our input and the cook is leaving, "
                    + "recording it so it gets picked up (" + (lit ? "lit" : "not lit") + ")");
            AsyncSmelting.leftBehind(mod, at, Blocks.FURNACE, material, _target.getItem(), lit);
            _recorded = true;
            return true;
        }

        // the target less what is out already, less what the other furnaces' jobs are cooking for it, at most one load. only iron
        // has jobs worth counting here (the meat's are the food task's business). `here` is the station we read off its own slots,
        // its job would count twice. plain alto has no jobs and gets the old sum
        private int owedHere(AltoClef mod, BlockPos here, int outInBag, int outInSlot) {
            int elsewhere = AsyncSmelting.countsElsewhere(_target.getItem()) ? AsyncSmelting.pendingOutput("iron_ingot", here) : 0;
            return owed(_allMaterials.getTargetCount(), elsewhere, outInBag, outInSlot, _loadCap);
        }

        static int owed(int target, int elsewhere, int outInBag, int outInSlot, int cap) {
            long owed = (long) target - elsewhere - outInBag - outInSlot;
            return (int) Math.max(Integer.MIN_VALUE, Math.min(owed, cap));
        }

        // the block this task reads off its slots right now: the open one, else the one the choice walks to
        private BlockPos stationHere(AltoClef mod) {
            return isContainerOpen(mod) ? getTargetContainerPosition() : stationInUse(mod);
        }

        // what the screen showed, or when this task never had it open (an interrupt restarts us and the cache is empty) what the
        // container tracker saw last time. without that a restart read the ore we had loaded as "no materials", and went
        // mining three more. a stale number is fine, the real slots decide once the screen is open
        private int materialsKnownInFurnace(AltoClef mod) {
            int shown = _allMaterials.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0;
            if (shown > 0 || isContainerOpen(mod)) {
                return shown;
            }
            return (int) StationMemory.known(false, shown, StationMemory.materialsRemembered(mod, stationInUse(mod), _allMaterials));
        }

        // same for the fuel: the coal we had already put in the fuel slot when the load got cut off is not coal to go and mine
        private double fuelKnownInFurnace(AltoClef mod) {
            boolean seen = !_furnaceCache.fuelSlot.isEmpty() || _furnaceCache.burningFuelCount > 0;
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
            ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);

            // Receive from output if present
            double currentlyCachedWhileCooking = StorageHelper.getFurnaceFuel() + StorageHelper.getFurnaceCookPercent();
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
                    mod.getSlotHandler().clickSlot(FurnaceSlot.INPUT_SLOT_FUEL, 0, ContainerInput.PICKUP);
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
                mod.getSlotHandler().clickSlot(FurnaceSlot.OUTPUT_SLOT, 0, ContainerInput.PICKUP);
                return null;
                // return new MoveItemToSlotTask(new ItemTarget(output.getItem(), output.getCount()), toMoveTo.get(), mod -> FurnaceSlot.OUTPUT_SLOT);
            }

            // Fill in input if needed
            // Materials needed in slot = (mat_target - out_in_inventory - out_in_furnace)
            ItemTarget materialTarget = _allMaterials;

            int neededMaterialsInSlot = owedHere(mod, getTargetContainerPosition(),
                    mod.getItemStorage().getItemCountInventoryOnly(_target.getItem().getMatches()),
                    _target.getItem().matches(output.getItem()) ? output.getCount() : 0);
            // We don't have the right material or we need more
            if (!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount()) {
                int materialsAlreadyIn = (materialTarget.matches(material.getItem()) ? material.getCount() : 0);
                setDebugState("Moving Materials");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), FurnaceSlot.INPUT_SLOT_MATERIALS);
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
            // keeps the wood the run still has plans for
            double lit = StorageHelper.getFurnaceFuel();
            double progress = StorageHelper.getFurnaceCookPercent();
            double slotFuel = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
            var bag = mod.getItemStorage().getItemStacksPlayerInventory(true);
            // a split load takes its own share and leaves the rest of the batch's fuel in the bag for the next furnace
            var pick = _split != null
                    ? FuelShortage.fillShare(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount)
                    : FuelShortage.fill(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
            if (pick != null) {
                setDebugState("Filling fuel");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(pick.stack().getItem(), pick.count()), FurnaceSlot.INPUT_SLOT_FUEL);
            }

            // nothing in the bag may burn and the furnace is short: "Waiting..." would never end, see the smoker. shut the screen and
            // go get it (the trip target is remembered, FuelShortage.fetchUntil)
            double bagFuel = StorageHelper.calculateInventoryFuelCount(mod);
            long now = mod.getWorld().getGameTime();
            if (_dryInside.confirmed(FuelShortage.dry(material.getCount(), lit, progress, slotFuel, bagFuel), now)) {
                setDebugState("Out of fuel, going to get some");
                _dryInside.fetchUntil(FuelShortage.missing(material.getCount(), lit, progress, slotFuel));
                _dryInside.reset();
                StorageHelper.closeScreen();
                return null;
            }

            // a slot full of one fuel with only another in the bag: it cannot go in without swapping the slot's out. once that has held
            // for a moment (the fire's reading lags a fill by a tick) the bag's fuel may replace it if it covers the whole job alone,
            // nothing wants the old stack back then. otherwise the slot is as full as it gets and we leave short
            BlockPos at = getTargetContainerPosition();
            boolean covered = AsyncSmelting.fuelCovers(slotFuel, lit, progress, material.getCount());
            boolean stuck = _cannotAdd.confirmed(FuelShortage.stuckShort(material.getCount(), lit, progress, slotFuel, bagFuel), now);
            var swap = stuck ? FuelShortage.swap(bag, fuel, material.getCount(), lit, progress, AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount) : null;
            if (swap != null && AsyncSmelting.emptyFuelSlot(mod, FurnaceSlot.INPUT_SLOT_FUEL)) {
                // the slot comes empty first, the fill above puts the covering fuel in next tick (AsyncSmelting.emptyFuelSlot)
                setDebugState("Taking the slot's fuel out for one that covers the job");
                return null;
            }
            // what came out of the slot goes back in the bag first (nothing is in flight here, a move in progress returned above)
            if (AsyncSmelting.clearCursor(mod)) {
                return null;
            }

            // loaded: the cook does not need us. the screen closes and whoever asked comes back later (the job is due when the fuel is,
            // AsyncSmelting.cookable)
            if (at != null && !material.isEmpty() && AsyncSmelting.wants(_target.getItem()) && (covered || stuck)) {
                setDebugState(covered ? "Loaded, leaving it to cook" : "Slot is as full as it gets, leaving it to cook");
                AsyncSmelting.loaded(mod, at, Blocks.FURNACE, material, _target.getItem(),
                        AsyncSmelting.cookable(slotFuel, lit, progress, material.getCount()));
                _loaded = true;
                // the batch moves on from here: after the last load the planner drops the iron need the same tick and the parent never
                // ticks again to see it, which left a batch with a load "to go" and the planner budgeting cobble for its furnace
                if (_split != null) {
                    _split.loaded(_loadIndex);
                }
                return null;
            }
            setDebugState("Waiting...");
            return null;
        }

        // a furnace with our ore in it (the screen was closed on it half loaded, or we put stuff in it ourselves) is used from any
        // distance and never left for a new one. that is also the "never put a furnace over our ore" rule: otherwise the choice
        // decides (StationChoice), and the one we picked last tick only sticks while it still wins
        // a split load only keeps its own furnace (or the one the load it replaces had started, the batch remembers it). the
        // nearest loaded one is the previous load's, cooking, and pinning to it put load 2 on top of load 1
        @Override
        protected BlockPos pinnedStation(AltoClef mod) {
            BlockPos kept = getTargetContainerPosition();
            boolean keep = kept != null && (hasStartedSmelting() || _furnaceCache.burnPercentage > 0 || StationMemory.holdsOurStuff(mod, kept));
            BlockPos half = _split == null ? null : _split.loadingAt();
            return pin(kept, keep, _split != null, half, half != null && AsyncSmelting.ironCookingAt(half),
                    () -> StationMemory.ourLoaded(mod, Blocks.FURNACE));
        }

        // the pin rule without the world. a half load we started is ours to finish, unless it did go in after all and is cooking
        // (its hand-off landed while we were away): that one is busy like any other
        static <T> T pin(T kept, boolean keepKept, boolean split, T half, boolean halfCooking, java.util.function.Supplier<T> ourLoaded) {
            if (keepKept) {
                return kept;
            }
            if (split) {
                return half == null || halfCooking ? null : half;
            }
            return ourLoaded.get();
        }

        // a split load walks past furnaces that already have our things in them (DoStuffInContainerTask.choose), and so makes or
        // places one even with a busy one of ours right here
        @Override
        protected boolean skipsBusy() {
            return _split != null;
        }

        // the caches start as EMPTY stacks and only change while the screen is open, so anything in them means we
        // really did put stuff in (or take stuff out of) a furnace
        public boolean hasStartedSmelting() {
            return !_furnaceCache.materialSlot.isEmpty() || !_furnaceCache.fuelSlot.isEmpty()
                    || !_furnaceCache.outputSlot.isEmpty() || _furnaceCache.burningFuelCount > 0;
        }

        private void tryUpdateOpenFurnace(AltoClef mod) {
            if (isContainerOpen(mod)) {
                _recorded = false;
                // Update current furnace cache
                _furnaceCache.burnPercentage = StorageHelper.getFurnaceCookPercent();
                _furnaceCache.burningFuelCount = StorageHelper.getFurnaceFuel();
                _furnaceCache.fuelSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
                _furnaceCache.materialSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
                _furnaceCache.outputSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            }
        }

        @Override
        protected String toHudString() {
            return "Smelting " + HudText.items(_target.getItem());
        }

    }

    static class FurnaceCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
