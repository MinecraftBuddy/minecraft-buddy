package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the rule the station verdict has to keep, over the same generated rooms the golden uses (StationVerdictEquivalenceTest.Room):
//   1. if the planner says a station kind is held, the container task asked right now walks to one of ours (or waits for a pickup it
//      vetoes on), and never places or crafts a new one. the plan budgets nothing for a station it holds
//   2. the planner's answer does not flip while we stand still
// the task is a model of DoStuffInContainerTask.choose (the real one needs a client), the same candidates the golden builds. rooms are
// seen the way the live game sees them: the registry synced first (a pickup nobody drives goes back to standing), and a pickup still in
// flight is driven this tick, which is what the pickup phase does ahead of any craft
//
// ONE named exception, StationContradictionsTest (G): a table the planner counted from inside and now lies at 48 < d <= 50 is only
// walked to by a task that already heads for it (previous target, the task's HOLD). a fresh craft there makes one. the rule is checked
// for that case with the previous target, and the exception is counted (pinned to this seed) so it cannot quietly grow
//
// out of the model on purpose: a split smelt's load (skipsBusy) refuses a busy furnace and makes another next to the one cooking, while
// the planner holds the busy one (StationContradictionsTest D, by design), and the pickup veto only ever shows up as "driven this tick"
// here (the C test walks the undriven window)
public class StationPropertyTest {
    private static final long NOW = 6000;
    private static final int ROOMS = 6000;

    @Rule
    public Timeout timeout = Timeout.seconds(180);

    private static boolean held(StationVerdict v, Kind kind) {
        return switch (kind) {
            case TABLE -> v.table().heldForPlanner();
            case FURNACE -> v.furnace().heldForPlanner();
            case SMOKER -> v.smoker().heldForPlanner();
        };
    }

    private static double dist(Bench b, double[] p) {
        return WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, p[0], p[1], p[2]);
    }

    // what DoStuffInContainerTask.choose feeds StationChoice for this kind, from where we stand, with this previous target
    private static StationChoice.Pick<String> task(StationVerdictEquivalenceTest.Room room, Kind kind, double[] p, String previous) {
        RunState state = room.state;
        String dim = room.dimension;
        List<StationChoice.Candidate<String>> seen = new ArrayList<>();
        Bench near = Workbenches.nearestStanding(state, kind, dim, p[0], p[1], p[2], WorkbenchRules.NEAR);
        Bench far = Workbenches.nearestStanding(state, kind, dim, p[0], p[1], p[2], WorkbenchRules.FORGET_DISTANCE);
        if (near != null) {
            seen.add(new StationChoice.Candidate<>("b" + state.benches.indexOf(near), dist(near, p), StationChoice.Role.OURS));
        }
        if (far != null && far != near && room.usable.getOrDefault(StationVerdictEquivalenceTest.Room.key(kind, far.pos), true) && !far.pickupFailed) {
            seen.add(new StationChoice.Candidate<>("b" + state.benches.indexOf(far), dist(far, p), StationChoice.Role.OURS));
        }
        if (previous != null) {
            boolean listed = seen.stream().anyMatch(c -> c.key().equals(previous));
            Bench b = state.benches.get(Integer.parseInt(previous.substring(1)));
            if (!listed && b.kind == kind && b.dimension.equals(dim) && Workbenches.standing(b, kind, dim)) {
                seen.add(new StationChoice.Candidate<>(previous, dist(b, p), StationChoice.Role.OURS));
            }
        }
        boolean inBag = room.bag[kind.ordinal()] > 0;
        boolean canMakeNow = StationChoice.canMakeFrom(kind, room.cobble - room.cobbleOwed, room.logs, room.planks, room.furnaceItems);
        return StationChoice.decide(seen, previous, inBag, true, canMakeNow, WorkbenchRules.NEAR, StationChoice.walkBackReach(kind));
    }

    // the table the ring exception is about: standing, ours, 48 < d <= 50 from here
    private static Bench ringTable(StationVerdictEquivalenceTest.Room room, double[] p) {
        for (Bench b : room.state.benches) {
            double d = dist(b, p);
            if (Workbenches.standing(b, Kind.TABLE, room.dimension) && d > StationChoice.WALK_BACK && d <= StationChoice.WALK_BACK + StationChoice.HOLD) {
                return b;
            }
        }
        return null;
    }

    // a room the way the live game has it by the time a craft asks: synced, and a pickup in flight being driven
    private static void live(StationVerdictEquivalenceTest.Room room) {
        Workbenches.sync(room.state, NOW);
        for (Bench b : room.state.benches) {
            if (b.state == Bench.State.PICKING_UP) {
                b.drivenTick = NOW;
            }
        }
    }

    @Test
    public void heldMeansTheTaskWalksToOneOfOurs() {
        Random r = new Random(0x9E3779B97L);
        PrintStream real = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        int heldLooks = 0;
        int walked = 0;
        int vetoed = 0;
        int ring = 0;
        StringBuilder bad = new StringBuilder();
        int failures = 0;
        try {
            for (int i = 0; i < ROOMS; i++) {
                StationVerdictEquivalenceTest.Room room = StationVerdictEquivalenceTest.Room.generate(r);
                live(room);
                for (double[] p : room.looks) {
                    boolean backBefore = room.latches.tableBack;
                    StationVerdict v = StationVerdict.of(room.state, room.dimension, p[0], p[1], p[2], room.world(), room.latches);
                    for (Kind kind : Kind.values()) {
                        if (!held(v, kind)) {
                            continue;
                        }
                        heldLooks++;
                        if (WorkbenchRules.placeVetoed(room.state.benches, kind, NOW)) {
                            vetoed++;
                            continue;
                        }
                        StationChoice.Pick<String> pick = task(room, kind, p, null);
                        if (pick.walks()) {
                            walked++;
                            continue;
                        }
                        Bench ringBench = kind == Kind.TABLE && backBefore ? ringTable(room, p) : null;
                        if (ringBench != null) {
                            // the named exception: a task already heading for it keeps walking
                            StationChoice.Pick<String> kept = task(room, kind, p, "b" + room.state.benches.indexOf(ringBench));
                            if (kept.walks()) {
                                ring++;
                                continue;
                            }
                        }
                        failures++;
                        if (bad.length() < 3000) {
                            bad.append("room ").append(i).append(' ').append(kind).append(" held at ").append(java.util.Arrays.toString(p)).append(" but the task does ")
                                    .append(pick.use()).append('\n').append("  ").append(room.note()).append('\n');
                        }
                    }
                }
            }
        } finally {
            System.setOut(real);
        }
        // the generator has to reach all of it, or the property says nothing
        assertTrue("held looks " + heldLooks, heldLooks > 3000);
        assertTrue("walked " + walked, walked > 2000);
        assertTrue("vetoed " + vetoed, vetoed > 50);
        // exactly what this seed reaches today: a change that widens the exception moves it
        assertEquals("ring", 18, ring);
        assertEquals(failures + " held looks the task does not walk to:\n" + bad, 0, failures);
    }

    @Test
    public void theAnswerDoesNotFlipWhileWeStandStill() {
        Random r = new Random(0x9E3779B97L);
        PrintStream real = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        int checked = 0;
        try {
            for (int i = 0; i < ROOMS; i++) {
                StationVerdictEquivalenceTest.Room room = StationVerdictEquivalenceTest.Room.generate(r);
                live(room);
                for (double[] p : room.looks) {
                    StationVerdict first = StationVerdict.of(room.state, room.dimension, p[0], p[1], p[2], room.world(), room.latches);
                    for (int again = 0; again < 3; again++) {
                        StationVerdict next = StationVerdict.of(room.state, room.dimension, p[0], p[1], p[2], room.world(), room.latches);
                        for (Kind kind : Kind.values()) {
                            assertEquals("room " + i + " " + kind + " flipped standing at " + java.util.Arrays.toString(p) + "\n" + room.note(),
                                    held(first, kind), held(next, kind));
                            checked++;
                        }
                    }
                }
            }
        } finally {
            System.setOut(real);
        }
        assertTrue(checked > 10000);
    }
}
