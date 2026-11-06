package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;

// the load-only half of smelting. when altoAsyncSmelting is on, the smelt tasks stop at "everything is in and lit", close
// the screen and say where they left it here, instead of standing at the furnace for the whole cook. the smelt tasks cannot
// see the gamer's RunState (and should not), so the jobs wait in this queue and GamerTask moves them into RunState.furnaceJobs
// at the start of its tick. iron and cooked food: the two smelts that are long AND something the planner knows how to wait on
public final class AsyncSmelting {
    private static final Set<String> OUTPUTS = Set.of("iron_ingot");
    // what the smoker and the furnace turn raw food into (the food task only ever asks for these, kelp is nobody's dinner)
    private static final Set<String> FOOD_OUTPUTS = Set.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
            "cooked_rabbit", "cooked_cod", "cooked_salmon", "baked_potato");
    private static final Queue<RunState.FurnaceJob> LOADED = new ConcurrentLinkedQueue<>();
    // the jobs the gamer is tracking, so the food task (which cannot see RunState) can count the meat that is cooking.
    // non gamer runs never set it and see no jobs, which is the old behaviour
    private static volatile Supplier<List<RunState.FurnaceJob>> jobs = List::of;

    // is this furnace one the gamer put down this run. same seam as the jobs: the smelt task decides between "walk to the
    // furnace I remember" and "place another" and has to know whose it is. non gamer runs see none, which is the old behaviour
    private static volatile Predicate<BlockPos> ours = pos -> false;

    // the game tick a smelt task last had its furnace screen in hand (moving ore, fuel, taking output). the station pickup
    // preempts whatever the kit task is doing, and the screen closing under a half done load is how 37 raw iron got left in a
    // furnace that then got forgotten. -1 = never
    private static volatile long lastWork = -1;

    private AsyncSmelting() {
    }

    // a smelt task is "finished" two ways: it loaded the furnace and walked off (the job is the memory now), or the bag already
    // holds the item it was asked for. the second one is not a load: a cooked batch collected from the same smoker meets the
    // target and the raw meat is still in the bag. whoever waits on the task has to tell them apart
    public interface Handoff {
        boolean handedOff();

        // the task is being dropped without having handed off (the cook gave up on a long fuel trip) and may have left its input in
        // the station. say so, or nobody ever goes back for it (leftBehind)
        // true when a job was left for it
        default boolean recordLeftBehind(AltoClef mod) {
            return false;
        }
    }

    // the smelt tasks call this every tick the screen is open and they are working it
    public static void working(long now) {
        lastWork = now;
    }

    public static long lastWork() {
        return lastWork;
    }

    // setting on and the thing coming out is one we have a plan for
    public static boolean wants(ItemTarget output) {
        Item[] matches = output.getMatches();
        if (matches.length != 1) {
            return false;
        }
        return wantsName(name(matches[0]), Baritone.settings().altoAsyncSmelting.value, Baritone.settings().altoAsyncCooking.value);
    }

    // the same decision without the settings or the registry, for the tests. food needs BOTH switches: smelting is the one
    // the phases flip when they can come back for a furnace, cooking is the user's "but not for the meat"
    public static boolean wantsName(String output, boolean smelting, boolean cooking) {
        if (!smelting) {
            return false;
        }
        return OUTPUTS.contains(output) || (cooking && FOOD_OUTPUTS.contains(output));
    }

    // GamerTask hands over where its jobs live (and takes it back with clear())
    public static void watchJobs(Supplier<List<RunState.FurnaceJob>> source) {
        jobs = source;
    }

    public static void watchFurnaces(Predicate<BlockPos> source) {
        ours = source;
    }

    public static boolean isOurFurnace(BlockPos pos) {
        return ours.test(pos);
    }

    // nutrition cooking in the background right now. the food task adds it to what it could make from the bag, so a loaded
    // smoker is food on the way and not a reason to go hunting again
    public static int pendingFoodUnits() {
        return FurnaceJobs.pendingUnits(jobs.get());
    }

    // what the jobs (and the loads still in the queue) will have made of this output, `skip` left out (null = none). the smelt
    // task reads its own station off the slots and every other furnace off this, so a split batch counts each load once
    public static int pendingOutput(String outputName, BlockPos skip) {
        RunState.Pos at = skip == null ? null : new RunState.Pos(skip.getX(), skip.getY(), skip.getZ());
        return FurnaceJobs.pending(jobs.get(), List.copyOf(LOADED), outputName, at, WorldHelper.getCurrentDimension().name());
    }

    // the smelt outputs whose jobs a smelt task takes off its own count (pendingOutput): iron only, the food task does its own
    // sums for the meat
    public static boolean countsElsewhere(ItemTarget output) {
        Item[] matches = output.getMatches();
        return matches.length == 1 && OUTPUTS.contains(name(matches[0]));
    }

    // a job of ours points at this block (recorded, or loaded this tick and still queued): busy, a split load goes elsewhere
    public static boolean jobAt(BlockPos pos) {
        return pos != null && FurnaceJobs.jobAt(jobs.get(), List.copyOf(LOADED), new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()),
                WorldHelper.getCurrentDimension().name());
    }

    // an iron job that is really cooking points at this block (a stranded one is our half load sitting cold, not a load to keep
    // off). the pending sum with and without the spot, so it is the same book pendingOutput reads
    public static boolean ironCookingAt(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        RunState.Pos at = new RunState.Pos(pos.getX(), pos.getY(), pos.getZ());
        String dimension = WorldHelper.getCurrentDimension().name();
        List<RunState.FurnaceJob> queued = List.copyOf(LOADED);
        return FurnaceJobs.pending(jobs.get(), queued, "iron_ingot", null, dimension)
                > FurnaceJobs.pending(jobs.get(), queued, "iron_ingot", at, dimension);
    }

    // is there enough fuel in (the lit item plus the fuel slot) to cook everything in the input slot without us. the numbers
    // are smelts, same units as the rest of the smelt task, so a coal is 8
    public static boolean fuelCovers(ItemStack fuelSlot, double litFuel, int inputCount) {
        return fuelCovers(fuelSlot, litFuel, 0, inputCount);
    }

    // the same with the cook arrow counted: the item in hand is `progress` of the way done, and that part needs no fuel. it is the
    // sum the fill uses (FuelShortage.missing), so "nothing left to put in" and "covered, leave" cannot disagree by a fraction
    public static boolean fuelCovers(ItemStack fuelSlot, double litFuel, double progress, int inputCount) {
        return fuelCovers(fuelSlot.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuelSlot), litFuel, progress, inputCount);
    }

    // same with the slot already in smelts (the smelt tasks have the number by then, and the tests have no fuel table to ask)
    public static boolean fuelCovers(double slotFuel, double litFuel, double progress, int inputCount) {
        return Math.max(litFuel, 0) + Math.max(progress, 0) + Math.max(slotFuel, 0) >= inputCount;
    }

    // how much of the `inputCount` items the fuel in and under the station gets through before it runs dry, all of them when it
    // is covered. not rounded: the job is due the tick the fire goes out. rounding up had it due up to a whole item after that,
    // the trip showed up to a cold furnace, and a cold furnace winds the arrow back, so the last item started over. a visit a bit
    // early is fine now, it stands at a fire that is about to go out (FurnacePlan.dryingSoon), feeds it and waits out the rest
    public static double cookable(double slotFuel, double litFuel, double progress, int inputCount) {
        double smelts = Math.max(litFuel, 0) + Math.max(progress, 0) + Math.max(slotFuel, 0);
        return Math.max(0, Math.min(inputCount, smelts));
    }

    // whatever is on the cursor goes back in the bag before the smelt task lets go of the screen or calls the load done: a fuel
    // swapped out of the slot lands there, and so does the odd leftover of a split stack. true = a click went out this tick, so
    // the caller waits for the next one. a full bag has nowhere to put it, closing the screen hands it back
    public static boolean clearCursor(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) {
            return false;
        }
        Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
        if (fit.isEmpty()) {
            return false;
        }
        mod.getSlotHandler().clickSlot(fit.get(), 0, ContainerInput.PICKUP);
        return true;
    }

    // the first half of a fuel swap: the slot's stack comes out onto the cursor (a stray cursor stack goes away first, clicking the
    // slot with it would trade the two). the pick goes in as an ordinary fill into an EMPTY slot after that, never as a one click
    // swap: onto a different item a right click puts the whole held stack in, which would be a whole stack of wood where the job
    // wanted 25 planks and the reserve wanted some of them kept. one click per tick. true = a click went out and the caller waits for
    // the next tick. false = the cursor holds something with nowhere to go (a full bag), and then the slot is left alone: clicking it
    // would trade the two, so the caller carries on without the swap
    public static boolean emptyFuelSlot(AltoClef mod, Slot fuelSlot) {
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return clearCursor(mod);
        }
        mod.getSlotHandler().clickSlot(fuelSlot, 0, ContainerInput.PICKUP);
        return true;
    }

    // everything is in: remember the furnace and let go of the screen
    public static void loaded(AltoClef mod, BlockPos pos, Block kind, ItemStack input, ItemTarget output) {
        loaded(mod, pos, kind, input, output, input.getCount());
    }

    // `cookable` = how much of the input the fuel will cook. a station left with less fuel than it needs (the slot is full of one
    // fuel and the bag has another) is due when the fuel is, not after the whole input: the visit then finds it cold and refuels it
    public static void loaded(AltoClef mod, BlockPos pos, Block kind, ItemStack input, ItemTarget output, double cookable) {
        long now = mod.getWorld().getGameTime();
        String kindName = BuiltInRegistries.BLOCK.getKey(kind).getPath();
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()),
                WorldHelper.getCurrentDimension().name(), kindName, name(input.getItem()), input.getCount(),
                name(output.getMatches()[0]), now, FurnaceJobs.doneTick(kindName, now, Math.min(input.getCount(), cookable)));
        job.unitsEach = unitsEach(output);
        LOADED.add(job);
        StorageHelper.closeScreen();
    }

    private static int unitsEach(ItemTarget output) {
        FoodProperties food = output.getMatches()[0].components().get(DataComponents.FOOD);
        return food == null || !FOOD_OUTPUTS.contains(name(output.getMatches()[0])) ? 0 : food.nutrition();
    }

    // the same for an output we only know by name (an adopted load, Workbenches.adoptLoad): 0 for anything that is not cooked food
    public static int unitsOfOutput(String outputName) {
        if (!FOOD_OUTPUTS.contains(outputName)) {
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(outputName));
        FoodProperties food = item.components().get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    // the cook was dropped with the meat already in the station and never handed off (the coal trip ran past its patience, or the
    // plan moved on mid load), so there is no load to hand off and nobody knows the station holds anything. `lit` = the last look
    // at the slots had it lit or fueled for the whole input (fuelCovers). then it is a cook like any other, with the usual timer.
    // otherwise it is a pickup (RunState.FurnaceJob.stranded): the meat is not cooking, so there is no due time to stand by for,
    // and the visit lights it if the bag has fuel by then or takes the meat back out
    public static void leftBehind(AltoClef mod, BlockPos pos, Block kind, ItemStack input, ItemTarget output, boolean lit) {
        long now = mod.getWorld().getGameTime();
        RunState.FurnaceJob job = strandedJob(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()), WorldHelper.getCurrentDimension().name(),
                BuiltInRegistries.BLOCK.getKey(kind).getPath(), name(input.getItem()), input.getCount(), name(output.getMatches()[0]),
                unitsEach(output), now, lit);
        LOADED.add(job);
    }

    // a lit one gets the timer a load would have (the first visit re-stamps it off the arrow), a cold one is due right now
    public static RunState.FurnaceJob strandedJob(RunState.Pos pos, String dimension, String kind, String input, int count, String output,
                                                  int unitsEach, long now, boolean lit) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(pos, dimension, kind, input, count, output, now,
                lit ? FurnaceJobs.doneTick(kind, now, count) : now);
        job.unitsEach = unitsEach;
        job.stranded = !lit;
        return job;
    }

    // what was loaded since the last call. the gamer's tick is the only reader
    public static List<RunState.FurnaceJob> drain() {
        List<RunState.FurnaceJob> out = new ArrayList<>();
        RunState.FurnaceJob job;
        while ((job = LOADED.poll()) != null) {
            out.add(job);
        }
        return out;
    }

    // a new run must not inherit a job from the one before it
    public static void clear() {
        LOADED.clear();
        lastWork = -1;
        jobs = List::of;
        ours = pos -> false;
        SmeltSplit.clear();
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
