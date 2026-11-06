package adris.altoclef.util.helpers;

// "heal up before going back in". a run that ended hurt used to hand the wheel straight back to the task, and the task's
// route goes right back past whatever we ran from: the next run starts a second later with less hp than the last one.
// so a commitment that ends hurt holds us where we are (CombatCommit owns one of these, the chain stands still for it)
// until we have healed, regen gave up, or it has gone on long enough. pure numbers like the rest of the machine
public final class RecoverHold {

    // a run that ends under this is a hold, and it is the same line the hold lets go at: a run that ends at 15.6 used to
    // walk straight back to the ore it ran from, toward the skeleton, with a bar and a half less than DONE_AT thinks is fine.
    // ending a run under DONE_AT and not holding was the gap, so there is none now
    public static final float RUN_BELOW = 18;
    // a fight that ends at or under the flee line. a fight we won at 10 is a won fight, the task carries on
    public static final float FIGHT_AT = CombatRules.FLEE_HEALTH;
    // healed enough to go back in. 14 was one creeper short: a pack in the dark takes 6 a blast through iron, and two of
    // those from 15 is the respawn screen while from 18 it is a bad day. regen at a full bar is quick, the stall exit
    // still lets go when it isn't happening
    public static final float DONE_AT = 18;
    // no hp back for this long (not counting meals): regen is not happening, standing here is just standing here.
    // plain regen at hunger 18 is a heart every 4 s, so 12 s is three misses in a row
    public static final long STALL_TICKS = 240;
    // the whole hold, re-runs included, never goes past this
    public static final long CAP_TICKS = 900;
    // anything this close while we hold is a run again, cooldown or not. same line as the run's bite
    public static final double RERUN_RANGE = CombatRules.RUN_BITE_START;
    // a projectile that hit us this recently, thrown by a blaze or a skeleton type, is a re-run in the hold even when the hostile
    // list never carried the thrower (a blaze only shows up charged, a skeleton can be past the foe range). two seconds
    public static final long SHOT_SOURCE_TICKS = 40;

    // who counts: not the ghast (it shoots from sixty blocks, "run 24" in the nether is lava, the shield is the answer to its
    // balls), not the wither skeleton (it is a melee mob)
    public static boolean rerunSource(String entityType) {
        return switch (entityType) {
            case "blaze", "skeleton", "stray", "bogged", "parched" -> true;
            default -> false;
        };
    }

    // meals while holding go to a full bar, saturation regen only kicks in at 20
    public static final int FILL_TO = 20;

    // what the body and the bag say, for the hold. healFood: the food chain's pick passes the heal meal's filter (no chorus,
    // no raw chicken, no rotten flesh). barred: a task that fights on its own terms has mob defense excluded (golem, dragon,
    // blaze rods), or we are in the end. eating: a bite is under way. lingering: wither, poison or fire is still ticking
    public record Body(int hunger, boolean healFood, boolean barred, boolean eating, boolean lingering) {
        // nobody said: no hold. the chain always says, the machine's own tests mostly do not care
        public static final Body NONE = new Body(20, false, true, false, false);

        public Body(int hunger, boolean healFood, boolean barred, boolean eating) {
            this(hunger, healFood, barred, eating, false);
        }

        // regen can happen: the bar is already at the regen line, or there is something decent to eat
        public boolean canRegen() {
            return hunger >= CombatRules.REGEN_HUNGER || healFood;
        }
    }

    // a commitment of this kind ended at this hp: is that a hold
    public static boolean wants(CombatCommit.Mode ended, float health) {
        return wants(ended, health, false);
    }

    // a fight that ends at 8.5 with wither still ticking is not a won fight, the next tick it is 7. lingering holds it too
    public static boolean wants(CombatCommit.Mode ended, float health, boolean lingering) {
        return switch (ended) {
            case RUN -> health < RUN_BELOW;
            case FIGHT -> health <= FIGHT_AT || (lingering && health < DONE_AT);
            default -> false;
        };
    }

    public static boolean mayStart(Body body) {
        return !body.barred() && body.canRegen();
    }

    private boolean on;
    private long startTick;
    private long stallTicks;
    private float lastHealth;
    private long lastTick;
    // the cap went off in the middle of a re-run: that run's end is not a new hold, or the cap would be a suggestion
    private boolean skipNextEnd;

    public boolean on() {
        return on;
    }

    public long startTick() {
        return startTick;
    }

    public long stallTicks() {
        return stallTicks;
    }

    public void reset() {
        on = false;
        stallTicks = 0;
        skipNextEnd = false;
    }

    // a commitment just ended (mode is the one that ended). one already on keeps its start and its cap: a re-run that ends
    // hurt is the same hold, not a fresh 45 s
    public void ended(long now, CombatCommit.Mode mode, float health, Body body) {
        if (skipNextEnd) {
            skipNextEnd = false;
            return;
        }
        // (a dead player is not recovering, whatever else ended on the same tick)
        if (health <= 0) return;
        if (on || !wants(mode, health, body.lingering()) || !mayStart(body)) return;
        on = true;
        startTick = now;
        stallTicks = 0;
        lastHealth = health;
        lastTick = now;
    }

    // once per step, before the machine moves. committed: a fight or a run is going on right now (a re-run), which keeps
    // the stall clock at zero, so a re-run never ends straight into a stall exit. a meal only pauses it
    public void update(long now, float health, boolean committed, Body body) {
        if (!on) return;
        long dt = Math.max(0, now - lastTick);
        lastTick = now;
        boolean gained = health > lastHealth;
        lastHealth = health;
        if (body.barred() || health >= DONE_AT) {
            on = false;
            return;
        }
        if (now - startTick >= CAP_TICKS) {
            on = false;
            stallTicks = 0;
            skipNextEnd = committed;
            return;
        }
        // (wither drains while we wait, so hp not rising says nothing until the effect is gone. the cap above still ends it)
        if (committed || gained || body.lingering()) {
            stallTicks = 0;
        } else if (!body.eating()) {
            stallTicks += dt;
        }
        // (never while committed, the line above keeps it at zero then)
        if (stallTicks >= STALL_TICKS) {
            on = false;
            stallTicks = 0;
        }
    }
}
