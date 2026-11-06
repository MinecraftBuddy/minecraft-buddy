package adris.altoclef.trace;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TraceRingTest {

    @Test
    public void linesComeBackOldestFirst() {
        TraceRing ring = new TraceRing();
        ring.add("a");
        ring.add("b");
        ring.add("c");
        assertEquals(List.of("a", "b", "c"), ring.snapshot());
    }

    @Test
    public void anEmptyRingIsAnEmptySnapshot() {
        assertTrue(new TraceRing().snapshot().isEmpty());
    }

    @Test
    public void theOldestLinesFallOffAtCapacity() {
        TraceRing ring = new TraceRing();
        for (int i = 0; i < TraceRing.CAPACITY + 10; i++) {
            ring.add("line " + i);
        }
        List<String> lines = ring.snapshot();
        assertEquals(TraceRing.CAPACITY, lines.size());
        assertEquals("line 10", lines.get(0));
        assertEquals("line " + (TraceRing.CAPACITY + 9), lines.get(lines.size() - 1));
    }

    @Test
    public void exactlyFullKeepsEverything() {
        TraceRing ring = new TraceRing(3);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        assertEquals(List.of("a", "b", "c"), ring.snapshot());
        ring.add("d");
        assertEquals(List.of("b", "c", "d"), ring.snapshot());
    }

    @Test
    public void theSnapshotIsACopy() {
        TraceRing ring = new TraceRing();
        ring.add("a");
        List<String> snap = ring.snapshot();
        ring.add("b");
        assertEquals(List.of("a"), snap);
        snap.add("junk");
        assertEquals(List.of("a", "b"), ring.snapshot());
    }

    @Test
    public void capacityIsThreeHundred() {
        assertEquals(300, TraceRing.CAPACITY);
    }

    @Test
    public void aWriterOnAnotherThreadDoesNotBreakTheRing() throws Exception {
        TraceRing ring = new TraceRing();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    ring.add("x");
                    ring.snapshot();
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(TraceRing.CAPACITY, ring.snapshot().size());
    }
}
