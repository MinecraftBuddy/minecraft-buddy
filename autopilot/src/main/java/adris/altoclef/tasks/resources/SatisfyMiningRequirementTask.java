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
    // (stone_pick_first) the WOOD requirement's pick: a stone pickaxe, decided on the first tick (null till then)
    private Boolean _stone;
    // the stone a stone pickaxe takes (cobblestone, cobbled deepslate or blackstone)
    static final int STONE_PICK_STONE = 3;

    static boolean stoneInstead(int stoneHeld) {
        return stoneHeld >= STONE_PICK_STONE;
    }

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        _requirement = requirement;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _stone = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_requirement) {
            case HAND:
                // Will never happen if you program this right
                break;
            case WOOD:
                // (the buddy's fork, Tuning stone_pick_first, a fix of 13 Nov 2026, on) with the stone for one in the bag, a stone
                // pickaxe (131 uses) and not a wooden one (59): decided once a task, so a block the path puts down does not flip
                // it. a coal mine at y 45 wore out 19 to 20 wooden pickaxes in one run with cobblestone in the bag (test heel)
                if (_stone == null) {
                    _stone = adris.altoclef.util.helpers.Tuning.get("stone_pick_first", 0) > 0
                            && stoneInstead(mod.getItemStorage().getItemCount(CollectStoneMaterialTask.MATERIALS));
                }
                if (_stone) {
                    return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                }
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
