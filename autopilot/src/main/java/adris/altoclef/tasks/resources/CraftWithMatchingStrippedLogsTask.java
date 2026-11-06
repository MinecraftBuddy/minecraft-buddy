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

public class CraftWithMatchingStrippedLogsTask extends CraftWithMatchingMaterialsTask {

    private final ItemTarget _visualTarget;
    private final Function<ItemHelper.WoodItems, Item> _getTargetItem;

    public CraftWithMatchingStrippedLogsTask(Item[] validTargets, Function<ItemHelper.WoodItems, Item> getTargetItem, CraftingRecipe recipe, boolean[] sameMask, int count) {
        super(new ItemTarget(validTargets, count), recipe, sameMask);
        _getTargetItem = getTargetItem;
        _visualTarget = new ItemTarget(validTargets, count);
    }


    @Override
    protected Task getSpecificSameResourceTask(AltoClef mod, Item[] toGet, int sameNeeded) {
        // Convert logs to stripped, one log each. same top up as the planks one: a literal 1 here is the plank freeze
        // again. nothing gets here today (no expected count override), so it's only for whoever adds one
        CraftMath.TopUp<Item> topUp = CraftMath.sameResourceTopUp(Arrays.asList(toGet),
                stripped -> StorageHelper.craftUsableCount(mod, stripped), stripped -> logCount(mod, stripped), 1, sameNeeded);
        if (topUp == null) return getAllSameResourcesTask(mod);
        return TaskCatalogue.getItemTask(topUp.item(), topUp.target());
    }

    private static int logCount(AltoClef mod, Item stripped) {
        Item log = ItemHelper.strippedToLogs(stripped);
        return log == null ? 0 : StorageHelper.craftUsableCount(mod, log);
    }

    @Override
    protected Item getSpecificItemCorrespondingToMajorityResource(Item majority) {
        for (ItemHelper.WoodItems woodItems : ItemHelper.getWoodItems()) {
            if (woodItems.strippedLog == majority) {
                return _getTargetItem.apply(woodItems);
            }
        }
        return null;
    }


    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftWithMatchingStrippedLogsTask task) {
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
        return "Getting: " + _visualTarget;
    }


    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

}
