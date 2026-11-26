package adris.altoclef.tasks.resources;

import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.util.helpers.BucketSourceRules;
import adris.altoclef.util.helpers.LavaBreak;
import adris.altoclef.util.helpers.SafeStand;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

// (Tuning pool_cast, off, 26 Nov 2026) the obsidian cast at one cell beside a lava pool, the same cell for every block: the
// cell stood a median 33 blocks from its lava (12 up), and the walk between took 27% of the phase, where 6 of 10 fatal
// fights began (the read heel-obsidian-time). no world here: the cells come in through Grid, so it can be tested.
//
// the cast's shape is fixed (PlaceObsidianBucketTask): P the cell the lava goes in, the stand S one west and one up, the
// clicks on west faces, the frame's walls at P + (1, 0, 0) and (1, 1, 0) closing the east side. so the pool lies east of the
// cell, no lava within LAVA_CLEAR sideways of the cell or the stand (an ordinary hit carries the feet 2.5 blocks,
// SafeStand.HIT), the cell's floor at least one over the lava's top, and the pool's nearest source within POOL_FAR so the
// scoop is a step. the cast's own test (every block within 3 breakable) fails anything near lava, so the cell has its own:
// LavaBreak on the cells the cast digs, SafeStand on the stand with the frame built, out of the sky and the deep dark
public final class PoolCast {
    // no lava this near the cell's or the stand's column (centre to centre, sideways)
    static final double LAVA_CLEAR = 3;
    // the pool's nearest source at most this far sideways from the cell; lava within it lies east and under the floor
    static final double POOL_FAR = 5;
    // the pool's source this many under the cell: its floor (y - 1) at least one over the lava
    static final int POOL_DY_MIN = 2, POOL_DY_MAX = 4;
    // the lava rules look this high over the cell: one over the stand's head
    static final int LAVA_UP = 3;
    // cells looked at around a lava source, sideways, and the cheapest of them whose stand is tested
    static final int SCAN = 6;
    static final int STAND_TRIES = 40;
    // a frame block the cast has to place costs as much as this many blocks of walk: a cell dug into rock first
    static final double FRAME_COST = 2;
    // ruined pinned cells that give the pool up, and how long a search for a cell may take (game ticks)
    static final int MAX_RUINED = 2;
    static final long SEARCH_TICKS = 20 * 60;
    // lava sources whose surroundings gave no cell, at most, before the pool is given up
    static final int MAX_TRIED = 24;
    // a lava source this near one already tried is not tried again (sideways and up or down)
    static final int TRIED_NEAR = 4;
    // the stand within NEAR: the cast is handed out (its own walk does the rest). after the buddy was there, further than LEASH
    // (a fight carried it off): a new cell where the buddy is, not the walk back
    static final double NEAR = 6;
    static final double LEASH = 24;
    // a walk to the pinned cell that comes no block nearer in this long got nowhere (game ticks the task ran)
    static final long WALK_STALL_TICKS = 20 * 60;
    // natural obsidian while the pool goes on: only this near (the pinned cell, or the buddy while none), and with a floor
    // under it
    static final double NATURAL_REACH = 16;
    // (the smoke of 27 Nov 2026) the cast at the pinned cell fetches its lava only within POOL_FETCH of the cell, and the cell
    // is picked only by a pool with POOL_MIN sources there that the fetch takes: the first pool was one source under a lid,
    // and after its scoop the fetch found nothing it took and wandered off. a fetch with an empty bucket in the bag and no
    // lava in POOL_FETCH_TICKS ruins the cell
    static final double POOL_FETCH = 8;
    static final int POOL_MIN = 4;
    static final long POOL_FETCH_TICKS = 20 * 30;
    // the mob defense held the wheel this recently: the buddy's way off the cell was a fight's (game ticks)
    static final long FIGHT_RECENT_TICKS = 20 * 10;

    // the cells the cast digs: the lava cell, the water cell, the air over it, the stand and its head room
    static final Vec3i[] DUG = {new Vec3i(0, 0, 0), new Vec3i(0, 1, 0), new Vec3i(0, 2, 0), new Vec3i(-1, 1, 0), new Vec3i(-1, 2, 0)};

    private PoolCast() {
    }

    // the world as the cell test needs it
    public interface Grid {
        // lava (a source or flowing), water, open or solid
        Cell at(int x, int y, int z);

        // a lava source, the bucket's to scoop
        default boolean source(int x, int y, int z) {
            return at(x, y, z) == Cell.LAVA;
        }

        // a block the cast can't dig in time (obsidian without a diamond pick, bedrock), or a cell not loaded
        default boolean hard(int x, int y, int z) {
            return false;
        }

        // a block lava sets on fire
        default boolean flammable(int x, int y, int z) {
            return false;
        }

        // a block that falls with nothing under it
        default boolean loose(int x, int y, int z) {
            return false;
        }

        // out of the sky's light and not in the deep dark (CollectObsidianTask.sheltered)
        default boolean sheltered(int x, int y, int z) {
            return true;
        }
    }

    public static BlockPos stand(BlockPos cell) {
        return cell.offset(-1, 1, 0);
    }

    // a lava cell: x, y, z, and 1 for a source the fetch takes (fetchable)
    private static List<int[]> lavaIn(Grid g, int x0, int x1, int y0, int y1, int z0, int z1) {
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (g.at(x, y, z) == Cell.LAVA) out.add(new int[]{x, y, z, fetchable(g, x, y, z) ? 1 : 0});
                }
            }
        }
        return out;
    }

    // a source the cast's lava fetch takes (CollectBucketLiquidTask): a source with no liquid over it
    // (WorldHelper.isSourceBlock), open to a ray or under a lid dug from the side (BucketSourceRules), and no lid that takes
    // too long to dig. a source in the pool's middle, under more lava, is not one
    static boolean fetchable(Grid g, int x, int y, int z) {
        if (!g.source(x, y, z)) return false;
        Cell above = g.at(x, y + 1, z);
        if (above == Cell.LAVA || above == Cell.WATER) return false;
        if (above == Cell.SOLID && g.hard(x, y + 1, z)) return false;
        return BucketSourceRules.classify(new BucketSourceRules.Cells() {
            @Override
            public boolean solid(int cx, int cy, int cz) {
                return g.at(cx, cy, cz) == Cell.SOLID;
            }

            @Override
            public boolean open(int cx, int cy, int cz) {
                return g.at(cx, cy, cz) != Cell.SOLID;
            }

            @Override
            public boolean free(int cx, int cy, int cz) {
                return g.at(cx, cy, cz) == Cell.OPEN;
            }
        }, x, y, z) != BucketSourceRules.Kind.NONE;
    }

    // a source within the pinned cell's fetch (the leaveAlone of the cast's lava fetch)
    public static boolean inFetch(BlockPos cell, BlockPos source) {
        return cell.distSqr(source) <= POOL_FETCH * POOL_FETCH;
    }

    // the sources the fetch takes within POOL_FETCH of the cell, banned ones left out
    static int poolSources(Grid g, BlockPos cell, Predicate<BlockPos> banned) {
        int r = (int) POOL_FETCH, n = 0;
        for (int[] l : lavaIn(g, cell.getX() - r, cell.getX() + r, cell.getY() - r, cell.getY() + r, cell.getZ() - r, cell.getZ() + r)) {
            BlockPos p = new BlockPos(l[0], l[1], l[2]);
            if (l[3] == 1 && inFetch(cell, p) && !banned.test(p)) n++;
        }
        return n;
    }

    // why the cell at x y z is no cast cell by a pool, or null when it is one
    public static String refuse(Grid g, int x, int y, int z) {
        int r = (int) POOL_FETCH;
        String why = refuseNear(g, lavaIn(g, x - r, x + r, y - r, y + r, z - r, z + r), x, y, z);
        return why != null ? why : refuseStand(g, x, y, z);
    }

    // the rules on the cells and the lava (cheap), lava the cells within reach of the cell
    static String refuseNear(Grid g, List<int[]> lava, int x, int y, int z) {
        LavaBreak.Cells lb = lavaCells(g);
        for (Vec3i d : DUG) {
            int cx = x + d.getX(), cy = y + d.getY(), cz = z + d.getZ();
            Cell c = g.at(cx, cy, cz);
            if (c == Cell.LAVA || c == Cell.WATER) return "lava or water where the cast digs";
            if (c == Cell.SOLID && g.hard(cx, cy, cz)) return "a block the cast can't dig";
            if (LavaBreak.refuse(lb, cx, cy, cz)) return "a block the cast digs would let lava in";
        }
        if (g.at(x, y - 1, z) != Cell.SOLID) return "no floor under the cell";
        // sand or gravel over the dug column or the stand's head falls into the cast once the cells under it are dug
        if (g.loose(x, y + 3, z) || g.loose(x - 1, y + 3, z)) return "sand or gravel over the cells the cast digs";
        for (Vec3i f : PlaceObsidianBucketTask.CAST_FRAME) {
            int fx = x + f.getX(), fy = y + f.getY(), fz = z + f.getZ();
            Cell c = g.at(fx, fy, fz);
            if (c == Cell.LAVA || c == Cell.WATER) return "lava or water in the frame";
            if (c == Cell.SOLID && g.flammable(fx, fy, fz)) return "a frame block that burns";
            if (c == Cell.SOLID && g.loose(fx, fy, fz) && g.at(fx, fy - 1, fz) != Cell.SOLID) return "a frame block that would fall";
        }
        boolean pool = false;
        int fetch = 0;
        double clear = LAVA_CLEAR * LAVA_CLEAR, far = POOL_FAR * POOL_FAR, fetchSq = POOL_FETCH * POOL_FETCH;
        for (int[] l : lava) {
            if (l[3] == 1) {
                double fx = l[0] - x, fy = l[1] - y, fz = l[2] - z;
                if (fx * fx + fy * fy + fz * fz <= fetchSq) fetch++;
            }
            if (l[1] < y - POOL_DY_MAX || l[1] > y + LAVA_UP) continue;
            double dz = l[2] - z, dx = l[0] - x, dxs = l[0] - (x - 1);
            double toCell = dx * dx + dz * dz;
            if (toCell < clear || dxs * dxs + dz * dz < clear) return "lava within " + (int) LAVA_CLEAR + " of the cell or the stand";
            if (toCell > far) continue;
            if (l[1] >= y - 1) return "lava level with the cell's floor or above it";
            if (l[0] <= x) return "lava on the stand's side, not east of the cell";
            if (l[3] == 1 && l[1] <= y - POOL_DY_MIN) pool = true;
        }
        if (!pool) return "no pool source the fetch takes within " + (int) POOL_FAR + " under the floor";
        return fetch >= POOL_MIN ? null : "only " + fetch + " sources the fetch takes within " + (int) POOL_FETCH;
    }

    // the rules on the stand (costly): out of the sky, and no ordinary hit from any side ends in lava or a fall that hurts,
    // with the frame built and the cells the cast digs open
    static String refuseStand(Grid g, int x, int y, int z) {
        if (!g.sheltered(x, y, z) || !g.sheltered(x - 1, y + 1, z)) return "under the sky or in the deep dark";
        if (SafeStand.unsafe(built(g, x, y, z), x - 1, y + 1, z)) return "a hit can throw the buddy off the stand into lava or a fall";
        return null;
    }

    // the grid as it is once the cast has dug its cells and built its frame
    static Cells built(Grid g, int x, int y, int z) {
        return (cx, cy, cz) -> {
            int dx = cx - x, dy = cy - y, dz = cz - z;
            for (Vec3i d : DUG) {
                if (d.getX() == dx && d.getY() == dy && d.getZ() == dz) return Cell.OPEN;
            }
            for (Vec3i f : PlaceObsidianBucketTask.CAST_FRAME) {
                if (f.getX() == dx && f.getY() == dy && f.getZ() == dz) return Cell.SOLID;
            }
            return g.at(cx, cy, cz);
        };
    }

    static LavaBreak.Cells lavaCells(Grid g) {
        return new LavaBreak.Cells() {
            @Override
            public boolean lava(int x, int y, int z) {
                return g.at(x, y, z) == Cell.LAVA;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                return g.at(x, y, z) == Cell.SOLID;
            }

            @Override
            public boolean open(int x, int y, int z) {
                return g.at(x, y, z) == Cell.OPEN;
            }

            @Override
            public boolean loose(int x, int y, int z) {
                return g.loose(x, y, z);
            }
        };
    }

    // the frame blocks the cast still has to place at this cell
    static int frameMissing(Grid g, int x, int y, int z) {
        int n = 0;
        for (Vec3i f : PlaceObsidianBucketTask.CAST_FRAME) {
            if (g.at(x + f.getX(), y + f.getY(), z + f.getZ()) != Cell.SOLID) n++;
        }
        return n;
    }

    // what a cell costs from where the buddy stands: the way to its stand, and FRAME_COST a frame block to place
    static double cost(Grid g, int x, int y, int z, BlockPos from) {
        double dx = x - 1 - from.getX(), dy = y + 1 - from.getY(), dz = z - from.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + FRAME_COST * frameMissing(g, x, y, z);
    }

    // a picked cell, the pool's source nearest to it and how far that is sideways
    public record Pick(BlockPos cell, BlockPos lava, double gap) {
    }

    // the cheapest cell (cost) that passes, among those within SCAN sideways of the lava source and POOL_DY_MIN to
    // POOL_DY_MAX over it; banned cells are skipped. null for none
    public static Pick pick(Grid g, BlockPos lava, BlockPos from, Predicate<BlockPos> banned) {
        int lx = lava.getX(), ly = lava.getY(), lz = lava.getZ();
        // every lava cell any candidate's rules look at: POOL_FETCH round it, and the stand's LAVA_CLEAR
        int r = SCAN + (int) POOL_FETCH, f = (int) POOL_FETCH;
        List<int[]> near = lavaIn(g, lx - r, lx + r, ly + POOL_DY_MIN - f, ly + POOL_DY_MAX + f, lz - r, lz + r);
        List<double[]> passing = new ArrayList<>();
        for (int y = ly + POOL_DY_MIN; y <= ly + POOL_DY_MAX; y++) {
            for (int x = lx - SCAN; x <= lx + SCAN; x++) {
                for (int z = lz - SCAN; z <= lz + SCAN; z++) {
                    if (banned.test(new BlockPos(x, y, z))) continue;
                    if (refuseNear(g, near, x, y, z) != null) continue;
                    passing.add(new double[]{cost(g, x, y, z, from), x, y, z});
                }
            }
        }
        passing.sort((a, b) -> Double.compare(a[0], b[0]));
        // the stand's test follows every push line out of the stand: at most STAND_TRIES of them a pick, so a tick stays short
        for (int i = 0; i < passing.size() && i < STAND_TRIES; i++) {
            double[] c = passing.get(i);
            int x = (int) c[1], y = (int) c[2], z = (int) c[3];
            if (refuseStand(g, x, y, z) != null) continue;
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int[] l : near) {
                if (l[3] != 1 || l[1] > y - POOL_DY_MIN || l[1] < y - POOL_DY_MAX) continue;
                double d = Math.hypot(l[0] - x, l[2] - z);
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
            return new Pick(new BlockPos(x, y, z), new BlockPos(best[0], best[1], best[2]), bestD);
        }
        return null;
    }

    // natural obsidian the collect may go for while the pool goes on: near, and with something under it to land on
    static boolean naturalTaken(double distanceSq, boolean floor) {
        return floor && distanceSq <= NATURAL_REACH * NATURAL_REACH;
    }

    // what the parent does with a pinned cell, by how far the feet are from the stand. a new cell only when a fight carried
    // the buddy past LEASH (fightRecent); a walk of its own (the fetch, the mining, a pickup, a take-along) goes back. in the
    // smoke a lava fetch that found nothing wandered 24 off and the pin was let go for it
    enum Step {CAST, WALK, BACK, REPICK}

    static Step step(boolean reached, double toStand, boolean fightRecent) {
        if (toStand <= NEAR) return Step.CAST;
        if (!reached) return Step.WALK;
        return toStand > LEASH && fightRecent ? Step.REPICK : Step.BACK;
    }

    // the mob defense held the wheel within FIGHT_RECENT_TICKS (defenseAt MIN_VALUE: never)
    static boolean fightRecent(long now, long defenseAt) {
        return defenseAt != Long.MIN_VALUE && now >= defenseAt && now - defenseAt <= FIGHT_RECENT_TICKS;
    }

    // the pin and its fallbacks, kept by CollectObsidianTask so it outlives the mining and the fights
    static final class Pin {
        // the pinned cell (null while there is none) and the source it was picked by
        BlockPos cell;
        BlockPos lava;
        // the buddy has been at the cell since it was pinned
        boolean reached;
        int ruined;
        // why the pool was given up, null while it goes on
        String gaveUp;
        // when the search for a cell began, MIN_VALUE while a cell is pinned
        long searchSince = Long.MIN_VALUE;
        final Set<BlockPos> banned = new HashSet<>();
        final List<BlockPos> tried = new ArrayList<>();
        // the walk to the pinned cell: its nearest yet and since when
        double walkBest = Double.MAX_VALUE;
        long walkBestAt;
        // since when the cast's lava fetch has looked for the pool's lava with an empty bucket in the bag, MIN_VALUE while not
        long fetchSince = Long.MIN_VALUE;

        boolean on() {
            return gaveUp == null;
        }

        void pin(BlockPos cell, BlockPos lava, long now) {
            this.cell = cell;
            this.lava = lava;
            reached = false;
            searchSince = Long.MIN_VALUE;
            walkBest = Double.MAX_VALUE;
            walkBestAt = now;
            fetchSince = Long.MIN_VALUE;
        }

        // the cast's lava fetch at the pinned cell this tick (hunting: handed out, an empty bucket in the bag, no lava bucket):
        // true once it has hunted POOL_FETCH_TICKS with no lava, which ruins the cell. a fetch for the empty bucket itself (iron,
        // a smelt) does not count
        boolean fetchStalled(boolean hunting, long now) {
            if (!hunting) {
                fetchSince = Long.MIN_VALUE;
                return false;
            }
            if (fetchSince == Long.MIN_VALUE || now < fetchSince) fetchSince = now;
            return now - fetchSince > POOL_FETCH_TICKS;
        }

        // the pinned cell ruined (its cast banned it, the walk to it got nowhere): banned and let go; MAX_RUINED of them give
        // the pool up
        void ruin(long now) {
            if (cell != null) banned.add(cell);
            cell = null;
            ruined++;
            if (ruined >= MAX_RUINED) {
                gaveUp = ruined + " cells ruined";
            } else {
                searchSince = now;
            }
        }

        // a new cell wanted where the buddy is now, not for a ruin
        void unpin(long now) {
            cell = null;
            tried.clear();
            searchSince = now;
        }

        // one more tick of looking for a cell; false once the look has taken SEARCH_TICKS, which gives the pool up
        boolean searching(long now) {
            if (searchSince == Long.MIN_VALUE || now < searchSince) searchSince = now;
            if (now - searchSince > SEARCH_TICKS) gaveUp = "no cell in " + SEARCH_TICKS / 20 + " s";
            return on();
        }

        // a source tried for a cell, and whether that gives the pool up
        boolean tried(BlockPos source) {
            tried.add(source);
            if (tried.size() >= MAX_TRIED) gaveUp = "no cell by " + tried.size() + " lava sources";
            return on();
        }

        boolean triedNear(BlockPos source) {
            for (BlockPos t : tried) {
                if (Math.abs(t.getX() - source.getX()) <= TRIED_NEAR && Math.abs(t.getZ() - source.getZ()) <= TRIED_NEAR
                        && Math.abs(t.getY() - source.getY()) <= TRIED_NEAR) return true;
            }
            return false;
        }

        // a walk to the pinned cell begins (the first, or one back to it)
        void walkStart(long now) {
            walkBest = Double.MAX_VALUE;
            walkBestAt = now;
        }

        // the walk to the pinned cell: true when it came no block nearer in WALK_STALL_TICKS
        boolean walkStalled(double toStand, long now) {
            if (toStand < walkBest - 1) {
                walkBest = toStand;
                walkBestAt = now;
            }
            return now - walkBestAt > WALK_STALL_TICKS;
        }

        // the task did not run for this long (a fight, an eat): the clocks don't count it
        void paused(long ticks) {
            if (searchSince != Long.MIN_VALUE) searchSince += ticks;
            if (fetchSince != Long.MIN_VALUE) fetchSince += ticks;
            walkBestAt += ticks;
        }
    }
}
