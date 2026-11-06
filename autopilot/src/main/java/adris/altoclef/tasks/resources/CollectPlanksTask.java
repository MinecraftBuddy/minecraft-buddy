package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.*;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.world.item.Item;

public class CollectPlanksTask extends ResourceTask {

    private final Item[] _planks;
    private final Item[] _logs;
    private final int _targetCount;
    private boolean _logsInNether;
    // we already fell through to mining logs for this target (see craftHeldLogsNow)
    private boolean _chopping;

    public CollectPlanksTask(Item[] planks, Item[] logs, int count, boolean logsInNether) {
        super(new ItemTarget(planks, count));
        _planks = planks;
        _logs = logs;
        _targetCount = count;
        _logsInNether = logsInNether;
    }

    public CollectPlanksTask(int count) {
        this(ItemHelper.PLANKS, ItemHelper.LOG, count, false);
    }

    public CollectPlanksTask(Item plank, Item log, int count) {
        this(new Item[]{plank}, new Item[]{log}, count, false);
    }

    public CollectPlanksTask(Item plank, int count) {
        this(plank, ItemHelper.planksToLog(plank), count);
    }

    private static CraftingRecipe generatePlankRecipe(Item[] logs) {
        return CraftingRecipe.newShapedRecipe(
                "planks",
                new Item[][]{
                        logs, null,
                        null, null
                },
                4
        );
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    // logs in the bag turn into planks before any tree is touched, even when they do not reach the target on their own (it
    // used to be all or nothing: 3 logs for 16 planks meant walking off to chop with 12 planks sitting in the bag). once we
    // have gone chopping, the logs that come in are only crafted when they finish the job, one inventory open per log is
    // slow and nobody wants that
    static boolean craftHeldLogsNow(int heldLogs, int potentialPlanks, int target, boolean chopping) {
        return heldLogs > 0 && (potentialPlanks >= target || !chopping);
    }

    @Override
    protected boolean craftableFromHeld(AltoClef mod) {
        return StorageHelper.craftUsableCount(mod, _logs) > 0;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _chopping = false;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // Craft when we can
        // counted the way the craft counts (craftUsableCount). logs in a furnace slot used to count as held, so we never
        // went chopping and the craft below said out of materials forever
        int totalInventoryPlankCount = StorageHelper.craftUsableCount(mod, _planks);
        int heldLogs = StorageHelper.craftUsableCount(mod, _logs);
        int potentialPlanks = totalInventoryPlankCount + heldLogs * 4;
        if (craftHeldLogsNow(heldLogs, potentialPlanks, _targetCount, _chopping)) {
            for (Item logCheck : _logs) {
                int count = StorageHelper.craftUsableCount(mod, logCheck);
                if (count > 0) {
                    Item plankCheck = ItemHelper.logToPlanks(logCheck);
                    if (plankCheck == null) {
                        Debug.logError("Invalid/Un-convertable log: " + logCheck + " (failed to find corresponding plank)");
                    }
                    int plankCount = StorageHelper.craftUsableCount(mod, plankCheck);
                    int otherPlankCount = totalInventoryPlankCount - plankCount;
                    int targetTotalPlanks = Math.min(count * 4 + plankCount, _targetCount - otherPlankCount);
                    setDebugState("We have " + logCheck + ", crafting " + targetTotalPlanks + " planks.");
                    return new CraftInInventoryTask(new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(_logs)));
                }
            }
        }

        // nothing left to craft from, the rest of the target is trees
        _chopping = true;
        // Collect planks and logs
        ArrayList<ItemTarget> blocksTomine = new ArrayList<>(2);
        blocksTomine.add(new ItemTarget(_logs));
        // Ignore planks if we're told to.
        if (!mod.getBehaviour().exclusivelyMineLogs()) {
            // TODO: Add planks back in, but with a heuristic check (so we don't go for abandoned mineshafts)
            //blocksTomine.add(new ItemTarget(ItemUtil.PLANKS));
        }

        ResourceTask mineTask = new MineAndCollectTask(blocksTomine.toArray(ItemTarget[]::new), MiningRequirement.HAND);
        // Kinda jank
        if (_logsInNether) {
            mineTask.forceDimension(Dimension.NETHER);
        }
        return mineTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectPlanksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " planks " + Arrays.toString(_planks);
    }

    public CollectPlanksTask logsInNether() {
        _logsInNether = true;
        return this;
    }
}
