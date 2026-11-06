package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// the golden for the stations: a few thousand seeded rooms (benches of every kind, state and distance, the bag, the plan, the
// latches from last look) and every answer the gamer gives about the tables, furnaces and smokers in each. the file was recorded
// before the station verdict existed and it only moves when a behaviour round says it should (the commit says which answers, and in
// which rooms). same shape as WoodBudgetEquivalenceTest.
//
// what it reaches: the planner's three held flags and their latches over three looks, heldNear / plannerWalksBack / standing per
// kind, StationChoice.decide fed the candidates DoStuffInContainerTask.choose would build (a MODEL of choose, the real one needs a
// client), WorkbenchRules.decide for every bench (the call, the reason, what it did to the bench), neededSoon, comingBack, phaseMayEnd,
// portalHold, anchorOf, doneUsing, holdFar, and what KitPlanner and CookGate make of the flags.
// what it can NOT reach, and so does not pin: DoStuffInContainerTask.choose (the choice above is a model of it, if choose changes
// this test will not notice), Workbenches.idle and Workbenches.inUse (the task tree and the menu are generated, idle is just a coin
// per bench), holdsStuff's container tracker read, Workbenches.run itself, and the container tasks' own use of the pick.
// every room is built fresh and decide() runs on a fresh copy of each bench, so rooms can not leak into each other.
// STATION_GOLDEN_WRITE=<dir> rewrites the file into <dir> instead of checking it (only ever from code that is known to be right).
// STATION_GOLDEN_FULL=<dir> dumps every answer in full, for diffing two versions state by state
public class StationVerdictEquivalenceTest {
    private static final int STATES = 4000;
    private static final int FULL_LINES = 200;
    private static final String FILE = "station-golden.txt";
    private static final String RESOURCES = "/adris/altoclef/tasks/speedrun/gamer/";

    static {
        ItemFixture.boot();
    }

    // where the distances sit: the thresholds are 15.75 (table return), 20 / 21 (NEAR), 48 / 50 (walk back, + HOLD), 128 (forget)
    private static final int[] AXIS = {0, 1, 5, 10, 15, 16, 17, 19, 20, 21, 22, 23, 30, 40, 46, 47, 48, 49, 50, 51, 52, 60, 100, 127, 128, 129, 140};
    private static final double[] SHELL = {15.75, 20, 21, 48, 128};
    private static final String[] NEEDS = {"log", "planks", "cobblestone", "coal", "iron_ingot", "food", "cook_in_smoker", "cook_in_furnace",
            "stone_pickaxe", "wooden_pickaxe", "bucket", "build_blocks", "flint", "wool", "furnace", "smoker"};
    private static final long NOW = 6000;

    @Rule
    public Timeout timeout = Timeout.seconds(180);

    @After
    public void clearFuel() {
        FuelPolicy.clear();
    }

    @Test
    public void everyStationAnswerIsStillTheSameAsItWas() throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Random r = new Random(0x57A71095L);
        PrintStream real = System.out;
        // Workbenches.record and friends log to the console, four thousand rooms of that is noise
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            for (int i = 0; i < STATES; i++) {
                Room room = Room.generate(r);
                lines.add(answers(room));
                notes.add(room.note());
            }
        } finally {
            System.setOut(real);
        }
        int[] tally = new int[8];
        for (String line : lines) {
            tally(line, tally);
        }
        // a tally of what the generator reaches, so a generator that quietly stops varying something fails loudly
        assertTrue("never held a table: " + tally[0], tally[0] > 300);
        assertTrue("never held a furnace: " + tally[1], tally[1] > 300);
        assertTrue("never held a smoker: " + tally[2], tally[2] > 200);
        assertTrue("never walked back to one: " + tally[3], tally[3] > 100);
        assertTrue("never picked one up: " + tally[4], tally[4] > 200);
        assertTrue("never forgot one: " + tally[5], tally[5] > 100);
        assertTrue("never saw the phase end: " + tally[6], tally[6] > 300);
        assertTrue("never walked to a world one: " + tally[7], tally[7] > 100);
        check(lines, notes);
    }

    private static void tally(String line, int[] t) {
        int at = line.indexOf(";flags2=");
        String block = line.substring(at + 8, at + 11);
        for (int k = 0; k < 3; k++) {
            if (block.charAt(k) == 'T') {
                t[k]++;
            }
        }
        if (line.contains(";wb=") && line.substring(line.indexOf(";wb="), line.indexOf(";wb=") + 7).contains("T")) {
            t[3]++;
        }
        if (line.contains("PICK_UP")) {
            t[4]++;
        }
        if (line.contains("FORGET_")) {
            t[5]++;
        }
        if (line.contains(";mayEnd=T")) {
            t[6]++;
        }
        if (line.contains("WORLD:w")) {
            t[7]++;
        }
    }

    // ---- the golden file

    private static void check(List<String> lines, List<String> notes) throws IOException {
        String full = System.getenv("STATION_GOLDEN_FULL");
        if (full != null && !full.isEmpty()) {
            List<String> dump = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                dump.add(lines.get(i) + "#" + notes.get(i));
            }
            Files.write(Path.of(full, "station-full.txt"), dump, StandardCharsets.UTF_8);
        }
        String write = System.getenv("STATION_GOLDEN_WRITE");
        if (write != null && !write.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                out.add(i < FULL_LINES ? hash(lines.get(i)) + "|" + lines.get(i) : hash(lines.get(i)));
            }
            Files.write(Path.of(write, FILE), out, StandardCharsets.UTF_8);
            return;
        }
        List<String> golden = readGolden();
        assertEquals("rooms in the golden", STATES, golden.size());
        int bad = 0;
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < STATES; i++) {
            String[] parts = golden.get(i).split("\\|", 2);
            if (parts[0].equals(hash(lines.get(i)))) {
                continue;
            }
            bad++;
            if (why.length() < 6000) {
                why.append("room ").append(i).append(" moved\n");
                if (parts.length == 2) {
                    why.append("  was ").append(parts[1]).append("\n  now ").append(lines.get(i)).append("\n");
                }
            }
        }
        assertEquals(FILE + ": " + bad + " of " + STATES + " rooms moved\n" + why, 0, bad);
    }

    private static List<String> readGolden() throws IOException {
        try (InputStream in = StationVerdictEquivalenceTest.class.getResourceAsStream(RESOURCES + FILE)) {
            assertNotNull("missing " + FILE, in);
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }

    private static String hash(String line) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                // two hex digits a byte, a colon between: a run of digits or letters in a hash could spell what the
                // repository's privacy check refuses (a number, a name), and the line number is the line's place
                if (i > 0) sb.append(':');
                sb.append(String.format("%02x", sum[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- one room

    // (package private: StationPropertyTest builds its rooms the same way)
    static final class Room {
        final String dimension;
        final double[][] looks = new double[3][];
        final RunState state = new RunState();
        // what each bench's block and walk back say, by kind + position
        final Map<String, Boolean> stands = new HashMap<>();
        final Map<String, Boolean> usable = new HashMap<>();
        final int[] bag = new int[Kind.values().length];
        int cobble;
        int cobbleOwed;
        int logs;
        int planks;
        int furnaceItems;
        final StationLatches latches = new StationLatches();
        // each registry bench's seed for its clocks, so a copy for decide() is the same bench again
        final List<Long> clockSeeds = new ArrayList<>();
        final List<String> plan = new ArrayList<>();
        boolean holdsPick;
        boolean holdsStonePick;
        // generated: what the live world would say
        long seed;
        // the latches the room started with, as drawn (the note prints them, the latches themselves move over the looks)
        String latches0 = "";

        static Room generate(Random r) {
            Room room = new Room(r.nextInt(8) == 0 ? "NETHER" : "OVERWORLD");
            room.seed = r.nextLong();
            room.fill(r);
            return room;
        }

        Room(String dimension) {
            this.dimension = dimension;
        }

        static String key(Kind kind, RunState.Pos p) {
            return kind + ":" + p.x + "," + p.y + "," + p.z;
        }

        void fill(Random r) {
            double px = r.nextInt(200) - 100 + 0.5;
            double py = 40 + r.nextInt(60) + 0.5;
            double pz = r.nextInt(200) - 100 + 0.5;
            looks[0] = new double[]{px, py, pz};
            for (int i = 1; i < 3; i++) {
                int step = r.nextInt(3) == 0 ? 0 : 1;
                looks[i] = new double[]{looks[i - 1][0] + step * (r.nextInt(17) - 8), looks[i - 1][1] + step * (r.nextInt(5) - 2),
                        looks[i - 1][2] + step * (r.nextInt(17) - 8)};
            }
            int count = r.nextInt(5);
            Set<String> used = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Kind kind = Kind.values()[r.nextInt(3)];
                RunState.Pos pos = place(r, px, py, pz);
                if (!used.add(key(kind, pos))) {
                    continue;
                }
                String dim = r.nextInt(7) == 0 ? (dimension.equals("NETHER") ? "OVERWORLD" : "NETHER") : dimension;
                Workbenches.record(state, kind, pos, dim, 4000 + r.nextInt(2000));
            }
            for (Bench b : state.benches) {
                long s = r.nextLong();
                clockSeeds.add(s);
                clocks(b, new Random(s));
                stands.put(key(b.kind, b.pos), r.nextInt(10) != 0);
                usable.put(key(b.kind, b.pos), r.nextInt(4) != 0);
                if (b.kind != Kind.TABLE && r.nextInt(4) == 0) {
                    FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(b.pos, b.dimension, b.kind == Kind.SMOKER ? "smoker" : "furnace",
                            "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
                }
            }
            for (Kind kind : Kind.values()) {
                bag[kind.ordinal()] = r.nextInt(3) == 0 ? 1 + r.nextInt(2) : 0;
            }
            cobble = r.nextInt(3) == 0 ? 0 : r.nextInt(30);
            cobbleOwed = r.nextInt(3) == 0 ? r.nextInt(12) : 0;
            logs = r.nextInt(3) == 0 ? 0 : r.nextInt(12);
            planks = r.nextInt(3) == 0 ? 0 : r.nextInt(20);
            furnaceItems = bag[Kind.FURNACE.ordinal()];
            int n = r.nextInt(7);
            for (int i = 0; i < n; i++) {
                plan.add(NEEDS[r.nextInt(NEEDS.length)]);
            }
            holdsPick = r.nextBoolean();
            holdsStonePick = holdsPick && r.nextBoolean();
            // the previous look's latches
            latches.tableFar = r.nextInt(3) == 0;
            int f = r.nextInt(3);
            latches.furnaceHeld = f == 0 ? null : Boolean.valueOf(f == 1);
            int s = r.nextInt(3);
            latches.smokerHeld = s == 0 ? null : Boolean.valueOf(s == 1);
            latches0 = "far" + latches.tableFar + "/f" + f + "/s" + s;
        }

        // a block at a distance from us that sits on a threshold more often than chance would put it
        private static RunState.Pos place(Random r, double px, double py, double pz) {
            int mode = r.nextInt(10);
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            if (mode < 4) {
                int d = AXIS[r.nextInt(AXIS.length)] * (r.nextBoolean() ? 1 : -1);
                // our middle is at .5, a block d away along one axis is exactly d from it
                return switch (r.nextInt(3)) {
                    case 0 -> new RunState.Pos(bx + d, by, bz);
                    case 1 -> new RunState.Pos(bx, by + d, bz);
                    default -> new RunState.Pos(bx, by, bz + d);
                };
            }
            if (mode < 7) {
                double d = SHELL[r.nextInt(SHELL.length)] + (r.nextDouble() - 0.5) * 1.6;
                double a = r.nextDouble() * Math.PI * 2;
                double e = (r.nextDouble() - 0.5) * Math.PI;
                return new RunState.Pos((int) Math.round(px + d * Math.cos(e) * Math.cos(a) - 0.5), (int) Math.round(py + d * Math.sin(e) - 0.5),
                        (int) Math.round(pz + d * Math.cos(e) * Math.sin(a) - 0.5));
            }
            return new RunState.Pos(bx + r.nextInt(121) - 60, by + r.nextInt(81) - 40, bz + r.nextInt(121) - 60);
        }

        // the bench's own clocks, the same every time for the same seed
        static void clocks(Bench b, Random r) {
            int st = r.nextInt(20);
            b.state = st < 12 ? Bench.State.STANDING : st < 16 ? Bench.State.BUSY : Bench.State.PICKING_UP;
            b.lastUsedTick = r.nextInt(3) == 0 ? WorkbenchRules.NEVER : NOW - r.nextInt(120);
            b.outsideSince = r.nextInt(3) == 0 ? WorkbenchRules.NEVER : NOW - r.nextInt(220);
            b.otherDimensionSince = r.nextInt(4) == 0 ? NOW - r.nextInt(60) : WorkbenchRules.NEVER;
            b.tries = r.nextInt(3);
            b.retryAt = r.nextInt(5) == 0 ? NOW + r.nextInt(100) - 30 : WorkbenchRules.NEVER;
            b.pickupStart = b.state == Bench.State.PICKING_UP ? NOW - r.nextInt(300) : WorkbenchRules.NEVER;
            b.broken = b.state == Bench.State.PICKING_UP && r.nextInt(4) == 0;
            b.drivenTick = b.state == Bench.State.PICKING_UP ? NOW - r.nextInt(150) : WorkbenchRules.NEVER;
            b.ranTicks = r.nextInt(5) == 0 ? 400 + r.nextInt(900) : r.nextInt(300);
            b.pickupFailed = b.state == Bench.State.STANDING && r.nextInt(8) == 0;
            b.leftSinceFail = r.nextBoolean();
            b.givenUp = b.state == Bench.State.BUSY && r.nextInt(5) == 0;
            b.doneFor = r.nextInt(3) == 0 ? b.lastUsedTick : WorkbenchRules.NEVER;
            b.leaveNow = r.nextInt(8) == 0;
            b.redecide = r.nextInt(8) == 0;
            b.comingBack = b.kind != Kind.TABLE && r.nextInt(6) == 0 ? "to cook" : null;
            b.comingBackLostSince = b.comingBack != null && r.nextInt(3) == 0 ? NOW - r.nextInt(100) : WorkbenchRules.NEVER;
            b.anchorLostSince = r.nextInt(6) == 0 ? NOW - r.nextInt(100) : WorkbenchRules.NEVER;
            b.farHeldLogged = r.nextBoolean();
        }

        StationVerdict.World world() {
            return new StationVerdict.World() {
                @Override
                public boolean stands(Kind kind, RunState.Pos pos) {
                    return stands.getOrDefault(key(kind, pos), true);
                }

                @Override
                public boolean inBag(Kind kind) {
                    return bag[kind.ordinal()] > 0;
                }

                @Override
                public boolean canMake(Kind kind) {
                    return StationChoice.canMakeFrom(kind, cobble - cobbleOwed, logs, planks, furnaceItems);
                }

                @Override
                public boolean walkUsable(Kind kind, Bench b) {
                    return usable.getOrDefault(key(kind, b.pos), true);
                }
            };
        }

        // for the full dump only (STATION_GOLDEN_FULL), never hashed: enough to tell which rooms a behaviour round should move. each
        // bench with its straight line from the three looks and its job, then the start-of-room latches
        String note() {
            StringBuilder where = new StringBuilder();
            for (Bench b : state.benches) {
                where.append(b.kind.word().charAt(0)).append(b.dimension.charAt(0)).append(b.state.name().charAt(0));
                for (double[] look : looks) {
                    where.append('/').append(Math.round(adris.altoclef.util.helpers.WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, look[0], look[1], look[2]) * 100) / 100.0);
                }
                where.append(FurnaceJobs.isBusy(state, b.pos, b.dimension) ? "/job" : "").append(' ');
            }
            return "dim=" + dimension + ",benches=" + state.benches + ",bag=" + java.util.Arrays.toString(bag) + ",cobble=" + cobble + "-" + cobbleOwed
                    + ",logs=" + logs + ",planks=" + planks + ",plan=" + plan + ",at=" + where
                    + ",latch0=" + latches0;
        }
    }

    // ---- every answer for one room

    private static String t(boolean b) {
        return b ? "T" : "F";
    }

    private static String answers(Room room) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random(room.seed);
        RunState state = room.state;
        String dim = room.dimension;

        // the planner's flags over three looks, the latches carrying from one to the next
        StationVerdict last = null;
        sb.append("flags=");
        for (int i = 0; i < 3; i++) {
            double[] p = room.looks[i];
            last = StationVerdict.of(state, dim, p[0], p[1], p[2], room.world(), room.latches);
            sb.append(t(last.table().heldForPlanner())).append(t(last.furnace().heldForPlanner())).append(t(last.smoker().heldForPlanner()));
            sb.append('/').append(t(room.latches.tableFar)).append(room.latches.furnaceHeld == null ? "-" : t(room.latches.furnaceHeld))
                    .append(room.latches.smokerHeld == null ? "-" : t(room.latches.smokerHeld)).append(',');
        }
        // the third look's three flags once more, on their own, for the tally (the verdict was built before any bench moved, which is
        // when mayEnd and the others are asked below)
        sb.append(";flags2=").append(t(last.table().heldForPlanner())).append(t(last.furnace().heldForPlanner())).append(t(last.smoker().heldForPlanner()));
        double px = room.looks[2][0];
        double py = room.looks[2][1];
        double pz = room.looks[2][2];

        // the registry's own questions, per kind
        StringBuilder wb = new StringBuilder();
        sb.append(";walks=");
        for (Kind kind : Kind.values()) {
            boolean inBag = room.bag[kind.ordinal()] > 0;
            boolean canMake = StationChoice.canMakeFrom(kind, room.cobble - room.cobbleOwed, room.logs, room.planks, room.furnaceItems);
            boolean walks = Workbenches.plannerWalksBack(state, kind, dim, px, py, pz, inBag, canMake, b -> room.usable.getOrDefault(Room.key(kind, b.pos), true));
            wb.append(t(walks));
            sb.append(t(walks));
            sb.append(t(Workbenches.any(state, kind, dim)));
            for (double radius : new double[]{WorkbenchRules.NEAR, WorkbenchRules.returnRadius(true), WorkbenchRules.bandRadius(false), WorkbenchRules.FORGET_DISTANCE}) {
                sb.append(t(Workbenches.heldNear(state, kind, dim, px, py, pz, radius, pos -> room.stands.getOrDefault(Room.key(kind, pos), true))));
            }
            sb.append(Workbenches.countStanding(state, kind, dim, px, py, pz, WorkbenchRules.NEAR, b -> true)).append('/')
                    .append(Workbenches.countStanding(state, kind, dim, px, py, pz, WorkbenchRules.FORGET_DISTANCE, b -> true));
            Bench near = Workbenches.nearestStanding(state, kind, dim, px, py, pz, WorkbenchRules.NEAR);
            Bench far = Workbenches.nearestStanding(state, kind, dim, px, py, pz, WorkbenchRules.FORGET_DISTANCE);
            Bench back = Workbenches.walkBackTo(state, kind, dim, px, py, pz, StationChoice.oursReach(kind, canMake), b -> room.usable.getOrDefault(Room.key(kind, b.pos), true));
            sb.append(index(state, near)).append(index(state, far)).append(index(state, back)).append(' ');
            sb.append(t(WorkbenchRules.placeVetoed(state.benches, kind, NOW)));
        }
        sb.append(";wb=").append(wb);
        sb.append(";standing=");
        for (Bench b : state.benches) {
            sb.append(t(Workbenches.standing(b, b.kind, dim))).append(t(WorkbenchRules.inFlight(b, NOW)))
                    .append(t(WorkbenchRules.targetVetoed(state.benches, b.pos, NOW))).append(t(WorkbenchRules.placedByUs(px, py, pz, b.pos)))
                    // the visit's two questions (FurnaceWatch asks them in the dimension we stand in), then the registry's busy
                    .append(t(FurnaceJobs.isBusy(state, b.pos, dim))).append(t(FurnaceJobs.isBusy(state, b.pos, b.dimension)))
                    .append(b.kind == Kind.TABLE ? "-" : t(FurnaceWatch.ours(state, b.kind.word(), b.pos, dim))).append(',');
        }

        // where the next station comes from, the candidates built the way DoStuffInContainerTask.choose builds them
        sb.append(";choice=");
        for (Kind kind : Kind.values()) {
            boolean inBag = room.bag[kind.ordinal()] > 0;
            boolean canMakeNow = StationChoice.canMakeFrom(kind, room.cobble - room.cobbleOwed, room.logs, room.planks, room.furnaceItems);
            List<StationChoice.Candidate<String>> seen = new ArrayList<>();
            // a split load sees only the idle ones, and the first look is not one
            Bench pinned = null;
            for (Bench b : state.benches) {
                if (b.kind == kind && b.dimension.equals(dim) && b.state == Bench.State.BUSY && !WorkbenchRules.inFlight(b, NOW)
                        && (pinned == null || dist(b, px, py, pz) < dist(pinned, px, py, pz))) {
                    pinned = b;
                }
            }
            if (pinned != null) {
                seen.add(new StationChoice.Candidate<>("b" + state.benches.indexOf(pinned), dist(pinned, px, py, pz), StationChoice.Role.PINNED));
            }
            Bench near = Workbenches.nearestStanding(state, kind, dim, px, py, pz, WorkbenchRules.NEAR);
            Bench far = Workbenches.nearestStanding(state, kind, dim, px, py, pz, WorkbenchRules.FORGET_DISTANCE);
            if (near != null) {
                seen.add(new StationChoice.Candidate<>("b" + state.benches.indexOf(near), dist(near, px, py, pz), StationChoice.Role.OURS));
            }
            if (far != null && far != near && room.usable.getOrDefault(Room.key(kind, far.pos), true) && !far.pickupFailed) {
                seen.add(new StationChoice.Candidate<>("b" + state.benches.indexOf(far), dist(far, px, py, pz), StationChoice.Role.OURS));
            }
            int worlds = r.nextInt(3);
            for (int w = 0; w < worlds; w++) {
                double d = r.nextBoolean() ? AXIS[r.nextInt(AXIS.length)] : r.nextInt(150);
                seen.add(new StationChoice.Candidate<>("w" + w, d, StationChoice.Role.WORLD));
            }
            // the one we were heading for: one of ours, a world one, or a block nothing lists any more
            String previous = null;
            int prev = r.nextInt(4);
            if (prev == 1 && !state.benches.isEmpty()) {
                previous = "b" + r.nextInt(state.benches.size());
            } else if (prev == 2 && worlds > 0) {
                previous = "w0";
            } else if (prev == 3) {
                previous = "gone";
            }
            // the previous one is a candidate in the world too: it keeps its place only when it is still a station
            if (previous != null && !previous.equals("gone")) {
                final String pk = previous;
                boolean listed = seen.stream().anyMatch(c -> c.key().equals(pk));
                if (!listed && pk.startsWith("b")) {
                    Bench b = state.benches.get(Integer.parseInt(pk.substring(1)));
                    if (b.kind == kind && b.dimension.equals(dim)) {
                        seen.add(new StationChoice.Candidate<>(pk, dist(b, px, py, pz), StationChoice.Role.OURS));
                    }
                }
            }
            double worldReach = WorkbenchRules.NEAR;
            boolean mayMake = r.nextInt(8) != 0;
            for (String p : new String[]{null, previous}) {
                StationChoice.Pick<String> pick = StationChoice.decide(seen, p, inBag, mayMake, canMakeNow, worldReach, StationChoice.walkBackReach(kind));
                sb.append(pick.use()).append(':').append(pick.key()).append(pick.use() == StationChoice.Use.WORLD ? "" : "").append('|');
            }
            sb.append(' ');
        }

        // what needs which station, over the room's plan
        sb.append(";soon=");
        for (Kind kind : Kind.values()) {
            for (boolean pick : new boolean[]{false, true}) {
                for (boolean stone : new boolean[]{false, true}) {
                    sb.append(t(WorkbenchRules.neededSoon(kind, room.plan, pick, stone)));
                }
            }
            sb.append(WorkbenchRules.comingBack(kind, room.plan, false)).append(WorkbenchRules.comingBack(kind, room.plan, true)).append(' ');
        }

        // one tick of decisions: every bench, on a copy so the bench itself stays the way the registry has it
        sb.append(";decide=");
        for (int i = 0; i < state.benches.size(); i++) {
            Bench real = state.benches.get(i);
            Bench b = new Bench(real.kind, real.pos, real.dimension, real.placedTick);
            Room.clocks(b, new Random(room.clockSeeds.get(i)));
            b.state = real.state;
            Random lr = new Random(room.seed ^ (i * 7919L));
            boolean same = b.dimension.equals(dim);
            boolean gone = !room.stands.getOrDefault(Room.key(b.kind, b.pos), true);
            boolean jobHere = b.kind != Kind.TABLE && FurnaceJobs.isBusy(state, b.pos, b.dimension);
            boolean holds = b.kind != Kind.TABLE && (jobHere || lr.nextInt(5) == 0);
            boolean soon = WorkbenchRules.neededSoon(b.kind, room.plan, room.holdsPick, room.holdsStonePick);
            WorkbenchRules.Look look = new WorkbenchRules.Look(NOW, WalkDist.of(b, px, py, pz), same, gone, lr.nextInt(4) != 0, holds, jobHere, soon,
                    lr.nextInt(10) != 0, WorkbenchRules.PICKUP_TRY_TICKS, b.kind == Kind.TABLE && WorkbenchRules.canRecraftTable(room.planks, room.logs));
            sb.append(b.kind.word().charAt(0));
            sb.append(t(WorkbenchRules.doneUsing(b, look))).append(t(WorkbenchRules.adoptable(b, look)));
            for (boolean lastChance : new boolean[]{false, true}) {
                Bench h = copy(real, room.clockSeeds.get(i));
                sb.append(t(WorkbenchRules.holdFar(h, look, lastChance))).append(h.redecide ? 'r' : '-').append(t(WorkbenchRules.tooFarForLastTrip(look, lastChance)));
            }
            // the real bench: anchorOf compares by identity against the registry
            Bench anchor = WorkbenchRules.anchorOf(real, state.benches, x -> FurnaceJobs.isBusy(state, x.pos, x.dimension));
            sb.append(anchor == null ? "-" : String.valueOf(state.benches.indexOf(anchor)));
            WorkbenchRules.Call call = WorkbenchRules.decide(b, look);
            sb.append(call).append('/').append(WorkbenchRules.reason(b, look)).append('/').append(b.state).append('/').append(b.outsideSince).append('/')
                    .append(b.otherDimensionSince).append('/').append(t(b.leftSinceFail)).append(t(b.pickupFailed)).append(t(b.givenUp)).append(',');
        }

        // may the phase end with them standing, and the portal's hold over every combination
        List<Bench> snapshot = new ArrayList<>(state.benches);
        sb.append(";mayEnd=").append(t(WorkbenchRules.phaseMayEnd(snapshot, dim, x -> FurnaceJobs.isBusy(state, x.pos, x.dimension))));
        sb.append("/").append(WorkbenchRules.leftGivenUp(snapshot, dim, x -> FurnaceJobs.isBusy(state, x.pos, x.dimension)).size());
        sb.append(";portal=");
        for (int bits = 0; bits < 16; bits++) {
            sb.append(t(WorkbenchRules.portalHold((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0)));
        }
        // the live one runs sync first (and may drop a stale drive), so it goes last
        sb.append(";live=").append(t(Workbenches.phaseMayEnd(state, dim, NOW))).append(';');

        // what the planner and the cook gate make of the flags
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.valueOf(dim);
        f.gameTime = NOW;
        f.tablePlaced = last.table().heldForPlanner();
        f.furnacePlaced = last.furnace().heldForPlanner();
        f.smokerPlaced = last.smoker().heldForPlanner();
        f.give(Items.CRAFTING_TABLE, room.bag[0]);
        f.give(Items.FURNACE, room.bag[1]);
        f.give(Items.SMOKER, room.bag[2]);
        f.give(Items.COBBLESTONE, room.cobble);
        f.give(Items.OAK_LOG, room.logs);
        f.give(Items.OAK_PLANKS, room.planks);
        f.give(Items.COAL, r.nextInt(4));
        f.give(Items.IRON_INGOT, r.nextInt(3) == 0 ? r.nextInt(8) : 0);
        f.give(Items.RAW_IRON, r.nextInt(4) == 0 ? 1 + r.nextInt(6) : 0);
        if (room.holdsPick) {
            f.give(Items.WOODEN_PICKAXE, 1);
        }
        if (room.holdsStonePick) {
            f.give(Items.STONE_PICKAXE, 1);
        }
        f.give(Items.WOODEN_AXE, r.nextInt(2));
        if (r.nextInt(3) > 0) {
            f.give(Items.BEEF, 1 + r.nextInt(8));
        }
        f.foodUnits = 20 + r.nextInt(131);
        OverworldConfig cfg = new OverworldConfig();
            cfg.netherSpareShields = 0;
            cfg.netherSpareSwords = 0;
        int beds = 0;
        EndConfig end = new EndConfig();
        end.beds = beds;
        WoodBudget w = WoodBudget.of(f, cfg, beds, GamerPhase.GATHER);
        FoodPlan food = FoodPlan.of(f, cfg, end, true, false, w);
        sb.append("gather=").append(names(KitPlanner.gather(f, cfg, beds, food, w))).append(';');
        sb.append("plan=").append(names(KitPlanner.plan(f, cfg, beds, food, w))).append(';');
        KitNeed cook = CookGate.need(f, cfg, beds, w);
        sb.append("cook=").append(cook == null ? "-" : cook.catalogueName()).append(';');
        for (boolean mayCraft : new boolean[]{false, true}) {
            sb.append("station").append(mayCraft ? "Y=" : "N=").append(CookGate.station(f, cfg, mayCraft, w)).append(';');
        }
        sb.append("feasible=").append(CookGate.cookFeasible(f, cfg, beds, w));
        return sb.toString();
    }

    private static Bench copy(Bench real, long seed) {
        Bench b = new Bench(real.kind, real.pos, real.dimension, real.placedTick);
        Room.clocks(b, new Random(seed));
        b.state = real.state;
        return b;
    }

    private static double dist(Bench b, double px, double py, double pz) {
        return WalkDist.of(b, px, py, pz);
    }

    private static int index(RunState state, Bench b) {
        return b == null ? -1 : state.benches.indexOf(b);
    }

    private static String names(List<KitNeed> needs) {
        StringBuilder sb = new StringBuilder("[");
        for (KitNeed n : needs) {
            sb.append(n.catalogueName()).append(':').append(n.count()).append(',');
        }
        return sb.append(']').toString();
    }

    // the bench's distance the way the world half measures it
    private static final class WalkDist {
        static double of(Bench b, double px, double py, double pz) {
            return adris.altoclef.util.helpers.WalkCost.stationDistance(b.pos.x, b.pos.y, b.pos.z, px, py, pz);
        }
    }
}
