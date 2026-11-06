package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

// putting ourselves out with a water bucket. pure, the chain hands in the world and what it can reach
public final class DunkRules {

    // a click on `face` of `hit` puts the water in `lands`
    public record Face(BlockPos hit, Direction face, BlockPos lands) {
    }

    // a look that never lands must not hold the wheel for the whole burn. about half a second is plenty for the camera
    public static final int MAX_AIM_TICKS = 10;
    // walking to a pond is for ponds we can see from here. past this the burn runs out faster than the walk does
    public static final double FAR_WATER_BLOCKS = 8;

    private DunkRules() {
    }

    // a dunk that gave up gets another go when something changed: we stand in a different cell, or we left the ground and came
    // back down. a clock would hand the wheel back and forth with the walk out every second, and neither of these can repeat
    // while we stand in one spot and fail
    public static boolean retry(BlockPos gaveUpAt, BlockPos feet, boolean leftGround, boolean airborne) {
        return !feet.equals(gaveUpAt) || leftGround && !airborne;
    }

    // the floor under us and the four walls beside us, but only where the bucket really puts the water in our own cell.
    // vanilla puts it where the crosshair says, not where we asked, so every face goes through waterGoesTo. a wall of air
    // is no face (the ray goes through it), a floor of air means we're falling and there is nothing to dunk on
    public static List<Face> candidates(BlockGetter world, BlockPos feet) {
        List<Face> out = new ArrayList<>();
        // already wet. the client keeps burning a tick or two after the pour and a second bucket would go in on top
        if (BucketPour.isWaterSource(world, feet)) {
            return out;
        }
        add(out, world, feet.below(), Direction.UP, feet);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            add(out, world, feet.relative(side), side.getOpposite(), feet);
        }
        return out;
    }

    private static void add(List<Face> out, BlockGetter world, BlockPos hit, Direction face, BlockPos feet) {
        if (world.getBlockState(hit).isAir() || !world.getFluidState(hit).isEmpty()) {
            return;
        }
        if (feet.equals(BucketPour.waterGoesTo(world, hit, face))) {
            out.add(new Face(hit, face, feet));
        }
    }

    public static boolean keepAiming(int aimTicks) {
        return aimTicks < MAX_AIM_TICKS;
    }

    public static boolean walkWorthIt(double distance) {
        return distance <= FAR_WATER_BLOCKS;
    }
}
