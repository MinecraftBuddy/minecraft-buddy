package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the nether killed the bot with a group of zombified piglins it had no business fighting
public class NeutralMobsTest {

    private static final String[] ALWAYS_NEUTRAL = {"zombified_piglin", "wolf", "bee", "iron_golem", "polar_bear", "llama",
            "trader_llama", "dolphin", "goat", "panda"};

    // far from us, nobody poked it, not looking at us. the "standing there with the aggressive flag up" case
    private static boolean calm(String type) {
        boolean neutral = NeutralMobs.neutralNow(type, false, false);
        return !NeutralMobs.hostile(neutral, true, false, false, 10);
    }

    @Test
    public void everyNeutralKindIsCalmUntilPoked() {
        for (String type : ALWAYS_NEUTRAL) {
            assertTrue(type, NeutralMobs.neutralNow(type, false, false));
            assertTrue(type + " is minding its own business", calm(type));
        }
    }

    @Test
    public void aProvokedNeutralIsHostile() {
        for (String type : ALWAYS_NEUTRAL) {
            boolean neutral = NeutralMobs.neutralNow(type, false, false);
            assertTrue(type, NeutralMobs.hostile(neutral, true, true, false, 10));
            // provoked and not (yet) flagged aggressive still counts, the flag is not reliable on the client
            assertTrue(type, NeutralMobs.hostile(neutral, false, true, false, 10));
        }
    }

    @Test
    public void aNeutralThatTargetsUsIsHostile() {
        assertTrue(NeutralMobs.hostile(true, false, false, true, 10));
    }

    @Test
    public void anAggressiveNeutralRightNextToUsIsHittingUs() {
        assertTrue(NeutralMobs.hostile(true, true, false, false, NeutralMobs.CLOSE));
        assertFalse(NeutralMobs.hostile(true, true, false, false, NeutralMobs.CLOSE + 0.1));
        // close but not aggressive is just a mob in the way
        assertFalse(NeutralMobs.hostile(true, false, false, false, 1));
    }

    @Test
    public void aCalmNeutralIsNotAfterUsEvenWhenItIsRightHere() {
        assertFalse(NeutralMobs.afterUs(false, false, false, 0));
    }

    @Test
    public void anythingElseKeepsTheOldAggressiveRule() {
        assertFalse(NeutralMobs.neutralNow("zombie", false, false));
        assertTrue(NeutralMobs.hostile(false, true, false, false, 30));
        assertFalse(NeutralMobs.hostile(false, false, true, true, 1));
    }

    @Test
    public void spidersAreOnlyNeutralInTheDaylight() {
        assertTrue(NeutralMobs.neutralNow("spider", true, false));
        assertTrue(NeutralMobs.neutralNow("cave_spider", true, false));
        assertFalse(NeutralMobs.neutralNow("spider", false, false));
        assertFalse(NeutralMobs.neutralNow("cave_spider", false, false));
        // poke a daylight spider and it is back to being a spider
        assertTrue(NeutralMobs.hostile(true, true, true, false, 8));
    }

    @Test
    public void piglinsAreNeutralOnlyWhileWeWearGold() {
        assertFalse(NeutralMobs.neutralNow("piglin", false, false));
        assertTrue(NeutralMobs.neutralNow("piglin", false, true));
        assertFalse(NeutralMobs.neutralNow("zombie", false, true));
        // the gold does not protect us from one we hit
        assertTrue(NeutralMobs.hostile(true, true, true, false, 8));
        assertFalse(NeutralMobs.hostile(true, true, false, false, 8));
    }

    @Test
    public void brutesAndHoglinsNeverGetTheBenefitOfTheDoubt() {
        assertFalse(NeutralMobs.neutralNow("piglin_brute", true, true));
        assertFalse(NeutralMobs.neutralNow("hoglin", true, true));
        assertFalse(NeutralMobs.neutralNow("zoglin", true, true));
        assertTrue(NeutralMobs.hostile(false, true, false, false, 20));
    }

    // a zombie chewing on us has its flag down on the hit tick and for half a second after, it should not blink off the list
    @Test
    public void aZombieThatJustBitUsStaysHostileWithItsFlagDown() {
        assertTrue(NeutralMobs.hostile(false, false, false, false, 1, NeutralMobs.hitRecently(10)));
    }

    @Test
    public void noFlagAndNoHitIsStillNotHostile() {
        assertFalse(NeutralMobs.hostile(false, false, false, false, 1, NeutralMobs.hitRecently(Provocations.NEVER)));
        assertFalse(NeutralMobs.hitRecently(Provocations.NEVER));
    }

    @Test
    public void theBiteWearsOffAfterTwoSwings() {
        assertTrue(NeutralMobs.hitRecently(NeutralMobs.ANGRY_AFTER_HIT));
        assertFalse(NeutralMobs.hostile(false, false, false, false, 1, NeutralMobs.hitRecently(41)));
        // flag up is hostile whatever the book says, same as before
        assertTrue(NeutralMobs.hostile(false, true, false, false, 1, NeutralMobs.hitRecently(41)));
    }

    // a neutral that hit us is already provoked, the new input changes nothing for it either way
    @Test
    public void neutralsIgnoreTheRecentHitInput() {
        for (String type : ALWAYS_NEUTRAL) {
            boolean neutral = NeutralMobs.neutralNow(type, false, false);
            assertFalse(type, NeutralMobs.hostile(neutral, false, false, false, 10, true));
            assertTrue(type, NeutralMobs.hostile(neutral, false, true, false, 10, false));
            assertFalse(type, NeutralMobs.hostile(neutral, true, false, false, 10, true));
        }
    }
}
