package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ToolPick.Option;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the block hardnesses are the game's own; the tool speeds are the tiers' (hardcoded above). what is not available outside a world is item
// tags (so "is this pickaxe the right one for obsidian" is told to the test by hand: diamond and netherite yes, the rest no)
public class BreakRulesTest {
    // mining speeds of the tool tiers
    private static final float IRON = 6;
    private static final float DIAMOND = 8;
    private static final float STONE_TIER = 4;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static float hardness(Block b) {
        return b.defaultBlockState().getDestroySpeed(null, null);
    }

    // what ToolSet.calculateSpeedVsBlock would say
    private static double progress(Block b, double toolSpeed, boolean fast) {
        return toolSpeed / hardness(b) / (fast ? 30 : 100);
    }

    private static double hand(Block b) {
        return progress(b, 1, false);
    }

    private static Option pick(Block b, double speed, boolean correct) {
        return new Option(true, correct, false, progress(b, speed, correct));
    }

    private static double seconds(double ticks) {
        return ticks / 20.0;
    }

    @Test
    public void theCapIsTwentySeconds() {
        assertEquals(400, BreakRules.MAX_TICKS);
        assertFalse(BreakRules.tooSlow(400));
        assertTrue(BreakRules.tooSlow(401));
    }

    @Test
    public void obsidianUnderAnIronPickIsNotWorthIt() {
        double ticks = BreakRules.bestTicks(List.of(pick(Blocks.OBSIDIAN, IRON, false)), hand(Blocks.OBSIDIAN));
        assertEquals(41.7, seconds(ticks), 0.1);
        assertTrue(BreakRules.tooSlow(ticks));
    }

    @Test
    public void obsidianUnderADiamondPickIsFine() {
        double ticks = BreakRules.bestTicks(List.of(pick(Blocks.OBSIDIAN, IRON, false), pick(Blocks.OBSIDIAN, DIAMOND, true)), hand(Blocks.OBSIDIAN));
        assertEquals(9.4, seconds(ticks), 0.05);
        assertFalse(BreakRules.tooSlow(ticks));
    }

    @Test
    public void obsidianWithABucketIsTwoHundredAndFiftySeconds() {
        double ticks = BreakRules.bestTicks(List.of(new Option(false, false, false, 0)), hand(Blocks.OBSIDIAN));
        assertEquals(250, seconds(ticks), 0.1);
        assertTrue(BreakRules.tooSlow(ticks));
    }

    @Test
    public void stoneByHandIsSevenAndAHalfSeconds() {
        double ticks = BreakRules.bestTicks(List.of(), hand(Blocks.STONE));
        assertEquals(7.5, seconds(ticks), 0.01);
        assertFalse(BreakRules.tooSlow(ticks));
    }

    @Test
    public void deepslateByHandIsFifteenSeconds() {
        double ticks = BreakRules.bestTicks(List.of(), hand(Blocks.DEEPSLATE));
        assertEquals(15, seconds(ticks), 0.01);
        assertFalse(BreakRules.tooSlow(ticks));
    }

    @Test
    public void aLogByHandIsQuickBecauseItNeedsNoToolForDrops() {
        // wood is not "requires the right tool", so it is the /30 rate even with an empty hand
        double ticks = BreakRules.bestTicks(List.of(), progress(Blocks.OAK_LOG, 1, true));
        assertEquals(3.0, seconds(ticks), 0.01);
    }

    @Test
    public void aStonePickOnObsidianIsAlsoNo() {
        double ticks = BreakRules.bestTicks(List.of(pick(Blocks.OBSIDIAN, STONE_TIER, false)), hand(Blocks.OBSIDIAN));
        assertTrue(BreakRules.tooSlow(ticks));
    }

    @Test
    public void thereIsNothingToDoWithoutAnyProgress() {
        // ToolSet says -1 for an unbreakable block, and 0 is a tool that makes none
        assertEquals(Double.POSITIVE_INFINITY, BreakRules.ticks(-1), 0);
        assertEquals(Double.POSITIVE_INFINITY, BreakRules.ticks(0), 0);
    }

    @Test
    public void aBlockThatBreaksInstantlyIsOneTick() {
        assertEquals(1, BreakRules.ticks(Double.POSITIVE_INFINITY), 0);
        assertEquals(1, BreakRules.ticks(5), 0);
        assertEquals(1, BreakRules.ticks(1), 0);
    }

    // the real thing, through ItemStacks. with no tags bound a pickaxe is the bare hand for every block here, so these are
    // the answers that do not depend on the tool being right: the bag cannot make anything faster than the hand
    @Test
    public void theRealBagMatchesTheHandWhenTagsAreNotAround() {
        List<ItemStack> bucket = List.of(new ItemStack(Items.WATER_BUCKET));
        assertTrue(BreakRules.tooSlow(bucket, Blocks.OBSIDIAN.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(bucket, Blocks.STONE.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(bucket, Blocks.DEEPSLATE.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(List.of(), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    public void bedrockAndLiquidsKeepSayingWhatTheyAlwaysSaid() {
        List<ItemStack> bag = List.of();
        assertFalse(BreakRules.tooSlow(bag, Blocks.BEDROCK.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(bag, Blocks.LAVA.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(bag, Blocks.WATER.defaultBlockState()));
        assertFalse(BreakRules.tooSlow(bag, Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void theQuickOutIsBelowTheHardestBlockTheHandCanDoInTime() {
        // hardness 4 by hand on a "needs the tool" block is exactly 400 ticks: the cap itself is allowed
        assertFalse(BreakRules.tooSlow(BreakRules.ticks(1.0 / 4 / 100)));
        assertTrue(BreakRules.tooSlow(BreakRules.ticks(1.0 / 4.5 / 100)));
    }

    @Test
    public void theLogLineHasTheTimeAndTheTool() {
        List<ItemStack> bag = List.of(new ItemStack(Items.WATER_BUCKET));
        assertEquals("250.0s with bare hand", BreakRules.describe(bag, Blocks.OBSIDIAN.defaultBlockState()));
    }
}
