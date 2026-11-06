package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// the buddy's fork: what stone tools and a furnace are made of in 26.x, cobblestone, cobbled deepslate or blackstone (the
// game's stone_tool_materials and stone_crafting_materials). the catalogue knew cobblestone only, so a stone pickaxe at y -9
// sent it after stone it could not reach with 76 cobbled deepslate in the bag, until the runner called it stuck (the runner,
// 2 Nov 2026, attempt 80). mined from stone, deepslate and blackstone, whichever is near
public class CollectStoneMaterialTask extends ResourceTask {

    public static final Item[] MATERIALS = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE};
    // never cobble, as CollectCobblestoneTask: cobble in the world is our own pillars. blackstone is mined: in the nether it
    // is the only stone there is, and a broken pickaxe there looked for stone that was not there. it is no pillar of ours,
    // the walker does not build with it (BaritoneSettingsScope), and what was placed a minute ago is left (PlacedByUs)
    private static final Block[] STONES = {Blocks.STONE, Blocks.DEEPSLATE, Blocks.BLACKSTONE};

    private final int _count;

    public CollectStoneMaterialTask(int targetCount) {
        super(new ItemTarget(MATERIALS, targetCount));
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
        return new MineAndCollectTask(new ItemTarget(MATERIALS, 1), STONES, MiningRequirement.WOOD);
    }

    @Override
    protected boolean minesOnItsOwn(Block[] blocks) {
        return coversAll(blocks, new Block[]{Blocks.STONE, Blocks.DEEPSLATE, Blocks.BLACKSTONE, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE});
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectStoneMaterialTask task && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect stone for tools";
    }
}
