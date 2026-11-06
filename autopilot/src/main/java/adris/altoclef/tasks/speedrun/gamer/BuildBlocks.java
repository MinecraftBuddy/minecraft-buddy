package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.tasks.NetherRestock;

import java.util.function.Consumer;

// the one rule for "go get build blocks". it used to be count < 32 to start and count >= 32 to stop, the same number, so
// a walk that burned two pillars worth sent the bot straight back out to mine more, forever. now a need starts under a low
// mark and runs to the target (the end gate already did this, 40 and 64). pure, the state it keeps lives in RunState
// (transient, like the food refill latch) and the phases wipe it on entry
public final class BuildBlocks {
    // a phase entry gets this many fresh refills of the base need, the next ones are refused. the loop breaker for a walk that
    // keeps burning the whole stack
    public static final int MAX_REFILLS = 3;
    // the stock-up on top of a full base is a use of the wait and not a need, so it gets one trip per entry and never eats
    // into the base's three
    public static final int MAX_EXTRA_REFILLS = 1;

    // no state, no cap: what the short forms of the planners use
    public static final BuildBlocks NONE = new BuildBlocks(0, false, false, () -> { });

    // the target of a refill under way, 0 when none
    private final int refill;
    private final boolean capped;
    private final boolean extraCapped;
    private final Runnable refused;

    private BuildBlocks(int refill, boolean capped, boolean extraCapped, Runnable refused) {
        this.refill = refill;
        this.capped = capped;
        this.extraCapped = extraCapped;
        this.refused = refused;
    }

    // the band on its own: under low it asks for the target, anywhere from low up it asks for nothing
    public static int need(int count, int low, int target) {
        return count < low ? target : 0;
    }

    // this tick's view. a refill whose target we reached is over, so it is let go here, and a ping pong of the same number
    // can never keep it alive. `log` hears the one line when the cap turns a refill down
    public static BuildBlocks of(GamerFacts f, RunState state, Consumer<String> log) {
        int held = f.buildBlocks();
        if (state.buildRefill > 0 && held >= state.buildRefill) {
            state.buildRefill = 0;
        }
        Runnable refused = () -> {
            if (!state.buildCapSaid) {
                state.buildCapSaid = true;
                log.accept("build blocks refilled " + MAX_REFILLS + " times this phase, keeping what we have (" + held + ")");
            }
        };
        return new BuildBlocks(state.buildRefill, state.buildRefills >= MAX_REFILLS, state.buildExtraRefills >= MAX_EXTRA_REFILLS, refused);
    }

    // what to collect up to, 0 when nothing. a refill that is under way for this very target keeps going until it gets there
    // (the band alone would drop it the moment the count crossed low, and the bot would stop at 8 of 32). a fresh one is
    // refused once the cap is used up
    public int want(int count, int low, int target) {
        return ask(count, low, target, capped, refused);
    }

    // the stock-up on top of the base: same band, but its own one trip, and a refusal is not worth a line
    public int wantExtra(int count, int low, int target) {
        return ask(count, low, target, extraCapped, () -> { });
    }

    private int ask(int count, int low, int target, boolean noFresh, Runnable onRefused) {
        if (refill == target && count < target) {
            return target;
        }
        int need = need(count, low, target);
        if (need > 0 && noFresh) {
            onRefused.run();
            return 0;
        }
        return need;
    }

    // KitRunner: a build blocks need is the head. the first tick of a fresh one is a refill, the rest of it is the same refill.
    // a latch left over for another target (the stock-up we went too deep for) is not this one, so this one is fresh
    public static void started(RunState state, int target, boolean extra) {
        if (state.buildRefill != target) {
            if (extra) {
                state.buildExtraRefills++;
            } else {
                state.buildRefills++;
            }
        }
        state.buildRefill = target;
    }

    // the user can keep more if they like, never less. the arrival dig goes well past the entry stack, a floor on the entry number
    // would throw the dig straight back out. a hand picked entry over it still counts, the gate would mine that and lose it too
    public static int reserveFor(int user, int entry) {
        return Math.max(user, Math.max(entry, NetherRestock.ARRIVAL_TARGET));
    }

    // a phase entry starts from nothing
    public static void newEntry(RunState state) {
        state.buildRefill = 0;
        state.buildRefills = 0;
        state.buildExtraRefills = 0;
        state.buildCapSaid = false;
    }
}
