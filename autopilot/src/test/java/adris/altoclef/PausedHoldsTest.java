package adris.altoclef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;

import static org.junit.Assert.*;

// the bookkeeping behind BotBehaviour.holdWhilePaused: a paused task's mob defense exclusions stay up until it is back,
// stopped, or its chain has the wheel again. strings stand in for entities
public class PausedHoldsTest {

    private static final Predicate<String> BLAZE = s -> s.equals("blaze");
    private static final Predicate<String> ENDERMAN = s -> s.equals("enderman");

    private final Object userChain = new Object();
    private final Object survivalChain = new Object();
    private final Object rods = new Object();

    private static boolean excludes(PausedHolds<Predicate<String>> holds, String mob) {
        return holds.any(p -> p.test(mob));
    }

    @Test
    public void aPausedTaskKeepsItsBlazesExcluded() {
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        assertTrue(excludes(holds, "blaze"));
        assertFalse(excludes(holds, "zombie"));
    }

    @Test
    public void theResumeLetsGo() {
        // onStart pushes a fresh level with the exclusion in it, the held copy is not needed any more
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.release(rods);
        assertFalse(excludes(holds, "blaze"));
        assertEquals(0, holds.size());
    }

    @Test
    public void aRealStopAndAStopWhilePausedLetGoToo() {
        // both call release(owner), the owner is the only key they have
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.release(rods);
        assertFalse(excludes(holds, "blaze"));
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.release(rods);
        assertEquals(0, holds.size());
    }

    @Test
    public void theChainComingBackLetsGoOfARootThatFinishedWhileItWasOut() {
        // the 7th rod got picked up while mob defense had the wheel: the root is dropped with no start or stop, and only
        // the chain resume is left to take the blazes back
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.releaseFor(userChain);
        assertFalse(excludes(holds, "blaze"));
    }

    @Test
    public void aNestedChainComingBackLeavesTheOuterChainsHold() {
        // user chain paused by world survival, which mob defense then paused. world survival getting the wheel back must
        // not wipe what the user chain's rods task handed over
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        Object fireTask = new Object();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.hold(fireTask, survivalChain, List.of(ENDERMAN));
        holds.releaseFor(survivalChain);
        assertTrue(excludes(holds, "blaze"));
        assertFalse(excludes(holds, "enderman"));
        holds.releaseFor(userChain);
        assertEquals(0, holds.size());
    }

    @Test
    public void aSecondPauseReplacesTheFirstHandOver() {
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.hold(rods, userChain, List.of(ENDERMAN));
        assertEquals(1, holds.size());
        assertFalse(excludes(holds, "blaze"));
        assertTrue(excludes(holds, "enderman"));
    }

    @Test
    public void nothingToHandOverIsNoEntry() {
        // every ResourceTask does the hand over, most have nothing excluded
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of());
        assertEquals(0, holds.size());
    }

    @Test
    public void theHoldIsACopyOfTheLevel() {
        // the level gets popped and is nobody's after that, what it says later must not leak in
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        List<Predicate<String>> level = new ArrayList<>(List.of(BLAZE));
        holds.hold(rods, userChain, level);
        level.add(ENDERMAN);
        assertFalse(excludes(holds, "enderman"));
    }

    @Test
    public void ownersAreByIdentityNotEquals() {
        // tasks are equal by isEqual, two rods tasks for the same count are equal and still not the same owner
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        String a = new String("rods");
        String b = new String("rods");
        holds.hold(a, userChain, List.of(BLAZE));
        holds.release(b);
        assertTrue(excludes(holds, "blaze"));
    }

    @Test
    public void clearDropsEverything() {
        PausedHolds<Predicate<String>> holds = new PausedHolds<>();
        holds.hold(rods, userChain, List.of(BLAZE));
        holds.hold(new Object(), survivalChain, List.of(ENDERMAN));
        holds.clear();
        assertEquals(0, holds.size());
    }
}
