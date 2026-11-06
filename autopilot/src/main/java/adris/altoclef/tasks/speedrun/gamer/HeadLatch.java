package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.GameClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the planner is recomputed every tick and KitRunner follows whatever is first, so two needs that trade places (a worn
// pick, a food count wobbling at the line) made the bot drop a task it had just started and pick another up, then
// swap back. this is the hysteresis: the need we are running stays the head for a short dwell. pure, so the rules have tests
public final class HeadLatch {
    // about 5 s. long enough that a need which just started finishes its first step, short enough that a real change of
    // plan (the ingots came back, the pick is craftable) is only a moment late
    public static final long DWELL_TICKS = 100;
    // "the running need is in the plan", for missingSince
    public static final long NEVER = -1;
    // the needs that are a plain "hold this many" of a bag count, the ones keepWhileGone may hold on to
    private static final Set<String> BAG_COUNTED = Set.of("log", "planks", KitPlanner.COBBLE, KitNeed.FOOD);

    private HeadLatch() {
    }

    // about 30 s. a need that was the head and gave it up less than this ago, and now wants it back, is half of a flip (food and
    // water at the portal each emptied the bag the other one needed). the running one holds for this long instead of the dwell,
    // so the first half of the flip gets finished and not undone after 5 s
    public static final long FLIP_TICKS = 600;

    // when each need name last stopped being the head (game ticks). the runner feeds it, pick reads it
    public static final class Flips {
        private final Map<String, Long> left = new HashMap<>();

        public void left(String name, long now) {
            left.put(name, now);
        }

        public boolean recent(String name, long now) {
            Long at = left.get(name);
            return at != null && now >= at && now - at < FLIP_TICKS;
        }

        public void clear() {
            left.clear();
        }
    }

    // how long armor in the bag lets a craft finish before it cuts in. a craft is a few seconds at the table,
    // this is for one that has to turn logs into planks first. one that takes longer than this is stuck and the armor goes on
    public static final long ARMOR_WAIT_TICKS = 600;
    // putting armor on is a second. this long on it with the same pieces still to go on is a piece it cannot reach (a cursed one
    // worn over the slot, one stuck in the offhand or the 2x2 grid) and EquipArmorTask never gives up, so the runner does
    // (KitRunner.watchArmor): armor on goes to the back of its list until the pieces change
    public static final long ARMOR_STUCK_TICKS = 400;

    // the need to run this tick. `running` started at `since` (game ticks), needs.get(0) is what the planner wants now.
    // `missingSince` is the tick the running need dropped out of the plan (NEVER while it is in). the running one is kept only
    // while all of these hold, any one of them failing hands over to the planner:
    //   - it is still owed, exactly as it was asked (resized means a different job). a log, planks or cobble need gone from the
    //     list is only believed once we hold its count, or it has stayed gone for a whole dwell: one planner input wobbling (a table that counts at 15
    //     blocks and not at 22) dropped the log need for a moment, and that moment used to hand the head straight over
    //   - the dwell is not up (a cook with its station has no dwell, a craft ahead of armor on gets
    //     ARMOR_WAIT_TICKS and the early load its own latch, see below)
    //   - the planner's head is not a pickaxe while we hold none at all: nothing to mine with is not something to wait out
    // side jobs and chains (golem, mob defense, food chain) sit above the runner and never come through here
    public static KitNeed pick(KitNeed running, long since, long missingSince, long now, List<KitNeed> needs, GamerFacts f) {
        return pick(running, since, missingSince, now, needs, f, null);
    }

    // same, and `flips` (may be null) says which needs just stopped being the head: asking for one of those back holds the running
    // need for FLIP_TICKS instead of the dwell. armor on is left out, it has its own wait and putting it on is a second
    public static KitNeed pick(KitNeed running, long since, long missingSince, long now, List<KitNeed> needs, GamerFacts f, Flips flips) {
        KitNeed head = needs.get(0);
        if (running == null || running.equals(head)) {
            return head;
        }
        if (toolGone(head, f)) {
            return head;
        }
        if (sameJob(needs, running) == null) {
            return keepWhileGone(running, missingSince, now, f) ? running : head;
        }
        if (!needs.contains(running)) {
            return head;
        }
        // a cook that has its station is not on the dwell clock: 5 s is the time it takes to walk up and put the meat in, and
        // the iron took the head the moment that ran out. it is the cook's own give up that ends it
        if (KitNeed.isCookName(running.catalogueName()) && f.cookStation() != null) {
            return running;
        }
        if (waitsForArmor(running, since, now, head, f)) {
            return running;
        }
        // a resize of the same job (a food count moving with the bag) is not a change of plan, it hands over without the flip rule
        boolean flipBack = flips != null && !running.catalogueName().equals(head.catalogueName())
                && !KitNeed.EQUIP_ARMOR.equals(head.catalogueName()) && flips.recent(head.catalogueName(), now);
        if (GameClock.since(since, now) >= (flipBack ? FLIP_TICKS : DWELL_TICKS)) {
            return head;
        }
        return running;
    }

    // the list the runner picks from: armor on moved to the front. the plan keeps it behind the crafts so everyone reading the plan
    // head (the climb, the benches, the furnace rules) still sees the real work, the runner is the only one that puts it on first.
    // it used to wait behind every craft of the kit while the chestplate sat in the bag at armor 0
    public static List<KitNeed> armorFirst(List<KitNeed> needs) {
        for (int i = 1; i < needs.size(); i++) {
            if (KitNeed.EQUIP_ARMOR.equals(needs.get(i).catalogueName())) {
                List<KitNeed> out = new ArrayList<>(needs);
                out.add(0, out.remove(i));
                return out;
            }
        }
        return needs;
    }

    // armor on given up on (KitRunner.watchArmor): everything else goes first. a plan with nothing else in it still gets it, it
    // is the only thing left to try
    public static List<KitNeed> armorLast(List<KitNeed> needs) {
        for (int i = 0; i < needs.size() - 1; i++) {
            if (KitNeed.EQUIP_ARMOR.equals(needs.get(i).catalogueName())) {
                List<KitNeed> out = new ArrayList<>(needs);
                out.add(out.remove(i));
                return out;
            }
        }
        return needs;
    }

    // armor on leads the runner's list the moment there is some in the bag (armorFirst), but a craft half way through its grid or ore
    // half way into the early furnace is not something to walk off from for a second of clicking: it finishes, then the armor.
    // the mining need and the rest get the plain dwell, digging on at armor 0 is the thing this is all about
    // (the load is timed by its own latch, FurnacePlan.earlyLoadInFlight: keySince is when the early need became the head, which
    // is all the mining for its ore too, and that would be past any wait the moment the ore went in)
    static boolean waitsForArmor(KitNeed running, long since, long now, KitNeed head, GamerFacts f) {
        if (!KitNeed.EQUIP_ARMOR.equals(head.catalogueName())) {
            return false;
        }
        if ("iron_ingot".equals(running.catalogueName())) {
            return f.earlyLoadInFlight();
        }
        return running.isCraft() && now >= since && now - since < ARMOR_WAIT_TICKS;
    }

    // the plan's entry for the same thing as `running` (same name, any count), null when the plan has none
    public static KitNeed sameJob(List<KitNeed> needs, KitNeed running) {
        if (running == null) {
            return null;
        }
        for (KitNeed n : needs) {
            if (n.catalogueName().equals(running.catalogueName())) {
                return n;
            }
        }
        return null;
    }

    // a running need the plan just dropped. held: we really are done, let go now. not held: keep at it until it has been gone a
    // whole dwell, a real change of plan stays gone and a flicker comes back first. only the plain gathers and food get this: their count is
    // the bag (food: bag plus what is cooking) and nothing else, so "not held" means not done. the rest drop out for reasons the bag can't see (iron cooking, a worn
    // pick replaced, a craft moved behind the smelt) and holding those sent the bot mining for iron already in the furnace
    static boolean keepWhileGone(KitNeed running, long missingSince, long now, GamerFacts f) {
        if (!BAG_COUNTED.contains(running.catalogueName()) || satisfied(running, f)) {
            return false;
        }
        if (missingSince == NEVER) {
            // nobody started the clock, so it went this very tick
            return true;
        }
        // a clock way back does not hold for ever (same as the dwell)
        return GameClock.since(missingSince, now) < DWELL_TICKS;
    }

    // the bag holds what the need asked for: a need is "hold this many in total", the same count the planner checks
    static boolean satisfied(KitNeed need, GamerFacts f) {
        // food is not an item id: the bag's units plus what is cooking for us, the basis the planner drops the need on
        if (KitNeed.FOOD.equals(need.catalogueName())) {
            return f.foodUnits() + f.pendingFoodUnits() >= need.count();
        }
        return KitPlanner.have(f, need.catalogueName()) >= need.count();
    }

    // a pick to make and no pick to make it with (or mine anything with). a merely worn one does not count as gone
    static boolean toolGone(KitNeed head, GamerFacts f) {
        return head.catalogueName().endsWith("_pickaxe") && !KitPlanner.holdsAnyPick(f);
    }
}
