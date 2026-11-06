package adris.altoclef.tasks.speedrun.gamer;

// "can we see that block right now": a face of it touches air, and a straight line from our eyes to that face crosses
// nothing that blocks the view. SeenFilter's answer was "was in sight once, within 128", which is how the coal detour went for
// coal it glimpsed through a crack and then tunnelled to. every detour uses it (coal ore, gravel). pure, the world half hands
// in the two block questions
public final class DetourSight {
    // a bit past reach. further than this and it is a walk, not a detour, whatever the line of sight says
    public static final double RANGE = 8;
    // how far apart the samples along the ray are. a block is 1, so a corner can slip between two of them at most by a
    // sliver, and a sliver of a corner is not what hides an ore
    private static final double STEP = 0.05;
    // the point we aim at sits this far off the face, on the air side, so the ore itself is not what the ray hits
    private static final double OFF_FACE = 0.01;

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    public interface Cells {
        boolean air(int x, int y, int z);

        // stone, dirt, ore: anything you can't look through
        boolean blocksView(int x, int y, int z);
    }

    private DetourSight() {
    }

    public static boolean visible(Cells w, double eyeX, double eyeY, double eyeZ, int x, int y, int z) {
        return visible(w, eyeX, eyeY, eyeZ, x, y, z, RANGE);
    }

    // the same with a range of the caller's (the take-along looks twice as far, TakeAlongConfig.sightRange)
    public static boolean visible(Cells w, double eyeX, double eyeY, double eyeZ, int x, int y, int z, double range) {
        for (int[] f : FACES) {
            if (!w.air(x + f[0], y + f[1], z + f[2])) {
                continue;
            }
            // the eye has to be on the open side of the face, you can't see the back of a wall
            double cx = x + 0.5 + f[0] * 0.5;
            double cy = y + 0.5 + f[1] * 0.5;
            double cz = z + 0.5 + f[2] * 0.5;
            if ((eyeX - cx) * f[0] + (eyeY - cy) * f[1] + (eyeZ - cz) * f[2] <= 0) {
                continue;
            }
            double tx = cx + f[0] * OFF_FACE;
            double ty = cy + f[1] * OFF_FACE;
            double tz = cz + f[2] * OFF_FACE;
            double dx = tx - eyeX;
            double dy = ty - eyeY;
            double dz = tz - eyeZ;
            if (dx * dx + dy * dy + dz * dz > range * range) {
                continue;
            }
            if (clear(w, eyeX, eyeY, eyeZ, dx, dy, dz, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clear(Cells w, double ex, double ey, double ez, double dx, double dy, double dz, int oreX, int oreY, int oreZ) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(len / STEP);
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;
            if ((bx != oreX || by != oreY || bz != oreZ) && w.blocksView(bx, by, bz)) {
                return false;
            }
        }
        return true;
    }
}
