package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.GoalXZ;

// anywhere within radius blocks of an xz column. the path ends on its own when it gets inside, so whoever counts "close
// enough" with the same radius never has to cancel a path that is still going to say it is done. a GoalXZ so the
// renderer and the status line still know what to do with it
public class GoalXZRadius extends GoalXZ {
    // the octile distance is a norm, and its biggest value on a unit circle is at 22.5 degrees: cos + (sqrt2 - 1) * sin there
    private static final double OCTILE_MAX_ON_CIRCLE = 1.0824;

    private final double radius;

    public GoalXZRadius(int x, int z, double radius) {
        super(x, z);
        this.radius = radius;
    }

    // same comparison NetherSweepPlanner uses for arrived, don't "fix" it into a sqrt
    public static boolean within(int dx, int dz, double radius) {
        return (double) dx * dx + (double) dz * dz <= radius * radius;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return within(x - getX(), z - getZ(), radius);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int dx = x - getX();
        int dz = z - getZ();
        if (within(dx, dz, radius)) {
            return 0;
        }
        // distance to the centre minus the farthest (in octile terms) the circle's edge can be, never more than the real walk
        return Math.max(0, calculate(dx, dz) - calculate(OCTILE_MAX_ON_CIRCLE * radius, 0));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GoalXZRadius g && g.getX() == getX() && g.getZ() == getZ() && g.radius == radius;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Double.hashCode(radius);
    }

    @Override
    public String toString() {
        return "GoalXZRadius{x=" + getX() + ",z=" + getZ() + ",r=" + radius + "}";
    }
}
