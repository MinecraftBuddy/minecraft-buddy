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
 * A momentum jump, flown tick by tick the way LivingEntity does it. A straight line along the jump: u down it, a lateral v we
 * hold at the centre, y up. Pure math so the planner can ask it things off thread. Same ideas as NeoJump, minus the wall
 */
final class MomentumJump {

    // same numbers as NeoJump, straight out of LivingEntity (same on 1.21.4 and 26.3). aiStep scales move input by 0.98
    private static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, JUMP = 0.42, GRAVITY = 0.08, BOOST = 0.2;
    static final double SPRINT_GROUND = NeoJump.SPRINT_GROUND;
    private static final double AIR_ACCEL = 0.026 * 0.98;
    private static final double HALF = 0.3, CAP = 0.2, EPS = 1e-7, LANE = 0.5 - HALF;
    static final int MAX_DIST = 7, MAX_RUNWAY = 6, MIN_HOP_RUNWAY = 3, MIN_DY = -3, MAX_DY = 1;
    private static final int MAX_TICKS = 30;
    // brake for w ticks starting at tick a, W facing backwards. INF is "until we touch down"
    private static final int INF = 100;
    private static final int[] BRAKE_W = {1, 2, 3, 4, 6, INF}, BRAKE_W_COARSE = {2, 4, INF};
    private static final int MAX_A = 16;
    // how good a standing start has to look before A* is allowed to plan one, and before we commit to the jump
    static final double PLAN_MARGIN = 0.03, JUMP_MARGIN = 0.02, COMFORTABLE = 0.06;
    // where we stand before the run up, measured in from the back of the runway
    // what a clean hop comes down with, vu after the landing tick. a hop that comes down slower than this wobbled
    private static final double HOP_SPEED = 0.25;
    private static final double[] STAGING = {0.3, 0.4, 0.5, 0.6};
    // same idea as NeoJump.PHASE: at 0.28 a tick the takeoff window is narrower than a step, so a run tick can angle off
    // the line for a bit to line a later tick up with the spot
    private static final double[] PHASE = {0, 20, -20, 45, -45};
    // same idea as NeoJump.HOP_PHASE
    private static final double[] HOP_PHASE = {0, 15, -15, 30, -30, 50, -50, 75, -75, 105, -105};
    // bump this whenever the physics, the policies, the staging candidates or any of the margins change. same story as
    // NeoJump.TABLE_VERSION, a stale file hands out answers the sim wouldn't give anymore
    static final int TABLE_VERSION = 1;
    // null means nobody has asked. PENDING is queued because A* asked, RUNNING is somebody computing it right now, NONE is
    // an answer (no run up works), anything else is the answer
    private static final double[] NONE = {}, PENDING = {}, RUNNING = {};
    private static final int PADS = MAX_DIST - 1, DYS = MAX_DY - MIN_DY + 1;
    private static final int TABLE_SIZE = (MAX_RUNWAY + 1) * (MAX_DIST - 1) * PADS * DYS * 2;
    // atomic so a half built answer can't be seen through a data race, and so claiming a key is one cas
    private static final AtomicReferenceArray<double[]> TABLE = newTable();

    /**
     * Where we're going this tick. heading is in the local frame: u down the line, v out to the side. a and w are the
     * brake schedule counted from the jump, age is how many ticks into it we are
     */
    record Plan(double margin, double score, int runup, double headingU, double headingV, int a, int w, int age, int ticks) {}

    /**
     * Where to stand, how many ticks of running before the jump (or hop), the spot to line the takeoff up with, whether
     * to hop on the way, and what the whole thing costs in ticks and clears by
     */
    record Staging(double u, double takeoff, boolean hop, int runup, double ticks, double margin) {}

    // local frame: origin at the center of the block we jump from, u along the jump. the runway is cells -runway..0 (the
    // takeoff block included), then cells 1..dist - 1 are gap except for pad, and dist is the landing, dy up or down from
    // the takeoff floor. after says the cell past the landing is floor at the same height as it. everything else is air
    final int runway, dist, pad, dy;
    final boolean after;
    private final double groundAccel, minFloor;
    // floor top by cell, NaN for nothing
    private final double[] floor;
    // where the sim is right now, so fly and rollout share a tick of physics
    private double pu, pv, pvu, pvv, py, pvy;
    // worst clearance so far, how far we'd slide after landing, ticks of brake used
    private double margin, slide;
    private int brakes;
    // what the last fly came out with
    private double lastMargin, lastTicks;

    MomentumJump(int runway, int dist, int pad, int dy, boolean after, double groundAccel) {
        this.runway = runway;
        this.dist = dist;
        this.pad = pad;
        this.dy = dy;
        this.after = after;
        this.groundAccel = groundAccel;
        this.floor = new double[runway + dist + 2];
        java.util.Arrays.fill(floor, Double.NaN);
        for (int k = -runway; k <= 0; k++) {
            floor[k + runway] = 0;
        }
        if (pad > 0) {
            floor[pad + runway] = 0;
        }
        floor[dist + runway] = dy;
        if (after) {
            floor[dist + 1 + runway] = dy;
        }
        this.minFloor = Math.min(0, dy);
    }

    private double h(int k) {
        return k < -runway || k > dist + 1 ? Double.NaN : floor[k + runway];
    }

    // no jump from further back than this lands, so there's no point asking. it's most of the run up
    double earliest() {
        return Math.max(-runway - 0.77, dist - 6.8);
    }

    // the table

    /**
     * @return where to stand before the run up, null if no run up we know makes it. works it out right now if nobody
     * has yet, which can be a second or two
     */
    static Staging staging(int runway, int dist, int pad, int dy, boolean after) {
        int key = key(runway, dist, pad, dy, after);
        if (key < 0) {
            return null;
        }
        return unpack(resolve(key, runway, dist, pad, dy, after));
    }

    /**
     * Same, except a shape nobody has worked out yet gets worked out in the background and we say no for now. A* asks
     * this, a second long stall the first time it sees a gap would eat the whole search. the first ask of the session
     * also starts on the whole table (see warm)
     */
    static Staging stagingIfKnown(int runway, int dist, int pad, int dy, boolean after) {
        int key = key(runway, dist, pad, dy, after);
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
                QUEUE.addFirst(new Job(key, runway, dist, pad, dy, after));
                ensureWorkers();
            }
            return null;
        }
        return spot == PENDING || spot == RUNNING ? null : unpack(spot);
    }

    // same dance as NeoJump.resolve
    private static double[] resolve(int key, int runway, int dist, int pad, int dy, boolean after) {
        for (; ; ) {
            double[] spot = TABLE.get(key);
            if (spot != null && spot != PENDING && spot != RUNNING) {
                return spot;
            }
            if (spot != RUNNING && TABLE.compareAndSet(key, spot, RUNNING)) {
                double[] result = null;
                try {
                    result = new MomentumJump(runway, dist, pad, dy, after, SPRINT_GROUND).pick();
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
                return new MomentumJump(runway, dist, pad, dy, after, SPRINT_GROUND).pick();
            }
        }
    }

    record Job(int key, int runway, int dist, int pad, int dy, boolean after) {}

    private static final AtomicBoolean WARMED = new AtomicBoolean(), SAVED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ConcurrentLinkedDeque<Job> QUEUE = new ConcurrentLinkedDeque<>();
    private static volatile Path tableFile;

    // where the table lives between launches. MovementMomentum hands it over from the Baritone side. null (the default, and
    // what the tests see) means no saving and no loading
    static void setTableFile(Path file) {
        tableFile = file;
    }

    static AtomicReferenceArray<double[]> newTable() {
        return new AtomicReferenceArray<>(TABLE_SIZE);
    }

    /**
     * Whether plain parkour (or walking) already gets from the takeoff to this landing. then there's nothing to add.
     * MovementParkour: flat 2..4, one up 2..3, and the cell next to us has to be a gap, so dist 1 is a traverse
     */
    static boolean parkourCovers(int dist, int dy) {
        return dy == 0 && dist <= 4 || dy == 1 && dist <= 3;
    }

    // every shape A* is ever allowed to ask about
    static boolean emits(int dist, int pad, int dy) {
        if (pad == 0) {
            return !parkourCovers(dist, dy);
        }
        // a pad chain is only worth it when jumping on from the pad couldn't be done from a standing start, because
        // otherwise it's one jump to the pad and a second movement from it
        return dist - pad > standingReach(dy);
    }

    // how far a jump from a standstill (no runway, runway 0 in the table) gets you at each dy. read off the table, and the test
    // checks it still matches. flat is parkour's 4, one up is parkour's 3
    static int standingReach(int dy) {
        return dy == 1 ? 3 : dy == 0 ? 4 : dy == -3 ? 6 : 5;
    }

    static boolean valid(int runway, int dist, int pad, int dy) {
        return runway >= 0 && runway <= MAX_RUNWAY && dist >= 2 && dist <= MAX_DIST && (pad == 0 || pad >= 2 && pad <= dist - 2)
                && dy >= MIN_DY && dy <= MAX_DY;
    }

    static int key(int runway, int dist, int pad, int dy, boolean after) {
        if (!valid(runway, dist, pad, dy)) {
            return -1;
        }
        return ((((runway * (MAX_DIST - 1) + dist - 2) * PADS + pad) * DYS + dy - MIN_DY) << 1) + (after ? 1 : 0);
    }

    // every key key() accepts and emits() wants, the short runways and the short jumps first
    static List<Job> jobs() {
        List<Job> list = new ArrayList<>();
        for (int runway = 0; runway <= MAX_RUNWAY; runway++) {
            for (int dist = 2; dist <= MAX_DIST; dist++) {
                for (int pad = 0; pad <= dist - 2; pad++) {
                    for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
                        for (int after = 0; after < 2; after++) {
                            if (valid(runway, dist, pad, dy) && emits(dist, pad, dy)) {
                                list.add(new Job(key(runway, dist, pad, dy, after == 1), runway, dist, pad, dy, after == 1));
                            }
                        }
                    }
                }
            }
        }
        list.sort(Comparator.comparingInt((Job j) -> j.pad() > 0 ? 1 : 0).thenComparingInt(Job::runway).thenComparingInt(Job::dist)
                .thenComparingInt(Job::pad).thenComparingInt(Job::dy).thenComparing(Job::after));
        return list;
    }

    // first ask of the session: load whatever the file has, queue up the rest, and get some threads on it. a quarter of the
    // cores, because NeoJump takes half and the game has to keep drawing frames while both go
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
        int cap = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        while (!QUEUE.isEmpty()) {
            int active = ACTIVE.get();
            if (active >= cap) {
                return;
            }
            if (ACTIVE.compareAndSet(active, active + 1)) {
                Thread thread = new Thread(MomentumJump::work, "momentum-warmup");
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
                    resolve(job.key(), job.runway(), job.dist(), job.pad(), job.dy(), job.after());
                } catch (Throwable t) {
                    // resolve already put the key back to unknown. one bad shape shouldn't stop the rest
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
        return "momentum-table version=" + version + " maxDist=" + MAX_DIST + " maxRunway=" + MAX_RUNWAY + " dy=" + MIN_DY + ".." + MAX_DY
                + " planMargin=" + PLAN_MARGIN + " jumpMargin=" + JUMP_MARGIN + " comfortable=" + COMFORTABLE;
    }

    // one line per shape: runway dist pad dy after, then none or the six numbers of a Staging. text because whoever gets
    // confused by it next will want to read it. false if it couldn't be written, which is fine, we do it all again next launch
    static boolean save(Path file, AtomicReferenceArray<double[]> table) {
        StringBuilder out = new StringBuilder(header(TABLE_VERSION)).append('\n');
        for (Job job : jobs()) {
            double[] spot = table.get(job.key());
            if (spot == null || spot == PENDING || spot == RUNNING) {
                continue;
            }
            out.append(job.runway()).append(' ').append(job.dist()).append(' ').append(job.pad()).append(' ').append(job.dy()).append(' ').append(job.after() ? 1 : 0);
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
                int runway = Integer.parseInt(part[0]), dist = Integer.parseInt(part[1]), pad = Integer.parseInt(part[2]);
                int dy = Integer.parseInt(part[3]), after = Integer.parseInt(part[4]);
                int key = after < 0 || after > 1 ? -1 : key(runway, dist, pad, dy, after == 1);
                if (key < 0) {
                    return 0;
                }
                double[] spot;
                if (part.length == 6 && part[5].equals("none")) {
                    spot = NONE;
                } else if (part.length == 11) {
                    spot = new double[6];
                    for (int k = 0; k < 6; k++) {
                        spot[k] = Double.parseDouble(part[5 + k]);
                        if (Double.isNaN(spot[k]) || Double.isInfinite(spot[k])) {
                            return 0;
                        }
                    }
                    if (spot[2] != 0 && spot[2] != 1 || spot[3] < 0 || spot[3] != Math.rint(spot[3])) {
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

    static Staging unpack(double[] spot) {
        return spot == NONE ? null : new Staging(spot[0], spot[1], spot[2] != 0, (int) spot[3], spot[4], spot[5]);
    }

    // the table's answer for each strategy, so the tests and the experiments can see both
    double plainMargin = Double.NaN, hopMargin = Double.NaN;
    // the staging pick() took, whichever kind
    Staging picked;

    double[] pick() {
        double[] plain = NONE, hop = NONE;
        plainMargin = hopMargin = Double.NaN;
        double takeoff = takeoff();
        if (!Double.isNaN(takeoff)) {
            for (double candidate : STAGING) {
                double u = candidate - runway - 0.5;
                double margin = rollout(u, takeoff);
                if (!Double.isNaN(margin) && (margin > plainMargin || Double.isNaN(plainMargin))) {
                    plainMargin = margin;
                    plain = new double[]{u, takeoff, 0, rolloutRunup, rolloutTicks, margin};
                }
            }
        }
        // a hop needs room, and most runways don't have it
        double hopTakeoff = runway >= MIN_HOP_RUNWAY ? hopTakeoff() : Double.NaN;
        if (!Double.isNaN(hopTakeoff)) {
            for (double candidate : STAGING) {
                double u = candidate - runway - 0.5;
                double margin = rolloutHop(u, hopTakeoff);
                if (!Double.isNaN(margin) && (margin > hopMargin || Double.isNaN(hopMargin))) {
                    hopMargin = margin;
                    hop = new double[]{u, hopTakeoff, 1, rolloutRunup, rolloutTicks, margin};
                }
            }
        }
        boolean plainOk = plainMargin >= PLAN_MARGIN, hopOk = hopMargin >= PLAN_MARGIN;
        // the hop is more moving parts (two jumps, two takeoff windows), so it only gets used when the plain run up
        // can't make it or only just can. same call as NeoJump.pick
        double[] answer = plainOk && (plainMargin >= COMFORTABLE || !hopOk || plainMargin >= hopMargin) ? plain : hopOk ? hop : NONE;
        picked = unpack(answer);
        return answer;
    }

    // the physics: the same tick LivingEntity runs, one axis along the line plus the sideways drift

    private static final int FLYING = 0, LAND_FINAL = 1, LAND_PAD = 2, LAND_STRIP = 3, FAILED = 4;

    /**
     * One tick on the ground holding W toward (c, s). false if we ran off the edge before jumping
     */
    private boolean run(double c, double s) {
        double du = pvu + groundAccel * c;
        double dv = pvv + groundAccel * s;
        double nu = pu + du, nv = pv + dv;
        // the runway and the takeoff block are one strip of floor from -runway - 0.5 to 0.5. we fall off at 0 overlap,
        // 0.03 is for being a hair off where we think we are
        if (nu > 0.77 || nu < -runway - 0.77 || overlap(nv, 0) < 0.03 || Math.abs(nv) > LANE - 0.03) {
            return false; // walked off the edge before jumping, nice one
        }
        pu = nu;
        pv = nv;
        pvu = du * GROUND_FRICTION;
        pvv = dv * GROUND_FRICTION;
        return true;
    }

    /**
     * One tick in the air holding W toward (c, s), or the jump tick itself
     */
    private int air(double c, double s, boolean jumpTick) {
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
        double yOld = py;
        py += pvy;
        double support = Double.NaN;
        int cell = 0;
        // the highest floor anywhere under the box, for telling a ledge from more of the same floor
        double under = Double.NEGATIVE_INFINITY;
        int flo = (int) Math.floor(pu - HALF + 0.5), fhi = (int) Math.floor(pu + HALF + 0.5);
        for (int k = flo; k <= fhi; k++) {
            double hk = h(k);
            if (Double.isNaN(hk) || overlap(pu, k) <= 0) {
                continue;
            }
            under = Math.max(under, hk);
            if (pvy < 0 && overlap(pv, 0) > 0 && hk <= yOld + EPS && (Double.isNaN(support) || hk > support)) {
                support = hk;
                cell = k;
            }
        }
        boolean landed = !Double.isNaN(support) && py <= support;
        if (landed) {
            py = support;
        } else if (py < minFloor - EPS) {
            return FAILED; // below every floor there is, and anything we fly into now is a wall
        }
        double nu = pu + du;
        int lo = Math.max(-runway, (int) Math.floor(Math.min(pu, nu) - HALF - CAP + 0.5));
        int hi = Math.min(dist + 1, (int) Math.floor(Math.max(pu, nu) + HALF + CAP + 0.5));
        for (int k = lo; k <= hi; k++) {
            double hk = h(k);
            if (Double.isNaN(hk)) {
                continue;
            }
            double sepNew = sep(nu, k), sepOld = sep(pu, k);
            if (hk > py + EPS) {
                // a floor above our feet is a wall
                if (sepNew <= 0 || sepOld <= 0) {
                    return FAILED;
                }
                margin = Math.min(margin, Math.min(sepNew, sepOld));
            } else if (sepNew < 0 && sepOld >= 0 && hk > under + EPS) {
                // first tick over a floor higher than what we were over: how far above it are we. sliding from one
                // strip cell onto the next at the same height is nothing (and landing there is a zero, which killed every hop)
                margin = Math.min(margin, py - hk);
            }
        }
        if (margin <= 0) {
            return FAILED;
        }
        double puPre = pu, pvPre = pv;
        pu = nu;
        pv += dv;
        // a 1 wide lane with a wall on each side: past 0.2 off centre the box touches one. nobody checks the sides, so we
        // just never go there
        margin = Math.min(margin, LANE - Math.abs(pv));
        if (margin <= 0) {
            return FAILED;
        }
        if (landed) {
            double slideU = pu + du * AIR_FRICTION / (1 - GROUND_FRICTION);
            double slideV = pv + dv * AIR_FRICTION / (1 - GROUND_FRICTION);
            double lateral = overlap(pvPre, 0);
            pvu = du * AIR_FRICTION;
            pvv = dv * AIR_FRICTION;
            pvy = 0;
            if (cell <= 0) {
                margin = Math.min(margin, Math.min(overlap(puPre, cell), lateral));
                return margin > 0 ? LAND_STRIP : FAILED;
            }
            if (cell == pad) {
                margin = Math.min(margin, Math.min(overlap(puPre, cell), lateral));
                return margin > 0 ? LAND_PAD : FAILED;
            }
            if (cell != dist && cell != dist + 1) {
                return FAILED; // there's nothing else to land on
            }
            // where we'd slide to with W let go (v / (1 - 0.546)). coming in hot usually leaves our feet hanging off the
            // edge of the landing, which is fine as long as the slide doesn't take us off it
            double span = after ? 2 : 1;
            double slideSupportU = Math.min(slideU + HALF, dist - 0.5 + span) - Math.max(slideU - HALF, dist - 0.5);
            margin = Math.min(margin, Math.min(Math.min(overlap(puPre, dist), lateral), Math.min(slideSupportU, overlap(slideV, 0))));
            slide = Math.sqrt((slideU - dist) * (slideU - dist) + slideV * slideV);
            return margin > 0 ? LAND_FINAL : FAILED;
        }
        pvu = du * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvv = dv * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvy = (pvy - GRAVITY) * 0.98;
        return FLYING;
    }

    /**
     * How much a box centered at c overlaps the cell centered at k on one axis
     */
    private static double overlap(double c, int k) {
        return Math.min(c + HALF, k + 0.5) - Math.max(c - HALF, k - 0.5);
    }

    // gap between a box centered at c and cell k, negative if they overlap
    private static double sep(double c, int k) {
        return Math.max(k - 0.5 - (c + HALF), c - HALF - (k + 0.5));
    }

    private double stripOverlap(double c) {
        return Math.min(c + HALF, 0.5) - Math.max(c - HALF, -runway - 0.5);
    }

    // planning: jump now, or run a tick first, and what to do in the air once we're up

    /**
     * Best way to go from here, jumping now or (on the ground) after at most maxRunup ticks of running. null if nothing
     * we know how to do lands it. in the air, pass last tick's plan: it gets carried on one tick and kept unless
     * something beats it, same reason as NeoJump.plan
     */
    Plan plan(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int maxRunup, Plan previous) {
        Plan best = null;
        for (int runup = 0; runup <= (onGround ? maxRunup : 0); runup++) {
            double score = search(u, v, vu, vv, y, vy, onGround, runup, 0);
            if (!Double.isNaN(score) && (best == null || score > best.score())) {
                if (runup > 0) {
                    double side = NeoJump.side(v, vv, 0, NeoJump.RUN_GAIN, groundAccel);
                    best = new Plan(bMargin[0], score, runup, Math.sqrt(1 - side * side), side, 0, 0, 0, bTicks[0]);
                } else {
                    best = new Plan(bMargin[0], score, 0, bHeadingU[0], bHeadingV[0], bA[0], bW[0], 0, bTicks[0]);
                }
            }
        }
        if (!onGround && previous != null && previous.runup() == 0) {
            int age = previous.age() + 1;
            double score = fly(u, v, vu, vv, y, vy, false, 0, previous.a(), previous.w(), age, 0);
            if (!Double.isNaN(score) && (best == null || score >= best.score())) {
                best = new Plan(lastMargin, score, 0, fHeadingU[0], fHeadingV[0], previous.a(), previous.w(), age, (int) lastTicks);
            }
        }
        return best;
    }

    // best of the whole brake grid, per depth since a flight onto a pad searches the next flight inside itself
    private final double[] bScore = new double[2], bMargin = new double[2], bHeadingU = new double[2], bHeadingV = new double[2];
    private final int[] bA = new int[2], bW = new int[2], bTicks = new int[2];

    private double search(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int runup, int depth) {
        double best = Double.NaN;
        int[] ws = depth == 0 ? BRAKE_W : BRAKE_W_COARSE;
        int step = depth == 0 ? 1 : 2;
        for (int a = 0; a <= MAX_A; a += step) {
            // w = 0 is the same schedule for every a, so one of those is plenty
            for (int wi = a == 0 ? -1 : 0; wi < ws.length; wi++) {
                int w = wi < 0 ? 0 : ws[wi];
                double score = fly(u, v, vu, vv, y, vy, onGround, runup, a, w, 0, depth);
                if (!Double.isNaN(score) && (Double.isNaN(best) || score > best)) {
                    best = score;
                    bScore[depth] = score;
                    bMargin[depth] = lastMargin;
                    bTicks[depth] = (int) lastTicks;
                    bHeadingU[depth] = fHeadingU[depth];
                    bHeadingV[depth] = fHeadingV[depth];
                    bA[depth] = a;
                    bW[depth] = w;
                }
            }
        }
        return best;
    }

    // the heading fly used on its first tick, per depth
    private final double[] fHeadingU = new double[2], fHeadingV = new double[2];

    /**
     * Fly one schedule from this state: run up a few ticks if on the ground, then jump (or keep flying) with W forward
     * except for w ticks of brake starting a ticks after the jump
     *
     * @return how good this went, NaN if we hit something, fell, or came down anywhere but where we're going. margin
     * first, the slide and the brake only break ties
     */
    private double fly(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int runup, int a, int w, int age, int depth) {
        margin = CAP;
        slide = 0;
        brakes = 0;
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        for (int i = 0; i < runup; i++) {
            double runS = NeoJump.side(pv, pvv, 0, NeoJump.RUN_GAIN, groundAccel);
            if (!run(Math.sqrt(1 - runS * runS), runS)) {
                return Double.NaN;
            }
        }
        boolean jumpTick = onGround;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            int now = tick + age;
            boolean brake = now >= a && now < a + w;
            // hold the centre. the jump boost goes the way we face too, so on the jump tick it counts as acceleration
            double s = NeoJump.side(pv, pvv, 0, NeoJump.RUN_GAIN, jumpTick ? BOOST + groundAccel : AIR_ACCEL);
            double c = Math.sqrt(1 - s * s);
            if (brake) {
                // facing backwards with W held is braking, sprint doesn't care which way we face
                c = -c;
                brakes++;
            }
            if (tick == 0) {
                fHeadingU[depth] = c;
                fHeadingV[depth] = s;
            }
            int result = air(c, s, jumpTick);
            if (result == FAILED || result == LAND_STRIP) {
                return Double.NaN;
            }
            if (result == LAND_FINAL) {
                lastMargin = margin;
                lastTicks = runup + tick + 1;
                return margin - 0.005 * slide - 0.001 * runup - 0.0004 * brakes;
            }
            if (result == LAND_PAD) {
                if (depth > 0) {
                    return Double.NaN; // one pad, and the nested search is only ever one deep
                }
                // the next tick we jump again off the speed we landed with. same land to jump boundary as rolloutHop
                double own = margin, ownTicks = runup + tick + 1;
                int ownBrakes = brakes;
                double score = search(pu, pv, pvu, pvv, py, pvy, true, 0, depth + 1);
                if (Double.isNaN(score)) {
                    return Double.NaN;
                }
                lastMargin = Math.min(own, bMargin[depth + 1]);
                lastTicks = ownTicks + bTicks[depth + 1];
                return Math.min(own, score) - 0.0004 * ownBrakes;
            }
            jumpTick = false;
        }
        return Double.NaN;
    }

    // before the jump: run up, hop

    /**
     * The best spot along the line to jump from at full speed, NaN if there isn't one
     */
    double takeoff() {
        double full = groundAccel * GROUND_FRICTION / (1 - GROUND_FRICTION);
        double spot = Double.NaN, best = 0;
        for (double u = earliest(); u <= 0.77; u += 0.02) {
            Plan plan = plan(u, 0, full, 0, 0, 0, true, 0, null);
            // later along the line wins a tie (or near enough, 0.005 of margin is noise). on a plateau any spot is fine and
            // the last one is the one a short run up can still hit
            if (plan != null && plan.margin() > 0 && plan.margin() >= best - 0.005) {
                best = Math.max(best, plan.margin());
                spot = u;
            }
        }
        return spot;
    }

    /**
     * Heading for one tick of run up (into the return value): hold the centre, and angle off a bit if that makes some later
     * tick land closer to the takeoff spot. same as NeoJump.runTick
     */
    double[] runTick(double u, double v, double vu, double vv, double takeoff) {
        double base = Math.asin(NeoJump.side(v, vv, 0, NeoJump.RUN_GAIN, groundAccel));
        double bestError = Double.POSITIVE_INFINITY, bestC = Math.cos(base), bestS = Math.sin(base);
        for (double off : PHASE) {
            double c = Math.cos(base + Math.toRadians(off)), s = Math.sin(base + Math.toRadians(off));
            pu = u;
            pv = v;
            pvu = vu;
            pvv = vv;
            if (!run(c, s)) {
                continue;
            }
            // then run straight and see how close the closest tick gets
            double error = Double.POSITIVE_INFINITY;
            for (int k = 0; k < 20 && pu < takeoff + 0.3; k++) {
                error = Math.min(error, Math.abs(pu - takeoff));
                double hold = NeoJump.side(pv, pvv, 0, NeoJump.RUN_GAIN, groundAccel);
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
        return new double[]{bestC, bestS};
    }

    private int rolloutRunup;
    private double rolloutTicks;

    /**
     * Play the whole thing out the way MovementMomentum will: stand still at u, run, jump when plan says jumping now
     * beats jumping next tick, replan every tick in the air, land on the pad and jump again the next tick.
     * same as NeoJump.rollout, and this is what decides if A* gets to use the shape
     *
     * @return the worst clearance along the way, NaN if it doesn't make it
     */
    double rollout(double u, double takeoff) {
        double ru = u, rv = 0, rvu = 0, rvv = 0;
        rolloutRunup = 0;
        rolloutTicks = 0;
        for (int tick = 0; tick < 4 * MAX_RUNWAY + 30; tick++) {
            Plan plan = ru > earliest() ? plan(ru, rv, rvu, rvv, 0, 0, true, 1, null) : null;
            double c, s;
            if (plan != null && plan.margin() >= JUMP_MARGIN) {
                if (plan.runup() == 0) {
                    rolloutTicks += tick;
                    return finish(ru, rv, rvu, rvv, plan, CAP);
                }
                c = plan.headingU();
                s = plan.headingV();
            } else {
                // nothing a tick out lands it yet, so keep running and line up with the takeoff spot
                double[] heading = runTick(ru, rv, rvu, rvv, takeoff);
                c = heading[0];
                s = heading[1];
            }
            pu = ru;
            pv = rv;
            pvu = rvu;
            pvv = rvv;
            if (!run(c, s)) {
                return Double.NaN;
            }
            rolloutRunup++;
            ru = pu;
            rv = pv;
            rvu = pvu;
            rvv = pvv;
        }
        return Double.NaN;
    }

    /**
     * From the tick of the first jump, with the plan for it: fly the whole way down. every landing on the pad is a
     * jump the very next tick
     */
    private double finish(double u, double v, double vu, double vv, Plan plan, double worst) {
        boolean ground = true;
        double y = 0, vy = 0;
        for (int tick = 0; tick < 3 * MAX_TICKS; tick++) {
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
            rolloutTicks++;
            if (result == FAILED || result == LAND_STRIP) {
                return Double.NaN;
            }
            if (result == LAND_FINAL) {
                return margin;
            }
            ground = result == LAND_PAD;
            worst = margin;
            u = pu;
            v = pv;
            vu = pvu;
            vv = pvv;
            y = py;
            vy = pvy;
            plan = plan(u, v, vu, vv, y, vy, ground, 0, ground ? null : plan);
        }
        return Double.NaN;
    }

    // the hop: a sprint jump on the runway a few blocks before the takeoff that comes down on the runway, and the jump
    // goes in on the very next tick off the speed the hop landed with. same as NeoJump's, one axis at a time

    /**
     * The best spot along the line to jump from right after a hop lands, NaN if there isn't one (or the hop itself can't
     * be done)
     */
    double hopTakeoff() {
        // the speed a hop lands with doesn't care where on the runway it was, so fly one down a runway with all the room
        // in the world and look at what it comes down with
        MomentumJump far = new MomentumJump(20, dist, pad, dy, after, groundAccel);
        far.pu = -8;
        far.pv = 0;
        far.pvu = groundAccel * GROUND_FRICTION / (1 - GROUND_FRICTION);
        far.pvv = 0;
        far.py = far.pvy = 0;
        far.margin = CAP;
        if (!far.hop(true, Double.NaN)) {
            return Double.NaN;
        }
        double vu = far.pvu, vv = far.pvv;
        // not a spot but the start of a window: the jump gets better the further along we come down (up to the edge), and a
        // hop that wobbles to hit one exact spot comes down slow, which is worth more than the spot. so the window starts
        // where the margin is most of what it'll ever be
        double[] margins = new double[(int) ((0.77 - earliest()) / 0.02) + 1];
        double best = 0;
        for (int i = 0; i < margins.length; i++) {
            Plan plan = plan(earliest() + 0.02 * i, 0, vu, vv, 0, 0, true, 0, null);
            margins[i] = plan == null ? 0 : plan.margin();
            best = Math.max(best, margins[i]);
        }
        if (best <= 0) {
            return Double.NaN;
        }
        double threshold = Math.min(best, Math.max(2 * PLAN_MARGIN, 0.6 * best));
        for (int i = 0; i < margins.length; i++) {
            if (margins[i] >= threshold) {
                return earliest() + 0.02 * i;
            }
        }
        return Double.NaN;
    }

    /**
     * One run up tick before a hop: {headingU, headingV, 1 if this is the tick to jump, else 0}. we jump when
     * jumping now lands closer to the takeoff than jumping a tick from now would. null if neither lands on the runway
     */
    double[] hopRunTick(double u, double v, double vu, double vv, double takeoff) {
        double side = NeoJump.side(v, vv, 0, NeoJump.RUN_GAIN, groundAccel);
        double c = Math.sqrt(1 - side * side);
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = pvy = 0;
        margin = CAP;
        double now = hop(true, takeoff) ? Math.abs(pu - takeoff) : Double.POSITIVE_INFINITY;
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = pvy = 0;
        margin = CAP;
        double next = Double.POSITIVE_INFINITY;
        if (run(c, side)) {
            py = pvy = 0;
            next = hop(true, takeoff) ? Math.abs(pu - takeoff) : Double.POSITIVE_INFINITY;
        }
        if (now == Double.POSITIVE_INFINITY && next == Double.POSITIVE_INFINITY) {
            return null;
        }
        if (now <= next) {
            // hop() trashed the heading it found for the jump tick, so ask for it again
            return hopTick(u, v, vu, vv, 0, 0, true, takeoff, 1);
        }
        return new double[]{c, side, 0};
    }

    /**
     * Heading for one tick of the hop, same shape as hopRunTick's answer. null if no heading survives
     */
    double[] hopTick(double u, double v, double vu, double vv, double y, double vy, boolean jumpTick, double takeoff, int jump) {
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        margin = CAP;
        double[] heading = hopHeading(jumpTick, takeoff);
        return heading == null ? null : new double[]{heading[0], heading[1], jump};
    }

    double[] hopTick(double u, double v, double vu, double vv, double y, double vy, double takeoff) {
        return hopTick(u, v, vu, vv, y, vy, false, takeoff, 0);
    }

    /**
     * Picks this tick's heading from the state in p*, which it leaves alone. each candidate is tried for one tick and then
     * flown straight to touchdown, and whichever comes down nearest takeoff wins
     */
    private double[] hopHeading(boolean jumpTick, double takeoff) {
        double u = pu, v = pv, vu = pvu, vv = pvv, y = py, vy = pvy, m = margin;
        double accel = jumpTick ? BOOST + groundAccel : AIR_ACCEL;
        double base = Math.asin(NeoJump.side(v, vv, 0, NeoJump.RUN_GAIN, accel));
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
                int result = air(c, s, jumpTick);
                boolean landed = result == LAND_STRIP;
                if (result == FAILED || result == LAND_FINAL || result == LAND_PAD) {
                    continue;
                }
                for (int k = 0; !landed && k < MAX_TICKS; k++) {
                    double hold = NeoJump.side(pv, pvv, 0, NeoJump.RUN_GAIN, AIR_ACCEL);
                    result = air(Math.sqrt(1 - hold * hold), hold, false);
                    if (result == FAILED || result == LAND_FINAL || result == LAND_PAD) {
                        break;
                    }
                    landed = result == LAND_STRIP;
                }
                if (!landed) {
                    continue;
                }
                // landing short of the window costs, landing in it is free. what a wobble costs us is speed, so that counts too
                double error = (Double.isNaN(takeoff) ? 0 : Math.max(0, takeoff - pu)) + 0.5 * Math.max(0, HOP_SPEED - pvu);
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
        return bestError < Double.POSITIVE_INFINITY ? new double[]{bestC, bestS} : null;
    }

    /**
     * A whole hop from the state in p* (on the ground if jump, else mid air) to touchdown, steering the way the runtime will
     *
     * @return false if it hits something or doesn't come down on the runway
     */
    private boolean hop(boolean jump, double takeoff) {
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double[] heading = hopHeading(jump, takeoff);
            if (heading == null) {
                return false;
            }
            int result = air(heading[0], heading[1], jump);
            if (result == FAILED || result == LAND_FINAL || result == LAND_PAD) {
                return false;
            }
            if (result == LAND_STRIP) {
                return true;
            }
            jump = false;
        }
        return false;
    }

    /**
     * rollout, for the run up that hops into the takeoff: run, hop, and jump the first flight the tick we land
     */
    double rolloutHop(double u, double takeoff) {
        double ru = u, rv = 0, rvu = 0, rvv = 0, ry = 0, rvy = 0, worst = CAP;
        boolean airborne = false;
        rolloutRunup = 0;
        rolloutTicks = 0;
        for (int tick = 0; tick < 8 * MAX_RUNWAY + 20; tick++) {
            double[] heading = airborne ? hopTick(ru, rv, rvu, rvv, ry, rvy, takeoff) : hopRunTick(ru, rv, rvu, rvv, takeoff);
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
            rolloutTicks++;
            if (!airborne && !jump) {
                if (!run(heading[0], heading[1])) {
                    return Double.NaN;
                }
                rolloutRunup++;
            } else {
                int result = air(heading[0], heading[1], jump);
                if (result == FAILED || result == LAND_FINAL || result == LAND_PAD) {
                    return Double.NaN;
                }
                airborne = true;
                if (result == LAND_STRIP) {
                    // touchdown, and the very next tick is the first jump
                    // plan() is scratch work on the same fields, so grab where we landed first
                    double lu = pu, lv = pv, lvu = pvu, lvv = pvv, landMargin = margin;
                    Plan plan = plan(lu, lv, lvu, lvv, 0, 0, true, 0, null);
                    return plan == null || plan.margin() < JUMP_MARGIN ? Double.NaN : finish(lu, lv, lvu, lvv, plan, landMargin);
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
}
