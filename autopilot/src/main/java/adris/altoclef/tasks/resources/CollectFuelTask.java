package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
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

    @Override
    protected void onStart(AltoClef mod) {
        // Nothing
    }

    @Override
    protected Task onTick(AltoClef mod) {

        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                // Just collect coal for now.
                setDebugState("Collecting coal.");
                int coal = mod.getItemStorage().getItemCountInventoryOnly(Items.COAL);
                return TaskCatalogue.getItemTask(Items.COAL, coalWanted(_targetFuel, StorageHelper.calculateInventoryFuelCount(mod), coal));
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

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Nothing
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
