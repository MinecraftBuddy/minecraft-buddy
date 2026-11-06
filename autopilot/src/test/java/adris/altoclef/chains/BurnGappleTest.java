package adris.altoclef.chains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

// burning at half hearts with a gapple in the bag eats it, full bar or not. anything missing and it waits like always
public class BurnGappleTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void burningAtHalfHeartsEatsTheGapple() {
        assertTrue(FoodChain.burnGapple(true, 10, true, true));
        assertTrue(FoodChain.burnGapple(true, 1.9f, true, true));
    }

    @Test
    public void anyMissingPieceWaits() {
        assertFalse(FoodChain.burnGapple(true, 10.5f, true, true));
        assertFalse(FoodChain.burnGapple(false, 4, true, true));
        assertFalse(FoodChain.burnGapple(true, 4, false, true));
        // a mob in bite range keeps the mouth shut, same stance rule as every other meal
        assertFalse(FoodChain.burnGapple(true, 4, true, false));
    }
}
