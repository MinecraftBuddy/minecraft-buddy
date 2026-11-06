package adris.altoclef.tasks.resources;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasks.container.SmeltInBlastFurnaceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class CollectIronIngotTask extends ResourceTask {

    private final int _count;
    private final SmeltRouter _router = new SmeltRouter();
    // the split load in progress (a fresh task per load, the router would hand back the finished one) and the batch it is for
    private SmeltInFurnaceTask _load;
    private SmeltSplit.Batch _loadBatch;
    private String _noSplitLogged = "";
    // the fuel fetch before load 1 (SmeltSplit.fuelStep) and the batch it is for. its clock lives on the batch
    private SmeltSplit.Batch _fuelFor;
    private CollectFuelTask _fuelTask;

    public CollectIronIngotTask(int count) {
        super(Items.IRON_INGOT, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(Blocks.FURNACE, Blocks.BLAST_FURNACE);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        SmeltTarget all = new SmeltTarget(new ItemTarget(Items.IRON_INGOT, _count), new ItemTarget(Items.RAW_IRON, _count));
        // a blast furnace that is already standing close (village armorer) beats a plain furnace, and works with
        // altoUseBlastFurnace off since that one is only about making our own
        // but not in the middle of a split batch: the router never saw those loads start, and the blast task would count the iron
        // already cooking in our furnaces as still to mine
        Task nearby = SmeltSplit.held(_count) == null ? _router.tryNearbyBlast(mod, all) : null;
        if (nearby != null) {
            return nearby;
        }
        if (Baritone.settings().altoUseBlastFurnace.value) {
            if (blastAround(mod)) {
                return new SmeltInBlastFurnaceTask(all);
            }
            if (!wouldBuildBlast(_count, mod)) {
                return _router.furnace(all);
            }
            Optional<BlockPos> furnacePos = mod.getBlockTracker().getNearestTracking(Blocks.FURNACE);
            furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
            if (mod.getItemStorage().getItemCount(Items.IRON_INGOT) >= 5) {
                return TaskCatalogue.getItemTask(Items.BLAST_FURNACE, 1);
            }
            return _router.furnace(new SmeltTarget(new ItemTarget(Items.IRON_INGOT, 5), new ItemTarget(Items.RAW_IRON, 5)));
        }
        return furnace(mod, all);
    }

    // whether a run for count ingots makes its own blast furnace on the way (5 ingots the slow way, 3 smooth stone, a furnace).
    // GatherBillTask asks the same question up front so the parts get fetched with everything else
    public static boolean wouldBuildBlast(int count, AltoClef mod) {
        return Baritone.settings().altoUseBlastFurnace.value && !blastAround(mod) && count >= 5;
    }

    private static boolean blastAround(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.BLAST_FURNACE) ||
                mod.getBlockTracker().anyFound(Blocks.BLAST_FURNACE) ||
                mod.getEntityTracker().itemDropped(Items.BLAST_FURNACE);
    }

    // the plain furnace, or a big batch split over a few of them (SmeltSplit). only the gamer splits: it is the one that walks away
    // from a loaded furnace and comes back (async smelting), and the one that knows which furnaces are ours
    private Task furnace(AltoClef mod, SmeltTarget all) {
        if (!StationHook.installed() || !AsyncSmelting.wants(all.getItem())) {
            return _router.furnace(all);
        }
        int bag = mod.getItemStorage().getItemCount(Items.IRON_INGOT);
        int raw = mod.getItemStorage().getItemCount(Items.RAW_IRON);
        // every furnace's load counts, the ones queued this tick too (the gamer only takes those in on its next tick)
        int owed = _count - bag - AsyncSmelting.pendingOutput("iron_ingot", null);
        if (owed <= 0) {
            // all of it is in a furnace somewhere, the jobs bring it back (the planner drops the need about now anyway)
            return null;
        }
        SmeltSplit.Batch batch = SmeltSplit.held(_count);
        if (batch == null) {
            // still mining (the single smelt fetches the ore the way it always did), or few enough for one furnace. the call is made
            // once the whole batch is in the bag and we are where it gets smelted (SmeltSplit.atSite: surfaced, or the climb gave
            // up). anywhere else the arbiter has the climb in front of us, a tick that slips through is the old single smelt
            if (SmeltSplit.fuelGaveUpFor(_count) || owed < SmeltSplit.MIN_SPLIT || raw < owed || !SmeltSplit.atSite()) {
                return _router.furnace(all);
            }
            batch = decide(mod, owed);
            if (batch == null) {
                return _router.furnace(all);
            }
        }
        if (_load != null && _loadBatch == batch) {
            if (_load.handedOff()) {
                // the load moved the batch on itself (SmeltSplit.Batch.loaded), this only lets go of it
                _load = null;
                Debug.logInternal("smelt: load " + batch.issued() + " of " + batch.loads() + " is in, " + owed + " iron still to load");
            } else {
                BlockPos at = _load.loadingAt();
                if (at != null) {
                    batch.loadingAt(at);
                }
                return _load;
            }
        }
        if (batch.over()) {
            // all the loads are in: whatever is still owed (a load came back short) is a normal smelt, which counts the jobs too
            return _router.furnace(all);
        }
        if (!batch.fuelReady()) {
            if (batch.issued() > 0 || batch.loadingAt() != null) {
                // ore is already in a furnace, too late to hold load 1 back. the loads fetch their own like they used to
                batch.fuelReady(true);
            } else {
                Task fuel = fuelFirst(mod, batch, owed);
                if (fuel != null) {
                    return fuel;
                }
                // the bag covers it, or the batch shrank to what it covers (or to nothing)
                batch = SmeltSplit.held(_count);
                if (batch == null) {
                    return _router.furnace(all);
                }
            }
        }
        int size = batch.nextLoad(owed, raw);
        if (size <= 0) {
            batch.end();
            Debug.logInternal("smelt: no raw iron left for load " + (batch.issued() + 1) + " of " + batch.loads()
                    + ", the other " + owed + " go the usual way");
            return _router.furnace(all);
        }
        _load = SmeltInFurnaceTask.splitLoad(all, batch, batch.issued(), size);
        _loadBatch = batch;
        Debug.logInternal("smelt: load " + (batch.issued() + 1) + " of " + batch.loads() + ", " + size + " raw iron"
                + (batch.loadingAt() != null ? ", finishing the one started at " + batch.loadingAt().toShortString() : ""));
        return _load;
    }

    // the whole batch's fuel goes in the bag before load 1, coal trips in between loads are what we're avoiding. null = load now
    private Task fuelFirst(AltoClef mod, SmeltSplit.Batch batch, int owed) {
        if (_fuelFor != batch) {
            _fuelFor = batch;
            _fuelTask = null;
        }
        int[] sizes = batch.sizes();
        double have = StorageHelper.calculateInventoryFuelCount(mod);
        int need = SmeltSplit.batchFuel(sizes, 0);
        String haveWords = Math.round(have * 10) / 10.0 + "";
        String needWords = need + " (" + need / SmeltSplit.SMELTS_PER_COAL + " coal)";
        switch (SmeltSplit.fuelStep(sizes, have, batch.fuelFetchTicks())) {
            case LOAD -> {
                batch.fuelReady(true);
                return null;
            }
            case FETCH -> {
                if (batch.fuelFetchTicks() == 0) {
                    Debug.logInternal("smelt: gathering fuel for the whole batch, need " + needWords + ", have " + haveWords
                            + " (smelts, a coal is " + SmeltSplit.SMELTS_PER_COAL + ")");
                }
                batch.fuelFetchTick();
                if (_fuelTask == null) {
                    _fuelTask = new CollectFuelTask(need);
                }
                return _fuelTask;
            }
            default -> {
                int k = SmeltSplit.loadsFuelCovers(owed, sizes.length, have);
                if (k <= 1) {
                    batch.end();
                    SmeltSplit.fuelGaveUp(_count);
                    Debug.logInternal("smelt: no fuel for a split after " + batch.fuelFetchTicks() / 20 + " s of looking (need " + needWords
                            + ", have " + haveWords + "), " + owed + " iron in one furnace");
                    return null;
                }
                int[] smaller = SmeltSplit.sizes(owed, k);
                Debug.logInternal("smelt: fuel covers " + k + " furnaces, not " + sizes.length + " (have " + haveWords
                        + ", " + sizes.length + " needed " + needWords + "), splitting " + SmeltSplit.words(smaller));
                SmeltSplit.start(_count, smaller).fuelReady(true);
                return null;
            }
        }
    }

    // once per batch: how many furnaces, from what is standing idle, what is in the bag and the cobble the tools are not owed. a
    // no is not held: the moment the first ore goes in, raw drops under owed and nothing asks again
    private SmeltSplit.Batch decide(AltoClef mod, int owed) {
        Vec3 me = mod.getPlayer().position();
        int idle = StationHook.countWithin(StationHook.Kind.FURNACE, me.x, me.y, me.z, StationChoice.WALK_BACK,
                p -> DoStuffInContainerTask.walkBackUsable(mod, p, Blocks.FURNACE) && !AsyncSmelting.jobAt(p));
        int inBag = mod.getItemStorage().getItemCount(Items.FURNACE);
        int spare = DoStuffInContainerTask.spareCobble(item -> mod.getItemStorage().getItemCount(item), StationHook.cobbleOwed());
        int k = SmeltSplit.loads(owed, idle, inBag, spare);
        String why = idle + " idle of ours within " + Math.round(StationChoice.WALK_BACK) + ", " + inBag + " in the bag, "
                + Math.max(0, spare) + " spare cobble";
        if (k <= 1) {
            String line = "smelt: " + owed + " iron in one furnace, nothing for a second (" + why + ")";
            if (!line.equals(_noSplitLogged)) {
                _noSplitLogged = line;
                Debug.logInternal(line);
            }
            return null;
        }
        int[] sizes = SmeltSplit.sizes(owed, k);
        Debug.logInternal("smelt: splitting " + owed + " iron across " + k + " furnaces (" + SmeltSplit.words(sizes) + "; " + why + ")");
        return SmeltSplit.start(_count, sizes);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.FURNACE, Blocks.BLAST_FURNACE);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectIronIngotTask && ((CollectIronIngotTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " iron.";
    }
}
