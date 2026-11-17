package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalColumnDown;
import adris.altoclef.util.helpers.LavaBreak;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

// dig down a fixed xz column to floorY. GetToYTask has no column, so it digs down wherever baritone fancies
public class GetDownColumnTask extends CustomBaritoneGoalTask {
    private final int x;
    private final int z;
    private final int floorY;

    private final boolean wander;
    // (dig_lava_check) the block the dig holds at, null while it digs
    private BlockPos lavaHold;

    // what makes two of these the same task, split out so the test does not need a running game to build one
    record Shaft(int x, int z, int floorY, boolean wander) {
    }

    public GetDownColumnTask(int x, int z, int floorY) {
        this(x, z, floorY, true);
    }

    // wander = false for a shaft we must not leave: a failed progress check walks off into the open and the column is gone
    public GetDownColumnTask(int x, int z, int floorY, boolean wander) {
        super(wander);
        this.x = x;
        this.z = z;
        this.floorY = floorY;
        this.wander = wander;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalColumnDown(x, z, floorY);
    }

    // (Tuning dig_lava_check, off) in the overworld the block under the feet in the column, the next this dig breaks, is not
    // broken while it would let lava in (LavaBreak.refuse): a dug-in cover dug down into lava. the dig holds, the path let go,
    // and the task that asked for the shaft ends it by its own clock (CornerWallTask's DIG_TICKS, ShelterTask's, the pit's,
    // the portal dig's); the break avoider keeps baritone from the same block on any other way down
    @Override
    protected Task onTick(AltoClef mod) {
        BlockPos next = null;
        if (LavaBreak.on() && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            BlockPos feet = mod.getPlayer().blockPosition();
            if (nextDigInColumn(feet.getX(), feet.getY(), feet.getZ(), x, z, floorY)
                    && WorldHelper.lavaBreakRefused(mod.getWorld(), feet.below())) {
                next = feet.below();
            }
        }
        if (next == null) {
            lavaHold = null;
            return super.onTick(mod);
        }
        if (!next.equals(lavaHold)) {
            lavaHold = next;
            Debug.logInternal("dig down: not breaking the block at " + next.toShortString() + " in the column to y " + floorY
                    + ", lava beside, over or under it (dig_lava_check)");
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        setDebugState("Lava by the next block down: the dig holds");
        return null;
    }

    // the feet stand in the column above its floor, so the block under them is the next one the dig breaks
    static boolean nextDigInColumn(int fx, int fy, int fz, int x, int z, int floorY) {
        return fx == x && fz == z && fy > floorY;
    }

    Shaft shaft() {
        return new Shaft(x, z, floorY, wander);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetDownColumnTask t && t.shaft().equals(shaft());
    }

    @Override
    protected String toDebugString() {
        return "Digging down at " + x + ", " + z + " to y=" + floorY;
    }

    @Override
    protected String toHudString() {
        return "Digging down to y " + floorY;
    }
}
