package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

// any spot the player could break the block from without moving, instead of only the six cells touching it.
// GoalNear(pos, 1) made a log three blocks up a vine covered trunk mean "get on the vines", when standing at the
// bottom and swinging would have been fine
public class GoalReachBlock implements Goal, IGoalRenderPos {

    // eyes are 1.62 above the feet. the real reach is 4.5, this leaves most of a block for the feet not being in the
    // middle of their cell and for the ray wanting a face rather than the center
    static final double EYE = 1.62;
    static final double REACH = 3.5;
    private static final double REACH_SQ = REACH * REACH;
    // furthest a cell can be from the block along one axis and still be in range (best case, level with the eyes)
    private static final int FLAT = 3;
    // and vertically, feet above the block / feet below it
    private static final int UP = 2;
    private static final int DOWN = 4;

    private final int x, y, z;

    public GoalReachBlock(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        double dx = x - this.x;
        double dz = z - this.z;
        // eye to the middle of the block
        double dy = y + EYE - (this.y + 0.5);
        return dx * dx + dz * dz + dy * dy <= REACH_SQ;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // how far the nearest cell that counts could be, undercounted so it never claims more than it takes (the
        // corners of the box aren't in range, nobody is paying for that)
        int dy = y - this.y;
        int yDiff = dy > UP ? dy - UP : dy < -DOWN ? dy + DOWN : 0;
        return GoalBlock.calculate(Math.max(0, Math.abs(x - this.x) - FLAT), yDiff, Math.max(0, Math.abs(z - this.z) - FLAT));
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("GoalReachBlock{x=%s,y=%s,z=%s}", SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
    }
}
