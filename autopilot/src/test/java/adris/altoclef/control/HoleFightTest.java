package adris.altoclef.control;

import adris.altoclef.util.helpers.ApproachRules;
import adris.altoclef.util.helpers.ApproachRules.Plan;
import adris.altoclef.util.helpers.BackOffRules;
import adris.altoclef.util.helpers.StandCap;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the whole per tick decision of a kill task standing at the bottom of a one wide shaft with a mob on top of it, driven
// without a world: the back-off rule, the approach plan and the crit machine, with a cooldown that counts like an axe's.
// the glue below is a copy of what AbstractDoToEntityTask / PlayerExtraController do with those answers, so it is only as
// honest as the copy, but it is the same three pieces talking to each other
public class HoleFightTest {

    private static final double AXE_DELAY = 25;

    private static final class Sim {
        final boolean roof;
        final CritTiming crit = new CritTiming();
        int ticker;
        // 0 = on the ground, otherwise ticks since the jump press. rising for 6, falling for 5, landed on the 12th
        int air;
        boolean flying;
        int sinceSwing;
        int swings;

        Sim(boolean roof) {
            this.roof = roof;
        }

        double ticksToFull() {
            double scale = Math.min(1, (ticker + 0.5) / AXE_DELAY);
            return (1 - scale) * AXE_DELAY;
        }

        boolean onGround() {
            return !flying;
        }

        boolean falling() {
            return flying && air >= 7;
        }

        void tick(int tick) {
            ticker++;
            if (flying) {
                air++;
                if (air >= 12) {
                    flying = false;
                    air = 0;
                }
            }
            // feet to feet 1.8 with a mob on our head: "too close" by the raw number, but no side room and no way to back off
            boolean backOffOk = BackOffRules.roomToBackOff(false, !roof);
            boolean tooClose = backOffOk;
            boolean ready = onGround() || crit.inFlight(tick);
            Plan plan = ApproachRules.plan(false, true, true, ready, true, tooClose,
                    sinceSwing >= ApproachRules.WAIT_TICKS, ApproachRules.Policy.KILL);
            boolean swung = false;
            if (plan == Plan.SWING_NOW) {
                swung = swing();
            } else if (plan == Plan.INTERACT) {
                // the gate: a full swing, or a hop that wants its calls
                boolean wantsCrit = crit.inFlight(tick) || ticksToFull() <= CritTiming.LEAD_TICKS;
                if (ticksToFull() <= 0 || wantsCrit) {
                    swung = melee(tick);
                }
            }
            // (a WAIT is the few ticks of coming down from a hop that already swung. never a walk or a shuffle)
            assertTrue("plan " + plan + " at tick " + tick, plan == Plan.INTERACT || plan == Plan.SWING_NOW || plan == Plan.WAIT);
            sinceSwing = swung ? 0 : sinceSwing + 1;
        }

        private boolean swing() {
            if (ticksToFull() > 0) {
                return false;
            }
            ticker = 0;
            swings++;
            return true;
        }

        private boolean melee(int tick) {
            CritTiming.Sample s = new CritTiming.Sample();
            s.inReach = true;
            s.reachGap = 0.2;
            s.onGround = onGround();
            s.falling = falling();
            s.ticksToFull = ticksToFull();
            s.lowHeadroom = roof;
            s.meleeNear = 1;
            switch (crit.step(tick, s)) {
                case JUMP:
                    flying = true;
                    air = 0;
                    return false;
                case SWING:
                    return swing();
                case PLAIN:
                    // plainSwing: a full cooldown, and on the ground, falling, or no hop in flight to spend
                    return (onGround() || falling() || !crit.inFlight(tick)) && swing();
                default:
                    return false;
            }
        }
    }

    private static List<Integer> run(boolean roof, int ticks) {
        Sim sim = new Sim(roof);
        List<Integer> swings = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            int before = sim.swings;
            sim.tick(tick);
            if (sim.swings > before) swings.add(tick);
        }
        return swings;
    }

    private static void assertSwingsOnTheCooldown(List<Integer> swings, int ticks) {
        // the first one inside one axe cooldown (plus the few ticks a crit may hold it), then one per cooldown after that
        assertTrue("no swing at all", !swings.isEmpty());
        assertTrue("first swing at " + swings.get(0), swings.get(0) <= AXE_DELAY + CritTiming.MAX_READY_WAIT);
        for (int i = 1; i < swings.size(); i++) {
            int gap = swings.get(i) - swings.get(i - 1);
            assertTrue("swing gap " + gap, gap >= AXE_DELAY && gap <= AXE_DELAY + CritTiming.MAX_READY_WAIT);
        }
        assertTrue("only " + swings.size() + " swings in " + ticks + " ticks", swings.size() >= ticks / (int) (AXE_DELAY + CritTiming.MAX_READY_WAIT));
    }

    @Test
    public void underARoofTheSwingGoesOutTheTickTheAxeIsReadyAndEveryCooldownAfter() {
        List<Integer> swings = run(true, 300);
        assertSwingsOnTheCooldown(swings, 300);
        // no hop to wait on: exactly on the cooldown
        assertEquals(24, (int) swings.get(0));
        for (int i = 1; i < swings.size(); i++) {
            assertEquals(25, swings.get(i) - swings.get(i - 1));
        }
    }

    @Test
    public void inAnOpenTopedShaftTheHopStillEndsInASwingEveryCooldown() {
        assertSwingsOnTheCooldown(run(false, 300), 300);
    }

    @Test
    public void withNoRoomTheTooCloseBackOffIsNeverStartedWhateverTheCooldownSays() {
        // the rule the task asks before it will start a run-away at all
        assertEquals(false, BackOffRules.roomToBackOff(false, true));
        assertEquals(false, BackOffRules.roomToBackOff(false, false));
    }

    // a path nobody will drop (not ours, or the watch is not there): the plan says wait while it is alive, and the cap is what
    // gets the swing out, then keeps getting it out every REPEAT_TICKS instead of once per five seconds
    @Test
    public void aPathThatNeverLetsGoStillGetsASwingAtTheCapAndThenSteadily() {
        StandCap cap = new StandCap();
        int ticker = 0;
        boolean swungLast = false;
        List<Integer> swings = new ArrayList<>();
        for (int tick = 0; tick < 400; tick++) {
            ticker++;
            cap.tick(true, swungLast);
            swungLast = false;
            Plan plan = ApproachRules.plan(false, true, true, true, false, false, cap.waitedLong(), ApproachRules.Policy.KILL);
            if (plan == Plan.SWING_NOW) {
                cap.forced();
                if ((AXE_DELAY - ticker - 0.5) <= 0) {
                    ticker = 0;
                    swungLast = true;
                    swings.add(tick);
                }
            } else {
                assertEquals("tick " + tick, Plan.WAIT, plan);
            }
        }
        assertTrue("no forced swing", !swings.isEmpty());
        assertTrue("first at " + swings.get(0), swings.get(0) <= ApproachRules.WAIT_TICKS + 1);
        for (int i = 1; i < swings.size(); i++) {
            int gap = swings.get(i) - swings.get(i - 1);
            assertTrue("gap " + gap, gap <= StandCap.REPEAT_TICKS + 1);
        }
    }

    // the run-away we started owns the keys and goes nowhere: the watch drops it ten ticks in, and the swing goes out on the
    // cooldown instead of waiting for the cap
    @Test
    public void aBackOffThatGoesNowhereIsDroppedAndTheSwingStillComesOnTheCooldown() {
        BackOffRules.Watch watch = new BackOffRules.Watch();
        boolean goalLive = true;
        int ticker = 0;
        int firstSwing = -1;
        int dropped = -1;
        for (int tick = 0; tick < 60 && firstSwing < 0; tick++) {
            ticker++;
            if (goalLive && watch.stuck(tick, 5, 5)) {
                goalLive = false;
                dropped = tick;
            }
            Plan plan = ApproachRules.plan(false, true, true, true, !goalLive, false, false, ApproachRules.Policy.KILL);
            if (plan == Plan.INTERACT && (AXE_DELAY - ticker - 0.5) <= 0) {
                firstSwing = tick;
            }
        }
        assertEquals(BackOffRules.STUCK_TICKS, dropped);
        assertEquals(24, firstSwing);
    }
}
