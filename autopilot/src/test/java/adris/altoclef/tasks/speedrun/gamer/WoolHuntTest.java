package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the sheep chase for the beds: a clock like the gold dig's, and once it gives up the plan stops asking for wool
public class WoolHuntTest {
    private static final int BEDS = 5;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    @Test
    public void theClockOnlyBillsChaseTicksAndGivesUpOnceAfterTheMinutes() {
        RunState s = new RunState();
        double last = -1;
        int gaveUp = 0;
        for (int t = 0; t <= 400; t++) {
            // every other 10 s is another job holding the wheel: no bill
            boolean chased = !s.woolHuntGaveUp && (t / 10) % 2 == 0;
            DiamondHunt.Step step = WoolHunt.tick(s, last, chased, t, 3);
            last = step.lastAge();
            gaveUp += step.gaveUp() ? 1 : 0;
        }
        assertEquals(1, gaveUp);
        assertTrue(s.woolHuntGaveUp);
        assertEquals(180, s.woolHuntSeconds, 11);
        double billed = s.woolHuntSeconds;
        // a flag that is set stays set
        DiamondHunt.Step after = WoolHunt.tick(s, -1, true, 900, 3);
        assertFalse(after.gaveUp());
        assertEquals(billed, s.woolHuntSeconds, 0);
    }

    @Test
    public void aStraightChaseGivesUpAtThreeMinutes() {
        RunState s = new RunState();
        double last = -1;
        for (int t = 0; t < 180; t++) {
            DiamondHunt.Step step = WoolHunt.tick(s, last, true, t, 3);
            assertFalse("t " + t, step.gaveUp());
            last = step.lastAge();
        }
        assertTrue(WoolHunt.tick(s, last, true, 180, 3).gaveUp());
    }

    @Test
    public void onlyTheWoolNeedIsTheChase() {
        assertTrue(WoolHunt.chasing(new KitNeed("wool", 15)));
        assertFalse(WoolHunt.chasing(new KitNeed("raw_gold", 5)));
        assertFalse(WoolHunt.chasing(null));
    }

    @Test
    public void aGivenUpChaseIsNeverAskedForAgain() {
        FakeFacts bag = new FakeFacts();
        assertNotNull(find(KitPlanner.plan(bag, cfg, BEDS), "wool"));
        bag.woolHuntGaveUp = true;
        assertNull(find(KitPlanner.plan(bag, cfg, BEDS), "wool"));
    }

    @Test
    public void aGivenUpChaseDropsTheWoolStockUpToo() {
        cfg.smeltExtras = List.of(new OverworldConfig.KitItem("wool_beds", 3));
        FakeFacts bag = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3)
                .cooking("iron_ingot", 40, 400);
        bag.foodUnits = 70;
        // the beds' wool is in hand, only the extra three beds' worth is left to ask for
        bag.give(Items.WOOL.white(), 3 * BEDS);
        assertNotNull(findAny(SmeltFiller.schedule(bag, cfg, BEDS, true), "wool"));
        bag.woolHuntGaveUp = true;
        assertNull(findAny(SmeltFiller.schedule(bag, cfg, BEDS, true), "wool"));
    }

    private static KitNeed findAny(SmeltFiller.Schedule s, String name) {
        for (List<KitNeed> list : List.of(s.runnable(), s.blocked(), s.stockUps())) {
            KitNeed hit = find(list, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    public void aDeathGivesTheChaseItsClockBack() {
        RunState s = new RunState();
        s.woolHuntSeconds = 300;
        s.woolHuntGaveUp = true;
        WoolHunt.onDeath(s);
        assertFalse(s.woolHuntGaveUp);
        assertEquals(0, s.woolHuntSeconds, 0);
        // and the need is back on the plan
        assertNotNull(find(KitPlanner.plan(new FakeFacts(), cfg, BEDS), "wool"));
    }

    @Test
    public void theSpawnBedStillOwedAsksForItsWoolToo() {
        FakeFacts bag = new FakeFacts().give(Items.WOOL.white(), 3 * BEDS);
        assertNull(find(KitPlanner.plan(bag, cfg, BEDS), "wool"));
        bag.spawnBedOwed = true;
        assertEquals(BEDS + 1, KitPlanner.bedGoal(bag, BEDS));
        assertNotNull(find(KitPlanner.plan(bag, cfg, BEDS), "wool"));
    }

    @Test
    public void woolBedsIsNotInTheDefaultStockUps() {
        assertTrue(new OverworldConfig().smeltExtras.stream().noneMatch(e -> e.item.equals("wool_beds")));
    }
}
