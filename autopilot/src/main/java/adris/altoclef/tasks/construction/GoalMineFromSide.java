package adris.altoclef.tasks.construction;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

import java.util.Objects;

// GoalNear(pos, 1) counts the square on top of the block as arrived, and for a block that is
// dangerous to break from above that is exactly the one square we are trying to get off of.
// this is GoalNear minus the whole column above the target: sides (and diagonals) at any of
// y-1..y+1, plus the shaft two below for digging up. the pather can still cross the top, it
// just doesn't get to stop there
public class GoalMineFromSide implements Goal, IGoalRenderPos {

    private final int x;
    private final int y;
    private final int z;
    // digging up from the shaft two below is out when there is a column of sand or gravel (or a stalactite) hanging on the
    // target: the whole thing comes down the shaft and onto us
    private final boolean allowBelow;
    // how far down the column under the target is open air (the caller looked). a tree we already cut the bottom of is the
    // case: the log above is "dangerous from above" because standing on it is a long drop, and with only the y-2 spot
    // allowed the bot pillared up next to the trunk instead of standing on the ground right under it and swinging up
    private final int openBelow;
    // feet this far under the block is still a swing: the eye is 1.62 up, so 5 down leaves about 3.4 to the bottom face
    public static final int MAX_UNDER = 5;

    public GoalMineFromSide(BlockPos target) {
        this(target, true);
    }

    public GoalMineFromSide(BlockPos target, boolean allowBelow) {
        this(target, allowBelow, 2);
    }

    public GoalMineFromSide(BlockPos target, boolean allowBelow, int openBelow) {
        this.x = target.getX();
        this.y = target.getY();
        this.z = target.getZ();
        this.allowBelow = allowBelow;
        this.openBelow = Math.max(2, Math.min(openBelow, MAX_UNDER));
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int dx = x - this.x;
        int dy = y - this.y;
        int dz = z - this.z;
        if (dx == 0 && dz == 0) {
            // below only. y-1 would put the ore where our head is, so really it's y-2 or lower, and further down only
            // as far as the column is open (feet and head both in air, nothing between us and the block)
            return allowBelow && dy <= -2 && dy >= -openBelow;
        }
        // up to one sideways step plus a diagonal, one up or down. wider than GoalNear(pos, 1), which is faces only: a
        // diagonal can have no ray to the block, DestroyBlockTask's close in is the way out of that
        return dy >= -1 && dy <= 1 && dx * dx + dz * dz <= 2;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // same as GoalNear. a hair optimistic about the excluded column, a* doesn't mind
        return GoalBlock.calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GoalMineFromSide that = (GoalMineFromSide) o;
        return x == that.x && y == that.y && z == that.z && allowBelow == that.allowBelow && openBelow == that.openBelow;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, allowBelow, openBelow);
    }

    @Override
    public String toString() {
        return "GoalMineFromSide{x=" + x + ", y=" + y + ", z=" + z + (allowBelow ? "" : ", sides only") + "}";
    }
}
