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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// going up a diagonal with something solid in one of the corner columns. the planner needs a Baritone.settings() with
// no game behind it, which only the bench harness's shadow BaritoneAPI gives (the real one starts a Minecraft client in
// its static init), so this skips itself when that isn't on the test classpath. a clean checkout doesn't have it
public class DiagonalAscendTest {

    // no static final block states, they need the registries and those come up in setUp
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
        Ctx(BlockStateInterface bsi) {
            // a pickaxe-ish tool for everything, so the flat diagonal can price a corner it would have to mine
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
    private boolean diagonalAscend, potions;

    @Before
    public void setUp() {
        // Settings builds its options out of registry things, so the game's registries have to be up first
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        diagonalAscend = settings.allowDiagonalAscend.value;
        settings.allowDiagonalAscend.value = true;
        // ToolSet asks the player about haste and fatigue, and there is no player
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.allowDiagonalAscend.value = diagonalAscend;
            settings.considerPotionEffects.value = potions;
        }
    }

    // a stone floor around the origin, and a stone block one up on the (dx, dz) diagonal from the start at 0,FEET,0
    private static MapWorld stepAt(int dx, int dz) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, FLOOR, z, stone());
            }
        }
        w.set(dx, FEET, dz, stone());
        return w;
    }

    // stone at these heights over the feet level, in one column
    private static void stack(MapWorld w, int x, int z, int... levels) {
        for (int level : levels) {
            w.set(x, FEET + level, z, stone());
        }
    }

    private static MutableMoveResult diagonal(MapWorld w, int dx, int dz) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, FEET, 0, dx, dz, res);
        return res;
    }

    private static void assertRejected(String what, MutableMoveResult res) {
        assertTrue(what + " should not be a diagonal ascend, got cost " + res.cost, res.cost >= COST_INF);
    }

    // every diagonal, with each of these stacks of stone (heights over the feet level) in one corner column and then the
    // other, and which of those still came back with a price on them. empty means all of them were turned down
    private static List<String> leaks(int[][] shapes) {
        List<String> leaked = new ArrayList<>();
        for (int[] d : DIAGONALS) {
            for (int[] shape : shapes) {
                MapWorld a = stepAt(d[0], d[1]);
                stack(a, 0, d[1], shape);
                if (diagonal(a, d[0], d[1]).cost < COST_INF) {
                    leaked.add("z corner " + Arrays.toString(shape) + " on " + d[0] + "," + d[1]);
                }
                MapWorld b = stepAt(d[0], d[1]);
                stack(b, d[0], 0, shape);
                if (diagonal(b, d[0], d[1]).cost < COST_INF) {
                    leaked.add("x corner " + Arrays.toString(shape) + " on " + d[0] + "," + d[1]);
                }
            }
        }
        return leaked;
    }

    @Test
    public void openCornersStillAscend() {
        for (int[] d : DIAGONALS) {
            MutableMoveResult res = diagonal(stepAt(d[0], d[1]), d[0], d[1]);
            assertTrue("open diagonal " + d[0] + "," + d[1] + " cost " + res.cost, res.cost < COST_INF);
            assertEquals(d[0], res.x);
            assertEquals(FEET + 1, res.y);
            assertEquals(d[1], res.z);
        }
    }

    @Test
    public void solidCornerWeCantJustStepOntoIsRejected() {
        // none of these is a block you could ascend onto (it's too tall or it's holding something over it), so the old
        // "could just ascend" check never saw them, and the other corner being open was enough to let them through
        assertEquals(Collections.emptyList(), leaks(new int[][]{{0, 1}, {0, 2}, {1}, {1, 2}, {0, 1, 2}}));
    }

    @Test
    public void cornersTheOldChecksAlreadyCaughtStayRejected() {
        // a lone block at your feet is a step so we'd just ascend it, and a ceiling over a clear corner is a head bonk
        assertEquals(Collections.emptyList(), leaks(new int[][]{{0}, {2}}));
    }

    @Test
    public void bothCornersSolidIsRejected() {
        for (int[] d : DIAGONALS) {
            MapWorld w = stepAt(d[0], d[1]);
            stack(w, 0, d[1], 0, 1);
            stack(w, d[0], 0, 0, 1);
            assertRejected("walled in on both corners of " + d[0] + "," + d[1], diagonal(w, d[0], d[1]));
        }
    }

    @Test
    public void traverseThenAscendTakesOver() {
        // what the planner is left with when the diagonal is off the table: along the open side, then up the step
        for (int[] d : DIAGONALS) {
            MapWorld w = stepAt(d[0], d[1]);
            stack(w, 0, d[1], 0, 1);
            CalculationContext ctx = new Ctx(w);
            assertRejected("the walled diagonal " + d[0] + "," + d[1], diagonal(w, d[0], d[1]));
            double along = MovementTraverse.cost(ctx, 0, FEET, 0, d[0], 0);
            double up = MovementAscend.cost(ctx, d[0], FEET, 0, d[0], d[1]);
            assertTrue("traverse " + along, along < COST_INF);
            assertTrue("ascend " + up, up < COST_INF);
        }
    }

    @Test
    public void flatDiagonalsStillEdgeAroundACorner() {
        // the flat branch prices a corner it has to squeeze past (or mine) and that isn't ours to change
        for (int[] d : DIAGONALS) {
            MapWorld open = stepAt(d[0], d[1]);
            open.set(d[0], FEET, d[1], Blocks.AIR.defaultBlockState());
            MutableMoveResult clear = diagonal(open, d[0], d[1]);
            assertTrue("flat open " + clear.cost, clear.cost < COST_INF);
            assertEquals(FEET, clear.y);

            MapWorld edging = stepAt(d[0], d[1]);
            edging.set(d[0], FEET, d[1], Blocks.AIR.defaultBlockState());
            stack(edging, 0, d[1], 0, 1);
            MutableMoveResult squeezed = diagonal(edging, d[0], d[1]);
            assertTrue("flat edging " + squeezed.cost, squeezed.cost < COST_INF);
            assertEquals(FEET, squeezed.y);
            assertTrue("edging " + squeezed.cost + " vs open " + clear.cost, squeezed.cost > clear.cost);
        }
    }
}
