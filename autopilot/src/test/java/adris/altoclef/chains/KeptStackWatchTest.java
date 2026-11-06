package adris.altoclef.chains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeptStackWatchTest {

    @Test
    public void firesOnceWhenTheWaitRunsOut() {
        KeptStackWatch watch = new KeptStackWatch();
        for (int i = 1; i < KeptStackWatch.GIVE_UP_TICKS; i++) {
            assertFalse(watch.tick());
        }
        assertTrue(watch.tick());
        assertFalse(watch.tick());
    }

    @Test
    public void resetStartsTheWaitOver() {
        KeptStackWatch watch = new KeptStackWatch();
        for (int i = 0; i < KeptStackWatch.GIVE_UP_TICKS - 1; i++) {
            watch.tick();
        }
        watch.reset();
        for (int i = 1; i < KeptStackWatch.GIVE_UP_TICKS; i++) {
            assertFalse(watch.tick());
        }
        assertTrue(watch.tick());
    }
}
