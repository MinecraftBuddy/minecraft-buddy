package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

// pouring a bucket into a cell next to us without it ending up on us. vanilla puts the liquid on whatever face the
// crosshair is on, not on the face we asked for, so a pour is only as good as what the crosshair hits. pure, the tasks
// hand in what they see
public final class BucketPour {

    public enum Verdict {
        POUR,
        // something in the cell (a block the ray stops on: grass, a slab, a snow layer). its top is where the lava goes
        CELL_NOT_CLEAR,
        // only just went clear. a block we broke is air on our side straight away, and if the server turns the break
        // down it sends the block back a round trip later. in between our ray sees the frame's face and clicks, but the
        // server does its own ray for a bucket: that one stops on the block's top and the lava goes in the cell over
        // it, right beside us. so we wait out a round trip
        SETTLING,
        // sand or gravel on its way into the cell
        FALLING_IN,
        IN_THE_AIR,
        // feet below the cell's top, i.e. we're in the pit we're about to fill
        TOO_LOW,
        // hanging over the edge into the column the liquid goes in and spreads out of
        IN_THE_COLUMN
    }

    // standing on a ledge reads as feet y = ledge top, give or take float noise
    private static final double FEET_SLACK = 1e-3;
    // the cell plus the two over it. lava in the cell itself doesn't climb, but the cell over it is where a misplaced pour
    // lands, and a body in there is a body in lava
    private static final int COLUMN_HEIGHT = 3;
    // never less than half a second, and never so long that the cast stalls on a lag spike reading
    public static final int SETTLE_TICKS = 10;
    public static final int MAX_SETTLE_TICKS = 60;

    private BucketPour() {
    }

    // how long a fresh clear cell has to stay clear: two round trips (the tab list ping is smoothed and spikes happen)
    // plus a few ticks for the server to get round to it
    public static int settleTicks(int pingMs) {
        int trips = (2 * Math.max(0, pingMs) + 49) / 50;
        return Math.min(MAX_SETTLE_TICKS, Math.max(SETTLE_TICKS, trips + 4));
    }

    // the cell the liquid goes in for a crosshair on hitPos's hitFace
    public static BlockPos landsIn(BlockPos hitPos, Direction hitFace) {
        return hitPos.relative(hitFace);
    }

    // where a water bucket click on hitFace of hitPos puts the water, same order as BucketItem.use: a waterloggable block
    // takes it itself, otherwise it's the cell on the face if that one takes water, otherwise nowhere. a grass
    // block we hit does not get it, the ray stopped on its outline so the water goes on top. pure, see waterGoesTo below
    public static BlockPos waterGoesTo(BlockPos hitPos, Direction hitFace, boolean hitTakesItInside, boolean nextTakesIt) {
        if (hitTakesItInside) {
            return hitPos;
        }
        return nextTakesIt ? hitPos.relative(hitFace) : null;
    }

    // the world half of that. sneaking is ignored, nobody sneaks a clutch
    public static BlockPos waterGoesTo(BlockGetter world, BlockPos hitPos, Direction hitFace) {
        boolean inside = world.getBlockState(hitPos).getBlock() instanceof LiquidBlockContainer && takesWater(world, hitPos);
        return waterGoesTo(hitPos, hitFace, inside, takesWater(world, hitPos.relative(hitFace)));
    }

    // air, something water washes away (grass, a torch), or a waterloggable block. 26.3's canPlaceLiquid says yes to
    // water even when it's waterlogged already, and so does the bucket
    static boolean takesWater(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced(Fluids.WATER)) {
            return true;
        }
        return state.getBlock() instanceof LiquidBlockContainer container && container.canPlaceLiquid(null, world, pos, state, Fluids.WATER);
    }

    // still water, including the water inside a waterlogged block (getBlock() == WATER misses those)
    public static boolean isWaterSource(BlockGetter world, BlockPos pos) {
        return world.getFluidState(pos).getType() == Fluids.WATER;
    }

    // the crosshair is on the block and the face we meant. anything else puts the liquid somewhere we didn't pick.
    // a null face means any face of the block was fine to the caller
    public static boolean aimedRight(BlockPos hitPos, Direction hitFace, BlockPos target, Direction face) {
        if (hitPos == null || !hitPos.equals(target)) {
            return false;
        }
        return face == null || face == hitFace;
    }

    // a cell the liquid can go straight into: air, or a liquid (the bucket ray goes through those). anything with an
    // outline stops the ray, and then the liquid goes on top of it instead
    public static boolean clearForPour(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return !state.getFluidState().isEmpty() && state.getShape(world, pos).isEmpty();
    }

    public static AABB column(BlockPos cell) {
        return new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + COLUMN_HEIGHT, cell.getZ() + 1);
    }

    // the checks for pouring into `cell` from a ledge one up and beside it. clearTicks is how long the cell has looked
    // clear in a row, settleTicks how long it has to (see settleTicks). the answer is the first check that fails
    public static Verdict check(boolean cellClear, int clearTicks, int settleTicks, boolean fallingIn, boolean onGround, AABB body, BlockPos cell) {
        if (!cellClear) {
            return Verdict.CELL_NOT_CLEAR;
        }
        if (clearTicks < settleTicks) {
            return Verdict.SETTLING;
        }
        if (fallingIn) {
            return Verdict.FALLING_IN;
        }
        if (!onGround) {
            return Verdict.IN_THE_AIR;
        }
        if (body.minY < cell.getY() + 1 - FEET_SLACK) {
            return Verdict.TOO_LOW;
        }
        if (body.intersects(column(cell))) {
            return Verdict.IN_THE_COLUMN;
        }
        return Verdict.POUR;
    }
}
