/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A neo, flown tick by tick the way LivingEntity does it. Pure math so the planner can ask it things off thread
 */
final class NeoJump {

    // straight out of LivingEntity (diffed 1.21.4 against 26.3, same numbers), same numbers SprintJump uses. aiStep scales
    // move input by 0.98 (applyInput on 26.3)
    private static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, JUMP = 0.42, GRAVITY = 0.08, BOOST = 0.2;
    // sprinting only. walking never made a single one of these, the jump boost is doing all the work
    static final double SPRINT_GROUND = 0.13 * 0.98;
    private static final double AIR_ACCEL = 0.026 * 0.98;
    private static final double HALF = 0.3;
    // nothing past this matters, and nothing past it gets checked either (see clearance)
    private static final double CAP = 0.2;
    static final int MAX_DIST = 4, MAX_RUNWAY = 6, MIN_HOP_RUNWAY = 3;
    private static final int MAX_TICKS = 20, MAX_SWITCH = 12;
    // two policies, we fly whichever lands better. both start the same: run up holding some sideways spot, then jump.
    // hold: keep v out past the end of the wall, then at some tick settle back in toward the landing.
    // curve: jump a bit out, then turn in a little more every tick. a 2 thick wall is all curve. a hill climb over
    // every tick's heading found 0.04 of room (jump 15 out, then -9, -13, -14, ... -80) where hold found none
    private static final double[] RUN = {0.5, 0.7, 0.76}, HOLD = {0.88, 0.95, 1.02, 1.1}, GAIN = {0.4, 1.0}, SETTLE = {0.3, 0.55, 0.75};
    // the hill climb's curve turns in gently and then harder and harder, so the turn rate gets to grow too (bend)
    private static final double[] CURVE_JUMP = {0, 5, 10, 15, 20, 25, 30}, CURVE_START = {5, 0, -5, -10, -20}, CURVE_RATE = {0, 2, 4, 7, 10, 15}, CURVE_BEND = {0, 0.5, 1, 1.5};
    private static final int HOLD_POLICY = 0, CURVE_POLICY = 1;
    // how good a standing start has to look before A* is allowed to plan one, and before we commit to the jump
    static final double PLAN_MARGIN = 0.03, JUMP_MARGIN = 0.02;
    // where we stand before the run up, relative to the back of the runway
    // 0.76 is right on the edge of the bridge (0.04 of floor left under us), which is where the good runs start. every
    // bit further out we start is that much more room past the wall, a 2 thick wall goes 0.025 -> 0.04 between 0.74 and 0.76
    private static final double[][] STAGING = {{0.4, 0.5}, {0.4, 0.7}, {0.4, 0.76}, {0.55, 0.7}, {0.55, 0.76}};
    // at full speed we cover 0.28 a tick and a 2 thick wall's takeoff window is 0.06 wide, so where the run started
    // decides whether we ever get a shot (a run that goes -0.41 then -0.13 never does). angling off the line for a tick
    // costs a bit of forward speed, which is how runTick lines a later tick up with the spot. degrees off the run heading
    private static final double[] PHASE = {0, 20, -20, 45, -45};
    // the hop is a sprint jump a few blocks before the takeoff that comes down on the runway, and the neo jumps on the very
    // next tick off the speed the hop landed with. in the air the only knob is which way W points: facing a bit off the
    // line costs forward accel, and the last few are facing backwards, which is a brake that leaves v alone
    private static final double[] HOP_PHASE = {0, 15, -15, 30, -30, 50, -50, 75, -75, 105, -105};
    // bump this whenever the physics, the policies, the staging candidates or any of the margins change. the table gets saved
    // to disk, and a file from before the change would keep handing out answers the sim wouldn't give anymore.
    // forgetting is how you spend an evening wondering why the test passes and the game doesn't
    static final int TABLE_VERSION = 1;
    // null means nobody has asked. PENDING is queued because A* asked, RUNNING is somebody computing it right now, NONE is
    // an answer (no run up works), anything else is the answer
    private static final double[] NONE = {}, PENDING = {}, RUNNING = {};
    private static final int TABLE_SIZE = (MAX_RUNWAY + 1) * (MAX_DIST + 1) * (1 << (MAX_DIST - 1)) * 2;
    // atomic so a half built answer can't be seen through a data race, and so claiming a key is one cas
    private static final AtomicReferenceArray<double[]> TABLE = newTable();

    /**
     * Where we're going this tick. heading is in the local frame: u down the line, v out toward the open side
     */
    record Plan(double margin, double score, int runup, double headingU, double headingV,
                int policy, double a, double b, int at, double d, boolean born, int age) {}

    /**
     * Where to stand, which sideways spot to hold on the run up, and how many ticks of running before the jump
     */
    record Staging(double u, double v, double runTarget, int runup, double takeoff, boolean hop) {}

    // local frame: origin at the center of the block we jump from, u along the jump, v toward the side we swing out on.
    // block (k, j) covers u in [k - 0.5, k + 0.5] and v in [j - 0.5, j + 0.5]. on the line (j = 0) we run up on k = -1,
    // take off from 0 and land on dist, bit k - 1 of walls says whether k is in the way. we fly through j = 1 from -1
    // to dist. the two blocks past the landing (j = 0 and 1) are open if openBeyond says so, everything else is a wall
    // as far as we're concerned. opening up j = 2 as well changed exactly nothing, we never get that far out
    final int dist, walls, runway;
    private final boolean openBeyond;
    private final double groundAccel;
    // what the last fly/best came out with: worst clearance, and the heading for the tick we're on
    private double margin, headingU, headingV;
    static final double RUN_GAIN = 0.6;

    NeoJump(int dist, int walls, int runway, boolean openBeyond, double groundAccel) {
        this.dist = dist;
        this.walls = walls;
        this.runway = runway;
        this.openBeyond = openBeyond;
        this.groundAccel = groundAccel;
    }

    /**
     * @return where to stand before the run up, null if no run up we know makes it. works it out right now if nobody
     * has yet, which is a few hundred ms
     */
    static Staging staging(int dist, int walls, int runway, boolean openBeyond) {
        int key = key(dist, walls, runway, openBeyond);
        if (key < 0) {
            return null;
        }
        return unpack(resolve(key, dist, walls, runway, openBeyond));
    }

    /**
     * Same, except a shape nobody has worked out yet gets worked out in the background and we say no for now. A* asks
     * this, a 400 ms stall the first time it sees a 2 thick wall would eat the whole search. the next path gets it.
     * the first ask of the session also starts on the whole table (see warm), so by the time anyone is standing in front
     * of a wall it's usually all there
     */
    static Staging stagingIfKnown(int dist, int walls, int runway, boolean openBeyond) {
        int key = key(dist, walls, runway, openBeyond);
        if (key < 0) {
            return null;
        }
        if (!WARMED.get()) {
            warm();
        }
        double[] spot = TABLE.get(key);
        if (spot == null) {
            // the warmup gets to everything eventually, this one gets to cut the line
            if (TABLE.compareAndSet(key, null, PENDING)) {
                QUEUE.addFirst(new Job(key, dist, walls, runway, openBeyond));
                ensureWorkers();
            }
            return null;
        }
        return spot == PENDING || spot == RUNNING ? null : unpack(spot);
    }

    // the answer for a key, working it out here if nobody is. not under any lock: a runway of 6 asks for six of these at
    // once and they'd go one at a time. but if somebody already has the key we wait for them instead of doing it twice
    private static double[] resolve(int key, int dist, int walls, int runway, boolean openBeyond) {
        for (; ; ) {
            double[] spot = TABLE.get(key);
            if (spot != null && spot != PENDING && spot != RUNNING) {
                return spot;
            }
            if (spot != RUNNING && TABLE.compareAndSet(key, spot, RUNNING)) {
                double[] result = null;
                try {
                    result = pick(new NeoJump(dist, walls, runway, openBeyond, SPRINT_GROUND));
                    return result;
                } finally {
                    // null if it threw, so the next ask gets to try again instead of waiting on a corpse
                    TABLE.set(key, result);
                }
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pick(new NeoJump(dist, walls, runway, openBeyond, SPRINT_GROUND));
            }
        }
    }

    // the warmup: every shape there is, computed once in the background and kept in a file

    record Job(int key, int dist, int walls, int runway, boolean openBeyond) {}

    private static final AtomicBoolean WARMED = new AtomicBoolean(), SAVED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ConcurrentLinkedDeque<Job> QUEUE = new ConcurrentLinkedDeque<>();
    private static volatile Path tableFile;

    // where the table lives between launches. MovementNeo hands it over from the Baritone side, this class doesn't know
    // what a game directory is. null (the default, and what the tests see) means no saving and no loading
    static void setTableFile(Path file) {
        tableFile = file;
    }

    static AtomicReferenceArray<double[]> newTable() {
        return new AtomicReferenceArray<>(TABLE_SIZE);
    }

    // every key key() accepts, the common ones first: dist 2 and 3 with the short runways are what you run into in the
    // wild, a dist 4 with six blocks of runway is somebody's flex
    static List<Job> jobs() {
        List<Job> list = new ArrayList<>();
        for (int dist = 2; dist <= MAX_DIST; dist++) {
            for (int walls = 1; walls < 1 << (dist - 1); walls++) {
                for (int runway = 1; runway <= MAX_RUNWAY; runway++) {
                    for (int open = 0; open < 2; open++) {
                        list.add(new Job(key(dist, walls, runway, open == 1), dist, walls, runway, open == 1));
                    }
                }
            }
        }
        list.sort(Comparator.comparingInt((Job j) -> j.dist() > 3 ? 1 : 0).thenComparingInt(Job::runway).thenComparingInt(Job::dist)
                .thenComparingInt(Job::walls).thenComparing(Job::openBeyond));
        return list;
    }

    // first ask of the session: load whatever the file has, queue up the rest, and get some threads on it. half the
    // cores, the game has to keep drawing frames while this goes
    private static void warm() {
        if (!WARMED.compareAndSet(false, true)) {
            return;
        }
        Path file = tableFile;
        if (file != null) {
            load(file, TABLE);
        }
        int missing = 0;
        for (Job job : jobs()) {
            if (TABLE.get(job.key()) == null) {
                QUEUE.addLast(job);
                missing++;
            }
        }
        if (missing == 0) {
            SAVED.set(true); // the file had all of it, nothing to write back
        }
        ensureWorkers();
    }

    private static void ensureWorkers() {
        int cap = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        while (!QUEUE.isEmpty()) {
            int active = ACTIVE.get();
            if (active >= cap) {
                return;
            }
            if (ACTIVE.compareAndSet(active, active + 1)) {
                Thread thread = new Thread(NeoJump::work, "neo-warmup");
                thread.setDaemon(true);
                // below the render thread, we're a guest
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                thread.start();
            }
        }
    }

    private static void work() {
        try {
            Job job;
            while ((job = QUEUE.pollFirst()) != null) {
                try {
                    resolve(job.key(), job.dist(), job.walls(), job.runway(), job.openBeyond());
                } catch (Throwable t) {
                    // resolve already put the key back to unknown. one bad shape shouldn't stop the other 131
                }
            }
        } finally {
            ACTIVE.decrementAndGet();
            // a job could have landed between our last poll and the decrement, and ensureWorkers would've seen us still active
            if (!QUEUE.isEmpty()) {
                ensureWorkers();
            } else if (ACTIVE.get() == 0) {
                saveIfDone();
            }
        }
    }

    private static void saveIfDone() {
        Path file = tableFile;
        if (file == null) {
            return;
        }
        for (Job job : jobs()) {
            double[] spot = TABLE.get(job.key());
            if (spot == null || spot == PENDING || spot == RUNNING) {
                return; // not all of it, so we don't write half a table
            }
        }
        if (SAVED.compareAndSet(false, true)) {
            save(file, TABLE);
        }
    }

    // anything about the sim that could change an answer goes in here, so a file from a different build is just ignored
    static String header(int version) {
        return "neo-table version=" + version + " maxDist=" + MAX_DIST + " maxRunway=" + MAX_RUNWAY + " planMargin=" + PLAN_MARGIN
                + " jumpMargin=" + JUMP_MARGIN + " comfortable=" + COMFORTABLE;
    }

    // one line per shape: dist walls runway openBeyond, then none or the six numbers of a Staging. text because it's
    // 132 lines and whoever gets confused by it next will want to read it. false if it couldn't be written, which is
    // fine, we just do it all again next launch
    static boolean save(Path file, AtomicReferenceArray<double[]> table) {
        StringBuilder out = new StringBuilder(header(TABLE_VERSION)).append('\n');
        for (Job job : jobs()) {
            double[] spot = table.get(job.key());
            if (spot == null || spot == PENDING || spot == RUNNING) {
                continue;
            }
            out.append(job.dist()).append(' ').append(job.walls()).append(' ').append(job.runway()).append(' ').append(job.openBeyond() ? 1 : 0);
            if (spot == NONE) {
                out.append(" none");
            } else {
                for (double d : spot) {
                    out.append(' ').append(d); // Double.toString round trips exactly
                }
            }
            out.append('\n');
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // write next to it and move, so a game that gets closed mid write leaves the old file and not half of a new one
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, out, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // how many shapes got filled in. 0 if the file is missing, from a different version, or anything in it is off, and then
    // nothing is touched. slots that already have an answer keep it
    static int load(Path file, AtomicReferenceArray<double[]> table) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        if (lines.isEmpty() || !lines.get(0).equals(header(TABLE_VERSION))) {
            return 0;
        }
        // parse everything before installing anything, a corrupt file shouldn't get to be half believed
        List<Integer> keys = new ArrayList<>();
        List<double[]> spots = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] part = line.split(" ");
            try {
                int dist = Integer.parseInt(part[0]), walls = Integer.parseInt(part[1]), runway = Integer.parseInt(part[2]), open = Integer.parseInt(part[3]);
                int key = open < 0 || open > 1 ? -1 : key(dist, walls, runway, open == 1);
                if (key < 0) {
                    return 0;
                }
                double[] spot;
                if (part.length == 5 && part[4].equals("none")) {
                    spot = NONE;
                } else if (part.length == 10) {
                    spot = new double[6];
                    for (int k = 0; k < 6; k++) {
                        spot[k] = Double.parseDouble(part[4 + k]);
                        if (Double.isNaN(spot[k]) || Double.isInfinite(spot[k])) {
                            return 0;
                        }
                    }
                    if (spot[3] < 0 || spot[3] != Math.rint(spot[3]) || spot[5] != 0 && spot[5] != 1) {
                        return 0;
                    }
                } else {
                    return 0;
                }
                keys.add(key);
                spots.add(spot);
            } catch (RuntimeException e) {
                return 0;
            }
        }
        int loaded = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (table.compareAndSet(keys.get(i), null, spots.get(i))) {
                loaded++;
            }
        }
        return loaded;
    }

    static int key(int dist, int walls, int runway, boolean openBeyond) {
        if (dist < 2 || dist > MAX_DIST || walls <= 0 || walls >= 1 << (dist - 1) || runway < 1 || runway > MAX_RUNWAY) {
            return -1;
        }
        return ((runway * (MAX_DIST + 1) + dist) * (1 << (MAX_DIST - 1)) + walls) * 2 + (openBeyond ? 1 : 0);
    }

    static Staging unpack(double[] spot) {
        return spot == NONE ? null : new Staging(spot[0], spot[1], spot[2], (int) spot[3], spot[4], spot[5] != 0);
    }

    // the table's answer for each strategy, so the tests can see both
    double plainMargin = Double.NaN, hopMargin = Double.NaN;

    double[] pick() {
        NeoJump neo = this;
        double[] plain = NONE, hop = NONE;
        plainMargin = hopMargin = Double.NaN;
        for (double run : RUN) {
            double takeoff = neo.takeoff(run);
            if (!Double.isNaN(takeoff)) {
                for (double[] candidate : STAGING) {
                    double u = candidate[0] - neo.runway - 0.5;
                    double margin = neo.rollout(u, candidate[1], run, takeoff);
                    if (margin > plainMargin || Double.isNaN(plainMargin)) {
                        plainMargin = margin;
                        plain = new double[]{u, candidate[1], run, neo.rolloutRunup, takeoff, 0};
                    }
                }
            }
            // a hop needs room, and most runways don't have it
            double hopTakeoff = neo.runway >= MIN_HOP_RUNWAY ? neo.hopTakeoff(run) : Double.NaN;
            if (Double.isNaN(hopTakeoff)) {
                continue;
            }
            for (double[] candidate : STAGING) {
                double u = candidate[0] - neo.runway - 0.5;
                double margin = neo.rolloutHop(u, candidate[1], run, hopTakeoff);
                if (margin > hopMargin || Double.isNaN(hopMargin)) {
                    hopMargin = margin;
                    hop = new double[]{u, candidate[1], run, neo.rolloutRunup, hopTakeoff, 1};
                }
            }
        }
        boolean plainOk = plainMargin >= PLAN_MARGIN, hopOk = hopMargin >= PLAN_MARGIN;
        // the hop is more moving parts (two jumps, two takeoff windows), so it only gets used when the plain run up
        // can't make it or only just can. a neo that clears by 0.1 on foot doesn't need to get fancy
        if (plainOk && (plainMargin >= COMFORTABLE || !hopOk || plainMargin >= hopMargin)) {
            return plain;
        }
        return hopOk ? hop : NONE;
    }

    static final double COMFORTABLE = 0.06;

    private static double[] pick(NeoJump neo) {
        return neo.pick();
    }

    /**
     * The best spot along the line to jump from at full speed holding v = run, NaN if there isn't one
     */
    double takeoff(double run) {
        // full sprint on the ground settles at v = a * 0.546 / (1 - 0.546), 0.153 for plain sprinting
        double full = groundAccel * GROUND_FRICTION / (1 - GROUND_FRICTION);
        double spot = Double.NaN, best = 0;
        for (double u = EARLIEST; u <= 0.3; u += 0.02) {
            Plan plan = plan(u, run, full, 0, 0, 0, true, 0, null);
            if (plan != null && plan.margin() > best) {
                best = plan.margin();
                spot = u;
            }
        }
        return spot;
    }

    /**
     * Heading for one tick of run up (into headingU/V): hold v at run, and angle off a bit if that makes some later tick
     * land closer to the takeoff spot
     */
    double[] runTick(double u, double v, double vu, double vv, double run, double takeoff) {
        double base = Math.asin(side(v, vv, run, RUN_GAIN, groundAccel));
        double bestError = Double.POSITIVE_INFINITY, bestC = Math.cos(base), bestS = Math.sin(base);
        for (double off : PHASE) {
            double c = Math.cos(base + Math.toRadians(off)), s = Math.sin(base + Math.toRadians(off));
            pu = u;
            pv = v;
            pvu = vu;
            pvv = vv;
            margin = CAP;
            if (!run(c, s)) {
                continue;
            }
            // then run straight and see how close the closest tick gets
            double error = Double.POSITIVE_INFINITY;
            for (int k = 0; k < 20 && pu < takeoff + 0.3; k++) {
                error = Math.min(error, Math.abs(pu - takeoff));
                double hold = side(pv, pvv, run, RUN_GAIN, groundAccel);
                if (!run(Math.sqrt(1 - hold * hold), hold)) {
                    break;
                }
            }
            // going straight is free, the others cost a little so we don't wiggle for a hundredth
            error += 0.003 * Math.abs(off) / 45;
            if (error < bestError) {
                bestError = error;
                bestC = c;
                bestS = s;
            }
        }
        headingU = bestC;
        headingV = bestS;
        return new double[]{bestC, bestS};
    }

    private int rolloutRunup;
    // no jump from further back than this lands even dist 2, so there's no point asking. it's most of the run up
    static final double EARLIEST = -1.5;

    /**
     * Play the whole thing out the way MovementNeo will: stand still at (u, v), run holding run, jump when plan says
     * jumping now beats jumping next tick, replan every tick in the air. the planner's own score is for one fixed
     * schedule, and replanning every tick does a lot better than any one schedule (a 2 thick wall goes from 0.028 to
     * whatever this says), so this is what decides if A* gets to use the shape
     *
     * @return the worst clearance along the way, NaN if it doesn't make it
     */
    double rollout(double u, double v, double run, double takeoff) {
        double ru = u, rv = v, rvu = 0, rvv = 0, ry = 0, rvy = 0, worst = CAP;
        boolean ground = true;
        Plan plan = null;
        rolloutRunup = 0;
        for (int tick = 0; tick < 4 * MAX_RUNWAY + 30; tick++) {
            plan = !ground ? plan(ru, rv, rvu, rvv, ry, rvy, false, 0, plan) : ru > EARLIEST ? plan(ru, rv, rvu, rvv, 0, 0, true, 1, null) : null;
            double c, s;
            boolean jump = false;
            if (plan != null && (!ground || plan.margin() >= JUMP_MARGIN)) {
                c = plan.headingU();
                s = plan.headingV();
                jump = ground && plan.runup() == 0;
            } else if (ground && ru < 0.2) {
                // nothing a tick out lands it yet, so keep running and line up with the takeoff spot
                runTick(ru, rv, rvu, rvv, run, takeoff);
                c = headingU;
                s = headingV;
            } else {
                return Double.NaN;
            }
            pu = ru;
            pv = rv;
            pvu = rvu;
            pvv = rvv;
            py = ry;
            pvy = rvy;
            margin = worst;
            if (ground && !jump) {
                if (!run(c, s)) {
                    return Double.NaN;
                }
                rolloutRunup++;
            } else {
                int result = air(c, s, ground);
                if (result == FAILED) {
                    return Double.NaN;
                }
                if (result == LANDED) {
                    return margin;
                }
                ground = false;
            }
            worst = margin;
            ru = pu;
            rv = pv;
            rvu = pvu;
            rvv = pvv;
            ry = py;
            rvy = pvy;
        }
        return Double.NaN;
    }

    /**
     * The best spot along the line to jump the neo from right after a hop lands, NaN if there isn't one (or the hop
     * itself can't be done)
     */
    double hopTakeoff(double run) {
        // the speed a hop lands with doesn't care where on the runway it was, so fly one down a runway with all the room
        // in the world and look at what it comes down with
        NeoJump far = new NeoJump(dist, walls, 20, openBeyond, groundAccel);
        far.pu = -8;
        far.pv = run;
        far.pvu = groundAccel * GROUND_FRICTION / (1 - GROUND_FRICTION);
        far.pvv = 0;
        far.py = far.pvy = 0;
        far.margin = CAP;
        if (!far.hop(true, run, Double.NaN)) {
            return Double.NaN;
        }
        double v = far.pv, vu = far.pvu, vv = far.pvv;
        double spot = Double.NaN, best = 0;
        for (double u = EARLIEST; u <= 0.3; u += 0.02) {
            Plan plan = plan(u, v, vu, vv, 0, 0, true, 0, null);
            if (plan != null && plan.margin() > best) {
                best = plan.margin();
                spot = u;
            }
        }
        return spot;
    }

    /**
     * One run up tick before a hop: {headingU, headingV, 1 if this is the tick to jump, else 0}. we jump when
     * jumping now lands closer to the neo takeoff than jumping a tick from now would. null if neither lands on the runway
     */
    double[] hopRunTick(double u, double v, double vu, double vv, double run, double takeoff) {
        double side = side(v, vv, run, RUN_GAIN, groundAccel);
        double c = Math.sqrt(1 - side * side);
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = pvy = 0;
        margin = CAP;
        double now = hop(true, run, takeoff) ? Math.abs(pu - takeoff) : Double.POSITIVE_INFINITY;
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = pvy = 0;
        margin = CAP;
        double next = Double.POSITIVE_INFINITY;
        if (run(c, side)) {
            py = pvy = 0;
            next = hop(true, run, takeoff) ? Math.abs(pu - takeoff) : Double.POSITIVE_INFINITY;
        }
        if (now == Double.POSITIVE_INFINITY && next == Double.POSITIVE_INFINITY) {
            return null;
        }
        if (now <= next) {
            // hop() trashed the heading it found for the jump tick, so ask for it again
            return hopTick(u, v, vu, vv, 0, 0, true, run, takeoff, 1);
        }
        return new double[]{c, side, 0};
    }

    /**
     * Heading for one tick of the hop, same shape as hopRunTick's answer. null if no heading survives
     */
    double[] hopTick(double u, double v, double vu, double vv, double y, double vy, boolean jumpTick, double run, double takeoff, int jump) {
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        margin = CAP;
        return hopHeading(jumpTick, run, takeoff) ? new double[]{headingU, headingV, jump} : null;
    }

    double[] hopTick(double u, double v, double vu, double vv, double y, double vy, double run, double takeoff) {
        return hopTick(u, v, vu, vv, y, vy, false, run, takeoff, 0);
    }

    /**
     * Picks this tick's heading (into headingU/V) from the state in p*, which it leaves alone. each candidate is tried
     * for one tick and then flown straight to touchdown, and whichever comes down nearest takeoff wins
     */
    private boolean hopHeading(boolean jumpTick, double run, double takeoff) {
        double u = pu, v = pv, vu = pvu, vv = pvv, y = py, vy = pvy, m = margin;
        double accel = jumpTick ? BOOST + groundAccel : AIR_ACCEL;
        double base = Math.asin(side(v, vv, run, RUN_GAIN, accel));
        double bestError = Double.POSITIVE_INFINITY, bestC = 0, bestS = 0;
        for (int mirror = 0; mirror < 2; mirror++) {
            for (double off : HOP_PHASE) {
                // mirrored is the same sideways push facing backwards: all brake, and v doesn't care
                if (mirror == 1 && off != 0) {
                    continue;
                }
                double angle = mirror == 0 ? base + Math.toRadians(off) : Math.PI - base;
                double c = Math.cos(angle), s = Math.sin(angle);
                pu = u;
                pv = v;
                pvu = vu;
                pvv = vv;
                py = y;
                pvy = vy;
                margin = m;
                int result = air(c, s, jumpTick, true);
                if (result == FAILED) {
                    continue;
                }
                boolean landed = result == HOPPED;
                for (int k = 0; !landed && k < MAX_TICKS; k++) {
                    double hold = side(pv, pvv, run, RUN_GAIN, AIR_ACCEL);
                    result = air(Math.sqrt(1 - hold * hold), hold, false, true);
                    if (result == FAILED) {
                        break;
                    }
                    landed = result == HOPPED;
                }
                if (!landed) {
                    continue;
                }
                double error = Double.isNaN(takeoff) ? 0 : Math.abs(pu - takeoff);
                error += 0.003 * Math.abs(off) / 45 + (mirror == 1 ? 0.003 : 0);
                if (error < bestError) {
                    bestError = error;
                    bestC = c;
                    bestS = s;
                }
            }
        }
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        margin = m;
        headingU = bestC;
        headingV = bestS;
        return bestError < Double.POSITIVE_INFINITY;
    }

    /**
     * A whole hop from the state in p* (on the ground if jump, else mid air) to touchdown, steering the way the runtime will
     *
     * @return false if it hits something or doesn't come down on the runway
     */
    private boolean hop(boolean jump, double run, double takeoff) {
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (!hopHeading(jump, run, takeoff)) {
                return false;
            }
            int result = air(headingU, headingV, jump, true);
            if (result == FAILED) {
                return false;
            }
            if (result == HOPPED) {
                return true;
            }
            jump = false;
        }
        return false;
    }

    /**
     * rollout, for the run up that hops into the takeoff: run, hop, and jump the neo the tick we land
     */
    double rolloutHop(double u, double v, double run, double takeoff) {
        double ru = u, rv = v, rvu = 0, rvv = 0, ry = 0, rvy = 0, worst = CAP;
        boolean airborne = false;
        rolloutRunup = 0;
        for (int tick = 0; tick < 8 * MAX_RUNWAY + 20; tick++) {
            double[] heading = airborne ? hopTick(ru, rv, rvu, rvv, ry, rvy, run, takeoff) : hopRunTick(ru, rv, rvu, rvv, run, takeoff);
            if (heading == null) {
                return Double.NaN;
            }
            boolean jump = heading[2] > 0;
            pu = ru;
            pv = rv;
            pvu = rvu;
            pvv = rvv;
            py = ry;
            pvy = rvy;
            margin = worst;
            if (!airborne && !jump) {
                if (!run(heading[0], heading[1])) {
                    return Double.NaN;
                }
                rolloutRunup++;
            } else {
                int result = air(heading[0], heading[1], jump, true);
                if (result == FAILED) {
                    return Double.NaN;
                }
                airborne = true;
                if (result == HOPPED) {
                    return neoAfterHop(pu, pv, pvu, pvv, margin);
                }
            }
            worst = margin;
            ru = pu;
            rv = pv;
            rvu = pvu;
            rvv = pvv;
            ry = py;
            rvy = pvy;
        }
        return Double.NaN;
    }

    /**
     * The neo jump off a hop's touchdown, and the flight, played out like rollout does
     */
    private double neoAfterHop(double u, double v, double vu, double vv, double worst) {
        Plan plan = plan(u, v, vu, vv, 0, 0, true, 0, null);
        boolean ground = true;
        double y = 0, vy = 0;
        for (int tick = 0; tick < MAX_TICKS + 2; tick++) {
            if (plan == null || ground && plan.margin() < JUMP_MARGIN) {
                return Double.NaN;
            }
            pu = u;
            pv = v;
            pvu = vu;
            pvv = vv;
            py = y;
            pvy = vy;
            margin = worst;
            int result = air(plan.headingU(), plan.headingV(), ground);
            if (result == FAILED) {
                return Double.NaN;
            }
            if (result == LANDED) {
                return margin;
            }
            ground = false;
            worst = margin;
            u = pu;
            v = pv;
            vu = pvu;
            vv = pvv;
            y = py;
            vy = pvy;
            plan = plan(u, v, vu, vv, y, vy, false, 0, plan);
        }
        return Double.NaN;
    }

    /**
     * Best way to go from here, jumping now or (on the ground) after at most maxRunup ticks of running. null if nothing
     * we know how to do lands it. in the air, pass last tick's plan: it gets carried on one tick and kept unless
     * something beats it. without that, replanning kept trading the curve we jumped on for whatever was in the grid
     * from here, which was worse every time (0.025 promised at takeoff, 0.002 delivered)
     */
    Plan plan(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int maxRunup, Plan previous) {
        Plan best = null;
        for (int runup = 0; runup <= (onGround ? maxRunup : 0); runup++) {
            for (int run = 0; run < (runup > 0 ? RUN.length : 1); run++) {
                double score = best(u, v, vu, vv, y, vy, onGround, runup, RUN[run]);
                if (!Double.isNaN(score) && (best == null || score > best.score())) {
                    if (runup > 0) {
                        double side = side(v, vv, RUN[run], RUN_GAIN, groundAccel);
                        best = new Plan(margin, score, runup, Math.sqrt(1 - side * side), side, 0, 0, 0, 0, 0, false, 0);
                    } else {
                        best = new Plan(margin, score, 0, headingU, headingV, bestPolicy, bestA, bestB, bestAt, bestD, onGround, 0);
                    }
                }
            }
        }
        if (!onGround && previous != null && previous.runup() == 0) {
            int age = previous.age() + 1;
            double score = fly(u, v, vu, vv, y, vy, false, 0, 0, previous.policy(), previous.a(), previous.b(), previous.at(), previous.d(), previous.born(), age);
            if (!Double.isNaN(score) && (best == null || score >= best.score())) {
                best = new Plan(margin, score, 0, headingU, headingV, previous.policy(), previous.a(), previous.b(), previous.at(), previous.d(), previous.born(), age);
            }
        }
        return best;
    }

    private double best(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int runup, double run) {
        best = Double.NaN;
        for (double hold : HOLD) {
            for (double gain : GAIN) {
                // switching at 0 never holds, so one hold value is plenty for that
                for (int at = hold == HOLD[0] ? 0 : 1; at <= MAX_SWITCH; at++) {
                    for (double settle : SETTLE) {
                        keep(fly(u, v, vu, vv, y, vy, onGround, runup, run, HOLD_POLICY, hold, gain, at, settle, onGround, 0), HOLD_POLICY, hold, gain, at, settle);
                    }
                }
            }
        }
        // in the air the jump angle doesn't exist, so one of those is plenty
        for (int jump = 0; jump < (onGround ? CURVE_JUMP.length : 1); jump++) {
            for (double start : CURVE_START) {
                for (double rate : CURVE_RATE) {
                    for (int bend = 0; bend < CURVE_BEND.length; bend++) {
                        // bend rides in the int slot hold uses for its switch tick, it's an index
                        keep(fly(u, v, vu, vv, y, vy, onGround, runup, run, CURVE_POLICY, CURVE_JUMP[jump], start, bend, rate, onGround, 0), CURVE_POLICY, CURVE_JUMP[jump], start, bend, rate);
                    }
                }
            }
        }
        margin = bestMargin;
        headingU = bestU;
        headingV = bestV;
        return best;
    }

    private double best, bestMargin, bestU, bestV, bestA, bestB, bestD;
    private int bestPolicy, bestAt;

    private void keep(double score, int policy, double a, double b, int at, double d) {
        if (!Double.isNaN(score) && (Double.isNaN(best) || score > best)) {
            best = score;
            bestMargin = margin;
            bestU = headingU;
            bestV = headingV;
            bestPolicy = policy;
            bestA = a;
            bestB = b;
            bestAt = at;
            bestD = d;
        }
    }

    /**
     * Sideways part of the heading that steers v toward target. asks for a sideways speed that shrinks with the offset
     * instead of aiming at the spot, same reason as SprintJump.Lean.next: aiming ignores the drift we already have
     */
    static double side(double v, double vv, double target, double gain, double accel) {
        double want = Math.max(-0.15, Math.min(0.15, gain * (target - v)));
        return Math.max(-1, Math.min(1, (want - vv) / accel));
    }

    /**
     * @return how good this went, NaN if we hit something, fell, or came down anywhere but the landing block
     */
    private double fly(double u, double v, double vu, double vv, double y, double vy, boolean onGround,
                       int runup, double run, int policy, double a, double b, int switchAt, double d, boolean born, int age) {
        margin = CAP;
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        for (int i = 0; i < runup; i++) {
            double runS = side(pv, pvv, run, RUN_GAIN, groundAccel);
            if (!run(Math.sqrt(1 - runS * runS), runS)) {
                return Double.NaN;
            }
        }
        boolean jumpTick = onGround;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            // age is how many ticks ago this schedule started, born is whether it started with the jump
            int now = tick + age;
            double c, s;
            if (policy == HOLD_POLICY) {
                // the jump boost goes the way we face too, so on the jump tick it counts as acceleration
                s = side(pv, pvv, now < switchAt ? a : d, b, jumpTick ? BOOST + groundAccel : AIR_ACCEL);
                c = Math.sqrt(1 - s * s);
            } else {
                // facing backwards with W held is braking, and sprint doesn't care which way we face
                int t = born ? now - 1 : now;
                double degrees = Math.max(-150, born && now == 0 ? a : b - d * t - CURVE_BEND[switchAt] * t * t);
                c = Math.cos(Math.toRadians(degrees));
                s = Math.sin(Math.toRadians(degrees));
            }
            if (tick == 0) {
                headingU = c;
                headingV = s;
            }
            int result = air(c, s, jumpTick);
            if (result == FAILED) {
                return Double.NaN;
            }
            if (result == LANDED) {
                // margin first. the slide and the run up only break ties, they used to be big enough to pick a sloppy
                // jump next tick over a clean one now, and then next tick never came
                return margin - 0.005 * slide - 0.001 * runup;
            }
            jumpTick = false;
        }
        return Double.NaN;
    }

    // where the sim is right now, so fly and rollout can share the same tick of physics
    private double pu, pv, pvu, pvv, py, pvy, slide;
    private static final int FLYING = 0, LANDED = 1, FAILED = 2, HOPPED = 3;

    /**
     * One tick on the ground holding W toward (c, s). false if we hit something or ran off the edge
     */
    private boolean run(double c, double s) {
        double du = pvu + groundAccel * c;
        double dv = pvv + groundAccel * s;
        if (!sweep(pu, pv, du, dv)) {
            return false;
        }
        pu += du;
        pv += dv;
        // the runway and the takeoff block are one strip of floor from -runway - 0.5 to 0.5. we fall off at 0 overlap,
        // 0.03 is for being a hair off where we think we are
        if (pu > 0.77 || pu < -runway - 0.77 || overlap(pv, 0) < 0.03) {
            return false; // walked off the edge before jumping, nice one
        }
        pvu = du * GROUND_FRICTION;
        pvv = dv * GROUND_FRICTION;
        return true;
    }

    /**
     * One tick in the air holding W toward (c, s), or the jump tick itself
     */
    private int air(double c, double s, boolean jumpTick) {
        return air(c, s, jumpTick, false);
    }

    private int air(double c, double s, boolean jumpTick, boolean hop) {
        double accel = AIR_ACCEL;
        if (jumpTick) {
            // onGround is still true on the jump tick, so it gets ground acceleration and ground friction
            pvu += BOOST * c;
            pvv += BOOST * s;
            pvy = JUMP;
            accel = groundAccel;
        }
        double du = pvu + accel * c;
        double dv = pvv + accel * s;
        // vanilla collides y first, so whether we land is decided where we are now, not where we're about to be
        py += pvy;
        boolean landed = pvy < 0 && py <= 0;
        if (landed && hop) {
            // same support rule as run(), the strip is the runway and the takeoff block
            if (stripOverlap(pu) < 0.03 || overlap(pv, 0) < 0.03 || !sweep(pu, pv, du, dv)) {
                return FAILED;
            }
            pu += du;
            pv += dv;
            // onGround was false when the tick started, so air friction, and landing zeroes vy
            pvu = du * AIR_FRICTION;
            pvv = dv * AIR_FRICTION;
            py = 0;
            pvy = 0;
            return HOPPED;
        }
        double supportU = overlap(pu, dist), supportV = overlap(pv, 0);
        if (landed && (supportU <= 0 || supportV <= 0)) {
            return FAILED; // nothing under us, or it's the block we started on
        }
        if (!sweep(pu, pv, du, dv)) {
            return FAILED;
        }
        pu += du;
        pv += dv;
        if (landed) {
            // where we'd slide to with W let go (v / (1 - 0.546)). coming in from the side usually leaves our feet
            // hanging off the edge of the landing block, which is fine as long as the slide doesn't take us off it
            double slideU = pu + du * AIR_FRICTION / (1 - GROUND_FRICTION);
            double slideV = pv + dv * AIR_FRICTION / (1 - GROUND_FRICTION);
            margin = Math.min(margin, Math.min(Math.min(supportU, supportV), Math.min(overlap(slideU, dist), overlap(slideV, 0))));
            slide = Math.sqrt((slideU - dist) * (slideU - dist) + slideV * slideV);
            return margin > 0 ? LANDED : FAILED;
        }
        pvu = du * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvv = dv * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvy = (pvy - GRAVITY) * 0.98;
        return FLYING;
    }

    private double stripOverlap(double c) {
        return Math.min(c + HALF, 0.5) - Math.max(c - HALF, -runway - 0.5);
    }

    /**
     * How much a box centered at c overlaps the block centered at k on one axis
     */
    private static double overlap(double c, int k) {
        return Math.min(c + HALF, k + 0.5) - Math.max(c - HALF, k - 0.5);
    }

    /**
     * vanilla moves y, then whichever of x/z is bigger, then the other (Direction.axisStepOrder). so a corner can get
     * clipped halfway through a tick even when both ends are clear, and it's the order that decides which corner
     */
    private boolean sweep(double u, double v, double du, double dv) {
        double half = Math.abs(du) > Math.abs(dv) ? clearance(u + du, v)
                : Math.abs(du) < Math.abs(dv) ? clearance(u, v + dv)
                : Math.min(clearance(u + du, v), clearance(u, v + dv)); // a tie goes x first, and we don't know which one u is
        margin = Math.min(margin, Math.min(half, clearance(u + du, v + dv)));
        return margin > 0;
    }

    private double clearance(double u, double v) {
        double min = CAP;
        int ku = (int) Math.floor(u + 0.5);
        int kv = (int) Math.floor(v + 0.5);
        // a box 0.3 from its center can't get within 0.2 of anything further than one block over
        for (int k = ku - 1; k <= ku + 1; k++) {
            for (int j = kv - 1; j <= kv + 1; j++) {
                if (blocked(k, j)) {
                    min = Math.min(min, Math.max(Math.max(k - 0.5 - u - HALF, u - HALF - k - 0.5), Math.max(j - 0.5 - v - HALF, v - HALF - j - 0.5)));
                }
            }
        }
        return min;
    }

    private boolean blocked(int k, int j) {
        if (k < -runway || k > dist + 1 || j < 0 || j > 1) {
            return true;
        }
        if (k == dist + 1) {
            return !openBeyond;
        }
        return j == 0 && k > 0 && k < dist && (walls & 1 << (k - 1)) != 0;
    }
}
