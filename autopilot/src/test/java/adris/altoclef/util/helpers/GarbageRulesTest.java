package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.GarbageRules.Cand;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

public class GarbageRulesTest {

    // the default setting, what the gamer overrides with minFoodUnits
    private static final int FLOOR = 40;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static int bag(ItemStack... stacks) {
        int sum = 0;
        for (ItemStack s : stacks) {
            sum += GarbageRules.keepUnits(s);
        }
        return sum;
    }

    // the index of the stack that goes, -1 for none
    private static int pick(int floor, ItemStack... stacks) {
        List<Cand> cands = new ArrayList<>();
        for (ItemStack s : stacks) {
            cands.add(GarbageRules.cand(s));
        }
        return GarbageRules.pick(cands, bag(stacks), floor);
    }

    @Test
    public void foodSumIsNutritionTimesCountNotPerStack() {
        // cooked beef is 8 a piece. the old sum added 8 per stack, so a stack of 16 looked like a single steak
        assertEquals(8, GarbageRules.keepUnits(new ItemStack(Items.COOKED_BEEF, 1)));
        assertEquals(128, GarbageRules.keepUnits(new ItemStack(Items.COOKED_BEEF, 16)));
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.COBBLESTONE, 64)));
        assertEquals(0, GarbageRules.keepUnits(ItemStack.EMPTY));
    }

    @Test
    public void rawMeatCountsAtItsCookedValueLikeTheGamerDoes() {
        // GamerFacts.foodUnits books raw mutton at 6, not the 2 it is raw
        assertEquals(6, GarbageRules.keepUnits(new ItemStack(Items.MUTTON, 1)));
    }

    @Test
    public void rottenFleshAndGapplesDoNotCountTowardTheFloor() {
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.ROTTEN_FLESH, 20)));
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.GOLDEN_APPLE, 3)));
    }

    @Test
    public void gappleIsNeverTheStackThatGoes() {
        ItemStack gapple = new ItemStack(Items.GOLDEN_APPLE, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 3);
        ItemStack pick = new ItemStack(Items.WOODEN_PICKAXE, 1);
        // under the floor it used to rank after junk and tools and still go once those were gone
        assertEquals(1, pick(FLOOR, gapple, cobble));
        assertEquals(1, pick(FLOOR, gapple, pick));
        assertEquals(-1, pick(FLOOR, gapple));
        assertEquals(-1, pick(0, gapple, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)));
    }

    @Test
    public void fullBagUnderTheFloorThrowsCobbleNeverTheSteak() {
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 16);
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 2);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        // 16 units against a floor of 40: the steak stays whatever order the bag is in
        assertEquals(0, pick(FLOOR, cobble, steak, pick));
        assertEquals(2, pick(FLOOR, steak, pick, cobble));
        // and without the cobble the steak still stays, the pick is what goes
        assertEquals(1, pick(FLOOR, steak, pick));
    }

    @Test
    public void underTheFloorNothingFoodIsEverGarbage() {
        assertEquals(-1, pick(FLOOR, new ItemStack(Items.COOKED_BEEF, 2), new ItemStack(Items.BREAD, 3)));
        assertTrue(GarbageRules.keepsFood(16, 16, FLOOR));
    }

    @Test
    public void surplusFoodGoesOnlyAfterEveryNonFoodCandidate() {
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        ItemStack bread = new ItemStack(Items.BREAD, 2);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        // 128 + 10 units, bread is pure surplus. but the cobble goes first, then even the pick, then the bread
        assertEquals(3, pick(FLOOR, steak, bread, pick, cobble));
        assertEquals(2, pick(FLOOR, steak, bread, pick));
        assertEquals(1, pick(FLOOR, steak, bread));
    }

    @Test
    public void surplusFoodGoesLowestUnitsFirst() {
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        ItemStack bread = new ItemStack(Items.BREAD, 4);
        ItemStack carrot = new ItemStack(Items.CARROT, 2);
        assertEquals(2, pick(FLOOR, steak, bread, carrot));
    }

    @Test
    public void fewerPlannedUnitsGoFirstEvenWhenTheyAreCooked() {
        // 8 raw beef is 64 at the gamer's measure, 4 cooked is 32. the cooked stack is the smaller loss
        ItemStack raw = new ItemStack(Items.BEEF, 8);
        ItemStack cooked = new ItemStack(Items.COOKED_BEEF, 4);
        ItemStack steak = new ItemStack(Items.COOKED_PORKCHOP, 16);
        assertEquals(1, pick(FLOOR, raw, cooked, steak));
    }

    @Test
    public void gapplesAndChorusFruitGoAfterJunkAndToolsToo() {
        ItemStack apple = new ItemStack(Items.GOLDEN_APPLE, 1);
        ItemStack chorus = new ItemStack(Items.CHORUS_FRUIT, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 30);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        assertEquals(2, pick(FLOOR, apple, chorus, cobble, pick));
        assertEquals(3, pick(FLOOR, apple, chorus, steak, pick));
        // and rotten flesh is junk like any other, it goes ahead of the pick
        ItemStack rotten = new ItemStack(Items.ROTTEN_FLESH, 20);
        assertEquals(2, pick(FLOOR, apple, pick, rotten));
    }

    @Test
    public void rawBeforeCookedWhenTheCountsMatch() {
        // both are 64 units at the gamer's measure, the raw one gives 3 a piece as it is and goes first
        ItemStack cooked = new ItemStack(Items.COOKED_BEEF, 8);
        ItemStack raw = new ItemStack(Items.BEEF, 8);
        ItemStack steak = new ItemStack(Items.COOKED_PORKCHOP, 16);
        assertEquals(1, pick(FLOOR, cooked, raw, steak));
    }

    @Test
    public void aStackIsOnlyGarbageIfTheRestStillClearsTheFloor() {
        // 41 units and a floor of 40: throwing the 40 would leave 1, so the one big stack stays
        assertTrue(GarbageRules.keepsFood(41, 40, FLOOR));
        assertFalse(GarbageRules.keepsFood(41, 1, FLOOR));
        assertFalse(GarbageRules.keepsFood(100, 60, FLOOR));
        // two stacks that are each spare alone are not both spare together
        ItemStack a = new ItemStack(Items.COOKED_BEEF, 7);
        ItemStack b = new ItemStack(Items.COOKED_PORKCHOP, 8);
        assertEquals(0, pick(FLOOR, a, b));
        assertEquals(-1, pick(FLOOR, b));
    }

    @Test
    public void aFloorOfZeroTurnsTheRuleOff() {
        assertEquals(0, pick(0, new ItemStack(Items.COOKED_BEEF, 2)));
    }

    @Test
    public void toolsStillGoWorstFirstAndJunkBeforeTools() {
        ItemStack wood = new ItemStack(Items.WOODEN_PICKAXE, 1);
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack junk = new ItemStack(Items.FLINT, 3);
        assertEquals(0, pick(FLOOR, wood, iron));
        assertEquals(2, pick(FLOOR, wood, iron, junk));
    }

    @Test
    public void heldFoodUnderTheFloorIsNotThrowableButSpareFoodIs() {
        // the cursor is in the bag total, so a lone 2 steak stack on it is the whole 16
        ItemStack held = new ItemStack(Items.COOKED_BEEF, 2);
        assertTrue(GarbageRules.keepsFood(bag(held), GarbageRules.keepUnits(held), FLOOR));
        ItemStack spare = new ItemStack(Items.BREAD, 2);
        ItemStack stock = new ItemStack(Items.COOKED_BEEF, 16);
        assertFalse(GarbageRules.keepsFood(bag(spare, stock), GarbageRules.keepUnits(spare), FLOOR));
    }

    @Test
    public void aPickaxeNeverLosesToASword() {
        // both plain Item since 1.21.2, the old getClass() bucket threw the pick here
        assertEquals(-1, GarbageRules.worseTool(List.of(Items.IRON_PICKAXE, Items.DIAMOND_SWORD)));
        assertEquals(-1, GarbageRules.worseTool(List.of(Items.DIAMOND_SWORD, Items.IRON_PICKAXE, Items.STONE_AXE, Items.WOODEN_SHOVEL)));
    }

    @Test
    public void theWorsePickaxeGoes() {
        assertEquals(0, GarbageRules.worseTool(List.of(Items.STONE_PICKAXE, Items.IRON_PICKAXE)));
        assertEquals(1, GarbageRules.worseTool(List.of(Items.IRON_PICKAXE, Items.STONE_PICKAXE)));
        // a sword in between changes nothing
        assertEquals(2, GarbageRules.worseTool(List.of(Items.DIAMOND_PICKAXE, Items.WOODEN_SWORD, Items.WOODEN_PICKAXE)));
    }

    @Test
    public void slotsWeMayNotThrowAreSkipped() {
        List<net.minecraft.world.item.Item> items = new ArrayList<>();
        items.add(null);
        items.add(Items.IRON_PICKAXE);
        items.add(Items.DIRT);
        assertEquals(-1, GarbageRules.worseTool(items));
    }

    private static boolean wouldWear(net.minecraft.world.item.Item bag, net.minecraft.world.item.Item worn) {
        return GarbageRules.wouldWear(worn == null, ItemHelper.getArmorPoints(bag), ItemHelper.getArmorToughness(bag),
                worn == null ? 0 : ItemHelper.getArmorPoints(worn), worn == null ? 0 : ItemHelper.getArmorToughness(worn));
    }

    @Test
    public void armorForABareSlotOrBetterThanWornIsKept() {
        assertTrue(wouldWear(Items.IRON_CHESTPLATE, null));
        assertTrue(wouldWear(Items.IRON_CHESTPLATE, Items.LEATHER_CHESTPLATE));
        // same points, diamond's toughness breaks the tie
        assertTrue(wouldWear(Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE));
        // an elytra on the back is worth no armor, the chestplate in the bag is not garbage
        assertTrue(wouldWear(Items.IRON_CHESTPLATE, Items.ELYTRA));
    }

    @Test
    public void spareArmorStaysThrowable() {
        assertFalse(wouldWear(Items.IRON_CHESTPLATE, Items.IRON_CHESTPLATE));
        assertFalse(wouldWear(Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE));
        assertFalse(wouldWear(Items.LEATHER_HELMET, Items.IRON_HELMET));
    }

    @Test
    public void armorPointsComeFromTheItem() {
        assertEquals(6.0, ItemHelper.getArmorPoints(Items.IRON_CHESTPLATE), 0.001);
        assertEquals(2.0, ItemHelper.getArmorToughness(Items.DIAMOND_CHESTPLATE), 0.001);
        assertEquals(0.0, ItemHelper.getArmorPoints(Items.COBBLESTONE), 0.001);
    }

    private static int lastResort(ItemStack... stacks) {
        List<Cand> cands = new ArrayList<>();
        for (ItemStack s : stacks) {
            cands.add(GarbageRules.cand(s));
        }
        return GarbageRules.lastResort(cands);
    }

    @Test
    public void lastResortTakesTheLowestStackAndIgnoresTheFoodFloor() {
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 3);
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE, 1);
        ItemStack netherrack = new ItemStack(Items.NETHERRACK, 64);
        // junk first, then tools, food last
        assertEquals(2, lastResort(steak, pick, netherrack));
        assertEquals(1, lastResort(steak, pick));
        // the floor would keep 3 steak, a stuck cursor does not care
        assertEquals(0, lastResort(steak));
        assertEquals(-1, pick(FLOOR, steak));
    }

    @Test
    public void lastResortStillSparesTheGappleAndCanComeUpEmpty() {
        assertEquals(-1, lastResort(new ItemStack(Items.GOLDEN_APPLE, 1)));
        assertEquals(-1, lastResort());
        assertEquals(1, lastResort(new ItemStack(Items.GOLDEN_APPLE, 1), new ItemStack(Items.BREAD, 2)));
    }

    // ---- which slots the garbage may look at

    private static Inventory inventory() {
        return new Inventory(null, new EntityEquipment());
    }

    @Test
    public void bagSlotsInThePlayerMenuSkipResultGridArmorAndOffhand() {
        Inventory inv = inventory();
        InventoryMenu menu = new InventoryMenu(inv, true, null);
        List<Integer> windows = GarbageRules.bagWindowSlots(menu, inv);
        assertEquals(36, windows.size());
        for (int w : windows) {
            assertTrue("window " + w, w >= InventoryMenu.INV_SLOT_START && w < InventoryMenu.USE_ROW_SLOT_END);
        }
    }

    @Test
    public void bagSlotsInATableSkipItsGridAndResult() {
        Inventory inv = inventory();
        CraftingMenu menu = new CraftingMenu(0, inv);
        List<Integer> windows = GarbageRules.bagWindowSlots(menu, inv);
        assertEquals(36, windows.size());
        // result 0, grid 1-9
        for (int w : windows) {
            assertTrue("window " + w, w >= 10);
        }
    }

    // Slot has no type for a shulker box, so the inventory index mapping pointed at the box's own slots
    @Test
    public void bagSlotsInAShulkerAreTheLastThirtySix() {
        Inventory inv = inventory();
        ShulkerBoxMenu menu = new ShulkerBoxMenu(0, inv);
        List<Integer> windows = GarbageRules.bagWindowSlots(menu, inv);
        assertEquals(36, windows.size());
        for (int w : windows) {
            assertTrue("window " + w, w >= 27);
        }
    }

    @Test
    public void bagSlotsInAChestComeAfterItsRows() {
        Inventory inv = inventory();
        ChestMenu menu = ChestMenu.threeRows(0, inv);
        List<Integer> windows = GarbageRules.bagWindowSlots(menu, inv);
        assertEquals(36, windows.size());
        // rows 0-26, then the bag 27-62 in one run
        for (int i = 0; i < windows.size(); i++) {
            assertEquals(27 + i, (int) windows.get(i));
        }
    }

    private static GarbageRules.Armor armor(int slot, net.minecraft.world.item.Item item, boolean wouldWear) {
        return new GarbageRules.Armor(slot, ItemHelper.getArmorPoints(item), ItemHelper.getArmorToughness(item), wouldWear);
    }

    @Test
    public void onlyTheBestPiecePerSlotIsKept() {
        // two iron helmets with nothing on the head: one goes on, the other is a spare
        boolean[] kept = GarbageRules.keptArmor(List.of(armor(0, Items.IRON_HELMET, true), armor(0, Items.IRON_HELMET, true)));
        assertTrue(kept[0]);
        assertFalse(kept[1]);
        // the better one wins wherever it sits, a different slot is its own question
        kept = GarbageRules.keptArmor(List.of(armor(0, Items.IRON_HELMET, true), armor(0, Items.DIAMOND_HELMET, true),
                armor(1, Items.IRON_CHESTPLATE, true), GarbageRules.Armor.NONE, armor(2, Items.IRON_LEGGINGS, false)));
        assertFalse(kept[0]);
        assertTrue(kept[1]);
        assertTrue(kept[2]);
        assertFalse(kept[3]);
        assertFalse(kept[4]);
    }

    // ---- the whole pick, and a drop that would come straight back out

    private static GarbageRules.Entry e(net.minecraft.world.item.Item item, int count) {
        return new GarbageRules.Entry(new ItemStack(item, count), true, -1);
    }

    // the default list's order (gravel and sand ahead of cobble, netherrack after), plus two non-blocks for the item tier
    private static java.util.Map<net.minecraft.world.item.Item, Integer> ranks;

    private static GarbageRules.Entry junk(net.minecraft.world.item.Item item, int count) {
        if (ranks == null) {
            ranks = GarbageRules.throwRanks(new net.minecraft.world.item.Item[]{Items.GRAVEL, Items.SAND, Items.COBBLESTONE, Items.DIRT,
                    Items.NETHERRACK, Items.SPIDER_EYE, Items.ROTTEN_FLESH, Items.GRAVEL});
        }
        return new GarbageRules.Entry(new ItemStack(item, count), true, ranks.get(item));
    }

    private static int next(List<GarbageRules.Entry> bag) {
        return GarbageRules.nextPick(bag, 64, true, 0, 0);
    }

    private static boolean comesBack(List<GarbageRules.Entry> bag, GarbageRules.Entry drop) {
        return GarbageRules.dropIsNextPick(bag, next(bag), drop, 64, true, 0, 0);
    }

    @Test
    public void thePickGoesJunkThenWorseToolThenAnything() {
        assertEquals(2, next(List.of(e(Items.STRING, 1), e(Items.STONE_SWORD, 1), junk(Items.ROTTEN_FLESH, 5))));
        assertEquals(2, next(List.of(e(Items.STRING, 1), e(Items.STONE_SWORD, 1), e(Items.WOODEN_SWORD, 1))));
        assertEquals(0, next(List.of(e(Items.STRING, 1), e(Items.STONE_SWORD, 1))));
        // blocks only past the reserve, then the block that is first on the throwaway list (cobble before netherrack)
        assertEquals(1, next(List.of(junk(Items.NETHERRACK, 64), e(Items.STRING, 1))));
        assertEquals(1, next(List.of(junk(Items.NETHERRACK, 40), junk(Items.COBBLESTONE, 30), e(Items.STRING, 9))));
        // nothing that may not go is ever the pick, with throwUnused off only junk and tools are
        assertEquals(-1, GarbageRules.nextPick(List.of(e(Items.STRING, 1)), 64, false, 0, 0));
        assertEquals(-1, next(List.of(new GarbageRules.Entry(new ItemStack(Items.STRING, 1), false, -1))));
    }

    // the menu lists the main bag before the hotbar, so going by bag order threw the cobble and kept the gravel
    @Test
    public void gravelInTheHotbarGoesBeforeCobbleInTheMainBag() {
        List<GarbageRules.Entry> bag = List.of(junk(Items.COBBLESTONE, 64), e(Items.STRING, 1), junk(Items.GRAVEL, 10));
        assertEquals(2, next(bag));
        assertEquals(2, GarbageRules.junkPick(bag, 64));
    }

    @Test
    public void junkGoesByListOrderAndBagOrderOnlyBreaksATie() {
        // non-blocks first whatever the list says about the blocks, then the earlier one on the list
        assertEquals(1, next(List.of(junk(Items.GRAVEL, 64), junk(Items.SPIDER_EYE, 2), junk(Items.ROTTEN_FLESH, 9))));
        assertEquals(2, next(List.of(junk(Items.GRAVEL, 64), junk(Items.ROTTEN_FLESH, 9), junk(Items.SPIDER_EYE, 2))));
        // two stacks of the same block: the first in the bag
        assertEquals(1, next(List.of(e(Items.STRING, 1), junk(Items.COBBLESTONE, 40), junk(Items.COBBLESTONE, 40))));
        // under the reserve no block goes, the list order does not change that
        assertEquals(-1, GarbageRules.junkPick(List.of(junk(Items.GRAVEL, 30), junk(Items.COBBLESTONE, 30)), 64));
        // a stack that may not go does not get a rank say either
        assertEquals(1, GarbageRules.junkPick(List.of(new GarbageRules.Entry(new ItemStack(Items.GRAVEL, 64), false, 0),
                junk(Items.COBBLESTONE, 64), junk(Items.NETHERRACK, 64)), 64));
    }

    @Test
    public void aDuplicateOnTheListKeepsItsFirstSpot() {
        assertEquals(0, (int) GarbageRules.throwRanks(new net.minecraft.world.item.Item[]{Items.GRAVEL, Items.SAND, Items.GRAVEL}).get(Items.GRAVEL));
    }

    // 2 bread and 5 steak is 50 against a floor of 45, the bread has to stay. a string is the pick and more bread comes off the
    // pile: once it is in the bag holds 60 and the old bread is surplus, so that bread would just go straight back out
    @Test
    public void theFoodFloorIsAskedOfTheBagAfterTheSwap() {
        ItemStack bread = new ItemStack(Items.BREAD, 2);
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 5);
        ItemStack string = new ItemStack(Items.STRING, 1);
        List<GarbageRules.Entry> bag = List.of(new GarbageRules.Entry(bread, true, -1), new GarbageRules.Entry(steak, true, -1),
                new GarbageRules.Entry(string, true, -1));
        int units = bag(bread, steak);
        assertEquals(50, units);
        int pick = GarbageRules.nextPick(bag, 64, true, units, 45);
        assertEquals(2, pick);
        GarbageRules.Entry drop = e(Items.BREAD, 2);
        int after = GarbageRules.unitsAfterSwap(units, string, drop.stack());
        assertEquals(60, after);
        assertTrue(GarbageRules.dropIsNextPick(bag, pick, drop, 64, true, after, 45));
        // the bag total as it is said keep it all, and the bread came in only to be thrown
        assertFalse(GarbageRules.dropIsNextPick(bag, pick, drop, 64, true, units, 45));
        // and throwing food takes its units off: the 2 bread pick gives way to 1 bread worth 5
        assertEquals(45, GarbageRules.unitsAfterSwap(50, bread, new ItemStack(Items.BREAD, 1)));
    }

    // a stone sword in the bag, a string is the pick: the wooden sword off the floor would be the worse tool the moment it is in
    @Test
    public void aWorseToolDropComesBackOutEvenWhenThePickIsNotATool() {
        List<GarbageRules.Entry> bag = List.of(e(Items.STONE_SWORD, 1), e(Items.STRING, 3));
        assertTrue(comesBack(bag, e(Items.WOODEN_SWORD, 1)));
    }

    // and the other way round: the wooden sword is the pick, the string that replaces it is the next pick
    @Test
    public void aDropBehindTheToolPickComesBackOutToo() {
        List<GarbageRules.Entry> bag = List.of(e(Items.STONE_SWORD, 1), e(Items.WOODEN_SWORD, 1), e(Items.BONE, 30));
        assertTrue(comesBack(bag, e(Items.STRING, 3)));
    }

    // two single stacks would take turns: throw the string for the feather, then the feather is the smallest
    @Test
    public void twoSingleStacksDoNotTakeTurns() {
        List<GarbageRules.Entry> bag = List.of(e(Items.STRING, 1), e(Items.BONE, 30));
        assertTrue(comesBack(bag, e(Items.FEATHER, 1)));
    }

    @Test
    public void anotherStackOfTheSameBlockComesBackOut() {
        List<GarbageRules.Entry> bag = List.of(junk(Items.NETHERRACK, 64), junk(Items.NETHERRACK, 64), e(Items.STRING, 1));
        assertTrue(comesBack(bag, junk(Items.NETHERRACK, 64)));
    }

    @Test
    public void aDropThatOutranksThePickIsWorthTheRoom() {
        List<GarbageRules.Entry> bag = List.of(e(Items.STRING, 1), e(Items.FEATHER, 5));
        assertFalse(comesBack(bag, e(Items.BONE, 20)));
        // food sits behind plain stuff
        assertFalse(comesBack(bag, e(Items.COOKED_BEEF, 1)));
        // a drop that may not go (important, armor we would wear) is never the pick
        assertFalse(comesBack(bag, new GarbageRules.Entry(new ItemStack(Items.BONE, 1), false, -1)));
        // no pick, no room to make
        assertFalse(GarbageRules.dropIsNextPick(bag, -1, e(Items.FEATHER, 1), 64, true, 0, 0));
    }

    // a bag entry the way StorageHelper builds it once the core protection is in: a core stack under the reserve may not go
    private static GarbageRules.Entry block(net.minecraft.world.item.Item item, int count, int held, int reserve) {
        boolean core = ItemHelper.isCoreBuildBlock(item);
        return new GarbageRules.Entry(new ItemStack(item, count), !GarbageRules.protectsCore(core, held, reserve), 0);
    }

    @Test
    public void coreBlocksUnderTheReserveAreProtectedAndFragmentsAreNot() {
        assertTrue(GarbageRules.protectsCore(true, 64, 64));
        assertTrue(GarbageRules.protectsCore(true, 3, 64));
        // past the reserve the spare cobble can go like it always could
        assertFalse(GarbageRules.protectsCore(true, 65, 64));
        // granite and friends are never protected
        assertFalse(GarbageRules.protectsCore(false, 3, 64));
        assertTrue(ItemHelper.isCoreBuildBlock(Items.COBBLESTONE));
        assertTrue(ItemHelper.isCoreBuildBlock(Items.COBBLED_DEEPSLATE));
        assertTrue(ItemHelper.isCoreBuildBlock(Items.NETHERRACK));
        assertTrue(ItemHelper.isCoreBuildBlock(Items.DIRT));
        assertFalse(ItemHelper.isCoreBuildBlock(Items.GRANITE));
        assertFalse(ItemHelper.isCoreBuildBlock(Items.ANDESITE));
        assertFalse(ItemHelper.isCoreBuildBlock(Items.TUFF));
        assertFalse(ItemHelper.isCoreBuildBlock(Items.STONE));
    }

    @Test
    public void aFullBagOfFragmentsThrowsAFragmentNeverTheCobble() {
        int held = 3 + 1 + 1 + 1 + 2;
        List<GarbageRules.Entry> bag = List.of(
                block(Items.COBBLESTONE, 3, held, 64), block(Items.GRANITE, 1, held, 64), block(Items.ANDESITE, 1, held, 64),
                block(Items.DIORITE, 1, held, 64), block(Items.TUFF, 2, held, 64));
        int pick = GarbageRules.nextPick(bag, 64, true, 0, 0);
        assertTrue(pick > 0);
        // the cobble drop is the cobble we are mining for, it is not the next thing out
        assertFalse(GarbageRules.dropIsNextPick(bag, pick, block(Items.COBBLESTONE, 1, held, 64), 64, true, 0, 0));
    }

    @Test
    public void aSecondIronSwordIsNeverTheWorseToolOrTheLeftover() {
        // two of the same tier tie on durability, so neither one is the worse tool
        assertEquals(-1, GarbageRules.worseTool(List.of(Items.IRON_SWORD, Items.IRON_SWORD)));
        // the kit protects the sword by item (GamerTask), so with both stacks barred nothing is a pick at all
        assertEquals(-1, next(List.of(new GarbageRules.Entry(new ItemStack(Items.IRON_SWORD, 1), false, -1),
                new GarbageRules.Entry(new ItemStack(Items.IRON_SWORD, 1), false, -1))));
        // a worse sword next to it still goes, the spare stays
        assertEquals(2, next(List.of(e(Items.IRON_SWORD, 1), e(Items.IRON_SWORD, 1), e(Items.WOODEN_SWORD, 1))));
    }

    // the cobble is past the reserve so it goes, and the furnace we mined to carry takes its slot. with the cobble gone the dirt
    // alone is under the reserve, and the next pick by "smallest stack" is that one furnace: the pickup said no to our own
    // station. guarded (canDropStack says no for a protected item, so mayGo is false) it is never the pick and the cobble goes
    @Test
    public void aGuardedStationIsNotTheNextPickSoTheJunkMakesRoomForIt() {
        List<GarbageRules.Entry> bag = List.of(junk(Items.COBBLESTONE, 40), junk(Items.DIRT, 30));
        int pick = GarbageRules.nextPick(bag, 64, true, 0, 0);
        assertEquals(0, pick);
        GarbageRules.Entry unguarded = e(Items.FURNACE, 1);
        GarbageRules.Entry guarded = new GarbageRules.Entry(new ItemStack(Items.FURNACE, 1), false, -1);
        assertTrue(GarbageRules.dropIsNextPick(bag, pick, unguarded, 64, true, 0, 0));
        assertFalse(GarbageRules.dropIsNextPick(bag, pick, guarded, 64, true, 0, 0));
        // and the room call that follows: refused before, the cobble goes now
        assertEquals(DropRoom.Kind.NONE, DropRoom.decide(false, pick, true, false, false, List.of()).kind());
        DropRoom.Decision now = DropRoom.decide(false, pick, GarbageRules.dropIsNextPick(bag, pick, guarded, 64, true, 0, 0), false, false, List.of());
        assertEquals(DropRoom.Kind.EVICT, now.kind());
        assertEquals(DropRoom.Source.GARBAGE, now.source());
        assertEquals(pick, now.slot());
    }
}
