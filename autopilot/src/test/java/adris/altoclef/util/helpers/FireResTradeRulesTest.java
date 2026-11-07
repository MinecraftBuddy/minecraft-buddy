package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.FireResTradeRules.Form;
import adris.altoclef.util.helpers.FireResTradeRules.Step;
import adris.altoclef.util.helpers.FireResTradeRules.Verdict;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FireResTradeRulesTest {
    private static final long SEARCH = 120 * 20;
    private static final long MAX = 600 * 20;
    private static final long NEVER = Long.MAX_VALUE;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // the barter's two (the drinkable and the splash), and every form a fire resistance potion comes in
    @Test
    public void everyFireResistancePotionCountsByItsForm() {
        assertEquals(Form.DRINK, FireResTradeRules.form(PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE)));
        assertEquals(Form.DRINK, FireResTradeRules.form(PotionContents.createItemStack(Items.POTION, Potions.LONG_FIRE_RESISTANCE)));
        assertEquals(Form.THROW, FireResTradeRules.form(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.FIRE_RESISTANCE)));
        assertEquals(Form.THROW, FireResTradeRules.form(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.LONG_FIRE_RESISTANCE)));
        assertEquals(Form.THROW, FireResTradeRules.form(PotionContents.createItemStack(Items.LINGERING_POTION, Potions.FIRE_RESISTANCE)));
    }

    // a piglin's water bottle, the kit's water bottles and the brew's awkward potion are not it
    @Test
    public void otherPotionsAndItemsAreNot() {
        assertEquals(Form.NONE, FireResTradeRules.form(PotionContents.createItemStack(Items.POTION, Potions.WATER)));
        assertEquals(Form.NONE, FireResTradeRules.form(PotionContents.createItemStack(Items.POTION, Potions.AWKWARD)));
        assertEquals(Form.NONE, FireResTradeRules.form(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER)));
        assertEquals(Form.NONE, FireResTradeRules.form(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)));
        assertEquals(Form.NONE, FireResTradeRules.form(new ItemStack(Items.POTION)));
        assertEquals(Form.NONE, FireResTradeRules.form(new ItemStack(Items.GOLD_INGOT)));
        assertEquals(Form.NONE, FireResTradeRules.form(ItemStack.EMPTY));
        assertEquals(Form.NONE, FireResTradeRules.form(null));
    }

    @Test
    public void beginsWithGoldNoPotionAndFewerThanTwoRods() {
        assertNull(FireResTradeRules.whyNot(96, false, 0));
        assertNull(FireResTradeRules.whyNot(1, false, 1));
        assertEquals("no gold", FireResTradeRules.whyNot(0, false, 0));
        assertEquals("2 rods already", FireResTradeRules.whyNot(96, false, 2));
        assertEquals("a fire resistance potion already in the bag", FireResTradeRules.whyNot(96, true, 0));
    }

    private static Verdict decide(boolean potion, int gold, boolean pending, long sinceBegin, long sinceGoldOut, long sincePiglin,
                                  boolean piglinNear, boolean atFortress, boolean blazeNear) {
        return FireResTradeRules.decide(potion, gold, pending, true, sinceBegin, sinceGoldOut, sincePiglin, piglinNear, atFortress,
                blazeNear, SEARCH, MAX);
    }

    @Test
    public void aPotionInTheBagEndsItWhateverElse() {
        assertEquals(Step.DONE, decide(true, 0, false, MAX + 1, NEVER, SEARCH + 1, false, true, true).step());
        assertEquals(Step.DONE, decide(true, 50, true, 100, NEVER, 0, true, false, false).step());
    }

    @Test
    public void theCapEndsItEvenMidTrade() {
        Verdict v = decide(false, 40, true, MAX, NEVER, 0, true, false, false);
        assertEquals(Step.GIVE_UP, v.step());
        assertEquals("the cap of 600 s passed", v.reason());
        assertEquals(Step.TRADE, decide(false, 40, true, MAX - 1, NEVER, 0, true, false, false).step());
    }

    @Test
    public void noGoldToWearGivesUp() {
        Verdict v = FireResTradeRules.decide(false, 40, false, false, 0, NEVER, 0, true, false, false, SEARCH, MAX);
        assertEquals(Step.GIVE_UP, v.step());
        assertEquals("no gold armour to wear, the piglins would attack", v.reason());
    }

    // the last ingot is still admired and its loot still to come: that wait is short, then out of gold
    @Test
    public void outOfGoldWaitsForTheLastThrowOnly() {
        Verdict none = decide(false, 0, false, 2000, 0, 0, true, false, false);
        assertEquals(Step.GIVE_UP, none.step());
        assertEquals("out of gold", none.reason());
        assertEquals(Step.TRADE, decide(false, 0, true, 2000, FireResTradeRules.LAST_THROW_TICKS - 1, 0, true, false, false).step());
        assertEquals(Step.GIVE_UP, decide(false, 0, true, 2000, FireResTradeRules.LAST_THROW_TICKS, 0, true, false, false).step());
    }

    @Test
    public void aBlazeNearHandsTheTickToTheFight() {
        assertEquals(Step.YIELD, decide(false, 40, true, 100, NEVER, 0, true, false, true).step());
        assertEquals(Step.YIELD, decide(false, 40, false, 100, NEVER, 0, false, true, true).step());
    }

    @Test
    public void aPiglinInSightIsTradedWith() {
        assertEquals(Step.TRADE, decide(false, 40, false, 100, NEVER, 0, true, false, false).step());
        // by the spawner too, while one is in sight
        assertEquals(Step.TRADE, decide(false, 40, false, 100, NEVER, 0, true, true, false).step());
        // an ingot being admired keeps the trade on with no free piglin
        assertEquals(Step.TRADE, decide(false, 40, true, 100, NEVER, 500, false, false, false).step());
    }

    @Test
    public void noPiglinForTheSearchTimeGivesUp() {
        assertEquals(Step.SEARCH, decide(false, 40, false, SEARCH, NEVER, SEARCH - 1, false, false, false).step());
        Verdict v = decide(false, 40, false, SEARCH, NEVER, SEARCH, false, false, false);
        assertEquals(Step.GIVE_UP, v.step());
        assertEquals("no piglin found in 120 s", v.reason());
    }

    // the trade is for before the fortress: there with none in sight, the rods go as before
    @Test
    public void atTheFortressWithNoPiglinGivesUp() {
        Verdict v = decide(false, 40, false, 300, NEVER, 300, false, true, false);
        assertEquals(Step.GIVE_UP, v.step());
        assertEquals("at the fortress with no piglin in sight", v.reason());
    }

    @Test
    public void aPiglinIsTakenUpWithinEnterAndKeptWithinKeep() {
        assertTrue(FireResTradeRules.piglinNear(false, FireResTradeRules.ENTER_RANGE));
        assertFalse(FireResTradeRules.piglinNear(false, FireResTradeRules.ENTER_RANGE + 1));
        assertTrue(FireResTradeRules.piglinNear(true, FireResTradeRules.ENTER_RANGE + 1));
        assertFalse(FireResTradeRules.piglinNear(true, FireResTradeRules.KEEP_RANGE + 1));
        assertFalse(FireResTradeRules.piglinNear(true, Double.POSITIVE_INFINITY));
    }

    private static boolean held(boolean traded, boolean blazeNear, boolean atFortress, boolean asked, boolean inLava, boolean onFire,
                                double health) {
        return FireResTradeRules.held(traded, blazeNear, atFortress, asked, inLava, onFire, health, FireResTradeRules.HOLD_HP);
    }

    // on the way, merely on fire with health to spare: the traded potion waits for the blazes
    @Test
    public void merelyOnFireOnTheWayThePotionWaits() {
        assertTrue(held(true, false, false, false, false, true, 15));
        assertTrue(held(true, false, false, false, false, true, 8.5));
    }

    // on fire at the health line or below, or in lava: it goes down
    @Test
    public void lowOnHealthOrInLavaItGoesDown() {
        assertFalse(held(true, false, false, false, false, true, 8));
        assertFalse(held(true, false, false, false, false, true, 3));
        assertFalse(held(true, false, false, false, true, true, 20));
        assertFalse(held(true, false, false, false, true, false, 20));
        assertFalse(FireResTradeRules.held(true, false, false, false, false, true, 11, 12));
        assertTrue(FireResTradeRules.held(true, false, false, false, false, true, 11, 4));
    }

    // a blaze in range, the fortress, a task asking, or no traded potion: as before
    @Test
    public void atTheBlazesOrUntradedNothingWaits() {
        assertFalse(held(true, true, false, false, false, true, 20));
        assertFalse(held(true, false, true, false, false, true, 20));
        assertFalse(held(true, false, false, true, false, true, 20));
        assertFalse(held(false, false, false, false, false, true, 20));
    }

    @Test
    public void theTradesSayCountsForAMinute() {
        assertTrue(FireResTradeRules.tradedFresh(1000, 1000));
        assertTrue(FireResTradeRules.tradedFresh(1000, 1000 + FireResTradeRules.HOLD_FRESH));
        assertFalse(FireResTradeRules.tradedFresh(1000, 1001 + FireResTradeRules.HOLD_FRESH));
        assertFalse(FireResTradeRules.tradedFresh(1000, 999));
        assertFalse(FireResTradeRules.tradedFresh(Long.MIN_VALUE / 2, 0));
    }

    // one line per fire episode: a hold right after the last one is the same episode
    @Test
    public void aHoldAfterAGapIsANewEpisode() {
        assertFalse(FireResTradeRules.newEpisode(1000, 1001));
        assertFalse(FireResTradeRules.newEpisode(1000, 1000 + FireResTradeRules.HOLD_EPISODE_GAP));
        assertTrue(FireResTradeRules.newEpisode(1000, 1001 + FireResTradeRules.HOLD_EPISODE_GAP));
        assertTrue(FireResTradeRules.newEpisode(1000, 999));
        assertTrue(FireResTradeRules.newEpisode(Long.MIN_VALUE / 2, 0));
    }

    @Test
    public void theNearestBlazeAsTheLinesWriteIt() {
        assertEquals("none in sight", FireResTradeRules.distance(Double.POSITIVE_INFINITY));
        assertEquals("23 blocks off", FireResTradeRules.distance(22.6));
    }

    // 16 in 469 per ingot: about one potion in 29 ingots, and the kit's 96 gets one 96 times in a hundred
    @Test
    public void theOddsOfAPotionByTheNthIngot() {
        assertEquals(0, FireResTradeRules.chanceWithin(0), 1e-9);
        assertEquals(16.0 / 469, FireResTradeRules.chanceWithin(1), 1e-9);
        assertEquals(0.63, FireResTradeRules.chanceWithin(29), 0.01);
        assertEquals(0.89, FireResTradeRules.chanceWithin(64), 0.01);
        assertEquals(0.96, FireResTradeRules.chanceWithin(96), 0.01);
        assertEquals(0.99, FireResTradeRules.chanceWithin(128), 0.01);
    }
}
