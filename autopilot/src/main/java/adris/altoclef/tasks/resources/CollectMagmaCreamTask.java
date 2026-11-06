package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.item.Items;

public class CollectMagmaCreamTask extends ResourceTask {
    private final int _count;
    private final Task _deltas = new adris.altoclef.tasks.movement.SearchChunkForBlockTask(net.minecraft.world.level.block.Blocks.BASALT);

    public CollectMagmaCreamTask(int count) {
        super(Items.MAGMA_CREAM, count);
        _count = count;
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
        /*
         * If in nether:
         *      If magma cube located, hunt magma cube.
         *      If not enough potential blaze powder, hunt blazes.
         * If in overworld:
         *      If not enough potential slimes, hunt slimes
         *      Otherwise, go to nether.
         * If in end:
         *      Go to overworld lol
         */
        int currentCream = mod.getItemStorage().getItemCount(Items.MAGMA_CREAM);
        int neededCream = _count - currentCream;
        switch (WorldHelper.getCurrentDimension()) {
            case NETHER -> {
                // (the buddy's fork: a cube that drops cream (size 2 and up), in the hunt's range and passing the rods task's
                // rules for the cream hunt (level, floor, lava, the sea, a drop), never any cube anywhere: that chased cubes into
                // the lava. none about: the basalt deltas, where cubes live, rather than blaze powder, which went through the rods
                // task's brew back to the cream, round and round (steering 58, piece 1, 6 Nov 2026))
                var us = mod.getPlayer();
                double range = adris.altoclef.util.helpers.Tuning.get("cream_range", 48);
                java.util.function.Predicate<net.minecraft.world.entity.Entity> fit = e -> e instanceof MagmaCube c && c.isAlive() && c.getSize() > 1
                        && c.distanceTo(us) <= range && CollectBlazeRodsTask.creamCubeWhyNot(mod, c) == null;
                if (mod.getEntityTracker().getClosestEntity(us.position(), fit, MagmaCube.class).isPresent()) {
                    setDebugState("Killing Magma cube");
                    return new KillAndLootTask(MagmaCube.class, fit, new ItemTarget(Items.MAGMA_CREAM, neededCream));
                }
                if (mod.getEntityTracker().getClosestItemDrop(us.position(), Items.MAGMA_CREAM).filter(d -> d.distanceTo(us) <= range).isPresent()) {
                    setDebugState("Picking up the magma cream");
                    return new adris.altoclef.tasks.movement.PickupDroppedItemTask(new ItemTarget(Items.MAGMA_CREAM, neededCream), true);
                }
                setDebugState("Looking for magma cubes: the basalt deltas");
                return _deltas;
            }
            case OVERWORLD -> {
                // (the buddy's fork: straight to the nether, its cubes; no slime hunt first, which swam an ocean among drowned for
                // a quarter of an hour, m4cream-1 run 3. slime balls in the bag are no use without blaze powder anyway)
                setDebugState("Going to the nether for magma cubes");
                return new DefaultGoToDimensionTask(Dimension.NETHER);
            }
            case END -> {
                setDebugState("Going to overworld, no magma cream materials exist here.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
        }

        setDebugState("INVALID DIMENSION??: " + WorldHelper.getCurrentDimension());
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectMagmaCreamTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " Magma cream.";
    }
}
