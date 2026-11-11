/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

// in the nether the usual way to die is not the lava, it's the walk along the cliff above it: an arrow or a fireball knocks us
// two blocks sideways and the drop is thirty blocks into the sea. so a node with a drop within two blocks of it pays a little,
// enough to take the inland route when there is one and not enough to turn down a ridge when that's all there is
public final class CliffAvoidance {

    // about a block and a bit of walking, per node. it adds up along a ridge, which is the point, but the heuristic knows
    // nothing about it so every extra tick of this is more nodes the search has to eat before it gives up on the cheap way
    public static final double NODE_COST = ActionCosts.WALK_ONE_BLOCK_COST * 1.25;
    // fortress bridges are all there is to walk on, and the wither skeletons up there are why we'd be near the edge anyway
    public static final double FORTRESS_NODE_COST = NODE_COST / 3;
    // how far sideways a knockback is worth worrying about
    public static final int REACH = 2;
    // a floor this close is a short fall, further than this is the sea
    public static final int DEPTH = 8;
    // a node over the lava sea: under its floor (placed or there) nothing but air down to lava. a bridge out over the sea is a
    // long walk in a ghast's sight with a thirty block fall into lava at every step: it bridged out toward a fortress search's
    // goal, the search stopped finding a way on, and a ghast knocked it off (the runner, 3 Nov 2026, attempt 135; a magma cube
    // did the same off a ledge in 129). twenty steps a node: a short crossing still goes, a long one is the long way round.
    // fortress bridges stand over the sea too and are what we cross it on, they keep the plain cliff cost
    public static final double SEA_NODE_COST = ActionCosts.WALK_ONE_BLOCK_COST * 20;
    static final int SEA_DEPTH = 64;

    private CliffAvoidance() {}

    // per search, not per context: contexts live across segments, and a column that was in an unloaded chunk last time (or
    // that we have bridged or mined since) has a different answer now. the scan is the cost, and every node asks about the
    // same columns as its neighbours do
    public static final class Memo {
        private final Long2ByteOpenHashMap columns = new Long2ByteOpenHashMap(4096);
        private final Long2ByteOpenHashMap nodes = new Long2ByteOpenHashMap(4096);
        private final Long2ByteOpenHashMap sea = new Long2ByteOpenHashMap(4096);
        private final Long2ByteOpenHashMap lavaNodes = new Long2ByteOpenHashMap(4096);

        public Memo() {
            columns.defaultReturnValue((byte) -1);
            nodes.defaultReturnValue((byte) -1);
            sea.defaultReturnValue((byte) -1);
            lavaNodes.defaultReturnValue((byte) -1);
        }
    }

    // the extra cost of ending a movement with our feet at (x, y, z). the nether's cliffs, see CalculationContext.cliffEdges,
    // and elsewhere only the lava ones, see lavaEdges
    public static double penalty(CalculationContext context, Memo memo, int x, int y, int z) {
        if (!context.cliffEdges) {
            return context.lavaEdges && lavaNear(context.bsi, memo, x, y, z) ? NODE_COST : 0;
        }
        boolean fortress = onFortressBricks(context.get(x, y - 1, z));
        if (!fortress && overSea(context.bsi, memo, x, y, z)) {
            return SEA_NODE_COST;
        }
        if (!nearADrop(context.bsi, memo, x, y, z)) {
            // (the nether's lava edges, Tuning nether_lava_edges, route jar 3: CalculationContext.lavaEdges)
            return context.lavaEdges && lavaNear(context.bsi, memo, x, y, z) ? NODE_COST : 0;
        }
        return fortress ? FORTRESS_NODE_COST : NODE_COST;
    }

    private static boolean overSea(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.sea.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean sea = overSea(of(bsi), x, y, z);
        memo.sea.put(key, (byte) (sea ? 1 : 0));
        return sea;
    }

    // feet at (x, y, z), the floor at y - 1 whatever it is: open cells from y - 2 down until lava, within SEA_DEPTH
    public static boolean overSea(Cells cells, int x, int y, int z) {
        for (int d = 2; d <= SEA_DEPTH; d++) {
            Cell c = cells.at(x, y - d, z);
            if (c == Cell.LAVA) {
                return true;
            }
            if (c != Cell.OPEN) {
                return false;
            }
        }
        return false;
    }

    // the buddy's fork (route jar 5): feet at (x, y, z), the floor at y - 1 whatever it is: lava under it within SEA_DEPTH, or
    // depth or more open cells under the floor, so that a body knocked off beside it falls more than depth blocks. a ghast's
    // ball knocked the buddy off its own bridge over a drop of 36 blocks to the ground, 20 damage, and overSea saw no sea
    public static boolean deepUnder(Cells cells, int x, int y, int z, int depth) {
        for (int d = 2; d <= SEA_DEPTH; d++) {
            Cell c = cells.at(x, y - d, z);
            if (c == Cell.LAVA) {
                return true;
            }
            if (c != Cell.OPEN) {
                return d - 2 >= depth;
            }
        }
        return true;
    }

    public static boolean onFortressBricks(BlockState floor) {
        return floor.is(Blocks.NETHER_BRICKS) || floor.is(Blocks.NETHER_BRICK_SLAB) || floor.is(Blocks.NETHER_BRICK_STAIRS);
    }

    // any other column within REACH of (x, z) that we could stand in at this height and that has nothing to land on. our own
    // column is left out on purpose: a bridge step's floor is about to be placed and a node on a ridge has its floor already,
    // so either way it's the neighbours that say whether the edge is near, and a bridge out over the void sees the void
    static boolean nearADrop(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.nodes.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean found = false;
        search:
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if ((dx != 0 || dz != 0) && dropColumn(bsi, memo, x + dx, y, z + dz)) {
                    found = true;
                    break search;
                }
            }
        }
        memo.nodes.put(key, (byte) (found ? 1 : 0));
        return found;
    }

    private static boolean dropColumn(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.columns.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean drop = scanColumn(bsi, x, y, z);
        memo.columns.put(key, (byte) (drop ? 1 : 0));
        return drop;
    }

    private static boolean scanColumn(BlockStateInterface bsi, int x, int y, int z) {
        // the bsi reads air for chunks we don't have, which would look like the biggest cliff ever
        if (!bsi.isLoaded(x, z) || !MovementHelper.canWalkThrough(bsi, x, y, z)) {
            return false;
        }
        for (int d = 1; d <= DEPTH; d++) {
            BlockState below = bsi.get0(x, y - d, z);
            if (MovementHelper.isLava(below)) {
                return true;
            }
            // a solid floor, or water to land in
            if (MovementHelper.isWater(below) || !MovementHelper.canWalkThrough(bsi, x, y - d, z, below)) {
                return false;
            }
        }
        return true;
    }

    // what a cell is, for the callers that want scanColumn's answer without a world (the lava knock guard and its tests)
    public enum Cell { LAVA, WATER, OPEN, SOLID }

    public interface Cells {
        Cell at(int x, int y, int z);

        // whether the cell stops a shot's line (RiskField's sight). a solid cell, for cells that know no more than that; the
        // game's own cells (of, SafeStand.cells) ask the block: SOLID is "no cell to walk through", and that is fire too
        default boolean blocksSight(int x, int y, int z) {
            return at(x, y, z) == Cell.SOLID;
        }
    }

    // the bsi as Cells. an unloaded chunk is solid, same as scanColumn: air there would read as the biggest cliff ever
    public static Cells of(BlockStateInterface bsi) {
        return new Cells() {
            @Override
            public Cell at(int x, int y, int z) {
                if (!bsi.isLoaded(x, z)) {
                    return Cell.SOLID;
                }
                BlockState state = bsi.get0(x, y, z);
                if (MovementHelper.isLava(state)) {
                    return Cell.LAVA;
                }
                if (MovementHelper.isWater(state)) {
                    return Cell.WATER;
                }
                return MovementHelper.canWalkThrough(bsi, x, y, z, state) ? Cell.OPEN : Cell.SOLID;
            }

            @Override
            public boolean blocksSight(int x, int y, int z) {
                return !bsi.isLoaded(x, z) || cover(bsi.get0(x, y, z));
            }
        };
    }

    // a block a shot does not pass, and that hides what stands behind it: its collision box spans the cell from side to side
    // and at least half its height (a full block, soul sand, stairs, a slab). fire, a web and the plants have no box; a
    // fence, a wall, a pane or a door has a thin one a line slips past, and counts as open: a cell that is wrongly in sight
    // costs a longer way to cover, one that is wrongly out of sight is a place to stand that is shot at (the flee search's
    // smoke of 11 Nov 2026: fire on the fortress walls hid the blazes from 81 of 194 lines the game had clear, and the buddy
    // stood where it was as "calm ground"). by the state alone, so the search thread and the game thread answer the same
    public static boolean cover(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        Boolean known = COVER.get(state);
        if (known == null) {
            known = spansCell(state);
            COVER.put(state, known);
        }
        return known;
    }

    private static final java.util.Map<BlockState, Boolean> COVER = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean spansCell(BlockState state) {
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // a shape that needs its world to be known: no cover, the side that errs toward a longer way
            return false;
        }
        if (shape.isEmpty()) {
            return false;
        }
        return covers(shape.min(Direction.Axis.X), shape.max(Direction.Axis.X), shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y),
                shape.min(Direction.Axis.Z), shape.max(Direction.Axis.Z));
    }

    // the box of a shape, in the cell's own 0 to 1: side to side both ways, and half the cell high or more
    static boolean covers(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        return minX <= 0.01 && maxX >= 0.99 && minZ <= 0.01 && maxZ >= 0.99 && maxY - minY >= 0.5;
    }

    // lava one cell to either side of (x, y, z) across the heading, at feet or the cell under. plain drops don't count, this is
    // for "a stumble here ends in lava" and not for "there is a cliff" (that's columnUnsafe)
    public static boolean lavaBeside(Cells cells, int x, int y, int z, int dirX, int dirZ) {
        // perpendicular to a unit heading is the same two numbers swapped
        int sx = dirZ;
        int sz = dirX;
        for (int dy = 0; dy >= -1; dy--) {
            if (cells.at(x + sx, y + dy, z + sz) == Cell.LAVA || cells.at(x - sx, y + dy, z - sz) == Cell.LAVA) {
                return true;
            }
        }
        return false;
    }

    // a traverse is on a lava shore if either end has lava beside it. the sprint decision asks this
    public static boolean traverseByLava(Cells cells, int x, int y, int z, int destX, int destZ) {
        int dirX = Integer.signum(destX - x);
        int dirZ = Integer.signum(destZ - z);
        return lavaBeside(cells, x, y, z, dirX, dirZ) || lavaBeside(cells, destX, y, destZ, dirX, dirZ);
    }

    // a diagonal has no single side, so any lava touching either end (3x3, feet or under) counts
    public static boolean diagonalByLava(Cells cells, int x, int y, int z, int destX, int destY, int destZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy >= -1; dy--) {
                    if (cells.at(x + dx, y + dy, z + dz) == Cell.LAVA || cells.at(destX + dx, destY + dy, destZ + dz) == Cell.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // scanColumn's meaning over Cells: something we could stand in at y with lava below, or nothing to land on within DEPTH
    public static boolean columnUnsafe(Cells cells, int x, int y, int z) {
        Cell here = cells.at(x, y, z);
        if (here != Cell.OPEN && here != Cell.WATER) {
            return false;
        }
        for (int d = 1; d <= DEPTH; d++) {
            Cell below = cells.at(x, y - d, z);
            if (below == Cell.LAVA) {
                return true;
            }
            if (below != Cell.OPEN) {
                return false;
            }
        }
        return true;
    }

    // the buddy's fork: outside the nether a plain drop is a fall, but a drop into lava is the same death as there. a climb
    // out of a pit beside a lava pool four blocks down: a zombie's blow knocked us in and the lava never let go (3 Nov
    // 2026). so the overworld prices the nodes next to lava below, and only those
    private static boolean lavaNear(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.lavaNodes.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean found = lavaNear(of(bsi), x, y, z);
        memo.lavaNodes.put(key, (byte) (found ? 1 : 0));
        return found;
    }

    // any other column within REACH of (x, z) with lava at this height, or open cells down to lava within DEPTH. our own
    // column is left out as nearADrop leaves it out
    public static boolean lavaNear(Cells cells, int x, int y, int z) {
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if ((dx != 0 || dz != 0) && lavaUnder(cells, x + dx, y, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean lavaUnder(Cells cells, int x, int y, int z) {
        Cell here = cells.at(x, y, z);
        if (here == Cell.LAVA) {
            return true;
        }
        if (here != Cell.OPEN) {
            return false;
        }
        for (int d = 1; d <= DEPTH; d++) {
            Cell below = cells.at(x, y - d, z);
            if (below == Cell.LAVA) {
                return true;
            }
            if (below != Cell.OPEN) {
                return false;
            }
        }
        return false;
    }

    // nearADrop over Cells, for the run goals that the pathfinder asks with a chunk copy instead of a bsi: an unsafe column
    // (see columnUnsafe) within a knock of (x, z), our own column left out the same way
    public static boolean dropNear(Cells cells, int x, int y, int z) {
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if ((dx != 0 || dz != 0) && columnUnsafe(cells, x + dx, y, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }
}
