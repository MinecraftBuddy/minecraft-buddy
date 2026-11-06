package adris.altoclef.tasks.construction;

import java.util.EnumSet;
import java.util.Set;

// the bookkeeping of PlaceStationTask with the game taken out: which step we are in, how many tries have gone wrong,
// how many times we gave up on the neighbourhood. the old task had its fail count on a per-spot object that got thrown
// away with the spot, so "fail 4 times, try something else" never got to 4. pure so it tests without a world
final class StationAttempt {
    // a click that has not made a block appear by now is not going to
    static final int VERIFY_TICKS = 10;
    // the client puts the block there the moment we click, the server can take it back a few ticks later. it has to stay
    // this long before we believe it
    static final int SETTLE_TICKS = 3;
    // aiming is a few ticks (equip, turn, wait for the crosshair). more than this and the spot is not clickable
    static final int AIM_TICKS = 20;
    // tries on one patch of floor before we go somewhere else
    static final int TRIES_BEFORE_RELOCATE = 3;
    static final int MAX_RELOCATIONS = 2;
    // an empty pick has to hold this long, on the ground, before it costs a walk
    static final int NO_SPOT_TICKS = 10;
    // holes we dig for room before we try walking somewhere else. a hole that didn't help, it's the wall's fault, not ours
    static final int MAX_CARVES = 3;
    // the swim back to the last dry land, only this far. past that it is an ocean crossing, not a detour for a crafting table
    static final double DRY_LAND_RANGE = 512;

    // where a relocate goes: the bank (afloat), a better patch of floor, back to where we last stood on land, or nowhere
    enum Move { SHORE, STANDPOINT, DRY_LAND, NONE }

    enum Phase {
        // choose a spot
        PICK,
        // nothing usable around and no better floor to walk to: mine a block out of the wall beside us and put the station in
        // the hole, like a player in a tunnel would
        CARVE,
        // equip, turn, wait for the crosshair to land on the face, click
        AIM,
        // clicked, waiting for the block to show up and stay
        VERIFY,
        // walking to a better patch of floor
        RELOCATE,
        // the player way is out of ideas, let PlaceBlockNearbyTask have it. the only phase that pillars and bridges
        FALLBACK,
        DONE
    }

    private Phase phase = Phase.PICK;
    private int tries;
    private int relocations;
    private int carves;
    private int phaseTicks;
    private int totalTicks;
    private int noSpotTicks;
    private int landedTicks;
    // every phase we have been in, for how()
    private final Set<Phase> reached = EnumSet.of(Phase.PICK);
    // why the player way ran dry, for the log line when the old placer takes over
    private String fallbackWhy = "";

    Phase phase() {
        return phase;
    }

    int tries() {
        return tries;
    }

    int relocations() {
        return relocations;
    }

    int phaseTicks() {
        return phaseTicks;
    }

    int carves() {
        return carves;
    }

    String fallbackWhy() {
        return fallbackWhy;
    }

    // once per task tick
    void tick() {
        phaseTicks++;
        totalTicks++;
    }

    // task ticks since the attempt began, for the line that says how long a placement took
    int totalTicks() {
        return totalTicks;
    }

    // the furthest we had to go to get the station down: pick (the first look worked), relocate (walked), carve (dug), fallback
    String how() {
        if (reached.contains(Phase.FALLBACK)) {
            return "fallback";
        }
        if (reached.contains(Phase.CARVE)) {
            return "carve";
        }
        return reached.contains(Phase.RELOCATE) ? "relocate" : "pick";
    }

    private void enter(Phase next) {
        reached.add(next);
        phase = next;
        phaseTicks = 0;
        noSpotTicks = 0;
        landedTicks = 0;
    }

    void picked() {
        enter(Phase.AIM);
    }

    // nothing usable in reach. walk to open ground first (free, and the next pick is the cheap one), then dig out some room
    // (one wall block, which is the right answer in a one wide tunnel), then the old placer. the walk and the dig both used to
    // be the other way round, and a dig is a block we mined for a station we could have put down in the next room
    void noSpot() {
        if (relocations < MAX_RELOCATIONS) {
            walk();
        } else if (carves < MAX_CARVES) {
            enter(Phase.CARVE);
        } else {
            fallbackWhy = "nothing usable, " + relocations + " walks and " + carves + " holes dug didn't help";
            enter(Phase.FALLBACK);
        }
    }

    // one pick came back empty. a walk only follows an answer that held for NO_SPOT_TICKS grounded ticks in a row: a hop, a
    // knockback or a mob standing in the only cell is a bad answer for a moment, not a bad patch of floor. a tick in the air
    // doesn't count and starts the wait over. a steady swim counts as grounded, the caller says so (onGround is never true in
    // deep water, so the wait would never run out). the digs and the old placer come after the walks and don't wait
    void noSpot(boolean grounded) {
        if (relocations >= MAX_RELOCATIONS) {
            noSpot();
            return;
        }
        noSpotTicks = grounded ? noSpotTicks + 1 : 0;
        if (noSpotTicks >= NO_SPOT_TICKS) {
            noSpot();
        }
    }

    // grounded empty picks in a row, for the log line that says the first one
    int noSpotTicks() {
        return noSpotTicks;
    }

    // nothing around us that is worth (or safe) to mine out, and the walks are used up: the last stop before the old placer
    void noCarve() {
        fallbackWhy = "nothing usable, nothing safe to mine out for room, and already moved " + relocations + " times";
        enter(Phase.FALLBACK);
    }

    // the hole is dug (or the digging timed out, same thing: look at what we've got now). counts either way so a wall that
    // won't give can't keep us here
    void carveDone() {
        if (phase == Phase.CARVE) {
            carves++;
            enter(Phase.PICK);
        }
    }

    boolean aimTimedOut() {
        return phase == Phase.AIM && phaseTicks >= AIM_TICKS;
    }

    // the click went out
    void clicked() {
        enter(Phase.VERIFY);
    }

    // one tick of watching the cell. the block has to stay SETTLE_TICKS, a flicker resets it
    void verify(boolean blockThere) {
        if (phase != Phase.VERIFY) {
            return;
        }
        landedTicks = blockThere ? landedTicks + 1 : 0;
        if (landedTicks >= SETTLE_TICKS) {
            enter(Phase.DONE);
        } else if (phaseTicks >= VERIFY_TICKS) {
            failed();
        }
    }

    // this try is lost (no hit, no block, click refused). the caller bans the spot
    void failed() {
        tries++;
        if (tries >= TRIES_BEFORE_RELOCATE) {
            giveUpOnThisPatch(TRIES_BEFORE_RELOCATE + " clicks lost on this patch");
        } else {
            enter(Phase.PICK);
        }
    }

    private void walk() {
        relocations++;
        tries = 0;
        enter(Phase.RELOCATE);
    }

    private void giveUpOnThisPatch(String why) {
        if (relocations < MAX_RELOCATIONS) {
            walk();
        } else {
            fallbackWhy = why + ", and already moved " + relocations + " times";
            enter(Phase.FALLBACK);
        }
    }

    // nowhere better to stand within reach of here. asking again would find the same nothing, so the walks are spent and the
    // dig is next
    void noStandpoint() {
        relocations = MAX_RELOCATIONS;
        if (carves < MAX_CARVES) {
            enter(Phase.CARVE);
        } else {
            fallbackWhy = "nowhere better to stand within " + StationSpots.MAX_MOVE + " blocks, and " + carves + " holes dug didn't help";
            enter(Phase.FALLBACK);
        }
    }

    // the bank and the floor around us first, they are close. the dry land is the way out when we are wet and both came back
    // empty, which is open sea with the shore past the 32 the bank search looks at. once per attempt: we went, we looked, if
    // that did not help another swim to the same place will not either. dryLandDistance < 0 = we never stood on land here
    static Move relocateMove(boolean shore, boolean standpoint, boolean inWater, double dryLandDistance, boolean dryLandUsed) {
        if (shore) {
            return Move.SHORE;
        }
        if (standpoint) {
            return Move.STANDPOINT;
        }
        if (inWater && !dryLandUsed && dryLandDistance >= 0 && dryLandDistance <= DRY_LAND_RANGE) {
            return Move.DRY_LAND;
        }
        return Move.NONE;
    }

    // we are at the new patch (or the walk timed out, same thing: look again from where we are)
    void arrived() {
        if (phase == Phase.RELOCATE) {
            tries = 0;
            // new walls, new holes to try
            carves = 0;
            enter(Phase.PICK);
        }
    }
}
