package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import net.minecraft.world.item.Items;

import java.util.Optional;

// the "not enough eyes for the portal" rules of LOCATE, ROOM and OPEN. pure over facts + state so they are testable
public final class StrongholdRules {
    public static final int FRAMES = 12;
    // one eye can be in the air or on the floor while we count, never more (we pick up before the next throw)
    static final int IN_FLIGHT = 1;
    // 5 s: framesFilled lags the inventory by a tick or two while eyes go in, do not read that as starving
    static final long STARVED_GRACE_TICKS = 100;

    // eyes we could still craft right now: one pearl and one powder each, a rod makes two powder
    public static int craftableEyes(GamerFacts facts) {
        int powder = facts.count(Items.BLAZE_POWDER) + 2 * facts.count(Items.BLAZE_ROD);
        return Math.min(facts.count(Items.ENDER_PEARL), powder);
    }

    // below this many eyes (held + in frames + craftable + the one in flight) the stronghold hunt is hopeless. above it
    // we carry on: every frame is pre-filled 10% of the time (28% that none is, 1.2 on average) so 11 eyes still
    // opens the portal more often than not, and the nether trip back costs up to 40 minutes. OPEN knows the real
    // filled count and makes the call there (regressForOpen)
    static final int HOPELESS_TOTAL = 9;

    // LOCATE and ROOM: only when it is hopeless
    public static Optional<GamerPhase> regressForEyes(GamerFacts facts, RunState state) {
        if (state.endPortalOpened || state.netherRevisits >= 1) {
            return Optional.empty();
        }
        int total = facts.count(Items.ENDER_EYE) + state.framesFilled + craftableEyes(facts) + IN_FLIGHT;
        return total < HOPELESS_TOTAL ? Optional.of(GamerPhase.NETHER) : Optional.empty();
    }

    // the numbers behind a regressForEyes, for the log
    public static String hopelessLine(GamerFacts facts, RunState state) {
        int held = facts.count(Items.ENDER_EYE);
        int craftable = craftableEyes(facts);
        return "eyes: hopeless, going back to the nether (held " + held + " + frames " + state.framesFilled + " + craftable "
                + craftable + " + flight " + IN_FLIGHT + " = " + (held + state.framesFilled + craftable + IN_FLIGHT)
                + " < " + HOPELESS_TOTAL + ")";
    }

    // OPEN: short of eyes for the frames that are still empty
    public static boolean starved(GamerFacts facts, RunState state) {
        if (state.endPortalOpened) {
            return false;
        }
        int need = FRAMES - state.framesFilled;
        return need > 0 && facts.count(Items.ENDER_EYE) + craftableEyes(facts) < need;
    }

    // OPEN regress: only after the shortage has stood for a few seconds (openNoEyesSince is kept by the handler tick)
    public static Optional<GamerPhase> regressForOpen(GamerFacts facts, RunState state) {
        if (state.netherRevisits >= 1 || state.openNoEyesSince == 0 || !starved(facts, state) || OpenTopUp.running(state)) {
            return Optional.empty();
        }
        return facts.gameTime() - state.openNoEyesSince >= STARVED_GRACE_TICKS ? Optional.of(GamerPhase.NETHER) : Optional.empty();
    }

    // true when the shortage stood long enough and a nether trip is no longer allowed: the handler fails the phase
    public static boolean starvedForGood(GamerFacts facts, RunState state) {
        return state.netherRevisits >= 1 && state.openNoEyesSince != 0 && starved(facts, state) && !OpenTopUp.running(state)
                && facts.gameTime() - state.openNoEyesSince >= STARVED_GRACE_TICKS;
    }

    // ROOM budget ran out: a window that really reached new chunks (standing near them, not just the 90 s per chunk timer
    // running out) is a slow spiral, not a stuck one, and its retry gets a fresh budget. only the budget verdict counts (a
    // stall means the last 180 s did nothing, whatever came before it), not the handler's fails ("searched every chunk", "not in the overworld") and a spent spiral are real endings
    public static boolean roomStillMoving(int chunksReachedThisWindow, boolean exhausted, String reason) {
        boolean watchdog = reason != null && reason.startsWith("took longer than");
        return watchdog && !exhausted && chunksReachedThisWindow > 0;
    }
    private StrongholdRules() {
    }

    // the free "still moving" retries a room window gets, a spiral that crawls forever is not a long legit search
    public static final int MAX_MOVING_RETRIES = 2;

    public static boolean mayRetryMoving(int movingRetries) {
        return movingRetries < MAX_MOVING_RETRIES;
    }
}
