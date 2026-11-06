package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Arrays;

public class GetBuildingMaterialsTask extends Task {
    private final int _count;

    public GetBuildingMaterialsTask(int count) {
        _count = count;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        // the count takes any build block, the mining only goes for the core ones. granite and friends took a slot each
        // for one or two blocks and the bag filled up with fragments
        Item[] core = Arrays.stream(StorageHelper.getBuildingMaterials(mod)).filter(ItemHelper::isCoreBuildBlock).toArray(Item[]::new);
        if (core.length == 0) {
            core = StorageHelper.getBuildingMaterials(mod);
        }
        core = miningTargets(core, WorldHelper.getCurrentDimension() == Dimension.NETHER);
        return new MineAndCollectTask(new ItemTarget[]{new ItemTarget(core, _count)}, MiningRequirement.WOOD).noReserve();
    }

    // cobble and dirt never generate in the nether, so any lying around is a bridge or a pillar of ours and we are standing on it
    public static Item[] miningTargets(Item[] core, boolean nether) {
        return nether ? new Item[]{Items.NETHERRACK} : core;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetBuildingMaterialsTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.getBuildingMaterialCount(mod) >= _count;
    }

    @Override
    protected String toHudString() {
        return "Getting " + _count + " building blocks";
    }

    @Override
    protected String toDebugString() {
        return "Collecting " + _count + " building materials.";
    }
}
