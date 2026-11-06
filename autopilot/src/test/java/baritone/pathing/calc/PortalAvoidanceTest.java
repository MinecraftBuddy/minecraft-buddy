package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// a nether portal node costs a wait's worth unless it is the goal. same fake world as GodBridgeCostTest, and the same skip
// when there's no shadow BaritoneAPI around
public class PortalAvoidanceTest {

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

    // stone floor at 63 and a two tall portal standing at (3, 64..65, 0)
    private static MapWorld world() {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(3, 64, 0, Blocks.NETHER_PORTAL.defaultBlockState());
        w.set(3, 65, 0, Blocks.NETHER_PORTAL.defaultBlockState());
        return w;
    }

    @Test
    public void aPlainWalkIntoAPortalIsPossibleAndThatIsTheProblem() {
        // no collision box, so the movements treat it as air. this is what the penalty is for, if this ever stops being
        // true the penalty can go
        Ctx ctx = new Ctx(world());
        assertTrue(MovementHelper.canWalkThrough(ctx.bsi, 3, 64, 0));
        assertTrue(MovementTraverse.cost(ctx, 2, 64, 0, 3, 0) < ActionCosts.COST_INF);
    }

    @Test
    public void aPortalOnTheWayCostsTheWait() {
        Ctx ctx = new Ctx(world());
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalBlock(6, 64, 0), ctx, 3, 64, 0), 0);
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalXZ(9, 9), ctx, 3, 64, 0), 0);
    }

    @Test
    public void theHeadCellCountsToo() {
        // standing a block under it, with only the portal's second cell over our head
        Ctx ctx = new Ctx(world());
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalBlock(6, 64, 0), ctx, 3, 63, 0), 0);
    }

    @Test
    public void aGoalAimedAtThePortalIsFree() {
        Ctx ctx = new Ctx(world());
        assertEquals(0, PortalAvoidance.penalty(new GoalBlock(3, 64, 0), ctx, 3, 64, 0), 0);
        // GetToBlockProcess with enterPortal
        assertEquals(0, PortalAvoidance.penalty(new GoalTwoBlocks(new BlockPos(3, 64, 0)), ctx, 3, 64, 0), 0);
        assertEquals(0, PortalAvoidance.penalty(new GoalComposite(new GoalBlock(0, 64, 0), new GoalBlock(3, 64, 0)), ctx, 3, 64, 0), 0);
    }

    @Test
    public void aGoalThatJustHappensToContainThePortalIsNotFree() {
        // these accept any cell in the column / radius, ending a path standing in the portal is the bad case
        Ctx ctx = new Ctx(world());
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalXZ(3, 0), ctx, 3, 64, 0), 0);
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalNear(new BlockPos(3, 64, 1), 3), ctx, 3, 64, 0), 0);
        assertEquals(PortalAvoidance.NODE_COST, PortalAvoidance.penalty(new GoalComposite(new GoalBlock(0, 64, 0), new GoalXZ(3, 0)), ctx, 3, 64, 0), 0);
    }

    @Test
    public void everythingElseIsFree() {
        Ctx ctx = new Ctx(world());
        assertEquals(0, PortalAvoidance.penalty(new GoalBlock(6, 64, 0), ctx, 2, 64, 0), 0);
        assertEquals(0, PortalAvoidance.penalty(new GoalBlock(6, 64, 0), ctx, 4, 64, 0), 0);
    }
}
