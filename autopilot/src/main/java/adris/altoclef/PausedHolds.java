package adris.altoclef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

// what a paused task keeps while a chain has the wheel (BotBehaviour.holdWhilePaused). keyed by identity on purpose:
// task equals is isEqual, and two "collect 7 rods" tasks being equal does not make them the same owner.
// pure so the bookkeeping can be tested without a game
final class PausedHolds<P> {

    private record Hold<P>(Object owner, Object chain, List<P> items) {
    }

    private final List<Hold<P>> holds = new ArrayList<>(2);

    // a second interrupt before the resume replaces the first hand over, it does not stack
    void hold(Object owner, Object chain, Collection<P> items) {
        release(owner);
        if (owner == null || items == null || items.isEmpty()) return;
        holds.add(new Hold<>(owner, chain, new ArrayList<>(items)));
    }

    void release(Object owner) {
        holds.removeIf(h -> h.owner() == owner);
    }

    // only this chain's: a nested chain coming back must not take the outer chain's away
    void releaseFor(Object chain) {
        holds.removeIf(h -> h.chain() == chain);
    }

    void clear() {
        holds.clear();
    }

    boolean any(Predicate<P> test) {
        for (Hold<P> h : holds) {
            for (P item : h.items()) {
                if (test.test(item)) return true;
            }
        }
        return false;
    }

    int size() {
        return holds.size();
    }
}
