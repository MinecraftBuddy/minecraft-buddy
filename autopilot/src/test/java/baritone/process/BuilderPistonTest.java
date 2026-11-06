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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderPistonTest {

    // filled in after the boot, same as BuilderFluidTest
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState HEAD;
    private static BlockState MOVING;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        HEAD = Blocks.PISTON_HEAD.defaultBlockState();
        MOVING = Blocks.MOVING_PISTON.defaultBlockState();
    }

    private static BlockState piston(Direction facing, boolean extended) {
        return Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing).setValue(PistonBaseBlock.EXTENDED, extended);
    }

    @Test
    public void aHeadIsRightWhereverItIs() {
        // breaking it is breaking the piston, which is the loop
        assertTrue(BuilderProcess.valid(HEAD, AIR, false));
        assertTrue(BuilderProcess.valid(HEAD, STONE, false));
        assertTrue(BuilderProcess.valid(MOVING, STONE, false));
        assertTrue(BuilderProcess.valid(MOVING, piston(Direction.NORTH, false), false));
    }

    @Test
    public void aHeadTheFileSavedIsJustAirTillThePowerComes() {
        assertTrue(BuilderProcess.valid(AIR, HEAD, false));
        // something else in there still has to go
        assertFalse(BuilderProcess.valid(Blocks.DIRT.defaultBlockState(), HEAD, false));
    }

    @Test
    public void pushedOutPistonIsStillThePiston() {
        assertTrue(BuilderProcess.valid(piston(Direction.NORTH, true), piston(Direction.NORTH, false), false));
        assertFalse(BuilderProcess.valid(piston(Direction.NORTH, false), piston(Direction.SOUTH, false), false));
    }
}
