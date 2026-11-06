package adris.altoclef.tasks.container;

import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Mode;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Task keeps a child that isEqual the new one, so a finished visit must not match a fresh one at the same smoker
public class CollectFromFurnaceEqualTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static CollectFromFurnaceTask visit() {
        return new CollectFromFurnaceTask(new BlockPos(1, 64, 2), Blocks.SMOKER, "smoker", Mode.NORMAL, 100, 1000);
    }

    @Test
    public void aRunningVisitIsEqualToAFreshOne() {
        assertTrue(visit().isEqual(visit()));
    }

    @Test
    public void aFinishedVisitIsNotEqualToAFreshOne() throws Exception {
        CollectFromFurnaceTask old = visit();
        Field done = CollectFromFurnaceTask.class.getDeclaredField("done");
        done.setAccessible(true);
        done.setBoolean(old, true);
        assertFalse(visit().isEqual(old));
    }
}
