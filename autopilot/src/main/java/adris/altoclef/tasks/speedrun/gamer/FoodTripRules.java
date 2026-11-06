package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.util.helpers.GameClock;

// the nether has no food of its own, so when the bag runs dry out there the way back is the portal we came by and the overworld's
// own food floor (FoodFloor). pure, the phase only feeds it numbers
public final class FoodTripRules {
    // the line moves out with the walk home: a trip from 400 blocks away eats food on the way, so it has to start with more in the bag.
    // 36 at the portal (a fortress fight burns well over the old 24 just healing), plus a unit per 40 blocks of walk
    public static final int GO_HOME_BASE = 36;
    // under this the bag is nearly empty and the trip goes whatever is going on around us. same slope as the line
    public static final int FLOOR_BASE = 12;
    public static final double BLOCKS_PER_UNIT = 40;
    // nothing hostile in the way (a blaze that sees us, fire, low hp) for this long before the trip starts, a walk out of cover into
    // a fireball is how a trip kills. 3 s
    public static final int CALM_TICKS = 60;
    // below this hp the trip waits for the regen, same as burning or a blaze looking at us
    public static final float CALM_HEALTH = 14;
    // trips per nether phase entry. a world with no food in reach would otherwise ping pong forever
    public static final int MAX_TRIPS = 2;
    // an empty bag out here (under the floor) is a death sentence, so past MAX_TRIPS it still goes home, up to this many. trips
    // that left the bag short are the ones that ate the cap, the hunt up top just gets another go
    public static final int MAX_TRIPS_DRY = 4;
    // the hunt up top gets this long, then we carry on with what we have. the walk home has no flat clock, see STALL_SECONDS
    public static final double MAX_SECONDS = 480;

    public static double goHomeLine(double distToPortal) {
        return GO_HOME_BASE + distToPortal / BLOCKS_PER_UNIT;
    }

    public static double floorLine(double distToPortal) {
        return FLOOR_BASE + distToPortal / BLOCKS_PER_UNIT;
    }

    // the one count the line is read against: FoodPlan's held with nothing pending. out here raw meat is raw (the plan has no cook past
    // PORTAL, cookHere below is the phase's own and only ever fixes the bag by cooking it) and a smoker still cooking up top is not
    // food we can reach. the open screen's share is out too: meat going into a smoker mid-load reads as cooked food, and a trip
    // that ends on that walks off with the bag empty
    public static int units(FoodPlan food) {
        return bagOnly(food.held(), food.pending(), food.station());
    }

    // pure half of units. station is already inside held (it rides in on facts.foodUnits()), pending is a separate sum
    static int bagOnly(int held, int pending, int station) {
        return held - pending - station;
    }

    // no food in the bag at all is just the same line: nothing to eat means no regen once hunger drops, and the floor is far over that
    public static boolean goHome(double foodUnits, double distToPortal, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        return portalKnown && !deathTripLive && foodUnits < goHomeLine(distToPortal)
                && (tripsDone < MAX_TRIPS || (tripsDone < MAX_TRIPS_DRY && underFloor(foodUnits, distToPortal)));
    }

    public static boolean underFloor(double foodUnits, double distToPortal) {
        return foodUnits < floorLine(distToPortal);
    }

    // the walk home is many minutes, a smoker does a chop in 5 s. only when cooking clears the line, or we would cook and then walk
    // home anyway. the smoker in the bag only: a craft out here means logs. fuel for every piece up front, because a smelt that
    // comes up short goes to fetch coal, and in the nether fetching coal is the walk home this was meant to save
    public static boolean cookHere(int units, int rawGap, int raw, int fuelSmelts, boolean hasSmoker, boolean suspended, double distToPortal) {
        double line = goHomeLine(distToPortal);
        return units < line && units + rawGap >= line && raw >= CookGate.MIN_RAW && hasSmoker && !suspended && fuelSmelts >= raw;
    }

    // a cook is a minute and a half of standing still. nothing in the station spot rules knows about a drop, so a bridge of ours
    // passes them fine, and one fireball there is the whole kit in the lava. two sides open covers the bends too, and the corner
    // of a ledge isn't somewhere to stand still either
    public static boolean onABridge(boolean floorEast, boolean floorWest, boolean floorSouth, boolean floorNorth) {
        int floors = (floorEast ? 1 : 0) + (floorWest ? 1 : 0) + (floorSouth ? 1 : 0) + (floorNorth ? 1 : 0);
        return floors < 3;
    }

    // something is trying to hurt us right now. pure so the wait below can be tested
    public static boolean unsafe(boolean blazeSeesUs, boolean onFire, float health) {
        return blazeSeesUs || onFire || health < CALM_HEALTH;
    }

    // the line is crossed, may the trip start. under the floor it goes anyway, else it waits until nothing unsafe has held for
    // CALM_TICKS. lastUnsafe < 0 = never seen, a clock way back is a restart (same as the other timers), a tick or two is lag
    public static boolean mayStart(boolean underFloor, long now, long lastUnsafe) {
        return underFloor || lastUnsafe < 0 || GameClock.since(lastUnsafe, now) >= CALM_TICKS;
    }

    // the overworld half is over: the bag is back at the line, or the clock ran out
    public static boolean tripOver(boolean stillShort, double secondsOnTrip) {
        return !stillShort || secondsOnTrip >= MAX_SECONDS;
    }

    // why a trip started. each reason has its own trip counter, so a blocks trip that came back empty handed does not eat the food
    // budget and the other way round
    public enum Reason {
        FOOD, BLOCKS
    }

    // under this many throwaways the next bridge is a coin flip (the restock digs earlier, NetherRestock.LOW), and a dig that found nothing twice running
    // is not about to
    public static final int BLOCKS_LOW = 8;
    public static final int BLOCKS_EXHAUSTED_ENDS = 2;
    // one need gets MAX_SECONDS, food and blocks together get this
    public static final double TWO_NEED_SECONDS = 600;

    // the restock dig ran dry (nothing left to mine in its band) twice with a cooldown between, and the bag is still empty. the
    // dig would have been the cheap way out, so this is the lava pool case: the bridges ate the stack and the band holds nothing
    public static boolean goHomeForBlocks(int held, int exhaustedEnds, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        return portalKnown && !deathTripLive && tripsDone < MAX_TRIPS && held < BLOCKS_LOW && exhaustedEnds >= BLOCKS_EXHAUSTED_ENDS;
    }

    // the phase restock stays out of the blaze spawner: a dig swapped in there drops the rods task's mob defense and starts
    // fights with whatever is shooting. same condition the camp pinger uses. the walks to the fortress and the spawner are what
    // spend the blocks, and they are not this
    public static boolean restockAllowed(boolean campingSpawner, boolean spawnerKnown, boolean busyWithBlazes) {
        return !(campingSpawner || spawnerKnown && busyWithBlazes);
    }

    // the overworld half's clock for the needs that were short when we got up there
    public static double seconds(boolean food, boolean blocks) {
        return food && blocks ? TWO_NEED_SECONDS : MAX_SECONDS;
    }

    // the same end check with the trip's own clock. stillShort = any need left (food under the minimum, blocks under the entry stack)
    public static boolean tripOver(boolean stillShort, double secondsOnTrip, double limit) {
        return !stillShort || secondsOnTrip >= limit;
    }

    // the clock does not cut a trip whose bag is still filling, same idea as the portal hold: a new high of food units inside the
    // last RISING_SECONDS holds it. the cap is the hard stop for a trip that keeps nibbling forever
    public static final double RISING_SECONDS = 120;
    public static final double HARD_SECONDS = 900;

    public static boolean tripOver(boolean stillShort, double secondsOnTrip, double limit, double secondsSinceGain) {
        return !stillShort || secondsOnTrip >= HARD_SECONDS || secondsOnTrip >= limit && secondsSinceGain >= RISING_SECONDS;
    }

    // where a trip ends: twice the line that sent us home (so the bag is not back under it after one fight), never under the
    // overworld minimum, never past the plan's own stop. the old stop (refillStop, 130) was out of reach inside the clock and
    // the trips came back at 48 anyway. distance = nether side distance to the portal when the trip started
    public static int tripEndUnits(double distToPortal, int overworldMinimum, int planEnd) {
        int own = Math.max((int) Math.ceil(goHomeLine(distToPortal) * 2), overworldMinimum);
        return Math.min(own, planEnd);
    }

    // ---- the walk home (nether half of a trip)

    // the walk home ends when it stops getting closer, not on a flat clock: the best horizontal distance to the portal has to
    // drop STALL_BLOCKS every STALL_SECONDS. same numbers and same mark as NetherRecoverTask's portal walk. the clock only has
    // to stop a walk that is stuck, a long honest one can take as long as it likes up to the hard cap
    public static final double STALL_SECONDS = 90;
    public static final double HOME_HARD_SECONDS = 900;
    // this long without progress and an empty bag, then the walk digs itself some blocks (the bridges ate them, and a walk
    // with no blocks is the walk that stalls)
    public static final double RESTOCK_AFTER_STALL_SECONDS = 20;
    public static final int RESTOCK_BELOW = 16;
    public static final int RESTOCK_STOP_AT = 32;
    // under the food floor the bag is nearly empty and time is the thing we are short of, so the dig is shorter
    public static final int RESTOCK_STOP_AT_HUNGRY = 16;

    // the progress mark: the distance at the last time we got somewhere, and the tick that was. asked = this stall already had
    // its restock
    public record Mark(double dist, long tick, boolean asked) {
    }

    // one tick of the walk. lastSeen = the last tick the walk was ticked at all (combat, food or a cover had the wheel in
    // between if it is old), digging = the restock holds the wheel right now, so its time is pushed onto the mark and does not
    // count as standing still
    public static Mark advance(Mark mark, double dist, long lastSeen, long now, boolean digging) {
        if (mark == null || NetherTripRules.markStale(lastSeen, now) || NetherTripRules.closedIn(mark.dist(), dist)) {
            return new Mark(dist, now, false);
        }
        if (digging) {
            return new Mark(mark.dist(), mark.tick() + NetherTripRules.restockCredit(lastSeen, now), mark.asked());
        }
        return mark;
    }

    public static boolean stalled(Mark mark, long now) {
        return mark != null && now - mark.tick() >= STALL_SECONDS * 20;
    }

    // the dig is due: stuck for a bit already, throwaways under the bar, and not asked yet this stall
    public static boolean restockDue(Mark mark, long now, int held) {
        return mark != null && !mark.asked() && held < RESTOCK_BELOW && now - mark.tick() >= RESTOCK_AFTER_STALL_SECONDS * 20;
    }

    public static int restockStopAt(boolean underFloor) {
        return underFloor ? RESTOCK_STOP_AT_HUNGRY : RESTOCK_STOP_AT;
    }

    // the walk home is over: it stopped getting closer, or it ran the hard cap. either way the trip counts as used (the counter
    // went up when it started), and it is not the reason to give up on trips for good
    public static boolean walkOver(boolean stalled, double secondsOnTrip) {
        return stalled || secondsOnTrip >= HOME_HARD_SECONDS;
    }

    // trip counters belong to one visit of the nether phase. a retry is the same visit (the trip budget would otherwise refill
    // every time the watchdog restarts the phase), a fresh entry is a new one. never on a dimension change: the overworld half of
    // a trip runs inside the nether phase and coming back down is not a new visit
    public static boolean countersReset(int attempt) {
        return attempt <= 1;
    }

    // the bag alone (units, not what is still cooking) is under where the trip ends
    public static boolean tripFoodShort(int units, int tripEnd) {
        return units < tripEnd;
    }

    // a trip that ended (a stalled walk home, or the overworld half done) waits this long (60 s) before the next one may start,
    // unless the bag is under the floor. the stall comment always said the next try has to earn its start, and nothing made it;
    // a trip that came back with a full bag has no business turning around on the first hungry tick either
    public static final long COOLDOWN_TICKS = 1200;

    // endedAt < 0 = no trip has ended. a clock way back is a restart, so no cooldown either
    public static boolean coolingDown(long now, long endedAt) {
        return endedAt >= 0 && GameClock.since(endedAt, now) < COOLDOWN_TICKS;
    }

    // the phase restarted (the watchdog's retry) with a trip's overworld half in flight and the bag still short. the retry
    // used to wipe the trip and walk straight back down with nothing. same trip, so no counter is spent on it. stillShort is
    // the same read the overworld half ends on (tripOver). once per trip: a hunt that stalls again goes the old way
    public static boolean resume(boolean inOverworld, boolean tripWasUp, boolean alreadyResumed, boolean stillShort) {
        return inOverworld && tripWasUp && !alreadyResumed && stillShort;
    }

    // a food trip that brought this much into the bag (a kill or two cooked, or the smoker we left at home) did what it was for,
    // so it does not count against MAX_TRIPS: the cap is for the world with no food in reach, not for a bot that keeps running dry
    // because the nether eats it. blocks trips and trips that came back empty handed stay counted
    public static final int TRIP_GAIN_UNITS = 12;
    // refunds per nether phase entry
    public static final int MAX_REFUNDS = 3;

    public static boolean refundsTrip(Reason reason, int unitsAtStart, int unitsAtEnd) {
        return reason == Reason.FOOD && unitsAtEnd - unitsAtStart >= TRIP_GAIN_UNITS;
    }

    // food units went up since the last look: the hunt is getting somewhere, so the watchdog's clock restarts
    public static boolean gained(int lastUnits, int units) {
        return lastUnits >= 0 && units > lastUnits;
    }

    private FoodTripRules() {
    }
}
