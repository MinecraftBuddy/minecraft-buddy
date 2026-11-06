package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.CombatRules;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

// sleeping through the night instead of fighting through it. every decision lives here and nothing here knows there is a game:
// when the trip applies, where the bed has to be to be out of the monsters' reach, the budget of a night, when mob defense
// stands down. NightSleepTask does the walking and the placing, GamerTask runs the trip between the phases
public final class NightSleep {
    // when a bed works is the game's own isBrightOutside (isDay back on 1.21.4), which is false from dusk to dawn and all through a thunderstorm: ticks
    // 12542 to 23459 in clear weather, 12010 to 23991 in full rain on 1.21.4 (so the old 23992 end of WorldHelper.canSleep was the rainy
    // one). 26.3 samples sky light off keyframes instead, which moves the edges a few ticks (worked out as about 12523 to 23477 clear,
    // not checked in game). nothing here keeps a copy of that line, the branch asks the level
    // the clock itself is still the overworld clock with a 24000 period, so DAY_LENGTH holds
    public static final long DAY_LENGTH = 24000;

    // the box the game looks in around the bed for monsters, and how far the bed may be from the one that clicks it
    public static final double BOX_HORIZONTAL = 8;
    public static final double BOX_VERTICAL = 5;
    // a monster this far (flat) from the bed counts as one that is coming. the tracker's hostile list stops at 16 anyway
    public static final double COMING_RANGE = 16;

    private NightSleep() {
    }

    // a night is one number, so the budget knows when a new one starts. dusk and dawn are the same day count
    public static long nightOf(long dayTime) {
        return Math.floorDiv(dayTime, DAY_LENGTH);
    }

    // ---- whether the trip applies

    public enum BedSource {
        NONE, BAG, CRAFT, FOUND
    }

    public enum Why {
        GO, OFF, WRONG_PLACE, NOT_NIGHT, BUSY, FIGHTING, LOW_HEALTH, QUIET, NO_BED, LEFTOVER
    }

    // everything the branch can see before it starts. busy is the engine's side (a nether trip, a recovery, a station being
    // picked up, a phase that must not be interrupted), fighting is a commitment the combat machine already holds, spent is
    // "this night is used up" (tries or seconds) and cooling is "a try just failed, wait a bit"
    // inPortal is "our feet or head are in a nether portal": the trip would start inside it, and the game teleports us out of it
    // six seconds later
    public record Inputs(boolean enabled, boolean overworld, boolean windowOpen, boolean busy, boolean fighting, boolean lowHealth,
                         boolean spent, boolean cooling, BedSource bed, boolean leftoverBed, boolean inPortal) {
        // the callers that have nothing to say about portals (BuildNightSleep walks its own way out)
        public Inputs(boolean enabled, boolean overworld, boolean windowOpen, boolean busy, boolean fighting, boolean lowHealth,
                      boolean spent, boolean cooling, BedSource bed, boolean leftoverBed) {
            this(enabled, overworld, windowOpen, busy, fighting, lowHealth, spent, cooling, bed, leftoverBed, false);
        }
    }

    // a bed of ours still standing from an earlier trip is picked up whatever the clock says, as long as nothing else has the
    // wheel (and not in a tight loop: it waits out the cooldown). the rest only starts at night, with a bed to use, and quietly
    // otherwise
    public static Why decide(Inputs in) {
        if (!in.enabled()) {
            return Why.OFF;
        }
        if (!in.overworld()) {
            return Why.WRONG_PLACE;
        }
        // (coming out of a portal at night: the return phase walks us off it first, then the trip can start on solid ground)
        if (in.busy() || in.inPortal()) {
            return Why.BUSY;
        }
        if (in.fighting()) {
            return Why.FIGHTING;
        }
        // (a trip would give up on its first tick, and starting one every tick keeps the phase from ever running)
        if (in.lowHealth()) {
            return Why.LOW_HEALTH;
        }
        if (in.leftoverBed()) {
            return in.cooling() ? Why.QUIET : Why.LEFTOVER;
        }
        if (!in.windowOpen()) {
            return Why.NOT_NIGHT;
        }
        if (in.spent() || in.cooling()) {
            return Why.QUIET;
        }
        return in.bed() == BedSource.NONE ? Why.NO_BED : Why.GO;
    }

    // the phases a night may cut into. the portal is let through and decides for itself (PhaseHandler.nightHold: lava being cast
    // must not be stretched, but looking for a spot is just walking), the nether is let through too (the night branch only runs in
    // the overworld, so only the home half of a food trip gets night handling, and keepsSpawnBed guards the bed by the portal),
    // the end prep is let through too (keepsSpawnBed guards the bed by the portal, and it holds the night itself once it only has the
    // walk in left, the End has no night), and the dragon is in the end
    public static boolean phaseAllowed(GamerPhase phase) {
        return switch (phase) {
            case GATHER, IRON, PORTAL, NETHER, EYES, RETURN, LOCATE, ROOM, OPEN, END_PREP -> true;
            default -> false;
        };
    }

    public enum Wake {
        SLEPT, WOKEN, WAIT
    }

    // the sleeping flag clears the tick the server skips the night, but the client learns the new time from a time packet that
    // goes out every 20 ticks, so for a moment it can still read night. reading it on the first tick called a night we slept
    // through "woken early" and burned a try. so: the morning is either the day flag or the day count having rolled over since
    // we lay down (the skip lands exactly on a multiple of 24000), and anything else gets `graceTicks` to turn into one of
    // those before it counts as a hit that woke us
    public static Wake wake(long asleepTicks, boolean day, long dayTimeThen, long dayTimeNow, long ticksSinceUp, int graceTicks) {
        if (asleepTicks <= 0) {
            return Wake.WOKEN;
        }
        if (day || nightOf(dayTimeNow) > nightOf(dayTimeThen)) {
            return Wake.SLEPT;
        }
        return ticksSinceUp < graceTicks ? Wake.WAIT : Wake.WOKEN;
    }

    // where the bed comes from. a bed already standing close beats carrying one (nothing to place, nothing to pick up), then the
    // bag, then crafting. a craft needs three wool of one colour and three planks, no hunting for either
    public static BedSource bedSource(boolean foundUsable, int bedsInBag, int bestWoolStack, int planks) {
        if (foundUsable) {
            return BedSource.FOUND;
        }
        if (bedsInBag > 0) {
            return BedSource.BAG;
        }
        return bestWoolStack >= 3 && planks >= 3 ? BedSource.CRAFT : BedSource.NONE;
    }

    // a placed bed (bag or craft) sleeps wherever we stand, and a nether or eyes leg that got home for food would move the respawn
    // off the bed by the portal and then pick that bed up. so while the spawn bed is standing those phases leave the night alone
    // (END_PREP: a night bed 200 blocks out must not move the respawn off the portal either. ROOM and OPEN: the beds made ahead of
    // the dig are in the bag from here on, and the one by the start is what a death in the shaft comes back to).
    // a bed that is already standing close (FOUND) is never claimed or picked up, that one stays fine
    public static boolean keepsSpawnBed(GamerPhase phase, BedSource source, boolean spawnBedStands) {
        return spawnBedStands && (phase == GamerPhase.NETHER || phase == GamerPhase.EYES || phase == GamerPhase.ROOM || phase == GamerPhase.OPEN
                || phase == GamerPhase.END_PREP)
                && (source == BedSource.BAG || source == BedSource.CRAFT);
    }

    // ---- a bed in the bag before dusk

    // the earliest the game lets anybody sleep: full rain opens it at 12010, clear weather at 12542 (the branch asks the level, not
    // this). the prep window is counted back from here so it is over for either
    public static final long EARLIEST_WINDOW = 12010;
    // the evening's prep stops here whatever the weather did (after it the trip itself has the craft stage)
    public static final long PREP_END = 13000;

    // what to do about a bed while it is still day, or why not. the bag is empty of beds at dusk on most evenings, and a trip can
    // only start once there is something to put down
    public enum Prep {
        CRAFT, WOOL,
        OFF, WRONG_PLACE, WRONG_PHASE, TOO_EARLY, TOO_LATE, BUSY, FIGHTING, LOW_HEALTH, BED_READY, SPENT, NO_WOOD, NO_SHEEP, GAVE_UP
    }

    // everything prep looks at. timeOfDay is the day time modulo a day. bedReady is a bed in the bag, one standing close that we
    // could use, or one of ours left standing. wood is planks plus four a log. sheepNear is a sheep we know of within the wool
    // radius. woodNeeded is the bed's three planks, and four more for a table when none is close or in the bag. the two gaveUps are
    // "this evening's wool trip / craft ran out of time"
    public record PrepInputs(boolean enabled, boolean overworld, boolean phaseOk, boolean busy, boolean fighting, boolean lowHealth,
                             boolean nightSpent, boolean windowOpen, long timeOfDay, boolean bedReady, int bestWoolStack, int wood,
                             int woodNeeded, boolean sheepNear, boolean woolGaveUp, boolean craftGaveUp) {
    }

    // craftLead and woolLead are ticks before EARLIEST_WINDOW. a craft is seconds, a wool trip is a walk and a chase, so it starts
    // earlier. the craft step also finishes a bed once the wool trip got the wool (it is the same rule, the materials are there)
    public static Prep prep(PrepInputs in, long craftLead, long woolLead) {
        if (!in.enabled()) {
            return Prep.OFF;
        }
        if (!in.overworld()) {
            return Prep.WRONG_PLACE;
        }
        if (!in.phaseOk()) {
            return Prep.WRONG_PHASE;
        }
        if (in.windowOpen() || in.timeOfDay() >= PREP_END) {
            return Prep.TOO_LATE;
        }
        if (in.timeOfDay() < EARLIEST_WINDOW - Math.max(craftLead, woolLead)) {
            return Prep.TOO_EARLY;
        }
        if (in.bedReady()) {
            return Prep.BED_READY;
        }
        if (in.nightSpent()) {
            return Prep.SPENT;
        }
        if (in.busy()) {
            return Prep.BUSY;
        }
        if (in.fighting()) {
            return Prep.FIGHTING;
        }
        if (in.lowHealth()) {
            return Prep.LOW_HEALTH;
        }
        if (in.wood() < in.woodNeeded()) {
            return Prep.NO_WOOD;
        }
        if (in.bestWoolStack() >= 3) {
            if (in.timeOfDay() < EARLIEST_WINDOW - craftLead) {
                return Prep.TOO_EARLY;
            }
            return in.craftGaveUp() ? Prep.GAVE_UP : Prep.CRAFT;
        }
        if (in.timeOfDay() < EARLIEST_WINDOW - woolLead) {
            return Prep.TOO_EARLY;
        }
        if (in.woolGaveUp()) {
            return Prep.GAVE_UP;
        }
        return in.sheepNear() ? Prep.WOOL : Prep.NO_SHEEP;
    }

    // the wood prep asks for. a ready bed only ever wants its own three, and so does a hunt that is running: a sheep 100 blocks out
    // walks us past the table's reach, then the four planks for a new table make it NO_WOOD halfway there, the phase walks home, the
    // table is close again and the hunt is back on. same idea as SheepHold. the craft still asks the real number
    public static int prepWoodNeeded(boolean bedReady, boolean hunting, IntSupplier withTable) {
        return bedReady || hunting ? 3 : withTable.getAsInt();
    }

    // which colour to hunt: the one we hold the most of that the sheep around could still make three of (a kill is one wool, a
    // shearing is one to three, so one a sheep is the safe count). held and near are indexed the same way, one slot a colour.
    // ties go to the colour with more sheep. -1 when no colour can get there
    public static int woolColour(int[] held, int[] near) {
        int best = -1;
        for (int i = 0; i < held.length; i++) {
            if (held[i] + near[i] < 3) {
                continue;
            }
            if (best < 0 || held[i] > held[best] || (held[i] == held[best] && near[i] > near[best])) {
                best = i;
            }
        }
        return best;
    }

    // ---- what may not be interrupted

    public enum Busy {
        NONE, PHASE, PHASE_HOLD, PICKUP, LOAD, EARLY_LOAD, EARLY_COLLECT, SCREEN
    }

    // the things a night trip waits for, in the order they are asked. each is a thing that is physically happening right now
    // and would be left half done: a station coming down that somebody is driving (a stale pickup does not count, it goes back
    // to standing on its own), ore going into a furnace that is not lit yet, the first iron going into one before the job is
    // recorded, a screen that is up. a smelt batch merely existing is not one of them: between loads the bot is only walking
    // or fetching fuel, and a load that gets interrupted is finished by a fresh task
    public static Busy busy(boolean phaseOk, boolean pickupInFlight, boolean smeltLoading, boolean earlyLoad, boolean screenUp) {
        return busy(phaseOk, false, pickupInFlight, smeltLoading, earlyLoad, screenUp);
    }

    // `phaseHolds` is an allowed phase asking to finish what it is doing first. not PHASE on purpose: PHASE turns the evening's bed
    // prep off, a hold only stops the trip and the prep keeps getting the bed ready
    public static Busy busy(boolean phaseOk, boolean phaseHolds, boolean pickupInFlight, boolean smeltLoading, boolean earlyLoad, boolean screenUp) {
        return busy(phaseOk, phaseHolds, pickupInFlight, smeltLoading, earlyLoad, false, screenUp);
    }

    // `earlyCollect`: the early iron is seconds from done and the furnace is right here, the bed waits for the pickup
    public static Busy busy(boolean phaseOk, boolean phaseHolds, boolean pickupInFlight, boolean smeltLoading, boolean earlyLoad,
                            boolean earlyCollect, boolean screenUp) {
        if (!phaseOk) {
            return Busy.PHASE;
        }
        if (phaseHolds) {
            return Busy.PHASE_HOLD;
        }
        if (pickupInFlight) {
            return Busy.PICKUP;
        }
        if (smeltLoading) {
            return Busy.LOAD;
        }
        if (earlyLoad) {
            return Busy.EARLY_LOAD;
        }
        if (earlyCollect) {
            return Busy.EARLY_COLLECT;
        }
        return screenUp ? Busy.SCREEN : Busy.NONE;
    }

    // a container screen (the inventory, a table, a furnace, a chest) is up for a few seconds while items move. one that has been up
    // longer than this is stuck open, and a night should not wait for it: the trip shuts it when it begins
    public static final long SCREEN_PATIENCE_TICKS = 400;
    // a furnace load that has been "in progress" this long is a batch whose iron need went away under it, not ore going in
    public static final long LOAD_PATIENCE_TICKS = 1200;

    public static boolean screenHolds(long upTicks) {
        return upTicks < SCREEN_PATIENCE_TICKS;
    }

    public static boolean loadHolds(long loadingTicks) {
        return loadingTicks < LOAD_PATIENCE_TICKS;
    }

    // a clock for one day's prep: it gets `maxSeconds` and then it is over, quietly, until the next day
    public static final class DayClock {
        private long day = Long.MIN_VALUE;
        private double seconds;
        private boolean gaveUp;

        public void roll(long dayId) {
            if (dayId != day) {
                day = dayId;
                seconds = 0;
                gaveUp = false;
            }
        }

        public void spend(double dt) {
            seconds += dt;
        }

        public double seconds() {
            return seconds;
        }

        public void giveUp() {
            gaveUp = true;
        }

        public boolean over(double maxSeconds) {
            return gaveUp || seconds >= maxSeconds;
        }
    }

    // "is a bed ready" with a short memory. the check flipped yes and no every few steps (best guess is a standing bed right on
    // the radius, the bed ready line in the log says which part moves), and every flip handed the wheel between the craft and
    // the phase. a yes holds for READY_HOLD_TICKS game ticks after the last real one, so a bed has to stay gone for five seconds
    // before the craft starts. a new day forgets it
    public static final long READY_HOLD_TICKS = 100;

    public static final class ReadyLatch {
        private long day = Long.MIN_VALUE;
        private long lastReady;
        private boolean seen;

        public boolean see(boolean raw, long tick, long dayId) {
            if (dayId != day) {
                day = dayId;
                seen = false;
            }
            if (raw) {
                seen = true;
                lastReady = tick;
                return true;
            }
            return seen && tick - lastReady < READY_HOLD_TICKS;
        }

        public void reset() {
            seen = false;
        }
    }

    // "is there a sheep to hunt" with two memories, because the answer sits on the edge of the search radius and flips with every
    // step of the sheep. a hunt that is running is not asked again (every flip cancelled the hunt and wiped its colour, so
    // nothing ever got far enough to finish), and a no is kept for SHEEP_HOLD_TICKS game ticks before anybody looks again
    public static final long SHEEP_HOLD_TICKS = 600;

    public static final class SheepHold {
        private long noUntil = Long.MIN_VALUE;

        // raw is only asked when neither memory has the answer
        public boolean see(boolean hunting, BooleanSupplier raw, long tick) {
            if (hunting) {
                return true;
            }
            if (tick < noUntil) {
                return false;
            }
            boolean yes = raw.getAsBoolean();
            if (!yes) {
                noUntil = tick + SHEEP_HOLD_TICKS;
            }
            return yes;
        }

        public void reset() {
            noUntil = Long.MIN_VALUE;
        }
    }

    // ---- when the branch says why it is not running

    // a line in the log when the answer changes and not before, and no faster than every `gap` ticks (a gate that flaps would
    // print every other tick). a key that was held back is still different next time, so it comes out then
    public static final class Said {
        private String last;
        private long lastTick = Long.MIN_VALUE / 2;

        public boolean worthSaying(String key, long tick, long gap) {
            if (key.equals(last) || tick - lastTick < gap) {
                return false;
            }
            last = key;
            lastTick = tick;
            return true;
        }
    }

    // ---- monsters

    // a monster's box, the way the game sees it
    public record Mob(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    // the game's own question when we click the bed (ServerPlayer.startSleepInBed): any monster whose box touches 8 either side
    // flat and 5 up and down of the bottom centre of the bed's HEAD block. headX/headZ are the block's corner coordinates, like a
    // BlockPos. it asks once, at the click: a monster that shows up later does not wake anybody
    public static boolean blocked(int headX, int headY, int headZ, List<Mob> mobs) {
        return blocked(headX, headY, headZ, mobs, 0);
    }

    // the same box, wider flat by `slack` on every side. before the bed is down we do not know which of the cells around us its
    // head will be in (up to two off), so the plan asks with the slack and the click asks without
    public static boolean blocked(int headX, int headY, int headZ, List<Mob> mobs, double slack) {
        double cx = headX + 0.5;
        double cz = headZ + 0.5;
        double reach = BOX_HORIZONTAL + slack;
        for (Mob m : mobs) {
            if (m.maxX() > cx - reach && m.minX() < cx + reach
                    && m.maxZ() > cz - reach && m.minZ() < cz + reach
                    && m.maxY() > headY - BOX_VERTICAL && m.minY() < headY + BOX_VERTICAL) {
                return true;
            }
        }
        return false;
    }

    // how many blocks higher the bed has to be so the box misses every monster that is coming: 0 when the spot is clear as it
    // stands, -1 when that is more than the cap or when going up would only drag a monster that is above us into the box.
    // `mobs` is everything within COMING_RANGE. the highest one below the top of the box decides, they all walk to the foot of
    // the pillar. a monster on a slope or a spider on the wall gets the margin. y is the cell we stand in, the bed goes in at
    // that height
    public static int blocksToRaise(int x, int y, int z, List<Mob> mobs, double margin, int cap) {
        if (!blocked(x, y, z, mobs, HEAD_REACH)) {
            return 0;
        }
        double highest = Double.NEGATIVE_INFINITY;
        for (Mob m : mobs) {
            // one already above the box is not one to climb over, it is one to not get closer to (checked below)
            if (m.minY() < y + BOX_VERTICAL) {
                highest = Math.max(highest, m.maxY());
            }
        }
        int n = (int) Math.ceil(highest + BOX_VERTICAL + margin - y);
        n = Math.max(n, 1);
        if (n > cap || blocked(x, y + n, z, mobs, HEAD_REACH)) {
            return -1;
        }
        return n;
    }

    // the bed's head is at most this many cells from the one we stand in (the foot is next to us, the head one more)
    public static final int HEAD_REACH = 2;

    // the column the pillar goes up in has to be open all the way: the cells over our head up to where we will stand, our own
    // two, and one more to jump into when the last block goes down. true when a roof is in the way
    public static boolean roofAt(int blocksToRaise, int openAbove) {
        return openAbove < blocksToRaise + 2;
    }

    // blocks the whole job eats: the pillar, a block under the foot (to click the bed onto) and one under the head, and a spare
    // for the one that lands wrong. the game has no support rule for a bed, but the item of a broken one drops at the head, and
    // without a block there it falls off the pillar
    public static int blocksNeeded(int blocksToRaise) {
        return Math.max(blocksToRaise, 0) + 3;
    }

    // ---- the budget of a night

    public static final class Budget {
        private long night = Long.MIN_VALUE;
        private int attempts;
        private double seconds;
        private double coolUntil = Double.NEGATIVE_INFINITY;
        private boolean slept;

        // a new night is a clean slate. the same night keeps what it spent
        public void roll(long nightId) {
            if (nightId != night) {
                night = nightId;
                attempts = 0;
                seconds = 0;
                coolUntil = Double.NEGATIVE_INFINITY;
                slept = false;
            }
        }

        public void began() {
            attempts++;
        }

        public void spend(double dt) {
            seconds += dt;
        }

        public void failed(double now, double cooldownSeconds) {
            coolUntil = now + cooldownSeconds;
        }

        // the night we slept in. the skip lands on the next day count, so by the time the trip says so the budget has rolled on to
        // the next night, and marking that one would skip tomorrow's sleep
        public void sleptOk(long nightId) {
            if (nightId == night) {
                slept = true;
            }
        }

        public int attempts() {
            return attempts;
        }

        public double seconds() {
            return seconds;
        }

        // done for tonight: it worked, or it ran out of tries or time
        public boolean spent(int maxAttempts, double maxSeconds) {
            return slept || attempts >= maxAttempts || seconds >= maxSeconds;
        }

        public boolean cooling(double now) {
            return now < coolUntil;
        }

        // the branch stays out of the way: spent for the night, or waiting out a cooldown
        public boolean quiet(double now, int maxAttempts, double maxSeconds) {
            return spent(maxAttempts, maxSeconds) || cooling(now);
        }
    }

    // ---- the stages and what mob defense does in each

    // RETURN is the walk back to an old bed of ours before the pick up
    public enum Stage {
        CRAFT, WALK, LIFT, BUILD, SLEEP, PICKUP, RETURN, DESCEND
    }

    // from the first block of the pillar to the bed being back in the bag, mobs are not ours to fight: a zombie that hits the
    // pillar must not turn into a fight or a 24 block run off the top of it. the walks to a bed and the way down are ordinary
    // travel, and low health always hands the wheel back (same line as the combat machine's bail)
    public static boolean standsDown(Stage stage, float health) {
        boolean up = stage == Stage.LIFT || stage == Stage.BUILD || stage == Stage.SLEEP || stage == Stage.PICKUP;
        return up && !shouldAbandon(health);
    }

    // in bed while the trip still thinks it is getting there (a stray click on the bed does that): the sleep is the whole point,
    // so the trip catches up with it instead of finishing the build around a sleeping player. the stages after the sleep
    // already know what being in bed means
    public static Stage stageWhenAsleep(boolean asleep, Stage stage) {
        if (!asleep) {
            return stage;
        }
        return switch (stage) {
            case CRAFT, WALK, LIFT, BUILD -> Stage.SLEEP;
            default -> stage;
        };
    }

    public static boolean shouldAbandon(float health) {
        return health <= CombatRules.FLEE_HEALTH;
    }

    // ---- what a refusal means

    public enum Refusal {
        NONE, MONSTERS, NOT_NOW, TOO_FAR, OBSTRUCTED, OCCUPIED, OTHER
    }

    // the overlay line the game puts up when a bed says no (block.minecraft.bed.*: "You may not rest now; there are monsters
    // nearby", "You can sleep only at night or during thunderstorms", "...the bed is too far away", "This bed is obstructed",
    // "This bed is occupied"). anything else bed-ish is OTHER, which is a failed try and not a reason to click again. a line
    // about something else is NONE: the overlay carries every action bar message there is, the server's "1/2 players sleeping"
    // included. English only, the overlay hands us the translated text (a client in another language falls back on the
    // monster check before the click and the click patience)
    public static Refusal refusal(String message) {
        if (message == null) {
            return Refusal.NONE;
        }
        String m = message.toLowerCase();
        if (m.contains("monsters nearby")) {
            return Refusal.MONSTERS;
        }
        if (m.contains("sleep only at night")) {
            return Refusal.NOT_NOW;
        }
        if (m.contains("too far away")) {
            return Refusal.TOO_FAR;
        }
        if (m.contains("obstructed")) {
            return Refusal.OBSTRUCTED;
        }
        if (m.contains("occupied")) {
            return Refusal.OCCUPIED;
        }
        // ("no amount of rest can pass this night" is the other players' percentage, also not a reason to click again)
        if (m.contains("you may not rest") || m.contains("this bed") || m.contains("the bed")) {
            return Refusal.OTHER;
        }
        return Refusal.NONE;
    }

    public enum Next {
        // pick the bed up and go higher
        HIGHER, GIVE_UP, CLOSER
    }

    // a refusal after we thought the spot was clear is a failed try: one more go from higher up for monsters, nothing else is
    // worth a second click. too far is a step closer, once
    public static Next afterRefusal(Refusal refusal, int lifts, int closers) {
        return switch (refusal) {
            case MONSTERS -> lifts < 1 ? Next.HIGHER : Next.GIVE_UP;
            case TOO_FAR -> closers < 1 ? Next.CLOSER : Next.GIVE_UP;
            default -> Next.GIVE_UP;
        };
    }

    // "only at night" with the morning already here is not the bed saying no, the night ended under us. daylight says so, and
    // so does the day count having moved on since the trip began (the backstop for a sky reading that lags the clock). at
    // real night it stays a failed try
    public static boolean nightIsOver(Refusal refusal, boolean bright, long nightThen, long nightNow) {
        return refusal == Refusal.NOT_NOW && (bright || nightNow > nightThen);
    }

    // build blocks the night pillar must leave alone. GATHER and IRON keep the stone kit's cobble. the portal pool build needs
    // 8 plus its fills to pick a layout and the gate never re-gathers once it is done, so the pillar cannot spend those
    public static final int PORTAL_BLOCK_RESERVE = 16;

    public static int blockReserve(GamerPhase phase, int stoneFloor) {
        return switch (phase) {
            case GATHER, IRON -> stoneFloor;
            case PORTAL -> PORTAL_BLOCK_RESERVE;
            default -> 0;
        };
    }

    // ---- clocks

    // seconds a stage may take before the try is written off. the pillar scales with its height
    public static double stageSeconds(Stage stage, int blocks) {
        return switch (stage) {
            case CRAFT -> 60;
            case WALK -> 45;
            case LIFT -> 8 + 4.0 * blocks;
            case BUILD -> 30;
            case SLEEP -> 0;
            case PICKUP -> 25;
            case RETURN -> 45;
            case DESCEND -> 10 + 3.0 * blocks;
        };
    }

    // a trip that is not finished but is no longer in the overworld was dragged away (a portal, mostly). that is a failed try
    // with a cooldown, or the walk back and the next try start in the same breath
    public static boolean droppedByDimension(boolean finished, boolean overworld) {
        return !finished && !overworld;
    }

    public static boolean timedOut(Stage stage, int blocks, double secondsInStage) {
        double limit = stageSeconds(stage, blocks);
        return limit > 0 && secondsInStage > limit;
    }

}
