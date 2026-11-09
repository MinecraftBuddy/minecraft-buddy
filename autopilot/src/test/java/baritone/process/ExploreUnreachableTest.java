package baritone.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.process.ExploreProcess.Unreachable;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

// a goal no path reaches twice is passed over, so the rings turn elsewhere instead of failing at the same chunks forever
public class ExploreUnreachableTest {

    private static final long A = ChunkPos.pack(10, 3);
    private static final long B = ChunkPos.pack(11, 3);
    private static final long C = ChunkPos.pack(-4, 7);

    @Test
    public void oneFailureBansNothing() {
        Unreachable u = new Unreachable();
        u.inWorld(1);
        u.aimAt(List.of(A, B));
        assertFalse(u.failed());
        assertFalse(u.banned(A));
        assertEquals(0, u.banned());
    }

    @Test
    public void theSecondFailureBansTheGoalsChunks() {
        Unreachable u = new Unreachable();
        u.inWorld(1);
        u.aimAt(List.of(A, B));
        u.failed();
        assertTrue(u.failed());
        assertTrue(u.banned(A));
        assertTrue(u.banned(B));
        assertFalse(u.banned(C));
        assertEquals(2, u.banned());
        // a third failure of the same goal bans nothing new
        assertFalse(u.failed());
    }

    @Test
    public void onlyTheLastGoalCounts() {
        Unreachable u = new Unreachable();
        u.inWorld(1);
        u.aimAt(List.of(A));
        u.failed();
        u.aimAt(List.of(C));
        u.failed();
        assertFalse(u.banned(A));
        assertFalse(u.banned(C));
    }

    @Test
    public void aNewWorldStartsEmpty() {
        Unreachable u = new Unreachable();
        u.inWorld(1);
        u.aimAt(List.of(A));
        u.failed();
        u.failed();
        assertTrue(u.banned(A));
        u.inWorld(1);
        assertTrue(u.banned(A));
        u.inWorld(2);
        assertFalse(u.banned(A));
        // and nothing aimed carries over: a failure right after the change bans nothing
        assertFalse(u.failed());
    }
}
