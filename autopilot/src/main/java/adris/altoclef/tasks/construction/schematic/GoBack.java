package adris.altoclef.tasks.construction.schematic;

// "are we at the build" for a schematic build, asked right before a count or a builder start. pure. a gather can end
// a few hundred blocks out, and a count from there sees the build's chunks unloaded, calls them air and bills the
// whole thing again (SchematicDiff). so walk back first, then let the chunks show up, then count
public final class GoBack {

    public enum Step {
        // close enough and the chunks are in, go on
        THERE,
        // still walking
        TRAVEL,
        // close enough, the footprint's chunks aren't all in yet
        WAIT,
        // the walk stopped getting anywhere, give up
        BAIL
    }

    // ticks without getting closer before the walk is called a loss. a detour round a lake is fine, two minutes
    // standing at a cliff isn't
    public static final int MAX_STUCK_TICKS = 2 * 60 * 20;
    // ticks to wait on chunks once there. a server that never sends a chunk shouldn't park us forever, the count just
    // goes ahead with what it can see
    public static final int MAX_WAIT_TICKS = 3 * 20;
    // less than a block closer isn't progress, it's swaying on the spot
    private static final double PROGRESS = 1;
    // deaths or teleports one walk forgives. three deaths on the way home is a different problem
    private static final int MAX_JUMPS = 3;

    private boolean travelling;
    private double best;
    private int stuck;
    private int waited;
    private int jumps;
    private boolean closer;

    // how far out a count still sees the whole thing loaded. a chunk short of the view distance so the edge chunk
    // isn't half sent, and at least one chunk because a render distance of 2 still has neighbours. never more than
    // 32: a 32 chunk server doesn't need us standing 500 blocks off
    public static int range(int viewChunks) {
        return Math.max(16, Math.min(32, (viewChunks - 1) * 16));
    }

    // from a point to the nearest column of the footprint, flat. chunks load by column, so height doesn't decide
    // whether the count can see the build, and getting up or down to it is the builder's job. min/max are block
    // coords, both ends in, so the box runs to max+1
    public static double distance(double x, double z, int minX, int minZ, int maxX, int maxZ) {
        double dx = gap(x, minX, maxX + 1);
        double dz = gap(z, minZ, maxZ + 1);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double gap(double v, double lo, double hi) {
        return v < lo ? lo - v : v > hi ? v - hi : 0;
    }

    // one tick of trying to get there. leaves at more than range, arrives at range/2, so a walk that ends right on the
    // line doesn't flip straight back into another one. moverDone = the walking task finished or gave up without
    // getting us in, that's a failed walk. it shouldn't happen (the walk's goal sits inside the arrive line), it's a
    // backstop so a walk that thinks it's done can't stand there for the whole stuck clock
    public Step step(double distance, int range, boolean loaded, boolean moverDone) {
        if (travelling) {
            if (distance <= range / 2.0) {
                travelling = false;
            } else if (moverDone) {
                return bail();
            } else if (distance > best + range && jumps < MAX_JUMPS) {
                // a death or a teleport threw us way back. that's a new walk, not a stuck one, the clock starts over.
                // capped, or a walk swinging 40 blocks back and forth would reset itself forever
                jumps++;
                best = distance;
                stuck = 0;
                closer = false;
                return Step.TRAVEL;
            } else {
                closer = distance < best - PROGRESS;
                if (closer) {
                    best = distance;
                    stuck = 0;
                } else if (++stuck > MAX_STUCK_TICKS) {
                    return bail();
                }
                return Step.TRAVEL;
            }
        } else if (distance > range) {
            travelling = true;
            best = distance;
            stuck = 0;
            waited = 0;
            jumps = 0;
            closer = true;
            return Step.TRAVEL;
        }
        if (loaded || ++waited > MAX_WAIT_TICKS) {
            reset();
            return Step.THERE;
        }
        return Step.WAIT;
    }

    // the last step was a new best while walking. the task feeds this to GatherGuard so a long honest walk isn't billed
    // as idle. getting thrown back isn't closer, or a swing would keep the guard's clock fresh too
    public boolean gotCloser() {
        return travelling && closer;
    }

    public void reset() {
        travelling = false;
        closer = false;
        stuck = 0;
        waited = 0;
        jumps = 0;
    }

    private Step bail() {
        reset();
        return Step.BAIL;
    }
}
