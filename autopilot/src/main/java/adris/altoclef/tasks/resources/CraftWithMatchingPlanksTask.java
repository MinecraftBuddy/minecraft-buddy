package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CraftMath;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import java.util.Arrays;
import java.util.function.Function;
import net.minecraft.world.item.Item;

public class CraftWithMatchingPlanksTask extends CraftWithMatchingMaterialsTask {

    private final ItemTarget _visualTarget;
    private final Function<ItemHelper.WoodItems, Item> _getTargetItem;

    public CraftWithMatchingPlanksTask(Item[] validTargets, Function<ItemHelper.WoodItems, Item> getTargetItem, CraftingRecipe recipe, boolean[] sameMask, int count) {
        super(new ItemTarget(validTargets, count), recipe, sameMask);
        _getTargetItem = getTargetItem;
        _visualTarget = new ItemTarget(validTargets, count);
    }


    @Override
    protected int getExpectedTotalCountOfSameItem(AltoClef mod, Item sameItem) {
        // Include logs. craftUsableCount like CollectPlanksTask, or we promise planks from a log it can't see
        return StorageHelper.craftUsableCount(mod, sameItem) + logCount(mod, sameItem) * 4;
    }

    // bamboo planks have no log, and craftUsableCount with a null item is asking for trouble
    private static int logCount(AltoClef mod, Item plank) {
        Item log = ItemHelper.planksToLog(plank);
        return log == null ? 0 : StorageHelper.craftUsableCount(mod, log);
    }

    @Override
    protected Task getSpecificSameResourceTask(AltoClef mod, Item[] toGet, int sameNeeded) {
        // Convert logs to planks
        CraftMath.TopUp<Item> topUp = CraftMath.sameResourceTopUp(Arrays.asList(toGet),
                plank -> StorageHelper.craftUsableCount(mod, plank), plank -> logCount(mod, plank), 4, sameNeeded);
        // no log anywhere means the counts disagree with the parent. go get wood instead of returning null and freezing
        if (topUp == null) return getAllSameResourcesTask(mod);
        return TaskCatalogue.getItemTask(topUp.item(), topUp.target());
    }

    @Override
    protected Item getSpecificItemCorrespondingToMajorityResource(Item majority) {
        for (ItemHelper.WoodItems woodItems : ItemHelper.getWoodItems()) {
            if (woodItems.planks == majority) {
                return _getTargetItem.apply(woodItems);
            }
        }
        return null;
    }


    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftWithMatchingPlanksTask task) {
            return task._visualTarget.equals(_visualTarget);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Crafting " + HudText.items(_visualTarget);
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting: " + _visualTarget;
    }


    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

}
