package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WoodFuel;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Optional;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.item.Items;

// TODO: Make this collect more than just coal. It should smartly pick alternative sources if coal is too far away or if we simply cannot get a wooden pick.
public class CollectFuelTask extends Task {
    // smelts a coal burns, in a furnace and (half the burn time, half the cook time) in a smoker alike
    private static final int COAL_SMELTS = 8;

    private final double _targetFuel;

    // targetFuel = smelts of usable fuel the bag should hold, the same number the smelt tasks compare it against
    public CollectFuelTask(double targetFuel) {
        _targetFuel = targetFuel;
    }

    // coal to have in the bag in total: what is there already plus enough more for what the usable fuel (coal, but also the
    // wood we may burn) leaves short. this used to compare raw coal items against smelts, which asked for double the coal the
    // smelt task needed, and the smelt task walked back to the furnace at the first piece anyway
    static int coalWanted(double targetFuel, double usableFuel, int coalHeld) {
        double missing = targetFuel - usableFuel;
        return missing <= 0 ? coalHeld : coalHeld + (int) Math.ceil(missing / COAL_SMELTS);
    }

    // done when the bag holds what we were asked for, in the units we were asked in
    static boolean enough(double usableFuel, double targetFuel) {
        return usableFuel >= targetFuel;
    }

    // (the buddy's fork, Tuning wood_fuel) logs or coal, decided once a trip: null till then. the coal ores are tracked from the
    // trip's start so the decision hears of one in view, and it waits for the tracker's first look at most DECIDE_TICKS
    private Boolean _logs;
    private long _startTick = Long.MIN_VALUE;
    private boolean _tracking;
    // a log trip that gets no log in LOG_STALL_TICKS goes for coal after all (no tree within its reach)
    private int _logsHad = -1;
    private long _logsSince;
    private static final long DECIDE_TICKS = 40;
    private static final long LOG_STALL_TICKS = 3600;
    private static final Block[] COAL_ORES = {Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE};
    // a coal ore counts as near within this, as the coal task walks to it: within 5 blocks while it wears less than 15 armour
    // with the ores hidden (legitMine, MineAndCollectTask.nearUnarmoured), else within 32
    private static final double COAL_NEAR = 32, COAL_NEAR_UNARMOURED = 5;

    @Override
    protected void onStart(AltoClef mod) {
        _logs = null;
        _startTick = Long.MIN_VALUE;
        _logsHad = -1;
        if (WoodFuel.on() && !_tracking) {
            mod.getBlockTracker().trackBlock(COAL_ORES);
            _tracking = true;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {

        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                double usable = StorageHelper.calculateInventoryFuelCount(mod);
                Task logs = WoodFuel.on() ? logTrip(mod, usable) : null;
                if (logs != null) return logs;
                if (WoodFuel.on() && _logs == null) {
                    setDebugState("Looking for coal ore in view first");
                    return null;
                }
                // Just collect coal for now.
                setDebugState("Collecting coal.");
                int coal = mod.getItemStorage().getItemCountInventoryOnly(Items.COAL);
                return TaskCatalogue.getItemTask(Items.COAL, coalWanted(_targetFuel, usable, coal));
            }
            case END -> {
                setDebugState("Going to overworld, since, well, no more fuel can be found here.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            case NETHER -> {
                setDebugState("Going to overworld, since we COULD use wood but wood confuses the bot. A bug at the moment.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            //return TaskCatalogue.getItemTask("planks", (int) Math.ceil(_targetFuel));
        }
        setDebugState("INVALID DIMENSION: " + WorldHelper.getCurrentDimension());
        return null;
    }

    // (wood_fuel) the log trip's task, or null for coal (or while the choice waits on the tracker, _logs still null)
    private Task logTrip(AltoClef mod, double usable) {
        long now = mod.getWorld().getGameTime();
        if (_startTick == Long.MIN_VALUE) _startTick = now;
        double missing = _targetFuel - usable;
        if (_logs == null) {
            boolean looked = !_tracking || mod.getBlockTracker().hasBeenScanned(COAL_ORES) || now - _startTick > DECIDE_TICKS;
            if (!looked && missing > WoodFuel.LOGS_UPTO) return null;
            boolean coalNear = coalNear(mod);
            _logs = WoodFuel.logsInstead(missing, coalNear);
            Debug.logInternal("fuel: " + Math.round(Math.max(0, missing) * 10) / 10.0 + " smelts short, "
                    + (coalNear ? "coal ore near" : "no coal ore near") + ": " + (_logs ? "logs" : "coal") + " (wood_fuel)");
        }
        if (!_logs) return null;
        int logsHeld = mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.LOG);
        if (logsHeld != _logsHad) {
            _logsHad = logsHeld;
            _logsSince = now;
        } else if (now - _logsSince > LOG_STALL_TICKS) {
            Debug.logInternal("fuel: no log in " + LOG_STALL_TICKS / 20 + " s, coal after all (wood_fuel)");
            _logs = false;
            return null;
        }
        int want = WoodFuel.logsWanted(missing, logsHeld, mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.PLANKS),
                FuelPolicy.plan());
        setDebugState("Collecting logs to burn, " + Math.round(Math.max(0, missing) * 10) / 10.0 + " smelts short");
        return TaskCatalogue.getItemTask("log", Math.max(want, logsHeld + 1));
    }

    // a coal ore the tracker knows (with the ores hidden, only one that touches air or a fluid) within the coal task's reach
    private static boolean coalNear(AltoClef mod) {
        boolean bare = Baritone.settings().legitMine.value && mod.getPlayer().getArmorValue() < 15;
        double reach = bare ? COAL_NEAR_UNARMOURED : COAL_NEAR;
        Optional<BlockPos> near = mod.getBlockTracker().getNearestTracking(COAL_ORES);
        return near.isPresent() && near.get().closerToCenterThan(mod.getPlayer().position(), reach);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_tracking) {
            mod.getBlockTracker().stopTracking(COAL_ORES);
            _tracking = false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFuelTask task) {
            return Math.abs(task._targetFuel - _targetFuel) < 0.01;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return enough(StorageHelper.calculateInventoryFuelCount(mod), _targetFuel);
    }

    @Override
    protected String toHudString() {
        return "Getting fuel";
    }

    @Override
    protected String toDebugString() {
        return "Collect Fuel: x" + _targetFuel;
    }
}
