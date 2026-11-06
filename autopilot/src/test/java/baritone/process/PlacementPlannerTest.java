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

package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the bits of the planner that don't need a world. the facing and half themselves come from vanilla's own
// getStateForPlacement, which wants a real Level, so that part only gets checked in game
public class PlacementPlannerTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    @Test
    public void preferredFaceGoesFirstAndNothingGoesMissing() {
        Direction[] faces = PlacementPlanner.facesFirst(Direction.WEST);
        assertEquals(Direction.WEST, faces[0]);
        assertEquals(6, faces.length);
        assertEquals(6, java.util.EnumSet.copyOf(List.of(faces)).size());
        assertEquals(Direction.values().length, PlacementPlanner.facesFirst(null).length);
    }

    @Test
    public void sideFaceSamplesStayClearOfTheMiddle() {
        // a stair's half on a side click is the hit's y against 0.5, so the samples can't sit on it
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (var m : PlacementPlanner.aabbSideMultipliers(side)) {
                assertTrue(Math.abs(m.y - 0.5) >= 0.25);
            }
        }
    }

    @Test
    public void aStairStackSuppliesEveryFacing() {
        // approxPlaceable has whatever way we faced when it was asked, the planner turns us around later
        assertTrue(BuilderProcess.canSupply(stairs(Direction.NORTH), stairs(Direction.SOUTH)));
        assertTrue(BuilderProcess.canSupply(stairs(Direction.NORTH), stairs(Direction.EAST).setValue(StairBlock.HALF, Half.TOP)));
        assertTrue(BuilderProcess.containsItem(List.of(Blocks.STONE.defaultBlockState(), stairs(Direction.WEST)), stairs(Direction.SOUTH)));
        assertFalse(BuilderProcess.canSupply(Blocks.STONE.defaultBlockState(), stairs(Direction.SOUTH)));
        assertFalse(BuilderProcess.canSupply(Blocks.SPRUCE_STAIRS.defaultBlockState(), stairs(Direction.SOUTH)));
        // air is no item, two of them aren't a match
        assertFalse(BuilderProcess.sameItem(Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertTrue(BuilderProcess.canSupply(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        // a torch stack is the wall torch's stack too, the planner gets the wall one out of the item
        assertTrue(BuilderProcess.canSupply(Blocks.TORCH.defaultBlockState(), Blocks.WALL_TORCH.defaultBlockState()));
        assertTrue(BuilderProcess.canSupply(Blocks.OAK_SIGN.defaultBlockState(), Blocks.OAK_WALL_SIGN.defaultBlockState()));
        assertFalse(BuilderProcess.canSupply(Blocks.TORCH.defaultBlockState(), Blocks.SOUL_WALL_TORCH.defaultBlockState()));
        // kelp plant has no item, so kelp doesn't count for it
        assertFalse(BuilderProcess.sameItem(Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState()));
    }

    @Test
    public void plannedGoalsGoLowestCellFirst() {
        BlockPos feet = new BlockPos(0, 64, 0);
        BuilderProcess.GoalPlanned low = new BuilderProcess.GoalPlanned(feet, 64);
        BuilderProcess.GoalPlanned high = new BuilderProcess.GoalPlanned(feet, 65);
        assertTrue(low.isInGoal(0, 64, 0));
        assertFalse(low.isInGoal(1, 64, 0));
        assertTrue(low.heuristic(5, 64, 5) < high.heuristic(5, 64, 5));
        assertNotEquals(low, high);
        assertEquals(low, new BuilderProcess.GoalPlanned(feet, 64));
    }

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            // y comes in shifted by -minY
            return blocks.getOrDefault(BlockPos.asLong(x, y - 64, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    @Test
    public void theTopOfALadderIsNoFloor() {
        // a ladder shaft up the side of a wall, a stone pillar next to it for comparison, open air all round
        MapWorld world = new MapWorld();
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
        for (int y = 64; y <= 67; y++) {
            world.set(-1, y, 0, Blocks.STONE.defaultBlockState());
            world.set(0, y, 0, ladder);
            world.set(3, y, 0, Blocks.STONE.defaultBlockState());
        }
        PlacementPlanner.CellWants nothing = (x, y, z, current) -> null;
        BlockPos cell = new BlockPos(10, 70, 10);
        try {
            PlacementPlanner.standable(world, nothing, cell, 3, 68, 0);
        } catch (Throwable t) {
            Assume.assumeNoException("MovementHelper wants the baritone settings", t);
        }
        assertTrue(PlacementPlanner.standable(world, nothing, cell, 3, 68, 0));
        // 3/16 of a block to stand on, we'd drop into the ladder cell and hang there
        assertFalse(PlacementPlanner.standable(world, nothing, cell, 0, 68, 0));
        // in the shaft is a hang spot, sneaking holds us there
        assertTrue(PlacementPlanner.standable(world, nothing, cell, 0, 66, 0));
        assertTrue(PlacementPlanner.climbable(ladder));
        assertFalse(PlacementPlanner.climbable(Blocks.STONE.defaultBlockState()));
        assertTrue(PlacementPlanner.hangable(ladder));
        // sneaking in scaffolding is how you go down it
        assertFalse(PlacementPlanner.hangable(Blocks.SCAFFOLDING.defaultBlockState()));
    }

    @Test
    public void thePlannersSlopIsNotTheCellsEdge() {
        // a stair's facing is the yaw's quadrant. this aim point is one the planner takes from feet at the origin: the
        // middle and all four EYE_SLOP corners agree with ANGLE_MARGIN to spare. coast to the edge of the same cell
        // and it's a different quadrant, which is why the builder walks to the middle before it gives up
        Vec3 aim = new Vec3(1.5, 65.6, 2.2);
        double s = PlacementPlanner.EYE_SLOP;
        for (double[] corner : new double[][]{{0, 0}, {-s, -s}, {-s, s}, {s, -s}, {s, s}}) {
            float yaw = yawFrom(0.5 + corner[0], 0.5 + corner[1], aim);
            assertEquals(Direction.SOUTH, Direction.fromYRot(yaw - PlacementPlanner.ANGLE_MARGIN));
            assertEquals(Direction.SOUTH, Direction.fromYRot(yaw + PlacementPlanner.ANGLE_MARGIN));
        }
        assertNotEquals(Direction.SOUTH, Direction.fromYRot(yawFrom(0.05, 0.95, aim)));
        assertTrue(BuilderProcess.centred(0.05, -0.08));
        assertFalse(BuilderProcess.centred(-0.45, 0.45));
    }

    private static float yawFrom(double x, double z, Vec3 aim) {
        return RotationUtils.calcRotationFromVec3d(new Vec3(x, 65.6, z), aim, new Rotation(0, 0)).getYaw();
    }

    private static BlockState chest(Direction facing, ChestType type) {
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, type);
    }

    @Test
    public void theFirstHalfOfAChestPairIsFineTillItsPartnerIsThere() {
        BlockPos pos = new BlockPos(0, 64, 0);
        BlockState left = chest(Direction.NORTH, ChestType.LEFT);
        BlockState single = chest(Direction.NORTH, ChestType.SINGLE);
        BlockState air = Blocks.AIR.defaultBlockState();
        // vanilla's own idea of where the other half is, and the other half points back
        BlockPos partner = BuilderProcess.chestPartner(pos, left);
        assertEquals(ChestBlock.getConnectedBlockPos(pos, left), partner);
        assertEquals(pos, BuilderProcess.chestPartner(partner, chest(Direction.NORTH, ChestType.RIGHT)));
        assertNull(BuilderProcess.chestPartner(pos, single));
        // nothing to join yet, single is as right as it gets
        assertTrue(BuilderProcess.chestHalfWaiting(single, left, air, true));
        assertTrue(BuilderProcess.chestHalfWaiting(single, left, Blocks.STONE.defaultBlockState(), false));
        // a trapped chest never joins a plain one, so it's not a partner either
        assertTrue(BuilderProcess.chestHalfWaiting(single, left, Blocks.TRAPPED_CHEST.defaultBlockState(), true));
        // the partner is down, now both have to match for real
        assertFalse(BuilderProcess.chestHalfWaiting(single, left, single, true));
        // facing still counts, and a single the file asked for is just valid()
        assertFalse(BuilderProcess.chestHalfWaiting(chest(Direction.EAST, ChestType.SINGLE), left, air, true));
        assertFalse(BuilderProcess.chestHalfWaiting(single, single, air, true));
        assertFalse(BuilderProcess.chestHalfWaiting(chest(Direction.NORTH, ChestType.RIGHT), left, air, true));
        assertFalse(BuilderProcess.chestHalfWaiting(Blocks.TRAPPED_CHEST.defaultBlockState(), left, air, true));
    }
}
