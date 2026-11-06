package adris.altoclef.util.helpers;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the pure half of "do something about it, and then actually finish", for every dimension: mobs are ignored until one
// really needs dealing with, and then we commit. "really" is doing a lot of work there: the task walks on past crowds,
// shooters and lit creepers, only a mob chewing on us in contact or actual danger gets the wheel. a fight holds until
// its target is dead or gone, a run holds until we are far away and nothing is following. no world in here, just numbers, so every transition can be tested without minecraft.
// what differs between the overworld, the nether and the end is who counts as a foe and what kind of foe it is (FoeRules),
// the machine itself does not know where it is
//
// the thing it replaced is a stack of small latches (a fight latch, a charge latch, a run latch, a park timer, a hold)
// that each handed the wheel back when they expired. one commitment has one way in and one way out, and nothing in
// between lets go
public final class CombatCommit {

    // a mob that hit us is only a fight while it is this close. a skeleton plinking from ten blocks is answered by walking,
    // a moving target is a bad target
    public static final double CONTACT = CombatRules.CONTACT_RANGE;
    // the warden's sonic boom reaches about this far, and a boom is a run whatever the hp
    public static final double BOOM_RANGE = 15;
    public static final double RANGED_RUN_RANGE = 12;
    public static final float FLEE_HP = CombatRules.FLEE_HEALTH;
    // under this hp, with wither or fire on us, we do not pick a path fight
    public static final float LINGER_PICK_HP = 16;
    public static final double WITHER_PACK_RANGE = 10;
    // heavy hitters are run from at this hp instead (see CombatRules.HEAVY_FLEE_HEALTH for why it is its own number)
    public static final float HEAVY_FLEE_HP = CombatRules.HEAVY_FLEE_HEALTH;
    // at or below FLEE_HP anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = CombatRules.LOW_HP_RANGE;
    // "it hit us" is remembered this long. a skeleton that landed an arrow ten seconds ago and is still standing there
    // is the same skeleton
    public static final long HIT_MEMORY = 200;
    // a hit this recent is news (the cooldown only listens to news)
    public static final long FRESH = 5;

    // a fight target this far away and quiet for LOST_TICKS is somebody else's problem now. we are not chasing a zombie
    // across the map, the task has places to be
    public static final double LOST_RANGE = 6;
    public static final long LOST_TICKS = 60;
    // ten seconds of not getting any closer: it is on a pillar, across water, or just not coming
    public static final long STALL_TICKS = 200;
    public static final double PROGRESS = 0.5;
    // the target ran away this much from the best we had: that is the new baseline, not progress
    public static final double RUNAWAY_SLACK = 3;

    // a run is over 24 blocks out (flat) with nothing angry within 12 for two seconds, or at 25 s. it used to be 50/16/45,
    // which was a lot of jogging for one zombie
    public static final double RUN_DISTANCE = 24;
    public static final double RUN_CLEAR = 12;
    public static final long RUN_CLEAR_TICKS = 40;
    public static final long RUN_CAP = 500;
    // a run that starts within this long of the end of one that was over as clear goes on from it: its origin, and RUN_DISTANCE
    // further than we stand. a run that was over at 24 blocks with nothing within 12 while three skeletons shot from 13 came
    // straight back as a new run, whose goal was any cell 24 from where it stood: the cheapest of those was back along the cave,
    // past the skeletons, and it ran there and out again until they shot it dead. (the cooldown and two seconds; a run ended by
    // its cap starts afresh from where it got to.) at most RERUN_LEGS legs in a row, then a run starts afresh
    public static final long RERUN_TICKS = CombatCommit.COOLDOWN + 40;
    public static final int RERUN_LEGS = 3;

    // cornered: a run that went nowhere for CORNER_TICKS with something on top of us (CORNER_RANGE), or for twice that with
    // something angry anywhere within CORNER_WATCH (a shooter we can't get away from). the window only runs while something
    // angry is within CORNER_WATCH, so standing still to eat does not count as being stuck
    public static final double CORNER_WATCH = LOW_HP_RANGE;
    public static final double CORNER_RANGE = CombatRules.CONTACT_RANGE;
    public static final long CORNER_TICKS = 60;
    public static final long CORNER_FAR_TICKS = 120;
    public static final double CORNER_PROGRESS = 1.5;

    // a run that has gone nowhere for this long with nothing angry within CORNER_WATCH is not a run, it is a bot standing at
    // a lava lake with a pathfinder that keeps saying no. over, instead of spinning until the cap. with something near the
    // cornered rule above already has an answer (fight it)
    public static final long RUN_STUCK_TICKS = 100;

    // a run that is really trying to move (a path executing, or the search came back with none) and has moved us less than this
    // far over this many such ticks with a melee foe in contact (ReachRules.MELEE_REACH, eye to box) has no room: it is the
    // cornered fight now and not after CORNER_TICKS. ticks where the run is not trying (the search still going, a bite, the air,
    // water) do not count at all: standing still there is by design and says nothing about room
    public static final long NO_ROOM_TICKS = 10;
    public static final double NO_ROOM_BLOCKS = 1;

    // after any commitment ends, only a fresh hit starts the next one. low hp too: only for something on top of us or one
    // that just hit us, see trigger
    public static final long COOLDOWN = 100;

    // a mob in contact that is standing in the path (or in the block we want to place) is a fight even before it swings:
    // walking past it is not an option when it is the thing in the way, and baritone will try to place a block into it
    // forever. no path progress for WAY_BOXED_TICKS with one in contact is the same thing in a 1 wide hole, where "the path"
    // is a pillar going nowhere. the cornered rule above is the run's version of this and wants 3 s, this one 1.5
    public static final long WAY_BOXED_TICKS = 30;
    public static final double WAY_PROGRESS = 1;
    // an in-the-way fight is over once it is this far off (and not hitting us), the path is clear again
    public static final double WAY_RELEASE = 4;
    // a flyer fight lets go this far past contact, so a blaze bobbing at the edge does not flip it on and off
    public static final double FLYER_SLACK = 1.5;
    // baritone drops the path for a tick or two between giving up on a place and the next search. that is not "we stopped
    // wanting to move", so the boxed clock only starts over after this long without a path
    public static final long WAY_GAP_TICKS = 10;

    // the target is a zombie we cannot reach and another melee mob that hit us stands in reach: after this long of that we
    // swing at the one that is actually there. two zombies taking turns through each other's i-frames are not this, the
    // target stays in reach for those
    public static final long TAKEOVER_TICKS = 20;

    // a crowd is this many foes actually on us (see engaged): a run whatever the hp. two is a fight, hurt or not
    public static final int CROWD_SIZE = 3;

    // a fast melee mob that already hit us this close is not outrun: at a run decision (low hp, crowd) it is the cornered fight
    public static final double FAST_FIGHT_RANGE = 4;
    // a magma cube or slime this close is that fight whether it has hit us yet or not: a split fresh from the cube just killed
    // has not, and a bite's knockback puts the one that did past FAST_FIGHT_RANGE. cubes keep up with any run: the cream hunt
    // ran from two medium cubes at 8 hp, three blocks in six seconds with three bites taken, where each was one swing of the
    // sword (m4cream3-3 run 4, 6 Nov 2026). by lava the walk's cube rules stand (rimEngage, the lava wait), and with another
    // melee mob within LOW_HP_RANGE it is the normal run, as for the lone wither skeleton: fresh splits kept the corner while
    // two wither skeletons took the buddy from 16 to 3 (m4cube-1 run 3)
    public static final double CUBE_FIGHT_RANGE = 6;
    // that corner lets go once the fast mob has been off us this long. one tick is not enough: its own hit knocks it past the range
    // above, and at low hp that would be us turning our back on the first bite
    public static final long FAST_CORNER_RELEASE_TICKS = 20;

    // a blaze this close on our own floor is worth the walk: past this, or hovering, is the chase the rods task warns about
    public static final double BLAZE_SWAP_RANGE = 6;
    public static final double BLAZE_FLOOR_HEIGHT = 1.5;

    // a run that keeps taking hits is not working. every hit knocks us out of contact, so a mob that runs in, swings, gets
    // knocked off and runs in again never added up to a second of anything. this many hits landed while we run (each one
    // counted once) and an armed bot turns on it
    public static final int RUN_HITS = 2;
    // hits in the first this many ticks of a run do not count. a crowd run starts with them on us, and they get their swings in
    // while we turn round and the first path comes back: that is why we are running, not news that it is not working.
    // (player i-frames are about 10 ticks and a zombie swings about every 20, so this is a swing or two each)
    public static final long RUN_HIT_GRACE = 30;

    // a sniper we could not get to (a cliff skeleton, one across water) is left alone this long, a lobber for LOBBER_GIVE_UP_TICKS.
    // the run out of its sight is cheap, the fight that stalls is not, so the sniper gets three times the patience
    public static final long SNIPER_GIVE_UP_TICKS = 1800;

    // a run from shooters is over once none of them has had a line to us this long (a leaf flickering in the way is not cover)
    public static final long LOS_CLEAR_TICKS = 20;

    // how far a lobber that hit us is still worth a fight (FoeRules.WITCH_HIT_RANGE is the same number from the other side:
    // how far a witch can be and still be what the poison is blamed on)
    public static final double LOBBER_RANGE = FoeRules.WITCH_HIT_RANGE;

    // a fight with a ranged foe we chose to go and get (a lobber, or a sniper that pinned us down) that stalled is not tried
    // again for this long: the next qualifying hit in that time is a run out of its range. thirty seconds, then it is a fight again
    public static final long LOBBER_GIVE_UP_TICKS = 600;

    // a lobber counts as landing hits for the next-target pick when its last one is this fresh. the witch throws about every three
    // seconds (60 tick attack interval) and half the throws miss, so FRESH (5 ticks) would almost never be true at the moment a
    // target dies
    public static final long LOBBER_PICK_TICKS = 100;

    // the tracker only lists hostiles closer than this, and it is how far a sniper can be and still pin us down
    public static final double FOE_RANGE = 16;
    // a piglin brute with a line on us this close is a run before its first swing (8 to 13 is most of the bar)
    public static final double BRUTE_RUN_RANGE = 12;
    // a wither skeleton with a line on us this close is a fight to start in the Nether, whatever else has a line on us: it is the
    // swordsman that walks up while the blazes shoot, and a path fight that waits for it is how we get hit from both sides
    // (not past WAY_RELEASE: a start further out than the release line would clear after the hold and start again, forever)
    public static final double WITHER_START_RANGE = WAY_RELEASE;
    // an in-the-way fight on a melee foe lets go only after the foe stood past WAY_RELEASE this long without hitting us. our own
    // swing knocks it back a block or two, and one tick out there is not the path being clear
    public static final long WAY_RELEASE_TICKS = 30;
    // a shooter with a line to us that hit us this lately counts as near for the low hp rule, whatever the distance
    public static final long SHOT_MEMORY = 100;
    // a shooter that was a reason not to eat stays one this long after it stops being (a line that flickers behind a tree, a
    // skeleton that steps out past the range): the meal is 1.6 s and one arrow in it is 4
    public static final long SHOT_LATCH = 60;

    // a ranged hit on us keeps the bite closed for SHOT_MEMORY ticks, however far off the shooter stood (past FOE_RANGE it is no foe
    // at all, so nothing else would remember it)
    public static long hitLatch(long until, long now) {
        return Math.max(until, now + SHOT_MEMORY);
    }
    // a shot this fresh (two seconds) breaks the run latch for the low hp rule: the latch is for fights that ended, not for arrows
    public static final long SHOT_FRESH = 40;

    // the bail line moves with how hard we are being hit: leave when a hit like the last big one would put us at or under
    // FLEE_HP, not when we are already there. the biggest single drop in the last NEXT_HIT_WINDOW ticks (capped, a fall or a
    // creeper is not a model for the next swing) is the guess. at armor 0 with hits of 3 to 5 the line sits at 11 to 13, in full
    // iron (about 2 an arrow) it is the old 10. checking "<= 8" only ever passed at 6 when the hits skip over 8
    public static final long NEXT_HIT_WINDOW = 40;
    public static final float NEXT_HIT_CAP = 6;
    // two steps further apart than this are not one continuous watch (the chain was off), a drop across the gap is no hit
    private static final long HIT_WATCH_GAP = 10;

    // whether a run is really trying to get somewhere this tick, for noRoomFight. the "search came back with no path" half is
    // only believed while it is the latest news: any path executing or search going takes the count of failures as the new base
    public static final class Drive {
        private int base;

        public boolean trying(boolean running, boolean pathing, boolean searching, int failures, boolean usingItem, boolean onGround,
                              boolean inWater) {
            if (!running || pathing || searching) {
                base = failures;
            }
            boolean failed = running && !pathing && !searching && failures > base;
            return running && (pathing || failed) && !usingItem && onGround && !inWater;
        }
    }

    // never hit us
    public static final long NEVER = Long.MAX_VALUE / 4;

    public enum Mode {
        NONE, FIGHT, RUN
    }

    // why a commitment started, so the chain can say so when it does
    public enum Why {
        NONE, HIT, LOW_HP, HEAVY, CORNERED,
        // it was standing in our path (or we were boxed in with it) and the path could not go on
        IN_WAY,
        // the run hit its cap with something still on our heels, so we turned around (boxed in rules, like CORNERED).
        // a run that something in contact keeps biting through turns around for the same reason
        CHASED,
        // enough of them on us at once (CROWD_SIZE): not a fight to take
        CROWD,
        // something hit us and we have nothing better than bare hands. a punch is not a fight, feet are
        UNARMED,
        // a lobber we already failed to get to hit us again: not another fight, a run out of its range
        LOBBER,
        // hurt, and a shooter with a line to us hit us lately: near enough to leave, whatever the distance
        SHOT,
        // a skeleton that is shooting us and we are fit to fight it: one target, till it is dead
        PINNED,
        // a cube or an angry piglin walked up to us on a lava rim: hit it first, its first hit is a knock into the lava
        RIM,
        // a creeper we already stepped from back beside us again (CreeperStep.REPEAT_WINDOW): stepping a third time is the
        // loop, leaving is not
        REPEAT_CREEPER,
        // melee only chasers we cannot outrun, and a pillar under us they cannot reach over (PerchRules)
        PERCH
    }

    public enum Event {
        NONE, FIGHT_START, RUN_START,
        // the target died and somebody else was already on us, so no gap
        FIGHT_NEXT,
        FIGHT_DEAD, FIGHT_LOST, FIGHT_STALLED,
        // an in-the-way fight whose mob stepped off, the path is ours again
        FIGHT_CLEARED,
        FIGHT_TO_RUN,
        RUN_CLEAR, RUN_CAP, RUN_STUCK,
        RUN_TO_FIGHT,
        // the cap came with us still hurt and chased: a fight
        RUN_CHASED_FIGHT,
        // the perch starts and ends. the words are the perch notes', not CombatLog's
        PERCH_START, PERCH_END
    }

    // what sort of foe it is, which is the only thing the machine needs to know about a species
    public enum Kind {
        NORMAL,
        // hovers where we cannot walk up to it (a blaze). it counts as a shooter (FoeRules.shoots), and like any shooter
        // it is only a fight once it is in contact
        FLYER,
        // lands 8 to 13 in one swing (hoglin, brute, vindicator): a run at HEAVY_FLEE_HP instead of FLEE_HP
        HEAVY,
        // not a fight at any hp (warden, wither): whatever fires, it is a run
        UNTOUCHABLE,
        // throws things from where it stands (the witch). a poison that keeps ticking is the hit, so one that hit us is a fight
        // from LOBBER_RANGE and not only in contact: it never walks up to us, and waiting for it is how a minute of poison happens
        LOBBER,
        // angry at us and not worth leaving: it teleports and chases, so a run is just free hits. fought to the end at any hp
        // (the water floor is the chain's, see EndermanWater). a calm one is never a foe at all
        ENDERMAN
    }

    // an angry hostile that can hurt us, as plain numbers. sinceHit: ticks since it hit us (NEVER for not yet)
    // reachGap: eye to the nearest point of its box, the three block reach (ReachRules). infinite when nobody measured
    // it, which is every foe that does not come from the world: the no room rule only believes a gap somebody really took
    // sight: it has a line to us (only asked for shooters that hit us lately, so false is the default and the cheap answer).
    // sinceHitBefore: ticks since the hit before the last one (NEVER for fewer than two), a shooter that keeps hitting us (nothing reads it since the pin window went, it is just carried)
    // fast: it moves at CombatRules.FAST_MOB_SPEED or better (spiders, slimes and magma cubes of any size, piglins, angry zombified piglins), so a run does not shake it
    // splitType: "slime" or "magma_cube" for the cube family, null for everything else. splits: it is big enough to leave splits
    // behind when it dies. x/y/z is where it stands (only read for the family, see splitHeir)
    public record Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                      boolean sight, long sinceHitBefore, boolean fast, String splitType, boolean splits, double x, double y, double z,
                      boolean knocker, boolean brute, boolean wet, boolean wither, boolean patrol) {
        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore, boolean fast, String splitType, boolean splits, double x, double y, double z,
                   boolean knocker, boolean brute, boolean wet, boolean wither) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, false);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore, boolean fast, String splitType, boolean splits, double x, double y, double z,
                   boolean knocker) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, false, false, false, false);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore, boolean fast) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, null, false, 0, 0, 0, false, false, false, false, false);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap,
                   boolean sight, long sinceHitBefore) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, false);
        }

        public Foe withFast(boolean fast) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        // knocker: a melee hit of its shoves us (cubes of any size, an angry zombified piglin), see CombatRules.rimKnocker
        public Foe withKnocker(boolean knocker) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        // brute: a piglin brute, the one heavy hitter we run from before it swings (see bruteComing)
        public Foe withBrute(boolean brute) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        // wither: a wither skeleton, the fortress swordsman we start on when it comes within WITHER_START_RANGE (see witherComing)
        public Foe withWither(boolean wither) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        // patrol: a pillager and we are dressed and armed for it (FoeRules.patrol). it keeps up with a run at 0.35 and a run kills
        // nothing, so a pack of them is one target at a time until the hp line, not a crowd or a second shooter to leave
        public Foe withPatrol(boolean patrol) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        // wet: it, or we, are in the water. a trident drowned in the sea is not a skeleton on a ledge, see sniper
        public Foe withWet(boolean wet) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        public Foe withSplit(String splitType, boolean splits, double x, double y, double z) {
            return new Foe(id, distance, ranged, creeper, sinceHit, kind, reachGap, sight, sinceHitBefore, fast, splitType, splits, x, y, z, knocker, brute, wet, wither, patrol);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind, double reachGap) {
            this(id, distance, ranged, creeper, sinceHit, kind, reachGap, false, NEVER);
        }

        // a plain mob
        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit) {
            this(id, distance, ranged, creeper, sinceHit, Kind.NORMAL);
        }

        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind) {
            this(id, distance, ranged, creeper, sinceHit, kind, Double.POSITIVE_INFINITY);
        }

        // how far away a hit of its counts as "on us" for the fight rules: in contact, or for a lobber where it stands
        public double strikeRange() {
            return kind == Kind.LOBBER ? LOBBER_RANGE : CONTACT;
        }

        // close enough that a swing of ours lands, by the three block number
        public boolean inReach() {
            return reachGap <= ReachRules.MELEE_REACH;
        }

        // close enough that the kill task swings instead of letting it come (ReachRules.killReach, on a plain player's arm). the
        // aura goes by inReach, so for the last 0.3 of it the aura may swing and the task won't
        public boolean inTaskReach() {
            return reachGap <= ReachRules.MELEE_REACH - ReachRules.KILL_REACH_MARGIN;
        }

        // never a reason to run, whatever the hp or the headcount
        public boolean standsAndFights() {
            return kind == Kind.ENDERMAN;
        }

        public boolean melee() {
            return !ranged && !creeper;
        }

        public boolean hitUs() {
            return sinceHit <= HIT_MEMORY;
        }

        public boolean fresh() {
            return sinceHit <= FRESH;
        }

        // a plain bow-and-arrow sort of shooter: no lobber (own rule), no hoverer, nothing we only run from. none of them in the
        // water: nobody walks up to a skeleton by swimming, and a trident drowned out there swims faster than we do, so the
        // hp runs stay on (the lone sniper exemption would pin us in a fight we cannot take)
        public boolean sniper() {
            return bow() && !wet;
        }

        // the sniper with the water left out: a skeleton up to its knees still shoots, and on a swim every one of them reads as wet
        public boolean bow() {
            return ranged && !creeper && kind == Kind.NORMAL;
        }

        // it shot us lately and still has a line: at low hp that is as good as standing next to us
        public boolean shooting() {
            return ranged && !creeper && sight && distance <= FOE_RANGE && sinceHit <= SHOT_MEMORY;
        }

        // this one is a run, not a fight, at this hp
        public boolean mustRun(float health) {
            return kind == Kind.UNTOUCHABLE || (kind == Kind.HEAVY && health <= HEAVY_FLEE_HP);
        }

        // something we only ever run from, close enough to matter. the warden: within 8, or booming us from out to 15. a
        // heavy hitter only once it has got to us (hit us from within 8, or in contact): one just standing around in a
        // bastion is walked past like anything else, otherwise the nether is one long jog
        public boolean danger(float health) {
            if (!mustRun(health)) return false;
            // (a shooter of them from twelve: a trident drowned throws from ten, so eight let the throw come first)
            if (kind == Kind.UNTOUCHABLE) return distance <= (ranged ? RANGED_RUN_RANGE : LOW_HP_RANGE) || (hitUs() && distance <= BOOM_RANGE);
            return distance <= CONTACT || (hitUs() && distance <= LOW_HP_RANGE);
        }
    }

    // everything one tick of the machine wants to know. target is the foe we are fighting looked up on its own (it can be
    // alive and outside the foe list), null when it is dead or gone. way is the path's side of things. armed: anything better
    // than a fist in the bag (a sword, an axe, or a tool that hits harder than a hand)
    // trying: the run is really trying to get somewhere this tick, see noRoomFight. false by default, so nothing that does not say
    // so can flip a run into a fight with it
    // creeperRepeat: CreeperStep just started a step on a creeper that came back (CreeperStep.escalate), idle makes it a run
    // body: hunger, food and whether a heal-up hold is allowed at all (RecoverHold). NONE by default, which never holds
    // boxed: every side of us is a wall or a mob (BoxRules), so a run has nowhere to go. false by default
    // nextHit: the machine's own guess at the next hit (see NEXT_HIT_WINDOW), filled in by step() from the hp it watches. whatever
    // a caller puts there is replaced, 0 by default
    // watched: a shooter that is not a foe (an uncharged blaze at range is not in the hostile list) has a line to us right now. only the
    // heal-up hold's re-run reads it, so it breaks the line before the meal and not after. false by default
    // lingering: wither, poison or fire is on us right now, so the damage keeps coming after the swing that started it. false by default
    public record Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                       boolean creeperRepeat, RecoverHold.Body body, boolean boxed, float nextHit, boolean watched, boolean lingering) {
        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body, boolean boxed, float nextHit, boolean watched) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, nextHit, watched, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body, boolean boxed, float nextHit) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, nextHit, false, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body, boolean boxed) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, 0);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat, RecoverHold.Body body) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying,
                    boolean creeperRepeat) {
            this(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, RecoverHold.Body.NONE);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed, boolean trying) {
            this(now, health, x, z, foes, target, way, armed, trying, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way, boolean armed) {
            this(now, health, x, z, foes, target, way, armed, false);
        }

        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target, Way way) {
            this(now, health, x, z, foes, target, way, true, false);
        }

        // no path in the picture
        public Tick(long now, float health, double x, double z, List<Foe> foes, Foe target) {
            this(now, health, x, z, foes, target, Way.NONE, true, false);
        }

        // hp is at or under the bail line, counting the next hit
        public boolean low() {
            return health - nextHit <= FLEE_HP;
        }

        // hp as the heavy hitter rules see it. their line is the higher of HEAVY_FLEE_HP and the moving one, so past the 2 it
        // already sits above the flee line the hp slides down by the rest, instead of every call learning a second number
        public float guardHealth() {
            return CombatCommit.guardHealth(health, nextHit);
        }

        Tick withNextHit(float hit) {
            return new Tick(now, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, hit, watched, lingering);
        }

        Tick withNow(long at) {
            return new Tick(at, health, x, z, foes, target, way, armed, trying, creeperRepeat, body, boxed, nextHit, watched, lingering);
        }
    }

    // inWay: ids of mobs sitting in the next few path nodes or the block baritone wants to place. pathing: something wants us
    // to move (a path or a search going), y: our height, a pillar out of a hole is progress too
    // weapon: a sword or an axe in the bag (not just a tool), which is what an in the way fight or a walk up to a skeleton needs.
    // true when nobody asked
    // rim: we stand on a lava rim or a ledge over a drop (LavaShore.edgeNear) with a melee foe close, path or no path
    // lavaClose: lava within LavaShore.GROUND_CLEAR of us. only a cube ever asks, so the brain only looks with one about
    public record Way(Set<Integer> inWay, boolean pathing, double y, boolean weapon, boolean rim, boolean lavaClose) {
        public static final Way NONE = new Way(Set.of(), false, 0);

        public Way(Set<Integer> inWay, boolean pathing, double y) {
            this(inWay, pathing, y, true);
        }

        public Way(Set<Integer> inWay, boolean pathing, double y, boolean weapon) {
            this(inWay, pathing, y, weapon, false, false);
        }

        public Way withRim(boolean rim) {
            return new Way(inWay, pathing, y, weapon, rim, lavaClose);
        }

        public Way withLavaClose(boolean lavaClose) {
            return new Way(inWay, pathing, y, weapon, rim, lavaClose);
        }
    }

    // what started a commitment: the mob, and the reason (HEAVY and LOW_HP are runs, the foe is just who to name)
    public record Trigger(Why why, Foe foe) {
    }

    // the rule for "does anything here really need dealing with". almost always no: a crowd around us, a skeleton
    // shooting from range, a lit creeper and a zombie ten blocks off are all scenery and the task walks on.
    // freshOnly is the cooldown. danger is never old news, low hp only counts something in contact or a fresh hit, and
    // the fight only a fresh hit.
    // 1. something we never fight at this hp has got to us (a heavy hitter at 10 hp that hit us or is in contact, the
    //    warden within 8 or booming us): a run
    // 2. we are hurt and something angry is within 8 (or a shooter with a line to us has hit us in the last SHOT_MEMORY ticks,
    //    from anywhere): a run. in the cooldown only if it is on top of us or just hit us,
    //    a run that ran out of time or a fight we turned around for ends with the others still about, and "within 8"
    //    alone would start the same run all over again
    // 3. it hit us and it is in contact: a fight. hitting back is the only way that one stops
    public static Trigger trigger(float health, List<Foe> foes, boolean freshOnly) {
        return trigger(health, 0, foes, freshOnly);
    }

    static float guardHealth(float health, float nextHit) {
        return health - Math.max(0, nextHit - (HEAVY_FLEE_HP - FLEE_HP));
    }

    public static Trigger trigger(float health, float nextHit, List<Foe> foes, boolean freshOnly) {
        return trigger(health, nextHit, nextHit, foes, freshOnly);
    }

    // lowHit is what the low hp line projects, nextHit still feeds the heavy hitter guard. they differ in a one on one fight
    // (see lowHit), where the real hp is the line and not the hp after the next swing lands
    static Trigger trigger(float health, float nextHit, float lowHit, List<Foe> foes, boolean freshOnly) {
        return trigger(health, nextHit, lowHit, foes, freshOnly, false);
    }

    // onFight: there is a fight target (see shooterFight)
    static Trigger trigger(float health, float nextHit, float lowHit, List<Foe> foes, boolean freshOnly, boolean onFight) {
        float guard = guardHealth(health, nextHit);
        Foe danger = null;
        Foe near = null;
        int on = 0;
        boolean shot = false;
        for (Foe foe : foes) {
            if (foe.danger(guard) && (danger == null || foe.distance < danger.distance)) danger = foe;
            boolean counts = !freshOnly || foe.distance <= CONTACT || foe.fresh();
            // a shooter with a line that hit us lately is near enough, however far it stands
            if (counts && (foe.distance <= LOW_HP_RANGE || foe.shooting()) && (near == null || foe.distance < near.distance)) near = foe;
            if (foe.kind() == Kind.NORMAL && !foe.creeper() && !foe.ranged() && engaged(foe, freshOnly)) on++;
            if (foe.ranged() && !foe.creeper() && foe.shooting()) shot = true;
        }
        if (danger != null) return new Trigger(Why.HEAVY, danger);
        // (a crowd as the crowd rule counts it, the skeleton on us among them, is that rule's and not a low hp run)
        float line = crowded(health, foes, freshOnly, onFight) ? FLEE_HP : fleeLine(on, shot);
        // (the buddy's fork: a lone wither skeleton is a fight we stay in before it is our target too, see lowHit and loneWither)
        if (loneWither(foes, freshOnly, guard)) lowHit = Math.min(lowHit, LINGER_HIT);
        if (health - lowHit <= line && near != null && !shooterFight(foes, freshOnly, guard, onFight)) return new Trigger(near.distance <= LOW_HP_RANGE ? Why.LOW_HP : Why.SHOT, near);
        Foe hit = hitInContact(foes, freshOnly);
        return hit == null ? null : new Trigger(Why.HIT, hit);
    }

    // one wither skeleton on our floor and nothing else on us, shooting at us or dangerous: the hp line is the one a fight
    // against it keeps (lowHit), not the one after its next 4.5. a skeleton that hit us on the walk, outside a fight, sent us
    // off at 12 hp; in a fortress's corridors the run's path turned back into it and a bite on the way stopped us, five hits
    // taken running and seven of eight from the front with the shield down, where the fight's shield had taken all three it
    // was up for (attempt 220, 4 Nov 2026)
    static boolean loneWither(List<Foe> foes, boolean freshOnly, float guard) {
        int withers = 0;
        int on = 0;
        for (Foe foe : foes) {
            if (foe.danger(guard)) return false;
            if (foe.creeper() && foe.distance() <= LOW_HP_RANGE) return false;
            if (foe.shooting()) return false;
            if (foe.wither() && foe.distance() <= WITHER_PACK_RANGE && Math.abs(foe.y()) <= 4) withers++;
            if (foe.melee() && engaged(foe, freshOnly)) on++;
        }
        return withers == 1 && on <= 1;
    }

    // the low hp line with company short of a crowd: two more with a second plain melee mob on us, two more with a shooter that
    // has a line on us while one is. one zombie after another in a cave at y -51, never three on it at once (so no crowd), each
    // killed and each landing a hit first: it left at 9 hp with two zombies and a skeleton on it and was dead before the run got
    // going, in full iron (the runner, 3 Nov 2026, attempt 96). a crowd keeps its own rule (at any hp), and heavy hitters,
    // creepers and anything alone keep FLEE_HP
    static final float COMPANY_FLEE_STEP = 2;

    static float fleeLine(int on, boolean shot) {
        if (on == 0 || on >= CROWD_SIZE) return FLEE_HP;
        return FLEE_HP + (on >= 2 ? COMPANY_FLEE_STEP : 0) + (shot ? COMPANY_FLEE_STEP : 0);
    }

    // the closest thing that hit us and is close enough to swing at
    private static Foe hitInContact(List<Foe> foes, boolean freshOnly) {
        Foe hit = null;
        for (Foe foe : foes) {
            boolean hitCounts = freshOnly ? foe.fresh() : foe.hitUs();
            if (hitCounts && foe.distance <= foe.strikeRange() && (hit == null || foe.distance < hit.distance)) hit = foe;
        }
        return hit;
    }

    // actually on us: it hit us and is within the swarm range, or it is in contact and could hit us (no creepers, those are
    // the creeper step's). bystanders do not count, three zombies milling about at five blocks are scenery as usual
    static boolean engaged(Foe foe, boolean freshOnly) {
        boolean hit = freshOnly ? foe.fresh() : foe.hitUs();
        if (hit && foe.distance() <= CombatRules.SWARM_RANGE) return true;
        return !foe.creeper() && foe.distance() <= CONTACT;
    }

    // one skeleton that is shooting us and at most one melee mob on us: kill the melee one in contact, then walk up to the skeleton and kill that. none
    // of the hp runs (LOW_HP, SHOT) apply to it: in a cave there is no outrunning arrows, every run was just
    // more arrows in the back and the skeleton never died. this deliberately beats the old "bail at hp 8" line for this one shape.
    // two shooters, a creeper close, a third mob on us or anything the danger rules want is not this shape, those keep their runs
    static boolean shooterFight(List<Foe> foes, boolean freshOnly, float guard) {
        return shooterFight(foes, freshOnly, guard, false);
    }

    // onFight: a fight is on, so a sniper that hit us lately still counts as landing arrows when its line flickers or it reloads
    static boolean shooterFight(List<Foe> foes, boolean freshOnly, float guard, boolean onFight) {
        int snipers = 0;
        boolean landing = false;
        int on = 0;
        for (Foe foe : foes) {
            if (foe.danger(guard)) return false;
            if (foe.creeper() && foe.distance() <= LOW_HP_RANGE) return false;
            if (foe.sniper()) {
                snipers++;
                landing |= foe.shooting() || (onFight && foe.hitUs() && foe.distance() <= FOE_RANGE);
            } else if (foe.ranged() && !foe.creeper() && foe.shooting()) {
                // a witch or a blaze with a line on us is its own rule
                return false;
            }
            // (melee only: the skeleton itself counts as engaged once it has hit us, and it is the one we are making room for)
            if (foe.melee() && engaged(foe, freshOnly)) on++;
        }
        return snipers == 1 && landing && on <= 1;
    }

    // too many on us to trade blows with: CROWD_SIZE of them at any hp
    public static boolean crowded(float health, List<Foe> foes, boolean freshOnly) {
        return crowded(health, foes, freshOnly, false);
    }

    static boolean crowded(float health, List<Foe> foes, boolean freshOnly, boolean onFight) {
        int on = 0;
        for (Foe foe : foes) {
            // (a patrol is the one crowd we stay for, see withPatrol. the hp line still ends it)
            if (engaged(foe, freshOnly) && !foe.patrol()) on++;
        }
        return on >= CROWD_SIZE;
    }

    // what the food chain is up to, for a run's meal: a shooter has a line on us, how far the nearest fast melee mob is (see
    // CombatRules.FAST_MOB_SPEED, infinity for none), and whether a bite is already under way
    // nearestMelee: the closest melee mob of any kind (infinity for none), hungry: the food bar is not full. both only matter to a
    // fight, which may take a bite when nothing can reach us (see fightBite)
    // nearestWither: the closest tracked wither skeleton, seen or not, foe or not (infinity for none). one that walked behind a
    // pillar is still walking at us, and the bite it caught was the one it came round the corner for
    public record Bite(boolean shotAt, double nearestFast, boolean biting, double nearestMelee, boolean hungry, double nearestWither) {
        public static final Bite NONE = new Bite(false, Double.POSITIVE_INFINITY, false);

        public Bite(boolean shotAt, double nearestFast, boolean biting) {
            this(shotAt, nearestFast, biting, 0, false);
        }

        public Bite(boolean shotAt, double nearestFast, boolean biting, double nearestMelee, boolean hungry) {
            this(shotAt, nearestFast, biting, nearestMelee, hungry, Double.POSITIVE_INFINITY);
        }

        Bite notBiting() {
            return new Bite(shotAt, nearestFast, false, nearestMelee, hungry, nearestWither);
        }
    }

    // a fight is no mealtime, except when we are hungry and no melee mob is within FIGHT_BITE_RANGE: the skeleton we are walking up
    // to cannot stop a bite, and regen needs the food bar (hunger 17 with no saturation means no regen at all)
    static final double FIGHT_BITE_RANGE = 4;

    private static boolean fightBite(Bite bite) {
        return bite.hungry() && bite.nearestMelee() > FIGHT_BITE_RANGE;
    }

    // is this foe a reason not to sit down to a meal: a shooter in SHOOTER_RANGE with a line to us right now, or one that already
    // hit us lately and still has a line, however far it stands (the range is for the ones that have not proved anything yet).
    // lineToUs is the ray the caller cast, only asked for the first kind
    public static boolean shotAt(Foe foe, boolean lineToUs) {
        if (!foe.ranged() || foe.creeper()) return false;
        return foe.shooting() || (foe.distance() <= CombatRules.SHOOTER_RANGE && (foe.sight() || lineToUs));
    }

    // fight / flee / eat for the food chain. a fight is never a mealtime. a run is one on a hysteresis pair: a bite may start
    // with nothing angry within RUN_BITE_START (nothing fast within RUN_BITE_START_FAST, nothing shooting at us), and once it
    // has started only a threat inside RUN_BITE_ABORT drops it. a hurt bot that never eats while it runs just bleeds slower,
    // and one line for both made every follower at 9 cancel the bite (the numbers are in CombatRules). the commitment stays
    // through a bite, it does not even hand the wheel over.
    // with nobody committed it is calm unless a lit creeper is close or we are hurt with something near. nearest is the
    // distance to the closest angry thing, infinity for none
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple) {
        return stance(mode, health, nearest, creeperClose, gapple, Bite.NONE);
    }

    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite) {
        return stance(mode, health, nearest, creeperClose, gapple, bite, false);
    }

    // recovering: the heal-up hold is on (RecoverHold). with nothing committed it eats by the run's bite lines, not the calm
    // ones: we are standing about hurt with whatever we ran from somewhere out there
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite, boolean recovering) {
        return stance(mode, health, nearest, creeperClose, gapple, bite, recovering, true);
    }

    // hasFood: see CombatRules.stance
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple,
                                            Bite bite, boolean recovering, boolean hasFood) {
        boolean fighting = fighting(mode, recovering, health, nearest, creeperClose, bite);
        // chewing a gapple counts as "biting" too, and the abort line is 5: without this the gapple that a hurt run wants at
        // 5 to 10 blocks would be dropped on the next tick and picked up on the one after, forever
        if (!fighting && gapple && mode == Mode.RUN && bite.biting() && health <= FLEE_HP) {
            fighting = fighting(mode, recovering, health, nearest, creeperClose, bite.notBiting());
        }
        // (at gapple hp a fight keeps the gapple stance below, that bite is the better one)
        if (mode == Mode.FIGHT && gapple && health <= FLEE_HP) fighting = true;
        // a calm meal right after a ranged hit is the same arrow, hurt or not (the golden apple is still fine)
        if (!fighting && mode == Mode.NONE && bite.shotAt() && health <= FLEE_HP && !gapple) return CombatRules.Stance.FLEE;
        if (!fighting) return CombatRules.Stance.CALM;
        CombatRules.Stance stance = CombatRules.stance(true, health, nearest, gapple, hasFood);
        // the quick bite at hp 4 is for nothing next to us. committed means something is close, so feet first, bite later
        // (and not right after a ranged hit either: a quick bite at hp 4 in a shooter's sight is the arrow that finishes the meal)
        if (stance == CombatRules.Stance.EAT && (mode != Mode.NONE || bite.shotAt())) return CombatRules.Stance.FLEE;
        return stance;
    }

    private static boolean fighting(Mode mode, boolean recovering, float health, double nearest, boolean creeperClose, Bite bite) {
        return switch (mode) {
            case FIGHT -> !fightBite(bite);
            case RUN -> runBiteBlocked(nearest, bite);
            default -> creeperClose || (health <= FLEE_HP && nearest <= LOW_HP_RANGE) || (recovering && runBiteBlocked(nearest, bite));
        };
    }

    private static boolean runBiteBlocked(double nearest, Bite bite) {
        return bite.biting() ? nearest < CombatRules.RUN_BITE_ABORT
                : bite.shotAt() || nearest < CombatRules.RUN_BITE_START || bite.nearestFast() < CombatRules.RUN_BITE_START_FAST
                || bite.nearestWither() < CombatRules.RUN_BITE_START;
    }

    // why the stance is what it is, for the log line that says so when it changes (and so why a hurt bot is not eating).
    // only ever built when a line is going out
    public static String stanceWhy(Mode mode, float health, double nearest, boolean creeperClose, Bite bite) {
        return stanceWhy(mode, health, nearest, creeperClose, bite, false);
    }

    public static String stanceWhy(Mode mode, float health, double nearest, boolean creeperClose, Bite bite, boolean recovering) {
        String near = Double.isInfinite(nearest) ? "nothing angry in sight" : "nearest " + Math.round(nearest) + " blocks";
        boolean holding = recovering && mode == Mode.NONE;
        if (!fighting(mode, recovering, health, nearest, creeperClose, bite)) {
            if (mode == Mode.FIGHT) return "fighting, no melee mob within " + (int) FIGHT_BITE_RANGE;
            if (holding) return "recovering, " + near + ", free to eat";
            return mode == Mode.RUN ? "running, " + near + ", free to eat" : "calm, " + near;
        }
        // (the lit creeper and the low hp line say their own thing, the rest is the run's bite lines)
        if (holding && !creeperClose && !(health <= FLEE_HP && nearest <= LOW_HP_RANGE)) return "recovering, " + near + ", too close to eat";
        return switch (mode) {
            case FIGHT -> "fighting, no meals mid fight";
            case RUN -> bite.biting() ? "running, " + near + ", too close to keep chewing"
                    : bite.shotAt() ? "running, a shooter has a line on us"
                    : nearest >= CombatRules.RUN_BITE_START && bite.nearestWither() < CombatRules.RUN_BITE_START
                    ? "running, a wither skeleton " + Math.round(bite.nearestWither()) + " blocks off, too close to start a bite"
                    : nearest >= CombatRules.RUN_BITE_START ? "running, something fast is too close to start a bite"
                    : "running, " + near + ", too close to eat";
            default -> creeperClose ? "a lit creeper is close"
                    : "hp " + Math.round(health) + " with something within " + (int) LOW_HP_RANGE + ", " + near;
        };
    }

    // ---- state

    private Mode mode = Mode.NONE;
    private Why why = Why.NONE;
    private int targetId = -1;
    // what the target was and where it stood the last time we saw it alive, for the splits it leaves behind (see splitHeir)
    private String lastSplitType;
    private boolean lastSplits;
    // the target was an angry enderman: teleporting out of the foe list is not dying (see targetGone)
    private boolean lastEnder;
    private double lastSplitX;
    private double lastSplitY;
    private double lastSplitZ;
    // when the splitter's target was first seen gone, -1 while it is alive
    private long splitGoneAt = -1;
    private boolean cornered;
    // when a run from a creeper that keeps coming back ended within CREEPER_RUN_NOWHERE of where it started (-1: never)
    private long creeperRunNowhereAt = -1;
    static final double CREEPER_RUN_NOWHERE = 3;
    static final long CREEPER_FIGHT_AFTER = 600;

    private static Foe nearestCreeper(Tick t) {
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.creeper() && foe.distance() <= LOW_HP_RANGE && (best == null || foe.distance() < best.distance())) best = foe;
        }
        return best;
    }

    private double originX;
    private double originZ;
    // how far from the origin this run has to get (RUN_DISTANCE, or more for a run that goes on from the last, see RERUN_TICKS)
    private double runDistance = RUN_DISTANCE;
    private int runLegs;
    // the last run's origin, legs and end, for the next run that starts soon after it
    private double lastRunX;
    private double lastRunZ;
    private int lastRunLegs;
    private long lastRunEnd = Long.MIN_VALUE / 2;
    private boolean lastRunClear;
    private long startTick;
    private long lastTick = Long.MIN_VALUE;
    // how far step() pulled the clock forward this tick. the foes' sinceHit counts on the raw clock, so a hit's own time is
    // now - sinceHit - skew (or the same hit reads two ticks newer every time the clock slips and counts twice)
    private long clockSkew;
    private float lastHp;
    private final java.util.ArrayDeque<HitSize> hitSizes = new java.util.ArrayDeque<>();
    private long cooldownUntil = Long.MIN_VALUE / 2;
    // the commitment that ended on this step right before the one step() returned started, NONE when nothing chained
    private Event endedFirst = Event.NONE;

    // fight bookkeeping
    private double bestDistance;
    private long progressAt;
    private long farSince = -1;
    // when a melee in-the-way target first stood past WAY_RELEASE, -1 while it is close or has hit us
    private long releaseSince = -1;

    // run bookkeeping
    private long clearSince = -1;
    // this run is from shooters: it is over when none has a line to us (LOS_CLEAR_TICKS), not when we are 12 blocks off, and
    // noSightSince is the clock on that
    private boolean losRun;
    private long noSightSince = -1;
    // a run turned into a fight (cornered, chased, no room): no new run starts until nothing is within RUN_CLEAR for
    // RUN_CLEAR_TICKS, the run's own clear test. only the warden and the heavy hitters get past it. without this the fight ended
    // with a cube bouncing out at five blocks and the same tick's low hp rule started a fresh run with a fresh grace
    private boolean runLatch;
    private long latchClearSince = -1;
    // times the latch let a low hp or crowd run through since it last fully cleared. one: a pack behind us does not get to eat
    // us one zombie at a time, but a second turn after that run is final, so run, turn, run, turn cannot flap
    private int latchBreaks;
    // the latch came from a turn on a chase (hitThrough, the cap). a corner, a box or a fast mob made it for a reason, those stay final
    private boolean latchChased;
    // hits landed on us this run after the grace, and per foe the tick of the last one we counted: one hit seen for a hundred
    // ticks is still one hit, the next swing from the same mob is a new one
    private int runHits;
    private final Map<Integer, Long> runHitAt = new HashMap<>();
    private long cornerSince = -1;
    private double cornerX;
    private double cornerZ;
    private long stuckSince = -1;
    private double stuckX;
    private double stuckZ;
    private long noRoomSince = -1;
    private int noRoomCount;
    private double noRoomX;
    private double noRoomZ;
    // the fight we are in came from the no room rule (the log says so)
    private boolean noRoom;
    // this fight stands in for a run (it came from one, or a run was skipped because we are boxed in): when it ends hurt the
    // hold uses the run's hp line, not the fight's
    private boolean runLine;
    // cornered only because we are boxed in right now: when the box opens the normal bail rules are back
    private boolean boxCorner;
    // cornered because a fast mob is on us (not a box): the warden and the heavy hitters still get us out of there
    private boolean fastCorner;
    // that fast corner is the lone wither skeleton one (loneWither): a crowd still bails it
    private boolean witherCorner;
    // since when nothing fast has been on us in a fast corner, -1 while something is
    private long fastClearSince = -1;
    // since when the melee target has been out of reach (takeover)
    private long outReachSince = -1;

    // the biggest hit taken since this chase started, kept across run renewals while the chasers overlap (chaseIds) and wiped once
    // nothing is committed. nextHit alone forgets a 4 damage swing two seconds later, and the chase decisions are for the long game
    private float runMaxHit;
    private Set<Integer> chaseIds = new HashSet<>();
    private Set<Integer> joiners = Set.of();

    // the perch (PerchRules): the world's side of this tick, fed by the brain. null means no facts and no perch
    private PerchRules.Facts perch;
    private int perchH;
    private double perchStartY;
    private int perchColX;
    private int perchColZ;
    // since when we have been standing at the top of it, -1 while still building
    private long perchTopSince = -1;
    private long perchQuietSince = -1;
    private long perchCapAt;
    private long perchStartedAt;
    private int perchShotBase;
    private String perchNote;
    // a perch that failed (we got hit on top, knocked off, no pillar) is not tried again for a while, that is what the old rules are for
    private long perchFailedAt = Long.MIN_VALUE / 2;
    private long lastPerchAt = Long.MIN_VALUE / 2;
    private double lastPerchX;
    private double lastPerchZ;
    private int rePerches;

    // the idle boxed-in window (inTheWay)
    private long wayBoxSince = -1;
    private long wayGapSince = -1;
    private double wayBoxX;
    private double wayBoxY;
    private double wayBoxZ;

    // when a fight with a lobber last stalled, for the run that replaces the next fight with it (see LOBBER_GIVE_UP_TICKS)
    private long lobberGaveUpAt = Long.MIN_VALUE / 2;
    // the same for a sniper we went after because it pinned us down. its own clock: a witch we could not reach says nothing
    // about a skeleton
    private long sniperGaveUpAt = Long.MIN_VALUE / 2;
    // this fight is with a ranged foe we chose to go and get (a lobber, or a sniper that is shooting us): a stall or a lost track means we
    // could not get to it, and that is remembered
    private boolean atRange;
    // this fight is on a lobber we picked because it was landing hits when the last target died (lobberNext): only the hard floor
    // and the danger rules leave it, see fight
    private boolean lobberPick;
    // this fight got into contact with its target at some point (a lobber we never touched was out of reach, whatever ended it)
    private boolean contacted;

    // mobs we gave up on, and when. they get another go when they hit us again
    private final Map<Integer, Long> ignored = new HashMap<>();
    // heavy hitters we already ran from for standing in the path, and when. the second time one of them is in the way we walk on
    // (it hits us and that is the HIT fight, or the path goes round) instead of running the same 24 blocks over and over
    private final Map<Integer, Long> ranFrom = new HashMap<>();

    // heal up before going back in: on after a commitment that ended hurt. not a mode, nothing is committed while it holds
    private final RecoverHold recover = new RecoverHold();

    public Mode mode() {
        return mode;
    }

    public Why why() {
        return why;
    }

    public int targetId() {
        return targetId;
    }

    public boolean cornered() {
        return cornered;
    }

    public boolean noRoom() {
        return noRoom;
    }

    // which corner this is, for the log. they all read "cornered" otherwise and they do not end the same way. null when none of these
    public String cornerKind() {
        if (!cornered) return null;
        if (boxCorner) return "box";
        if (witherCorner) return "wither";
        return fastCorner ? "fast" : null;
    }

    // the brain hands over the world's side of the perch every tick, right before step
    public void perchFacts(PerchRules.Facts facts) {
        this.perch = facts;
    }

    public boolean perching() {
        return mode == Mode.FIGHT && why == Why.PERCH;
    }

    public int perchHeight() {
        return perching() ? perchH : 0;
    }

    public String takePerchNote() {
        String note = perchNote;
        perchNote = null;
        return note;
    }

    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    public double runDistance() {
        return runDistance;
    }

    public long startTick() {
        return startTick;
    }

    // so the chain can still say how the old one ended when step() hands back the start of the next
    public Event endedFirst() {
        return endedFirst;
    }

    public boolean coolingDown(long now) {
        return now < cooldownUntil;
    }

    public boolean isIgnored(int id) {
        return ignored.containsKey(id);
    }

    // the heal-up hold is on. it outlives a re-run: a run that started from the hold comes back to it
    public boolean recovering() {
        return recover.on();
    }

    public RecoverHold recoverHold() {
        return recover;
    }

    public void reset() {
        lastSplitType = null;
        lastSplits = false;
        lastEnder = false;
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        lastTick = Long.MIN_VALUE;
        clockSkew = 0;
        hitSizes.clear();
        cooldownUntil = Long.MIN_VALUE / 2;
        endedFirst = Event.NONE;
        runLatch = false;
        latchClearSince = -1;
        latchBreaks = 0;
        latchChased = false;
        farSince = -1;
        releaseSince = -1;
        losRun = false;
        noSightSince = -1;
        clearSince = -1;
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        noRoomSince = -1;
        noRoom = false;
        runLine = false;
        boxCorner = false;
        fastCorner = false;
        witherCorner = false;
        fastClearSince = -1;
        outReachSince = -1;
        wayBoxSince = -1;
        wayGapSince = -1;
        ignored.clear();
        ranFrom.clear();
        runMaxHit = 0;
        chaseIds.clear();
        joiners = Set.of();
        perch = null;
        perchH = 0;
        perchNote = null;
        perchFailedAt = Long.MIN_VALUE / 2;
        lastPerchAt = Long.MIN_VALUE / 2;
        rePerches = 0;
        lobberGaveUpAt = Long.MIN_VALUE / 2;
        sniperGaveUpAt = Long.MIN_VALUE / 2;
        atRange = false;
        recover.reset();
    }

    // a ranged foe that hit us when we already failed to reach one of its kind lately: running is the answer, a second fight
    // would stall the same (one that has walked into contact is a plain fight, that is the reachable witch)
    private boolean gaveUpOn(Foe foe, long now) {
        long at = foe.kind() == Kind.LOBBER ? lobberGaveUpAt : sniperGaveUpAt;
        boolean ranged = foe.kind() == Kind.LOBBER || foe.sniper();
        long patience = foe.kind() == Kind.LOBBER ? LOBBER_GIVE_UP_TICKS : SNIPER_GIVE_UP_TICKS;
        return ranged && foe.distance() > CONTACT && now >= at && now - at <= patience;
    }

    private void giveUpOn(Foe target, long now) {
        if (target.kind() == Kind.LOBBER) {
            lobberGaveUpAt = now;
        } else {
            sniperGaveUpAt = now;
        }
    }

    // hp that dropped between two steps is a hit, and the biggest of the lately ones is the guess for the next. pure numbers off
    // the hp we are handed, so a poison tick or a fall counts too and ages out in two seconds like the rest
    private float watchHits(Tick t) {
        boolean watching = lastTick != Long.MIN_VALUE && t.now() - lastTick <= HIT_WATCH_GAP;
        if (watching && lastHp - t.health() > 0.01f) hitSizes.addLast(new HitSize(t.now(), lastHp - t.health()));
        lastHp = t.health();
        while (!hitSizes.isEmpty() && t.now() - hitSizes.peekFirst().at() > NEXT_HIT_WINDOW) hitSizes.removeFirst();
        float biggest = 0;
        for (HitSize hit : hitSizes) biggest = Math.max(biggest, hit.size());
        return Math.min(biggest, NEXT_HIT_CAP);
    }

    private record HitSize(long at, float size) {
    }

    public Event step(Tick t) {
        // the server's time packet drags the clock back a tick or two under lag. that is not a new world (CombatBrain catches
        // those), so the clock just stands still instead of wiping a live run and every "since" going negative
        clockSkew = lastTick != Long.MIN_VALUE && t.now() < lastTick ? lastTick - t.now() : 0;
        if (clockSkew > 0) t = t.withNow(lastTick);
        t = t.withNextHit(watchHits(t));
        if (mode != Mode.NONE) {
            runMaxHit = Math.max(runMaxHit, t.nextHit());
            // whoever joins the chase is part of it: the hit was theirs as much as the first lot's. a tick late on purpose, the ones
            // that just turned up are not yet the ones that hurt us (the decisions below ask who overlaps)
            chaseIds.addAll(joiners);
            joiners = nearMelee(t);
        } else {
            joiners = Set.of();
        }
        lastTick = t.now();
        endedFirst = Event.NONE;
        recover.update(t.now(), t.health(), mode != Mode.NONE, t.body());
        tickLatch(t);
        Mode before = mode;
        int oldTarget = targetId;
        Event event = switch (before) {
            case FIGHT -> fight(t);
            case RUN -> run(t);
            default -> idle(t);
        };
        if (before == Mode.NONE || mode != Mode.NONE) {
            if (mode == Mode.NONE) clearChase();
            return event;
        }
        // it just ended. whatever idle would start next tick starts now: a tick of NONE in between is a tick of the chain
        // letting go of the wheel and the user task walking with the mob still on us
        Event next = idle(afterEnd(t, oldTarget));
        if (next == Event.NONE) {
            if (mode == Mode.NONE) clearChase();
            return event;
        }
        endedFirst = event;
        return next;
    }

    // the latch lets go once nothing is within RUN_CLEAR for RUN_CLEAR_TICKS, whatever mode we are in
    private void tickLatch(Tick t) {
        if (!runLatch) return;
        double nearest = t.target() != null ? t.target().distance() : Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) nearest = Math.min(nearest, foe.distance());
        if (nearest <= RUN_CLEAR) {
            latchClearSince = -1;
        } else if (latchClearSince < 0) {
            latchClearSince = t.now();
        } else if (t.now() - latchClearSince >= RUN_CLEAR_TICKS) {
            runLatch = false;
            latchBreaks = 0;
            latchChased = false;
        }
    }

    // a run reason the latch lets through: the warden and the heavy hitters always, the rest only once the latch is off. low hp
    // gets through whenever a melee mob is on us, crowd gets one break per latch, unless we are boxed or something fast is on us
    // (those stay the fight, see runOrBox)
    private Why unlatched(Why reason, Tick t) {
        if (!runLatch || reason == Why.HEAVY) return reason;
        if ((reason == Why.LOW_HP || reason == Why.CROWD) && latchChased && latchBreaks == 0 && !t.boxed() && fastTarget(t) == null) {
            latchBreaks++;
            return reason;
        }
        // under the flee line with something melee on us the latch has no say: another fight at that hp is the one that kills
        // us, and the break counter is for the crowd. boxed or with something fast on us it still stays the cornered fight, see
        // runOrBox. a shooter out there or a mob we gave up on is not that, the latch exists for exactly those
        if (reason == Why.LOW_HP && (t.health() <= FLEE_HP || (t.lingering() && t.low())) && !t.boxed() && fastTarget(t) == null && meleeOnUs(t)) return reason;
        // healing up and a melee mob is walking over: it has not hit us yet, so it is not "on us", but the fight that set the latch
        // is long over and the next one at this hp is the one that kills us
        if (reason == Why.LOW_HP && recover.on() && !t.boxed() && fastTarget(t) == null && meleeNear(t, RecoverHold.RERUN_RANGE)) return reason;
        // a shooter with a line that hit us a moment ago is not the fight that set the latch: standing in its sights is how the
        // meal turns into a funeral. only in the idle heal hold (a fight keeps its latch, recovery stays on through one). boxed or with
        // something fast on us it stays the cornered fight, same as above
        if (mode == Mode.NONE && recover.on() && (reason == Why.LOW_HP || reason == Why.SHOT) && !t.boxed() && fastTarget(t) == null && shotJustNow(t)) return reason;
        return Why.NONE;
    }

    // a ranged foe with a line to us that landed a hit within SHOT_FRESH ticks, one we have not given up on
    private boolean shotJustNow(Tick t) {
        for (Foe foe : t.foes()) {
            if (foe.shooting() && foe.sinceHit() <= SHOT_FRESH && !gaveUpOn(foe, t.now())) return true;
        }
        return false;
    }

    // a ranged foe in FOE_RANGE with a line to us, hit yet or not: the path fight we pick is not one to start in its sights
    private boolean seenByShooter(Tick t) {
        for (Foe foe : t.foes()) {
            if (foe.ranged() && !foe.creeper() && foe.sight() && foe.distance() <= FOE_RANGE && !gaveUpOn(foe, t.now())) return true;
        }
        return false;
    }

    // a melee foe we have not given up on within range
    private boolean meleeNear(Tick t, double range) {
        for (Foe foe : t.foes()) {
            if (foe.melee() && foe.distance() <= range && !gaveUpOn(foe, t.now()) && !stillIgnored(foe, t.now())) return true;
        }
        return false;
    }

    // the heal hold has a mob close and nothing decided: one line per IDLE_NOTE_GAP ticks saying what each gate thought, taken
    // by the brain (this class has no logger)
    private static final long IDLE_NOTE_GAP = 40;
    private long idleNoteAt = Long.MIN_VALUE / 2;
    private String idleNote;

    public String takeNote() {
        String note = idleNote;
        idleNote = null;
        return note;
    }

    private void noteIdle(Tick t, boolean cooling) {
        if (t.now() - idleNoteAt < IDLE_NOTE_GAP) return;
        Foe nearest = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() <= 8 && (nearest == null || foe.distance() < nearest.distance())) nearest = foe;
        }
        if (nearest == null) return;
        idleNoteAt = t.now();
        Foe fast = fastTarget(t);
        idleNote = "heal hold, " + String.format(java.util.Locale.ROOT, "%.1f", nearest.distance()) + " from foe #" + nearest.id()
                + (nearest.melee() ? " (melee" : " (ranged") + (nearest.hitUs() ? ", has hit us" : "") + (nearest.fresh() ? ", fresh" : "")
                + "), hp " + String.format(java.util.Locale.ROOT, "%.1f", t.health()) + ", boxed " + t.boxed() + ", runLatch " + runLatch
                + ", latchBreaks " + latchBreaks + ", fastTarget " + (fast == null ? "none" : "#" + fast.id()) + ", ignored " + ignored.keySet()
                + ", meleeOnUs " + meleeOnUs(t) + ", reason " + runReason(t, cooling) + " / rerun " + rerun(t);
    }

    private static boolean meleeOnUs(Tick t) {
        for (Foe foe : t.foes()) {
            if (foe.melee() && engaged(foe, false)) return true;
        }
        return false;
    }

    // what idle would have seen next tick: no fight target (there is none with nothing committed), and not the target if it
    // just died. a dead one can still be in the foe list for a tick with its fresh hit, same idea as nextTarget skipping it.
    // one we gave up on but is alive stays, ignored does its job and danger is still danger
    private static Tick afterEnd(Tick t, int oldTarget) {
        boolean dead = oldTarget >= 0 && t.target() == null;
        List<Foe> foes = !dead ? t.foes() : t.foes().stream().filter(foe -> foe.id() != oldTarget).toList();
        return new Tick(t.now(), t.health(), t.x(), t.z(), foes, null, t.way(), t.armed(), t.trying(), t.creeperRepeat(),
                t.body(), t.boxed(), t.nextHit(), t.watched(), t.lingering());
    }

    // nothing is committed any more: the chase is over, its biggest hit goes with it
    private void clearChase() {
        runMaxHit = 0;
        chaseIds.clear();
        joiners = Set.of();
    }

    // a run or a fight starts: a new set of chasers is a new chase, the same ones keep the hit memory
    private void noteChase(Tick t) {
        Set<Integer> now = nearMelee(t);
        if (now.isEmpty()) return;
        if (!overlapsChase(now)) {
            runMaxHit = 0;
            chaseIds = now;
        } else {
            chaseIds.addAll(now);
        }
    }

    private static Set<Integer> nearMelee(Tick t) {
        Set<Integer> ids = new HashSet<>();
        for (Foe foe : t.foes()) {
            if (foe.melee() && foe.distance() <= LOW_HP_RANGE) ids.add(foe.id());
        }
        return ids;
    }

    private boolean overlapsChase(Set<Integer> now) {
        return chaseIds.isEmpty() || !Collections.disjoint(chaseIds, now);
    }

    // low for the chase decisions (turning round on a chase, the cap): the worst hit of the whole chase, not just the last two seconds.
    // only while the chasers are the ones that hit us, a fresh bunch has not hurt us yet
    private boolean lowChase(Tick t) {
        float memory = overlapsChase(nearMelee(t)) ? runMaxHit : 0;
        return t.health() - Math.max(t.nextHit(), memory) <= FLEE_HP;
    }

    // ---- PERCH

    // how tall a pillar to stack right here, 0 for not now. every chaser within 8 is a melee only perch species, at least one is heavy or
    // fast (or a run hit us already), no shooter has hit us lately, and the spot works (PerchRules.perchHeight)
    private int perchAt(Tick t) {
        PerchRules.Facts f = perch;
        if (f == null || t.now() - perchFailedAt < PerchRules.REPERCH_TICKS) return 0;
        if (why == Why.REPEAT_CREEPER && mode == Mode.RUN) return 0;
        if (t.now() - f.shotAt() <= PerchRules.SHOT_QUIET) return 0;
        // one re-perch near the last one, then the old rules
        if (t.now() - lastPerchAt < PerchRules.REPERCH_TICKS && Math.hypot(t.x() - lastPerchX, t.z() - lastPerchZ) < PerchRules.REPERCH_BLOCKS
                && rePerches >= PerchRules.REPERCH_MAX) return 0;
        // no room to build a pillar: the old rules
        if (!PerchRules.roomToStart(t.foes())) return 0;
        boolean pressing = runHits >= 1;
        for (Foe foe : t.foes()) {
            if (foe.ranged() && !foe.creeper() && foe.sinceHit() <= PerchRules.SHOT_QUIET) return 0;
            if (foe.distance() <= PerchRules.RANGE && (foe.kind() == Kind.HEAVY || foe.fast())) pressing = true;
        }
        return pressing ? PerchRules.perchHeight(f, t.foes()) : 0;
    }

    private Event startPerch(Tick t, int h, String trigger) {
        PerchRules.Facts f = perch;
        int chasers = 0;
        for (Foe foe : t.foes()) {
            if (foe.distance() <= PerchRules.RANGE) chasers++;
        }
        boolean again = t.now() - lastPerchAt < PerchRules.REPERCH_TICKS && Math.hypot(t.x() - lastPerchX, t.z() - lastPerchZ) < PerchRules.REPERCH_BLOCKS;
        rePerches = again ? rePerches + 1 : 0;
        lastPerchAt = t.now();
        lastPerchX = t.x();
        lastPerchZ = t.z();
        // it stands in for a run, so ending hurt the hold uses the run's hp line
        runLine = true;
        boxCorner = false;
        fastCorner = false;
        witherCorner = false;
        fastClearSince = -1;
        noRoom = false;
        contacted = false;
        lobberPick = false;
        atRange = false;
        mode = Mode.FIGHT;
        why = Why.PERCH;
        targetId = -1;
        cornered = false;
        startTick = t.now();
        farSince = -1;
        releaseSince = -1;
        wayBoxSince = -1;
        outReachSince = -1;
        perchH = h;
        perchStartY = f.feetY();
        perchColX = (int) Math.floor(t.x());
        perchColZ = (int) Math.floor(t.z());
        perchTopSince = -1;
        perchQuietSince = -1;
        perchCapAt = t.now() + PerchRules.CAP_TICKS;
        perchStartedAt = t.now();
        perchShotBase = f.shots();
        perchNote = "perch start: " + trigger + ", h " + h + ", chasers " + chasers + ", rim=" + t.way().rim();
        return Event.PERCH_START;
    }

    private Event leavePerch(Tick t, String reason, boolean failed) {
        if (failed) perchFailedAt = t.now();
        perchNote = (failed ? "perch fail: " : "perch leave: ") + reason;
        return end(t, Event.PERCH_END);
    }

    // the whole perch fight. it skips the target bookkeeping and every bail the fight has (each would end a perch in seconds): up here
    // the foes cannot reach us, and what ends it is the short list below
    private Event perch(Tick t) {
        PerchRules.Facts f = perch;
        if (f == null) return leavePerch(t, "no facts", true);
        if (f.pillarFailed()) return leavePerch(t, "pillar went nowhere", true);
        for (Foe foe : t.foes()) {
            if (foe.kind() == Kind.UNTOUCHABLE && foe.danger(t.guardHealth())) return leavePerch(t, "something we only run from", true);
        }
        if (f.shots() - perchShotBase >= PerchRules.SHOTS_LEAVE) return leavePerch(t, "a shooter hit us twice", true);
        // the creeper step is off up here and a blast at the foot of the pillar is not something we sit through
        if (f.creeper4()) return leavePerch(t, "a creeper at the foot", true);
        for (Foe foe : t.foes()) {
            if (foe.creeper() && foe.distance() <= PerchRules.LEAVE_RANGE) return leavePerch(t, "a creeper at the foot", true);
        }
        boolean top = f.grounded() && f.feetY() >= perchStartY + perchH - 0.05;
        if (top && perchTopSince < 0) {
            perchTopSince = t.now();
            perchColX = (int) Math.floor(t.x());
            perchColZ = (int) Math.floor(t.z());
        }
        if (perchTopSince >= 0) {
            // knocked off the column, or the column is shorter than it was
            if ((int) Math.floor(t.x()) != perchColX || (int) Math.floor(t.z()) != perchColZ || f.feetY() < perchStartY + perchH - 1.5) {
                return leavePerch(t, "knocked off the pillar", true);
            }
            // a melee hit that landed after we got to the top: the height was not enough
            for (Foe foe : t.foes()) {
                long at = t.now() - foe.sinceHit() - clockSkew;
                if (foe.melee() && at > perchTopSince + 2) return leavePerch(t, "hit on top by foe #" + foe.id(), true);
            }
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) {
            if (foe.melee()) nearest = Math.min(nearest, foe.distance());
        }
        // (counted off the world when the brain did: the foe list loses a mob that cannot reach us, and one at the foot of the pillar cannot)
        boolean near6 = f.near6() >= 0 ? f.near6() > 0 : nearest <= PerchRules.LEAVE_RANGE;
        boolean near4 = f.near4() >= 0 ? f.near4() > 0 : nearest <= PerchRules.CAP_RANGE;
        // nobody within 6 for a while: the anger ran out, the pillar did its job
        if (!near6) {
            if (perchQuietSince < 0) perchQuietSince = t.now();
            if (t.now() - perchQuietSince >= PerchRules.QUIET_TICKS) {
                return leavePerch(t, "nobody within " + (int) PerchRules.LEAVE_RANGE + " blocks", false);
            }
        } else {
            perchQuietSince = -1;
        }
        // the renew below has no end of its own: a piglin at the foot that the hit cannot reach would keep us up here forever
        if (t.now() - perchStartedAt >= PerchRules.CAP_TICKS * 2) return leavePerch(t, "total cap", true);
        if (t.now() >= perchCapAt) {
            // something still at the foot is a reason to stay, not to climb down into it
            if (near4) {
                perchCapAt = t.now() + PerchRules.CAP_TICKS;
            } else {
                return leavePerch(t, "cap", false);
            }
        }
        return Event.NONE;
    }

    // ---- NONE

    private Event idle(Tick t) {
        forgetIgnored(t);
        boolean cooling = coolingDown(t.now());
        // danger is "anything that bad within 8", ignored or not, cooldown or not: leaving is not a fight with that one mob.
        // low hp in the cooldown wants it on top of us or a fresh hit, see trigger
        Why run = unlatched(runReason(t, cooling), t);
        if (run != Why.NONE) return runOrBox(t, run);
        // cooldown or not: it is not something hitting us again, it is the creeper step going round in circles. a committed
        // fight asks the same thing at its top, the fight walking us back to the creeper is how the circle keeps going
        if (t.creeperRepeat()) {
            // the last run from it got nowhere (no safe heading, water or a drop all round): it is fought, the shield up as it
            // swells (a raised shield takes a creeper's blast whole). it ran 0 blocks in a loop, held at three blocks with the
            // shield up, for minutes (the runner, 3 Nov 2026, attempt 109)
            Foe creeper = nearestCreeper(t);
            if (creeper != null && t.armed() && creeperRunNowhereAt >= 0 && t.now() - creeperRunNowhereAt < CREEPER_FIGHT_AFTER) {
                return startFight(t, creeper, Why.HIT, Event.FIGHT_START, false);
            }
            return startRun(t, Why.REPEAT_CREEPER, Event.RUN_START);
        }
        // healing up: whatever comes back near is a run again, before it is a fight and whatever the cooldown says. we are
        // standing here because we are hurt, and trading blows at 10 hp is how the last run started
        if (recover.on() && endermanAbout(t) == null) {
            Why again = unlatched(rerun(t), t);
            if (again != Why.NONE) return runOrBox(t, again);
        }
        // the fight is only for one we have not given up on, or one that has hit us since. with nothing in hand better than a
        // fist it is a run instead: a punch is not a fight, and the cornered rules still turn us round when a run goes nowhere
        // one that hit us from out there is the target too, not only one in contact: the fight waits for it where it teleports
        Foe ender = endermanOnUs(t);
        if (ender != null) return startFight(t, ender, Why.HIT, Event.FIGHT_START, false);
        // one about to swing counts too: the shield needs 5 ticks to come up and its first hit would land before that
        Foe close = endermanClose(t);
        if (close != null) return startFight(t, close, Why.HIT, Event.FIGHT_START, false);
        if (recover.on()) noteIdle(t, cooling);
        // healing up under the flee line with the run rules saying run (the latch is what kept it from starting), an old hit is
        // not a reason to start swinging: only one that hit us just now is. boxed, something fast, or an enderman or shooter
        // making rerun say anything but LOW_HP means no run to take instead, so the old hit still fights
        boolean freshHitsOnly = cooling || (recover.on() && t.health() <= FLEE_HP && !t.boxed() && fastTarget(t) == null
                && rerun(t) == Why.LOW_HP && endermanAbout(t) == null);
        Foe hit = hitInContact(candidates(t), freshHitsOnly);
        if (hit != null) {
            // (no run for these while an enderman is about: it follows, so a swing at what is on us is the better trade)
            boolean enderAbout = endermanAbout(t) != null;
            if (gaveUpOn(hit, t.now()) && !enderAbout) return startRun(t, Why.LOBBER, Event.RUN_START);
            if (!t.armed() && !hit.standsAndFights() && !enderAbout) return startRun(t, Why.UNARMED, Event.RUN_START);
            return startFight(t, hit, Why.HIT, Event.FIGHT_START, false);
        }
        // a cube or an angry piglin close on a rim and no hit yet: its first hit is the knock into the lava, so we hit first
        Foe rim = rimEngage(t, cooling);
        if (rim != null) return startFight(t, rim, Why.RIM, Event.FIGHT_START, false);
        // not in contact, but a skeleton is shooting us. one target and kill it. hurt, or more than one of them, is a run out of
        // their sight instead (the low hp run above already took the hurt case when it was close enough to count)
        List<Foe> shooters = snipers(t, cooling);
        if (!shooters.isEmpty()) {
            Foe sniper = shooters.get(0);
            // a pickaxe beats a fist but it is still ten swings on the far end of a walk full of arrows. armed is for what is
            // already on us, going to get one wants the real thing
            boolean realWeapon = t.armed() && t.way().weapon();
            // (with an enderman about the run is off, a skeleton on us is the armed fight below or nothing)
            boolean hurtRun = t.low() && !(realWeapon && shooterFight(candidates(t), cooling, t.guardHealth()));
            if ((hurtRun || secondShooter(shooters) || gaveUpOn(sniper, t.now())) && endermanAbout(t) == null) {
                return startRun(t, Why.SHOT, Event.RUN_START);
            }
            // (bare hands and pickaxes do not go and get a skeleton, they keep walking till it hurts and then it is the run above)
            if (realWeapon) return startFight(t, sniper, Why.PINNED, Event.FIGHT_START, false);
        }
        // not a swing yet, but it is the thing in the way. cooldown or not: the cooldown is about not picking a fight again
        // with what is around us, and this one is a path that cannot go on until it is gone
        Foe way = inTheWay(t);
        // a heavy hitter in the path is not a fight we go and pick, at any hp: one swing is 8 to 13 and they come in threes.
        // it is a run, and the sweep keeps a zone round where we ran from (RunZones) so the path does not walk back into it.
        // one that hits us is still the HIT fight above, that is unchanged
        if (way == null && endermanAbout(t) == null) {
            Foe heavy = heavyInTheWay(t);
            if (heavy != null) {
                int h = perchAt(t);
                if (h > 0) return startPerch(t, h, "heavy in the way");
                ranFrom.put(heavy.id(), t.now());
                return startRun(t, Why.HEAVY, Event.RUN_START);
            }
            Foe brute = bruteComing(t);
            if (brute != null) {
                ranFrom.put(brute.id(), t.now());
                return startRun(t, Why.HEAVY, Event.RUN_START);
            }
        }
        // a wither skeleton close with a line on us: one target, hit it first. outside the cooldown and past the shooter-sight gate
        // below (a fortress always has a blaze looking at us), because its walk to us is the thing that costs the hp
        Foe wither = witherComing(t);
        if (wither != null) {
            // (hurt, or a pack of them on our floor: not a fight we pick. a skeleton we turn down still walks up and hits us, so it is
            // a run from it, once, instead of nothing)
            // (the buddy's fork: hurt alone is still its fight, down to the line a fight against it keeps; the run at 15.8 hp
            // headed back into the corridor it came from and took the hit from the front, attempt 220)
            if (otherWither(t, wither)) {
                ranFrom.put(wither.id(), t.now());
                return startRun(t, Why.HEAVY, Event.RUN_START);
            }
            return startFight(t, wither, Why.IN_WAY, Event.FIGHT_START, false);
        }
        // (not with bare hands: it would punch the thing down to the flee line. it hits us, and that is the unarmed run above)
        // (and not with a pickaxe either: the way fight is one we pick, it wants a sword or an axe)
        // (and not hurt: a path fight is one we pick, and at this hp we do not pick them)
        // (and not with a shooter that has a line on us: the walk to the thing in the way is a walk in its sights)
        if (way == null || !t.armed() || !t.way().weapon() || t.low() || seenByShooter(t)) return Event.NONE;
        if (t.lingering() && t.health() < LINGER_PICK_HP) return Event.NONE;
        return startFight(t, way, Why.IN_WAY, Event.FIGHT_START, false);
    }

    // the run's rim shield is up this tick and stands us still on purpose: the on the spot timers start over, so the shield never
    // counts toward a cornered fight or a stuck run. once it lets go the run gets its whole window to walk again
    public void pauseCorner() {
        cornerSince = -1;
        stuckSince = -1;
    }

    // the closest knocker within RIM_ENGAGE_RANGE while we stand on a rim. armed, above the flee line, not crowded and not in a
    // run latch (those are the run rules' call, and idle already asked them), and never one we gave up on
    // not a cube with lava this close: the fight parks us next to the lava to wait for it and the hop lands anyway. we walk on,
    // and if it does catch us that is the plain HIT fight
    private Foe rimEngage(Tick t, boolean cooling) {
        if (!t.way().rim() || !t.armed() || runLatch || t.health() <= FLEE_HP || crowded(t.health(), t.foes(), cooling)) return null;
        Foe best = null;
        for (Foe foe : candidates(t)) {
            if (!CombatRules.rimKnocker(foe) || foe.distance() > CombatRules.RIM_ENGAGE_RANGE) continue;
            if (t.way().lavaClose() && foe.splitType() != null) continue;
            if (foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // a run for a crowd or low hp, unless every side of us is a wall or a mob: a run from there is a few seconds of digging
    // with the thing still hitting us, so it is the cornered fight straight away. the rest (heavy hitters, the warden, a creeper
    // that keeps coming back, a lobber) keep their run, the fight would only bail again
    private Event runOrBox(Tick t, Why reason) {
        int h = perchAt(t);
        if (h > 0) return startPerch(t, h, "run " + reason);
        if (reason == Why.CROWD || reason == Why.LOW_HP) {
            Foe on = t.boxed() ? boxedTarget(t) : null;
            if (on == null) on = fastTarget(t);
            if (on != null) {
                Event event = startFight(t, on, Why.CORNERED, Event.RUN_TO_FIGHT, true);
                runLine = true;
                boxCorner = t.boxed();
                fastCorner = !t.boxed();
                witherCorner = fastCorner && !on.fast();
                return event;
            }
        }
        return startRun(t, reason, Event.RUN_START);
    }

    // a fast melee mob that has hit us, close, with a weapon in hand to answer it: a run is a jog with it biting our back, so
    // that is the fight. cubes, slimes and spiders move at 0.3 or better (see CombatBrain.fast). in the heal-up hold it is
    // seen from further off: knockback puts the thing past the close range and a re-run from it is the cornered death anyway
    private Foe fastTarget(Tick t) {
        if (!t.armed()) return null;
        double range = recover.on() ? RecoverHold.RERUN_RANGE : FAST_FIGHT_RANGE;
        Foe best = null;
        boolean otherMelee = false;
        for (Foe foe : t.foes()) {
            if (foe.melee() && foe.splitType() == null && foe.kind() != Kind.UNTOUCHABLE && foe.distance() <= LOW_HP_RANGE) otherMelee = true;
        }
        for (Foe foe : t.foes()) {
            boolean close = foe.hitUs() && foe.distance() <= range;
            boolean cube = foe.splitType() != null && !t.way().lavaClose() && !otherMelee && foe.distance() <= CUBE_FIGHT_RANGE;
            if (!foe.fast() || !foe.melee() || !(close || cube) || foe.kind() == Kind.UNTOUCHABLE) continue;
            if (foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best != null ? best : loneWither(t);
    }

    // a wither skeleton that hit us from close, with no other melee mob within 8: not fast, but the same cornered fight. 4.5 a
    // swing in the back plus the wither ticks finish a run long before it gets 24 blocks, so turning round is the better bet,
    // flee line or not. two of them, or anything else melee with it, is the normal run (and a crowd mid fight still bails)
    private Foe loneWither(Tick t) {
        Foe wither = null;
        for (Foe foe : t.foes()) {
            if (!foe.melee() || foe.distance() > LOW_HP_RANGE) continue;
            if (wither != null || !foe.wither()) return null;
            wither = foe;
        }
        if (wither == null || !wither.hitUs() || wither.distance() > FAST_FIGHT_RANGE) return null;
        // (three on us counts the shooters too: a skeleton and two blazes landing hits is the crowd run, not this)
        if (crowded(t.health(), t.foes(), false, true)) return null;
        if (gaveUpOn(wither, t.now()) || stillIgnored(wither, t.now())) return null;
        return wither;
    }

    // who we swing at when there is nowhere to go: whatever is in contact, a melee foe first. never the warden, never one we only
    // run from, never a creeper (that is the creeper step's)
    private Foe boxedTarget(Tick t) {
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > CONTACT || foe.creeper() || foe.kind() == Kind.UNTOUCHABLE || foe.mustRun(t.guardHealth())) continue;
            if (gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || (foe.melee() && !best.melee()) || (foe.melee() == best.melee() && foe.distance() < best.distance())) best = foe;
        }
        return best;
    }

    // the low hp rule's foes, minus the ones we gave up on: anything within RERUN_RANGE, or a shooter with a line that just hit us
    private Why rerun(Tick t) {
        boolean near = false;
        boolean shot = false;
        // (the shooter fight is not run from while healing either, the skeleton is the next kill. a watched blaze still counts)
        // (only if we'd actually go and get it: with a pickaxe nobody's coming, and that was us healing in its sights)
        if (t.armed() && t.way().weapon() && shooterFight(candidates(t), false, t.guardHealth())) return t.watched() ? Why.SHOT : Why.NONE;
        for (Foe foe : candidates(t)) {
            if (foe.standsAndFights() || gaveUpOn(foe, t.now())) continue;
            if (foe.distance() <= RecoverHold.RERUN_RANGE) near = true;
            if (foe.shooting() && foe.fresh()) shot = true;
        }
        if (near) return Why.LOW_HP;
        // a blaze with a line that has not hit us yet counts too: the meal is a long stand in its sights
        return shot || t.watched() ? Why.SHOT : Why.NONE;
    }

    // skeletons with a line to us that hit us lately, nearest first. in the cooldown only a fresh hit counts
    private List<Foe> snipers(Tick t, boolean freshOnly) {
        return candidates(t).stream()
                .filter(foe -> foe.sniper() && foe.shooting() && (!freshOnly || foe.fresh()))
                .sorted(java.util.Comparator.comparingDouble(Foe::distance))
                .toList();
    }

    // two of them shooting ends the one target deal, unless they are a patrol: those we kill in turn (see withPatrol)
    private static boolean secondShooter(List<Foe> shooters) {
        return shooters.stream().filter(foe -> !foe.patrol()).count() >= 2;
    }

    // the closest one in contact that is in the path, or any in contact when the path has gone nowhere for 1.5 s. never a
    // creeper (a lit one is CreeperStep's, an unlit one in the way is not worth the swing and the bang), never one we only
    // run from, never one we gave up on
    private Foe inTheWay(Tick t) {
        Foe close = null;
        Foe blocking = null;
        for (Foe foe : candidates(t)) {
            // (no shooters: they back off on their own, so in the way at 3 and gone at 5 is a fight, a clear, and the same
            // fight again a few steps later, and they never block a cell for long anyway)
            if (foe.distance() > CONTACT || !foe.melee() || foe.mustRun(t.guardHealth())) continue;
            // (heavy hitters are heavyInTheWay's, a run. not a swing at any hp, and not the boxed in pick either)
            if (foe.kind() == Kind.HEAVY) continue;
            if (close == null || foe.distance() < close.distance()) close = foe;
            if (t.way().inWay().contains(foe.id()) && (blocking == null || foe.distance() < blocking.distance())) blocking = foe;
        }
        if (blocking != null) {
            wayBoxSince = -1;
            return blocking;
        }
        // boxed in: the path wants to go somewhere and has not got 1 block anywhere (up counts) with one of them on us
        if (t.way().pathing()) {
            wayGapSince = -1;
        } else if (wayGapSince < 0) {
            wayGapSince = t.now();
        }
        boolean gaveUp = wayGapSince >= 0 && t.now() - wayGapSince >= WAY_GAP_TICKS;
        if (close == null || gaveUp) {
            wayBoxSince = -1;
            return null;
        }
        if (!t.way().pathing()) return null;
        double y = t.way().y();
        if (wayBoxSince < 0 || Math.sqrt(sq(t.x() - wayBoxX) + sq(y - wayBoxY) + sq(t.z() - wayBoxZ)) >= WAY_PROGRESS) {
            wayBoxSince = t.now();
            wayBoxX = t.x();
            wayBoxY = y;
            wayBoxZ = t.z();
            return null;
        }
        if (t.now() - wayBoxSince < WAY_BOXED_TICKS) return null;
        wayBoxSince = -1;
        return close;
    }

    // a heavy hitter in contact that sits in the next path nodes. same filter as inTheWay, minus the boxed in half: boxed in
    // with one on us is the cornered rules' business
    private Foe heavyInTheWay(Tick t) {
        for (Foe foe : candidates(t)) {
            if (foe.kind() != Kind.HEAVY || foe.distance() > CONTACT || !foe.melee() || !t.way().inWay().contains(foe.id())) continue;
            // (a run we already made for this one, lately: not again. RunZones is what keeps the sweep's path off it)
            Long ran = ranFrom.get(foe.id());
            if (ran != null && t.now() >= ran && t.now() - ran < RunZones.EXPIRY_TICKS) continue;
            return foe;
        }
        return null;
    }

    // a brute that has a line on us inside BRUTE_RUN_RANGE, at any hp: the first hit is 8 or more, so waiting for contact is
    // the whole mistake. hoglins are left out on purpose, they wander crimson forests and a 12 block rule there is a jog.
    // not the same brute twice inside the zone expiry, RunZones keeps the sweep off it
    static boolean bruteRun(Foe foe, Map<Integer, Long> ranFrom, long now) {
        if (!foe.brute() || !foe.sight() || foe.distance() > BRUTE_RUN_RANGE) return false;
        Long ran = ranFrom.get(foe.id());
        return ran == null || now < ran || now - ran >= RunZones.EXPIRY_TICKS;
    }

    private Foe bruteComing(Tick t) {
        for (Foe foe : candidates(t)) {
            if (bruteRun(foe, ranFrom, t.now())) return foe;
        }
        return null;
    }

    // an aware wither skeleton with a line on us inside WITHER_START_RANGE (4), when we have a sword or an axe in hand and are not low.
    // the Nether only (the foe is only marked there): everywhere else mobs are still ignored by default
    private Foe witherComing(Tick t) {
        if (!t.armed() || !t.way().weapon() || t.low()) return null;
        Foe best = null;
        for (Foe foe : candidates(t)) {
            if (!foe.wither() || !foe.sight() || foe.distance() > WITHER_START_RANGE || gaveUpOn(foe, t.now())) continue;
            // (one we already ran from is not run from twice inside the zone expiry, same as bruteRun)
            Long ran = ranFrom.get(foe.id());
            if (ran != null && t.now() >= ran && t.now() - ran < RunZones.EXPIRY_TICKS) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    // what makes a fight in progress (or one about to start) a run instead, NONE for fit to fight. the same danger rules
    // as trigger(), plus the fight target itself (it can be outside the foe list and still be the thing to leave)
    private Why runReason(Tick t, boolean freshOnly) {
        return runReason(t, freshOnly, t.nextHit());
    }

    // another wither skeleton within WITHER_PACK_RANGE of us, on our floor (the ones through the fortress floors do not count). a wither
    // skeleton's y is its height over us, see CombatBrain.withSplit
    private static boolean otherWither(Tick t, Foe self) {
        return witherNear(t, self.id());
    }

    // a wither skeleton on our floor, or the one we are fighting: the ticks are eating hp and standing still for an enderman is the wait
    // it needs to walk up and hit twice
    private static boolean witherOnFloor(Tick t) {
        return witherNear(t, -1) || (t.target() != null && t.target().wither());
    }

    // any wither skeleton on our floor in WITHER_PACK_RANGE, bar the one with this id (-1: none barred)
    static boolean witherNear(Tick t, int notId) {
        for (Foe foe : t.foes()) {
            if (foe.id() != notId && foe.wither() && foe.distance() <= WITHER_PACK_RANGE && Math.abs(foe.y()) <= 4) return true;
        }
        return false;
    }

    // a committed fight against one melee mob is not left for the hp it would have after the next hit: a 4 damage hitter
    // would send us off at 12 hp, and the run is the thing that gets us hit in the back. the real hp is the line there,
    // a second melee mob near, a shooter with a line on us, and everything not yet fighting, keep the projected one
    private static final float LINGER_HIT = 2;

    private static float lowHit(Tick t) {
        Foe target = t.target();
        // a lone wither skeleton is a fight we stay in: its hit is 4.5 and the bail at 12.5 hp is what turned our back on it
        // (while the wither effect is on, the ticks keep going after we stop swinging, so a small projected hit moves the bail up to ~10 hp)
        if (target != null && target.wither() && !otherWither(t, target)) return t.lingering() ? LINGER_HIT : 0;
        // (one hit is the whole cost only when nothing keeps ticking after it: wither and fire do)
        if (t.lingering() || target == null || !target.melee() || target.standsAndFights()) return t.nextHit();
        for (Foe foe : t.foes()) {
            if (foe.id() == target.id()) continue;
            if (foe.shooting() || (foe.melee() && foe.distance() <= LOW_HP_RANGE)) return t.nextHit();
        }
        return 0;
    }

    private Why runReason(Tick t, boolean freshOnly, float lowHit) {
        return runReason(t, freshOnly, lowHit, t.target() != null);
    }

    // onFight: a fight is on even when the target was taken out of the picture (see shooterFight)
    private Why runReason(Tick t, boolean freshOnly, float lowHit, boolean onFight) {
        Foe target = t.target();
        // (the same "has it got to us" rule as for anything else, the fight target just gets asked too)
        if (target != null && target.danger(t.guardHealth())) return Why.HEAVY;
        // an angry enderman is never a reason to leave (low hp, crowd or otherwise), so the rules below do not get to see it
        List<Foe> foes = leavable(t.foes());
        Trigger trigger = trigger(t.health(), t.nextHit(), lowHit, foes, freshOnly, onFight);
        // and with one about, nothing else is either: it teleports onto us, so a run is just free hits. only the warden and the heavy
        // hitters (the danger rules) still get us out of there
        if (endermanAbout(t) != null) return trigger != null && trigger.why() == Why.HEAVY ? Why.HEAVY : Why.NONE;
        if (trigger != null && trigger.why() != Why.HIT) return trigger.why();
        if (target != null && !target.standsAndFights() && t.health() - lowHit <= FLEE_HP && target.distance() <= LOW_HP_RANGE
                && !shooterFight(foes, freshOnly, t.guardHealth(), onFight)) return Why.LOW_HP;
        // a crowd on us is not a fight to take, at any hp. only the ones actually on us count (engaged), never bystanders
        if (crowded(t.health(), foes, freshOnly, onFight)) return Why.CROWD;
        return Why.NONE;
    }

    // the nearest angry enderman within FOE_RANGE, the fight target counted too (it can be outside the foe list). hitUsOnly: only
    // one that has hit us lately, which is what starts or takes over a fight (a merely angry one is no reason to start swinging, it
    // comes to us, unless it is already within FIGHT_SHIELD_RANGE, see endermanClose). skipIgnored: not one we wrote off after it stalled or wandered off, unless it hit us since
    private Foe nearestEnderman(Tick t, boolean hitUsOnly, boolean skipIgnored) {
        Foe best = null;
        Foe target = t.target();
        if (target != null && target.standsAndFights() && target.distance() <= FOE_RANGE && (!hitUsOnly || target.hitUs())) best = target;
        for (Foe foe : t.foes()) {
            if (!foe.standsAndFights() || foe.distance() > FOE_RANGE || (hitUsOnly && !foe.hitUs())) continue;
            if (skipIgnored && stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // what holds the runs back: an angry one nearby counts even after we wrote it off, it is still about
    private Foe endermanAbout(Tick t) {
        return witherOnFloor(t) ? null : nearestEnderman(t, false, false);
    }

    // what a run turns round for: one we have not written off
    private Foe endermanToFight(Tick t) {
        return witherOnFloor(t) ? null : nearestEnderman(t, false, true);
    }

    private Foe endermanOnUs(Tick t) {
        return nearestEnderman(t, true, true);
    }

    // an angry one that has not hit us yet but is close enough to swing next: fight it now so the shield is up for that swing
    private Foe endermanClose(Tick t) {
        Foe best = witherOnFloor(t) ? null : nearestEnderman(t, false, true);
        return best != null && best.distance() <= CombatRules.FIGHT_SHIELD_RANGE ? best : null;
    }

    // the foes a run can be about: everyone but the ones we stand and fight
    static List<Foe> leavable(List<Foe> foes) {
        for (Foe foe : foes) {
            if (foe.standsAndFights()) return foes.stream().filter(f -> !f.standsAndFights()).toList();
        }
        return foes;
    }

    // the same tick with one foe gone from the list and no fight target, for asking what the rest of them would say
    private static Tick withoutFoe(Tick t, int id) {
        List<Foe> foes = t.foes().stream().filter(foe -> foe.id() != id).toList();
        return new Tick(t.now(), t.health(), t.x(), t.z(), foes, null, t.way(), t.armed(), t.trying(), t.creeperRepeat(),
                t.body(), t.boxed(), t.nextHit(), t.watched(), t.lingering());
    }

    private static boolean anyDanger(Tick t) {
        if (t.target() != null && t.target().danger(t.guardHealth())) return true;
        for (Foe foe : t.foes()) {
            if (foe.danger(t.guardHealth())) return true;
        }
        return false;
    }

    // the foes minus the ones we gave up on (unless they have hit us since)
    private List<Foe> candidates(Tick t) {
        if (ignored.isEmpty()) return t.foes();
        return t.foes().stream().filter(foe -> !stillIgnored(foe, t.now())).toList();
    }

    private boolean stillIgnored(Foe foe, long now) {
        Long at = ignored.get(foe.id());
        if (at == null) return false;
        // the last hit is at or before the moment we gave up on it: no news
        return foe.sinceHit() >= now - at;
    }

    private void forgetIgnored(Tick t) {
        if (ignored.isEmpty()) return;
        ignored.entrySet().removeIf(e -> t.now() - e.getValue() > 6000 || t.now() < e.getValue());
    }

    // ---- FIGHT

    private Event fight(Tick t) {
        if (why == Why.PERCH) return perch(t);
        Foe target = t.target();
        // the box opened (the zombie in the only gap died, we got knocked out): no longer the cornered fight, so the bail rules
        // below are asked again
        if (boxCorner && !t.boxed()) {
            boxCorner = false;
            cornered = false;
            if (why == Why.CORNERED) why = Why.HIT;
        }
        // same idea for the fast corner: the mob that made it a corner stopped being on us, so the bail rules get asked again.
        // the latch stays, it already knows about the hp line
        if (fastCorner && !t.boxed() && fastTarget(t) == null) {
            if (fastClearSince < 0) fastClearSince = t.now();
            if (t.now() - fastClearSince >= FAST_CORNER_RELEASE_TICKS) {
                fastCorner = false;
                witherCorner = false;
                cornered = false;
                fastClearSince = -1;
                if (why == Why.CORNERED) why = Why.HIT;
            }
        } else {
            fastClearSince = -1;
        }
        // the step keeps backing us off the same creeper and the fight keeps walking us back to it: the second go is the run's.
        // boxed in a run goes nowhere, so there the fight stays
        // (not when the run from it got nowhere: then this fight is the one with the creeper, see idle)
        boolean creeperFight = creeperRunNowhereAt >= 0 && t.now() - creeperRunNowhereAt < CREEPER_FIGHT_AFTER;
        if (t.creeperRepeat() && !t.boxed() && !creeperFight) return startRun(t, Why.REPEAT_CREEPER, Event.FIGHT_TO_RUN);
        // the bail-outs, and the only ones: the danger rules. a wobble above 8 is a fight, and so is hp 8 with nothing
        // within 8 (the last thing we were hitting died, that is a won fight, not a reason to run 24 blocks). boxed in
        // there is nowhere to bail to, so a cornered fight never asks
        // a fight we turned around for is out in the open though: the warden or a heavy walking up is still a run
        Why bail = !cornered ? runReason(t, false, lowHit(t)) : (why == Why.CHASED || fastCorner) && anyDanger(t) ? Why.HEAVY : Why.NONE;
        // the fight we turned on a chase is the one cornered fight that may still bail, once (unlatched counts it): a real corner
        // (box, fast mob, no room) is never CHASED, and the enderman's own rules stay out of it. the kill itself is not a bail,
        // targetGone chains to the next one or ends it
        if (cornered && bail == Why.NONE && target != null && why == Why.CHASED && !boxCorner && !fastCorner && endermanAbout(t) == null) {
            Why again = runReason(t, false, lowHit(t));
            if (again == Why.LOW_HP || again == Why.CROWD) bail = again;
        }
        // the witch we picked over the zombies is not walked away from at 11 hp for being a witch (that is how it lobs for a
        // minute while we hit zombies): the hp line is the hard one, at or under it the run is on again
        // (only the part the witch itself causes: a second skeleton or a crowd still ends it, one skeleton is the shooter fight)
        // a second skeleton on us is not the one-target fight any more
        if (bail == Why.NONE && why == Why.PINNED && secondShooter(snipers(t, false)) && endermanAbout(t) == null) bail = Why.SHOT;
        if (lobberPick && endermanAbout(t) == null) {
            if ((bail == Why.LOW_HP || bail == Why.SHOT) && t.health() > FLEE_HP) bail = runReason(withoutFoe(t, targetId), false, t.nextHit(), true);
            if (bail == Why.NONE && t.health() <= FLEE_HP) bail = Why.LOW_HP;
        }
        // the latch is about melee on us, so a run under the hp line with only the blaze left would never get past it, and the
        // hp line is the one thing the pick promised to keep
        boolean blazeHpLine = lobberPick && target != null && target.kind() == Kind.FLYER && t.health() <= FLEE_HP;
        if (!blazeHpLine) bail = unlatched(bail, t);
        // the lone wither skeleton corner is one skeleton's: a crowd turning up is a crowd again, latch or no latch (loneWither
        // says no to a crowd too, so the stay below lets it go)
        if (bail == Why.NONE && witherCorner && crowded(t.health(), t.foes(), false, true)) bail = Why.CROWD;
        if (bail != Why.NONE) {
            // (before the stay below: a fast mob that hit us makes the fight stay, and up on a pillar there is nothing to stay for)
            int perchHeight = perchAt(t);
            if (perchHeight > 0) return startPerch(t, perchHeight, "fight bail " + bail);
            // boxed in, a crowd or low hp is no reason to leave: there is no leaving. this fight is the cornered one from here.
            // the same goes for something fast that has hit us and is close: it comes along on the run
            Foe fast = fastTarget(t);
            boolean stay = (bail == Why.CROWD || bail == Why.LOW_HP) && (t.boxed() || fast != null);
            if (!stay) return startRun(t, bail, Event.FIGHT_TO_RUN);
            cornered = true;
            runLine = true;
            boxCorner = t.boxed();
            fastCorner = !t.boxed();
            witherCorner = fastCorner && !fast.fast();
            runLatch = true;
            latchChased = false;
            latchClearSince = -1;
            if (why != Why.CHASED) why = Why.CORNERED;
        }
        if (target != null) noteSplit(target);
        if (target == null) return targetGone(t);
        // an enderman that hit us is the target over whatever we are on, it is the one that cannot be walked away from
        // (the lone wither corner does not carry over to a new target, same as targetGone)
        boolean chain = cornered && !witherCorner;
        Why swapWhy = why == Why.PINNED || cornered != chain ? Why.HIT : why;
        Foe ender = endermanOnUs(t);
        if (ender != null && ender.id() != target.id() && (!target.standsAndFights() || !target.hitUs()) && !witherOnFloor(t)) {
            return startFight(t, ender, swapWhy, Event.FIGHT_NEXT, chain);
        }
        Foe blaze = blazeToSwap(t, target);
        if (blaze != null) {
            // (a real target change, not a dead one, so it gets its own line next to the "next" one)
            idleNote = "blaze #" + blaze.id() + " on our floor with a line on us, it goes first";
            Event event = startFight(t, blaze, Why.HIT, Event.FIGHT_NEXT, false);
            // picked over what is on us, same deal as the witch: no swapping straight back, and only the hard hp line ends it
            lobberPick = true;
            return event;
        }
        Foe swap = takeover(t, target);
        if (swap != null) return startFight(t, swap, swapWhy, Event.FIGHT_NEXT, chain);
        // a cube we can't swing at with lava this close: all the task does from here is stand and let it come. written off like a
        // stall, so the route runs until it hits us again. goes by the task's reach and not the aura's: in the gap between them the
        // task still won't swing and StandCap doesn't count it. boxed in there is no route to go back to. a stuck run's fight stays too:
        // the walk already failed, letting go is the same stuck run and this same pick ten ticks later
        boolean stuckRunFight = cornered && !boxCorner && !fastCorner;
        if (target.splitType() != null && t.way().lavaClose() && !target.inTaskReach() && !t.boxed() && !stuckRunFight) {
            ignored.put(target.id(), t.now());
            idleNote = String.format(java.util.Locale.ROOT, "let go of #%d, out of reach by lava (gap %.1f, hp %.1f)", target.id(), target.reachGap(), t.health());
            Event cleared = end(t, Event.FIGHT_CLEARED);
            // (end just offered us a heal hold, which is the same stand beside the same lava under a nicer name, and rerun won't
            // hear about this cube now. RecoverHold gets its chance after a run or a fight somewhere better)
            recover.reset();
            return cleared;
        }
        // it stepped out of the way and is not on us: the path goes on. (one that hit us since is a normal fight now and
        // goes by the normal rules below)
        // (the rim fight lets go one block past where it starts, CombatRules.RIM_RELEASE, so it does not flip back on)
        double release = why == Why.RIM ? CombatRules.RIM_RELEASE : WAY_RELEASE;
        boolean past = (why == Why.IN_WAY || why == Why.RIM) && target.distance() > release && !target.hitUs();
        // (a melee foe has to stay out there for WAY_RELEASE_TICKS: our own swing knocks it a block past the line for a tick)
        if (past && why == Why.IN_WAY && target.melee()) {
            if (releaseSince < 0) releaseSince = t.now();
            if (t.now() - releaseSince >= WAY_RELEASE_TICKS) return end(t, Event.FIGHT_CLEARED);
        } else if (past) {
            return end(t, Event.FIGHT_CLEARED);
        } else {
            releaseSince = -1;
        }
        // a blaze drifted off after it hit us: it is only a fight in contact, chasing it along a ledge is how we get shot off
        // (not one we walked over to on purpose, see blazeToSwap: that one is out of contact by design until it leaves our floor)
        if (target.kind() == Kind.FLYER && target.distance() > CONTACT + FLYER_SLACK && !walkingToBlaze(t, target)) return end(t, Event.FIGHT_CLEARED);
        // out of play: wandered off past LOST_RANGE and not hitting us. if it wants another go, that is a new fight
        // (not a sniper we are still walking up to: it is far by design, an arrow in the air or a miss is no reason to lose it
        // after three seconds, only the stall rule below writes that one off)
        if (target.distance() > LOST_RANGE && !(why == Why.PINNED && !contacted)) {
            if (farSince < 0) farSince = t.now();
            if (t.now() - farSince >= LOST_TICKS && target.sinceHit() > LOST_TICKS) {
                // boxed in, the second tier picks within 8, so a lost one would be picked again every few seconds. same
                // deal as a stall: written off until it hits us again
                if (cornered || target.standsAndFights()) ignored.put(target.id(), t.now());
                // a ranged foe we never reached and lost track of is the same story as one that stalled: the poison goes on
                if (atRange && !contacted) giveUpOn(target, t.now());
                return end(t, Event.FIGHT_LOST);
            }
        } else {
            farSince = -1;
        }
        // getting anywhere? contact is the best kind of progress, closing in counts, running off just moves the baseline
        double d = target.distance();
        // (from a standing pillbox a foe by its wall that has not hit us is no progress: a wither skeleton outside the box held
        // the fight for 6.4 game minutes, unreachable and never hitting, realfort-b run 4; the stall rule lets it go)
        // (a walker only: with blazes in it too the fight let go of every blaze hanging by the box, 53 killed against 90,
        // realfort-a-bent-l)
        boolean byTheBox = BlazeShelter.boxStands(t.now(), 40) && target.kind() != Kind.FLYER && target.sinceHit() > STALL_TICKS;
        if (d <= CONTACT && !byTheBox) {
            contacted = true;
            bestDistance = d;
            progressAt = t.now();
        } else if (d < bestDistance - PROGRESS) {
            bestDistance = d;
            progressAt = t.now();
        } else if (d > bestDistance + RUNAWAY_SLACK) {
            bestDistance = d;
        }
        if (t.now() - progressAt >= STALL_TICKS) {
            ignored.put(target.id(), t.now());
            if (atRange) giveUpOn(target, t.now());
            return end(t, Event.FIGHT_STALLED);
        }
        return Event.NONE;
    }

    // a blaze with a line on us that lately hit us, close and on our own floor, while we trade blows with something else. the
    // fire and the fireballs are most of the damage in that spot and it takes three or four swings, so it goes first. only from a
    // plain melee target: an enderman comes to us anyway, and turning from a wither skeleton or a heavy hitter is a free hit in
    // the back. boxed there is no walking over, and a walk along a rim is how we get shot off it
    private Foe blazeToSwap(Tick t, Foe target) {
        if (!t.armed() || t.health() <= FLEE_HP || t.boxed() || t.way().rim() || endermanAbout(t) != null) return null;
        if (!target.melee() || target.kind() != Kind.NORMAL || target.wither()) return null;
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.kind() != Kind.FLYER || !foe.shooting() || foe.distance() > BLAZE_SWAP_RANGE || Math.abs(foe.y()) > BLAZE_FLOOR_HEIGHT) continue;
            if (gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // the blaze we picked is still a walk away. it gets the slack on both lines so one bob over the line does not end the fight, and
    // under the hp line nobody is walking anywhere for it
    private boolean walkingToBlaze(Tick t, Foe target) {
        return lobberPick && t.health() > FLEE_HP && target.distance() <= BLAZE_SWAP_RANGE + FLYER_SLACK
                && Math.abs(target.y()) <= BLAZE_FLOOR_HEIGHT + FLYER_SLACK;
    }

    // the one we are swinging at is not the one hitting us. a shooter target with a melee foe that hit us in reach: that foe,
    // once. a melee target that has been out of reach for TAKEOVER_TICKS with such a foe in reach: that foe. anything else stays,
    // the first kill matters most and two foes taking turns must not make us ping-pong
    private Foe takeover(Tick t, Foe target) {
        // (the enderman comes to us, standing for it is the fight. swapping to whatever else is in reach would just swap back)
        if (target.standsAndFights()) return null;
        // the witch we picked over the zombies stays picked: swapping straight back to the one biting us is the loop this pick
        // exists to end. boxed in we cannot walk to it anyway, so the swing at what is on us is the old rule's
        if (lobberPick && !t.boxed()) return null;
        boolean shooter = target.ranged() && !target.creeper();
        if (!shooter && !target.melee()) {
            outReachSince = -1;
            return null;
        }
        if (!shooter) {
            if (target.inReach()) {
                outReachSince = -1;
                return null;
            }
            if (outReachSince < 0) outReachSince = t.now();
            if (t.now() - outReachSince < TAKEOVER_TICKS) return null;
        }
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.id() == target.id() || !foe.melee() || foe.kind() == Kind.UNTOUCHABLE || foe.mustRun(t.guardHealth())) continue;
            // (same wither rule as the other enderman paths)
            if (foe.standsAndFights() && witherOnFloor(t)) continue;
            if (!foe.hitUs() || !foe.inReach() || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.reachGap() < best.reachGap()) best = foe;
        }
        return best;
    }

    // the target died or left the world. if somebody else is already on us, carry straight on (a gap here is a tick of
    // the user task walking with a zombie in our face), otherwise it is over
    private Event targetGone(Tick t) {
        // (a chased fight stays CHASED, it is the same reason we turned around)
        Why boxed = why == Why.CHASED ? Why.CHASED : Why.CORNERED;
        // the lone wither corner was that skeleton's and nobody else's: whoever is next gets the normal bail rules
        boolean chain = cornered && !witherCorner;
        Foe lobber = lobberNext(t);
        if (lobber != null) {
            Event event = startFight(t, lobber, chain ? boxed : Why.HIT, Event.FIGHT_NEXT, chain);
            lobberPick = true;
            return event;
        }
        Foe heir = splitHeir(t);
        if (heir != null) return startFight(t, heir, chain ? boxed : Why.HIT, Event.FIGHT_NEXT, chain);
        Foe next = nextTarget(t);
        if (next != null) return startFight(t, next, chain ? boxed : Why.HIT, Event.FIGHT_NEXT, chain);
        // (PINNED: it is a walk away by design, a HIT fight would call that lost after three seconds)
        Foe patrol = patrolNext(t);
        if (patrol != null) return startFight(t, patrol, Why.PINNED, Event.FIGHT_NEXT, false);
        // a boxed in fight never asked the bail rules, so the warden standing on us when the last thing dies is still ours to
        // leave: a gap and a five second cooldown next to it is not a plan
        if (cornered && anyDanger(t)) return startRun(t, Why.HEAVY, Event.FIGHT_TO_RUN);
        // a cube that splits spawns its children when the body is removed, about a second after the last hit (the death animation).
        // ending the fight here hands the wheel to whatever walks next, and the children show up behind it. a short wait, then over
        if (lastSplits) {
            if (splitGoneAt < 0) splitGoneAt = t.now();
            if (t.now() - splitGoneAt <= SPLIT_WAIT_TICKS) return Event.NONE;
        }
        // an angry enderman that blinked away is not a dead one: it comes back angry and hits for 3.8 a second. the fight
        // waits for it (same clock as the split wait), and an enderman that shows up inside the wait is the same fight
        if (lastEnder) {
            if (splitGoneAt < 0) splitGoneAt = t.now();
            Foe back = endermanToFight(t);
            if (back != null) return startFight(t, back, cornered ? boxed : Why.HIT, Event.FIGHT_NEXT, cornered);
            if (t.now() - splitGoneAt <= ENDER_WAIT_TICKS) return Event.NONE;
        }
        return end(t, Event.FIGHT_DEAD);
    }

    // the next one of a patrol that is shooting us. nextTarget only takes what is in contact and the rest of them shoot from
    // six blocks, so that was fight over, a cooldown and arrows in the meantime. the hp line already had its say in fight().
    // cornered or boxed there is no walking over to it, those keep their own rules
    private Foe patrolNext(Tick t) {
        if (cornered || t.boxed()) return null;
        Foe best = null;
        for (Foe foe : candidates(t)) {
            if (!foe.patrol() || !foe.shooting() || foe.id() == targetId || ignored.containsKey(foe.id()) || gaveUpOn(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // a lobber that has been landing hits on us just now, from where it can see us, when the last target dies: it is the next one, ahead
    // of whatever zombie is nearest. the old order (nearest in reach first, a run for anything lobbing at low hp after) left us
    // trading blows with zombies at 11 hp while it threw from 10 blocks away. only at this moment: a live target is never dropped
    // for it. at or under the hp line it is not a fight to pick (the low hp run has it), and not bare handed
    private Foe lobberNext(Tick t) {
        // (boxed in there is no walking to it, the heavy hitter or the warden is a run and not a pick, see targetGone)
        if (!t.armed() || t.health() <= FLEE_HP || t.boxed() || anyDanger(t)) return null;
        Foe best = null;
        for (Foe foe : t.foes()) {
            if (foe.id() == targetId || foe.kind() != Kind.LOBBER || foe.sinceHit() > LOBBER_PICK_TICKS || !foe.sight() || foe.distance() > LOBBER_RANGE) continue;
            if (gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (best == null || foe.distance() < best.distance()) best = foe;
        }
        return best;
    }

    // how far from the corpse a split can spawn and still be the same fight. they pop out within about a block, 3 is the margin
    static final double SPLIT_RANGE = 3.0;
    // the death animation is 20 ticks, the rest is margin
    static final long SPLIT_WAIT_TICKS = 30;
    // a teleport and the walk back is a few seconds at most, three is plenty
    static final long ENDER_WAIT_TICKS = 60;

    private void noteSplit(Foe foe) {
        lastSplitType = foe.splitType();
        lastSplits = foe.splits();
        lastEnder = foe.standsAndFights();
        lastSplitX = foe.x();
        lastSplitY = foe.y();
        lastSplitZ = foe.z();
        splitGoneAt = -1;
    }

    // a slime or magma cube just died: the same kind next to the corpse is its split and the same fight. they have not hit us yet,
    // so the "must have hit us" rule of nextTarget loses them, the fight ends, and whatever walks next walks past a cube that is
    // about to bite (on a ledge, that bite is a knock into the drop)
    private Foe splitHeir(Tick t) {
        if (lastSplitType == null) return null;
        Foe best = null;
        double bestGap = Double.MAX_VALUE;
        for (Foe foe : t.foes()) {
            if (foe.id() == targetId || !lastSplitType.equals(foe.splitType()) || !foe.melee() || foe.kind() == Kind.UNTOUCHABLE
                    || foe.mustRun(t.guardHealth()) || ignored.containsKey(foe.id()) || gaveUpOn(foe, t.now())) continue;
            double gap = Math.sqrt(Math.pow(foe.x() - lastSplitX, 2) + Math.pow(foe.y() - lastSplitY, 2) + Math.pow(foe.z() - lastSplitZ, 2));
            if (gap <= SPLIT_RANGE && gap < bestGap) {
                best = foe;
                bestGap = gap;
            }
        }
        return best;
    }

    private Foe nextTarget(Tick t) {
        Foe best = null;
        if (cornered) {
            // still boxed in: whatever is touching us and has hit us (bar the one thing that is never a fight, and creepers: that
            // is the creeper step's, a fresh zombie standing next to us is not a reason to keep swinging)
            for (Foe foe : t.foes()) {
                if (foe.id() != targetId && foe.kind() != Kind.UNTOUCHABLE && !foe.creeper() && foe.hitUs() && foe.distance() <= CORNER_RANGE
                        && (best == null || foe.distance() < best.distance())) best = foe;
            }
            return best;
        }
        // the next thing chewing on us, same rule as starting one. (not one we gave up on, idle sorts that out once this fight
        // is over, and not one we only run from, runReason already had its say)
        List<Foe> rest = candidates(t).stream()
                .filter(foe -> foe.id() != targetId && !ignored.containsKey(foe.id()) && !foe.mustRun(t.guardHealth()) && !gaveUpOn(foe, t.now()))
                .toList();
        return hitInContact(rest, false);
    }

    private Event startFight(Tick t, Foe foe, Why reason, Event event, boolean boxedIn) {
        // a new fight with a mob we once gave up on is a clean slate with it (it hit us since, or we are boxed in with it)
        ignored.remove(foe.id());
        noteChase(t);
        // (a chain in a fast corner is still that corner. wiping it here left cornered on with nothing able to ever end it, and
        // took the heavy bail with it)
        fastCorner = fastCorner && event == Event.FIGHT_NEXT && boxedIn;
        if (!fastCorner) fastClearSince = -1;
        witherCorner = false;
        noRoom = false;
        outReachSince = -1;
        // (a chain on a fight keeps where it came from)
        if (event != Event.FIGHT_NEXT) {
            runLine = event == Event.RUN_TO_FIGHT;
            boxCorner = false;
        }
        if (event == Event.RUN_TO_FIGHT || event == Event.RUN_CHASED_FIGHT) {
            runLatch = true;
            latchChased = event == Event.RUN_CHASED_FIGHT;
            latchClearSince = -1;
        }
        contacted = false;
        lobberPick = false;
        atRange = foe.kind() == Kind.LOBBER || reason == Why.PINNED;
        mode = Mode.FIGHT;
        why = reason;
        targetId = foe.id();
        noteSplit(foe);
        cornered = boxedIn;
        startTick = t.now();
        bestDistance = foe.distance();
        progressAt = t.now();
        farSince = -1;
        releaseSince = -1;
        wayBoxSince = -1;
        return event;
    }

    // ---- RUN

    // a run starting now goes on from the last one (see RERUN_TICKS). a clock that went backwards (a new world) is a fresh run
    static boolean rerun(long now, long lastRunEnd, int lastRunLegs) {
        return now >= lastRunEnd && now - lastRunEnd <= RERUN_TICKS && lastRunLegs < RERUN_LEGS;
    }

    private Event startRun(Tick t, Why reason, Event event) {
        noteChase(t);
        mode = Mode.RUN;
        why = reason;
        targetId = -1;
        cornered = false;
        // (a creeper that keeps coming back has its own measure of a run that got nowhere, from where this one starts)
        if (reason != Why.REPEAT_CREEPER && lastRunClear && rerun(t.now(), lastRunEnd, lastRunLegs)) {
            originX = lastRunX;
            originZ = lastRunZ;
            runLegs = lastRunLegs + 1;
            runDistance = Math.hypot(t.x() - originX, t.z() - originZ) + RUN_DISTANCE;
        } else {
            originX = t.x();
            originZ = t.z();
            runLegs = 1;
            runDistance = RUN_DISTANCE;
        }
        startTick = t.now();
        clearSince = -1;
        noSightSince = -1;
        losRun = (reason == Why.SHOT || reason == Why.LOW_HP) && t.foes().stream().anyMatch(foe -> foe.sniper() && foe.shooting())
                && t.foes().stream().noneMatch(foe -> foe.distance() <= LOW_HP_RANGE && (!foe.ranged() || foe.creeper()));
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        noRoomSince = -1;
        noRoom = false;
        return event;
    }

    // a run that is not moving us with a melee foe in reach: nowhere to run, so this is the cornered fight already. the same
    // exit as the corner window below, only quicker, and it needs a gap somebody measured (see Foe.reachGap)
    private Event noRoomFight(Tick t) {
        Foe onUs = null;
        for (Foe foe : t.foes()) {
            if (foe.melee() && foe.kind() != Kind.UNTOUCHABLE && foe.inReach() && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
        }
        if (onUs == null) {
            noRoomSince = -1;
            return Event.NONE;
        }
        // (not trying: paused, the count and the anchor stay where they were)
        if (!t.trying()) return Event.NONE;
        if (noRoomSince < 0 || Math.hypot(t.x() - noRoomX, t.z() - noRoomZ) >= NO_ROOM_BLOCKS) {
            noRoomSince = t.now();
            noRoomCount = 1;
            noRoomX = t.x();
            noRoomZ = t.z();
        } else {
            noRoomCount++;
        }
        if (noRoomCount < NO_ROOM_TICKS) return Event.NONE;
        Event event = startFight(t, onUs, Why.CORNERED, Event.RUN_TO_FIGHT, true);
        noRoom = true;
        return event;
    }

    private Event run(Tick t) {
        // an angry enderman about turns any run into the fight: it teleports onto us, nothing outruns it. the warden still wins
        Foe ender = endermanToFight(t);
        // (a run from the warden or a heavy hitter is not ours to turn, it ends where it ends: that mob is still about once we are past 8)
        // (nor while a wither skeleton is on our floor: standing still to swat the enderman is the wait it needs to walk up and
        // hit twice, and the wither ticks are already eating the hp the fight would need)
        if (ender != null && why != Why.HEAVY && !anyDanger(t)) {
            return startFight(t, ender, Why.CHASED, Event.RUN_CHASED_FIGHT, false);
        }
        // melee only things we cannot outrun: stack up right here instead of jogging (and instead of turning round on them later)
        int perchH = perchAt(t);
        if (perchH > 0) return startPerch(t, perchH, "run " + why);
        double fromOrigin = Math.hypot(t.x() - originX, t.z() - originZ);
        double nearest = Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) {
            nearest = Math.min(nearest, foe.distance());
        }
        // RUN_CLEAR and the sight count both stop short of a bow: 12 blocks is well inside SHOOTER_RANGE, and a skeleton round a
        // corner is one step from its line again. neither ending counts until it is out of range. the cap still ends the run
        boolean bowRange = inBowRange(t);
        // clear means nothing angry within RUN_CLEAR. the clock is on whether or not we are far enough yet, so the moment
        // we are far enough the answer is already in
        if (nearest <= RUN_CLEAR || bowRange) {
            clearSince = -1;
        } else if (clearSince < 0) {
            clearSince = t.now();
        }
        if (t.now() - startTick >= RUN_CAP) return capped(t);
        if (losRun) {
            // the line is the whole point: out of sight is out of range, however few blocks that took
            boolean seen = t.foes().stream().anyMatch(foe -> foe.ranged() && !foe.creeper() && foe.sight());
            if (seen || bowRange) {
                noSightSince = -1;
            } else if (noSightSince < 0) {
                noSightSince = t.now();
            } else if (t.now() - noSightSince >= LOS_CLEAR_TICKS) {
                return end(t, Event.RUN_CLEAR);
            }
        } else if (fromOrigin >= runDistance && clearSince >= 0 && t.now() - clearSince >= RUN_CLEAR_TICKS) return end(t, Event.RUN_CLEAR);

        // running is not shaking it: it keeps landing hits, in contact or not. armed, that is a fight (the same boxed in fight
        // as the cap's, so it finishes this target and cannot flip back to a run on the next tick). unarmed, a punch is still
        // not a fight, the run goes on and the corner and cap rules decide
        boolean holds = creeperHolds(t);
        Event hitThrough = holds ? Event.NONE : hitThrough(t);
        if (hitThrough != Event.NONE) return hitThrough;

        Event cramped = holds ? Event.NONE : noRoomFight(t);
        if (cramped != Event.NONE) return cramped;

        if (nearest > CORNER_WATCH) {
            cornerSince = -1;
            return stuck(t);
        }
        // something is close, the corner window below is the one that counts and this one starts over when it leaves
        stuckSince = -1;
        if (cornerSince < 0 || Math.hypot(t.x() - cornerX, t.z() - cornerZ) >= CORNER_PROGRESS) {
            cornerSince = t.now();
            cornerX = t.x();
            cornerZ = t.z();
            return Event.NONE;
        }
        // on the spot for three seconds with something on top of us, or six with something anywhere within the watch range
        long stuck = t.now() - cornerSince;
        if (stuck < CORNER_TICKS || (nearest > CORNER_RANGE && stuck < CORNER_FAR_TICKS)) return Event.NONE;
        // nowhere to go and it is close: armed or not, hurt or not, this is a fight. never a creeper (the creeper step's). on top
        // of us anything else will do, further out only something we could walk up to and hit and have not given up on (a skeleton across water that
        // stalled a fight would be picked again every six seconds, with a run in between, for as long as we stood there).
        // the warden is the exception to "anything will do": never a fight in any state, boxed in or not
        Foe closest = null;
        for (Foe foe : t.foes()) {
            if (holds) break;
            if (foe.distance() > CORNER_WATCH || foe.kind() == Kind.UNTOUCHABLE) continue;
            if (foe.creeper() || (foe.distance() > CORNER_RANGE && ignored.containsKey(foe.id()))) continue;
            if (closest == null || foe.distance() < closest.distance()) closest = foe;
        }
        // nobody worth a fight: the run goes on, the cap or the mob settles it
        if (closest == null) return Event.NONE;
        return startFight(t, closest, Why.CORNERED, Event.RUN_TO_FIGHT, true);
    }

    private static boolean inBowRange(Tick t) {
        for (Foe foe : t.foes()) {
            if (foe.bow() && foe.distance() <= CombatRules.SHOOTER_RANGE) return true;
        }
        return false;
    }

    // the cap with nothing on us is just the end of a run. chased with something still inside 8, ending it only meant a fresh
    // run with a fresh origin on the very next tick, forever. so turn around and fight, unless we are under the flee line:
    // there the fresh origin is the point, a new run of the same kind instead of a fight we would lose
    private Event capped(Tick t) {
        // a melee chaser within 8 at the cap perches whatever chased() says: ending here is a run with a fresh origin, turning is a plain fight
        int perchH = perchAt(t);
        if (perchH > 0) return startPerch(t, perchH, "run cap");
        if (!chased(t)) return end(t, Event.RUN_CAP);
        Foe chaser = chaser(t);
        // nobody to turn on: the warden (danger starts the next run on the same step), or only a creeper at range or mobs we
        // gave up on (the run just ends, the cooldown waits for one of them to get on us or hit us)
        // and with danger about a fight would only bail again on the next step, so it is the next run straight away
        if (chaser == null || anyDanger(t) || creeperHolds(t)) return end(t, Event.RUN_CAP);
        if (lowChase(t) && !t.boxed() && fastTarget(t) == null) return startRun(t, Why.LOW_HP, Event.RUN_START);
        return startFight(t, chaser, Why.CHASED, Event.RUN_CHASED_FIGHT, true);
    }

    // a run that exists because the creeper step kept going round stays a run while that creeper is still within 8: turning on
    // the zombie walks us straight back to it, which is the loop the run was started for
    private boolean creeperHolds(Tick t) {
        if (why != Why.REPEAT_CREEPER) return false;
        for (Foe foe : t.foes()) {
            if (foe.creeper() && foe.distance() <= LOW_HP_RANGE) return true;
        }
        return false;
    }

    // RUN_HITS hits after the grace and the run is not working. armed with nothing we only run from about, turn on it (boxed
    // in rules). no pick this tick keeps the count, the next tick asks again
    private Event hitThrough(Tick t) {
        countRunHits(t);
        if (runHits < RUN_HITS || !t.armed() || anyDanger(t)) return Event.NONE;
        // hurt, turning round is the fight that ends us. the run keeps going, boxed in the corner rules still fight
        // (the worst hit of the chase counts, not just the last two seconds of them)
        if (lowChase(t)) return Event.NONE;
        Foe pick = hitPick(t);
        if (pick == null) return Event.NONE;
        return startFight(t, pick, Why.CHASED, Event.RUN_CHASED_FIGHT, true);
    }

    // a hit counts wherever the hitter is within SWARM_RANGE, not just in contact: the tick we hear about a hit is the tick it
    // knocked us back, so the mob that did it is three or four blocks off by then. never one we only run from (the warden is
    // in there), never one we gave up on and that has not hit us since
    private void countRunHits(Tick t) {
        for (Foe foe : t.foes()) {
            if (!foe.hitUs() || !foe.melee() || foe.distance() > CombatRules.SWARM_RANGE) continue;
            if (foe.mustRun(t.guardHealth()) || stillIgnored(foe, t.now())) continue;
            long at = t.now() - foe.sinceHit() - clockSkew;
            if (at <= startTick + RUN_HIT_GRACE) continue;
            Long counted = runHitAt.get(foe.id());
            if (counted != null && at <= counted) continue;
            runHitAt.put(foe.id(), at);
            runHits++;
        }
    }

    // who the hits turn us on: the closest melee foe in contact, else the closest one that landed a counted hit this run and is
    // still within 8. same "never one we only run from, no creeper, nothing we gave up on" as chaser()
    private Foe hitPick(Tick t) {
        Foe onUs = null;
        Foe hitter = null;
        for (Foe foe : t.foes()) {
            if (!foe.melee() || foe.mustRun(t.guardHealth()) || gaveUpOn(foe, t.now()) || stillIgnored(foe, t.now())) continue;
            if (foe.distance() <= CONTACT && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
            if (foe.distance() <= LOW_HP_RANGE && runHitAt.containsKey(foe.id()) && (hitter == null || foe.distance() < hitter.distance())) {
                hitter = foe;
            }
        }
        return onUs != null ? onUs : hitter;
    }

    private void clearRunHits() {
        runHits = 0;
        runHitAt.clear();
    }

    // still on our heels at the cap: hurt with something near, or a crowd that is still on us
    private boolean chased(Tick t) {
        if (crowded(t.health(), t.foes(), false)) return true;
        if (!lowChase(t)) return false;
        for (Foe foe : t.foes()) {
            if (foe.distance() <= LOW_HP_RANGE) return true;
        }
        return false;
    }

    // who to turn around on: the closest one on top of us or that hit us from inside 8, else the closest inside 8 we could
    // walk up to and hit (same "no creeper, nothing we gave up on" as the corner pick). never the warden
    private Foe chaser(Tick t) {
        Foe onUs = null;
        Foe near = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > LOW_HP_RANGE || foe.kind() == Kind.UNTOUCHABLE) continue;
            // (a lobber or sniper we could not reach is not who the run turns round on)
            if (gaveUpOn(foe, t.now())) continue;
            if (!foe.creeper() && (foe.distance() <= CONTACT || foe.hitUs()) && (onUs == null || foe.distance() < onUs.distance())) onUs = foe;
            if (!foe.creeper() && !ignored.containsKey(foe.id()) && (near == null || foe.distance() < near.distance())) near = foe;
        }
        return onUs != null ? onUs : near;
    }

    // nothing angry within CORNER_WATCH and no ground gained for RUN_STUCK_TICKS: the pathfinder has nowhere to take us (a lava
    // lake, the edge of the loaded world, an island)
    private Event stuck(Tick t) {
        if (stuckSince < 0 || Math.hypot(t.x() - stuckX, t.z() - stuckZ) >= CORNER_PROGRESS) {
            stuckSince = t.now();
            stuckX = t.x();
            stuckZ = t.z();
            return Event.NONE;
        }
        return t.now() - stuckSince >= RUN_STUCK_TICKS ? end(t, Event.RUN_STUCK) : Event.NONE;
    }

    // ---- the end of anything

    private Event end(Tick t, Event event) {
        // a run from a creeper that keeps coming back that got nowhere: the next time it comes back it is fought (below, idle)
        if (mode == Mode.RUN && why == Why.REPEAT_CREEPER && Math.hypot(t.x() - originX, t.z() - originZ) < CREEPER_RUN_NOWHERE) {
            creeperRunNowhereAt = t.now();
        }
        if (mode == Mode.RUN) {
            lastRunX = originX;
            lastRunZ = originZ;
            lastRunLegs = runLegs;
            lastRunEnd = t.now();
            lastRunClear = event == Event.RUN_CLEAR;
        }
        // (mode is still the one that is ending)
        recover.ended(t.now(), mode == Mode.FIGHT && runLine ? Mode.RUN : mode, t.health(), t.body());
        runLine = false;
        boxCorner = false;
        fastCorner = false;
        witherCorner = false;
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        farSince = -1;
        releaseSince = -1;
        clearSince = -1;
        clearRunHits();
        cornerSince = -1;
        stuckSince = -1;
        losRun = false;
        cooldownUntil = t.now() + COOLDOWN;
        return event;
    }
}
