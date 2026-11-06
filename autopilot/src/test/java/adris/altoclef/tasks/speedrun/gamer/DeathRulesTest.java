package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DeathRules.Source;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

// who gets to say a new player instance was a death. the three witnesses are plain booleans here
public class DeathRulesTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void ourOwnTickSawIt() {
        assertEquals(Source.TICK, DeathRules.source(true, false, false));
        assertTrue(DeathRules.seen(true, false, false));
    }

    @Test
    public void onlyTheStashSawIt() {
        // mob defense held the wheel through the fight, the death and the death screen: no tick, no zero health on a body
        // we ever looked at, just the packet
        assertEquals(Source.STASH, DeathRules.source(false, true, false));
        assertTrue(DeathRules.seen(false, true, false));
    }

    @Test
    public void onlyTheOldInstanceKnows() {
        assertEquals(Source.OLD_INSTANCE, DeathRules.source(false, false, true));
        assertTrue(DeathRules.seen(false, false, true));
    }

    @Test
    public void aPortalIsNotADeath() {
        // new instance, old one alive, nothing in the stash
        assertEquals(Source.NONE, DeathRules.source(false, false, false));
        assertFalse(DeathRules.seen(false, false, false));
    }

    @Test
    public void theStashBeatsTheTickWhenBothHaveARecord() {
        // the packet is the earlier sample, the tick can have seen the body after it slid
        assertEquals(Source.STASH, DeathRules.source(true, true, true));
        assertEquals(Source.STASH, DeathRules.source(true, true, false));
    }

    @Test
    public void theTickBeatsTheOldInstance() {
        assertEquals(Source.TICK, DeathRules.source(true, false, true));
    }

    @Test
    public void everyWitnessCountsOnItsOwn() {
        for (int bits = 1; bits < 8; bits++) {
            assertTrue("bits " + bits, DeathRules.seen((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0));
        }
        assertFalse(DeathRules.seen(false, false, false));
    }

    // ---- is the pile worth the walk

    private static final int FOOD_LINE = 45;

    private static DeathRules.Worth rich() {
        return new DeathRules.Worth(30, 1, true, 100, false);
    }

    private static DeathRules.Worth bag(int iron, int gear, boolean pick, int food, boolean run) {
        return new DeathRules.Worth(iron, gear, pick, food, run);
    }

    private static String why(double blocks, DeathRules.Worth worth, int killedBefore) {
        return DeathRules.refuse(true, blocks, 1000, worth, FOOD_LINE, killedBefore, false, false);
    }

    @Test
    public void aNearRichPileIsWorthTheWalk() {
        assertNull(why(80, rich(), 0));
    }

    @Test
    public void aFarRichPileWithinReachIsStillWorthIt() {
        assertNull(why(450, rich(), 0));
        assertNull(why(1000, rich(), 0));
    }

    @Test
    public void pastTheReachItIsLeftAndSaysSo() {
        assertEquals("1001 blocks is further than the 1000 we walk", why(1001, rich(), 0));
    }

    @Test
    public void aPoorPileIsLeftAtAnyDistance() {
        assertEquals("the bag was nearly empty (6 food)", why(40, bag(0, 0, false, 6, false), 0));
        assertEquals("the bag was nearly empty (nothing much)", why(600, bag(0, 0, false, 0, false), 0));
        // two iron is a smelt away from nothing, a handful of food is a hunt
        assertTrue(why(40, bag(2, 0, false, FOOD_LINE - 1, false), 0).startsWith("the bag was nearly empty"));
    }

    @Test
    public void eachKindOfRichIsRichOnItsOwn() {
        assertNull(why(500, bag(3, 0, false, 0, false), 0));
        assertNull(why(500, bag(0, 1, false, 0, false), 0));
        assertNull(why(500, bag(0, 0, false, FOOD_LINE, false), 0));
        // a late bag: eyes, pearls, rods, a bed and no iron tool in sight
        assertNull(why(500, bag(0, 0, false, 0, true), 0));
    }

    @Test
    public void aBarePickIsOnlyWorthAShortWalk() {
        DeathRules.Worth pick = bag(0, 0, true, 10, false);
        assertNull(why(DeathRules.NEAR_BLOCKS, pick, 0));
        assertTrue(why(DeathRules.NEAR_BLOCKS + 1, pick, 0).startsWith("the bag was nearly empty"));
    }

    @Test
    public void aSpotThatKilledUsTwiceIsNotWalkedIntoAThirdTime() {
        // one earlier death there is a bag recovered and a second life spent in the same cave, not a loop
        assertNull(why(80, rich(), 1));
        assertEquals("this spot has killed us 3 times", why(80, rich(), 2));
        assertEquals("this spot has killed us 4 times", why(80, rich(), 3));
    }

    @Test
    public void dyingAtThePileWeWereGoingBackForEndsItWhateverIsInTheBag() {
        assertEquals("it killed us on the way back for the last pile",
                DeathRules.refuse(true, 80, 1000, rich(), FOOD_LINE, 0, true, false));
    }

    @Test
    public void aBagWeNeverSawIsNotAReasonToLeave() {
        assertNull(why(200, null, 0));
    }

    @Test
    public void aPileInAnotherDimensionIsNotThisRulesBusiness() {
        assertEquals("it is in another dimension", DeathRules.refuse(false, 10, 1000, rich(), FOOD_LINE, 0, false, false));
    }

    private static RunState.Death death(Dimension dimension, int x, int z) {
        RunState.Death d = new RunState.Death();
        d.dimension = dimension.name();
        d.x = x;
        d.z = z;
        return d;
    }

    @Test
    public void deathsCloseTogetherAreTheSameSpot() {
        List<RunState.Death> before = List.of(death(Dimension.OVERWORLD, 100, 100), death(Dimension.OVERWORLD, 130, 100),
                death(Dimension.OVERWORLD, 400, 100), death(Dimension.NETHER, 110, 100));
        assertEquals(2, DeathRules.killedHere(before, Dimension.OVERWORLD, 110, 100));
        assertEquals(0, DeathRules.killedHere(before, Dimension.OVERWORLD, 250, 100));
        assertEquals(1, DeathRules.killedHere(before, Dimension.NETHER, 110, 100));
        assertEquals(0, DeathRules.killedHere(List.of(), Dimension.OVERWORLD, 0, 0));
        // an old save can have no dimension on an entry, that must not blow up
        assertEquals(0, DeathRules.killedHere(List.of(new RunState.Death()), Dimension.OVERWORLD, 0, 0));
        assertTrue(DeathRules.sameSpot(0, 0, 48, 0));
        assertFalse(DeathRules.sameSpot(0, 0, 49, 0));
    }

    @Test
    public void whatTheBagHeldComesOffTheFacts() {
        FakeFacts f = new FakeFacts().give(Items.RAW_IRON, 25).give(Items.IRON_INGOT, 5).give(Items.IRON_PICKAXE, 1)
                .give(Items.STONE_AXE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        f.foodUnits = 100;
        assertEquals(bag(30, 2, true, 100, false), DeathRules.worth(f));
        assertEquals(bag(0, 0, false, 0, false), DeathRules.worth(new FakeFacts()));
        // a stone axe alone is not a pile
        assertEquals(bag(0, 0, false, 0, false), DeathRules.worth(new FakeFacts().give(Items.STONE_AXE, 1)));
        // eyes, pearls, rods, obsidian, a bed or a bucket are the run itself
        for (net.minecraft.world.item.Item item : new net.minecraft.world.item.Item[] {Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD,
                Items.BLAZE_POWDER, Items.OBSIDIAN, Items.DIAMOND, Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET,
                Items.FLINT_AND_STEEL, Items.BED.red(), Items.BED.white()}) {
            assertTrue(item.toString(), DeathRules.worth(new FakeFacts().give(item, 1)).run());
        }
    }

    @Test
    public void theLogLinesSayWhatWasInIt() {
        assertEquals("death: going back for the pile, 450 blocks, 30 iron, 1 iron or better piece, a pick, 100 food",
                DeathRules.going(450.2, rich()));
        assertEquals("death: going back for the pile, 450 blocks, run items", DeathRules.going(450, bag(0, 0, false, 0, true)));
        assertEquals("death: going back for the pile, 12 blocks", DeathRules.going(12, null));
        assertEquals("death: leaving the pile, it is a long way", DeathRules.leaving("it is a long way"));
    }

    // ---- night, no armor, a pick

    private static String night(double blocks, DeathRules.Worth worth, boolean darkAndBare) {
        return DeathRules.refuse(true, blocks, 1000, worth, FOOD_LINE, 0, false, darkAndBare);
    }

    @Test
    public void aPickAloneIsNotWorthANightBare() {
        assertEquals("it is night and we have no armor for a pick", night(40, bag(0, 0, true, 10, false), true));
    }

    @Test
    public void aBagWithNoPickStillSaysNearlyEmptyAtNight() {
        assertTrue(night(40, bag(0, 0, false, 10, false), true).startsWith("the bag was nearly empty"));
    }

    @Test
    public void aRichPileStillGoesAtNight() {
        assertNull(night(40, rich(), true));
    }

    @Test
    public void aPickByDayOrArmoredStillGoes() {
        assertNull(night(40, bag(0, 0, true, 10, false), false));
    }

    @Test
    public void aBagWeNeverSawStillGoesAtNight() {
        assertNull(night(40, null, true));
    }

    @Test
    public void tooFarSaysFurtherEvenAtNight() {
        assertTrue(night(1001, bag(0, 0, true, 10, false), true).contains("further than"));
    }

    @Test
    public void aRichPileGetsTheLongReach() {
        assertEquals(3000, DeathRules.reachFor(rich(), FOOD_LINE, 1000));
        assertEquals(3000, DeathRules.reachFor(bag(0, 0, false, 0, true), FOOD_LINE, 1000));
    }

    @Test
    public void aPoorOrUnseenPileKeepsThePlainReach() {
        assertEquals(1000, DeathRules.reachFor(bag(0, 0, true, 6, false), FOOD_LINE, 1000));
        assertEquals(1000, DeathRules.reachFor(null, FOOD_LINE, 1000));
    }

    @Test
    public void aReachSetHigherOrToZeroIsRespected() {
        assertEquals(5000, DeathRules.reachFor(rich(), FOOD_LINE, 5000));
        assertEquals(0, DeathRules.reachFor(rich(), FOOD_LINE, 0));
    }

    @Test
    public void theLongReachWalksTo1271ButNotPast3000() {
        int reach = DeathRules.reachFor(rich(), FOOD_LINE, 1000);
        assertNull(DeathRules.refuse(true, 1271, reach, rich(), FOOD_LINE, 0, false, false));
        assertTrue(DeathRules.refuse(true, 3001, reach, rich(), FOOD_LINE, 0, false, false).contains("further than"));
    }

    @Test
    public void diedAtThePileStillStopsTheRetryLoopForARichPile() {
        assertEquals("it killed us on the way back for the last pile",
                DeathRules.refuse(true, 1271, 3000, rich(), FOOD_LINE, 0, true, false));
    }

    @Test
    public void aRichPileAtNightWithNoArmorWaitsForTheDayInsteadOfBeingDropped() {
        // refuse() still lets it through, waitsForDay is the extra say
        assertNull(DeathRules.refuse(true, 1271, 3000, rich(), FOOD_LINE, 0, false, true));
        assertTrue(DeathRules.waitsForDay(true, rich(), FOOD_LINE, true));
        assertFalse(DeathRules.waitsForDay(false, rich(), FOOD_LINE, true));
        assertFalse(DeathRules.waitsForDay(true, bag(0, 0, true, 6, false), FOOD_LINE, true));
        assertFalse(DeathRules.waitsForDay(true, null, FOOD_LINE, true));
    }

    @Test
    public void aRichPileInsideTheTickingAreaIsFetchedNowNotWaitedFor() {
        // loaded chunks keep ticking, the items on the floor despawn while we wait
        assertFalse(DeathRules.waitsForDay(true, rich(), FOOD_LINE, DeathRules.pileUnloaded(100, 90, 176)));
        assertTrue(DeathRules.waitsForDay(true, rich(), FOOD_LINE, DeathRules.pileUnloaded(1271, 0, 176)));
    }

    @Test
    public void theTickingAreaIsASquareAroundUs() {
        assertFalse(DeathRules.pileUnloaded(176, -176, 176));
        assertTrue(DeathRules.pileUnloaded(-177, 10, 176));
        assertTrue(DeathRules.pileUnloaded(10, 177, 176));
        // 150 each way is 212 round, still loaded
        assertFalse(DeathRules.pileUnloaded(150, 150, 176));
    }

    // a far nether pile is only put off for the eye makings: rods, powder, pearls, eyes
    @Test
    public void eyePartsAreTheOnlyThingsAFarNetherPileWaitsFor() {
        for (net.minecraft.world.item.Item item : new net.minecraft.world.item.Item[] {Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD,
                Items.BLAZE_POWDER}) {
            DeathRules.Worth bag = DeathRules.worth(new FakeFacts().give(item, 1));
            assertTrue(item.toString(), bag.eyeParts());
            assertTrue(item.toString(), adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.defersFor(bag));
        }
        // a stick, a water bucket, iron and armor all come back with the rebuild
        FakeFacts rest = new FakeFacts().give(Items.STICK, 1).give(Items.WATER_BUCKET, 1).give(Items.IRON_INGOT, 20).give(Items.IRON_SWORD, 1);
        rest.worn.add(Items.IRON_CHESTPLATE);
        DeathRules.Worth bag = DeathRules.worth(rest);
        assertFalse(bag.eyeParts());
        assertFalse(adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.defersFor(bag));
        // no bag seen at all is no reason to wait either
        assertFalse(adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.defersFor(null));
    }
}
