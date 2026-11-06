package adris.altoclef.trace;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StormGateTest {

    @Test
    public void aClassWithNobodyAboveItIsFlagged() {
        StormGate gate = new StormGate();
        assertFalse(gate.shadowed(String.class, List.of(), 0, c -> 0));
        assertFalse(gate.shadowed(String.class, List.of(Integer.class), 0, c -> 0));
    }

    @Test
    public void aChildOfAClassThatFlaggedStaysQuietForAMinute() {
        StormGate gate = new StormGate();
        gate.seen(Integer.class, 1000);
        assertTrue(gate.shadowed(String.class, List.of(Integer.class), 1000, c -> 0));
        assertTrue(gate.shadowed(String.class, List.of(Integer.class), 1000 + StartStormWatch.WINDOW_MS, c -> 0));
        assertFalse(gate.shadowed(String.class, List.of(Integer.class), 1001 + StartStormWatch.WINDOW_MS, c -> 0));
    }

    @Test
    public void anyAncestorCoversItNotJustTheParent() {
        StormGate gate = new StormGate();
        gate.seen(Long.class, 0);
        assertTrue(gate.shadowed(String.class, List.of(Integer.class, Long.class), 5, c -> 0));
    }

    @Test
    public void aParentThatIsStormingButHasNotFlaggedYetCoversItToo() {
        StormGate gate = new StormGate();
        // more than half the limit of starts in the window: the child is the symptom, the parent will flag
        assertTrue(gate.shadowed(String.class, List.of(Integer.class), 0, c -> StartStormWatch.LIMIT / 2 + 1));
        assertFalse(gate.shadowed(String.class, List.of(Integer.class), 0, c -> StartStormWatch.LIMIT / 2));
    }

    @Test
    public void aClassDoesNotShadowItself() {
        StormGate gate = new StormGate();
        gate.seen(String.class, 0);
        assertFalse(gate.shadowed(String.class, List.of(String.class), 5, c -> 99));
    }

    @Test
    public void theQuietChildStillCoversItsOwnChildren() {
        StormGate gate = new StormGate();
        gate.seen(Integer.class, 0);
        // the middle one was silenced, but it was seen: the grandchild is covered by the same storm
        gate.seen(String.class, 5);
        assertTrue(gate.shadowed(Long.class, List.of(String.class), 6, c -> 0));
    }
}
