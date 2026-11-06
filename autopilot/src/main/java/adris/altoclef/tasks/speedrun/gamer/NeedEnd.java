package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// "is the need we were on really over", for FurnacePlan's far rule (Moment.headDone). the plan head moves for all sorts of reasons
// that are not the end of anything (the food gate, the cook latch, a need dropping out for a tick while its item sits in a crafting
// grid), and from far off every one of those used to be a walk home. so the need, by name, has to be gone from the list (moved down
// it does not count: bed wool turning into stock-up wool is the same sheep) and stay gone for SOFT_HOLD_TICKS.
// a side job that ended (the village chest is empty, no bed left) is an end too when it was the whole outing (no kit need still
// going), no hold: the arbiter already rests those.
// pure, the phase feeds it the list and the clock
public final class NeedEnd {
    private String on;
    private long goneSince = -1;
    private boolean sideEnded;

    // every tick, before the moment. true once the need we were on has been out of the list long enough, or a side job just ended
    // with no kit need still in flight
    public boolean done(List<KitNeed> needs, long now) {
        boolean side = sideEnded;
        sideEnded = false;
        if (on == null) {
            return side;
        }
        if (has(needs, on)) {
            // a bed or a coal detour that cut into the wool ending is not the wool ending. only a side job that WAS the outing counts
            goneSince = -1;
            return false;
        }
        if (goneSince < 0) {
            goneSince = now;
        }
        return side || settled(now);
    }

    // the kit is running `head`. it becomes the need we are on when there is none yet, or the old one is settled as over. a head
    // that only moved in front of it does not take its place
    public void working(KitNeed head, long now) {
        if (head == null) {
            return;
        }
        if (on == null || (goneSince >= 0 && settled(now))) {
            on = head.catalogueName();
            goneSince = -1;
        }
    }

    // a side job handed the wheel back on its own. the next done() says so once
    public void sideEnded() {
        sideEnded = true;
    }

    // a trip started (we are at the furnace after, near or far is moot), or the batch is over: start clean
    public void reset() {
        on = null;
        goneSince = -1;
        sideEnded = false;
    }

    private boolean settled(long now) {
        return now - goneSince >= FurnacePlan.SOFT_HOLD_TICKS;
    }

    private static boolean has(List<KitNeed> needs, String name) {
        for (KitNeed need : needs) {
            if (need.catalogueName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
