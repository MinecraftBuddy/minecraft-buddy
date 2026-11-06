package adris.altoclef.chains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// the heal meal's filter, which is also what lets the heal-up hold start without a full bar
public class HealFoodTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void foodThatHurtsDoesNotHeal() {
        assertFalse(FoodChain.healFood(Items.CHORUS_FRUIT));
        assertFalse(FoodChain.healFood(Items.CHICKEN));
        assertFalse(FoodChain.healFood(Items.ROTTEN_FLESH));
    }

    @Test
    public void realFoodDoes() {
        assertTrue(FoodChain.healFood(Items.COOKED_BEEF));
        assertTrue(FoodChain.healFood(Items.BREAD));
        assertTrue(FoodChain.healFood(Items.BEEF));
    }
}
