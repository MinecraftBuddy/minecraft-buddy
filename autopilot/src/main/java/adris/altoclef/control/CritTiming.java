package adris.altoclef.control;

// when to jump and when to swing so a hit lands as a crit. no minecraft in here on purpose, PlayerExtraController gathers
// the facts every tick and this just says what to do about them, which is also why it has tests
//
// vanilla wants all of these at the moment of the hit: full cooldown, falling (fallDistance > 0 and off the ground), not on
// a ladder or vine, not in water, not blind, not riding, not sprinting. the adapter drops sprint on the jump tick and every
// tick after, and the machine never swings on a tick that still starts sprinting (the stop packet goes out after the interact
// packet of that tick, so the server would still see a sprint hit)
public class CritTiming {

    public enum Step {
        // no crit business, do whatever a swing would have done before this existed (hit if the cooldown is full)
        PLAIN,
        // press jump, and stop sprinting while at it
        JUMP,
        // in the air and waiting on the fall or the cooldown. hold the swing
        WAIT,
        // falling with a full cooldown. now
        SWING,
        // a hop is due soon but the mob is too far for it to still be in reach when we come down. walk up, do not jump
        APPROACH
    }

    // the jump goes up for 6 ticks (0.42 and then gravity eating it) and the first tick that is really falling is the 7th,
    // so a jump this many ticks before the cooldown fills lands the crit the very tick the cooldown is ready. any later
    // and we wait on the cooldown, any earlier and we wait on the fall. a sword (12.5 tick cooldown) would otherwise
    // lose about half its dps to the jump, which is the whole reason this is not just "jump at 90%"
    public static final int LEAD_TICKS = 7;

    // a jump that has not left the ground by now never will (baritone owns the keys, a trapdoor, whatever), swing normally
    private static final int TAKEOFF_TICKS = 3;
    // and one that is still going this long after is not a hop, something is off
    private static final int MAX_AIR_TICKS = 24;
    // nobody asked us for this long, whatever we were doing is stale (task switched, mob died, stopped being a target)
    private static final long STALE_TICKS = 3;

    // a crit is a bonus, not a reason to stand there. none of these is a good time to go hopping around:
    // three things on us at once. it used to also be "got hit in the last second", which vs a zombie (hits once a second, so
    // always) meant it never hopped at all. players crit while taking hits, that is most of pvp
    public static final int CROWD_NO_CRIT = 3;
    // and three hearts or fewer, where the hop's few ticks of not blocking start to cost real money
    public static final float LOW_HEALTH_NO_CRIT = 6;
    // however it goes, the swing is never held more than this many ticks past a full cooldown. the fall is not a promise
    public static final int MAX_READY_WAIT = 4;
    // the one swing that may wait longer: the opener (full cooldown, nobody hitting us), held on the ground while a path
    // lets go or we close the last bit of the gap. half a second, then it is a plain hit so a pig behind a fence still gets one
    public static final int MAX_OPENER_HOLD = 10;

    // the hop is 7+ ticks of nothing and the swing happens at the end of it, so the mob has to be well inside reach when we
    // leave the ground, not just barely in it. it used to jump the moment anything was in reach and then watch it stay out
    // there (the crit never connected, which was the whole bug). this much slack, measured eye to the mob's box like vanilla
    public static final double JUMP_MARGIN = 0.5;
    // and a mob walking away faster than this (blocks per tick along the line to us) will be gone by the time we come down.
    // 0.1 is a zombie's pace, so that is the line for "it is leaving"
    public static final double MAX_RETREAT = 0.1;

    // is the mob close enough, and staying put enough, that a hop now still ends with it in reach
    public static boolean closeEnoughToHop(double reachGap, double reach, double retreatSpeed) {
        return reachGap <= reach - JUMP_MARGIN && retreatSpeed <= MAX_RETREAT;
    }

    // the tooClose back-off in the kill tasks walks us away from the mob. mid hop (or right before one) that is exactly what
    // makes the swing whiff, so it sits those out
    public static boolean shouldBackOff(boolean tooClose, boolean hopWantsToStayClose) {
        return tooClose && !hopWantsToStayClose;
    }

    // no back-off from something that is already biting us in reach (the step cannot land under the knockback) or from an
    // angry enderman (it teleports onto us, backing off just makes us wait for the next hit with a path in the way of the swing)
    public static boolean noBackOff(boolean underFire, boolean angryEnderman) {
        return underFire || angryEnderman;
    }

    // a sword has no hop to wait for, so a swing that is due ends the back-off right now, mid step or not. other weapons keep
    // the polite cancel (a hop that is being planned is not worth a cancelled step on a ledge)
    public static boolean hardDropBackOff(boolean swordInHand, boolean stayClose) {
        return swordInHand && stayClose;
    }

    // the two reasons above as one question. PlayerExtraController asks it for wantsCritTick too, so the kill tasks and
    // this machine can't disagree about whether a hop is on
    public static boolean underPressure(int meleeNear, float health) {
        return meleeNear >= CROWD_NO_CRIT || health <= LOW_HEALTH_NO_CRIT;
    }

    // everything that can change the answer. the defaults are the easy case, every guard open, so a test sets only the
    // thing it is about
    public static class Sample {
        public boolean enabled = true;
        public boolean inReach = true;
        // where the mob is for the jump decision: eye to the nearest point of its box, the interaction range we have (3 for
        // a normal player), and how fast it is walking away from us. the defaults are a mob standing right in front of us
        public double reachGap = 1.0;
        public double reach = 3.0;
        public double retreatSpeed = 0;
        public boolean onGround = true;
        // dy < 0 and fallDistance > 0, the "is a crit possible this tick" half that cares about the fall
        public boolean falling = false;
        // ticks until the attack strength scale hits 1.0, 0 or less when it is ready
        public double ticksToFull = 0;
        // a plain hit kills it, so waiting for a crit is just waiting
        public boolean normalHitKills = false;
        // vanilla refuses the crit, or the jump would be a bad idea
        public boolean inFluid = false;
        public boolean onClimbable = false;
        public boolean blind = false;
        public boolean riding = false;
        // eating, or any other use of the item in hand
        public boolean usingItem = false;
        // shield up (KITE and STAND). jumping and blocking fight each other
        public boolean shielding = false;
        // something would bonk our head, or the first block of floor around us is not close and not lava free
        public boolean lowHeadroom = false;
        public boolean unsafeFloor = false;
        // baritone is walking a path. our jump press would be ignored at best and a parkour or pillar at worst
        public boolean pathing = false;
        // the client says we are sprinting, which is also what the server was last told (the stop packet goes out with the
        // player tick, after the interact packet of the tick we swing in). vanilla drops the crit for a sprinting hit
        public boolean sprinting = false;
        // how many melee mobs are within 3, how much health we have. the defaults are a quiet fight at full health
        public int meleeNear = 0;
        public float health = 20;
        // the target can't hit back, so nobody is owed the swing right now and a full cooldown can wait for the fall
        public boolean harmless = false;
        // we took a hit in the last half second or so. a hostile that is hitting us is owed the swing now, one that
        // hasn't yet gets the same opener hop a harmless one does
        public boolean recentlyHurt = false;
        // the ray the server last heard about is on the mob, in the arm's reach, nothing in the way. a swing that isn't is
        // refused anyway, so a crit waits for the aim instead of being thrown away on a whiff
        public boolean aimed = true;
        // the hand changed this tick or last. the cooldown and the attack speed only catch up in the player tick, so the
        // reading in this sample may still describe the item we just put away
        public boolean settling = false;

        boolean pressured() {
            return underPressure(meleeNear, health);
        }

        boolean retreating() {
            return retreatSpeed > MAX_RETREAT;
        }
    }

    private enum Phase {IDLE, AIRBORNE}

    private Phase phase = Phase.IDLE;
    // this hop started on a full cooldown (an opener), so the ready-wait cap stays out of it unless we get hit mid air
    private boolean openerHop = false;
    private int airTicks = 0;
    // airborne ticks the cooldown has been full for, the MAX_READY_WAIT cap counts these
    private int readyTicks = 0;
    // we jumped and came down without a swing. the next hit is a normal one instead of another try
    private boolean plainNext = false;
    // grounded ticks an opener has been held for, the MAX_OPENER_HOLD cap counts these. not in reset() on purpose:
    // a give up must not buy the next try a fresh half second
    private int openerTicks = 0;
    private long lastNow = Long.MIN_VALUE;
    private Step lastStep = Step.PLAIN;

    public Step step(long now, Sample s) {
        if (now == lastNow) {
            // the kill task and the force field both ask every tick. only the first one gets to act
            return lastStep == Step.JUMP || lastStep == Step.SWING ? Step.WAIT : lastStep;
        }
        if (lastNow != Long.MIN_VALUE && now - lastNow > STALE_TICKS) {
            reset();
            plainNext = false;
            openerTicks = 0;
        }
        lastNow = now;
        lastStep = decide(s);
        return lastStep;
    }

    private Step decide(Sample s) {
        if (!s.enabled) {
            reset();
            plainNext = false;
            openerTicks = 0;
            return Step.PLAIN;
        }
        // a swing went out, whatever was held is paid
        if (s.ticksToFull > 0) {
            openerTicks = 0;
        }
        if (phase == Phase.AIRBORNE) {
            return airborne(s);
        }
        // a swing now could be the old item's cooldown talking, and a hop now would be timed off a number that is about to
        // jump. a tick or two of standing still is cheaper than either
        if (s.settling) {
            return Step.WAIT;
        }
        // (a harmless target has no full cooldown veto to force the hit on landing, so a mid rise call can't spend the
        // promise: it can't swing anyway, and the hop would just start over)
        boolean couldSwing = s.onGround || s.falling || !s.harmless;
        if (plainNext && couldSwing) {
            plainNext = false;
            return Step.PLAIN;
        }
        // already coming down (a knockback, a ledge) with everything lined up: that is a free crit, take it. still sprinting
        // means the server would call it a sprint hit, so drop it this tick and swing on the next one (still falling). the
        // crosshair not on it yet is the same deal, the swing would only be refused
        if (!s.onGround) {
            if (!(s.falling && (s.inReach || s.aimed) && vanillaAllows(s) && s.ticksToFull <= 0)) {
                return Step.PLAIN;
            }
            return s.sprinting || !s.aimed ? Step.WAIT : Step.SWING;
        }
        if (canJump(s)) {
            phase = Phase.AIRBORNE;
            airTicks = 0;
            readyTicks = 0;
            openerHop = s.ticksToFull <= 0;
            return Step.JUMP;
        }
        if (s.ticksToFull <= 0 && opens(s)) {
            return opener(s);
        }
        // hop is due but the mob is a bit far. close the gap now instead of jumping at nothing. in a fight this only ever
        // runs while the cooldown is charging, so the plain swing still fires the tick it fills. an opener is the one case
        // with a full cooldown, and opener() has the cap that keeps that from holding a swing for ever
        return wantsHop(s) && !s.retreating() ? Step.APPROACH : Step.PLAIN;
    }

    // a full cooldown that nobody is owed yet: something harmless, or a hostile that hasn't landed a hit on us lately
    private static boolean opens(Sample s) {
        return s.harmless || !s.recentlyHurt;
    }

    // full cooldown, on the ground, nobody owed the swing, and no jump this tick. a hunt is all openers (we walk up to every
    // animal, so the cooldown is always full on arrival), and so is a zombie walking into reach while our cooldown has long
    // been full. the approach path is usually still alive on the tick we get into reach, so without a short hold the hop
    // never gets its turn. only a path or a bit of gap is worth holding for: any other veto, a mob walking off, a hit
    // landing on us or the cap running out is a plain hit right now
    private Step opener(Sample s) {
        if (hopVeto(s, false) != null || s.retreating() || ++openerTicks > MAX_OPENER_HOLD) {
            return Step.PLAIN;
        }
        // WAIT while baritone still has the keys (the adapter keeps its hands off them), walk up once it let go
        return s.pathing ? Step.WAIT : Step.APPROACH;
    }

    private Step airborne(Sample s) {
        airTicks++;
        // (a crowd or a bad health number showing up while we are up is in here too. come down swinging, not waiting. and
        // a weapon swap mid hop: the cooldown we jumped on is not the one we have now)
        if (!vanillaAllows(s) || s.shielding || s.usingItem || s.pathing || s.pressured() || s.settling) {
            return giveUp();
        }
        if (s.onGround) {
            // the tick after the jump press we can still be standing, the player has not ticked yet
            if (airTicks < TAKEOFF_TICKS) {
                return Step.WAIT;
            }
            // up and back down without a swing, or never up at all
            return giveUp();
        }
        if (airTicks > MAX_AIR_TICKS) {
            return giveUp();
        }
        readyTicks = s.ticksToFull <= 0 ? readyTicks + 1 : 0;
        // out of reach mid air: a knockback, or the mob took a step. it can step back in before we come down, and giving up
        // up here buys nothing because nothing can swing anyway. the landing is where that hop gives up. (inReach is the
        // planning reach, a bit short of the arm. a crosshair that is on it is in the arm's reach, and that's a swing)
        if (!s.inReach && !s.aimed) {
            return Step.WAIT;
        }
        // falling with a full cooldown is the crit. still going up with one past the cap is a plain hit from here, but it
        // is a hit. not for an opener: its hop starts with the cooldown already full, so the cap would swing on the way up
        // every time and throw away the crit the hop was for. unless something hits us mid air, then it is owed after all
        boolean capped = readyTicks > MAX_READY_WAIT && !s.harmless && (!openerHop || s.recentlyHurt);
        if ((s.falling && s.ticksToFull <= 0) || capped) {
            // still sprinting: the stop packet has not made it yet, the WAIT clears it and the swing is a tick late. not
            // aimed: the swing would be refused, so wait for the crosshair. still falling is still a crit
            if (s.sprinting || !s.aimed) {
                return Step.WAIT;
            }
            reset();
            return Step.SWING;
        }
        return Step.WAIT;
    }

    // swing normally from here on: this tick if there is a swing to give, and the next hit too so a cooldown that never
    // lines up with the fall can't turn into a bunny hop
    private Step giveUp() {
        reset();
        plainNext = true;
        return Step.PLAIN;
    }

    public void reset() {
        phase = Phase.IDLE;
        openerHop = false;
        airTicks = 0;
        readyTicks = 0;
    }

    // mid jump and somebody is still asking. the kill task lets go of its grounded check for this
    public boolean inFlight(long now) {
        return phase == Phase.AIRBORNE && now - lastNow <= STALE_TICKS;
    }

    private static boolean vanillaAllows(Sample s) {
        return !s.inFluid && !s.onClimbable && !s.blind && !s.riding;
    }

    private static boolean canJump(Sample s) {
        return wantsHop(s) && closeEnoughToHop(s.reachGap, s.reach, s.retreatSpeed);
    }

    // every reason to hop except where the mob is: guards, and the cooldown being inside the lead
    public static boolean wantsHop(Sample s) {
        return hopVeto(s) == null;
    }

    // the first thing standing between us and a hop, null when there is none. same list wantsHop always had, as words so
    // the play log can say which one fired
    public static String hopVeto(Sample s) {
        return hopVeto(s, s.pathing, false);
    }

    // is a hop on the cards for this cooldown at all, even if it is not time yet. the kill task's back-off asks this: it has
    // to stay off for the whole charge and not just the lead, or it is still walking when the lead comes and the path vetoes
    // the hop it was meant to make room for. so every veto except the path (usually that is the back-off itself), the lead
    // window and a settling swap (those two are just "not yet")
    public static boolean hopPlanned(Sample s) {
        return hopVeto(s, false, true) == null;
    }

    // pathing as its own argument so opener() can ask "would it hop if the path were gone" without touching the sample
    private static String hopVeto(Sample s, boolean pathing) {
        return hopVeto(s, pathing, false);
    }

    private static String hopVeto(Sample s, boolean pathing, boolean planning) {
        if (!s.inReach) {
            return "out of reach";
        }
        if (!vanillaAllows(s)) {
            return "vanilla refuses (fluid, ladder, blind, riding)";
        }
        if (s.usingItem) {
            return "using item";
        }
        if (s.shielding) {
            return "shielding";
        }
        if (s.lowHeadroom) {
            return "low headroom";
        }
        if (s.unsafeFloor) {
            return "unsafe floor";
        }
        if (pathing) {
            return "pathing";
        }
        if (s.settling && !planning) {
            return "weapon swap settling";
        }
        // a plain hit kills it, a jump would only make the kill later
        if (s.normalHitKills) {
            return "plain hit kills";
        }
        // crowded or hurting: swing like a person who would like to keep their hearts
        if (s.meleeNear >= CROWD_NO_CRIT) {
            return "crowd (" + s.meleeNear + ")";
        }
        if (s.health <= LOW_HEALTH_NO_CRIT) {
            return "low health";
        }
        // a full cooldown while something is hitting us means the swing is owed right now. a hop is 7 ticks up before the
        // first falling one, past the cap on how long a crit may hold a swing, so a hop then is a delay with extra steps.
        // nobody hitting us (an animal, or a hostile walking into reach while our cooldown has been full for ages) is owed
        // nothing, and without the opener the first hit of every fight was a plain one
        if (s.ticksToFull <= 0 && !opens(s)) {
            return "cooldown full, swing now";
        }
        if (s.ticksToFull > LEAD_TICKS && !planning) {
            return "cooldown not in the lead yet";
        }
        return null;
    }
}
