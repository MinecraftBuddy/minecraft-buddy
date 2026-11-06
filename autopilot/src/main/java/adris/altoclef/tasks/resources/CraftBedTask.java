package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;

// beds from wool in the bag and nothing else. CollectBedTask walks to any tracked bed in the world first, however far, which is
// what the END_PREP bed errand must not do
public class CraftBedTask extends CraftWithMatchingWoolTask {
    private final ItemTarget target;

    public CraftBedTask(int count) {
        super(new ItemTarget(ItemHelper.BED, count), colorfulItems -> colorfulItems.wool, colorfulItems -> colorfulItems.bed,
                CollectBedTask.createBedRecipe(TaskCatalogue.getItemTarget("wool", 1)),
                new boolean[]{true, true, true, false, false, false, false, false, false});
        target = new ItemTarget(ItemHelper.BED, count);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CraftBedTask task && task.target.equals(target);
    }

    @Override
    protected String toHudString() {
        return "Crafting " + HudText.items(target);
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting bed from wool: " + target;
    }
}
