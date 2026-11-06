package adris.altoclef.tasks;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// which clicks get the "is the ray on our face" gate: full buckets only
public class InteractWithBlockPourTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void fullBucketsPour() {
        assertTrue(InteractWithBlockTask.pours(new ItemTarget(Items.LAVA_BUCKET, 1)));
        assertTrue(InteractWithBlockTask.pours(new ItemTarget(Items.WATER_BUCKET, 1)));
        // a fish comes with its water
        assertTrue(InteractWithBlockTask.pours(new ItemTarget(Items.COD_BUCKET, 1)));
    }

    @Test
    public void theEmptyOneAndEverythingElseDoNot() {
        assertFalse(InteractWithBlockTask.pours(new ItemTarget(Items.BUCKET, 1)));
        assertFalse(InteractWithBlockTask.pours(new ItemTarget(Items.FLINT_AND_STEEL, 1)));
        assertFalse(InteractWithBlockTask.pours(ItemTarget.EMPTY));
        assertFalse(InteractWithBlockTask.pours(null));
    }

    @Test
    public void anyFullBucketInTheListCounts() {
        assertTrue(InteractWithBlockTask.pours(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.LAVA_BUCKET}, 1)));
    }
}
