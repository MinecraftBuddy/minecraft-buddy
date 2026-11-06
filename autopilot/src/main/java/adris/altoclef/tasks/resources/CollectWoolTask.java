package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.ShearSheepTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public class CollectWoolTask extends ResourceTask {

    // wool blocks further than this are left for sheep (the buddy's fork)
    private static final double WOOL_REACH = 64;

    private final int _count;

    private final HashSet<DyeColor> _colors;
    private final Item[] _wools;

    // the deep dark pass asks for a biome per tracked wool, a second apart is plenty (DangerFilter does 2 s)
    private static final int DEEP_DARK_EVERY = 20;
    private int _deepDarkTicks;
    private int _deepDarkKnown = -1;

    public CollectWoolTask(DyeColor[] colors, int count) {
        super(new ItemTarget(getWoolColorItems(colors), count));
        _colors = new HashSet<>(Arrays.asList(colors));
        _count = count;
        _wools = getWoolColorItems(colors);
    }

    public CollectWoolTask(DyeColor color, int count) {
        this(new DyeColor[]{color}, count);
    }

    public CollectWoolTask(int count) {
        this(DyeColor.values(), count);
    }

    private static Item[] getWoolColorItems(DyeColor[] colors) {
        Item[] result = new Item[colors.length];
        for (int i = 0; i < result.length; ++i) {
            result[i] = ItemHelper.getColorfulItems(colors[i]).wool;
        }
        return result;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(_wools));
        _deepDarkTicks = 0;
        _deepDarkKnown = -1;
    }

    // lives here and not in DangerFilter so plain alto's wool gets it too, and this task is the only one tracking wool
    private static void banDeepDarkWool(AltoClef mod, List<BlockPos> known) {
        ClientLevel level = mod.getWorld();
        // only the colours we track get looked at, but the block check is still "is this wool at all" (a stale entry that
        // got recoloured is still city carpet)
        Set<Block> wool = new HashSet<>(Arrays.asList(ItemHelper.itemsToBlocks(ItemHelper.WOOL)));
        DeepDarkRules.banPass(mod.getBans(), WorldHelper.getCurrentDimension(), known,
                pos -> mod.getChunkTracker().isChunkLoaded(pos),
                pos -> wool.contains(level.getBlockState(pos).getBlock()),
                pos -> level.getBiome(pos).is(Biomes.DEEP_DARK));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // TODO: If we don't find good color wool blocks
        // and we DONT find good color sheep:
        // USE DYES + REGULAR WOOL TO CRAFT THE WOOL COLOR!!

        // If we find a wool block, break it.
        Block[] woolBlocks = ItemHelper.itemsToBlocks(_wools);
        BlockTracker tracker = mod.getBlockTracker();
        List<BlockPos> known = tracker.getKnownLocations(woolBlocks);
        // also the tick a scan brings new wool in, so the mine task never gets a city block to walk at for a second
        if (_deepDarkTicks-- <= 0 || known.size() != _deepDarkKnown) {
            _deepDarkTicks = DEEP_DARK_EVERY;
            _deepDarkKnown = known.size();
            banDeepDarkWool(mod, known);
        }
        // the plain anyFound counts banned blocks too (getNearest skips them, the cache keeps them), so an ancient city in
        // range would park us in a mine task with nothing to mine instead of going for sheep
        // (the buddy's fork: and only wool within WOOL_REACH. one 430 blocks off at y -22 had the bed's wool go after it and
        // after the sheep and a wander in turn, 186 times in five minutes, and no wool came, the runner, 2 Nov 2026, attempt 60)
        var me = mod.getPlayer().position();
        if (tracker.anyFound(p -> !tracker.unreachable(p) && p.closerToCenterThan(me, WOOL_REACH), woolBlocks)) {
            return new MineAndCollectTask(new ItemTarget(_wools), woolBlocks, MiningRequirement.HAND);
        }

        // If we have shears, right click nearest sheep
        // Otherwise, kill + loot wool.

        // Dimension
        if (isInWrongDimension(mod) && !mod.getEntityTracker().entityFound(Sheep.class)) {
            return getToCorrectDimensionTask(mod);
        }

        // (shears and no sheep ready to shear used to park us in a shear task that can only wander, so the kill task below
        // never got its turn)
        if (mod.getItemStorage().hasItem(Items.SHEARS) && mod.getEntityTracker().entityFound(ShearSheepTask::readyToShear, Sheep.class)) {
            // Shear sheep.
            return new ShearSheepTask();
        }

        // Only option left is to Kill la Kill.
        return new KillAndLootTask(Sheep.class, entity -> {
            if (entity instanceof Sheep sheep) {
                // Hunt sheep of the same color.
                // lambs drop nothing, and with shears in hand they are all that is left once the shear task has no sheep
                return _colors.contains(sheep.getColor()) && !sheep.isSheared() && !sheep.isBaby();
            }
            return false;
        }, new ItemTarget(_wools, _count));
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(_wools));
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectWoolTask task && task._count == _count && task._colors.equals(_colors);
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " wool.";
    }

}
