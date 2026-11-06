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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceRulesTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean same(BlockState a, BlockState b) {
        return BuilderProcess.sameBlockstate(a, b, false, List.of());
    }

    @Test
    public void stairShapeIsTheNeighboursCall() {
        BlockState straight = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState corner = straight.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT);
        assertTrue(same(corner, straight));
        // which way it faces and which half it's on are still ours to get right
        assertFalse(same(straight.setValue(StairBlock.FACING, Direction.EAST), straight));
        assertFalse(same(straight.setValue(StairBlock.HALF, Half.TOP), straight));
    }

    @Test
    public void fenceAndWallConnectionsDontCount() {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        assertTrue(same(fence, fence.setValue(CrossCollisionBlock.SOUTH, true).setValue(CrossCollisionBlock.EAST, true)));
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        assertTrue(same(pane, pane.setValue(CrossCollisionBlock.NORTH, true)));
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        assertTrue(same(wall, wall.setValue(WallBlock.NORTH, WallSide.TALL).setValue(WallBlock.UP, false)));
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        assertTrue(same(gate, gate.setValue(FenceGateBlock.IN_WALL, true)));
        assertFalse(same(gate, gate.setValue(FenceGateBlock.FACING, Direction.WEST)));
        // open or shut is whoever walked through last, the path opens these itself
        assertTrue(same(gate, gate.setValue(FenceGateBlock.OPEN, true)));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertTrue(same(door, door.setValue(DoorBlock.OPEN, true)));
        assertFalse(same(door, door.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)));
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        assertTrue(same(trapdoor, trapdoor.setValue(TrapDoorBlock.OPEN, true)));
        assertFalse(same(trapdoor, trapdoor.setValue(TrapDoorBlock.HALF, Half.TOP)));
        // the builder never pours into a block, so wet or dry is the world's call too
        assertTrue(same(fence, fence.setValue(BlockStateProperties.WATERLOGGED, true)));
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        assertTrue(same(stair, stair.setValue(BlockStateProperties.WATERLOGGED, true)));
        assertTrue(same(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true)));
    }

    @Test
    public void vineFacesStayStrict() {
        // same property objects as the fence's, but somebody picked these
        assertTrue(VineBlock.NORTH == CrossCollisionBlock.NORTH);
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true);
        assertFalse(same(vine, vine.setValue(VineBlock.NORTH, false).setValue(VineBlock.SOUTH, true)));
        assertFalse(PlaceRules.derived(Blocks.VINE, VineBlock.NORTH));
        assertTrue(PlaceRules.derived(Blocks.OAK_FENCE, CrossCollisionBlock.NORTH));
    }

    @Test
    public void aimProofIsAFullCubeWithNothingToAim() {
        assertTrue(PlaceRules.aimProof(Blocks.OAK_PLANKS.defaultBlockState()));
        assertTrue(PlaceRules.aimProof(Blocks.COBBLESTONE.defaultBlockState()));
        // vertical is the default and a side click still lays it down
        assertFalse(PlaceRules.aimProof(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_FENCE.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.FURNACE.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        // not a cube is not the same as aimed: nothing on a fence cares where we looked, a ladder's facing does
        assertFalse(PlaceRules.aimed(Blocks.OAK_FENCE.defaultBlockState()));
        assertTrue(PlaceRules.aimed(Blocks.LADDER.defaultBlockState()));
    }

    @Test
    public void railShapeIsTheNeighboursCall() {
        BlockState rail = Blocks.RAIL.defaultBlockState();
        assertTrue(same(rail, rail.setValue(RailBlock.SHAPE, RailShape.SOUTH_EAST)));
        for (Block block : List.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL)) {
            assertTrue(PlaceRules.derived(block, ((BaseRailBlock) block).getShapeProperty()));
        }
        BlockState powered = Blocks.POWERED_RAIL.defaultBlockState();
        assertTrue(same(powered, powered.setValue(PoweredRailBlock.POWERED, true)));
        // still a rail and not a powered one
        assertFalse(same(rail, powered));
    }

    @Test
    public void usableIsAnythingWithItsOwnRightClick() {
        for (Block block : List.of(Blocks.CHEST, Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.REPEATER,
                Blocks.COMPARATOR, Blocks.NOTE_BLOCK, Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.DAYLIGHT_DETECTOR, Blocks.CRAFTING_TABLE,
                Blocks.ANVIL, Blocks.FLOWER_POT, Blocks.COMPOSTER, Blocks.CAKE, Blocks.OAK_SIGN)) {
            assertTrue(block.toString(), PlaceRules.usable(block.defaultBlockState()));
        }
        // the stuff builds are made of clicks straight through to a placement, no crouch for those
        for (Block block : List.of(Blocks.STONE, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS,
                Blocks.COBBLESTONE_WALL, Blocks.GRASS_BLOCK, Blocks.OAK_LEAVES)) {
            assertFalse(block.toString(), PlaceRules.usable(block.defaultBlockState()));
        }
    }

    @Test
    public void readsSneakIsTheShortList() {
        assertTrue(PlaceRules.readsSneak(Blocks.CHEST));
        assertTrue(PlaceRules.readsSneak(Blocks.CANDLE));
        assertTrue(PlaceRules.readsSneak(Blocks.SCAFFOLDING));
        assertFalse(PlaceRules.readsSneak(Blocks.OAK_STAIRS));
    }

    @Test
    public void pistonExtendedIsTheCircuitsCall() {
        for (Block block : List.of(Blocks.PISTON, Blocks.STICKY_PISTON)) {
            BlockState in = block.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.NORTH);
            assertTrue(same(in.setValue(PistonBaseBlock.EXTENDED, true), in));
            assertTrue(same(in, in.setValue(PistonBaseBlock.EXTENDED, true)));
            // which way it pushes is still ours
            assertFalse(same(in.setValue(PistonBaseBlock.FACING, Direction.SOUTH), in));
        }
    }

    @Test
    public void litAndPoweredAreTheCircuitsCall() {
        BlockState torch = Blocks.REDSTONE_TORCH.defaultBlockState();
        assertTrue(same(torch.setValue(BlockStateProperties.LIT, false), torch.setValue(BlockStateProperties.LIT, true)));
        BlockState wallTorch = Blocks.REDSTONE_WALL_TORCH.defaultBlockState();
        assertTrue(same(wallTorch.setValue(RedstoneWallTorchBlock.LIT, false), wallTorch.setValue(RedstoneWallTorchBlock.LIT, true)));
        BlockState ore = Blocks.REDSTONE_ORE.defaultBlockState();
        assertTrue(same(ore.setValue(RedStoneOreBlock.LIT, true), ore.setValue(RedStoneOreBlock.LIT, false)));
        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        assertTrue(same(lamp.setValue(RedstoneLampBlock.LIT, true), lamp.setValue(RedstoneLampBlock.LIT, false)));
        BlockState observer = Blocks.OBSERVER.defaultBlockState();
        assertTrue(same(observer.setValue(ObserverBlock.POWERED, true), observer));
        BlockState plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        assertTrue(same(plate.setValue(PressurePlateBlock.POWERED, true), plate));
        BlockState target = Blocks.TARGET.defaultBlockState();
        assertTrue(same(target.setValue(BlockStateProperties.POWER, 9), target));
    }

    @Test
    public void diodeClicksStillCount() {
        BlockState repeater = Blocks.REPEATER.defaultBlockState();
        assertTrue(same(repeater.setValue(RepeaterBlock.POWERED, true), repeater));
        assertTrue(same(repeater.setValue(RepeaterBlock.LOCKED, true), repeater));
        // delay is a click somebody chose
        assertFalse(same(repeater.setValue(RepeaterBlock.DELAY, 3), repeater));
        BlockState comparator = Blocks.COMPARATOR.defaultBlockState();
        assertTrue(same(comparator.setValue(ComparatorBlock.POWERED, true), comparator));
        assertFalse(same(comparator.setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT), comparator));
    }

    @Test
    public void noteBlockPitchStillCounts() {
        BlockState note = Blocks.NOTE_BLOCK.defaultBlockState();
        assertTrue(same(note.setValue(NoteBlock.POWERED, true), note));
        assertFalse(same(note.setValue(NoteBlock.NOTE, 7), note));
    }
}
