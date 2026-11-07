package adris.altoclef.util.helpers;

// the buddy's fork, Tuning box_home (off by default, steering 91 point 3, 7 Nov 2026): the pillbox by the blaze spawner as the
// buddy's home. It goes up first on arrival, with no wait for a wither skeleton pack (one is 2.4 high and does not fit a box
// two high, so it is a danger only in the open). Once it stands the buddy stays in it till the order's rods are in: no cream
// hunt, no wart errand, no fire walk, no hiding place elsewhere, no wander, no chase out of it; a rod is fetched by a short
// trip out and straight back (rodLeft, tripEnd). A heal is sealed: the look out walled from inside, the wait in the back cell, the
// wall broken again. A fire resistance potion waits for the first blaze while the buddy is in the box. A box that cannot be
// got back into is replaced by one where the buddy stands (camp_reentry's first choice), never a walk round to a corner.
// Of 60 blaze-stage deaths and stalls since 6 Nov 2026 23:43 with a box standing, 43 ended outside it after a leave never
// undone: the cream hunt 18, the run off to recover 7, the wander 7, a rod 6, a chase 5. The decisions are here, the world
// is read by the rods task, the fight and the food chain
public final class BoxHomeRules {
    // ---- the rod trip: a rod outside the box is fetched by a trip out of it, one rod at a time, begun from inside the box.
    // the first block (realfort-pair-home, 7 Nov 2026) had a rod fetched only within 4 of the cell (flat), from 2 under its
    // floor to 1.5 over it, with no blaze within 24 having a line to the buddy. B:3 lost 9 rods, all 2.0 to 2.8 off and two
    // up (on the spawner's platform by the box and on the box's own wall): never one trip, the height said no. B:2's two rods
    // lay 3.4 to 3.7 off and one up past the box's wall: the sight flipped from tick to tick, so the pickup and the lure
    // took turns (105 pickups begun in 61 s) and the buddy never got past the door; both rods
    // despawned. so: a wider reach, a ledge two up, a gate on the buddy's health and the blazes near, and once out the trip
    // goes on till it is over (tripEnd), never undone by the next tick's look

    // a rod at most this far from the box's cell (flat), from ROD_DOWN under its floor to ROD_UP over it. a rod on a ledge
    // two up is taken from the ledge's foot: the pickup reaches half a block over the head (1.8 + 0.5)
    public static final double ROD_RANGE = 8;
    public static final double ROD_UP = 2.3;
    public static final double ROD_DOWN = 2;
    // a trip begins at this health or more, with no blaze within TRIP_CLEAR of the buddy or of the rod; under fire resistance
    // only one within the bite's reach (BlazeFight.BITE_RANGE) keeps it in. just past the fight's reach (4.5): in B:3, 33
    // blazes killed at the box, none was in that reach for 646 of the 779 s of the fight (the fight camping), while the old
    // gate's "none in sight within 24" seldom held with 5 to 13 blazes within 16
    public static final float TRIP_HP = 12;
    public static final double TRIP_CLEAR = 5;
    public static final double TRIP_CLEAR_FIREPROOF = 2.5;
    // a trip not done in this long is over and its rod let go (ticks): 8 blocks out and back at a walk is 4 s, a pillar step
    // and the path search a second or two more
    public static final long TRIP_TICKS = 200;
    // a fall in health from the trip's best by more than this is a hit: the trip is over, straight back
    public static final float HIT = 0.5f;
    // after a trip ended by a hit, the next waits this long (ticks)
    public static final long HIT_PAUSE_TICKS = 100;
    // the sealed heal is done at the full health less this (the food chain eats while the wall stands)
    public static final float FULL_MARGIN = 1.0f;
    // and given up after this long sealed, or with no rise in health for this long (no food, the wither): game ticks
    public static final long HEAL_MAX_TICKS = 2400;
    public static final long NO_RISE_TICKS = 600;

    private BoxHomeRules() {
    }

    public static boolean on() {
        return Tuning.get("box_home", 0) > 0;
    }

    // the box holds the buddy: it stands and the order's rods are not all in yet
    public static boolean holds(boolean boxStands, int rods, int rodsWanted) {
        return boxStands && rods < rodsWanted;
    }

    // why the closest rod outside the box is left this tick, null when a trip for it begins (a rod in the box's cells is
    // always taken, never asked here). flat is from the box's cell, up from its floor (negative: down); burning a rod on fire
    // or in lava (it burns within ticks: 5 of B:3's 9 were gone within 2 s), letGo one whose trip found no way or ran out of
    // time; blaze the nearest living blaze to the buddy or the rod; sinceHit the ticks since a trip ended by a hit (a large
    // number with none). inBox: trips begin from inside the box only, so a hit or the trip's end always goes home first
    public static String rodLeft(double flat, double up, boolean burning, boolean letGo, boolean inBox, float hp, double blaze,
                                 boolean fireproof, long sinceHit) {
        if (burning) return "on fire";
        if (letGo) return "let go";
        if (flat > ROD_RANGE) return "far";
        if (up > ROD_UP) return "high";
        if (up < -ROD_DOWN) return "low";
        if (!inBox) return "out of the box";
        if (hp < TRIP_HP) return "hp";
        if (blaze <= (fireproof ? TRIP_CLEAR_FIREPROOF : TRIP_CLEAR)) return "blaze";
        if (sinceHit >= 0 && sinceHit < HIT_PAUSE_TICKS) return "hit";
        return null;
    }

    // why a trip out for a rod is over, null while it goes on: the rod in the bag or gone, burning, no way to it (the pickup's
    // own no-path ban), a hit, or too long
    public static String tripEnd(boolean gone, boolean taken, boolean burning, boolean reachable, boolean hit, long ticks) {
        if (gone) return taken ? "picked up" : "gone";
        if (burning) return "on fire";
        if (!reachable) return "no way to it";
        if (hit) return "hit";
        if (ticks > TRIP_TICKS) return "not reached in " + TRIP_TICKS / 20 + " s";
        return null;
    }

    // a rod whose trip ended so is not tried again from this box: no way to it, or not reached in time. one left on a hit is
    // tried again once the gate opens (rodLeft)
    public static boolean letGoAfter(String end) {
        return end != null && (end.equals("no way to it") || end.startsWith("not reached"));
    }

    // a fire by the spawner is put out from inside: one in the box's own cells, or in its door (the cell outside the look
    // out, within reach from it). any other is left, the walk to it being a walk out into the blazes' sight
    public static boolean fireOut(boolean inBoxCells, boolean inDoor) {
        return inBoxCells || inDoor;
    }

    // the fight's KILL lets its blaze go once the buddy is past the leash from the box's cell: with box_home whatever the
    // blaze (one that bites is fought from inside, at the opening), else only one beyond the bite's reach
    public static boolean letGo(boolean home, double fromCell, double leash, boolean beyondBite) {
        return fromCell > leash && (home || beyondBite);
    }

    // a heal run goes into the box from anywhere with box_home (the box's cell the fight's corner); without it only from
    // within the run's corner range
    public static boolean runHome(boolean home, boolean boxIsCorner, boolean nearCorner) {
        return nearCorner || (home && boxIsCorner);
    }

    // a heal begins sealed: the box holds the buddy, the fight is healing, the buddy is in the box, no brew box is under way,
    // and the bag has the blocks the wall takes
    public static boolean sealHeal(boolean holds, boolean healing, boolean inBox, boolean brewBusy, int blocksInBag, int blocksNeeded) {
        return holds && healing && inBox && !brewBusy && blocksInBag >= blocksNeeded;
    }

    // why a sealed heal is over, null while it goes on: full, no rise, or too long
    public static String healDone(float health, float max, long sealedTicks, long sinceRiseTicks) {
        if (health >= max - FULL_MARGIN) return "full";
        if (sealedTicks > HEAL_MAX_TICKS) return "sealed " + HEAL_MAX_TICKS / 20 + " s";
        if (sinceRiseTicks > NO_RISE_TICKS) return "no rise in " + NO_RISE_TICKS / 20 + " s";
        return null;
    }

    // ---- the box given up (realfort-pair-home2 B:1, 7 Nov 2026): the box was picked 120 blocks off the spawner and its
    // 2400-tick clock ran out on the walk there (a hoglin, a wither skeleton, a blaze run, a stuck loop), not one block
    // placed; the give-up barred boxes at that spawner, and the buddy fought in the open and died. so the box's clock starts
    // at its cell, and a box given up is not the end: the next one goes up where the buddy stands (camp_reentry's
    // pillboxHere), at most RETRIES more at one spawner. the far box (far_box) has its own way back, the near camp

    // boxes tried again at one spawner after a give-up
    public static final int RETRIES = 2;
    // a box where the buddy stands is looked for this often while a try is owed (ticks): a cell's plans are not free
    public static final long RETRY_LOOK_TICKS = 20;

    // the try a give-up leaves at a spawner with made tries made there already: 1..RETRIES, or 0 for none (no box there any
    // more, the camp as without one)
    public static int retry(boolean farBox, int made) {
        if (farBox || made >= RETRIES) return 0;
        return made + 1;
    }

    // a fire resistance potion (traded or brewed) that would go down now waits: it goes down in the box with a blaze near,
    // when a task asks for it (the brew box drinks its brew inside), in lava, or on fire at holdHp or less
    public static boolean holdPotion(boolean asked, boolean inLava, boolean onFire, double health, double holdHp, boolean inBox, boolean blazeNear) {
        if (asked || inLava) return false;
        if (onFire && health <= holdHp) return false;
        return !(inBox && blazeNear);
    }
}
