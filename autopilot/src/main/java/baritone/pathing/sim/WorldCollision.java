/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.sim;

import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;

// the real world for PlayerSim. block states come out of the BlockStateInterface cache the pathing already warms, the shapes
// are walked in place (forAllBoxes) instead of turned into a list of AABBs, and "is this block plain" is answered once per
// state and remembered. a game thread only thing, the scratch fields are shared
public final class WorldCollision implements CollisionProvider {

    private final BlockStateInterface bsi;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final IdentityHashMap<BlockState, Boolean> plain = new IdentityHashMap<>();

    // what forAllBoxes hands to, with the cell it is currently translating for
    private BoxList target;
    private int ox, oy, oz;
    private final Shapes.DoubleLineConsumer collect = (x1, y1, z1, x2, y2, z2) ->
            target.add(x1 + ox, y1 + oy, z1 + oz, x2 + ox, y2 + oy, z2 + oz);

    public WorldCollision(BlockStateInterface bsi) {
        this.bsi = bsi;
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        BlockState state = bsi.get0(x, y, z);
        if (state.isAir()) {
            return;
        }
        VoxelShape shape = state.getCollisionShape(bsi.access, pos.set(x, y, z));
        if (shape.isEmpty()) {
            return;
        }
        if (shape == Shapes.block()) {
            out.add(x, y, z, x + 1.0, y + 1.0, z + 1.0);
            return;
        }
        target = out;
        ox = x;
        oy = y;
        oz = z;
        shape.forAllBoxes(collect);
    }

    @Override
    public boolean plain(int x, int y, int z) {
        BlockState state = bsi.get0(x, y, z);
        if (state.isAir()) {
            return true;
        }
        return plain.computeIfAbsent(state, WorldCollision::isPlain);
    }

    // what Entity / LivingEntity read off a block that makes movement more than friction 0.6 and a flat floor
    private static boolean isPlain(BlockState state) {
        Block block = state.getBlock();
        return state.getFluidState().isEmpty()
                && block.getFriction() == 0.6F && block.getSpeedFactor() == 1.0F && block.getJumpFactor() == 1.0F
                && !state.is(BlockTags.CLIMBABLE)
                && !state.is(Blocks.COBWEB) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.BUBBLE_COLUMN) && !state.is(Blocks.HONEY_BLOCK);
    }
}
