package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationHook;

// one table, furnace or smoker this run put down. plain data so the rules (WorkbenchRules) can be tested without a game, the
// world half is Workbenches. the position and the kind come from the persisted RunState lists, everything else here is just
// what the bot has been doing about it lately and starts over after a relog (a pickup that was half done is simply decided again)
public final class Bench {
    public enum State {
        // in the world, nobody is cooking in it
        STANDING,
        // in the bag. an entry only passes through this on its way out of the registry (the log says so), the bag itself is the truth
        IN_BAG,
        // a furnace or smoker with something of ours in it (a job, or what the container tracker last saw). never picked up
        BUSY,
        // coming down right now: the block is being broken, or the drop is being collected
        PICKING_UP
    }

    public final StationHook.Kind kind;
    public final RunState.Pos pos;
    // OVERWORLD, NETHER, END
    public final String dimension;
    public State state = State.STANDING;
    public final long placedTick;
    // the last tick its screen was open, WorkbenchRules.NEVER = never
    public long lastUsedTick = WorkbenchRules.NEVER;
    // the last tick a container task said it was working at or walking to this very station (StationHook.touch), NEVER = none. not
    // saved: a relog has no task working at anything
    public long leasedTick = WorkbenchRules.NEVER;
    // the first tick of the stretch we have been further than NEAR from it, NEVER while we are inside
    public long outsideSince = WorkbenchRules.NEVER;
    // same for being in another dimension than the one it stands in
    public long otherDimensionSince = WorkbenchRules.NEVER;
    // pickups that ran out of time or could not start. MAX_TRIES of them and the entry is forgotten
    public int tries;
    // no new pickup before this tick
    public long retryAt = WorkbenchRules.NEVER;
    public long pickupStart = WorkbenchRules.NEVER;
    // the block came down and the drop is what is left to collect
    public boolean broken;
    // how many of the item were in the bag when the pickup began, it is back once there is one more
    public int bagBefore;
    // the last tick a phase ran this pickup. a pickup nobody drives is not a reason to veto anything
    public long drivenTick = WorkbenchRules.NEVER;
    // ticks the pickup really had the wheel since it began (or since the block came down). the time limits count these, not the
    // clock: a mob fight or a craft in between is not the pickup failing
    public long ranTicks;
    // the tries ran out on time alone and the block still stands: kept, and tried again once we have been away and come back near
    public boolean pickupFailed;
    public boolean leftSinceFail;
    // why the last pickup was started, and the last wait reason that went to the log (so a wait is one line, not one per tick)
    public String why = "";
    public String waitLogged = "";
    // the "busy in another dimension, keeping it" line went out for this stretch away
    public boolean elsewhereLogged;
    // the job at this station was dropped as stale (FurnaceWatch.housekeeping), so what the container tracker still remembers in it
    // is not adopted back into a new one. clears once the station is seen empty or gets a real job. not saved, a relog decides again
    public boolean givenUp;
    // the "phase ends with it still holding our items" line went out for this give up
    public boolean givenUpLogged;
    // the lastUsedTick the done-with-it decision was made for (WorkbenchRules.doneWith), NEVER = none yet
    public long doneFor = WorkbenchRules.NEVER;
    // that decision said the next job is far from it: it comes down even though the plan still wants one. a new use clears it
    public boolean leaveNow;
    // the furnace or smoker of ours cooking within NEAR of it, so we are coming back here anyway (WorkbenchRules.anchorOf). worked out
    // every look, null for none
    public Bench anchor;
    // the anchor let go: the next look decides this one again as if we had just finished with it
    public boolean redecide;
    // the "should come down but we are too far from it" line went out for this stretch away (WorkbenchRules.holdFar)
    public boolean farHeldLogged;
    // a furnace or smoker the plan brings us back to soon, and why ("to cook", "to smelt"), null when it does not
    // (WorkbenchRules.comingBack). worked out every look like the anchor
    public String comingBack;
    // the first tick of the stretch the plan has said "not coming back" / the anchor has been missing, NEVER while it holds.
    // WorkbenchRules.LET_GO_TICKS of that and it lets go
    public long comingBackLostSince = WorkbenchRules.NEVER;
    public long anchorLostSince = WorkbenchRules.NEVER;

    public Bench(StationHook.Kind kind, RunState.Pos pos, String dimension, long placedTick) {
        this.kind = kind;
        this.pos = pos;
        this.dimension = dimension;
        this.placedTick = placedTick;
    }

    // seen empty, a real job, or a screen of it open again: the give up is over, and the next one gets its own log line
    public void clearGivenUp() {
        givenUp = false;
        givenUpLogged = false;
    }

    public boolean is(StationHook.Kind k, RunState.Pos p) {
        return kind == k && pos.equals(p);
    }

    @Override
    public String toString() {
        return kind.word() + " at " + pos.x + " " + pos.y + " " + pos.z + " (" + dimension.toLowerCase(java.util.Locale.ROOT) + ", " + state + ")";
    }
}
