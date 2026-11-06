package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.Optional;

// a loaded frame far away (or far below) is not something to walk at: the room search digs down from dry land near it instead.
// the pure half of that, so the rule and the column pick are testable without a world
final class RoomRetarget {
    // close enough to walk straight at the ring: this near sideways and within a dig-less climb vertically
    static final double NEAR_HORIZONTAL = 48;
    static final double NEAR_VERTICAL = 12;
    // how far from the frame we look for dry land. inside NEAR_HORIZONTAL so the dig column itself counts as near
    static final int SEARCH_RADIUS = 40;
    // the lava pool sits right under the portal, keep the dig column well off it
    static final double KEEP_OFF_RING = 6;
    // blocks under the surface that must be free of fluid (a dig column goes through them)
    static final int FLUID_DEPTH = 4;
    static final int MAX_RETARGETS = 2;
    // the approach counts as stuck when it has not come this much closer in this long
    static final double PROGRESS_BLOCKS = 3;
    static final double PROGRESS_SECONDS = 10;

    interface Probe {
        boolean loaded(int x, int z);

        // y of the top block that stops motion or holds fluid (so an ocean reads as its surface water)
        int topY(int x, int z);

        boolean solid(int x, int y, int z);

        boolean fluid(int x, int y, int z);
    }

    private RoomRetarget() {
    }

    // walk straight at it only from here, anything else goes down a dry column first
    static boolean near(double px, double py, double pz, int fx, int fy, int fz) {
        return Math.hypot(fx - px, fz - pz) <= NEAR_HORIZONTAL && Math.abs(fy - py) <= NEAR_VERTICAL;
    }

    static boolean progressed(double bestDist, double dist) {
        return dist <= bestDist - PROGRESS_BLOCKS;
    }

    // solid on top, no water or lava on it or in the few blocks a dig goes through (ice over an ocean fails on what is under it)
    static boolean dry(Probe probe, int x, int z) {
        if (!probe.loaded(x, z)) {
            return false;
        }
        int top = probe.topY(x, z);
        if (!probe.solid(x, top, z) || probe.fluid(x, top, z) || probe.fluid(x, top + 1, z)) {
            return false;
        }
        for (int i = 1; i <= FLUID_DEPTH; i++) {
            if (probe.fluid(x, top - i, z)) {
                return false;
            }
        }
        return true;
    }

    // the nearest dry column to the frame's x/z (square rings out, nearest of the first ring that has one), at least
    // KEEP_OFF_RING from the ring centre. empty = nothing in range, the caller keeps the old straight walk
    static Optional<int[]> pickColumn(Probe probe, int fx, int fz, int cx, int cz) {
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = fx + dx;
                    int z = fz + dz;
                    double d = Math.hypot(dx, dz);
                    // the square's corners are further than the radius, and the near rule is radial
                    if (d > SEARCH_RADIUS || d >= bestD || Math.hypot(x - cx, z - cz) < KEEP_OFF_RING || !dry(probe, x, z)) {
                        continue;
                    }
                    bestD = d;
                    best = new int[]{x, z};
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
