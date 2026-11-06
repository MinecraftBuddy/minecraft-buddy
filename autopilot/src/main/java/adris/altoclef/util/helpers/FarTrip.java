package adris.altoclef.util.helpers;

// the buddy's fork, Tuning far_box (off by default, steering 89): the spawn trips of the far pillbox (BlazePillbox.planFar).
// the box stands outside the spawner's range, so the spawner spawns only while the buddy is out on a trip: a walk into
// range to the trip's cell, a short wait there until a blaze has spawned and seen the buddy, then back into the box, where
// the lure draws it to the opening. pure: the rods task feeds it the clock and what it sees, it answers go, carry on or back.
// one trip at a time; the clocks are game ticks
public final class FarTrip {
    // no trip while a live blaze is within this of the box's cell: the lure has one to work on
    public static final double NEAR_BOX = 16;
    // a blaze this close to the buddy on a trip counts as drawn, line or not
    public static final double CLOSE = 6;

    // the defaults; each one a tuning in seconds or hit points (Numbers.tuned)
    static final double QUIET_S = 10;
    static final double WAIT_S = 10;
    static final double CAP_S = 20;
    static final double GO_HP = 18;
    static final double BACK_HP = 16;
    static final int DRAW = 1;
    static final int DRY = 8;

    // quiet: no blaze about for this long, and as long since the last trip, before the next (ticks); wait: at the trip's cell
    // at most this long (ticks); cap: the whole trip at most this long, the walks in it (ticks); goHp: a trip starts only at
    // this health or more; backHp: below it the trip is over at once; draw: this many blazes drawn ends the trip; dry: this
    // many trips in a row with none drawn and the far box is given up
    public record Numbers(long quiet, long waitTicks, long cap, float goHp, float backHp, int draw, int dry) {
        public static final Numbers DEFAULT = new Numbers(ticks(QUIET_S), ticks(WAIT_S), ticks(CAP_S), (float) GO_HP, (float) BACK_HP, DRAW, DRY);

        // the rig's TUNE_FAR_TRIP_* values (seconds, hit points, blazes, trips)
        public static Numbers tuned() {
            return new Numbers(ticks(Tuning.get("far_trip_quiet", QUIET_S)), ticks(Tuning.get("far_trip_wait", WAIT_S)),
                    ticks(Tuning.get("far_trip_cap", CAP_S)), (float) Tuning.get("far_trip_go_hp", GO_HP),
                    (float) Tuning.get("far_trip_hp", BACK_HP), (int) Math.round(Tuning.get("far_trip_draw", DRAW)),
                    (int) Math.round(Tuning.get("far_trip_dry", DRY)));
        }
    }

    static long ticks(double seconds) {
        return Math.round(seconds * 20);
    }

    // at home (in or about the box), walking out to the trip's cell, waiting there
    public enum Stage { HOME, OUT, WAIT }

    private Stage stage = Stage.HOME;
    // since when nothing was about and no trip under way (MIN_VALUE: not yet looked)
    private long quietFrom = Long.MIN_VALUE;
    private long began, arrived;
    private int drawnMost;
    private int dryRun;

    public Stage stage() {
        return stage;
    }

    // the most blazes drawn at once on the trip under way or the last one
    public int drawnMost() {
        return drawnMost;
    }

    // trips in a row that drew none
    public int dryRun() {
        return dryRun;
    }

    public void reset() {
        stage = Stage.HOME;
        quietFrom = Long.MIN_VALUE;
        drawnMost = 0;
        dryRun = 0;
    }

    // a tick at home. about: a live blaze within NEAR_BOX of the box, or one with a line on the buddy. true: begin a trip now
    // (the caller calls begin). in the box, at goHp or more, not burning, and quiet for n.quiet; a clock that went back (a new
    // world) starts the quiet over
    public boolean wantsTrip(long now, boolean about, float hp, boolean burning, boolean inBox, Numbers n) {
        if (stage != Stage.HOME) return false;
        if (about || quietFrom == Long.MIN_VALUE || now < quietFrom) {
            quietFrom = now;
            return false;
        }
        return inBox && !burning && hp >= n.goHp() && now - quietFrom >= n.quiet();
    }

    public void begin(long now) {
        stage = Stage.OUT;
        began = now;
        drawnMost = 0;
    }

    // at the trip's cell: the wait's clock starts
    public void arrived(long now) {
        if (stage != Stage.OUT) return;
        stage = Stage.WAIT;
        arrived = now;
    }

    // a tick on a trip: why it is over, or null to carry on. drawn: the blazes drawn now (charged with a line on the buddy, or
    // within CLOSE). low health and fire first: back at once, whatever else
    public String turnBack(long now, int drawn, float hp, boolean burning, Numbers n) {
        if (stage == Stage.HOME) return null;
        drawnMost = Math.max(drawnMost, drawn);
        if (hp < n.backHp()) return "low hp";
        if (burning) return "on fire";
        if (drawn >= n.draw()) return drawn + (drawn == 1 ? " blaze" : " blazes") + " drawn";
        if (now < began) return "the clock went back";
        if (stage == Stage.WAIT && now - arrived >= n.waitTicks()) return "waited " + (now - arrived) / 20 + " s";
        if (now - began >= n.cap()) return "out " + (now - began) / 20 + " s";
        return null;
    }

    // the trip is over: home again, the quiet starts over. true when it drew none
    public boolean end(long now) {
        boolean dry = drawnMost == 0;
        dryRun = dry ? dryRun + 1 : 0;
        stage = Stage.HOME;
        quietFrom = now;
        return dry;
    }

    // n.dry trips in a row drew none: the far box is no use here
    public boolean dried(Numbers n) {
        return n.dry() > 0 && dryRun >= n.dry();
    }

    // ticks since the trip began
    public long out(long now) {
        return now - began;
    }
}
