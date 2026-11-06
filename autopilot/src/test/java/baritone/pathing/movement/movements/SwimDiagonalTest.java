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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// a swimmer can't edge around a corner, so a water diagonal needs both corner columns open at feet and head height.
// same scaffolding as DiagonalAscendTest, and it skips itself the same way without the shadow BaritoneAPI
public class SwimDiagonalTest {

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState water() {
        return Blocks.WATER.defaultBlockState();
    }

    // the surface is at FEET, three deep, so the nodes float at FEET like a real lake
    private static final int BED = 60;
    private static final int FEET = 64;

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
        Ctx(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Settings settings;
    private boolean swimming, potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        swimming = settings.allowSwimming.value;
        settings.allowSwimming.value = true;
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.allowSwimming.value = swimming;
            settings.considerPotionEffects.value = potions;
        }
    }

    private static MapWorld lake() {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, BED, z, stone());
                for (int y = BED + 1; y <= FEET; y++) {
                    w.set(x, y, z, water());
                }
            }
        }
        return w;
    }

    private static MapWorld dry() {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, FEET - 1, z, stone());
            }
        }
        return w;
    }

    private static MutableMoveResult diagonal(MapWorld w, int dx, int dz) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, FEET, 0, dx, dz, res);
        return res;
    }

    @Test
    public void openWaterDiagonalIsFine() {
        for (int[] d : DIAGONALS) {
            MutableMoveResult res = diagonal(lake(), d[0], d[1]);
            assertTrue("open water " + d[0] + "," + d[1] + " cost " + res.cost, res.cost < COST_INF);
            assertEquals(FEET, res.y);
        }
    }

    @Test
    public void aBlockInEitherCornerAtEitherHeightTurnsItDown() {
        for (int[] d : DIAGONALS) {
            for (int level = 0; level <= 1; level++) {
                MapWorld a = lake();
                a.set(0, FEET + level, d[1], stone());
                MutableMoveResult ra = diagonal(a, d[0], d[1]);
                assertTrue("z corner +" + level + " on " + d[0] + "," + d[1] + " cost " + ra.cost, ra.cost >= COST_INF);

                MapWorld b = lake();
                b.set(d[0], FEET + level, 0, stone());
                MutableMoveResult rb = diagonal(b, d[0], d[1]);
                assertTrue("x corner +" + level + " on " + d[0] + "," + d[1] + " cost " + rb.cost, rb.cost >= COST_INF);
            }
        }
    }

    @Test
    public void withoutSwimmingTheOldEdgingStays() {
        // the bob doesn't coast like a swimmer, so with allowSwimming off the corner is priced like it always was
        settings.allowSwimming.value = false;
        for (int[] d : DIAGONALS) {
            MapWorld w = lake();
            w.set(0, FEET + 1, d[1], stone());
            MutableMoveResult res = diagonal(w, d[0], d[1]);
            assertTrue("edging in water without swimming " + d[0] + "," + d[1] + " cost " + res.cost, res.cost < COST_INF);
        }
    }

    @Test
    public void dryDiagonalsStillEdge() {
        for (int[] d : DIAGONALS) {
            MutableMoveResult open = diagonal(dry(), d[0], d[1]);
            assertTrue("dry open " + open.cost, open.cost < COST_INF);
            assertEquals(FEET, open.y);

            MapWorld edging = dry();
            edging.set(0, FEET, d[1], stone());
            edging.set(0, FEET + 1, d[1], stone());
            MutableMoveResult squeezed = diagonal(edging, d[0], d[1]);
            assertTrue("dry edging " + squeezed.cost, squeezed.cost < COST_INF);
            assertTrue("edging " + squeezed.cost + " vs open " + open.cost, squeezed.cost > open.cost);
        }
    }
}
