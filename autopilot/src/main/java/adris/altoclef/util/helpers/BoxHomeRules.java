package adris.altoclef.util.helpers;

// the buddy's fork, Tuning box_home (off by default, steering 91 point 3, 7 Nov 2026): the pillbox by the blaze spawner as the
// buddy's home. It goes up first on arrival, with no wait for a wither skeleton pack (one is 2.4 high and does not fit a box
// two high, so it is a danger only in the open). Once it stands the buddy stays in it till the order's rods are in: no cream
// hunt, no wart errand, no fire walk, no hiding place elsewhere, no wander, no chase out of it; a rod is fetched only from a
// few blocks off with no blaze in sight. A heal is sealed: the look out walled from inside, the wait in the back cell, the
// wall broken again. A fire resistance potion waits for the first blaze while the buddy is in the box. A box that cannot be
// got back into is replaced by one where the buddy stands (camp_reentry's first choice), never a walk round to a corner.
// Of 60 blaze-stage deaths and stalls since 6 Nov 2026 23:43 with a box standing, 43 ended outside it after a leave never
// undone: the cream hunt 18, the run off to recover 7, the wander 7, a rod 6, a chase 5. The decisions are here, the world
// is read by the rods task, the fight and the food chain
public final class BoxHomeRules {
    // a rod lying at most this far from the box's cell (flat) is fetched, and only with no blaze in sight
    public static final double ROD_RANGE = 4;
    // a blaze this close with a line to the buddy is in sight for the rod's fetch (a blaze shoots from farther than it
    // comes, so the fetch looks wider than the fight's reach)
    public static final double SIGHT_RANGE = 24;
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

    // a rod outside the box is fetched: close to the box and no blaze in sight. one in the box is always taken
    public static boolean rodFetch(boolean rodInBox, double rodFromCell, boolean blazeInSight) {
        if (rodInBox) return true;
        return rodFromCell <= ROD_RANGE && !blazeInSight;
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

    // a fire resistance potion (traded or brewed) that would go down now waits: it goes down in the box with a blaze near,
    // when a task asks for it (the brew box drinks its brew inside), in lava, or on fire at holdHp or less
    public static boolean holdPotion(boolean asked, boolean inLava, boolean onFire, double health, double holdHp, boolean inBox, boolean blazeNear) {
        if (asked || inLava) return false;
        if (onFire && health <= holdHp) return false;
        return !(inBox && blazeNear);
    }
}
