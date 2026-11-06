package adris.altoclef.util.helpers;

// does filling a bucket need a slot of its own. water and lava buckets stack to 1, so filling one out of a stack of empties
// hands the full one to a NEW slot, and vanilla throws it on the floor when there is none. a lone empty bucket is swapped
// in place and needs nothing
public final class BucketFillRules {

    private BucketFillRules() {
    }

    // GO = fill now, MAKE_ROOM = free a slot first, HOLD = do not fill at all
    public enum Fill { GO, MAKE_ROOM, HOLD }

    // bucketStacks: the size of every stack of empty buckets we carry (0s are fine, they count as no stack). hasFreeSlot is
    // a free slot in the 36 main and hotbar slots, which is where Inventory.add looks (armor and offhand do not count)
    public static boolean needsFreeSlot(int[] bucketStacks, boolean hasFreeSlot) {
        if (hasFreeSlot) return false;
        // any stack of 2+ will do: forceEquipItem takes the first stack it finds, which may be that one
        for (int count : bucketStacks) {
            if (count > 1) return true;
        }
        return false;
    }

    // 15 s of holding before we start giving buckets away: long enough for a slot to turn up by itself (a meal, a placed
    // block), short enough that nobody mistakes it for a freeze
    public static final int HOLD_DROP_TICKS = 300;

    // WAIT = keep holding, DROP_SPARES = throw every bucket but one out of the stack in hand (all in one go: a thrown
    // bucket is back in the stack when its 40 tick pickup delay ends, so a slow trickle never gets the stack down),
    // FILL_IN_PLACE = the hand holds a lone bucket, which is swapped for the full one with no new slot, so fill
    public enum Hold { WAIT, DROP_SPARES, FILL_IN_PLACE }

    // heldBuckets: size of the bucket stack in hand (0 = none), heldTicks: how long the hold has lasted
    public static Hold holdStep(int heldBuckets, int heldTicks) {
        if (heldTicks < HOLD_DROP_TICKS || heldBuckets < 1) return Hold.WAIT;
        return heldBuckets == 1 ? Hold.FILL_IN_PLACE : Hold.DROP_SPARES;
    }

    // how many to throw from a stack of that size: never the last one
    public static int spares(int heldBuckets) {
        return Math.max(0, heldBuckets - 1);
    }

    // roomStuck = we already tried to free a slot and there is nothing we may throw. a thrown water bucket is still a
    // water bucket and gets picked up again, so water fills anyway. a thrown lava bucket lands right beside the lake we
    // filled it from, where the drop rule may well write it off, and the empty bucket it came from is gone with it: lava holds
    public static Fill plan(int[] bucketStacks, boolean hasFreeSlot, boolean roomStuck, boolean lava) {
        if (!needsFreeSlot(bucketStacks, hasFreeSlot)) return Fill.GO;
        if (!roomStuck) return Fill.MAKE_ROOM;
        return lava ? Fill.HOLD : Fill.GO;
    }

    // MAKE_ROOM for this long and the room task is not getting anywhere (a throw that lands in pickup range comes back
    // after its 40 tick delay), so stop waiting on it
    public static final int ROOM_GIVE_UP_TICKS = 200;
    // a gap this long without MAKE_ROOM is a real break, shorter ones are just the free slot a throw leaves for a tick
    public static final int ROOM_GAP_TICKS = 40;

    // since = tick this stretch of MAKE_ROOM began, now = this tick
    public static boolean roomTimedOut(long since, long now) {
        return now - since >= ROOM_GIVE_UP_TICKS;
    }

    // true when the stretch last seen at `last` is over, so a MAKE_ROOM at `now` starts a fresh one
    public static boolean roomStretchBroke(long last, long now) {
        return now - last >= ROOM_GAP_TICKS;
    }
}
