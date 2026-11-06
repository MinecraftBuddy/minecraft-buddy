package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ToolPick.Option;
import baritone.Baritone;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// item tags are not bound outside a world, so a real pickaxe would read as "wrong for everything" here. the options are
// built by hand with the numbers vanilla gives (tool speed / hardness / 30 when it is the right tool, / 100 when not)
public class ToolPickTest {
    private static final double OBSIDIAN = 50;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static double progress(double hardness, double toolSpeed, boolean correct) {
        return toolSpeed / hardness / (correct ? 30 : 100);
    }

    private static final double HAND = progress(OBSIDIAN, 1, false);

    private static Option tool(double toolSpeed, boolean correct) {
        return new Option(true, correct, false, progress(OBSIDIAN, toolSpeed, correct));
    }

    private static final Option BUCKET = new Option(false, false, false, 0);

    // the old rule, chain off and no swords: fastest right tool, else the fastest wrong one
    private static int fastest(List<Option> bag, double hand) {
        return ToolPick.best(bag, hand, false, false);
    }

    // the chain on, swords off, which is how the defaults ship
    private static int chain(List<Option> bag) {
        return ToolPick.best(bag, HAND, true, false);
    }

    // a right tool for the block with the numbers of its material: max damage and what is left of it
    private static Option right(double speed, int max, int left) {
        return new Option(true, true, false, progress(OBSIDIAN, speed, true), false, false, max, left);
    }

    private static Option soft(double speed, int max) {
        return new Option(true, true, false, progress(OBSIDIAN, speed, true), false, true, max, max);
    }

    private static Option sword(boolean correct) {
        return new Option(true, correct, false, progress(OBSIDIAN, 1.5, correct), true, false, 1561, 1561);
    }

    private static final int STONE_MAX = 131;
    private static final int IRON_MAX = 250;
    private static final int DIAMOND_MAX = 1561;

    @Test
    public void ironPickBeatsABucketOnObsidian() {
        // nothing below diamond is "correct" for obsidian, and this used to answer nothing at all
        List<Option> bag = List.of(BUCKET, tool(6, false), BUCKET);
        assertEquals(1, fastest(bag, HAND));
    }

    @Test
    public void noToolsAtAllIsStillNothing() {
        assertEquals(-1, fastest(List.of(BUCKET, BUCKET, BUCKET), HAND));
        assertEquals(-1, fastest(List.of(), HAND));
    }

    @Test
    public void aShovelIsNoBetterThanAHandOnObsidianSoThePickWins() {
        // a shovel does nothing to obsidian: speed 1, the same as the hand
        Option shovel = tool(1, false);
        assertEquals(1, fastest(List.of(shovel, tool(4, false)), HAND));
        // and the shovel alone is not worth a swap
        assertEquals(-1, fastest(List.of(shovel), HAND));
    }

    @Test
    public void theFastestWrongToolWins() {
        List<Option> bag = List.of(tool(2, false), tool(6, false), tool(4, false));
        assertEquals(1, fastest(bag, HAND));
    }

    @Test
    public void twoEquallyFastWrongToolsKeepTheFirst() {
        // an iron axe and an iron pick are the same speed on obsidian, the pick must not trade places with the axe
        assertEquals(0, fastest(List.of(tool(6, false), tool(6, false)), HAND));
    }

    @Test
    public void aCorrectToolStillWinsExactlyLikeBefore() {
        List<Option> bag = List.of(tool(6, true), tool(8, true), BUCKET);
        assertEquals(1, fastest(bag, HAND));
    }

    @Test
    public void aCorrectToolBeatsAFasterWrongOne() {
        // the right tool keeps the drops, the wrong one is only ever the fallback
        List<Option> bag = List.of(tool(9, false), tool(2, true));
        assertEquals(1, fastest(bag, HAND));
    }

    @Test
    public void shearsJumpTheQueueOnLeaves() {
        Option shears = new Option(false, false, true, 0);
        assertEquals(1, fastest(List.of(tool(6, true), shears, tool(8, true)), HAND));
    }

    @Test
    public void shearsOnABlockTheyDoNotFitAreJustAnItem() {
        Option shears = new Option(false, false, false, 0);
        assertEquals(-1, fastest(List.of(shears), HAND));
    }

    @Test
    public void aBlockThatCannotBeBrokenPicksNothing() {
        // calculateSpeedVsBlock says -1 for everything, the hand included
        Option nothing = new Option(true, false, false, -1);
        assertEquals(-1, fastest(List.of(nothing), -1));
    }

    // the table: stone / iron ore / diamond ore / obsidian as the bag the block lets through. diamond ore takes iron and up,
    // obsidian takes diamond, wood is never right for either of them

    @Test
    public void stoneGoesToTheWornStoneBeforeAnythingElse() {
        // wood 59, stone with 100 left, stone with 20 left, iron, diamond
        List<Option> bag = List.of(soft(2, 59), right(4, STONE_MAX, 100), right(4, STONE_MAX, 20), right(6, IRON_MAX, 250), right(8, DIAMOND_MAX, 1561));
        assertEquals(2, chain(bag));
    }

    @Test
    public void aToolNoFasterThanTheHandIsNotWorthTheDurability() {
        assertTrue(ToolPick.noBetterThanHand(true, HAND, HAND));
        assertFalse(ToolPick.noBetterThanHand(true, HAND * 2, HAND));
        // a stack of cobble or an empty slot is free to hold
        assertFalse(ToolPick.noBetterThanHand(false, HAND, HAND));
    }

    @Test
    public void swordsDoNotMineAndTheChainIsOnByDefault() {
        assertFalse(Baritone.settings().useSwordToMine.value);
        assertTrue(Baritone.settings().toolWearChain.value);
    }

    @Test
    public void ironOreStillTakesTheStoneFirst() {
        // stone is right for iron ore too, so it still goes first
        List<Option> bag = List.of(right(6, IRON_MAX, 250), right(4, STONE_MAX, 100), right(8, DIAMOND_MAX, 1561));
        assertEquals(1, chain(bag));
    }

    @Test
    public void diamondOreTakesIronNotDiamond() {
        // stone and wood cannot break it, so they are not right and not options at all
        Option stone = new Option(true, false, false, progress(OBSIDIAN, 4, false));
        List<Option> bag = List.of(stone, right(8, DIAMOND_MAX, 1561), right(6, IRON_MAX, 200));
        assertEquals(2, chain(bag));
    }

    @Test
    public void obsidianTakesTheOnlyPickThatCanBreakIt() {
        Option iron = new Option(true, false, false, progress(OBSIDIAN, 6, false));
        assertEquals(1, chain(List.of(iron, right(8, DIAMOND_MAX, 1561))));
    }

    @Test
    public void woodAndGoldAreLastEvenWhenTheyAreNearlyDead() {
        // gold has 32 uses to its name, still behind a fresh iron one
        List<Option> bag = List.of(soft(12, 32), soft(2, 59), right(6, IRON_MAX, 250));
        assertEquals(2, chain(bag));
        // and with nothing else right for the block they are used, wood only when gold is gone
        assertEquals(0, chain(List.of(soft(12, 32), soft(2, 59))));
        assertEquals(1, chain(List.of(BUCKET, soft(2, 59))));
    }

    @Test
    public void sameMaterialWornFirst() {
        assertEquals(1, chain(List.of(right(6, IRON_MAX, 200), right(6, IRON_MAX, 3), right(6, IRON_MAX, 250))));
    }

    @Test
    public void nothingRightFallsBackToTheFasterThanHandRule() {
        // dirt with only an iron pick and a bucket: the pick is no faster than the hand, so nobody is swung
        Option wrongPick = new Option(true, false, false, HAND, false, false, IRON_MAX, IRON_MAX);
        assertEquals(-1, chain(List.of(BUCKET, wrongPick)));
        Option fasterWrong = new Option(true, false, false, HAND * 6, false, false, IRON_MAX, IRON_MAX);
        assertEquals(1, chain(List.of(BUCKET, fasterWrong)));
    }

    @Test
    public void chainOffIsTheOldFastestRight() {
        List<Option> bag = List.of(right(4, STONE_MAX, 100), right(8, DIAMOND_MAX, 1561));
        assertEquals(1, fastest(bag, HAND));
    }

    @Test
    public void swordsNeverMineUnlessAskedAndNeverJoinTheChain() {
        // a cobweb: only the sword is right
        assertEquals(-1, ToolPick.best(List.of(sword(true)), HAND, true, false));
        assertEquals(0, ToolPick.best(List.of(sword(true)), HAND, true, true));
        // a sword that is right does not beat a right pick in the chain, it is the fallback
        assertEquals(1, ToolPick.best(List.of(sword(true), right(4, STONE_MAX, 100)), HAND, true, true));
        // and a sword that is wrong but faster than the hand is not swung when swords are off
        assertEquals(-1, ToolPick.best(List.of(sword(false)), HAND, false, false));
        assertEquals(0, ToolPick.best(List.of(sword(false)), HAND, false, true));
    }

    @Test
    public void shearsStillJumpTheQueueWithTheChainOn() {
        Option shears = new Option(false, false, true, 0);
        assertEquals(1, chain(List.of(right(6, IRON_MAX, 250), shears)));
    }

    @Test
    public void swapDownOnlyForTheToolThatShouldDieFirst() {
        Option stone = right(4, STONE_MAX, 100);
        Option iron = right(6, IRON_MAX, 250);
        assertEquals(true, ToolSwap.worthSwappingDown("iron", iron, "stone", stone));
        assertEquals(false, ToolSwap.worthSwappingDown("stone", stone, "iron", iron));
        // the same item never trades places with itself
        assertEquals(false, ToolSwap.worthSwappingDown("stone", stone, "stone", right(4, STONE_MAX, 10)));
        // a hand that is wrong for the block is the normal swap's business
        assertEquals(false, ToolSwap.worthSwappingDown("bucket", BUCKET, "stone", stone));
    }

    @Test
    public void namesForTheLog() {
        assertEquals("iron_pickaxe", ToolPick.nameOf(new ItemStack(Items.IRON_PICKAXE)));
        assertEquals("bare hand", ToolPick.nameOf(ItemStack.EMPTY));
    }
}
