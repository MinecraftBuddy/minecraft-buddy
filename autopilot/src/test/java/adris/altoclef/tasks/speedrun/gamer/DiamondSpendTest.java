package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.gamer.DiamondSpend.Open;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// what the bag's diamonds buy: the greedy walk on its own, then the walk against real gear
public class DiamondSpendTest {
    private static final Open ALL = new Open(true, true, true, true, true, true);
    private static final List<String> ORDER = List.of("diamond_pickaxe", "diamond_chestplate", "diamond_leggings",
            "diamond_boots", "diamond_helmet", "diamond_sword");

    private OverworldConfig cfg;
    private FakeFacts f;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        cfg.netherSpareShields = 0;
        cfg.netherSpareSwords = 0;
        f = new FakeFacts();
    }

    private static Open random(Random r) {
        return new Open(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean());
    }

    private static Open without(Open o, String name) {
        return new Open(o.pickaxe() && !name.equals("diamond_pickaxe"), o.chestplate() && !name.equals("diamond_chestplate"),
                o.leggings() && !name.equals("diamond_leggings"), o.boots() && !name.equals("diamond_boots"),
                o.helmet() && !name.equals("diamond_helmet"), o.sword() && !name.equals("diamond_sword"));
    }

    // iron on every slot and in the bag, worn
    private FakeFacts ironed() {
        FakeFacts bag = new FakeFacts().give(Items.IRON_PICKAXE, 1).give(Items.IRON_SWORD, 1);
        for (Item piece : new Item[]{Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_HELMET}) {
            bag.give(piece, 1);
            bag.worn.add(piece);
        }
        return bag;
    }

    @Test
    public void twoDiamondsAreSavedForThePickWhileItIsOpen() {
        assertEquals(List.of(), DiamondSpend.choose(new FakeFacts().give(Items.DIAMOND, 2), cfg));
        assertEquals(List.of("diamond_sword"),
                DiamondSpend.choose(new FakeFacts().give(Items.DIAMOND, 2).give(Items.DIAMOND_PICKAXE, 1), cfg));
    }

    @Test
    public void fewerThanTwoBuysNothing() {
        for (int d = 0; d < DiamondSpend.CHEAPEST; d++) {
            assertEquals(List.of(), DiamondSpend.spend(d, ALL));
            assertEquals(List.of(), DiamondSpend.choose(new FakeFacts().give(Items.DIAMOND, d), cfg));
        }
    }

    @Test
    public void theWalkTakesWhatTheLeftoversPayFor() {
        assertEquals(List.of("diamond_pickaxe"), DiamondSpend.spend(3, ALL));
        assertEquals(List.of("diamond_pickaxe"), DiamondSpend.spend(4, ALL));
        // the 2 over is the sword, the last thing on the list takes what nothing else can use
        assertEquals(List.of("diamond_pickaxe", "diamond_sword"), DiamondSpend.spend(5, ALL));
        assertEquals(List.of("diamond_pickaxe", "diamond_sword"), DiamondSpend.spend(6, ALL));
        // 4 left over after the pick is exactly the boots
        assertEquals(List.of("diamond_pickaxe", "diamond_boots"), DiamondSpend.spend(7, ALL));
        assertEquals(List.of("diamond_pickaxe", "diamond_leggings"), DiamondSpend.spend(10, ALL));
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate"), DiamondSpend.spend(11, ALL));
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate", "diamond_boots"), DiamondSpend.spend(15, ALL));
        // 3 + 8 + 7 + 4 + 5 + 2 = 29, the whole list
        assertEquals(ORDER, DiamondSpend.spend(29, ALL));
        assertEquals(ORDER, DiamondSpend.spend(40, ALL));
        // 18 is the pick and the chestplate and the leggings, the rest of the list does not fit in the 0 left
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate", "diamond_leggings"), DiamondSpend.spend(18, ALL));
    }

    @Test
    public void ownedThingsAreSkippedAndCostNothing() {
        // no pick open (we have one): the 8 goes to the chestplate with 3 over for nothing but the sword
        assertEquals(List.of("diamond_chestplate", "diamond_sword"), DiamondSpend.spend(11, without(ALL, "diamond_pickaxe")));
        assertEquals(List.of("diamond_chestplate"), DiamondSpend.spend(8, without(ALL, "diamond_pickaxe")));
        assertEquals(List.of(), DiamondSpend.spend(40, new Open(false, false, false, false, false, false)));
    }

    @Test
    public void everyChoiceIsPaidForByTheDiamondsHeld() {
        // the catalogue mines a diamond ore for any diamond it is short of, so a craft that is not covered is a trip into a cave
        Random r = new Random(7);
        for (int round = 0; round < 400; round++) {
            Open open = random(r);
            for (int d = 0; d <= 40; d++) {
                List<String> chosen = DiamondSpend.spend(d, open);
                int paid = 0;
                int last = -1;
                for (String name : chosen) {
                    assertTrue(name, DiamondSpend.cost(name) > 0);
                    paid += DiamondSpend.cost(name);
                    // in list order and never twice
                    int at = ORDER.indexOf(name);
                    assertTrue(chosen.toString(), at > last);
                    last = at;
                }
                assertTrue("d=" + d + " " + chosen, paid <= d);
            }
        }
    }

    @Test
    public void everyDiamondCountIsPaidAgainstTheRealPlan() {
        // the same over the planner: whatever the plan asks for in diamond items is covered by the diamonds in the bag
        Random r = new Random(11);
        Item[] gear = {Items.IRON_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_PICKAXE, Items.IRON_CHESTPLATE, Items.DIAMOND_LEGGINGS,
                Items.IRON_BOOTS, Items.NETHERITE_HELMET, Items.IRON_HELMET, Items.DIAMOND_SWORD};
        for (int round = 0; round < 60; round++) {
            FakeFacts bag = new FakeFacts();
            for (Item item : gear) {
                if (r.nextInt(3) == 0) {
                    bag.give(item, 1);
                }
            }
            for (int d = 0; d <= 40; d++) {
                bag.items.put(Items.DIAMOND, d);
                int need = 0;
                for (KitNeed n : KitPlanner.plan(bag, cfg, 8)) {
                    if (n.catalogueName().startsWith("diamond_")) {
                        need += DiamondSpend.cost(n.catalogueName());
                        assertTrue("diamond item " + n + " not in the list", DiamondSpend.cost(n.catalogueName()) > 0);
                    }
                }
                assertTrue("round " + round + " d=" + d + " plan wants " + need, need <= d);
            }
        }
    }

    @Test
    public void craftingAnythingOnTheListLeavesTheRestOfTheListAlone() {
        // the diamonds a craft eats and the item it turns into cancel out, whatever the order the crafts happen in. this is
        // what keeps the plan still while the bot works through it
        Random r = new Random(3);
        for (int round = 0; round < 300; round++) {
            Open open = random(r);
            for (int d = 0; d <= 40; d++) {
                List<String> chosen = DiamondSpend.spend(d, open);
                for (String crafted : chosen) {
                    List<String> rest = new ArrayList<>(chosen);
                    rest.remove(crafted);
                    assertEquals("d=" + d + " after " + crafted, rest,
                            DiamondSpend.spend(d - DiamondSpend.cost(crafted), without(open, crafted)));
                }
            }
        }
    }

    @Test
    public void oneMoreDiamondOnlyEverSwapsForADearerItemOrAddsOne() {
        // not a superset, on purpose: 9 buys the boots, 10 the leggings, 11 the chestplate, and the sword (the 2 nothing else could
        // use) goes the moment a dearer piece fits. what it never does is swap an item out and spend fewer diamonds for it
        List<Integer> swaps = new ArrayList<>();
        for (int d = 0; d < 40; d++) {
            List<String> now = DiamondSpend.spend(d, ALL);
            List<String> next = DiamondSpend.spend(d + 1, ALL);
            if (!next.containsAll(now)) {
                swaps.add(d);
                int paidNow = now.stream().mapToInt(DiamondSpend::cost).sum();
                int paidNext = next.stream().mapToInt(DiamondSpend::cost).sum();
                assertTrue("d=" + d, paidNext > paidNow);
            }
        }
        assertEquals(List.of(2, 6, 9, 10, 14, 17, 21, 26), swaps);
    }

    @Test
    public void aBareBagWithDiamondsBuysThePickFirst() {
        f.give(Items.DIAMOND, 3);
        assertEquals(List.of("diamond_pickaxe"), DiamondSpend.choose(f, cfg));
    }

    @Test
    public void ironEverywhereIsUpgradedNotSkipped() {
        // worn iron on every slot and an iron pick: the diamonds still go to the pick and the armor, but never to the iron sword
        FakeFacts bag = ironed().give(Items.DIAMOND, 30);
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate", "diamond_leggings", "diamond_boots", "diamond_helmet"),
                DiamondSpend.choose(bag, cfg));
    }

    @Test
    public void theSwordIsOnlyAStandInForTheIronOne() {
        f.give(Items.DIAMOND, 30);
        assertTrue(DiamondSpend.choose(f, cfg).contains("diamond_sword"));
        for (Item sword : new Item[]{Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD}) {
            FakeFacts has = new FakeFacts().give(Items.DIAMOND, 30).give(sword, 1);
            assertFalse(sword.toString(), DiamondSpend.choose(has, cfg).contains("diamond_sword"));
        }
        // a stone sword is no reason to hold off, a kit with no iron sword in it has nothing to stand in for
        assertTrue(DiamondSpend.choose(new FakeFacts().give(Items.DIAMOND, 30).give(Items.STONE_SWORD, 1), cfg).contains("diamond_sword"));
        cfg.ironKit.removeIf(k -> k.item.equals("iron_sword"));
        assertFalse(DiamondSpend.choose(f, cfg).contains("diamond_sword"));
    }

    @Test
    public void slotsAlreadyAtDiamondOrBetterAreSkipped() {
        FakeFacts bag = new FakeFacts().give(Items.DIAMOND, 40).give(Items.DIAMOND_PICKAXE, 1).give(Items.DIAMOND_CHESTPLATE, 1)
                .give(Items.NETHERITE_BOOTS, 1);
        bag.worn.add(Items.DIAMOND_CHESTPLATE);
        assertEquals(List.of("diamond_leggings", "diamond_helmet", "diamond_sword"), DiamondSpend.choose(bag, cfg));
    }

    @Test
    public void armorPlansLimitTheSlots() {
        f.give(Items.DIAMOND, 40);
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate", "diamond_helmet", "diamond_sword"), DiamondSpend.choose(f, cfg));
        cfg.armorPlan = ArmorPlan.NONE;
        assertEquals(List.of("diamond_pickaxe", "diamond_sword"), DiamondSpend.choose(f, cfg));
    }

    @Test
    public void aSlotThePlanWentGoldInIsNotDiamondEither() {
        // 5 gold ingots in the bag: the helmet is the piglin piece (PiglinGold), so the diamonds go to the other slots
        f.give(Items.DIAMOND, 40).give(Items.GOLD_INGOT, 5);
        List<String> chosen = DiamondSpend.choose(f, cfg);
        assertFalse(chosen.contains("diamond_helmet"));
        assertTrue(chosen.contains("diamond_chestplate"));
    }

    @Test
    public void switchedOffChoosesNothing() {
        cfg.diamondSpend = false;
        f.give(Items.DIAMOND, 40);
        assertEquals(List.of(), DiamondSpend.choose(f, cfg));
        assertFalse(DiamondSpend.takesThePick(f, cfg));
    }

    @Test
    public void diamondsInTheGridStillCountSoNothingFlipsMidCraft() {
        // MinecraftFacts counts the cursor, the 2x2 and the table's 3x3 as ours, so the count a craft sees never dips. the same
        // bag with the diamonds gone and the pick in hand is the rest of the list
        FakeFacts before = new FakeFacts().give(Items.DIAMOND, 11);
        assertEquals(List.of("diamond_pickaxe", "diamond_chestplate"), DiamondSpend.choose(before, cfg));
        FakeFacts after = new FakeFacts().give(Items.DIAMOND, 8).give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(List.of("diamond_chestplate"), DiamondSpend.choose(after, cfg));
    }

    @Test
    public void everyNameOnTheListIsInTheCatalogue() {
        for (String name : ORDER) {
            assertTrue(name, TaskCatalogue.taskExists(name));
        }
    }

    @Test
    public void replacesPairsEachDiamondItemWithItsIronOne() {
        List<String> chosen = List.of("diamond_pickaxe", "diamond_boots");
        assertTrue(DiamondSpend.replaces(chosen, "iron_pickaxe"));
        assertTrue(DiamondSpend.replaces(chosen, "iron_boots"));
        assertFalse(DiamondSpend.replaces(chosen, "iron_axe"));
        assertFalse(DiamondSpend.replaces(chosen, "iron_ingot"));
        assertFalse(DiamondSpend.replaces(chosen, "golden_boots"));
        assertFalse(DiamondSpend.replaces(List.of(), "iron_pickaxe"));
    }
}
