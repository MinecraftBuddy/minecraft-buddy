package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.HotbarTools;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.level.block.Block;

// logs are MineAndCollectTask's job, near or far. far ones used to go to baritone's own #mine, which takes the nearest log it
// can find whatever that log is part of and never hears about the pick's rules (TreeReach.buried). one path, one set of rules
public class MineLogsTask extends ResourceTask {
    private static final Block[] LOGS = ItemHelper.itemsToBlocks(ItemHelper.LOG);

    private final int _count;
    private final MineAndCollectTask _miner;

    public MineLogsTask(int count) {
        super(new ItemTarget(ItemHelper.LOG, count));
        _count = count;
        _miner = new MineAndCollectTask(_itemTargets, LOGS, MiningRequirement.HAND);
    }

    // the miner sweeps up its own drops, a pickup task from ResourceTask would fight it for the pather
    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return true;
    }

    // the catalogue's mineIfPresent is these very logs. its range check would only hand out the same miner we do
    @Override
    protected boolean minesOnItsOwn(Block[] blocks) {
        return coversAll(blocks, LOGS);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // the axe, if the bag has one. ToolSet never looks past the hotbar
        HotbarTools.bringDown(mod, "axe");
        setDebugState("Mining logs");
        return _miner;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof MineLogsTask task && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Mining " + _count + " logs";
    }
}
