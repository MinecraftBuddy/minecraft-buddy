package adris.altoclef.trace;

import adris.altoclef.Debug;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TraceTest {

    @After
    public void putTheSinkBack() {
        Trace.setSink(null);
    }

    @Test
    public void aDumpHasAHeaderTheBufferedLinesAndAFooter() {
        List<String> out = new ArrayList<>();
        Trace.setSink(out::add);
        Trace.record("the marker line 7f3a");
        Trace.dump("test");
        assertTrue(out.get(0).startsWith("TRACE DUMP (test) build "));
        assertTrue(out.stream().anyMatch(l -> l.endsWith("the marker line 7f3a")));
        assertTrue(out.get(out.size() - 1).startsWith("END TRACE DUMP (test)"));
    }

    @Test
    public void dumpingDoesNotFeedTheRing() {
        Trace.setSink(l -> {
        });
        Trace.record("something");
        int before = Trace.snapshot().size();
        Trace.dump("test");
        assertEquals(before, Trace.snapshot().size());
    }

    @Test
    public void debugLogInternalReachesTheRing() {
        Debug.logInternal("hello from the test 91c2");
        assertTrue(Trace.snapshot().stream().anyMatch(l -> l.endsWith("ALTO CLEF: hello from the test 91c2")));
    }

    @Test
    public void aSinkThatThrowsNeverReachesTheCaller() {
        Trace.setSink(l -> {
            throw new IllegalStateException("boom");
        });
        Trace.dump("test");
    }

    @Test
    public void aNullLineIsIgnored() {
        Trace.record(null);
    }
}
