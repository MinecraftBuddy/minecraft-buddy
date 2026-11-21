package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// built_log_skip: a foreign block in any of the 26 cells round a log marks it built; the log itself and cells two off do not
public class BuiltLogTest {
    @Test
    public void aForeignBlockBesideMarksTheLogBuilt() {
        BlockPos log = new BlockPos(10, 70, -5);
        assertFalse(BuiltLog.built(log, cell -> false));
        assertTrue(BuiltLog.built(log, cell -> cell.equals(new BlockPos(11, 71, -4))));
        assertTrue(BuiltLog.built(log, cell -> cell.equals(new BlockPos(10, 69, -5))));
        assertFalse(BuiltLog.built(log, cell -> cell.equals(log)));
        assertFalse(BuiltLog.built(log, cell -> cell.equals(new BlockPos(12, 70, -5))));
    }
}
