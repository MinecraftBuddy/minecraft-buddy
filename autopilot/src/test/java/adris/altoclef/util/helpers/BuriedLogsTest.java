package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuriedLogsTest {
    private static final BlockPos POST = new BlockPos(5, 20, 5);

    @Test
    public void aBuriedLogStaysBuriedOnceItsChunkIsGone() {
        BuriedLogs logs = new BuriedLogs();
        boolean[] loaded = {false};
        // the real scan's shape: unloaded reads as not buried, loaded tells the truth
        Predicate<BlockPos> scan = log -> loaded[0];
        assertFalse(logs.buried(POST, scan));
        loaded[0] = true;
        assertTrue(logs.buried(POST, scan));
        loaded[0] = false;
        assertTrue(logs.buried(POST, scan));
    }

    @Test
    public void aTreeOnTheSurfaceIsNeverCaught() {
        BuriedLogs logs = new BuriedLogs();
        for (int i = 0; i < 3; i++) {
            assertFalse(logs.buried(POST, log -> false));
        }
    }

    @Test
    public void otherLogsAreNotCaughtWithIt() {
        BuriedLogs logs = new BuriedLogs();
        assertTrue(logs.buried(POST, log -> true));
        assertFalse(logs.buried(POST.above(), log -> false));
    }

    @Test
    public void aReusedMutablePosIsNotWhatGetsRemembered() {
        BuriedLogs logs = new BuriedLogs();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(1, 2, 3);
        assertTrue(logs.buried(at, log -> true));
        at.set(9, 9, 9);
        assertFalse(logs.buried(new BlockPos(9, 9, 9), log -> false));
        assertTrue(logs.buried(new BlockPos(1, 2, 3), log -> false));
    }

    @Test
    public void aNewTaskStartsClean() {
        assertTrue(new BuriedLogs().buried(POST, log -> true));
        assertFalse(new BuriedLogs().buried(POST, log -> false));
    }
}
