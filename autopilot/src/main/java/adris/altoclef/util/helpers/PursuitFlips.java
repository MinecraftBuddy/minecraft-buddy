package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// the closest-object tasks drop a pursuit the moment it stops being valid and pick it again the moment it is valid once
// more. when the walk to it is what makes it invalid (a rule that reads the feet's height, a danger the step brings up)
// the two take turns for as long as the task lives: a block under the sea flipped out and in 260 times in 43 minutes
// (look 205), and looks 130, 151 and 202 were the same shape with other triggers. this counts, per object, the times the
// pursuit let it go while it was still there; LIMIT of them inside WINDOW ticks and the caller leaves it alone for a while
// (Tuning flip_guard, the ban book's FLIP_GUARD). pure, the caller feeds it the clock
public final class PursuitFlips {
    // eight drops of one object: a fair try with a few hiccups is three or four, the loops the looks read made one about
    // every ten seconds
    public static final int LIMIT = 8;
    // 3 minutes. eight hiccups spread wider than this are not a loop
    public static final long WINDOW = 3600;

    private final Map<Object, ArrayDeque<Long>> drops = new HashMap<>();

    // the pursuit let `obj` go at `now` while it still exists. true once that makes LIMIT inside WINDOW: the count then
    // starts over, so a second ban needs a second full loop
    public boolean dropped(Object obj, long now) {
        forgetOld(now);
        ArrayDeque<Long> at = drops.computeIfAbsent(obj, k -> new ArrayDeque<>());
        at.addLast(now);
        if (at.size() >= LIMIT) {
            drops.remove(obj);
            return true;
        }
        return false;
    }

    // how many drops of `obj` are inside the window now
    public int count(Object obj, long now) {
        forgetOld(now);
        ArrayDeque<Long> at = drops.get(obj);
        return at == null ? 0 : at.size();
    }

    // the span of the drops that made the last ban, for the log line: the caller asks before calling dropped
    public long firstDrop(Object obj) {
        ArrayDeque<Long> at = drops.get(obj);
        return at == null || at.isEmpty() ? -1 : at.peekFirst();
    }

    public void clear() {
        drops.clear();
    }

    private void forgetOld(long now) {
        Iterator<Map.Entry<Object, ArrayDeque<Long>>> it = drops.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> at = it.next().getValue();
            while (!at.isEmpty() && now - at.peekFirst() > WINDOW) {
                at.pollFirst();
            }
            if (at.isEmpty()) {
                it.remove();
            }
        }
    }
}
