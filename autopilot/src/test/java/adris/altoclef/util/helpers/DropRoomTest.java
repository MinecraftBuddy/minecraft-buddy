package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.helpers.DropRoom.Decision;
import adris.altoclef.util.helpers.DropRoom.Kind;
import adris.altoclef.util.helpers.DropRoom.Source;
import adris.altoclef.util.helpers.DropRoom.Stack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

public class DropRoomTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static Stack kit(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, false, false);
    }

    private static Stack rest(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.REST, false, false);
    }

    private static Stack build(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.REST, false, true);
    }

    private static Stack core(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.CORE, false, false);
    }

    private static Stack kept(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, true, false);
    }

    // a bag of kit we keep, one of each, nothing anybody may push out
    private static List<Stack> keepers() {
        List<Stack> bag = new ArrayList<>();
        for (Item item : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.OBSIDIAN, Items.SHIELD, Items.BUCKET}) {
            bag.add(kit(item, 1));
        }
        return bag;
    }

    private static List<Stack> with(Stack... more) {
        List<Stack> bag = keepers();
        bag.addAll(List.of(more));
        return bag;
    }

    private static Decision pearl(List<Stack> bag) {
        return DropRoom.decide(false, -1, false, false, true, bag);
    }

    // ---- the order

    @Test
    public void fitsBeatsEverything() {
        assertSame(Decision.FIT, DropRoom.decide(true, 3, false, true, true, keepers()));
    }

    @Test
    public void theGarbagePickComesFirst() {
        Decision d = DropRoom.decide(false, 2, false, true, true, with(rest(Items.STRING, 1), rest(Items.STRING, 3)));
        assertEquals(new Decision(Kind.EVICT, Source.GARBAGE, 2), d);
    }

    @Test
    public void aDropThatWouldBeTheNextGarbageIsANo() {
        assertSame(Decision.NO, DropRoom.decide(false, 2, true, false, false, keepers()));
    }

    @Test
    public void wearableArmorStillGetsTheLastResort() {
        assertEquals(new Decision(Kind.EVICT, Source.WEAR, -1), DropRoom.decide(false, -1, false, true, false, keepers()));
    }

    @Test
    public void breadWithAFullBagOfKeepersIsANo() {
        // kit never evicts, even with a perfectly good second string stack around
        assertSame(Decision.NO, DropRoom.decide(false, -1, false, false, false, with(rest(Items.STRING, 1), rest(Items.STRING, 3))));
    }

    // ---- the victim pick

    @Test
    public void pearlPushesOutATierThreeDuplicate() {
        List<Stack> bag = with(rest(Items.STRING, 9), rest(Items.STRING, 4));
        assertEquals(new Decision(Kind.EVICT, Source.VICTIM, 7), pearl(bag));
    }

    @Test
    public void pearlPushesOutALoneTierThreeStack() {
        List<Stack> bag = with(rest(Items.STRING, 9));
        assertEquals(new Decision(Kind.EVICT, Source.VICTIM, bag.size() - 1), pearl(bag));
    }

    @Test
    public void pearlPushesOutADuplicateStonePickaxe() {
        List<Stack> bag = with(kit(Items.STONE_PICKAXE, 1), kit(Items.STONE_PICKAXE, 1));
        assertEquals(6, pearl(bag).slot());
    }

    @Test
    public void pearlPushesOutAWorseToolWeHoldBetter() {
        List<Stack> bag = with(kit(Items.IRON_PICKAXE, 1), kit(Items.WOODEN_PICKAXE, 1));
        assertEquals(7, pearl(bag).slot());
    }

    @Test
    public void surplusBeatsABuildStackBeatsNothing() {
        // two build stacks, the smaller one goes
        assertEquals(7, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20))).slot());
        // one build stack is the bridge, not a victim
        assertSame(Decision.NO, pearl(with(build(Items.NETHERRACK, 64))));
        // a spare furnace is surplus, so it goes before a build stack
        assertEquals(8, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20), kit(Items.FURNACE, 1), kit(Items.FURNACE, 1))).slot());
        // the only furnace isn't spare, the build stack goes instead
        assertEquals(7, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20), kit(Items.FURNACE, 1))).slot());
    }

    @Test
    public void woolPastABedsWorth() {
        // 7 wool: the red two can go and leave five, the white five can't (two left)
        assertEquals(7, pearl(with(kit(Items.WOOL.white(), 5), kit(Items.WOOL.red(), 2))).slot());
        assertSame(Decision.NO, pearl(with(kit(Items.WOOL.white(), 4))));
        // two stacks are not a spare when together they're one bed
        assertSame(Decision.NO, pearl(with(kit(Items.WOOL.white(), 1), kit(Items.WOOL.white(), 2))));
    }

    @Test
    public void aSpareCraftingTableButNeverTheLast() {
        assertEquals(6, pearl(with(kit(Items.CRAFTING_TABLE, 1), kit(Items.CRAFTING_TABLE, 1))).slot());
        assertSame(Decision.NO, pearl(with(kit(Items.CRAFTING_TABLE, 1))));
    }

    @Test
    public void neverTheseEvenAsDuplicates() {
        assertSame(Decision.NO, pearl(with(core(Items.ENDER_PEARL, 3), core(Items.ENDER_PEARL, 16))));
        assertSame(Decision.NO, pearl(with(kit(Items.WATER_BUCKET, 1), kit(Items.WATER_BUCKET, 1))));
        assertSame(Decision.NO, pearl(with(kit(Items.GOLDEN_APPLE, 1), kit(Items.GOLDEN_APPLE, 2))));
        assertSame(Decision.NO, pearl(with(kit(Items.POTION, 1), kit(Items.POTION, 1))));
        // nuggets are for bartering, a second stack is not spare
        assertSame(Decision.NO, pearl(with(kit(Items.GOLD_NUGGET, 30), kit(Items.GOLD_NUGGET, 64))));
        // armor we wear or would, enchanted, named, important, food under the floor: the world says keep
        assertSame(Decision.NO, pearl(with(kept(Items.IRON_HELMET, 1), kept(Items.IRON_HELMET, 1))));
        // the last pickaxe, the last flint and steel
        assertSame(Decision.NO, pearl(with(kit(Items.STONE_PICKAXE, 1))));
        assertSame(Decision.NO, pearl(with(kit(Items.FLINT_AND_STEEL, 1))));
    }

    @Test
    public void tableNetherrackPotionAndKeepersIsANo() {
        List<Stack> bag = with(kit(Items.CRAFTING_TABLE, 1), build(Items.NETHERRACK, 64), kit(Items.POTION, 1));
        assertSame(Decision.NO, pearl(bag));
    }

    @Test
    public void emptySlotsAreNobody() {
        List<Stack> bag = with(rest(Items.STRING, 2), new Stack(ItemStack.EMPTY, ItemValue.REST, false, false), rest(Items.STRING, 5));
        assertEquals(6, pearl(bag).slot());
    }

    private static List<ItemStack> stacks(ItemStack... all) {
        return new ArrayList<>(List.of(all));
    }

    @Test
    public void theEndsBedsAreSpokenForAndTheSpareIsNot() {
        List<ItemStack> bag = stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.BED.white()), new ItemStack(Items.BED.white()), ItemStack.EMPTY);
        boolean[] kept = DropRoom.reserved(bag, 2);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
        assertEquals(false, kept[2]);
        assertEquals(false, kept[3]);
    }

    @Test
    public void aPearlCannotPushAReserveBedOut() {
        // two white beds look like a duplicate, that's the surplus rule's whole idea of a spare
        List<Stack> bag = keepers();
        bag.add(kit(Items.BED.white(), 1));
        bag.add(kit(Items.BED.white(), 1));
        assertEquals(bag.size() - 2, DropRoom.victim(bag));
        List<ItemStack> raw = new ArrayList<>();
        for (Stack st : bag) {
            raw.add(st.stack());
        }
        boolean[] kept = DropRoom.reserved(raw, 8);
        List<Stack> guarded = new ArrayList<>();
        for (int i = 0; i < bag.size(); i++) {
            Stack st = bag.get(i);
            guarded.add(new Stack(st.stack(), st.tier(), kept[i], st.build()));
        }
        assertEquals(-1, DropRoom.victim(guarded));
    }

    @Test
    public void theWoolThatStillHasToBecomeBedsIsSpokenFor() {
        // 1 bed of 3 reserved, so 6 wool to go. the scraps of another colour can't make a bed
        List<ItemStack> bag = stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6), new ItemStack(Items.WOOL.blue(), 2));
        boolean[] kept = DropRoom.reserved(bag, 3);
        assertEquals(true, kept[1]);
        assertEquals(false, kept[2]);
        // beds covered: the wool is just wool
        assertEquals(false, DropRoom.reserved(stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6)), 1)[1]);
    }

    @Test
    public void scrapsDontPayForAnotherColoursWool() {
        // 9 wool owed: 5 red and 5 blue are 3 usable each, so the white three are still needed
        List<ItemStack> bag = stacks(new ItemStack(Items.WOOL.red(), 5), new ItemStack(Items.WOOL.blue(), 5), new ItemStack(Items.WOOL.white(), 3));
        boolean[] kept = DropRoom.reserved(bag, 3);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
        assertEquals(true, kept[2]);
    }

    @Test
    public void aSetOfThreeSplitOverTwoStacksStaysWhole() {
        List<ItemStack> bag = stacks(new ItemStack(Items.WOOL.red(), 2), new ItemStack(Items.WOOL.red(), 2));
        boolean[] kept = DropRoom.reserved(bag, 1);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
    }

    @Test
    public void noRunNothingIsSpokenFor() {
        boolean[] kept = DropRoom.reserved(stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6)), 0);
        assertEquals(false, kept[0]);
        assertEquals(false, kept[1]);
    }
}
