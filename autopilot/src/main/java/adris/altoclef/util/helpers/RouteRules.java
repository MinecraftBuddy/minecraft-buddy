package adris.altoclef.util.helpers;

// the buddy's fork: the route through the nether that an order of blaze rods plays by default (steering 118 to 120). seven
// phases in a fixed order: 1 into the nether, 2 the fire resistance trade, 3 the wart and the cream, 4 the first two rods
// under the traded potion, 5 the brew shut in the pillbox, 6 the other rods under the brewed potions, 7 out through a portal.
// the rig scores the phases from the lines this class words ("route: phase N WORD"), so they stay exactly as they are. a
// phase's line is said once, when the route first reaches it; a phase skipped says nothing and the next one says its own.
// the exit's state is static: the buddy's own order (Obtain) asks it before it calls an order of rods done in the nether
public final class RouteRules {
    private RouteRules() {
    }

    // the phases' words, phase 1 first
    static final String[] WORDS = {"nether", "trade", "wart", "rods2", "brew", "rods", "exit"};

    public static final int NETHER = 1;
    public static final int TRADE = 2;
    public static final int WART = 3;
    public static final int RODS2 = 4;
    public static final int BREW = 5;
    public static final int RODS = 6;
    public static final int EXIT = 7;

    public static final String NO_POTION = "route: no potion, the fallback";
    // the errand's dead end without the cream or the wart: no brew, so the rods past the first two come without it
    public static final String NO_BREW = "route: no brew, the fallback";
    public static final String OUT = "route: out of the nether";

    // the line of phase n (1 to 7)
    public static String line(int phase) {
        if (phase < 1 || phase > WORDS.length) throw new IllegalArgumentException("no route phase " + phase);
        return "route: phase " + phase + " " + WORDS[phase - 1];
    }

    // whether phase is said now, the route having said up to last (0 for none yet): only a phase past the last
    public static boolean says(int last, int phase) {
        return phase > last;
    }

    // ---- phase 3, the errand (Tuning route_errand, on by default; route jar 2, 8 Nov 2026). the first route jar gave up the
    // cream after 60 s of waiting by the spawner and never fetched the wart (the cream came first, the wart only after it):
    // phase 4 began with neither in two runs of two. now the errand wants both at once, takes what is in sight (a cube first,
    // it moves; then the wart the tracker knows), and with nothing in sight walks round the spawner in rings to bring more
    // of the fortress into view. it ends only with both in the bag or at a dead end

    // the rings the errand walks round the spawner while nothing is in sight: this far apart, this many points a ring, this
    // many rings, a point given this long (game ticks) before the next
    public static final double ERRAND_RING_STEP = 20;
    public static final int ERRAND_RING_POINTS = 8;
    public static final int ERRAND_RINGS = 3;
    public static final int ERRAND_POINTS = ERRAND_RING_POINTS * ERRAND_RINGS;
    public static final long ERRAND_POINT_TICKS = 20 * 20;
    // a point counts as reached within this many blocks of its column
    public static final double ERRAND_POINT_NEAR = 4;
    // the dead end's clock (Tuning route_errand_dead_s): this long with neither the cream nor the wart got, counted from the
    // spawner first known (game seconds; the trade's dead end is 600 s too)
    public static final double ERRAND_DEAD_S = 600;
    // one cube is hunted at most this long, one wart block fetched at most this long, then let go for another (game seconds)
    public static final double CUBE_HOLD_S = 40;
    public static final double WART_HOLD_S = 90;

    public static boolean errandOn() {
        return Tuning.get("route_errand", 0) > 0;
    }

    // the k-th point of the errand's walk (0 first), round the spawner at sx, sz, the first of each ring at angle from
    // (radians: the buddy's own side, so the walk starts near). the rings ERRAND_RING_STEP apart, nearest first
    public static int[] errandPoint(int k, double sx, double sz, double from) {
        int ring = 1 + Math.floorMod(k, ERRAND_POINTS) / ERRAND_RING_POINTS;
        double r = ERRAND_RING_STEP * ring;
        double a = from + Math.floorMod(k, ERRAND_RING_POINTS) * (2 * Math.PI / ERRAND_RING_POINTS);
        return new int[]{(int) Math.round(sx + r * Math.cos(a)), (int) Math.round(sz + r * Math.sin(a))};
    }

    // why the errand stops at a dead end, null to go on: every point of the rings walked with nothing to hunt or fetch left,
    // or deadTicks with neither item got since the spawner was known (sinceProgress counts only once it is)
    public static String errandDeadEnd(int pointsWalked, long sinceProgress, long deadTicks) {
        return errandDeadEnd(pointsWalked, sinceProgress, deadTicks, 1);
    }

    // (Tuning route_errand_rounds, 1 by default; route jar 11's B arm 3, 9 Nov 2026) the rings walked this many times before
    // the dead end, and the clock of neither got that many times as long. route jar 8 ended 18 errands at the rings' dead end
    // after 444 to 1010 s, cream 0 in 17 of them (wart in 12), while the 13 creams it got came after 25 to 1118 s, about
    // one per 22 minutes of errand: the cubes of a fortress come and go, so a round more looks at new ones
    public static int errandRounds() {
        return Math.max(1, (int) Math.round(Tuning.get("route_errand_rounds", 1)));
    }

    public static String errandDeadEnd(int pointsWalked, long sinceProgress, long deadTicks, int rounds) {
        if (pointsWalked >= ERRAND_POINTS * rounds) return "the " + ERRAND_RINGS + " rings round the spawner walked"
                + (rounds > 1 ? " " + rounds + " times" : "") + ", nothing left in sight";
        if (sinceProgress >= deadTicks * rounds) return "neither got in " + Math.round(deadTicks * rounds / 20.0) + " s at the fortress";
        return null;
    }

    // ---- the dead end's clock at the fortress only (Tuning route_errand_near, on by default; route jar 6, 9 Nov 2026). route
    // jar 5's clock ran from the spawner first known, wherever the buddy was: route jar 5's run 1 began the errand 205 blocks
    // from the spawner, drifted 620 blocks off on the wart's wander and met the 600 s dead end without ever standing at the
    // fortress. now it runs only within ERRAND_NEAR blocks of the spawner: the outer ring is 60 blocks out and the walk
    // between its points bulges past it, and a fortress's wart rooms and halls lie mostly within that of its spawner. the
    // ring walk's own point clock still runs anywhere, so a buddy that never gets near still meets the rings' dead end
    public static final double ERRAND_NEAR = 80;

    // whether the dead end's clock runs this tick: the spawner known and the buddy within near blocks of it. near 0 or less
    // is route jar 5's way: anywhere once the spawner is known
    public static boolean errandClockRuns(boolean spawnerKnown, double toSpawner, double near) {
        if (!spawnerKnown) return false;
        return near <= 0 || toSpawner <= near;
    }

    // ---- the wart walk (Tuning route_wart_walk, on by default; route jar 6). route jar 5 handed a wart the tracker knew to the
    // general mining task, which takes only a block in a loaded chunk: a wart room 174 blocks off was none to it, so it
    // wandered (11 times in 10 minutes), and the 90 s clock gave up one wart of the room after another while the buddy
    // drifted away from it. now the errand walks to the wart itself: one block held till it is got or given up, a walk to
    // within WART_STAND of it, and the break once within WART_BREAK_NEAR with its chunk loaded; the drop is picked up as
    // before. a wart is given up only when its stand is not reached: no step closer (the nearest the buddy has been, down
    // by WART_STEP) in WART_STALL_S of the walk's own ticks, or not broken in WART_BREAK_S within WART_BREAK_NEAR. a wart not
    // reached takes the others within WART_ROOM of it with it: one room, one way in
    public static final int WART_STAND = 2;
    public static final double WART_STEP = 1;
    public static final double WART_STALL_S = 60;
    public static final double WART_BREAK_NEAR = 5;
    public static final double WART_BREAK_S = 30;
    public static final double WART_ROOM = 8;
    // and the walk to one wart has WART_WALK_S of its own ticks in all, steps closer or not (route jar 15, 9 Nov 2026): a fight
    // or the ghast shelter in between starts the nearest again from where the walk goes on, so a wart with no way in, the
    // walk pulled off by a ghast every few seconds, came a step closer after each pull and was never given up (look 37,
    // route-9 run 7: about 9 minutes round one wart, 21 path failures)
    public static final double WART_WALK_S = 150;

    public static boolean wartWalkOn() {
        return Tuning.get("route_wart_walk", 0) > 0;
    }

    // a step closer to the wart: the distance now (blocks) at least WART_STEP below the nearest so far
    public static boolean wartCloser(double best, double now) {
        return now <= best - WART_STEP;
    }

    // why the wart held is given up, null to go on. stallTicks: the walk's own ticks since its last step closer, out of the
    // break's range; nearTicks: its own ticks within it, the block still standing; walkTicks: all its own ticks out of the
    // break's range; the caps in game ticks
    public static String wartGiveUp(long stallTicks, long nearTicks, long walkTicks, long stallCap, long breakCap, long walkCap) {
        if (nearTicks >= breakCap) return "not broken in " + Math.round(breakCap / 20.0) + " s at its stand";
        if (stallTicks >= stallCap) return "its stand not reached, no step closer in " + Math.round(stallCap / 20.0) + " s";
        if (walkTicks >= walkCap) return "its stand not reached in " + Math.round(walkCap / 20.0) + " s of walking";
        return null;
    }

    public static String wartGiveUp(long stallTicks, long nearTicks, long stallCap, long breakCap) {
        return wartGiveUp(stallTicks, nearTicks, 0, stallCap, breakCap, Long.MAX_VALUE);
    }

    // ---- the wart near the spawner (Tuning route_wart_near, the distance, WART_NEAR by default, 0 off; route jar 8, 9 Nov 2026).
    // the block tracker keeps the wart of earlier runs on the same lane, and route jar 6's walk goes to any of it: realfort-
    // route-5's t4 run 38 walked for a wart 10132 blocks off that run 1 of its lane broke, t2 run 9 for one 3465 off and died
    // in lava on the way; the walk's give-up never fires on a long walk that keeps getting closer. a fortress's wart rooms
    // lie well within 150 of its spawner, so a wart farther from the spawner the errand works from (from the buddy while
    // none is known) is no target; the tracker's memory is kept as it is
    public static final double WART_NEAR = 150;

    public static double wartNear() {
        return Tuning.get("route_wart_near", 0);
    }

    // whether a wart this far from the anchor (the spawner, else the buddy) may be the errand's target
    public static boolean wartInReach(double fromAnchor, double near) {
        return near <= 0 || fromAnchor <= near;
    }

    // ---- the far pass for the wart (Tuning route_wart_far, off; 11 Nov 2026). WART_NEAR's "a fortress's wart rooms lie well
    // within 150 of its spawner" does not hold on every fortress: of 20 whole-route runs that began phase 4 without wart
    // (11 Nov 2026, 03:00-09:00), 6 had none within 150 of the spawner, its rooms 215 to 520 off (two fortresses of the
    // drill's fifteen), and the rings (32 to 64 round the spawner) never brought them into view. so the dead end, with the
    // wart still wanted, first begins the far pass: the fortress's brick chunks explored from where the buddy stands, a wart
    // within WART_FAR of the spawner the target (the tracker's stale wart of other fortresses lies thousands off), for
    // WART_FAR_S of game time at most; then the dead end as before. a wart given up before stays given up
    public static final double WART_FAR = 600;
    public static final double WART_FAR_S = 300;

    public static boolean wartFarOn() {
        return Tuning.get("route_wart_far", 0) > 0;
    }

    // whether a dead end begins the far pass instead: the switch on, the wart still wanted, the kit there, no far pass yet
    public static boolean wartFarBegins(boolean on, boolean wantWart, boolean kit, boolean begun) {
        return on && wantWart && kit && !begun;
    }

    // ---- the potion at a guarded wart (Tuning route_wart_drink, off; his yes of 11 Nov 2026, steering 157). of 20 whole-route
    // runs that began phase 4 without wart (11 Nov 2026, 03:00-09:00), 12 had seen it and given it up: blazes by it (the wart
    // room by the spawner) set off phase 3's walk-away every second or two, at the edge of their sight, so the walk never got
    // closer. the traded potion is kept for the spawner; at such a wart it goes down there instead: the walk then goes on
    // with no walk-away (a blaze's fireball does nothing under it), the wart is taken and the spawner fight follows under the
    // same potion. a wart counts as guarded once the walk to it has been broken off by WART_DRINK_AWAYS walk-aways, within
    // WART_DRINK_NEAR of it (64 in the first smoke, 11 Nov 2026: the walks the blazes held off were 82 to 137 from the wart
    // in three of its four, the 20 runs read before gave up at 19 to 94; at 100 the walk on under the potion takes half a
    // minute or so of its three)
    public static final int WART_DRINK_AWAYS = 3;
    public static final double WART_DRINK_NEAR = 100;

    public static boolean wartDrinkOn() {
        return Tuning.get("route_wart_drink", 0) > 0;
    }

    // whether the potion goes down at the wart now: the switch on, aways walk-aways since the wart was picked, d blocks off
    // it, a potion in the bag and none in effect
    public static boolean wartDrinks(boolean on, int aways, double d, boolean potion, boolean underIt) {
        return on && aways >= WART_DRINK_AWAYS && d <= WART_DRINK_NEAR && potion && !underIt;
    }

    // the far pass's own dead end, null to go on: sinceTicks of game time since it began against capTicks
    public static String wartFarDeadEnd(long sinceTicks, long capTicks) {
        return sinceTicks >= capTicks ? "the far pass along the fortress, no wart got in " + Math.round(capTicks / 20.0) + " s" : null;
    }

    // ---- phase 3 out of the blaze camp (route jar 8). realfort-route-5 lost 8 of 31 runs in phase 3 to the spawner's blazes
    // and their wither skeletons, 16 to 65 blocks from the spawner, 7 of 8 with the potion still kept for phase 4: the fight
    // and the hiding place took the tick, so the errand's clock (its own ticks) stood still and its dead end never came. t8's
    // run 27 walked its 24th ring point at 04:37:00, fought for 16.7 minutes with no dead end, burned at 04:53:37; the dead
    // end was said after the death.
    // (Tuning route_errand_clock, on by default) the dead end's clock counts game time each tick while the errand is the
    // order, whoever has the tick, and the dead end is asked each tick before the fight's
    public static boolean errandClockOn() {
        return Tuning.get("route_errand_clock", 0) > 0;
    }

    // the clock's step this tick: game time since the last tick it was asked (gap), whole with the switch on, at most
    // BrewErrandRules.GAP_MAX (the errand's own ticks) with it off; a clock that went back counts nothing
    public static long errandClockStep(long gap, boolean gameTime) {
        if (gap <= 0) return 0;
        return gameTime ? gap : Math.min(gap, BrewErrandRules.GAP_MAX);
    }

    // (Tuning route_ring_out, the first ring's radius, RING_OUT by default, 0 off) the rings round the spawner out of its blazes'
    // reach: ring 1 was 20 out, where the fight counts a blaze with a line on the buddy a threat (BlazeFightRules.SIGHT_RANGE 20)
    // and blazes drift some 8 to 12 from the spawner they came from. from 32 out, RING_OUT_STEP apart (32, 48, 64: the
    // outer ring stays inside ERRAND_NEAR's 80, where the dead end's clock runs), a blaze by the spawner is 20 or more off
    public static final double RING_OUT = 32;
    public static final double RING_OUT_STEP = 16;

    public static double ringOut() {
        return Tuning.get("route_ring_out", 0);
    }

    // the radius of ring (1 first): from first, step apart with the switch on (first above 0), else ERRAND_RING_STEP * ring
    public static double ringRadius(int ring, double first) {
        if (first <= 0) return ERRAND_RING_STEP * ring;
        return first + RING_OUT_STEP * (ring - 1);
    }

    // the k-th point of the errand's walk as errandPoint, the rings from first out (ringRadius)
    public static int[] errandPoint(int k, double sx, double sz, double from, double first) {
        int ring = 1 + Math.floorMod(k, ERRAND_POINTS) / ERRAND_RING_POINTS;
        double r = ringRadius(ring, first);
        double a = from + Math.floorMod(k, ERRAND_RING_POINTS) * (2 * Math.PI / ERRAND_RING_POINTS);
        return new int[]{(int) Math.round(sx + r * Math.cos(a)), (int) Math.round(sz + r * Math.sin(a))};
    }

    // (Tuning route_errand_away, on by default) while the errand is the order the camp at the spawner is not taken: no hiding
    // place, no chase. a blaze with a line on the buddy within the fight's sight is walked away from, out of its range, and
    // only one within AWAY_BLOW is hit (self-defence: a blaze that close bites, and running from it gives it free shots)
    public static final double AWAY_BLOW = 3.3;

    public static boolean errandAwayOn() {
        return Tuning.get("route_errand_away", 0) > 0;
    }

    // what the fight does with a blaze while the errand holds it off: "hit" one within the blow's reach, "away" from one with a
    // line on the buddy (seen), null to leave it be
    // ---- Tuning route_errand_risk (off by default; steering 146.2, 10 Nov 2026): in phase 3 a blaze is fought without the
    // potion when the fight is safe by the danger ladder's own numbers, else walked away from as before (awayAct). the ladder's
    // blaze drills (forced fight against forced flee, full health, a way out): with 1 to 4 blazes the fight never died (14
    // pairs), with 5 0 of 8, 6 2 of 8, 7 4 of 8, 8 3 of 8. so: at most RISK_BLAZES blazes within RISK_RANGE, no wither
    // skeleton within RISK_WITHER, the health at RISK_HP or more, not burning or withered; a blaze fought is one within
    // RISK_RANGE (the blazes' sight), its rods picked up as any
    public static final int RISK_BLAZES = 4;
    // the blazes counted within RISK_RANGE (the blazes' sight, BlazeFightRules.SIGHT_RANGE) of the buddy and of the blaze to be
    // fought; a blaze fought is one within RISK_REACH of the buddy; no wither skeleton within RISK_WITHER of either. (look 48,
    // box 2, 10 Nov 2026: judged from the buddy's place alone, at 20 the chase walked 15 blocks into four blazes just past
    // the count, a magma cube held it among them and a wither skeleton came in from 24: dead. at a reach of 16 and counting
    // round the buddy only, 2 of 6 smoke runs passed phase 3, at 20 none of 4, 3 dead)
    public static final double RISK_RANGE = BlazeFightRules.SIGHT_RANGE;
    public static final double RISK_REACH = 16;
    public static final double RISK_WITHER = 16;
    public static final double RISK_HP = 14;

    public static boolean errandRiskOn() {
        return Tuning.get("route_errand_risk", 0) > 0;
    }

    // whether phase 3 fights now: blazes alive within RISK_RANGE, wither skeletons within RISK_WITHER, the health, and burning or
    // withered (a hurt that keeps going)
    public static boolean errandFights(int blazes, int witherSkeletons, double health, boolean hurting) {
        return blazes <= Tuning.get("route_errand_risk_blazes", RISK_BLAZES) && witherSkeletons == 0
                && health >= Tuning.get("route_errand_risk_hp", RISK_HP) && !hurting;
    }

    public static String awayAct(double distance, boolean seesBuddy) {
        if (distance <= AWAY_BLOW) return "hit";
        if (seesBuddy) return "away";
        return null;
    }

    // ---- the cube held, hunted by its id (Tuning route_cube_id, on by default; route jar 6). the hunt's kill task was made
    // with a rule for the one cube held, but the task runner keeps a running child that compares equal, and two kill tasks
    // for cubes compared equal: the second cube held was hunted with the first one's rule, found nothing and wandered (route
    // jar 5's run 1: a cube 47 blocks off, the buddy wandering the other way). made by the cube's id, each cube's task is
    // its own
    public static boolean cubeByIdOn() {
        return Tuning.get("route_cube_id", 0) > 0;
    }

    // ---- magma cubes (route jar 7, 9 Nov 2026). route jar 6 lost 3 of 11 runs to magma cubes before phase 3. the cream hunt
    // on the trade's search took one cube after another in a field of them by a lava shore, at 10 hp and at 6, the next cube 4
    // blocks off the moment a fight ended: 26 cubes killed and dead (run 13). the errand's hunt (the trade's search and the
    // brew errand; Tuning route_cube_alone, the radius, CUBE_ALONE by default, 0 off) takes a cube of size 2 only (a big one
    // splits into two to four that split again: a crowd of its own making), with no other cube of any size within the radius
    // of it, and the buddy at CUBE_HP or more; the cube held is let go below CUBE_HP or with CUBE_CROWD others by it
    public static final double CUBE_ALONE = 6;
    public static final double CUBE_HP = 14;
    public static final int CUBE_CROWD = 2;

    public static double cubeAlone() {
        return Tuning.get("route_cube_alone", 0);
    }

    // why a cube is not hunted by the alone rule (the count line's words), null when it may be: its size, the other cubes
    // within the radius of it, the buddy's health
    public static String cubeWhyNot(int size, int othersNear, double health) {
        return cubeWhyNot(size, othersNear, health, false);
    }

    // big: Tuning route_cube_big, a cube of size 4 taken alone with the buddy at CUBE_BIG_HP or more
    public static String cubeWhyNot(int size, int othersNear, double health, boolean big) {
        if (size > 2 && !big) return "big";
        if (othersNear > 0) return "crowd";
        if (health < (size > 2 ? CUBE_BIG_HP : CUBE_HP)) return "hp";
        return null;
    }

    // ---- the big cube (Tuning route_cube_big, off by default; the phase-3 block on the cream, 10 Nov 2026). the alone rule
    // refuses a cube of size 4 for the crowd it splits into: realfort-route-16 left one in 32 of 60 runs, 27 of them with no
    // cream at phase 4. a cube of size 2 or 4 drops cream 1 in 4, and a big one splits into two to four of size 2, so one big
    // cube killed with its family brings cream about two times in three. with the switch the errand's hunt takes a big cube
    // with no other cube within the alone radius and the buddy at CUBE_BIG_HP or more; for FAMILY_S after it dies, the cubes
    // within FAMILY_R of where it died are no crowd to each other, so its family is hunted one by one. the hp let-go, the lava,
    // sea, drop and shore rules hold for every cube
    public static final double CUBE_BIG_HP = 18;
    public static final double FAMILY_S = 60;
    public static final double FAMILY_R = 8;
    private static double famX, famY, famZ;
    private static long famAt = Long.MIN_VALUE;

    public static boolean cubeBigOn() {
        return Tuning.get("route_cube_big", 0) > 0;
    }

    // the big cube held died here at game time now: its family's window begins
    public static synchronized void bigCubeDied(double x, double y, double z, long now) {
        famX = x;
        famY = y;
        famZ = z;
        famAt = now;
    }

    // whether a cube at x, y, z is of the last big cube's family now
    public static synchronized boolean inFamily(double x, double y, double z, long now) {
        return familyHolds(famX, famY, famZ, famAt, now, x, y, z);
    }

    static boolean familyHolds(double fx, double fy, double fz, long at, long now, double x, double y, double z) {
        if (at == Long.MIN_VALUE || now < at || now - at > FAMILY_S * 20) return false;
        double dx = x - fx, dy = y - fy, dz = z - fz;
        return dx * dx + dy * dy + dz * dz <= FAMILY_R * FAMILY_R;
    }

    // why the cube held is let go by the alone rule, null while it is hunted on
    public static String cubeLetGo(int othersNear, double health) {
        if (health < CUBE_HP) return String.format(java.util.Locale.ROOT, "the buddy at %.0f hp", health);
        if (othersNear >= CUBE_CROWD) return othersNear + " more cubes by it";
        return null;
    }

    // the trade steps off magma cubes (Tuning route_trade_cube, the distance, TRADE_CUBE by default, 0 off). run 7 handed
    // ingot after ingot with a big cube coming; it hit, the mob defense stood and fought it with calm piglins and zombified
    // piglins within 3, so every full charge was thrown away (the sweep would anger them) and the early swings did little
    // through the cube's armour: 54 charges thrown away, nothing killed, 20 hp to 0 in 45 s. with a cube within the distance
    // the trade hands nothing and walks off TRADE_CUBE_RUN from the cubes, on till none is within the distance and
    // TRADE_CUBE_CLEAR more; then it goes back to the piglins
    public static final double TRADE_CUBE = 6;
    public static final double TRADE_CUBE_CLEAR = 4;
    public static final double TRADE_CUBE_RUN = 12;

    public static double tradeCube() {
        return Tuning.get("route_trade_cube", 0);
    }

    // whether the trade walks off the cubes now: away, whether it already does; nearest, the nearest cube (infinite for none)
    public static boolean tradeCubeAway(boolean away, double nearest, double near) {
        if (near <= 0) return false;
        return nearest <= (away ? near + TRADE_CUBE_CLEAR : near);
    }

    // ---- the stone for the brewing stand (Tuning brew_keep_stone, the count, STAND_STONE by default, 0 off). the pillboxes
    // built cobblestone first, so the brew box was shut with no stone left for the stand (three of it, with a rod): "the
    // stand not made inside in 30 s, opening", the box opened, the buddy out in the open for stone and the fire resistance
    // gone while it looked (route jar 6's runs 1 and 10, both dead to blazes). with no stand in the bag the last of the
    // stand's stone (cobblestone, blackstone, cobbled deepslate, all kinds together) is never a box's block
    public static final int STAND_STONE = 3;

    public static int keepStone() {
        return (int) Math.round(Tuning.get("brew_keep_stone", 0));
    }

    // whether a block of the stand's stone may go into a box: the stand in the bag, or more of the stone than kept
    public static boolean stonePlaceable(boolean standInBag, int stoneInBag, int keep) {
        return keep <= 0 || standInBag || stoneInBag > keep;
    }

    // ---- the potion kept for the spawner fight (Tuning route_hold, phases 3 and 4; route jar 2). the first route jar let it go
    // for any blaze within 16, so it went down on the walk to the pillbox with a blaze 12 off and ran out with one rod; and
    // it kept it while the buddy burned on a lava rim at 17 hp, so the buddy fell back in with none. whether a fire resistance
    // potion that would go down now waits: asked (a task's drink, the brew box), in lava, or burning with the health falling
    // (healthFalling: below the best health of this burn), or on fire at holdHp or less, and it goes (on the way with
    // route_hold_way: wayLetGo instead); at the spawner fight
    // (fightBegun: the route past phase 3 and the buddy at the spawner) a blaze in drinking range lets it go too. a blaze on
    // the way that is not hitting (no fire on the buddy) does not
    // (Tuning route_hold_fight, on by default: off, route jar 1's hold, FireResTradeRules.held)
    public static boolean holdPotion(boolean asked, boolean inLava, boolean onFire, boolean healthFalling, double health, double holdHp,
                                     boolean fightBegun, boolean blazeNear) {
        return holdLetGo(asked, inLava, onFire, healthFalling, health, holdHp, fightBegun, blazeNear) == null;
    }

    // why the potion kept goes down now (the line's words), null while it waits
    public static String holdLetGo(boolean asked, boolean inLava, boolean onFire, boolean healthFalling, double health, double holdHp,
                                   boolean fightBegun, boolean blazeNear) {
        if (asked) return "asked for";
        if (inLava) return "in lava";
        if (onFire && healthFalling) return "burning, the health falling";
        if (onFire && health <= holdHp) return "on fire at " + Math.round(health) + " hp";
        if (fightBegun && blazeNear) return "the spawner fight";
        return null;
    }

    public static boolean holdFightOn() {
        return Tuning.get("route_hold_fight", 0) > 0;
    }

    // ---- Tuning route_trade_fire (seconds of fire resistance, 0 off; steering 150, 10 Nov 2026): the trade goes on past the
    // first potion until the fire resistance potions in the bag last this long together (a traded one 3:00), or the gold is all
    // handed. with that much the route skips the wart, the cream and the brew (phases 3 and 5), every rod under the traded
    // potions; with less it goes on as before. the brew's three potions last 8:00 each, so the same fire takes about eight
    // traded ones
    public static int tradeFireTicks() {
        return (int) Math.max(0, Tuning.get("route_trade_fire", 0)) * 20;
    }

    // the trade's verdict under route_trade_fire: done once the fire in the bag reaches the target, and when the gold runs out
    // with some fire but under the target, done too (the route goes on as before with what it got); target 0: the first potion
    public static boolean tradeEnough(int fireTicks, int targetTicks, boolean potion) {
        return targetTicks > 0 ? fireTicks >= targetTicks : potion;
    }

    // m:ss for the lines
    public static String clock(int ticks) {
        int s = Math.max(0, ticks) / 20;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    // ---- Tuning route_drink_ready (off by default; 10 Nov 2026): at the spawner fight the kept potion goes down only once a
    // blaze can be fought: one within DRINK_SIGHT in the buddy's line of sight, or the buddy at its camp (the spawner's top or
    // the camp's spot). the drink came on a blaze within 16 alone, and the 180 s went by with no swing: inside the brick under
    // the spawner digging up (the blazes 8-10 off behind it), at a bridge's end with the blazes over the void, every chase
    // "no path" and a two-minute ban (cascade realfort-p4-cream7 runs 2, 5 and 18; 'potion_idle' in 32 phase-4 losses).
    // the safety drinks (in lava, burning with the health falling, on fire at the hold's health) are not touched
    public static final double DRINK_SIGHT = 8;

    public static boolean drinkReadyOn() {
        return Tuning.get("route_drink_ready", 0) > 0;
    }

    // whether the spawner fight can begin under the potion now: atCamp (on the spawner's top or the camp's spot), seenBlaze the
    // distance to the nearest blaze in the line of sight (infinite with none)
    public static boolean fightReady(boolean atCamp, double seenBlaze) {
        return atCamp || seenBlaze <= DRINK_SIGHT;
    }

    // ---- the potion on the way (Tuning route_hold_way, on by default; route jar 3, 8 Nov 2026). route jar 2 let it go on the
    // way for any burn with the health half a heart down: run 1 stepped into a lava pool's edge on the fortress search at 20
    // hp (out at once, 18.1 hp) and run 2 took one fireball in a blaze fight in phase 3 (17.4 hp); both reached the spawner
    // with none. the potion is the spawner fight's (rule 91.1): before that fight it goes down only with life at stake.
    // in lava: not out within WAY_LAVA_S, or at WAY_LAVA_HP or less. lava takes about 1.9 a half second through this armour,
    // so 2 s is about 8 hp, and 12 hp leaves about 3 s of lava for the throw and the way out; a step in and out costs one
    // hit. burning, out of the lava: at WAY_BURN_HP or less. the burn is 1 hp a second and the food heals against it; 10 hp
    // is ten seconds of fire, or two fireballs and a burn, from death
    public static final double WAY_LAVA_S = 2;
    public static final double WAY_LAVA_HP = 12;
    public static final double WAY_BURN_HP = 10;

    public static boolean holdWayOn() {
        return Tuning.get("route_hold_way", 0) > 0;
    }

    // why the potion kept goes down now on the way (before the spawner fight), null while it waits. lavaTicks: how long the
    // buddy has been in this lava (game ticks), lavaCap: WAY_LAVA_S in ticks
    public static String wayLetGo(boolean asked, boolean inLava, long lavaTicks, long lavaCap, boolean onFire, double health,
                                  double lavaHp, double burnHp) {
        if (asked) return "asked for";
        if (inLava && lavaTicks >= lavaCap) return "in lava for " + Math.round(lavaTicks / 20.0) + " s";
        if (inLava && health <= lavaHp) return "in lava at " + Math.round(health) + " hp";
        if (onFire && !inLava && health <= burnHp) return "burning at " + Math.round(health) + " hp";
        return null;
    }

    // and past phase 3 (the first two rods on) but out of the spawner's FIGHT_NEAR: a burn with a blaze within WAY_BLAZE is the
    // blazes' fight, not the way's, so the potion goes down at once (Tuning route_way_blaze, on by default; 11 Nov 2026, a
    // fix: a traded potion ran out on a rim 16 blocks from nine blazes, the next waited for 10 hp under the way's rule, and the
    // buddy burned to 12.5 hp, ran off the rim and died with it in the bag). phase 3's fights keep the way's rule
    public static final double WAY_BLAZE = 24;

    public static boolean wayBlazeOn() {
        return Tuning.get("route_way_blaze", 0) > 0;
    }

    // why the potion kept goes down now among the blazes past phase 3, null while the way's rule holds. blaze: the nearest
    // one (blocks), infinite for none
    public static String wayBlazeLetGo(boolean pastErrand, boolean onFire, double blaze) {
        if (pastErrand && onFire && blaze <= WAY_BLAZE) return "on fire among the blazes (" + Math.round(blaze) + " off)";
        return null;
    }

    // the spawner fight has begun for the potion's sake: the buddy this close to the spawner (blocks), the route past phase 3
    public static final double FIGHT_NEAR = 10;

    public static boolean fightBegun(int phase, double toSpawner) {
        return phase >= RODS2 && toSpawner <= FIGHT_NEAR;
    }

    // a burn's health counts as falling this far below its best (half a heart: one tick of fire is a whole heart)
    public static final double BURN_FALL = 0.5;

    public static boolean healthFalling(boolean onFire, double best, double health) {
        return onFire && health <= best - BURN_FALL;
    }

    // ---- phase 7, the exit (Tuning route_exit, on by default)

    // portal cells this close (blocks, each axis) are one portal: the exit counted one frame's cells as several
    public static final int PORTAL_SAME = 3;

    public static boolean samePortal(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.abs(ax - bx) <= PORTAL_SAME && Math.abs(ay - by) <= PORTAL_SAME && Math.abs(az - bz) <= PORTAL_SAME;
    }

    // the exit's own cap: still in the nether this long after it began, it is given up (game seconds; Tuning route_exit_s)
    public static final double EXIT_S = 600;
    // a portal is remembered this often while the rods are fetched (game ticks), and at most this many of them
    public static final long PORTAL_NOTE_TICKS = 20;
    public static final int PORTALS_KEPT = 16;

    // the exit of the order under way: begun (phase 7), given up (no portal known, or the cap), and the line out said
    private static volatile boolean exitBegun;
    private static volatile boolean exitGaveUp;
    private static volatile boolean outSaid;

    // the exit's marks and the zones kept (RunMemory)
    public static synchronized int remembered() {
        return AWAY_ZONES.size() + (exitBegun ? 1 : 0) + (exitGaveUp ? 1 : 0) + (outSaid ? 1 : 0);
    }

    // a new order of rods: nothing of the last one's exit, nor of its zones walked away from
    public static void reset() {
        exitBegun = false;
        exitGaveUp = false;
        outSaid = false;
        clearAwayZones();
    }

    // ---- the zones walked away from (Tuning route_away_zone, on by default; route jar 12, 9 Nov 2026, look 35, row L37). the
    // walk away from blazes with a line on the buddy (route_errand_away) went out to 24, and the errand's next ring point on
    // the blazes' side pulled it back inside 20: about 75 s back and forth near one spot, climbing to y 111 (t8 route-6 run
    // 7), 40 walk-aways in 3 minutes in rounds' B (t9 run 7.B). each blaze walked away from leaves a zone of AWAY_ZONE round
    // it for AWAY_ZONE_TICKS (renewed while the walk goes on); a ring point inside a live zone is passed over
    public static final double AWAY_ZONE = 24;
    public static final long AWAY_ZONE_TICKS = 600;
    // at most this many zones kept: the oldest goes
    public static final int AWAY_ZONES_KEPT = 16;
    private static final java.util.ArrayDeque<double[]> AWAY_ZONES = new java.util.ArrayDeque<>();

    public static boolean awayZoneOn() {
        return Tuning.get("route_away_zone", 0) > 0;
    }

    // a blaze at (x, z) walked away from at game tick now: its zone, or the one already there within half a zone, renewed
    public static synchronized void noteAway(double x, double z, long now) {
        for (double[] zone : AWAY_ZONES) {
            if (Math.hypot(zone[0] - x, zone[1] - z) <= AWAY_ZONE / 2) {
                zone[0] = x;
                zone[1] = z;
                zone[2] = now + AWAY_ZONE_TICKS;
                return;
            }
        }
        if (AWAY_ZONES.size() >= AWAY_ZONES_KEPT) AWAY_ZONES.pollFirst();
        AWAY_ZONES.addLast(new double[]{x, z, now + AWAY_ZONE_TICKS});
    }

    // whether (x, z) lies in a zone still live at game tick now
    public static synchronized boolean inAwayZone(double x, double z, long now) {
        for (double[] zone : AWAY_ZONES) {
            if (zone[2] >= now && Math.hypot(zone[0] - x, zone[1] - z) <= AWAY_ZONE) return true;
        }
        return false;
    }

    public static synchronized void clearAwayZones() {
        AWAY_ZONES.clear();
        awayWallLive = false;
    }

    // ---- the zones as walls (Tuning route_away_wall, off by default; route jar 16's B arm, 10 Nov 2026, rows L39 a and
    // E1110-0300.3). the zones above passed over only the ring points: the walk to a ring point, a wart or a cube still went
    // through the blazes' sight, a blaze saw the buddy, the buddy walked away, and out of sight the errand walked back in:
    // 73 then 146 walk-aways in about 6 min (t8 route-9 run 8); in route-12 half the runs over 100 in phase 3, 15-20% of its
    // time, and those runs passed phase 3 5% against 32%. with the switch, while the errand runs (awayWallLive, set by the
    // rods task each tick), a live zone is a wall for every walk but the zone the buddy stands in (so the walk away and the
    // way out stay open), and a wart or a cube inside one is passed over until the zone runs out
    private static volatile boolean awayWallLive;
    // the wall's radius round the blaze: its sight (BlazeFightRules.SIGHT_RANGE), under the zone's AWAY_ZONE. with the wall the
    // zone's 24 the smoke's B (10 Nov, t7) went on 138 times in 8 min: the walk away ends once no blaze sees the buddy, at about
    // 20, still inside the zone the buddy stands in, which is open, so the errand walked back to 19.9 each time. at 20 the
    // buddy stands outside the wall as soon as the walk away is done
    public static final double AWAY_WALL = BlazeFightRules.SIGHT_RANGE;

    public static boolean awayWallOn() {
        return Tuning.get("route_away_wall", 0) > 0;
    }

    public static void awayWallLive(boolean on) {
        awayWallLive = on;
    }

    public static boolean awayWallLive() {
        return awayWallLive;
    }

    // whether (x, z) is a wall for a walk from (px, pz) at game tick now: within AWAY_WALL of a live zone's blaze, and the walker
    // not.
    // called by the path search for its nodes, so squared distances and no allocation
    public static synchronized boolean awayWall(double x, double z, long now, double px, double pz) {
        double r2 = AWAY_WALL * AWAY_WALL;
        for (double[] zone : AWAY_ZONES) {
            if (zone[2] < now) continue;
            double dx = zone[0] - x, dz = zone[1] - z;
            if (dx * dx + dz * dz > r2) continue;
            double ex = zone[0] - px, ez = zone[1] - pz;
            if (ex * ex + ez * ez <= r2) continue;
            return true;
        }
        return false;
    }

    // whether a target at (x, z) is passed over now: the walls live and the target within a live zone's wall
    public static synchronized boolean awayPassOver(double x, double z, long now) {
        if (!awayWallLive) return false;
        for (double[] zone : AWAY_ZONES) {
            if (zone[2] >= now && Math.hypot(zone[0] - x, zone[1] - z) <= AWAY_WALL) return true;
        }
        return false;
    }

    public static void exitBegins() {
        exitBegun = true;
    }

    public static void exitGivesUp() {
        exitGaveUp = true;
    }

    public static boolean exitGaveUp() {
        return exitGaveUp;
    }

    // the line out of the nether, once an order, and only after the exit began; true when said now
    public static boolean out() {
        if (!exitBegun || outSaid) return false;
        outSaid = true;
        adris.altoclef.Debug.logInternal(OUT);
        return true;
    }

    public static boolean exitOn() {
        return Tuning.get("route_exit", 0) > 0;
    }

    // whether an order of rods with its count in the bag is still under way for the exit: the switch on, still in the nether,
    // and the exit not given up. the buddy's own order and the rods task both ask this
    public static boolean exitHolds(boolean on, boolean inNether, boolean gaveUp) {
        return on && inNether && !gaveUp;
    }

    // whether the exit gives up now: no portal known at all, or still in the nether capTicks after it began
    public static String exitGiveUp(boolean portalKnown, long sinceBegun, long capTicks) {
        if (!portalKnown) return "no portal known";
        if (sinceBegun >= capTicks) return "not out in " + Math.round(capTicks / 20.0) + " s";
        return null;
    }
}
