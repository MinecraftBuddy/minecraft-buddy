package baritone.pathing.movement.movements;

import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

// what a missed vine grab falls into. the planner only prices the landing column, so a grab across a shaft with lava at the
// bottom looked as safe as one over grass
final class ClimbMiss {

    // a fall longer than this with no floor in it is not one we plan on surviving anyway
    static final int DEPTH = 20;

    private ClimbMiss() {
    }

    // blockAt: the block at a y in one column. startY: the first cell the fall passes through. true when the first thing
    // down there that we land on is not lava or fire (water counts, it is the soft way down)
    static boolean safeFloor(IntFunction<BlockState> blockAt, int startY) {
        for (int i = 0; i < DEPTH; i++) {
            BlockState state = blockAt.apply(startY - i);
            if (state.isAir()) {
                continue;
            }
            // (the fluid type, not the tag: tags are not bound until a world is, and the planner asks the same way as MovementHelper)
            Fluid fluid = state.getFluidState().getType();
            if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA || state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.SOUL_FIRE) {
                return false;
            }
            if (fluid != Fluids.EMPTY) {
                return true;
            }
            // a vine or a lichen sheet is not a floor, we fall through it to whatever is under
            if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }
}
