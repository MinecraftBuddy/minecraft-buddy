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

package baritone.pathing.movement.movements;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.assertTrue;

// a flat diagonal that edges round a blocked corner over an open corner with no floor (Tuning diag_edge_floor): to clear the
// blocked column the box stands for a tick wholly in the open one, and drops through it. two falls from a nether rim into the
// lava, 22 Nov 2026. skips itself without the bench harness's shadow BaritoneAPI, as DiagonalAscendTest does
public class DiagonalEdgeFloorTest {

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

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

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi, boolean edgeFloor) {
            // a tool that can't mine anything quickly enough to matter: the blocked corner stays blocked
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
            this.diagEdgeFloor = edgeFloor;
        }
    }

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Settings settings;
    private boolean potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
    }

    // a stone floor round the origin with a hole down to the world's bottom in the given columns
    private static MapWorld floorWithHoles(int[]... holes) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, FLOOR, z, stone());
            }
        }
        for (int[] h : holes) {
            w.set(h[0], FLOOR, h[1], Blocks.AIR.defaultBlockState());
        }
        return w;
    }

    // a wall two high in a column, feet and head
    private static void wall(MapWorld w, int x, int z) {
        w.set(x, FEET, z, Blocks.OBSIDIAN.defaultBlockState());
        w.set(x, FEET + 1, z, Blocks.OBSIDIAN.defaultBlockState());
    }

    private static double cost(MapWorld w, int dx, int dz, boolean edgeFloor) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w, edgeFloor), 0, FEET, 0, dx, dz, res);
        return res.cost;
    }

    @Test
    public void edgingOverAHoleIsRejected() {
        for (int[] d : DIAGONALS) {
            // the x corner walled, the z corner a hole: the box has to pass wholly over the hole
            MapWorld a = floorWithHoles(new int[]{0, d[1]});
            wall(a, d[0], 0);
            assertTrue("x corner walled, z corner a hole on " + d[0] + "," + d[1], cost(a, d[0], d[1], true) >= COST_INF);
            // and the other way round
            MapWorld b = floorWithHoles(new int[]{d[0], 0});
            wall(b, 0, d[1]);
            assertTrue("z corner walled, x corner a hole on " + d[0] + "," + d[1], cost(b, d[0], d[1], true) >= COST_INF);
        }
    }

    @Test
    public void theOldPlannerTookIt() {
        // the switch off: the same diagonal is priced, which is what dropped the buddy from the rim
        for (int[] d : DIAGONALS) {
            MapWorld a = floorWithHoles(new int[]{0, d[1]});
            wall(a, d[0], 0);
            assertTrue("switch off, " + d[0] + "," + d[1], cost(a, d[0], d[1], false) < COST_INF);
        }
    }

    @Test
    public void edgingOverAFloorStillGoes() {
        for (int[] d : DIAGONALS) {
            MapWorld a = floorWithHoles();
            wall(a, d[0], 0);
            assertTrue("edging over a floor on " + d[0] + "," + d[1], cost(a, d[0], d[1], true) < COST_INF);
        }
    }

    @Test
    public void edgingOverAOneBlockDipStillGoes() {
        // the open corner one lower than our floor: the box drops a block at most and the planner picks up from there
        for (int[] d : DIAGONALS) {
            MapWorld a = floorWithHoles(new int[]{0, d[1]});
            a.set(0, FLOOR - 1, d[1], stone());
            wall(a, d[0], 0);
            assertTrue("a dip of one on " + d[0] + "," + d[1], cost(a, d[0], d[1], true) < COST_INF);
        }
    }

    @Test
    public void anOpenDiagonalOverAHoledCornerStillGoes() {
        // no corner blocked: the box goes straight over the shared corner point, touching the start and the end columns
        // all the way, so a hole in a corner column is no fall
        for (int[] d : DIAGONALS) {
            MapWorld a = floorWithHoles(new int[]{0, d[1]});
            assertTrue("open diagonal over a hole on " + d[0] + "," + d[1], cost(a, d[0], d[1], true) < COST_INF);
        }
    }
}
