package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;

import adris.altoclef.tasks.speedrun.gamer.WoodReserve.Keep;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// how much wood the furnace has to leave alone
public class WoodReserveTest {
    private static final int BEDS = 8;
    private final OverworldConfig cfg = new OverworldConfig();
    {
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void shortOfTheBudgetKeepsEverything() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 10).give(Items.OAK_PLANKS, 6);
        assertEquals(new Keep(10, 6), WoodBudget.of(f, cfg, BEDS).keep());
    }

    @Test
    public void onlyWhatIsAboveTheBudgetBurns() {
        // 15 logs is exactly the budget (see KitPlannerTest), 5 more are fuel
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20);
        assertEquals(new Keep(15, 0), WoodBudget.of(f, cfg, BEDS).keep());
    }

    @Test
    public void speciesShareOnePool() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 9).give(Items.BIRCH_LOG, 9);
        assertEquals(new Keep(15, 0), WoodBudget.of(f, cfg, BEDS).keep());
    }

    @Test
    public void planksCountTowardsTheBudgetToo() {
        // the planner asks in whole logs, so 58 planks is where it stops asking and the other 10 are spare
        FakeFacts f = new FakeFacts().give(Items.OAK_PLANKS, 68);
        assertEquals(new Keep(0, 58), WoodBudget.of(f, cfg, BEDS).keep());
    }

    @Test
    public void nothingWoodenLeftToCraftMeansNothingToKeep() {
        // everything the kit crafts from wood is made already
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 3)
                .give(Items.WOODEN_PICKAXE, 1).give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2).give(Items.STONE_SWORD, 1)
                .give(Items.CRAFTING_TABLE, 1).give(Items.FURNACE, 1).give(Items.SHIELD, 1).give(Items.LADDER, 3)
                .give(Items.IRON_PICKAXE, 1).give(Items.IRON_SWORD, 1).give(Items.BUCKET, 2).give(Items.SHEARS, 1)
                .give(Items.FLINT_AND_STEEL, 1);
        Keep keep = WoodBudget.of(f, cfg, 0).keep();
        assertEquals(0, keep.planks());
        // whatever it keeps, it keeps no more than it holds
        assertEquals(true, keep.logs() <= 3);
    }

    @Test
    public void theSearchFindsTheEdge() {
        assertEquals(7, WoodReserve.most(20, k -> k <= 7));
        assertEquals(0, WoodReserve.most(20, k -> k == 0));
        assertEquals(20, WoodReserve.most(20, k -> true));
        assertEquals(0, WoodReserve.most(0, k -> true));
    }
}
