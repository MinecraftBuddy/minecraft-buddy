package adris.altoclef.util.helpers;

import java.util.List;

// which way to sprint for the first few ticks of a creeper step, before baritone has a path. no world in here: the
// caller hands in a lookup for what a cell is, so every case can be tested with a map of fake blocks
public final class CreeperEscape {

    // how far along the heading we look, and how often. half a block so a diagonal never hops over a cell
    static final double AHEAD = 2;
    static final double STEP = 0.5;
    // a jump that has to happen inside this much of runway, any earlier and we are airborne over the wrong thing
    static final double JUMP_RUNWAY = 1;
    // falls up to three blocks cost nothing, the fourth hurts
    static final int MAX_DROP = 3;
    // straight away first, then either side. the sign is just which side gets tried first
    static final int[] FAN = {0, 45, -45, 90, -90};

    private CreeperEscape() {
    }

    public enum Kind {
        // nothing to collide with and no liquid
        OPEN,
        // a body does not go through it, and it is a block's height at most so one jump gets onto it
        SOLID,
        // taller than a jump (fences, walls): not through it, not onto it, not along it
        WALL,
        // water is fine to run through and to land in, it is just slow
        WATER,
        // lava, fire, cactus, berries, powder snow, cobweb: not into it, not onto it
        DANGER
    }

    public interface Cells {
        Kind kind(int x, int y, int z);
    }

    // degrees is how far off directly-away this is (0 = straight), dx/dz the unit direction, jump = a one block step is
    // coming up inside the runway
    public record Heading(int degrees, double dx, double dz, boolean jump) {
        // minecraft yaw: 0 faces +z, 90 faces -x
        public float yaw() {
            return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
    }

    // one creeper's spot, x/z only
    public record At(double x, double z) {
    }

    public static Heading choose(double px, double py, double pz, double cx, double cz, Cells cells) {
        return choose(px, py, pz, List.of(new At(cx, cz)), cells);
    }

    // null when no heading is safe (or the nearest creeper is on top of us and "away" means nothing), and then the path has it.
    // "away" is from the nearest one and the fan goes round from there, but a heading is only as good as the closest creeper it
    // leaves us beside: two on opposite sides score the sidestep best, and a second one behind the first is never run into
    public static Heading choose(double px, double py, double pz, List<At> creepers, Cells cells) {
        return pick(px, py, pz, creepers, List.of(), cells);
    }

    // same, but a heading that runs at some other mob (a zombie standing where "away" points) is passed over. when that leaves
    // nothing the creeper only answer stands: a bad heading is still better than no sprint at all, the creeper is the one that
    // explodes
    public static Heading choose(double px, double py, double pz, List<At> creepers, List<At> others, Cells cells) {
        Heading h = pick(px, py, pz, creepers, others, cells);
        return h != null || others.isEmpty() ? h : pick(px, py, pz, creepers, List.of(), cells);
    }

    // a mob this close and this much in front of a heading is something we would run into
    static final double OTHER_RANGE = 10;
    static final double OTHER_CONE_COS = Math.cos(Math.toRadians(45));

    private static boolean intoOther(double px, double pz, double dx, double dz, List<At> others) {
        for (At o : others) {
            double d = Math.hypot(o.x() - px, o.z() - pz);
            if (d < 1e-3 || d > OTHER_RANGE) continue;
            // 45 itself counts: a sidestep that brushes past a zombie is still a sidestep next to a zombie
            if (((o.x() - px) * dx + (o.z() - pz) * dz) / d >= OTHER_CONE_COS - 1e-6) return true;
        }
        return false;
    }

    private static Heading pick(double px, double py, double pz, List<At> creepers, List<At> others, Cells cells) {
        At nearest = null;
        double away = Double.POSITIVE_INFINITY;
        for (At c : creepers) {
            double d = Math.hypot(px - c.x(), pz - c.z());
            if (d < away) {
                away = d;
                nearest = c;
            }
        }
        if (nearest == null || away < 1e-3) return null;
        double ax = (px - nearest.x()) / away;
        double az = (pz - nearest.z()) / away;
        double closest = closest(px, pz, creepers);
        Heading best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int degrees : FAN) {
            double rad = Math.toRadians(degrees);
            double dx = ax * Math.cos(rad) - az * Math.sin(rad);
            double dz = ax * Math.sin(rad) + az * Math.cos(rad);
            // sideways is only worth it if it still gains a little. nothing in the fan goes backwards, but the check is
            // what keeps it that way if somebody widens the fan
            double score = closest(px + dx * AHEAD, pz + dz * AHEAD, creepers);
            if (score <= closest + 0.01) continue;
            // (strictly better only, so straight on wins a tie and the fan order stays the tiebreak)
            if (score <= bestScore + 1e-6) continue;
            int jump = runway(px, py, pz, dx, dz, cells);
            if (jump >= 0 && !intoOther(px, pz, dx, dz, others)) {
                best = new Heading(degrees, dx, dz, jump == 1);
                bestScore = score;
            }
        }
        return best;
    }

    private static double closest(double x, double z, List<At> creepers) {
        double min = Double.POSITIVE_INFINITY;
        for (At c : creepers) min = Math.min(min, Math.hypot(x - c.x(), z - c.z()));
        return min;
    }

    // -1 = not safe to run, 0 = run, 1 = run and jump
    private static int runway(double px, double py, double pz, double dx, double dz, Cells cells) {
        // standing on farmland or soul sand puts py a hair under the next block up, so nudge it or the floor reads as a step
        int y = (int) Math.floor(py + 0.2);
        int lastX = (int) Math.floor(px);
        int lastZ = (int) Math.floor(pz);
        boolean jump = false;
        for (double s = STEP; s <= AHEAD + 1e-9; s += STEP) {
            int x = (int) Math.floor(px + dx * s);
            int z = (int) Math.floor(pz + dz * s);
            if (x == lastX && z == lastZ) continue;
            // crossing a corner the body clips both cells it touches, so a diagonal through a wall corner is a wall
            if (x != lastX && z != lastZ && (!free(cells, x, y, lastZ) || !free(cells, lastX, y, z))) return -1;
            Kind feet = cells.kind(x, y, z);
            Kind head = cells.kind(x, y + 1, z);
            if (feet == Kind.DANGER || head == Kind.DANGER || feet == Kind.WALL || head == Kind.WALL) return -1;
            if (feet == Kind.SOLID) {
                // one block up is a jump, a wall two high (or a low ceiling over the step) is a wall. the cell over our own
                // head has to be open too or the jump just bonks
                Kind over = cells.kind(x, y + 2, z);
                if (!passable(head) || !passable(over)) return -1;
                if (!passable(cells.kind(lastX, y + 2, lastZ))) return -1;
                y++;
                if (s <= JUMP_RUNWAY) jump = true;
            } else {
                if (head == Kind.SOLID) return -1;
                if (feet != Kind.WATER) {
                    // a flag and not -1 for "none": below y 0 the ground cell itself is negative (deepslate caves)
                    boolean found = false;
                    int ground = y;
                    for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
                        Kind below = cells.kind(x, y - drop, z);
                        if (below == Kind.DANGER || below == Kind.WALL) return -1;
                        if (below == Kind.SOLID || below == Kind.WATER) {
                            ground = y - drop + 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) return -1;
                    y = ground;
                }
            }
            lastX = x;
            lastZ = z;
        }
        return jump ? 1 : 0;
    }

    private static boolean passable(Kind k) {
        return k == Kind.OPEN || k == Kind.WATER;
    }

    // a cell a body can be in, both feet and head
    private static boolean free(Cells cells, int x, int y, int z) {
        return passable(cells.kind(x, y, z)) && passable(cells.kind(x, y + 1, z));
    }
}
