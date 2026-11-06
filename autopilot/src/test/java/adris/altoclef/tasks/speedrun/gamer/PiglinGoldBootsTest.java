package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// gold boots are 4 ingots and a piglin pass, but never at the price of the diamond boots we are wearing
public class PiglinGoldBootsTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static FakeFacts fourIngots() {
        return new FakeFacts().give(Items.GOLD_INGOT, 4);
    }

    @Test
    public void fourIngotsAndBareFeetCraftTheBoots() {
        assertEquals(List.of(new KitNeed("golden_boots", 1)), PiglinGold.gate(fourIngots()));
    }

    @Test
    public void wornDiamondBootsKeepTheGateFromCraftingGoldOnes() {
        FakeFacts bag = fourIngots();
        bag.worn.add(Items.DIAMOND_BOOTS);
        assertTrue(PiglinGold.gate(bag).isEmpty());
        FakeFacts netherite = fourIngots();
        netherite.worn.add(Items.NETHERITE_BOOTS);
        assertTrue(PiglinGold.gate(netherite).isEmpty());
    }

    @Test
    public void fiveIngotsStillMakeTheHelmetOverDiamondBoots() {
        FakeFacts bag = new FakeFacts().give(Items.GOLD_INGOT, 5);
        bag.worn.add(Items.DIAMOND_BOOTS);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PiglinGold.gate(bag));
    }

    @Test
    public void goldBootsInTheBagAreNotWornOverDiamondOnes() {
        FakeFacts bag = new FakeFacts().give(Items.GOLDEN_BOOTS, 1);
        bag.worn.add(Items.DIAMOND_BOOTS);
        List<Item> equip = new ArrayList<>();
        PiglinGold.addToEquip(bag, equip);
        assertTrue(equip.isEmpty());
        // with nothing on the feet they go on as ever
        FakeFacts bare = new FakeFacts().give(Items.GOLDEN_BOOTS, 1);
        PiglinGold.addToEquip(bare, equip);
        assertEquals(List.of(Items.GOLDEN_BOOTS), equip);
    }

    @Test
    public void thePlanNeverSwapsDiamondBootsForGold() {
        FakeFacts bag = fourIngots();
        bag.worn.add(Items.DIAMOND_BOOTS);
        assertNull(PiglinGold.slot(bag, List.of("iron_boots")));
        FakeFacts held = new FakeFacts().give(Items.GOLDEN_BOOTS, 1);
        held.worn.add(Items.DIAMOND_BOOTS);
        assertNull(PiglinGold.slot(held, List.of("iron_boots")));
        assertEquals("boots", PiglinGold.slot(fourIngots(), List.of("iron_boots")));
    }

    @Test
    public void goldBootsDeadWeightNextToDiamondOnesDoNotBlockTheHelmet() {
        FakeFacts bag = new FakeFacts().give(Items.GOLDEN_BOOTS, 1).give(Items.GOLD_INGOT, 5);
        bag.worn.add(Items.DIAMOND_BOOTS);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PiglinGold.gate(bag));
        assertTrue(!PiglinGold.holdsPiece(bag));
        // and the nether fallback digs for the helmet, not for boots that would never go on
        assertEquals("helmet", PiglinGold.netherSlot(new FakeFacts() {{ worn.add(Items.DIAMOND_BOOTS); }}, List.of("iron_boots")));
        assertEquals("boots", PiglinGold.netherSlot(new FakeFacts(), List.of("iron_boots")));
    }
}
