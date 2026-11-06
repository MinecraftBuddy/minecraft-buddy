package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.List;

// the dry spell: the food task has been out wandering with no animal loaded for a while, so the bot stops standing in
// front of an empty field and mines/chops with a low belly instead. the latch is about 3 minutes, then the hunt gets
// another go. an animal showing up (GamerFacts.preyNear) pulls food straight back to the front, latch or not
public final class FoodDry {
    private FoodDry() {
    }

    // searching this long with nothing loaded and the spell starts
    static final long SEARCH_TICKS = 90 * 20;
    // and food sits behind the next kit step this long
    static final long LATCH_TICKS = 180 * 20;
    // prey this close (FoodHunt.distance) counts as "there is food here". it has to leave the bigger one to stop counting, so a
    // pig on the edge of the line does not flip the head every few seconds
    public static final double NEAR = 24;
    public static final double LEAVE = 32;

    // an animal that just stopped being there is most likely a kill, and its meat is on the ground. prey stays "near" this
    // long so food keeps the head through the pickup instead of the last pig's drops getting left for the starter
    static final long PREY_HOLD_TICKS = 5 * 20;

    // `seenAt` is the last tick prey was really loaded (Long.MIN_VALUE for never)
    static boolean preyHeld(long seenAt, long now) {
        return seenAt != Long.MIN_VALUE && now >= seenAt && now - seenAt < PREY_HOLD_TICKS;
    }

    // what the planner sees: prey in sight, just gone (a kill), or the food task still waiting on or walking to the loot
    static boolean preyKept(boolean seen, long seenAt, long now, boolean lootPending) {
        return seen || preyHeld(seenAt, now) || lootPending;
    }

    // one look per tick. returns a log line when the spell starts or ends, null otherwise
    static String tick(RunState s, boolean searching, boolean prey, long now) {
        if (s.foodDryOn) {
            // (a clock that went backwards must not hold the latch for ever)
            if (now >= s.foodDryUntil || now < s.foodDryUntil - LATCH_TICKS) {
                s.foodDryOn = false;
                s.foodSearchSince = -1;
                return "food dry spell over, trying the hunt again";
            }
            return null;
        }
        if (!searching || prey) {
            s.foodSearchSince = -1;
            return null;
        }
        if (s.foodSearchSince < 0 || now < s.foodSearchSince) {
            s.foodSearchSince = now;
        }
        if (now - s.foodSearchSince < SEARCH_TICKS) {
            return null;
        }
        s.foodDryOn = true;
        s.foodDryUntil = now + LATCH_TICKS;
        s.foodSearchSince = -1;
        return "food dry spell: no animal loaded after " + SEARCH_TICKS / 20 + " s of searching, food waits " + LATCH_TICKS / 20
                + " s behind the next kit step";
    }

    // food steps back only while the spell is on and nothing edible is around
    public static boolean defers(boolean latched, boolean prey) {
        return latched && !prey;
    }

    // the leading food needs go behind the first need that is not food. all food (or no food up front) stays as it is,
    // there is nothing else to do with the time
    static List<KitNeed> defer(List<KitNeed> needs) {
        int lead = 0;
        while (lead < needs.size() && KitNeed.FOOD.equals(needs.get(lead).catalogueName())) {
            lead++;
        }
        if (lead == 0 || lead == needs.size()) {
            return needs;
        }
        List<KitNeed> out = new ArrayList<>(needs.size());
        out.add(needs.get(lead));
        out.addAll(needs.subList(0, lead));
        out.addAll(needs.subList(lead + 1, needs.size()));
        return out;
    }
}
