package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Items;

/**
 * Make sure we have a tool at or above a mining level.
 */
public class SatisfyMiningRequirementTask extends Task {

    private final MiningRequirement _requirement;

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        _requirement = requirement;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_requirement) {
            case HAND:
                // Will never happen if you program this right
                break;
            case WOOD:
                return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
            case STONE:
                // the buddy's fork: the copper one when the copper for it is in the bag already, a better stone pickaxe
                if (mod.getItemStorage().getItemCount(Items.COPPER_INGOT) >= 3) {
                    return TaskCatalogue.getItemTask(Items.COPPER_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON:
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND:
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SatisfyMiningRequirementTask task) {
            return task._requirement == _requirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Mining Req: " + _requirement;
    }

    @Override
    protected String toHudString() {
        return switch (_requirement) {
            case HAND -> "Getting ready to mine";
            case WOOD -> "Getting a Wooden Pickaxe";
            case STONE -> "Getting a Stone Pickaxe";
            case IRON -> "Getting an Iron Pickaxe";
            case DIAMOND -> "Getting a Diamond Pickaxe";
            case NETHERITE -> "Getting a Netherite Pickaxe";
        };
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _requirement);
    }
}
