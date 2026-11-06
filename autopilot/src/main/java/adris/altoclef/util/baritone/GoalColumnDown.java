package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;

// an xz column, done at floorY or anywhere under it. GoalYLevel has no xz so baritone digs down wherever the cheapest
// shaft is, however far off. this one only ends inside the column
public class GoalColumnDown implements Goal {
    private final int x;
    private final int z;
    private final int floorY;

    public GoalColumnDown(int x, int z, int floorY) {
        this.x = x;
        this.z = z;
        this.floorY = floorY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z && y <= floorY;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // under the floor costs nothing extra, so only the part above it is the walk down
        return GoalBlock.calculate(x - this.x, Math.max(y, floorY) - floorY, z - this.z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GoalColumnDown g && g.x == x && g.z == z && g.floorY == floorY;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * x + z) + floorY;
    }

    @Override
    public String toString() {
        return "GoalColumnDown{x=" + x + ",z=" + z + ",floorY=" + floorY + "}";
    }
}
