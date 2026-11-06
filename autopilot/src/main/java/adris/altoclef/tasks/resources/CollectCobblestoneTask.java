package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectCobblestoneTask extends ResourceTask {

    // stone only. cobble in the world is almost always our own pillar or scaffolding, and mining the block we stand on
    // for cobble to pillar with again is a loop, not a harvest. the drops are cobble either way
    private static final Block[] STONES = {Blocks.STONE};
    // what minesOnItsOwn claims, see there
    private static final Block[] CLAIMED = {Blocks.STONE, Blocks.COBBLESTONE};

    private final int _count;

    public CollectCobblestoneTask(int targetCount) {
        super(Items.COBBLESTONE, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        return new MineAndCollectTask(Items.COBBLESTONE, 1, STONES, MiningRequirement.WOOD);
    }

    // the catalogue's mineIfPresent would be placed cobble, which is our pillars. we claim it (and don't mine it) so no
    // second miner ever gets started on them. dormant while the catalogue says dontMineIfPresent
    @Override
    protected boolean minesOnItsOwn(Block[] blocks) {
        return coversAll(blocks, CLAIMED);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectCobblestoneTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect Cobblestone";
    }
}
