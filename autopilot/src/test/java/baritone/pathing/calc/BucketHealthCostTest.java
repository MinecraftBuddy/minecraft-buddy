package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a bucket drop is offered unless a miss would kill. same fake world as GodBridgeCostTest, and the same skip when there's
// no shadow BaritoneAPI around
public class BucketHealthCostTest {

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
        Ctx(BlockStateInterface bsi, boolean bucket, double health) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, bucket, true, 0, 1.0f, health, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean fast;
    private double fallDamageCost;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        fast = settings.fastMode.value;
        fallDamageCost = settings.fallDamageCost.value;
        settings.considerPotionEffects.value = false;
        settings.fastMode.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.fastMode.value = fast;
            settings.fallDamageCost.value = fallDamageCost;
        }
    }

    // stone floor at 63, a ledge to stand on at (0, 62 + height), and an open column at x=1 to drop down. the drop lands on y=64
    private static MapWorld cliff(int blocksFallen) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int y = 64; y < 64 + blocksFallen; y++) {
            w.set(0, y - 1, 0, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    private static MutableMoveResult drop(int blocksFallen, boolean bucket, double health) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(cliff(blocksFallen), bucket, health), 0, 64 + blocksFallen, 0, 1, 0, res);
        return res;
    }

    @Test
    public void aTwentyBlockBucketDropNeedsToSurviveAMiss() {
        // 20 blocks is 17 damage when the bucket misses
        MutableMoveResult full = drop(20, true, 20);
        assertEquals(64, full.y);
        assertTrue(full.cost < ActionCosts.COST_INF);
        assertTrue("hp 18 survives a miss with 1 left", drop(20, true, 18).cost < ActionCosts.COST_INF);
        assertEquals("hp 17 dies on a miss", ActionCosts.COST_INF, drop(20, true, 17).cost, 0);
    }

    @Test
    public void aShortBucketDropIsOfferedAtAnyHealth() {
        // 10 blocks is 7 damage, 8 hp survives a miss
        assertTrue(drop(10, true, 8).cost < ActionCosts.COST_INF);
        assertEquals(ActionCosts.COST_INF, drop(10, true, 7).cost, 0);
        assertTrue(drop(3, true, 1).cost < ActionCosts.COST_INF);
    }

    @Test
    public void thePricedOutBucketDoesNotTurnIntoAHurtingFallWithoutFastMode() {
        assertEquals(ActionCosts.COST_INF, drop(20, true, 10).cost, 0);
    }

    @Test
    public void midAirKeepsTheBucketWhateverTheHealth() {
        // the fall is already on, the bucket is the only save left. the gate is a planning thing
        CalculationContext low = new Ctx(cliff(20), true, 4);
        MutableMoveResult res = new MutableMoveResult();
        boolean bucket = MovementDescend.dynamicFallCost(low, 0, 84, 0, 1, 0, 0, low.get(1, 82, 0), res, false);
        assertTrue(bucket);
        assertEquals(64, res.y);
        // and the same call as the planner makes it is refused
        res = new MutableMoveResult();
        assertFalse(MovementDescend.dynamicFallCost(low, 0, 84, 0, 1, 0, 0, low.get(1, 82, 0), res, true, true));
    }

    @Test
    public void aClutchDoesNotGetAroundARefusedBucketDrop() {
        double clutch = 40;
        // 20 blocks at 17 hp: a miss kills, so no clutch either
        assertEquals(ActionCosts.COST_INF, MovementDescend.clutchAtMissHealth(clutch, true, 17, 20), 0);
        assertEquals(clutch, MovementDescend.clutchAtMissHealth(clutch, true, 18, 20), 0);
        // mid air nothing is taken away
        assertEquals(clutch, MovementDescend.clutchAtMissHealth(clutch, false, 4, 20), 0);
    }

    @Test
    public void thePureGateAgreesWithTheFallDamageRule() {
        assertTrue(ExperimentalMovement.canAffordBucketMiss(20, 20));
        assertTrue(ExperimentalMovement.canAffordBucketMiss(18, 20));
        assertFalse(ExperimentalMovement.canAffordBucketMiss(17, 20));
        assertTrue(ExperimentalMovement.canAffordBucketMiss(0.5, 3)); // nothing to miss under the safe distance
        assertTrue(ExperimentalMovement.canAffordBucketMiss(5.5, 7)); // absorption makes for fractional hp
    }

    // ---- maxFallHeightNoWater is only free while the fall doesn't hurt ----

    private static MutableMoveResult plainWindow(int blocksFallen, boolean ladder, boolean wall, double health, boolean plainFree) {
        // the cliff face at x=0 is the wall the ladder hangs on, no wall is a ledge on its own with air under it
        MapWorld w = cliff(blocksFallen);
        if (!wall) {
            for (int y = 64; y < 63 + blocksFallen; y++) {
                w.set(0, y, 0, Blocks.AIR.defaultBlockState());
            }
        }
        Ctx ctx = new Ctx(w, false, health);
        ctx.maxFallHeightNoWater = 9;
        ctx.hasClutchItem = ladder;
        ctx.plainFallsAreFree = plainFree;
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, 64 + blocksFallen, 0, 1, 0, res);
        return res;
    }

    @Test
    public void aDropThatHurtsTakesTheLadderEvenWhenTheLimitAllowsIt() {
        MutableMoveResult res = plainWindow(6, true, true, 20, false);
        assertEquals(64, res.y);
        assertTrue(res.clutch);
        assertEquals(0, res.damage, 0);
        assertTrue(res.cost < ActionCosts.COST_INF);
    }

    @Test
    public void theLadderWinsNoMatterWhatItCosts() {
        // hearts for free, so the plain fall is as cheap as it can get and the clutch (a ladder to place and fetch) is dearer
        settings.fallDamageCost.value = 0D;
        MutableMoveResult hurt = plainWindow(6, false, false, 20, false);
        MutableMoveResult clutch = plainWindow(6, true, true, 20, false);
        assertEquals(3, hurt.damage, 0);
        assertTrue(clutch.cost > hurt.cost);
        assertTrue(clutch.clutch);
    }

    @Test
    public void aDropThatHurtsWithNoWallIsPricedAsDamage() {
        MutableMoveResult free = plainWindow(6, false, false, 20, true);
        MutableMoveResult res = plainWindow(6, true, false, 20, false);
        assertFalse(res.clutch);
        assertEquals(3, res.damage, 0);
        assertEquals(64, res.y);
        assertEquals(free.cost + 3 * settings.fallDamageCost.value, res.cost, 1e-6);
    }

    @Test
    public void aDropThatHurtsWithNoLadderIsPricedAsDamageToo() {
        MutableMoveResult res = plainWindow(6, false, true, 20, false);
        assertFalse(res.clutch);
        assertEquals(3, res.damage, 0);
        // and the floor it was priced against rides along, the runtime asks the same question with it
        assertEquals(ExperimentalMovement.BUCKET_MISS_FLOOR, res.hurtFloor, 0);
    }

    @Test
    public void aDropThatDoesNotHurtStaysFree() {
        // 3 blocks is the safe fall, ladder and wall in reach or not
        for (boolean ladder : new boolean[]{false, true}) {
            MutableMoveResult res = plainWindow(3, ladder, true, 20, false);
            assertEquals(64, res.y);
            assertFalse(res.clutch);
            assertEquals(0, res.damage, 0);
            assertTrue(res.cost < ActionCosts.COST_INF);
        }
        assertEquals(plainWindow(3, false, false, 20, true).cost, plainWindow(3, true, true, 20, false).cost, 1e-9);
    }

    @Test
    public void aHurtingDropThatWouldKillIsRefusedAndTheLadderIsStillOffered() {
        // 9 blocks is 6 damage. without fastMode the floor is 1 hp: 6 hp dies, 7 hp lives
        assertEquals(ActionCosts.COST_INF, plainWindow(9, false, false, 6, false).cost, 0);
        assertEquals(6, plainWindow(9, false, false, 7, false).damage, 0);
        // and a clutch is off the menu below fastModeMinHealth like anywhere else, so at 7 hp it is the priced fall
        MutableMoveResult low = plainWindow(9, true, true, 7, false);
        assertFalse(low.clutch);
        assertEquals(6, low.damage, 0);
    }

    @Test
    public void fastModeStillOnlyAsksNotToDieInsideTheLimit() {
        settings.fastMode.value = true;
        // 3 damage at 14 hp leaves 11, under fastModeMinHealth, but the user raised the limit and it used to be free
        assertEquals(3, plainWindow(6, false, false, 14, false).damage, 0);
        // the clutch is a different gate (fastModeMinHealth), and it's off at 11 hp, so this one is the priced fall
        MutableMoveResult low = plainWindow(6, true, true, 11, false);
        assertFalse(low.clutch);
        assertEquals(3, low.damage, 0);
    }

    @Test
    public void fastModesLongerHurtingDropsKeepItsOwnFloor() {
        settings.fastMode.value = true;
        // limit is the default 3, so a 6 block drop is one fastMode adds: 3 damage at 14 hp leaves 11, under the 12 floor
        Ctx low = new Ctx(cliff(6), false, 14);
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(low, 0, 70, 0, 1, 0, res);
        assertEquals(ActionCosts.COST_INF, res.cost, 0);
        Ctx ok = new Ctx(cliff(6), false, 15);
        res = new MutableMoveResult();
        MovementDescend.cost(ok, 0, 70, 0, 1, 0, res);
        assertEquals(3, res.damage, 0);
        assertEquals(12, res.hurtFloor, 0);
    }

    @Test
    public void aContextThatWantsTheFallItselfKeepsItFree() {
        MutableMoveResult res = plainWindow(9, true, true, 20, true);
        assertFalse(res.clutch);
        assertEquals(0, res.damage, 0);
        assertEquals(64, res.y);
    }

    @Test
    public void netherRefusesFastModesHurtingDropsButNotTheOverworld() {
        settings.fastMode.value = true;
        // 6 blocks is past the default limit of 3, so only fastMode's extra falls could take it
        Ctx nether = new Ctx(cliff(6), false, 20);
        nether.hurtingFallsOk = false;
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(nether, 0, 70, 0, 1, 0, res);
        assertEquals(ActionCosts.COST_INF, res.cost, 0);
        Ctx overworld = new Ctx(cliff(6), false, 20);
        assertTrue(overworld.hurtingFallsOk);
        res = new MutableMoveResult();
        MovementDescend.cost(overworld, 0, 70, 0, 1, 0, res);
        assertEquals(3, res.damage, 0);
    }

    @Test
    public void netherKeepsTheFallsTheUserRaisedTheLimitFor() {
        settings.fastMode.value = true;
        // EnterNetherPortalTask raises the limit to 5 to hop off a portal frame, that has to survive
        Ctx nether = new Ctx(cliff(5), false, 20);
        nether.hurtingFallsOk = false;
        nether.maxFallHeightNoWater = 5;
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(nether, 0, 69, 0, 1, 0, res);
        assertEquals(64, res.y);
        assertTrue(res.cost < ActionCosts.COST_INF);
    }

    @Test
    public void netherDescentFloorIgnoresFastModesHurtingFalls() {
        settings.fastMode.value = true;
        settings.fallDamageCost.value = 0D;
        // hearts for free, or the hurting falls cost more per block than the free ones and the gate changes nothing
        Ctx overworld = new Ctx(cliff(1), false, 20);
        Ctx nether = new Ctx(cliff(1), false, 20);
        nether.hurtingFallsOk = false;
        assertTrue(nether.descentTicksPerBlock() > overworld.descentTicksPerBlock());
    }
}
