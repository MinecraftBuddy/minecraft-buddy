package adris.altoclef.util.helpers;

import java.util.Locale;

// the buddy's fork: the way out on foot from a crowd, for the forced drills' count ladder (his remark 1 on (b2): whether a
// flee had a way out at all). from the feet, straight away from the crowd's centre and at 30 and 60 degrees either side, a
// walk block by block on the ground: a step up of one, a step down of up to three, until lava, a drop deeper than three, a
// wall (more than a step up), the edge of the loaded world, or MAX blocks. the longest of the five is the way. Baritone's
// own path is worked out a few ticks later and in pieces, and it goes round what stops this one, so it could not say what
// ends a way; the rig measures what the flee really gained (its distance at 10 and 30 s). pure: the game hands in its cells
public final class FleeWay {
    // a way this long is open ground (the flee's own run distance, DangerForceChain.FLEE_DISTANCE)
    public static final int MAX = 50;
    // the headings tried, degrees off straight away
    static final int[] TURNS = {0, 30, -30, 60, -60};
    static final int STEP_DOWN = 3;

    private FleeWay() {
    }

    public enum Cell {
        // nothing to stand in or on (air, a plant, a torch)
        OPEN,
        // something to stand on that stops the feet (a block)
        SOLID,
        LAVA,
        WATER,
        // not loaded on the client
        UNLOADED
    }

    public interface Cells {
        Cell at(int x, int y, int z);
    }

    public enum End {
        OPEN, LAVA, DROP, WALL, UNLOADED;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // one heading's walk: the blocks walked and what ended it
    public record Walk(int turn, int blocks, End end) {
    }

    // the five headings' walks, the longest first among equals by the order of TURNS
    public record Way(Walk best, Walk[] walks) {
        public String line() {
            StringBuilder sb = new StringBuilder();
            for (Walk w : walks) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(w.turn() > 0 ? "+" : "").append(w.turn()).append(' ').append(w.blocks()).append(' ').append(w.end().word());
            }
            return "best " + best.blocks() + " " + best.end().word() + " (" + (best.turn() > 0 ? "+" : "") + best.turn() + "), " + sb;
        }
    }

    // from feet (fx, fy, fz) away from the centre (cx, cz)
    public static Way find(Cells cells, double fx, int fy, double fz, double cx, double cz) {
        double ax = fx - cx;
        double az = fz - cz;
        double len = Math.sqrt(ax * ax + az * az);
        // (standing on the centre: any way is away; east)
        double base = len < 1e-6 ? 0 : Math.atan2(az, ax);
        Walk[] walks = new Walk[TURNS.length];
        Walk best = null;
        for (int i = 0; i < TURNS.length; i++) {
            double h = base + Math.toRadians(TURNS[i]);
            walks[i] = walk(cells, fx, fy, fz, Math.cos(h), Math.sin(h), TURNS[i]);
            if (best == null || walks[i].blocks() > best.blocks()) best = walks[i];
        }
        return new Way(best, walks);
    }

    // one heading: a block at a time, the feet's height kept as the ground goes
    static Walk walk(Cells cells, double fx, int fy, double fz, double dx, double dz, int turn) {
        int y = fy;
        int lastX = (int) Math.floor(fx);
        int lastZ = (int) Math.floor(fz);
        int blocks = 0;
        for (int d = 1; d <= MAX; d++) {
            int x = (int) Math.floor(fx + dx * d);
            int z = (int) Math.floor(fz + dz * d);
            if (x == lastX && z == lastZ) continue;
            lastX = x;
            lastZ = z;
            Object next = next(cells, x, y, z);
            if (next instanceof End end) return new Walk(turn, blocks, end);
            y = (Integer) next;
            blocks = d;
        }
        return new Walk(turn, blocks, End.OPEN);
    }

    // the feet's height in the next column, or what stops the walk there
    private static Object next(Cells cells, int x, int y, int z) {
        Cell feet = cells.at(x, y, z);
        Cell head = cells.at(x, y + 1, z);
        if (feet == Cell.UNLOADED || head == Cell.UNLOADED) return End.UNLOADED;
        if (feet == Cell.LAVA || head == Cell.LAVA) return End.LAVA;
        if (feet == Cell.SOLID) {
            // a step up of one, with room for the head over it
            if (head == Cell.SOLID || cells.at(x, y + 2, z) == Cell.SOLID) return End.WALL;
            if (cells.at(x, y + 2, z) == Cell.LAVA) return End.LAVA;
            return y + 1;
        }
        if (head == Cell.SOLID) return End.WALL;
        // feet and head open: the ground under them, a step down of up to three
        for (int down = 1; down <= STEP_DOWN + 1; down++) {
            Cell below = cells.at(x, y - down, z);
            if (below == Cell.UNLOADED) return End.UNLOADED;
            if (below == Cell.LAVA) return End.LAVA;
            if (below == Cell.SOLID || below == Cell.WATER) return y - down + 1;
        }
        return End.DROP;
    }
}
