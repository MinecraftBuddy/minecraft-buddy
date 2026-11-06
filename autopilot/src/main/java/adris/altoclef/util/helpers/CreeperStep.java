package adris.altoclef.util.helpers;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the creeper preempt. not a commitment: the fight and run clocks keep going, and the wheel goes straight back the moment
// the fuse is over. a creeper within TRIGGER gets stepped away from, lit or unlit-and-in-view (one two blocks off lights with the whole fuse
// still ahead and nowhere to be), a lit one further out only when the path is walking into it. the one thing CombatCommit
// does hear: the same creeper coming back for a second go (REPEAT_WINDOW)
//
// one way to step: walk out past 7 so it stops swelling. it used to also stand just past the blast and let it pop, which
// was a second mode with its own caps and its own way of being wrong, and the bot dithered between the two
public final class CreeperStep {

    // a creeper this close gets stepped away from, lit or unlit and in view (see inView). the fuse starts at 3, so this fires before it even lights
    public static final double TRIGGER = 4;
    // where the step walks to. the blast reaches about 6 (power 3, twice that is the falloff radius), past it is a scratch,
    // and a creeper that gets more than 7 away stops swelling, so 8 clears both with a block to spare for the search's
    // idea of where the creeper is being a tick old
    public static final double SAFE = 8;
    // the path walking into a lit one: the next few nodes coming within this of it count like being within TRIGGER
    public static final double PATH_NEAR = 3;
    public static final int PATH_LOOKAHEAD = 6;
    // only lit creepers within this are asked about the path at all. past 7 it un-swells by itself anyway
    public static final double PATH_RANGE = 8;
    // the fuse is 1.5 s, so 2.5 s of standing about is plenty. one that is still lit by then gets left alone
    public static final long HOLD_CAP = 50;
    // the cap while the shield is up and the creeper lit (the buddy's fork, see step)
    public static final long HOLD_CAP_SHIELD = 200;
    // an unlit creeper keeps the step held while it is within this
    public static final double HOLD_RADIUS = 5;
    // no ground gained after this long while still inside TRIGGER is boxed in. a shield takes over then. a second, because
    // the cancel waits for a safe moment and the search takes a few ticks too, and the fuse is 30 so the shield still makes it.
    // it only looks at where we are, so the raw sprint at the start of the step counts as ground gained like any other
    public static final long CORNER_TICKS = 20;
    public static final double CORNER_GAIN = 0.75;
    // ids we remember (logged once, capped once). creeper ids are per world, a small bag is plenty
    private static final int MEMORY = 64;

    // a creeper we stepped from that comes back beside us this soon did not go away: that is the run's job now (CombatCommit,
    // REPEAT_CREEPER), not another lap of the step
    public static final long REPEAT_WINDOW = 200;
    // a lit creeper this close gets the shield and not a step (the buddy's fork): every one the step would take, so with a
    // shield in hand there is no step from a lit one at all. in a mining tunnel there was no stepping out: one lit at 3 blocks
    // killed it while the step asked for a path (attempt 45), and one at 4 ended the step the tick it began and blew at 2,
    // 7.6 through nothing, the skeletons had the rest (attempt 53; the runner, 2 Nov 2026)
    public static final double SHIELD_CLOSE = TRIGGER;
    // a lit one within the blast's reach gets the shield too, picked further out (its path near ours) or held: a shield blocks
    // only from its fifth tick up, and one stepped from at 4.3 got the shield a tick later at 4 and blew at 2 before it
    // counted, 16.7 through 14 points of armour (the runner, 2 Nov 2026, attempt 57)
    public static final double SHIELD_REACH = 6;

    // the hand sprint at the start of a step. a standing start covers about 2 blocks in 10 ticks and 7 in 30, and the fuse is 30,
    // so it keeps going straight away until we are at SAFE or the fuse is spent. (waiting on the path for the rest of it is how
    // a creeper lit at 3 still got us from 2 blocks)
    // a creeper this far or further stops swelling
    public static final double SWELL_STOP = 7;
    public static final long RAW_MAX_TICKS = 30;
    // a lit one inside this keeps the hand sprint going, no handoff to a path that starts from a standstill. the
    // blast reaches about 6
    public static final double RAW_HOLD = 6;

    public static boolean rawGoing(long ticks, double distance, double litDistance) {
        if (litDistance < RAW_HOLD) return true;
        return ticks < RAW_MAX_TICKS && distance < SAFE;
    }

    public enum Event { NONE, START, CORNERED, END_GONE, END_UNLIT, END_CAP }

    // one creeper as the world sees it this tick. lit = swelling up or flint-and-steeled, pathNear = the path walks into it
    // fusing = the fuse still has charge in it, lit or deflating (a creeper that lost its line to us goes unlit with the swell
    // still up and relights the moment we walk back in). inView = about our height with a clear line, only an unlit one needs it
    public record Seen(int id, boolean lit, double distance, boolean pathNear, boolean fusing, boolean inView) {
        public Seen(int id, boolean lit, double distance, boolean pathNear, boolean fusing) {
            this(id, lit, distance, pathNear, fusing, true);
        }

        public Seen(int id, boolean lit, double distance, boolean pathNear) {
            this(id, lit, distance, pathNear, lit);
        }
    }

    private int _id = -1;
    private long _since;
    // when the creeper we hold last went lit, -1 while it is unlit. the cap only blacklists one that sat lit the whole time
    private long _litSince = -1;
    private double _startDistance;
    private boolean _cornered;
    private boolean _log;
    // this step's creeper is back for a second go and the commitment should run from it (true on the tick it starts only)
    private boolean _escalate;
    private final Set<Integer> _logged = new LinkedHashSet<>();
    private final Set<Integer> _capped = new LinkedHashSet<>();
    // id -> the tick its last step ended
    private final Map<Integer, Long> _stepped = new LinkedHashMap<>();

    public boolean active() {
        return _id >= 0;
    }

    public int creeperId() {
        return _id;
    }

    // boxed in and holding a shield: put it up instead of walking
    public boolean cornered() {
        return _id >= 0 && _cornered;
    }

    // the step that just started should print its line (once per creeper, a relit one does not get a second)
    public boolean shouldLog() {
        return _log;
    }

    public double startedAt() {
        return _startDistance;
    }

    // the step that just started is a creeper coming back: hand CombatCommit a run (true on that one tick only)
    public boolean escalate() {
        return _escalate;
    }

    public void reset() {
        _id = -1;
        _cornered = false;
        _log = false;
        _escalate = false;
        _logged.clear();
        _capped.clear();
        _stepped.clear();
    }

    public Event step(long now, List<Seen> creepers, boolean shieldReady) {
        return step(now, creepers, shieldReady, CombatCommit.Mode.NONE, false);
    }

    // once per game tick. creepers is every creeper close enough to matter, shieldReady = a shield we could put up now.
    // mode = what the commitment held last tick, commitOn = it is switched on (so a run handed to it really starts)
    public Event step(long now, List<Seen> creepers, boolean shieldReady, CombatCommit.Mode mode, boolean commitOn) {
        return step(now, creepers, shieldReady, mode, commitOn, false);
    }

    // noHeading: CreeperEscape has nowhere to sprint (a one wide shaft, a creeper on our head). waiting on a path for a second
    // to find that out is most of the fuse, so a lit one inside TRIGGER with no way out is cornered the tick it shows up. the
    // chain asks CreeperEscape, this stays pure
    public Event step(long now, List<Seen> creepers, boolean shieldReady, CombatCommit.Mode mode, boolean commitOn, boolean noHeading) {
        _log = false;
        _escalate = false;
        boolean idle = mode == CombatCommit.Mode.NONE;
        if (_id >= 0) {
            Seen held = find(creepers, _id);
            if (held != null && !held.lit()) _litSince = -1;
            else if (held != null && _litSince < 0) _litSince = now;
            Event end = Event.NONE;
            if (held == null) end = Event.END_GONE;
            else if (fuseOut(held, mode)) end = Event.END_UNLIT;
            // (the buddy's fork: behind the shield with the creeper lit, the cap waits for HOLD_CAP_SHIELD. at HOLD_CAP the hold
            // ended, the shield came down, went up again the next tick and was not blocking yet when the fuse ran out: the probe
            // had it blocking at 0 degrees for 26 ticks, down at 30, and the blast through it, in iron, the practice runner, 2 Nov
            // 2026, attempt 72. a lit fuse runs out in thirty ticks, so the longer cap is only for one that never blows)
            else if (GameClock.since(_since, now) >= (_cornered && held.lit() ? HOLD_CAP_SHIELD : HOLD_CAP)) end = Event.END_CAP;
            // (the buddy's fork: another one lit within SHIELD_REACH while this one is held, and the held one unlit or further:
            // that one has the clock on it, and the hold moves to it this tick through the pick below, the shield when it is
            // that close. the step held an unlit one it could not reach while a second lit 2.4 blocks off, and no shield went up
            // for the blast, 24 through full iron, the runner, 3 Nov 2026, attempt 179)
            Seen other = end == Event.NONE ? closestLitOther(creepers, _id) : null;
            boolean moveOn = other != null && other.distance() <= SHIELD_REACH && (!held.lit() || other.distance() < held.distance());
            if (end == Event.NONE && !moveOn) {
                // the one we hold, lit again within SHIELD_CLOSE: the shield, as at the start. one stepped from to 5 blocks
                // deflated, the run it handed to took over, and it lit again behind and blew at 3.3, 14.7 of its 20 hp, a
                // shield in its hand (the runner, 2 Nov 2026, attempt 48)
                if (!_cornered && shieldReady && (held.lit() && held.distance() <= SHIELD_REACH
                        || held.fusing() && held.distance() <= SHIELD_CLOSE)) {
                    _cornered = true;
                    return Event.CORNERED;
                }
                if (!_cornered && shieldReady && noHeading && held.lit() && held.distance() <= TRIGGER) {
                    _cornered = true;
                    return Event.CORNERED;
                }
                if (!_cornered && shieldReady && now - _since >= CORNER_TICKS && held.distance() <= TRIGGER
                        && held.distance() - _startDistance < CORNER_GAIN) {
                    _cornered = true;
                    return Event.CORNERED;
                }
                return Event.NONE;
            }
            // a capped one stays lit next to us somehow (charged and stuck?), it is not worth holding the wheel forever. a hold that
            // only ran long because the fuse lit, deflated and relit is not that one: it still has to light up to explode, so it
            // gets stepped from again if it relights (and one that flickers at the swell edge is the shield's problem, not a blacklist's)
            if (end == Event.END_CAP && _litSince >= 0 && now - _litSince >= HOLD_CAP) remember(_capped, _id);
            // (re-put so the oldest end is the one that falls out first)
            _stepped.remove(_id);
            _stepped.put(_id, now);
            if (_stepped.size() > MEMORY) _stepped.remove(_stepped.keySet().iterator().next());
            _id = -1;
            _cornered = false;
            if (!moveOn) return end;
        }
        Seen pick = null;
        for (Seen s : creepers) {
            if (!triggers(s) || _capped.contains(s.id())) continue;
            // a run is already taking us away from everything, creepers included. stopping it to step is the step and the run
            // trading the wheel, so only when the run's own path is about to walk into a lit one, or one is already hissing
            // inside TRIGGER. the path check only looks ahead, and a creeper only lights within three blocks, so beside or
            // behind us it was never "on the path" and the run jogged on through the blast
            // (the buddy's fork: or one deflating with charge left inside TRIGGER. it lights again with that charge, and one
            // that came at it while it healed mid run was first seen lit at 2 blocks and blew in the same instant, in full
            // iron, the runner, 2 Nov 2026, attempt 48)
            if (mode == CombatCommit.Mode.RUN && !s.pathNear() && !((s.lit() || s.fusing()) && s.distance() <= TRIGGER)) continue;
            // the lit one first, it is the one with a clock on it
            if (pick == null || (s.lit() && !pick.lit()) || (s.lit() == pick.lit() && s.distance() < pick.distance())) pick = s;
        }
        if (pick == null) return Event.NONE;
        // a second go is the run's, when there is a run to hand it to. the step still walks us out of the blast until it gets
        // going. with nobody to hand it to (the commitment is off) it is just the step again. a committed fight counts too, it
        // walks us straight back to the creeper otherwise. boxed in there is nowhere for a run to go, so not then
        // the buddy's fork: a lit one that came back for a second go gets the shield too, not a run. a shield faced at the creeper
        // takes the whole blast, and a run from one that follows only hands it the next go: one followed it 49 blocks, the run
        // ran out and it blew up beside it, 19 of its 20 hp through a chestplate, a shield in the bag (the runner, 2 Nov 2026)
        boolean back = cameBack(pick.id(), now);
        // and one lit within SHIELD_CLOSE: there is no stepping out of that blast in the fuse that is left. one came up the
        // staircase a block and a half below it and took it from 20 to 2 in the second it was seen, a shield in its hand (the
        // runner, 2 Nov 2026)
        // (within SHIELD_CLOSE a deflating one with charge left counts as lit: it lights again with that charge)
        boolean boxed = shieldReady && (pick.lit() && pick.distance() <= SHIELD_REACH
                || pick.distance() <= TRIGGER && ((noHeading || back) && pick.lit() || pick.distance() <= SHIELD_CLOSE && pick.fusing()));
        _escalate = back && !boxed && (idle || mode == CombatCommit.Mode.FIGHT) && commitOn;
        // nowhere to go beats every plan that walks
        _id = pick.id();
        _since = now;
        _litSince = now;
        _startDistance = pick.distance();
        _cornered = boxed;
        _log = remember(_logged, pick.id());
        return boxed ? Event.CORNERED : Event.START;
    }

    // the step is over when it is unlit AND past HOLD_RADIUS. unlit with charge left and still close is a deflating fuse, and
    // the user task walking back in relights it: that was the wheel flipping back and forth. the cap is the backstop for one
    // that never goes away
    //
    // a step inside a run is stricter: the run's path leads back past the creeper, so letting go at "unlit and over 5" had the
    // creeper relight and the step start again about every second, with the arrows landing in between. there it holds until the
    // swell is fully drained and we are past 7 (SWELL_STOP). not SAFE: the goal counts a cell by its corner, so walking -x or -z
    // it is done at 7.5 live, and waiting on 8 would sit there till the cap
    private boolean fuseOut(Seen held, CombatCommit.Mode mode) {
        if (held.lit()) return false;
        if (mode == CombatCommit.Mode.RUN) return !held.fusing() && held.distance() > SWELL_STOP;
        return held.distance() > HOLD_RADIUS;
    }

    // the nearest lit creeper other than the one held, leaving out the capped ones
    private Seen closestLitOther(List<Seen> creepers, int held) {
        Seen best = null;
        for (Seen s : creepers) {
            if (s.id() == held || !s.lit() || _capped.contains(s.id())) continue;
            if (best == null || s.distance() < best.distance()) best = s;
        }
        return best;
    }

    private boolean cameBack(int id, long now) {
        Long at = _stepped.get(id);
        return at != null && now >= at && now - at <= REPEAT_WINDOW;
    }

    // an unlit creeper is only a threat while it can actually see us coming: about the same height and a clear line. a distance check
    // alone fired through cave walls and floors while mining. lit ones keep the plain distance, the fuse is already running
    public static final double VIEW_DY = 2;

    public static boolean inView(double dy, boolean lineOfSight) {
        return Math.abs(dy) <= VIEW_DY && lineOfSight;
    }

    // close (lit, or unlit and in view), or lit and the path is about to walk into it
    public static boolean triggers(Seen s) {
        return (s.distance() <= TRIGGER && (s.lit() || s.inView())) || (s.lit() && s.pathNear());
    }

    // nodes as {x, y, z} block corners. the node's middle is what the player walks through
    public static boolean pathPassesNear(double cx, double cy, double cz, List<int[]> nodes) {
        for (int[] n : nodes) {
            double dx = n[0] + 0.5 - cx, dy = n[1] - cy, dz = n[2] + 0.5 - cz;
            if (dx * dx + dy * dy + dz * dz <= PATH_NEAR * PATH_NEAR) return true;
        }
        return false;
    }

    private static Seen find(List<Seen> creepers, int id) {
        for (Seen s : creepers) if (s.id() == id) return s;
        return null;
    }

    // true when it was not in there yet
    private static boolean remember(Set<Integer> bag, int id) {
        if (!bag.add(id)) return false;
        if (bag.size() > MEMORY) {
            Integer oldest = bag.iterator().next();
            bag.remove(oldest);
        }
        return true;
    }
}
