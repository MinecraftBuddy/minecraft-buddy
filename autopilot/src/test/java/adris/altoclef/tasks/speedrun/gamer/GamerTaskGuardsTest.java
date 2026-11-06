package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// the stations we carry around are on the run's never-throw list, like the table always was
public class GamerTaskGuardsTest {

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void theStationsWeCarryAreGuarded() {
        assertTrue(GamerTask.guards(Items.CRAFTING_TABLE));
        assertTrue(GamerTask.guards(Items.FURNACE));
        assertTrue(GamerTask.guards(Items.SMOKER));
    }
}
