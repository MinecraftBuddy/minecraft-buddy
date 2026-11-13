package adris.altoclef.util.helpers;

// (Tuning gold_poke_shafts, 14 Nov 2026) the gold mine's own dig pattern, pure: the caller checks the world and feeds the
// outcome of each move back. poke holes off a main tunnel: the 1x2 tunnel along a heading, and at every SPACING-th step a
// station with a 1x1 hole each side at head height, as deep as the block reach allows from the tunnel cell (dug standing in
// the tunnel, never walked into). a hole at the feet's height or over the head is out: from an eye at 1.62 the ray to its
// second cell runs through the wall beside the tunnel. the legs of a serpentine: after LEG steps, where the way ahead is
// closed, or where a hole meets a cave, a connector GAP blocks to the side and the next leg back the other way, parallel to
// the last. wide, never thorough
//
// what a block dug shows (each cell whose face shows at some time, the ones dug later among them): a 1x2 tunnel 8 cells a
// step for 2 dug, 4 a block; a hole 4 deep 17 for 4, 4.25 a block (its first cell shows only the cell over it and the one
// beyond, each after 5); a station every 4 steps 66 for 16, 4.1 a block. the holes reach rock 5 to each side of the tunnel
// where the tunnel shows 1, so a vein of gold (clumps of up to 9) 2 to 5 off the tunnel shows too, with half the steps
// walked for a block dug. holes 4 apart: each shows the rows next to it, so one row in four at head height goes unseen and
// two holes never show the same cell
public final class PokePattern {
    // a station every SPACING steps of the main tunnel
    public static final int SPACING = 4;
    // a hole at most this deep: the far cell's middle 4 from an eye in the tunnel cell, inside a reach of 4.5
    public static final int MAX_DEPTH = 4;
    // steps of a leg before the serpentine turns
    public static final int LEG = 64;
    // the connector's length: the next leg runs this far from the last. both legs' holes reach 4 toward each other and show
    // a fifth, so one row between them goes unseen
    public static final int GAP = 12;
    // a connector closed after this many steps still starts the next leg there
    public static final int MIN_GAP = 6;

    private PokePattern() {
    }

    public enum Kind { HOLE, STEP }

    // a cell to dig: a HOLE's cell broken from the station the buddy stands in (side 0 left, 1 right, depth from 1), or the
    // feet cell of a STEP walked into (its head cell dug with it)
    public record Move(Kind kind, int x, int y, int z, int side, int depth) {
    }

    // what blocked() did: turned the run (a connector, its other side, or the next leg), or nothing left to turn to here
    public enum Turn { TURNED, RESET }

    // the deepest hole whose far cell stays within the reach from an eye anywhere in the tunnel cell: the middle of a hole's
    // cell lies about its depth from the eye, half a block more with the feet at the cell's edge
    public static int depthFor(double reach) {
        return Math.max(1, Math.min(MAX_DEPTH, (int) Math.floor(reach - 0.5)));
    }

    // left of a heading: east (1, 0) to north (0, -1)
    public static int[] left(int dx, int dz) {
        return new int[]{dz, -dx};
    }

    // one run of the pattern: where the buddy stands for it, the leg's heading, the serpentine's side, the station's holes
    public static final class Run {
        private int x, y, z;
        private int hx, hz;
        // the side the connectors go to
        private int ax, az;
        private boolean connecting;
        // the connector's side was turned round once at this turn
        private boolean flipped;
        // steps of the leg or the connector so far
        private int steps;
        // the next hole of the station the buddy stands at, -1 for none
        private int hole;
        // bit 0 left, bit 1 right: a hole of this station met a cave on that side
        private int caveSides;
        private final int depth;
        private int legs = 1;

        // a run from the cell the buddy stands in, along (dx, dz), its first station there
        public Run(int x, int y, int z, int dx, int dz, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            hx = dx;
            hz = dz;
            int[] l = left(dx, dz);
            ax = l[0];
            az = l[1];
            this.depth = Math.max(1, Math.min(MAX_DEPTH, depth));
            hole = 0;
        }

        // the next cell to dig: the station's next hole, else the next step of the leg or the connector
        public Move next() {
            if (hole >= 0) {
                int side = hole / depth, k = hole % depth + 1;
                int[] l = left(hx, hz);
                int sx = side == 0 ? l[0] : -l[0], sz = side == 0 ? l[1] : -l[1];
                return new Move(Kind.HOLE, x + sx * k, y + 1, z + sz * k, side, k);
            }
            int dx = connecting ? ax : hx, dz = connecting ? az : hz;
            return new Move(Kind.STEP, x + dx, y, z + dz, -1, 0);
        }

        // the hole's cell under way is open (dug, or open already by the buddy's own digging)
        public void holeDone() {
            if (hole < 0) return;
            hole++;
            if (hole >= 2 * depth) endStation();
        }

        // the rest of this side's hole stays undug: its next cell failed a check. cave: it would have opened into a cave
        // (or water or lava), and after the station the run turns away from that side
        public void skipSide(boolean cave) {
            if (hole < 0) return;
            int side = hole / depth;
            if (cave) caveSides |= 1 << side;
            hole = (side + 1) * depth;
            if (hole >= 2 * depth) endStation();
        }

        private void endStation() {
            hole = -1;
            int caves = caveSides;
            caveSides = 0;
            if (caves != 0 || steps >= LEG) startConnector(caves);
        }

        // a connector to the serpentine's side, or away from the side a hole met a cave on (as that side lies for this leg)
        private void startConnector(int caves) {
            int[] l = left(hx, hz);
            if (caves == 1) {
                ax = -l[0];
                az = -l[1];
            } else if (caves == 2) {
                ax = l[0];
                az = l[1];
            }
            connecting = true;
            flipped = false;
            steps = 0;
        }

        // the step was reached. true when that begins a station or a leg (a segment's end, for the log)
        public boolean stepped() {
            if (connecting) {
                x += ax;
                z += az;
            } else {
                x += hx;
                z += hz;
            }
            steps++;
            if (connecting) {
                if (steps >= GAP) {
                    newLeg();
                    return true;
                }
                return false;
            }
            if (steps % SPACING == 0) {
                hole = 0;
                return true;
            }
            return false;
        }

        // the next leg, back the other way from the connector's end; its first station SPACING steps on
        private void newLeg() {
            connecting = false;
            hx = -hx;
            hz = -hz;
            steps = 0;
            hole = -1;
            legs++;
        }

        // the next step cannot be dug (it would open into a cave, water, lava or a drop, or was not reached in time): a
        // connector from a leg, the other side from a connector's first cell, the next leg from a connector past MIN_GAP;
        // else RESET, and the caller starts a run of its own here
        public Turn blocked() {
            hole = -1;
            caveSides = 0;
            if (!connecting) {
                startConnector(0);
                return Turn.TURNED;
            }
            if (steps == 0 && !flipped) {
                ax = -ax;
                az = -az;
                flipped = true;
                return Turn.TURNED;
            }
            if (steps >= MIN_GAP) {
                newLeg();
                return Turn.TURNED;
            }
            return Turn.RESET;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        // the way the buddy is going: the leg's heading, or the connector's
        public int dx() {
            return connecting ? ax : hx;
        }

        public int dz() {
            return connecting ? az : hz;
        }

        public boolean connecting() {
            return connecting;
        }

        public boolean station() {
            return hole >= 0;
        }

        public int steps() {
            return steps;
        }

        public int legs() {
            return legs;
        }

        public int depth() {
            return depth;
        }
    }
}
