package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.WeaponPick.Candidate;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class WeaponPickTest {

    @BeforeClass
    public static void bootstrap() {
        // damage and swing speed come off the item's attribute components, which need the registries
        ItemFixture.boot();
    }

    private static Candidate fresh(Item item) {
        return new Candidate(item, 0, item.getDefaultInstance().getMaxDamage());
    }

    private static Candidate worn(Item item) {
        int max = item.getDefaultInstance().getMaxDamage();
        return new Candidate(item, (int) Math.ceil(max * 0.9), max);
    }

    @Test
    public void anySwordBeatsAnyAxe() {
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(fresh(Items.STONE_SWORD), fresh(Items.WOODEN_AXE))));
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), fresh(Items.STONE_SWORD))));
        assertSame(Items.IRON_SWORD, WeaponPick.best(List.of(fresh(Items.IRON_SWORD), fresh(Items.IRON_AXE))));
        assertSame(Items.DIAMOND_SWORD, WeaponPick.best(List.of(fresh(Items.DIAMOND_AXE), fresh(Items.DIAMOND_SWORD))));
        // even a wooden sword against a netherite axe, the sweep and the faster charge are worth more than the hit
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.NETHERITE_AXE), fresh(Items.WOODEN_SWORD))));
    }

    @Test
    public void theBestSwordWinsAmongSwords() {
        assertSame(Items.DIAMOND_SWORD, WeaponPick.best(List.of(fresh(Items.STONE_SWORD), fresh(Items.DIAMOND_SWORD), fresh(Items.IRON_AXE))));
    }

    @Test
    public void anAxeIsTheFallbackWhenThereIsNoSword() {
        assertSame(Items.IRON_AXE, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), fresh(Items.IRON_AXE))));
    }

    @Test
    public void nothingButSwordsAndAxesIsAWeapon() {
        assertNull(WeaponPick.best(List.of()));
        assertNull(WeaponPick.best(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.DIAMOND_SHOVEL), fresh(Items.STICK))));
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SWORD))));
    }

    @Test
    public void aWornToolLosesToAnyHealthyWeapon() {
        // a diamond sword about to break is worse than a wooden axe with all its uses left
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(worn(Items.DIAMOND_SWORD), fresh(Items.WOODEN_AXE))));
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), worn(Items.DIAMOND_SWORD))));
    }

    @Test
    public void aWornToolIsStillBetterThanFists() {
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(worn(Items.DIAMOND_AXE))));
        // and between two worn ones the sword still wins
        assertSame(Items.IRON_SWORD, WeaponPick.best(List.of(worn(Items.IRON_SWORD), worn(Items.IRON_AXE))));
    }

    @Test
    public void wearUsesToolWearsLineLikeTheKitPlanner() {
        // the line itself is pinned in ToolWearTest, this is the ranking following it: 111 of 131 is healthy, 112 is worn
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_SWORD, 111, 131),
                new WeaponPick.Candidate(Items.STONE_AXE, 5, 131))));
        assertSame(Items.STONE_AXE, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_SWORD, 112, 131),
                new WeaponPick.Candidate(Items.STONE_AXE, 5, 131))));
        // an item that does not wear (max 0) is never worn, however the damage reads
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_SWORD, 5, 0))));
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_AXE, 112, 131),
                new WeaponPick.Candidate(Items.STONE_SWORD, 5, 0))));
    }

    @Test
    public void theFasterWeaponWinsATieOnDamage() {
        // stone and diamond axes both hit for 9, the diamond one swings faster
        assertEquals(ItemHelper.getAttackDamage(Items.STONE_AXE), ItemHelper.getAttackDamage(Items.DIAMOND_AXE), 1e-6);
        assertTrue(ItemHelper.getAttackSpeed(Items.DIAMOND_AXE) > ItemHelper.getAttackSpeed(Items.STONE_AXE));
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(fresh(Items.STONE_AXE), fresh(Items.DIAMOND_AXE))));
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(fresh(Items.DIAMOND_AXE), fresh(Items.STONE_AXE))));
    }

    @Test
    public void aCompleteTieKeepsTheFirstOneListed() {
        // the kill tasks list the hand first so equal weapons never get swapped back and forth
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.WOODEN_SWORD), fresh(Items.GOLDEN_SWORD))));
        assertSame(Items.GOLDEN_SWORD, WeaponPick.best(List.of(fresh(Items.GOLDEN_SWORD), fresh(Items.WOODEN_SWORD))));
    }

    @Test
    public void swingSpeedComesFromTheAttributeComponent() {
        // 4 base, a sword takes 2.4 off and an axe takes 3.2 (wooden) off
        assertEquals(4f, ItemHelper.getAttackSpeed(Items.STICK), 1e-6);
        assertEquals(1.6f, ItemHelper.getAttackSpeed(Items.IRON_SWORD), 1e-4);
        assertEquals(0.8f, ItemHelper.getAttackSpeed(Items.WOODEN_AXE), 1e-4);
    }

    // ---- a tool beats fists

    @Test
    public void aPickaxeOnlyBagIsNotAWeaponButItIsAToolThatBeatsFists() {
        assertNull(WeaponPick.best(List.of(fresh(Items.IRON_PICKAXE))));
        assertSame(Items.IRON_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.IRON_PICKAXE))));
        assertTrue(ItemHelper.hitsHarderThanFists(Items.WOODEN_PICKAXE));
    }

    @Test
    public void shovelsCountToo() {
        assertSame(Items.WOODEN_SHOVEL, WeaponPick.bestTool(List.of(fresh(Items.WOODEN_SHOVEL))));
    }

    @Test
    public void aBagWithNothingBetterThanFistsHasNoTool() {
        assertNull(WeaponPick.bestTool(List.of()));
        assertNull(WeaponPick.bestTool(List.of(fresh(Items.STICK), fresh(Items.BREAD), fresh(Items.DIRT), fresh(Items.COBBLESTONE))));
        assertFalse(ItemHelper.hitsHarderThanFists(Items.STICK));
        assertFalse(ItemHelper.hitsHarderThanFists(Items.AIR));
    }

    @Test
    public void anyWeaponBeatsAnyToolAndTheHarderToolBeatsTheSofterOne() {
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SWORD))));
        assertSame(Items.IRON_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.WOODEN_PICKAXE), fresh(Items.IRON_PICKAXE))));
    }

    @Test
    public void aWornToolIsNotOnTheListAndTheHandWinsTies() {
        assertSame(Items.WOODEN_SHOVEL, WeaponPick.bestTool(List.of(worn(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SHOVEL))));
        assertSame(Items.STONE_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.STONE_PICKAXE), fresh(Items.STONE_PICKAXE))));
        // and a worn one is not worth a fight when it is all there is: the last uses of the only pick are worth more
        assertNull(WeaponPick.bestTool(List.of(worn(Items.DIAMOND_PICKAXE))));
    }

    // the bag has no say in whether we fight any more: a zombie chewing on us gets hit back with whatever there is, fists
    // included. the machine does not even know what is in the bag
    @Test
    public void aZombieThatHitsUsInContactIsAFightWhateverIsInTheBag() {
        CombatCommit.Foe zombie = new CombatCommit.Foe(1, 1.5, false, false, 2);
        assertEquals(CombatCommit.Event.FIGHT_START,
                new CombatCommit().step(new CombatCommit.Tick(100, 20, 0, 0, List.of(zombie), null)));
    }

    @Test
    public void damageWasNotDisturbedByTheSpeedRefactor() {
        assertEquals(1f, ItemHelper.getAttackDamage(Items.STICK), 1e-6);
        assertEquals(4f, ItemHelper.getAttackDamage(Items.WOODEN_SWORD), 1e-6);
        assertEquals(7f, ItemHelper.getAttackDamage(Items.WOODEN_AXE), 1e-6);
        assertEquals(10f, ItemHelper.getAttackDamage(Items.NETHERITE_AXE), 1e-6);
    }

    // the pick flips with the crowd count, the hand must not: every swap hands the cooldown back to zero
    @Test
    public void aCrowdFlagFlippingEveryTickNeverChangesTheHandMidCooldown() {
        Item inHand = Items.STONE_AXE;
        for (int tick = 0; tick < 25; tick++) {
            Item pick = tick % 2 == 0 ? Items.STONE_SWORD : Items.STONE_AXE;
            // charging: past the just swung window and under a full cooldown, then full as well
            double charged = Math.min(tick + 3, 25);
            inHand = WeaponPick.hold(inHand, pick, charged);
            assertSame("tick " + tick, Items.STONE_AXE, inHand);
        }
    }

    @Test
    public void aFullCooldownWeaponAlsoStaysUntilTheSwingLands() {
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, Items.IRON_SWORD, 25));
    }

    @Test
    public void rightAfterASwingTheSwapIsAllowed() {
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.STONE_AXE, Items.STONE_SWORD, 0));
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.STONE_AXE, Items.STONE_SWORD, WeaponPick.JUST_SWUNG_TICKS));
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, Items.STONE_SWORD, WeaponPick.JUST_SWUNG_TICKS + 0.5));
    }

    @Test
    public void aHandWithoutAWeaponSwapsAtOnceWhateverTheCharge() {
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.WATER_BUCKET, Items.STONE_AXE, 25));
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.DIAMOND_PICKAXE, Items.STONE_SWORD, 9));
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.BREAD, Items.STONE_SWORD, 9));
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.AIR, Items.STONE_SWORD, 9));
    }

    @Test
    public void noPickMeansTheHandStaysAsItIs() {
        assertSame(Items.BREAD, WeaponPick.hold(Items.BREAD, null, 0));
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, null, 0));
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, Items.STONE_AXE, 0));
    }

    @Test
    public void anySwingSinceTheLastSwapOpensTheDoorOnce() {
        // mid cooldown, past the just swung window, but a swing went out since the hand last changed
        assertSame(Items.STONE_SWORD, WeaponPick.hold(Items.STONE_AXE, Items.STONE_SWORD, 12, true));
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, Items.STONE_SWORD, 12, false));
        // and a pick that is already in hand never moves it
        assertSame(Items.STONE_AXE, WeaponPick.hold(Items.STONE_AXE, Items.STONE_AXE, 12, true));
    }

    @Test
    public void bestAxeSkipsTheSwordAndTheWornAxe() {
        assertSame(Items.IRON_AXE, WeaponPick.bestAxe(List.of(fresh(Items.DIAMOND_SWORD), fresh(Items.IRON_AXE))));
        assertSame(Items.STONE_AXE, WeaponPick.bestAxe(List.of(worn(Items.DIAMOND_AXE), fresh(Items.STONE_AXE))));
        assertNull(WeaponPick.bestAxe(List.of(fresh(Items.DIAMOND_SWORD), worn(Items.IRON_AXE))));
        assertNull(WeaponPick.bestAxe(List.of(fresh(Items.DIAMOND_SWORD))));
    }
}
