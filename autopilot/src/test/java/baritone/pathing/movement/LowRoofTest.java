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

import adris.altoclef.util.helpers.ItemFixture;
import baritone.pathing.movement.LowRoof.Forward;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LowRoofTest {

    // the top water cell of everything below
    private static final int Y = 200;
    private static final double EXACT = 1.0E-5;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // real blocks, because what a pad or a slab actually measures is half of what's being tested. air unless set
    private static final class Room implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        Room set(int y, BlockState state) {
            blocks.put(new BlockPos(0, y, 0), state);
            return this;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    // the same questions Movement's live column answers, minus the width of the box: everything here is a whole column
    private static LowRoof.Column column(Room room) {
        double top = LowRoof.waterTop(room, 0, Y, 0);
        return new LowRoof.Column() {
            @Override
            public double waterTop() {
                return top;
            }

            @Override
            public boolean standingFits(double feetY) {
                return feetY + LowRoof.STANDING_HEIGHT - EXACT <= roofOver(feetY);
            }

            @Override
            public double roofOver(double feetY) {
                double roof = Double.POSITIVE_INFINITY;
                for (Map.Entry<BlockPos, BlockState> cell : room.blocks.entrySet()) {
                    BlockPos pos = cell.getKey();
                    var shape = cell.getValue().getCollisionShape(room, pos);
                    if (!shape.isEmpty() && pos.getY() + shape.max(Direction.Axis.Y) > feetY) {
                        roof = Math.min(roof, pos.getY() + shape.min(Direction.Axis.Y));
                    }
                }
                return roof;
            }
        };
    }

    private static BlockState water() {
        return Blocks.WATER.defaultBlockState();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState flowing(int level) {
        return water().setValue(LiquidBlock.LEVEL, level);
    }

    private static BlockState slab(SlabType type) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    private static boolean tooLow(Room room) {
        return LowRoof.tooLow(column(room), Y);
    }

    @Test
    public void oneAirCellOverTheWaterIsTheRoomFromTheBug() {
        assertTrue(tooLow(new Room().set(Y, water()).set(Y + 2, stone())));
    }

    @Test
    public void twoAirCellsLeaveRoomForTheWaterline() {
        // the box tops out under the roof at the waterline, so the server takes those moves and nobody has to stand
        assertFalse(tooLow(new Room().set(Y, water()).set(Y + 3, stone())));
    }

    @Test
    public void openWaterIsNotLow() {
        assertFalse(tooLow(new Room().set(Y, water())));
    }

    @Test
    public void aLilyPadKeepsUsSwimming() {
        // eye under water at the highest the box fits, the server swims too
        assertFalse(tooLow(new Room().set(Y, water()).set(Y + 1, Blocks.LILY_PAD.defaultBlockState())));
    }

    @Test
    public void aTopSlabOverTheWaterIsLow() {
        // half a block more room than the pad, and that half puts the server's eye in the air
        assertTrue(tooLow(new Room().set(Y, water()).set(Y + 1, slab(SlabType.TOP))));
    }

    @Test
    public void aBottomSlabOverTheWaterIsAsLowAsAPad() {
        assertFalse(tooLow(new Room().set(Y, water()).set(Y + 1, slab(SlabType.BOTTOM))));
    }

    @Test
    public void aFloodedColumnKeepsUsSwimming() {
        assertFalse(tooLow(new Room().set(Y - 1, water()).set(Y, water()).set(Y + 1, stone())));
    }

    @Test
    public void aWaterloggedRoofIsStillFlooded() {
        BlockState wet = slab(SlabType.TOP).setValue(BlockStateProperties.WATERLOGGED, true);
        assertFalse(tooLow(new Room().set(Y, water()).set(Y + 1, wet)));
    }

    @Test
    public void flowingWaterIsLowAtItsRealHeight() {
        // a thin sheet: the waterline falls below dest's clearance, and that's the height that can't fit
        Room room = new Room().set(Y, flowing(6)).set(Y + 2, stone());
        assertEquals(Y + 2.0 / 9, LowRoof.waterTop(room, 0, Y, 0), 1.0E-6);
        assertEquals(Y + Movement.DEST_CLEARANCE, LowRoof.waterline(LowRoof.waterTop(room, 0, Y, 0), Y), 1.0E-6);
        assertTrue(tooLow(room));
    }

    @Test
    public void flowingWaterUnderAFloodedRoofIsFlooded() {
        assertFalse(tooLow(new Room().set(Y, flowing(1)).set(Y + 1, stone())));
    }

    @Test
    public void waterlineTargetMatchesWhatHoldWaterlineAimsFor() {
        assertEquals(Y + 8.0 / 9 - 0.15, LowRoof.waterline(Y + 8.0 / 9, Y), 1.0E-6);
        // climbing out, dest's clearance wins
        assertEquals(Y + 1.3, LowRoof.waterline(Y + 8.0 / 9, Y + 1), 1.0E-6);
    }

    @Test
    public void anExactFitAtTheWaterlineIsNotLow() {
        double top = Y + 8.0 / 9;
        double target = LowRoof.waterline(top, Y);
        LowRoof.Column exact = new LowRoof.Column() {
            @Override
            public double waterTop() {
                return top;
            }

            @Override
            public boolean standingFits(double feetY) {
                return feetY <= target;
            }

            @Override
            public double roofOver(double feetY) {
                return target + LowRoof.STANDING_HEIGHT;
            }
        };
        assertFalse(LowRoof.tooLow(exact, Y));
    }

    @Test
    public void noWaterMeansNoRoofProblem() {
        assertTrue(Double.isNaN(LowRoof.waterTop(new Room().set(Y + 2, stone()), 0, Y, 0)));
        assertFalse(tooLow(new Room().set(Y + 2, stone())));
    }

    @Test
    public void feetAboveTheWaterLookOneBlockDown() {
        Room room = new Room().set(Y - 1, water());
        assertEquals(Y - 1 + 8.0 / 9, LowRoof.waterTop(room, 0, Y, 0), 1.0E-6);
    }

    @Test
    public void twoBlocksDownIsNotOurWater() {
        assertTrue(Double.isNaN(LowRoof.waterTop(new Room().set(Y - 2, water()), 0, Y, 0)));
    }

    @Test
    public void plantsAndWaterloggedBlocksCountAsWater() {
        assertEquals(Y + 8.0 / 9, LowRoof.waterTop(new Room().set(Y, Blocks.KELP.defaultBlockState()), 0, Y, 0), 1.0E-6);
        assertEquals(Y + 8.0 / 9, LowRoof.waterTop(new Room().set(Y, Blocks.SEAGRASS.defaultBlockState()), 0, Y, 0), 1.0E-6);
        assertEquals(Y + 8.0 / 9, LowRoof.waterTop(new Room().set(Y, slab(SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true)), 0, Y, 0), 1.0E-6);
    }

    @Test
    public void theTopIsTheTopEvenFromDeepDown() {
        Room room = new Room().set(Y - 2, water()).set(Y - 1, Blocks.KELP_PLANT.defaultBlockState()).set(Y, water());
        assertEquals(Y + 8.0 / 9, LowRoof.waterTop(room, 0, Y - 2, 0), 1.0E-6);
    }

    @Test
    public void anOceanOverOurHeadIsNotOurs() {
        Room room = new Room();
        for (int y = Y; y <= Y + Surfacing.REACH; y++) {
            room.set(y, water());
        }
        assertTrue(Double.isNaN(LowRoof.waterTop(room, 0, Y, 0)));
    }

    @Test
    public void jumpsUnderTheRoofOnlyOnceStanding() {
        assertTrue(LowRoof.jump(true, true, Y + 0.2, Y));
        // still in the swim pose: let it settle, holding jump is what would keep it from fitting
        assertFalse(LowRoof.jump(true, false, Y + 0.2, Y));
    }

    @Test
    public void jumpNeverFromOpenWaterIntoALowRoom() {
        // the lip hop is exactly this
        assertFalse(LowRoof.jump(false, true, Y + 0.2, Y));
        assertFalse(LowRoof.jump(false, false, Y + 0.2, Y));
    }

    @Test
    public void aMoveGoingDownIsNotPinnedToTheRoof() {
        assertFalse(LowRoof.jump(true, true, Y + 0.2, Y - 1));
        // a climb still gets the jump
        assertTrue(LowRoof.jump(true, true, Y + 0.2, Y + 1));
    }

    @Test
    public void wrongYUnderTheRoofStillPressesForward() {
        // the cap under a top slab over deep water, feet a block under dest, and the jump can't lift them any further
        assertTrue(LowRoof.leanIn(Forward.GO, true, false, Y - 1, Y, Y));
        // right level: the movement has its own keys
        assertFalse(LowRoof.leanIn(Forward.GO, true, false, Y, Y, Y));
    }

    @Test
    public void onlyLevelMovesLeanIn() {
        // an ascend sits under dest the whole way, that's not the wrong y branch and it has its own W
        assertFalse(LowRoof.leanIn(Forward.GO, true, false, Y - 1, Y - 1, Y));
        assertFalse(LowRoof.leanIn(Forward.GO, true, false, Y - 1, Y + 1, Y));
    }

    @Test
    public void aForcedAimKeepsTheCrosshair() {
        // a gate to open or a block to place, moveTowards would swing the aim off it every tick
        assertFalse(LowRoof.leanIn(Forward.GO, true, true, Y - 1, Y, Y));
    }

    @Test
    public void leaningInNeverBeatsTheLipOrThePrep() {
        assertFalse(LowRoof.leanIn(Forward.HOLD, true, false, Y - 1, Y, Y));
        assertFalse(LowRoof.leanIn(Forward.GIVE_UP, true, false, Y - 1, Y, Y));
        assertFalse(LowRoof.leanIn(Forward.GO, false, false, Y - 1, Y, Y));
    }

    @Test
    public void forwardIsFineFromUnderTheRoof() {
        LowRoof.Lip lip = new LowRoof.Lip();
        for (int i = 0; i < 200; i++) {
            assertEquals(Forward.GO, lip.tick(true, true, false, true));
            assertEquals(Forward.GO, lip.tick(true, false, false, true));
        }
    }

    @Test
    public void forwardIsFineWithNoLowRoofAhead() {
        assertEquals(Forward.GO, new LowRoof.Lip().tick(false, false, false, true));
    }

    @Test
    public void theLipWaitsUntilTheBoxFitsAtDest() {
        LowRoof.Lip lip = new LowRoof.Lip();
        assertEquals(Forward.HOLD, lip.tick(false, true, false, true));
        assertEquals(Forward.GO, lip.tick(false, true, true, true));
    }

    @Test
    public void theLipGivesUpAfterItsBudget() {
        LowRoof.Lip lip = new LowRoof.Lip();
        for (int i = 0; i < LowRoof.LIP_TICKS; i++) {
            assertEquals(Forward.HOLD, lip.tick(false, true, false, true));
        }
        assertEquals(Forward.GIVE_UP, lip.tick(false, true, false, true));
    }

    @Test
    public void preppingHoldsTheKeysWithoutSpendingTheBudget() {
        LowRoof.Lip lip = new LowRoof.Lip();
        for (int i = 0; i < LowRoof.LIP_TICKS * 3; i++) {
            assertEquals(Forward.HOLD, lip.tick(false, true, false, false));
        }
        assertEquals(Forward.HOLD, lip.tick(false, true, false, true));
    }
}
