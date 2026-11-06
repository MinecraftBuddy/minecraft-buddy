package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.world.FrameGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;

import java.util.HashSet;
import java.util.Set;

// walk to the nearest empty frame of OUR ring and put an eye in it, over and over (BM2:416-422 shape, but it only
// looks at the 12 frames of the ring we found, not at whatever frame the tracker remembers)
public class FillPortalFramesTask extends Task {
    private final RunState.Pos centre;
    private final Set<Long> ring = new HashSet<>();
    private final SilverfishSpawnerBreaker spawnerBreaker = new SilverfishSpawnerBreaker();
    private final boolean breakSpawner;
    private Task fill;
    private int tick;

    public FillPortalFramesTask(RunState.Pos centre, boolean breakSpawner) {
        this.centre = centre;
        this.breakSpawner = breakSpawner;
        for (int[] p : FrameGeometry.positions(new int[]{centre.x, centre.y, centre.z})) {
            ring.add(new BlockPos(p[0], p[1], p[2]).asLong());
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        fill = new DoToClosestBlockTask(
                pos -> new InteractWithBlockTask(Items.ENDER_EYE, pos),
                pos -> ring.contains(pos.asLong()) && isEmptyFrame(mod, pos),
                Blocks.END_PORTAL_FRAME);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        tick++;
        // a spawner the room phase did not get to would keep throwing silverfish at us while we work
        Task spawner = spawnerBreaker.next(mod, breakSpawner, tick);
        if (spawner != null) {
            setDebugState("Breaking the silverfish spawner", "Breaking the silverfish spawner");
            return spawner;
        }
        setDebugState("Putting eyes in the portal frames", "Putting in Eyes of Ender");
        return fill;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
    }

    private static boolean isEmptyFrame(AltoClef mod, BlockPos pos) {
        var state = mod.getWorld().getBlockState(pos);
        return state.is(Blocks.END_PORTAL_FRAME) && !state.getValue(EndPortalFrameBlock.HAS_EYE);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FillPortalFramesTask t && t.centre.equals(centre);
    }

    @Override
    protected String toHudString() {
        return "Putting in Eyes of Ender";
    }

    @Override
    protected String toDebugString() {
        return "Filling the end portal frames around " + centre;
    }
}
