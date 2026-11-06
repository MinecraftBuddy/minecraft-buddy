package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// raw gold for the piglin helmet: only asked for while an ore is already known, and given up on after its clock
public class GoldHuntTest {
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
        cfg.cookFuelLogs = 0;
    }

    // the whole default kit in hand and on, so the plan has nothing left but the gold
    private static FakeFacts complete() {
        FakeFacts full = new FakeFacts();
        for (Item i : new Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.FURNACE, Items.IRON_PICKAXE, Items.IRON_SWORD,
                Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        full.give(Items.WOODEN_AXE, 1).give(Items.OAK_LOG, 20);
        full.give(Items.BUCKET, 2).give(Items.LADDER, 3).give(Items.WOOL.white(), 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        full.foodUnits = 100;
        full.diamondHuntGaveUp = true;
        return full;
    }

    private List<KitNeed> plan(FakeFacts f) {
        return KitPlanner.plan(f, cfg, 8);
    }

    @Test
    public void noOreKnownMeansNoNeedAtAll() {
        FakeFacts bag = complete();
        assertTrue(plan(bag).isEmpty());
        assertNull(GoldHunt.need(bag));
    }

    @Test
    public void anOreKnownAsksForTheHelmetsWorthOfRawGold() {
        FakeFacts bag = complete();
        bag.goldOreKnown = true;
        assertEquals(List.of(new KitNeed("raw_gold", 5)), plan(bag));
    }

    @Test
    public void whatWeHoldCountsTowardsTheFive() {
        FakeFacts bag = complete();
        bag.goldOreKnown = true;
        bag.give(Items.RAW_GOLD, 2);
        // hold five in total, the planner nets out what is in the bag
        assertEquals(List.of(new KitNeed("raw_gold", 5)), plan(bag));
        bag.give(Items.GOLD_INGOT, 1);
        assertEquals(List.of(new KitNeed("raw_gold", 4)), plan(bag));
    }

    private static boolean asksForGold(List<KitNeed> plan) {
        return plan.stream().anyMatch(n -> n.catalogueName().equals("raw_gold"));
    }

    @Test
    public void enoughGoldOrAGoldPieceMeansNoNeed() {
        // (the plan can still craft or put on the piece, it just does not dig for more)
        FakeFacts ingots = complete();
        ingots.goldOreKnown = true;
        ingots.give(Items.GOLD_INGOT, 5);
        assertFalse(asksForGold(plan(ingots)));
        FakeFacts mixed = complete();
        mixed.goldOreKnown = true;
        mixed.give(Items.RAW_GOLD, 2).give(Items.GOLD_INGOT, 3);
        assertFalse(asksForGold(plan(mixed)));
        FakeFacts held = complete();
        held.goldOreKnown = true;
        held.give(Items.GOLDEN_HELMET, 1);
        assertFalse(asksForGold(plan(held)));
        FakeFacts worn = complete();
        worn.goldOreKnown = true;
        worn.worn.add(Items.GOLDEN_BOOTS);
        assertFalse(asksForGold(plan(worn)));
    }

    @Test
    public void aGivenUpDigIsNeverAskedForAgain() {
        FakeFacts bag = complete();
        bag.goldOreKnown = true;
        bag.goldHuntGaveUp = true;
        assertFalse(asksForGold(plan(bag)));
        assertTrue(plan(bag).isEmpty());
    }

    @Test
    public void theDigWaitsForTheEssentials() {
        FakeFacts bag = complete();
        bag.goldOreKnown = true;
        // the ore needs the iron pick
        bag.items.remove(Items.IRON_PICKAXE);
        assertFalse(plan(bag).stream().anyMatch(n -> n.catalogueName().equals("raw_gold")));
    }

    @Test
    public void theDigIsGatheringNotACraft() {
        assertTrue(new KitNeed("raw_gold", 5).isGathering());
        assertFalse(new KitNeed("raw_gold", 5).isCraft());
    }

    @Test
    public void theClockOnlyBillsDigTicksAndGivesUpOnceAfterTheMinutes() {
        RunState s = new RunState();
        double last = -1;
        int gaveUp = 0;
        for (int t = 0; t <= 400; t++) {
            // every other 10 s is another job holding the wheel: no bill
            boolean dug = !s.goldHuntGaveUp && (t / 10) % 2 == 0;
            DiamondHunt.Step step = GoldHunt.tick(s, last, dug, t, 3);
            last = step.lastAge();
            gaveUp += step.gaveUp() ? 1 : 0;
        }
        assertEquals(1, gaveUp);
        assertTrue(s.goldHuntGaveUp);
        assertEquals(180, s.goldHuntSeconds, 11);
        double billed = s.goldHuntSeconds;
        // a flag that is set stays set
        DiamondHunt.Step after = GoldHunt.tick(s, -1, true, 900, 3);
        assertFalse(after.gaveUp());
        assertEquals(billed, s.goldHuntSeconds, 0);
    }

    @Test
    public void aStraightDigGivesUpAtThreeMinutes() {
        RunState s = new RunState();
        double last = -1;
        for (int t = 0; t < 180; t++) {
            DiamondHunt.Step step = GoldHunt.tick(s, last, true, t, 3);
            assertFalse("t " + t, step.gaveUp());
            last = step.lastAge();
        }
        assertTrue(GoldHunt.tick(s, last, true, 180, 3).gaveUp());
    }

    @Test
    public void onlyTheGoldNeedIsTheGoldDig() {
        assertTrue(GoldHunt.digging(new KitNeed("raw_gold", 5)));
        assertFalse(GoldHunt.digging(new KitNeed(KitPlanner.DIAMOND, 3)));
        assertFalse(GoldHunt.digging(null));
    }
}
