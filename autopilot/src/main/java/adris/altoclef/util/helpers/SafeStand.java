package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.hoglin.HoglinBase;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// whether a cell (the feet's block) is a safe place to stand when a hit can come: no knock from it may end in lava or in a
// fall that hurts. the one test for every place that picks a cell to stand on, since a knock off an edge into lava or a fall
// is how the buddy dies most. the knock is followed the way the body moves: the box (0.6 wide) is pushed from the cell's
// centre along a line, up to the knock's reach. a block at feet or head height on the way stops it; lava the box touches is
// a burn; once no part of the box is over floor the body falls straight down to whatever is there, at any depth. a fall onto
// stone short of HURTS lands, and the rest of the push goes on from there
//
// the reaches are 26.3's own knockback, measured: an ordinary hit (melee, an arrow, a blaze's fireball, a magma cube) is a
// push of 0.4 that carries the feet's centre 2.5 blocks; a hoglin's throw carries it up to 7, any way round as the hoglin
// turns; a ghast's fireball, the hit and its blast together, 4.5 along the ball's flight
public final class SafeStand {
    public static final double HIT = 2.5;
    public static final double HOGLIN = 7;
    public static final double GHAST = 4.5;
    // a fall this many blocks deep hurts
    public static final int HURTS = 4;
    // foes further than this are left out, but for the kinds that shoot
    public static final double FAR = 16;
    // a known foe pushes away from itself, give or take this much
    static final double CONE = Math.toRadians(45);
    // half the player's width
    static final double HALF_WIDTH = 0.3;
    // the push is followed in steps this long, and the lines out of a cell are this far apart at the reach's end: a hole one
    // block wide is 0.4 wide to the box's centre, and no line may pass it by
    static final double STEP = 0.1;
    static final double SPACING = 0.2;
    // a column open this far down is the void
    static final int VOID = 384;
    // the box a hair smaller: one that only touches a block's face is not in it
    private static final double INSET = 1e-4;

    private SafeStand() {
    }

    // the drills' switch (tuning safe_stand, 1 is on): off, the callers keep their old tests, so one jar runs the yardstick
    // and this beside it (steering 40). the tests force it either way
    static Boolean forced;

    public static boolean on() {
        if (forced != null) return forced;
        return Tuning.get("safe_stand", 0) >= 1;
    }

    // one way a hit can push: away from (fromX, fromZ), within CONE of the line, up to reach; or any way round
    public record Push(double fromX, double fromZ, double reach, boolean allRound) {
        public static Push away(double fromX, double fromZ, double reach) {
            return new Push(fromX, fromZ, reach, false);
        }

        public static Push round(double reach) {
            return new Push(0, 0, reach, true);
        }
    }

    // the worst case, for a cell picked before a fight (a camp, a cover, the rim's firm ground): an ordinary hit from any side
    public static boolean unsafe(Cells cells, int x, int y, int z) {
        return unsafe(cells, x, y, z, List.of(Push.round(HIT)));
    }

    // whether any of the pushes ends in lava or a fall that hurts. the cell is taken as one the body fits in; with no push at
    // all only the cell itself is judged (no floor under it is a fall)
    public static boolean unsafe(Cells cells, int x, int y, int z, Iterable<Push> pushes) {
        Probe probe = new Probe(cells);
        double cx = x + 0.5;
        double cz = z + 0.5;
        if (badAt(probe, cx, cz, y, 1, 0, 0) >= 0) return true;
        return lines(cx, cz, pushes, (ux, uz, reach) -> badAt(probe, cx, cz, y, ux, uz, reach) >= 0);
    }

    // where a throwaway block would stop the knock that goes wrong soonest: the cell beside this one, at feet height, that
    // the centre enters first along the push that ends badly the shortest way out (the drop's side). {x, y, z}, or null when
    // no push ends badly. a block there stops that push; on a bare bridge that cell has no face to go against but the one
    // under it, which goes first
    public static int[] blockSide(Cells cells, int x, int y, int z, Iterable<Push> pushes) {
        Probe probe = new Probe(cells);
        double cx = x + 0.5;
        double cz = z + 0.5;
        double[] worst = {Double.MAX_VALUE, 0, 0};
        lines(cx, cz, pushes, (ux, uz, reach) -> {
            double s = badAt(probe, cx, cz, y, ux, uz, reach);
            if (s >= 0 && s < worst[0]) {
                worst[0] = s;
                worst[1] = ux;
                worst[2] = uz;
            }
            return false;
        });
        if (worst[0] == Double.MAX_VALUE) return null;
        for (double s = 0.01; s < 1.5; s += 0.01) {
            int bx = (int) Math.floor(cx + worst[1] * s);
            int bz = (int) Math.floor(cz + worst[2] * s);
            if (bx != x || bz != z) return new int[]{bx, y, bz};
        }
        return null;
    }

    // the same for an ordinary hit from any side
    public static int[] blockSide(Cells cells, int x, int y, int z) {
        return blockSide(cells, x, y, z, List.of(Push.round(HIT)));
    }

    // how far the cell's centre is from the nearest column within radius (flat, to the column's nearest side) where a body at
    // this height would burn or fall HURTS or more; Double.MAX_VALUE for none. for choosing between safe cells, not for safety
    public static double dropGap(Cells cells, int x, int y, int z, int radius) {
        double best = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                double gap = Math.hypot(Math.max(0, Math.abs(dx) - 0.5), Math.max(0, Math.abs(dz) - 0.5));
                if (gap < best && hazard(cells, x + dx, y, z + dz)) best = gap;
            }
        }
        return best;
    }

    // a column a body with its feet at this height burns in (lava at feet or head) or falls HURTS or more from, lava on the
    // way counting; a wall is none. HURTS cells down are enough: past them it is a fall that hurts whatever is at the bottom
    static boolean hazard(Cells cells, int x, int feet, int z) {
        Cell body = cells.at(x, feet, z);
        Cell head = cells.at(x, feet + 1, z);
        if (body == Cell.SOLID || head == Cell.SOLID) return false;
        if (body == Cell.LAVA || head == Cell.LAVA) return true;
        if (body == Cell.WATER) return false;
        for (int d = 1; d <= HURTS; d++) {
            Cell c = cells.at(x, feet - d, z);
            if (c == Cell.LAVA) return true;
            if (c != Cell.OPEN) return false;
        }
        return true;
    }

    // a line a push can take out of a cell; true from take stops the look
    private interface Line {
        boolean take(double ux, double uz, double reach);
    }

    // every line of every push: a foe's within CONE of the line away from it, the others all round
    private static boolean lines(double cx, double cz, Iterable<Push> pushes, Line line) {
        for (Push p : pushes) {
            double dx = cx - p.fromX();
            double dz = cz - p.fromZ();
            // (a foe right on the cell has no "away": any way round)
            if (p.allRound() || Math.hypot(dx, dz) < 0.05) {
                int n = 8 * (int) Math.ceil(2 * Math.PI * p.reach() / SPACING / 8);
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * i / n;
                    if (line.take(Math.cos(a), Math.sin(a), p.reach())) return true;
                }
                continue;
            }
            double base = Math.atan2(dz, dx);
            // an odd count, so the straight line away is one of them
            int n = Math.max(3, (int) Math.ceil(2 * CONE * p.reach() / SPACING) + 1) | 1;
            for (int i = 0; i < n; i++) {
                double a = base - CONE + 2 * CONE * i / (n - 1);
                if (line.take(Math.cos(a), Math.sin(a), p.reach())) return true;
            }
        }
        return false;
    }

    // the worst case at a cell in the world
    public static boolean unsafe(Level level, BlockPos feet) {
        return unsafe(cells(level), feet.getX(), feet.getY(), feet.getZ());
    }

    // the foes there are, each pushing its own way and as far as its kind does (push)
    public static boolean unsafe(Level level, BlockPos feet, Iterable<? extends Entity> foes) {
        return unsafe(cells(level), feet.getX(), feet.getY(), feet.getZ(), pushes(foes, feet));
    }

    public static List<Push> pushes(Iterable<? extends Entity> foes, BlockPos feet) {
        List<Push> out = new ArrayList<>();
        double cx = feet.getX() + 0.5;
        double cz = feet.getZ() + 0.5;
        for (Entity foe : foes) {
            if (foe == null || !foe.isAlive()) continue;
            Push p = push(foe instanceof HoglinBase, foe instanceof Ghast, ranged(foe), foe.getX(), foe.getZ(),
                    Math.sqrt(foe.distanceToSqr(cx, feet.getY(), cz)));
            if (p != null) out.add(p);
        }
        return out;
    }

    // a foe's push: a hoglin (or zoglin) throws any way round, a ghast's ball carries further, the rest is an ordinary hit
    // away from the foe. null for one too far off to reach us, which only the kinds that shoot never are
    static Push push(boolean hoglin, boolean ghast, boolean ranged, double fx, double fz, double distance) {
        if (distance > FAR && !ranged) return null;
        if (hoglin) return Push.round(HOGLIN);
        return Push.away(fx, fz, ghast ? GHAST : HIT);
    }

    // the kinds that hit from further than FAR: ghasts, blazes, skeletons of every kind, a piglin or pillager with a crossbow
    static boolean ranged(Entity foe) {
        if (foe instanceof Ghast || foe instanceof Blaze || foe instanceof AbstractSkeleton) return true;
        return (foe instanceof Piglin || foe instanceof Pillager) && ((LivingEntity) foe).isHolding(s -> s.getItem() instanceof CrossbowItem);
    }

    // the world as cells: lava, water, open (nothing to bump into) or solid. an unloaded chunk reads solid, as CliffAvoidance.of
    // has it: air there would read as the deepest drop of all
    public static Cells cells(Level level) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            at.set(x, y, z);
            if (!level.isLoaded(at)) return Cell.SOLID;
            BlockState state = level.getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA)) return Cell.LAVA;
            if (state.getFluidState().is(FluidTags.WATER)) return Cell.WATER;
            return state.getCollisionShape(level, at).isEmpty() ? Cell.OPEN : Cell.SOLID;
        };
    }

    // what a column is at a feet height, for the box over it
    static final int BLOCKED = 0;
    static final int LAVA = 1;
    static final int FLOOR = 2;
    static final int DROP = 3;
    // where a drop ends
    static final int END_LAVA = 0;
    static final int END_WATER = 1;
    static final int END_STONE = 2;
    static final int END_VOID = 3;

    // one push from (cx, cz) with the feet at y, along (ux, uz), up to reach: how far along it the box touches lava or starts
    // a fall that hurts, or -1 when it does neither
    private static double badAt(Probe probe, double cx, double cz, int y, double ux, double uz, double reach) {
        int feet = y;
        int steps = (int) Math.ceil(reach / STEP - 1e-9);
        long last = Long.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double s = Math.min(i * STEP, reach);
            double px = cx + ux * s;
            double pz = cz + uz * s;
            int x0 = (int) Math.floor(px - HALF_WIDTH + INSET);
            int x1 = (int) Math.floor(px + HALF_WIDTH - INSET);
            int z0 = (int) Math.floor(pz - HALF_WIDTH + INSET);
            int z1 = (int) Math.floor(pz + HALF_WIDTH - INSET);
            // (the same columns at the same height as the last step: nothing new)
            long here = ((long) x0 & 0xFFFF) << 48 | ((long) z0 & 0xFFFF) << 32 | ((long) feet & 0xFFFF) << 16 | (x1 - x0) << 1 | (z1 - z0);
            if (here == last) continue;
            last = here;
            boolean held = false;
            boolean lava = false;
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int kind = probe.at(bx, feet, bz) & 3;
                    if (kind == BLOCKED) return -1;
                    if (kind == LAVA) lava = true;
                    else if (kind == FLOOR) held = true;
                }
            }
            if (lava) return s;
            if (held) continue;
            // nothing under the box: it falls until the first of its columns ends
            int depth = Integer.MAX_VALUE;
            int ends = 0;
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int info = probe.at(bx, feet, bz);
                    int d = info >>> 4;
                    int end = 1 << ((info >> 2) & 3);
                    if (d < depth) {
                        depth = d;
                        ends = end;
                    } else if (d == depth) {
                        ends |= end;
                    }
                }
            }
            if ((ends & 1 << END_LAVA) != 0) return s;
            // (water takes the fall, and the push with it)
            if ((ends & 1 << END_WATER) != 0) return -1;
            if ((ends & 1 << END_VOID) != 0 || depth - 1 >= HURTS) return s;
            feet = feet - depth + 1;
            last = Long.MIN_VALUE;
        }
        return -1;
    }

    // the columns one call looks at, each read once: the kind in the low two bits, for a drop where it ends in the next two and
    // how far down (from the feet) above them
    private static final class Probe {
        private final Cells cells;
        private final Long2IntOpenHashMap known = new Long2IntOpenHashMap(256);

        Probe(Cells cells) {
            this.cells = cells;
            known.defaultReturnValue(-1);
        }

        int at(int x, int feet, int z) {
            long key = BlazeCover.key(x, feet, z);
            int info = known.get(key);
            if (info < 0) {
                info = column(cells, x, feet, z);
                known.put(key, info);
            }
            return info;
        }
    }

    static int column(Cells cells, int x, int feet, int z) {
        Cell body = cells.at(x, feet, z);
        Cell head = cells.at(x, feet + 1, z);
        if (body == Cell.SOLID || head == Cell.SOLID) return BLOCKED;
        if (body == Cell.LAVA || head == Cell.LAVA) return LAVA;
        if (body == Cell.WATER) return FLOOR;
        for (int d = 1; d <= VOID; d++) {
            Cell c = cells.at(x, feet - d, z);
            if (c == Cell.OPEN) continue;
            if (d == 1 && c != Cell.LAVA) return FLOOR;
            int end = c == Cell.LAVA ? END_LAVA : c == Cell.WATER ? END_WATER : END_STONE;
            return DROP | end << 2 | d << 4;
        }
        return DROP | END_VOID << 2 | (VOID + 1) << 4;
    }
}
