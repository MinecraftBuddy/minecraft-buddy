package adris.altoclef.tasks.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// where a table or furnace goes, picked the way a player does it: look at the floor next to you and click it. the old
// way asked baritone's builder to place it and the builder walked, scaffolded and wandered for 8 seconds a table (and
// sat on a fence forever). pure, the world comes in through Cells, so the scoring tests without a game
final class StationSpots {
    // how far the click can be from the eye. baritone reaches 4.5, the margin is for the bot drifting while it aims
    static final double REACH = 4.0;
    // horizontal search radius around the feet
    static final int RADIUS = 4;
    // standpoint sorting: a cell this many blocks under (or over) our feet is a climb. heavier than distance on purpose
    static final double LEVEL = 6;
    // a station off our feet level is a second choice, always: bigger than any distance in the scan (2 * RADIUS^2 = 32), so a
    // spot on our own level at the far edge of reach still beats one a step up right next to us
    static final double OFF_LEVEL = 100;
    // how far the "can we walk to a standing cell" flood strays from the feet: the scan, plus a cell for the station's
    // neighbour, plus one for walking around
    static final int FLOOD_RADIUS = RADIUS + 2;
    // standing point search for a relocation
    static final int MIN_MOVE = 2;
    static final int MAX_MOVE = 6;
    // how far a swim to shore looks, and how far up or down from our feet a bank can sit
    static final int SHORE_RADIUS = 32;
    static final int SHORE_DY = 3;
    // a full ring of columns is ~160 and each costs a few hundred cell checks, once per relocation (twice a task at most).
    // this used to be 30, and the 30 nearest all look at the same floor we are already failing on
    static final int MAX_STANDPOINTS_TRIED = 200;
    // a failed spot takes its column with it, this far up and down. a click that missed once misses again from here
    static final int BAN_Y = 2;

    private StationSpots() {
    }

    // the questions we ask the world. every coordinate is a block cell
    interface Cells {
        // air or something a block replaces (grass), nothing in the way, not water
        boolean placeable(int x, int y, int z);

        // the face of the block at x,y,z that points (fx,fy,fz) is a full solid face you can build on. fence tops and
        // bottom slabs are not, which is the point of asking instead of "is it solid"
        boolean faceSturdy(int x, int y, int z, int fx, int fy, int fz);

        // we can stand in it (no collision, no liquid)
        boolean passable(int x, int y, int z);

        // standing on the block here is fine: a full block under it, feet and head cells open
        boolean standable(int x, int y, int z);

        // a ray from the eye to the middle of that face of the support hits the face, and nothing else gets in the way
        boolean visible(double eyeX, double eyeY, double eyeZ, int sx, int sy, int sz, int fx, int fy, int fz, int cx, int cy, int cz);

        // a block we could mine out to make room: solid, breakable with what we hold, and not bedrock, a container, an ore
        // or anything wet. mining it must not be a favour to somebody (a chest) or to the bot (the iron we came for)
        boolean carvable(int x, int y, int z);

        // opening the neighbour lets this in: a liquid that flows, or sand and gravel that fall
        boolean floods(int x, int y, int z);

        // a station of ours or any workbench (StationHook.keepStanding): never carved, whatever carvable says. mining the table we
        // just crafted at to make room for the furnace is a table we then have to make again
        default boolean keep(int x, int y, int z) {
            return false;
        }
    }

    // where we are standing and where the eye is (sneaking eye if we will sneak, the caller knows)
    record Stance(int feetX, int feetY, int feetZ, double eyeX, double eyeY, double eyeZ) {
        boolean isOwnCell(int x, int y, int z) {
            return x == feetX && z == feetZ && (y == feetY || y == feetY + 1);
        }
    }

    // `placed` is the cell the block goes in, `support` is the block we click, and the face is the way from one to the other
    record Spot(int x, int y, int z, int sx, int sy, int sz) {
        int faceX() {
            return x - sx;
        }

        int faceY() {
            return y - sy;
        }

        int faceZ() {
            return z - sz;
        }

        boolean isTopFace() {
            return faceY() == 1;
        }
    }

    record Stand(int x, int y, int z) {
    }

    // a block cell: a carve candidate, or the one we already carved
    record Cell(int x, int y, int z) {
    }

    // spots that let us down. banning a cell takes its column, BAN_Y either way
    static final class Bans {
        private final List<int[]> banned = new ArrayList<>();

        void ban(int x, int y, int z) {
            banned.add(new int[]{x, y, z});
        }

        boolean isBanned(int x, int y, int z) {
            for (int[] b : banned) {
                if (b[0] == x && b[2] == z && Math.abs(b[1] - y) <= BAN_Y) {
                    return true;
                }
            }
            return false;
        }

        void clear() {
            banned.clear();
        }
    }

    private static final int[][] HORIZONTALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // level first: a flood that finds flat ground never looks for a staircase
    private static final int[] STEPS = {0, 1, -1};

    // lower is better. distance from our feet, and any height away from them costs OFF_LEVEL (feet level wins, always)
    static double score(double horizontalDistSq, int levelsAway) {
        return horizontalDistSq + (levelsAway == 0 ? 0 : OFF_LEVEL);
    }

    // why a cell was turned down, for the one line that says so when nothing is left
    enum Refusal {
        IN_THE_WAY("in the way"),
        BANNED("banned"),
        SEALS_US_IN("would seal us in"),
        ROOFED("roofed"),
        BOXED_IN("boxed in"),
        NO_FLOOR("no floor"),
        NOT_CLICKABLE("not clickable"),
        CROWDS_A_STATION("crowds a station");

        final String words;

        Refusal(String words) {
            this.words = words;
        }
    }

    // how many cells each reason took out in the floor pass of one search
    static final class Refusals {
        private final Map<Refusal, Integer> counts = new EnumMap<>(Refusal.class);

        void add(Refusal why) {
            counts.merge(why, 1, Integer::sum);
        }

        int count(Refusal why) {
            return counts.getOrDefault(why, 0);
        }

        int total() {
            int n = 0;
            for (int c : counts.values()) {
                n += c;
            }
            return n;
        }

        // "31 no floor, 12 boxed in, 4 not clickable", biggest first
        String summary() {
            List<Map.Entry<Refusal, Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort(Map.Entry.<Refusal, Integer>comparingByValue().reversed());
            StringBuilder out = new StringBuilder();
            for (Map.Entry<Refusal, Integer> e : list) {
                out.append(out.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey().words);
            }
            return out.length() == 0 ? "nothing in range" : out.toString();
        }
    }

    // the best cell to put the block in from where we stand: one we can click from here and walk back to without touching a
    // block (see judge). the floor next to us, else the side of a wall. empty when neither does
    static Optional<Spot> best(Cells w, Stance me, Bans bans) {
        return best(w, me, bans, null, null);
    }

    // `carved` is a cell we mined out ourselves to have somewhere to put the block. it doesn't have to leave a way out: in
    // a 1x1 shaft the other exits are straight up and down, and the hole we dug is the only horizontal neighbour there is.
    // it doesn't need air over it or a floor beside it either, the rock around it is why we dug it
    static Optional<Spot> best(Cells w, Stance me, Bans bans, Cell carved) {
        return best(w, me, bans, carved, null);
    }

    // `why` (if given) gets a count for every cell the floor pass turned down
    static Optional<Spot> best(Cells w, Stance me, Bans bans, Cell carved, Refusals why) {
        Memo memo = new Memo(w, me);
        Spot top = scan(w, memo, me, bans, carved, false, why);
        return top != null ? Optional.of(top) : Optional.ofNullable(scan(w, memo, me, bans, carved, true, null));
    }

    private static Spot scan(Cells w, Memo memo, Stance me, Bans bans, Cell carved, boolean sides, Refusals why) {
        Spot best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                // the cheap part first, the raycasts are what costs
                double distSq = dx * dx + dz * dz;
                // two down is a hole we would have to dig our way back out of, so the scan stops at one either way
                for (int dy = -1; dy <= 1; dy++) {
                    int x = me.feetX() + dx;
                    int y = me.feetY() + dy;
                    int z = me.feetZ() + dz;
                    double score = score(distSq, dy);
                    if (score >= bestScore) {
                        continue;
                    }
                    boolean dugByUs = carved != null && carved.x() == x && carved.y() == y && carved.z() == z;
                    Spot spot = judge(w, memo, me, bans, dugByUs, sides, x, y, z, why);
                    if (spot != null) {
                        best = spot;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    // one cell, cheapest question first. a spot, or null with the reason counted
    private static Spot judge(Cells w, Memo memo, Stance me, Bans bans, boolean dugByUs, boolean sides, int x, int y, int z, Refusals why) {
        if (bans.isBanned(x, y, z)) {
            return refuse(why, Refusal.BANNED);
        }
        if (me.isOwnCell(x, y, z) || !w.placeable(x, y, z)) {
            return refuse(why, Refusal.IN_THE_WAY);
        }
        // the dug cell skips the next two: it is a hole in rock, and rock over it and around it is the whole point. it still
        // needs a standing cell beside it, our own is the usual one
        if (!dugByUs && !leavesAWayOut(w, me, x, y, z)) {
            return refuse(why, Refusal.SEALS_US_IN);
        }
        // the use-walk aims at the top of a station, rock over it is what makes the walk dig
        if (!dugByUs && !w.passable(x, y + 1, z)) {
            return refuse(why, Refusal.ROOFED);
        }
        if (!memo.floorBeside(x, y, z)) {
            return refuse(why, Refusal.BOXED_IN);
        }
        Spot spot = sides ? sideSupport(w, me, x, y, z) : topSupport(w, me, x, y, z);
        if (spot == null) {
            return refuse(why, sides || w.faceSturdy(x, y - 1, z, 0, 1, 0) ? Refusal.NOT_CLICKABLE : Refusal.NO_FLOOR);
        }
        if (!reachableStanding(memo, me, x, y, z)) {
            return refuse(why, Refusal.BOXED_IN);
        }
        if (!leavesStationsUsable(w, memo, x, y, z)) {
            return refuse(why, Refusal.CROWDS_A_STATION);
        }
        return spot;
    }

    private static Spot refuse(Refusals why, Refusal reason) {
        if (why != null) {
            why.add(reason);
        }
        return null;
    }

    // a station beside the cell keeps a standing cell of its own once the block is down, and nothing goes on top of one: a
    // roofed table is a table we dig towards. stations are whatever Cells.keep says, ours or a village's
    private static boolean leavesStationsUsable(Cells w, Memo memo, int x, int y, int z) {
        if (w.keep(x, y - 1, z)) {
            return false;
        }
        for (int[] h : HORIZONTALS) {
            int sx = x + h[0];
            int sz = z + h[1];
            if (!w.keep(sx, y, sz)) {
                continue;
            }
            boolean standingLeft = false;
            for (int[] g : HORIZONTALS) {
                int nx = sx + g[0];
                int nz = sz + g[1];
                standingLeft |= !(nx == x && nz == z) && memo.standable(nx, y, nz);
            }
            if (!standingLeft) {
                return false;
            }
        }
        return true;
    }

    private static final int FOUND_STANDING = 1;
    private static final int FOUND_EDGE = 2;

    // two things about the block going into (x,y,z): some standing cell beside it that we can walk to from our feet, and
    // (if we could walk out of the neighbourhood before) still a walk out of it. a table in the middle of a tunnel is fine,
    // a table that bricks us into the dead end is the same hole we would have to dig out of
    private static boolean reachableStanding(Memo memo, Stance me, int x, int y, int z) {
        boolean wasOpen = memo.wasOpenToTheEdge();
        int found = flood(memo, me, true, x, y, z, wasOpen, null);
        return (found & FOUND_STANDING) != 0 && (!wasOpen || (found & FOUND_EDGE) != 0);
    }

    // a flood over standable cells from our feet, one step up or down at a time, with the block at (bx,by,bz) in the way if
    // `blocked`. stops the moment it has what it was sent for
    // `collect` (if given) is the seen set to fill, and the flood runs to the end instead of stopping early
    private static int flood(Memo memo, Stance me, boolean blocked, int bx, int by, int bz, boolean wantEdge, Set<Long> collect) {
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Set<Long> seen = collect != null ? collect : new HashSet<>();
        open.add(new int[]{me.feetX(), me.feetY(), me.feetZ()});
        seen.add(Memo.key(me.feetX(), me.feetY(), me.feetZ()));
        int found = 0;
        while (!open.isEmpty()) {
            int[] c = open.poll();
            if (blocked && c[1] == by && Math.abs(c[0] - bx) + Math.abs(c[2] - bz) == 1) {
                found |= FOUND_STANDING;
            }
            if (Math.abs(c[0] - me.feetX()) == FLOOD_RADIUS || Math.abs(c[2] - me.feetZ()) == FLOOD_RADIUS || Math.abs(c[1] - me.feetY()) == 3) {
                found |= FOUND_EDGE;
            }
            if (collect == null && (!blocked || (found & FOUND_STANDING) != 0) && (!wantEdge || (found & FOUND_EDGE) != 0)) {
                return found;
            }
            for (int[] h : HORIZONTALS) {
                for (int step : STEPS) {
                    int nx = c[0] + h[0];
                    int ny = c[1] + step;
                    int nz = c[2] + h[1];
                    boolean inTheBlock = blocked && nx == bx && nz == bz && (ny == by || ny == by - 1);
                    if (inTheBlock || Math.abs(nx - me.feetX()) > FLOOD_RADIUS || Math.abs(nz - me.feetZ()) > FLOOD_RADIUS
                            || Math.abs(ny - me.feetY()) > 3 || seen.contains(Memo.key(nx, ny, nz))) {
                        continue;
                    }
                    // seen only once a step into it works: a cell we couldn't squeeze into from one side may be fine from another
                    if (memo.standable(nx, ny, nz) && memo.headRoom(c[0], c[1], c[2], nx, ny, nz, blocked, bx, by, bz)
                            && seen.add(Memo.key(nx, ny, nz))) {
                        open.add(new int[]{nx, ny, nz});
                    }
                }
            }
        }
        return found;
    }

    // one search's answers, asked once each: the flood and the neighbour checks hit the same cells over and over
    private static final class Memo {
        private final Cells w;
        private final Stance me;
        private final Map<Long, Boolean> standable = new HashMap<>();
        private Boolean openToTheEdge;

        Memo(Cells w, Stance me) {
            this.w = w;
            this.me = me;
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        // we are standing in our own cell whatever is under it (a slab, a carpet)
        boolean standable(int x, int y, int z) {
            if (x == me.feetX() && y == me.feetY() && z == me.feetZ()) {
                return true;
            }
            return standable.computeIfAbsent(key(x, y, z), k -> w.standable(x, y, z));
        }

        boolean floorBeside(int x, int y, int z) {
            for (int[] h : HORIZONTALS) {
                if (standable(x + h[0], y, z + h[1])) {
                    return true;
                }
            }
            return false;
        }

        // the step between two standing cells has room for the head: up a block needs the cell over the old head open, down a
        // block needs the cell the head swings through open. the cell the block is going into is not open
        boolean headRoom(int cx, int cy, int cz, int nx, int ny, int nz, boolean blocked, int bx, int by, int bz) {
            if (ny == cy) {
                return true;
            }
            int hx = ny > cy ? cx : nx;
            int hz = ny > cy ? cz : nz;
            int hy = ny > cy ? cy + 2 : cy + 1;
            return !(blocked && hx == bx && hy == by && hz == bz) && w.passable(hx, hy, hz);
        }

        // can we walk out of the neighbourhood as it stands (the flood reaches its edge). asked once a search
        boolean wasOpenToTheEdge() {
            if (openToTheEdge == null) {
                openToTheEdge = (flood(this, me, false, 0, 0, 0, true, null) & FOUND_EDGE) != 0;
            }
            return openToTheEdge;
        }
    }

    private static Spot topSupport(Cells w, Stance me, int x, int y, int z) {
        return clickable(w, me, new Spot(x, y, z, x, y - 1, z)) ? new Spot(x, y, z, x, y - 1, z) : null;
    }

    // the wall next to the cell, whichever of them the eye sees from nearest
    private static Spot sideSupport(Cells w, Stance me, int x, int y, int z) {
        Spot best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int[] h : HORIZONTALS) {
            Spot spot = new Spot(x, y, z, x - h[0], y, z - h[1]);
            double dist = faceDistSq(me, spot);
            if (dist < bestDist && clickable(w, me, spot)) {
                best = spot;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean clickable(Cells w, Stance me, Spot s) {
        return faceDistSq(me, s) <= REACH * REACH
                && w.faceSturdy(s.sx(), s.sy(), s.sz(), s.faceX(), s.faceY(), s.faceZ())
                && w.visible(me.eyeX(), me.eyeY(), me.eyeZ(), s.sx(), s.sy(), s.sz(), s.faceX(), s.faceY(), s.faceZ(), s.x(), s.y(), s.z());
    }

    // eye to the middle of the face we would click
    static double faceDistSq(Stance me, Spot s) {
        double dx = s.sx() + 0.5 + s.faceX() * 0.5 - me.eyeX();
        double dy = s.sy() + 0.5 + s.faceY() * 0.5 - me.eyeY();
        double dz = s.sz() + 0.5 + s.faceZ() * 0.5 - me.eyeZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // the block can't wall us in: after it goes down some neighbour of our feet cell is still open at both feet and head
    // height. nobody wants a table that is the last door out of a hole
    static boolean leavesAWayOut(Cells w, Stance me, int x, int y, int z) {
        for (int[] h : HORIZONTALS) {
            int nx = me.feetX() + h[0];
            int nz = me.feetZ() + h[1];
            boolean taken = nx == x && nz == z && (y == me.feetY() || y == me.feetY() + 1);
            if (!taken && w.passable(nx, me.feetY(), nz) && w.passable(nx, me.feetY() + 1, nz)) {
                return true;
            }
        }
        return false;
    }

    // blocks to mine out when nothing around us takes a station, best first: beside our feet (what a player does in a tunnel:
    // knock out the wall and put the furnace in the hole), then two out along a line that is already open. each needs a full
    // block under it for the floor and nothing next to it that would pour in once it is open. never beside our head: a
    // station up there has no cell to stand beside it at its own level, and the walk back to it would pillar one
    static List<Cell> carveCandidates(Cells w, Stance me, Bans banned) {
        List<Cell> out = new ArrayList<>();
        for (int[] h : HORIZONTALS) {
            addCarve(w, me, banned, out, me.feetX() + h[0], me.feetY(), me.feetZ() + h[1]);
        }
        for (int[] h : HORIZONTALS) {
            // the look down at the floor two out goes through the cell between, so that one has to be open. and it is the
            // cell we stand in to use the station, so it needs a floor too
            if (w.standable(me.feetX() + h[0], me.feetY(), me.feetZ() + h[1])) {
                addCarve(w, me, banned, out, me.feetX() + 2 * h[0], me.feetY(), me.feetZ() + 2 * h[1]);
            }
        }
        return out;
    }

    private static void addCarve(Cells w, Stance me, Bans banned, List<Cell> out, int x, int y, int z) {
        if (banned.isBanned(x, y, z) || w.keep(x, y, z) || !w.carvable(x, y, z)) {
            return;
        }
        // a floor to stand the block on, in reach. the look from our eye down to that floor stays inside our own cell and the
        // one we open, so unlike best() there is no raycast to ask (the cell is still solid right now, a raycast would lie)
        Spot onFloor = new Spot(x, y, z, x, y - 1, z);
        if (!w.faceSturdy(x, y - 1, z, 0, 1, 0) || faceDistSq(me, onFloor) > REACH * REACH) {
            return;
        }
        // above and the four sides. the floor is covered by being a sturdy, dry face
        if (w.floods(x, y + 1, z) || w.floods(x + 1, y, z) || w.floods(x - 1, y, z) || w.floods(x, y, z + 1) || w.floods(x, y, z - 1)) {
            return;
        }
        out.add(new Cell(x, y, z));
    }

    // somewhere 2-6 blocks away to stand where best() finds a spot, nearest first. empty if the whole neighbourhood is bad.
    // eyeHeight is the eye over the feet (sneaking one, if the placing sneaks)
    static Optional<Stand> standpoint(Cells w, Stance me, Bans bans, double eyeHeight) {
        List<Stand> stands = new ArrayList<>();
        for (int dx = -MAX_MOVE; dx <= MAX_MOVE; dx++) {
            for (int dz = -MAX_MOVE; dz <= MAX_MOVE; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < MIN_MOVE) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    int x = me.feetX() + dx;
                    int y = me.feetY() + dy;
                    int z = me.feetZ() + dz;
                    // open ground only: a standing cell with another one beside it. best() below asks the rest (a spot we can
                    // click, with room around it), this is the cheap half that keeps the 200 tries off tunnel mouths
                    if (!bans.isBanned(x, y, z) && w.standable(x, y, z) && floorBeside(w, x, y, z)) {
                        stands.add(new Stand(x, y, z));
                    }
                }
            }
        }
        // and one a plain walk gets to. the walk is baritone's own and breaks or places if that is cheaper, so a stand behind a
        // wall or across a gap is a dig or a bridge, which is the thing this is trying not to do
        Set<Long> walkable = new HashSet<>();
        flood(new Memo(w, me), me, false, 0, 0, 0, false, walkable);
        stands.removeIf(s -> !walkable.contains(Memo.key(s.x(), s.y(), s.z())));
        stands.sort(Comparator.comparingDouble((Stand s) -> sq(s.x() - me.feetX()) + sq(s.z() - me.feetZ()) + LEVEL * Math.abs(s.y() - me.feetY())));
        int tried = 0;
        for (Stand s : stands) {
            if (tried++ >= MAX_STANDPOINTS_TRIED) {
                break;
            }
            Stance there = new Stance(s.x(), s.y(), s.z(), s.x() + 0.5, s.y() + eyeHeight, s.z() + 0.5);
            if (best(w, there, bans).isPresent()) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    // swimming with a table in the bag: the nearest dry stand within radius, straight line, no walk flood (baritone swims fine,
    // the flood only steps on floors). one with floor beside it wins over a lone stand, since the pick after the swim needs
    // somewhere to put the table. empty if the water goes on past radius
    static Optional<Stand> nearestShore(Cells w, Stance me, int radius) {
        Stand best = null;
        Stand bestLone = null;
        double bestD = Double.MAX_VALUE;
        double bestLoneD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = sq(dx) + sq(dz);
                // a stand with floor beside it that close already wins, nothing in this column can beat it
                if (flat >= bestD || flat > sq(radius)) {
                    continue;
                }
                for (int dy = -SHORE_DY; dy <= SHORE_DY; dy++) {
                    int x = me.feetX() + dx;
                    int y = me.feetY() + dy;
                    int z = me.feetZ() + dz;
                    double d = flat + sq(dy);
                    if (d >= bestD && d >= bestLoneD) {
                        continue;
                    }
                    if (!w.standable(x, y, z)) {
                        continue;
                    }
                    if (floorBeside(w, x, y, z)) {
                        if (d < bestD) {
                            bestD = d;
                            best = new Stand(x, y, z);
                        }
                    } else if (d < bestLoneD) {
                        bestLoneD = d;
                        bestLone = new Stand(x, y, z);
                    }
                }
            }
        }
        return Optional.ofNullable(best != null ? best : bestLone);
    }

    private static boolean floorBeside(Cells w, int x, int y, int z) {
        for (int[] h : HORIZONTALS) {
            if (w.standable(x + h[0], y, z + h[1])) {
                return true;
            }
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }
}
