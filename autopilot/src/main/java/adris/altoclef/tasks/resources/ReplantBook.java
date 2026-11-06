package adris.altoclef.tasks.resources;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// the holes we made in the field and which one we are walking to. knows nothing about minecraft (ticks come in as
// numbers) so the stall rules can be tested without a world
final class ReplantBook<T> {
    // a plant that shows up and is gone again within this many ticks was the server saying no
    private static final int SETTLE_TICKS = 60;
    private static final int MAX_REVERTS = 3;
    // pickups run ahead of the replant and a gap that long is not time spent on the cell
    private static final int MAX_TICK_GAP = 2;

    private final Set<T> holes = new LinkedHashSet<>();
    // trampled, unreachable or just refusing seeds. never offered again, a loop on one bad cell starves the good ones
    private final Set<T> gaveUp = new HashSet<>();
    // holes that stopped being holes a moment ago, watched in case they come back
    private final Map<T, Integer> recent = new HashMap<>();
    private final Map<T, Integer> reverts = new HashMap<>();
    private final int budgetTicks;
    private final Consumer<T> onGiveUp;
    private T target;
    private int spent;
    private int lastPick;

    ReplantBook(int budgetTicks, Consumer<T> onGiveUp) {
        this.budgetTicks = budgetTicks;
        this.onGiveUp = onGiveUp;
    }

    void add(T cell) {
        if (!gaveUp.contains(cell)) {
            holes.add(cell);
        }
    }

    // a cell that stopped being a hole (planted, trampled to dirt) is forgotten, unless it turns back into one right away:
    // the client predicts a plant the server may throw out, and that looks planted for a tick. three of those and the cell is
    // a dud. a target that has used up its whole budget is given up on too: planting takes a few ticks, a whole budget on
    // one cell means something is wrong with the cell
    void refresh(int now, Predicate<T> stillAHole) {
        Iterator<T> it = holes.iterator();
        while (it.hasNext()) {
            T cell = it.next();
            if (!stillAHole.test(cell)) {
                it.remove();
                recent.put(cell, now);
            }
        }
        Iterator<Map.Entry<T, Integer>> watch = recent.entrySet().iterator();
        while (watch.hasNext()) {
            Map.Entry<T, Integer> e = watch.next();
            T cell = e.getKey();
            if (now - e.getValue() > SETTLE_TICKS) {
                watch.remove();
            } else if (stillAHole.test(cell)) {
                watch.remove();
                if (reverts.merge(cell, 1, Integer::sum) >= MAX_REVERTS) {
                    giveUp(cell);
                } else {
                    holes.add(cell);
                }
            }
        }
        if (target == null) {
            return;
        }
        if (!holes.contains(target)) {
            target = null;
        } else if (spent > budgetTicks) {
            giveUp(target);
        }
    }

    private void giveUp(T cell) {
        gaveUp.add(cell);
        holes.remove(cell);
        if (cell.equals(target)) {
            target = null;
        }
        onGiveUp.accept(cell);
    }

    // the committed hole, or the nearest one in range when we have none. null when there is nothing worth the walk
    T pick(int now, ToDoubleFunction<T> distSq, double rangeSq) {
        if (target != null) {
            spent += Math.min(Math.max(now - lastPick, 0), MAX_TICK_GAP);
            lastPick = now;
            return target;
        }
        T best = null;
        double bestDist = rangeSq;
        for (T cell : holes) {
            double d = distSq.applyAsDouble(cell);
            if (d <= bestDist) {
                best = cell;
                bestDist = d;
            }
        }
        if (best != null) {
            target = best;
            spent = 0;
            lastPick = now;
        }
        return target;
    }

    // out of seeds: the walk is off, and the clock shouldn't run while we are busy finding some
    void release() {
        target = null;
    }

    int near(ToDoubleFunction<T> distSq, double rangeSq) {
        int n = 0;
        for (T cell : holes) {
            if (distSq.applyAsDouble(cell) <= rangeSq) {
                n++;
            }
        }
        return n;
    }

    boolean committed() {
        return target != null;
    }

    // cells that just stopped being holes and might come back
    boolean settling() {
        return !recent.isEmpty();
    }

    boolean any() {
        return !holes.isEmpty();
    }
}
