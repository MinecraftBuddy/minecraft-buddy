package adris.altoclef.util.helpers;

// may a bite start right now. chewing multiplies the move input by 0.2 and drops the sprint, and no movement in baritone
// knows that (a parkour jump, a sprint ascend or a bridge that places on the move comes up short), so outside a fight a
// bite only begins while we are standing still. pure: plain booleans in, a verdict out, the food chain gathers the inputs
public final class BiteRules {
    // blocks per tick of horizontal speed that still counts as standing. a sprint is 0.28 and the ground eats 45% of it per
    // tick, so this is a handful of ticks after the pause is granted
    public static final double STILL_SPEED = 0.03;
    // ticks of wanting a stop and not getting one before the log says so
    public static final int WAIT_LOG_TICKS = 60;
    // ticks of holding the use key with nothing happening before we let go of it and press it again
    public static final int USE_START_TICKS = 10;
    // presses again before we give up for a while, and how long a while is
    public static final int RETRIES = 3;
    // a laggy server can leave a normal bite at about -32 before the completion arrives
    public static final int STUCK_TICKS = 40;
    public static final int BACKOFF_TICKS = 40;
    // something with a name hit us this recently and a standing meal is off (the game forgets the damage source after 40)
    public static final int HIT_TICKS = 40;
    public static final int NO_HIT = Integer.MAX_VALUE;
    // starving and stuck behind a stop that does not come: ticks of wanting one, and the hunger that makes it an emergency
    public static final int ESCAPE_TICKS = 200;
    public static final int STARVING_HUNGER = 6;

    // the shield was called for this tick or the one before (the food chain asks before mob defense has run). a stamp from the
    // future is a clock that went backwards, a new world, and nothing there is dodging
    public static boolean dodgeHolds(long now, long dodgeTick) {
        return dodgeTick != Long.MIN_VALUE && now >= dodgeTick && now - dodgeTick <= 1;
    }

    private BiteRules() {
    }

    public enum Verdict {
        // standing safe (or the pause is granted and we stopped): start chewing, and hold the pause until the meal is done
        EAT_NOW,
        // a path is live and we can stop it: ask pathing to pause, touch nothing else (a slot swap would fight its tool)
        ASK_STOP,
        // we cannot stop here (air, water, ladder, boat, a movement that is mid jump): do nothing, ask again next tick
        WAIT,
        // the combat machine has the wheel, chewing on the move is how it works there and nothing changes
        CHEW_ON_THE_MOVE,
        // something hit us a moment ago: no standing meal starts and one going is dropped. asks for nothing, holds nothing
        NOT_NOW
    }

    // combat: a committed run or fight, or a stance that is not calm (the gapple and the quick bite live there).
    // meal: a bite is already going, it is ours to finish. pathLive: pathing has a path. pathPaused: it has one and it
    // did not tick this tick because we asked
    public record Scene(boolean combat, boolean meal, boolean screen, boolean onGround, boolean inWater, boolean onLadder,
                        boolean riding, double speed, boolean pathLive, boolean pathPaused, boolean safeToCancel, int hitAge) {
        // everything calm and still, no path: the starving-in-a-field case
        public static Scene standing() {
            return new Scene(false, false, false, true, false, false, false, 0, false, false, true, NO_HIT);
        }

        public Scene withCombat(boolean v) {
            return new Scene(v, meal, screen, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withMeal(boolean v) {
            return new Scene(combat, v, screen, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withScreen(boolean v) {
            return new Scene(combat, meal, v, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withOnGround(boolean v) {
            return new Scene(combat, meal, screen, v, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withInWater(boolean v) {
            return new Scene(combat, meal, screen, onGround, v, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withOnLadder(boolean v) {
            return new Scene(combat, meal, screen, onGround, inWater, v, riding, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withRiding(boolean v) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, v, speed, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withSpeed(double v) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, riding, v, pathLive, pathPaused, safeToCancel, hitAge);
        }

        public Scene withHitAge(int v) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, riding, speed, pathLive, pathPaused, safeToCancel, v);
        }

        public Scene withPath(boolean live, boolean paused, boolean safe) {
            return new Scene(combat, meal, screen, onGround, inWater, onLadder, riding, speed, live, paused, safe, hitAge);
        }
    }

    public static Verdict verdict(Scene s) {
        // the fight code decides how it eats, we are not in that business
        if (s.combat()) return Verdict.CHEW_ON_THE_MOVE;
        // somebody just hit us. standing still to chew is the last thing to do, and the fight code gets to decide what
        // happens next. before the meal check on purpose, a bite going is dropped too
        if (recentlyHit(s.hitAge())) return Verdict.NOT_NOW;
        // already chewing: dropping a bite halfway is worse than any of the below
        if (s.meal()) return Verdict.EAT_NOW;
        // a bite cannot start with a screen open (the game does not look at the use key then), so do not fight it
        if (s.screen()) return Verdict.WAIT;
        // falls end by themselves, waiting is the whole plan
        if (airborne(s)) return Verdict.WAIT;
        if (!s.pathLive()) {
            // nothing is steering us. water and ladders only matter to a movement that wants full speed
            return s.speed() > STILL_SPEED ? Verdict.WAIT : Verdict.EAT_NOW;
        }
        // a live path in water, on a ladder or in a boat is mid movement by definition, and nobody pauses a swim to chew
        if (s.inWater() || s.onLadder() || s.riding()) return Verdict.WAIT;
        if (s.pathPaused()) {
            return s.speed() > STILL_SPEED ? Verdict.ASK_STOP : Verdict.EAT_NOW;
        }
        // the movement says no (parkour past its run up, a jump in the air). asking anyway would leave a latch behind
        // that gets granted at some random later point
        return s.safeToCancel() ? Verdict.ASK_STOP : Verdict.WAIT;
    }

    // floating in water, hanging on a ladder or sitting in a boat are all "not on the ground" to the game, but that is not
    // falling. with nothing steering us they are a perfectly good place to chew, with a path they are waits of their own
    private static boolean airborne(Scene s) {
        return !s.onGround() && !s.inWater() && !s.onLadder() && !s.riding();
    }

    // the damage source's own age is only known to the game for 40 ticks, the chain keeps its own count
    public static boolean recentlyHit(int ticksSinceHit) {
        return ticksSinceHit >= 0 && ticksSinceHit <= HIT_TICKS;
    }

    // what counts as a hit: something with a body did it (the attacker, or the arrow or fireball that carried it). poison,
    // wither, falls, fire, lava and hunger have neither
    public static boolean entityHit(boolean hasAttacker, boolean hasDirectEntity) {
        return hasAttacker || hasDirectEntity;
    }

    // what drops a bite: a hit with a body that is not a projectile. an arrow or a fireball comes from a mob that is nowhere
    // near, so standing to chew is not the wrong call for it (the shield rules have their own say), a zombie in contact is
    public static boolean contactHit(boolean hasAttacker, boolean hasDirectEntity, boolean projectile) {
        return !projectile && entityHit(hasAttacker, hasDirectEntity);
    }

    // a bite in progress gives way to the projectile shield only when the thing is about to land: raising it quick-moves the
    // food out of the hand, and the bite starts over from nothing
    public static final double SHIELD_IMPACT_TICKS = 10;
    // a ghast ball speeds up on the way in and ticksToClosest assumes today's speed, so its clock reads long: 16 on it is
    // about when the shield still has its 5 ticks to come up. a lost bite costs a second, a ghast ball costs the run
    public static final double SHIELD_IMPACT_FIREBALL_TICKS = 16;

    public static boolean shieldMayStart(boolean biting, double impactTicks, boolean fireball) {
        return !biting || impactTicks <= (fireball ? SHIELD_IMPACT_FIREBALL_TICKS : SHIELD_IMPACT_TICKS);
    }

    // starving behind a stop that does not come. only water and a boat are worth the risk of chewing on the move: a swim
    // or a ride can be as long as the ocean, while mid-air ends by itself, a ladder is a few blocks and a movement that is
    // not safe to cancel is over in a moment
    public static boolean starvingEscape(int waitedTicks, int hunger, float health, String why) {
        if (waitedTicks < ESCAPE_TICKS) return false;
        if (hunger > STARVING_HUNGER && !CombatRules.desperateMeal(health, hunger)) return false;
        return "water".equals(why) || "boat".equals(why);
    }

    // how long we can want a stop before everybody else gets to stop pretending we are busy eating (the food chain's
    // needsToEat() goes quiet past this, the wanting itself carries on)
    public static boolean waitedTooLong(int ticks) {
        return ticks >= WAIT_LOG_TICKS;
    }

    // why we are not eating yet, for the log line. only built when the line is going out
    public static String why(Scene s) {
        if (s.screen()) return "screen";
        if (airborne(s)) return "mid-air";
        if (!s.pathLive()) return "moving";
        if (s.inWater()) return "water";
        if (s.onLadder()) return "ladder";
        if (s.riding()) return "boat";
        if (s.pathPaused()) return "sliding to a stop";
        return s.safeToCancel() ? "pause not granted" : "movement not safe";
    }

    // counts the ticks we have wanted a stop and not had one. a long swim or a fall chain can take a while, and eating on
    // the move is not the answer to that, so all this does is say so once
    public static final class Waiting {
        private int ticks;
        private boolean logged;

        // the line to log, or null
        public String tick(Verdict verdict, String why) {
            if (verdict != Verdict.ASK_STOP && verdict != Verdict.WAIT) {
                reset();
                return null;
            }
            ticks++;
            if (ticks >= WAIT_LOG_TICKS && !logged) {
                logged = true;
                return "food: waiting for a safe stop, " + ticks + "t (" + why + ")";
            }
            return null;
        }

        public int ticks() {
            return ticks;
        }

        public void reset() {
            ticks = 0;
            logged = false;
        }
    }

    public enum Press {
        HOLD,
        // let go of the key for this tick, the next hold is a clean rising edge
        PRESS_AGAIN,
        // the use is on but never finishes: tell the server to let go of it (see UseWatch), then press again
        RELEASE,
        // it will not start, stop trying for BACKOFF_TICKS
        BACK_OFF
    }

    // the bite that never starts: the key is down and the game never begins to use the item. a stale key (down with no
    // rising edge) is the lead suspect, so the cure is letting go and pressing again. fed once per tick that we hold
    public static final class UseWatch {
        private int idle;
        private int retries;
        // separate from retries on purpose: a use that is on resets retries, and a stuck use is always on
        private int stuck;
        private int backoff;
        private int steps;
        private int startedAt;
        private boolean wasUsing;
        private boolean started;

        public Press step(boolean using) {
            return step(using, 0);
        }

        // remaining is the client's use timer. a bite that sits well below zero never got its completion from the server
        // (the server is still "using" something else, a shield that broke under us), so more holding will not fix it
        public Press step(boolean using, int remaining) {
            started = using && !wasUsing;
            wasUsing = using;
            if (started) {
                startedAt = steps;
            }
            steps++;
            if (using) {
                idle = 0;
                retries = 0;
                if (remaining < -STUCK_TICKS) {
                    if (stuck >= RETRIES) {
                        backoff = BACKOFF_TICKS;
                        stuck = 0;
                        return Press.BACK_OFF;
                    }
                    stuck++;
                    return Press.RELEASE;
                }
                return Press.HOLD;
            }
            idle++;
            if (idle < USE_START_TICKS) return Press.HOLD;
            idle = 0;
            if (retries >= RETRIES) {
                backoff = BACKOFF_TICKS;
                retries = 0;
                return Press.BACK_OFF;
            }
            retries++;
            return Press.PRESS_AGAIN;
        }

        // ticks between the first step of this meal and the bite really starting, a bite that dragged its feet shows up here
        public int ticksToStart() {
            return startedAt;
        }

        public boolean startedThisTick() {
            return started;
        }

        public int retries() {
            return retries;
        }

        public boolean backingOff() {
            return backoff > 0;
        }

        // the clock runs whether or not anyone is eating, that is the point of a back off
        public void tickBackoff() {
            if (backoff > 0) backoff--;
        }

        // a meal ended. a back off in progress is not part of the meal and keeps counting
        public void reset() {
            idle = 0;
            retries = 0;
            stuck = 0;
            steps = 0;
            startedAt = 0;
            wasUsing = false;
            started = false;
        }

        public void clear() {
            reset();
            backoff = 0;
        }
    }
}
