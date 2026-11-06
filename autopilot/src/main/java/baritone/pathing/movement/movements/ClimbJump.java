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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

// jumping onto ladders and vines and off of them, flown tick by tick the way LivingEntity does it. pure math so the
// planner can ask it things off thread. same idea as NeoJump, except this one is 2D (u along the jump, y up) because
// the jumps are dead straight and v never leaves 0
final class ClimbJump {

    // straight out of LivingEntity (same on 1.21.4 and 26.3), same numbers NeoJump uses
    private static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, JUMP = 0.42, GRAVITY = 0.08, DRAG = 0.98;
    private static final double BOOST = 0.2, INPUT = 0.98, AIR_WALK = 0.02, AIR_SPRINT = 0.026;
    static final double SPRINT_BONUS = 1.3;
    // handleOnClimbable clamps to this, and holding space (or bumping a wall) while climbing sets vy to CLIMB_UP
    private static final double CLIMB_CAP = 0.15, CLIMB_UP = 0.2;
    private static final double HALF = 0.3, HEIGHT = 1.8, EPS = 1e-7, BUMP = 1e-5, LADDER = 3 / 16.0;
    static final double WALK_SPEED = 0.1;

    // what a cell is. the ladder boxes sit on the side they hang from: the one we grab hangs on the far wall (+u), the one
    // we leap from hangs on the near wall (-u). vines have no collision at all and just count as climbable
    static final byte AIR = 0, SOLID = 1, VINE = 2, LADDER_FAR = 3, LADDER_NEAR = 4;
    // kinds, for the shape. FLOOR is only a destination
    static final int KIND_LADDER = 0, KIND_VINE = 1, KIND_FLOOR = 2;
    // what's under (or above) a climbable cell in its column
    static final int BELOW_AIR = 0, BELOW_CLIMB = 1, BELOW_SOLID = 2;

    // key to everything. dy is where dest sits relative to src. a grab goes from a floor to a climbable, a leap from a
    // climbable to a floor or another climbable. runway and sprint are for grabs only
    record Shape(boolean grab, int dist, int dy, int srcKind, int destKind, int destBelow, int destAbove,
                 boolean srcBelow, int srcAbove, boolean wall, int runway, boolean sprint) {}

    // us is where a grab starts running from, ok is whether the closed loop made it from there, margin is how robust
    record Result(boolean ok, double us, double margin) {}

    static final int PUSH = 1, COAST = 0, BRAKE = -1;
    // input(t): i1 until s1, i2 until s2, then i3. space is held for the first hold ticks (leaps climbing out)
    record Sched(int hold, int i1, int s1, int i2, int s2, int i3) {
        int input(int t) {
            return t < s1 ? i1 : t < s2 ? i2 : i3;
        }
    }

    record Plan(double score, int robust, int runup, Sched sched, int age) {}

    // what to press this tick
    record Action(int input, boolean jump, int status) {}

    // stage is a leap that doesn't like where it's hanging yet
    static final int GO = 0, DONE = 1, FAIL = 2, STAGE = 3;

    static final class Control {
        Plan plan;
        int stay;
        boolean launched;

        Control(boolean launched) {
            this.launched = launched;
        }
    }

    static final int MAX_RUNWAY = 3, MAX_DIST = 4, MAX_RUNUP = 30;
    private static final int MAX_TICKS = 28, STAY = 5;
    // how many of the five perturbed copies of a schedule have to survive before we call it a plan. A* asks for more
    static final int PLAN_ROBUST = 4, GO_ROBUST = 3;
    private static final double SHAKE = 0.03;
    // what vy is on any tick where we stood still: gravity pulled, the floor (or the sneak on a ladder) pushed back
    static final double STAND = -GRAVITY * DRAG;

    private static final Sched[] GRAB_FAMILY = grabFamily(), LEAP_FAMILY = leapFamily(), AIR_FAMILY = airFamily();

    private static Sched[] grabFamily() {
        return family(new int[]{0}, 10, new int[]{0, 2, 4, 6, 9});
    }

    // holding space is how a leap gets up in the air at all, so how long is half the schedule
    private static Sched[] leapFamily() {
        return family(new int[]{0, 2, 3, 4, 5, 6, 8, 11}, 12, new int[]{0, 2, 4, 7});
    }

    private static Sched[] airFamily() {
        return family(new int[]{0}, 12, new int[]{0, 2, 4, 7});
    }

    private static Sched[] family(int[] holds, int maxS1, int[] gaps) {
        List<Sched> list = new ArrayList<>();
        for (int hold : holds) {
            for (int s1 = 0; s1 <= maxS1; s1++) {
                for (int gap : gaps) {
                    if (gap == 0) {
                        // no middle phase, so i2 doesn't exist. just the end
                        for (int i3 : new int[]{PUSH, COAST, BRAKE}) {
                            if (i3 != PUSH || s1 == 0) {
                                list.add(new Sched(hold, PUSH, s1, COAST, s1, i3));
                            }
                        }
                        continue;
                    }
                    for (int i2 : new int[]{COAST, BRAKE}) {
                        for (int i3 : new int[]{PUSH, COAST, BRAKE}) {
                            if (i3 != i2) {
                                list.add(new Sched(hold, PUSH, s1, i2, s1 + gap, i3));
                            }
                        }
                    }
                }
            }
        }
        return list.toArray(new Sched[0]);
    }

    private static final ConcurrentHashMap<Shape, Result> TABLE = new ConcurrentHashMap<>();
    private static final Result PENDING = new Result(false, 0, 0);

    // works it out right now if nobody has yet, which is a second or so for a grab
    static Result lookup(Shape shape) {
        Result result = TABLE.get(shape);
        if (result == null || result == PENDING) {
            result = solve(shape);
            TABLE.put(shape, result);
        }
        return result;
    }

    // same, except a shape nobody has worked out yet gets worked out in the background and we say nothing for now. A*
    // asks this, a stall the first time it sees a ladder would eat the whole search. the next path gets it
    static Result lookupIfKnown(Shape shape) {
        Result result = TABLE.get(shape);
        if (result == null) {
            if (TABLE.putIfAbsent(shape, PENDING) == null) {
                ForkJoinPool.commonPool().execute(() -> {
                    Result solved = new Result(false, 0, 0);
                    try {
                        solved = solve(shape);
                    } finally {
                        // a sim that blows up is a shape that doesn't work, not a shape that's pending until the heat death
                        TABLE.put(shape, solved);
                    }
                });
            }
            return null;
        }
        return result == PENDING ? null : result;
    }

    private static Result solve(Shape shape) {
        ClimbJump sim = new ClimbJump(shape, WALK_SPEED);
        if (!shape.grab()) {
            return sim.solveLeap();
        }
        return sim.solveGrab();
    }

    // grabs start from rest somewhere on the runway. the back of it is where the run is longest, the rest is what the
    // run up looks like when the floor behind us is shorter than we'd like
    private Result solveGrab() {
        double back = -shape.runway() - 0.2;
        Result best = new Result(false, back, 0);
        for (double us = back; us <= 0.2; us += 0.25) {
            double margin = Math.min(rollout(us, 0, 0, STAND, false), rollout(us + SHAKE, 0, 0, STAND, false));
            if (!Double.isNaN(margin) && margin >= PLAN_ROBUST && (!best.ok() || margin > best.margin())) {
                best = new Result(true, us, margin);
            }
        }
        return best;
    }

    // a leap starts from wherever in the cell we are, hanging still (sneaked) or climbing into the wall. up to the middle
    // of the cell is what the movement waits for. pressed up against the wall is the worst case, being out in the cell has
    // more room to run, so that's the one that's checked
    private Result solveLeap() {
        double u0 = hugU();
        double worst = Double.POSITIVE_INFINITY;
        for (boolean climbing : new boolean[]{false, true}) {
            for (double fy : new double[]{0.05, 0.3, 0.5}) {
                for (double du : new double[]{0, SHAKE}) {
                    double margin = rollout(u0 + du, fy, 0, climbing ? CLIMB_UP * DRAG - GRAVITY * DRAG : STAND, climbing);
                    if (Double.isNaN(margin)) {
                        return new Result(false, u0, 0);
                    }
                    worst = Math.min(worst, margin);
                }
            }
        }
        return new Result(worst >= PLAN_ROBUST, u0, worst);
    }
    // where we end up pressed against the wall behind us. a ladder is thin, a vine's wall is a whole block
    double hugU() {
        return shape.srcKind() == KIND_LADDER ? -0.5 + LADDER + HALF : -0.5 + HALF;
    }

    // ---- the world. cells[k + COL0][r + ROW0], k along the jump from src, r up from src's feet

    private static final int COL0 = 6, ROW0 = 5, COLS = 16, ROWS = 14;
    private final byte[][] cells = new byte[COLS][ROWS];
    final Shape shape;
    private final double baseSpeed;
    private final int destRow;
    private final boolean climbDest;

    ClimbJump(Shape shape, double baseSpeed) {
        this.shape = shape;
        this.baseSpeed = baseSpeed;
        this.destRow = shape.dy();
        this.climbDest = shape.destKind() != KIND_FLOOR;
        build();
    }

    private void set(int k, int r, byte type) {
        cells[k + COL0][r + ROW0] = type;
    }

    private byte get(int k, int r) {
        int c = k + COL0, row = r + ROW0;
        return c < 0 || c >= COLS || row < 0 || row >= ROWS ? AIR : cells[c][row];
    }

    private void build() {
        int dist = shape.dist();
        if (shape.grab()) {
            for (int k = -shape.runway(); k <= 0; k++) {
                set(k, -1, SOLID);
            }
        } else {
            // the wall we hang from, the whole column of it
            for (int r = -4; r <= 4; r++) {
                set(-1, r, SOLID);
            }
            byte here = shape.srcKind() == KIND_LADDER ? LADDER_NEAR : VINE;
            set(0, 0, here);
            if (shape.srcBelow()) {
                set(0, -1, here);
            }
            for (int r = 1; r <= shape.srcAbove(); r++) {
                set(0, r, here);
            }
        }
        if (climbDest) {
            byte there = shape.destKind() == KIND_LADDER ? LADDER_FAR : VINE;
            set(dist, destRow, there);
            switch (shape.destBelow()) {
                case BELOW_CLIMB -> set(dist, destRow - 1, there);
                case BELOW_SOLID -> set(dist, destRow - 1, SOLID);
                default -> {}
            }
            for (int r = 1; r <= shape.destAbove(); r++) {
                set(dist, destRow + r, there);
            }
            if (shape.wall() || shape.destKind() == KIND_LADDER) {
                for (int r = destRow - 1; r <= destRow + 3; r++) {
                    set(dist + 1, r, SOLID);
                }
            }
        } else {
            set(dist, destRow - 1, SOLID);
            if (shape.wall()) {
                for (int r = destRow; r <= destRow + 3; r++) {
                    set(dist + 1, r, SOLID);
                }
            }
        }
    }

    // ---- the state. what the last step left us in, and what the next one starts from

    private double pu, py, pvu, pvy;
    private boolean pGround, pSprint, pBump;

    private void load(double u, double y, double vu, double vy, boolean ground, boolean sprint, boolean bump) {
        pu = u;
        py = y;
        pvu = vu;
        pvy = vy;
        pGround = ground;
        pSprint = sprint;
        pBump = bump;
    }

    private boolean climbable(double u, double y) {
        byte type = get((int) Math.floor(u + 0.5), (int) Math.floor(y));
        return type == VINE || type == LADDER_FAR || type == LADDER_NEAR;
    }

    // one tick of LivingEntity.travel plus the bits of LocalPlayer.aiStep that decide whether we're sprinting. input is
    // which way W points (along the jump, against it, or not held at all), space is the jump key
    private void step(int input, boolean space, boolean wantSprint) {
        // 26.3 would start a sprint in the air too, this model only starts one from the ground, so a leap is planned a bit
        // slower than it can really go. W has to be held, a bump stops it, and letting go of W stops it too
        boolean sprint = input != COAST && (pSprint || wantSprint && pGround) && !pBump;
        boolean ground = pGround;
        double vu = pvu, vy = pvy;
        if (space && ground) {
            vy = JUMP;
            if (sprint) {
                vu += BOOST * (input == 0 ? 1 : input);
            }
        }
        double accel = ground ? baseSpeed * (sprint ? SPRINT_BONUS : 1) : sprint ? AIR_SPRINT : AIR_WALK;
        vu += accel * INPUT * input;
        // on the ladder: horizontal clamped, falling clamped. this is the cell we're in now, before the move
        if (climbable(pu, py)) {
            vu = Math.max(-CLIMB_CAP, Math.min(CLIMB_CAP, vu));
            vy = Math.max(vy, -CLIMB_CAP);
        }
        // y first, then u, same as vanilla's axis order with nothing sideways
        double dy = collideY(pu, py, vy);
        py += dy;
        boolean landed = vy < 0 && dy != vy;
        double du = collideU(pu, py, vu);
        boolean bump = Math.abs(du - vu) >= BUMP;
        pu += du;
        if (bump) {
            vu = 0;
        }
        if (dy != vy) {
            vy = 0;
        }
        // holding space or pushing into the wall while we're in a ladder is what climbs it, and it's checked after the move
        if ((bump || space) && climbable(pu, py)) {
            vy = CLIMB_UP;
        }
        // the jump tick is still onGround as far as friction goes
        double friction = ground ? GROUND_FRICTION : AIR_FRICTION;
        pvu = vu * friction;
        pvy = (vy - GRAVITY) * DRAG;
        pGround = landed;
        pSprint = sprint;
        pBump = bump;
    }

    private double collideY(double u, double y, double dy) {
        if (dy == 0) {
            return 0;
        }
        double lo = u - HALF, hi = u + HALF;
        int k0 = (int) Math.floor(lo + 0.5), k1 = (int) Math.floor(hi + 0.5);
        int r0, r1;
        if (dy < 0) {
            r0 = (int) Math.floor(y + dy);
            r1 = (int) Math.floor(y);
        } else {
            r0 = (int) Math.floor(y + HEIGHT);
            r1 = (int) Math.floor(y + HEIGHT + dy);
        }
        for (int k = k0; k <= k1; k++) {
            for (int r = r0; r <= r1; r++) {
                byte type = get(k, r);
                if (type == AIR || type == VINE) {
                    continue;
                }
                double a = a(type, k), b = b(type, k);
                if (b <= lo + EPS || a >= hi - EPS) {
                    continue;
                }
                if (dy < 0 && r + 1 <= y + EPS) {
                    dy = Math.max(dy, Math.min(0, r + 1 - y));
                } else if (dy > 0 && r >= y + HEIGHT - EPS) {
                    dy = Math.min(dy, r - (y + HEIGHT));
                }
            }
        }
        return dy;
    }

    private double collideU(double u, double y, double du) {
        if (du == 0) {
            return 0;
        }
        double lo = u - HALF, hi = u + HALF;
        int r0 = (int) Math.floor(y + EPS), r1 = (int) Math.ceil(y + HEIGHT - EPS) - 1;
        int k0, k1;
        if (du > 0) {
            k0 = (int) Math.floor(hi + 0.5 - EPS);
            k1 = (int) Math.floor(hi + du + 0.5);
        } else {
            k0 = (int) Math.floor(lo + du + 0.5);
            k1 = (int) Math.floor(lo + 0.5 + EPS);
        }
        for (int k = k0; k <= k1; k++) {
            for (int r = r0; r <= r1; r++) {
                byte type = get(k, r);
                if (type == AIR || type == VINE) {
                    continue;
                }
                double a = a(type, k), b = b(type, k);
                if (du > 0 && a >= hi - EPS) {
                    du = Math.min(du, Math.max(0, a - hi));
                } else if (du < 0 && b <= lo + EPS) {
                    du = Math.max(du, Math.min(0, b - lo));
                }
            }
        }
        return du;
    }

    private static double a(byte type, int k) {
        return type == LADDER_FAR ? k + 0.5 - LADDER : k - 0.5;
    }

    private static double b(byte type, int k) {
        return type == LADDER_NEAR ? k - 0.5 + LADDER : k + 0.5;
    }

    // ---- what counts as being there

    private int feetCol() {
        return (int) Math.floor(pu + 0.5);
    }

    private int feetRow() {
        return (int) Math.floor(py);
    }

    // feet in dest's cell and it's a climbable one, so vanilla is holding us
    private boolean caught() {
        return climbDest && feetCol() == shape.dist() && feetRow() == destRow && climbable(pu, py);
    }

    // standing on the landing block. the box might overhang but the block has to be under some of it
    private boolean landed() {
        return !climbDest && pGround && Math.min(pu + HALF, shape.dist() + 0.5) - Math.max(pu - HALF, shape.dist() - 0.5) > 0
                && Math.abs(py - destRow) < 1e-9;
    }

    private boolean settled() {
        double center = shape.dist();
        if (climbDest) {
            return caught() && Math.abs(pu - center) <= 0.3 && Math.abs(pvu) <= 0.05;
        }
        return landed() && feetCol() == shape.dist() && Math.abs(pvu) <= 0.05;
    }

    private boolean lost() {
        return py < Math.min(0, destRow) - 3 || pu < -shape.runway() - 1.2 || pu > shape.dist() + 2.2;
    }

    // stay put: aim for a crawl toward the middle of the cell, coasting if that's about what we've got. same on the
    // ground and on the ladder, only the acceleration differs
    private int stayInput() {
        double want = Math.max(-0.1, Math.min(0.1, 0.6 * (shape.dist() - pu)));
        double accel = (pGround ? baseSpeed : AIR_WALK) * INPUT;
        double need = (want - pvu) / accel;
        return need > 0.5 ? PUSH : need < -0.5 ? BRAKE : COAST;
    }

    // keep the feet in the dest cell: climb while we're low in it, slide while we're high. same rule whether we've
    // caught the dest cell or just a bit of the column under it, so a catch that's a block low climbs back up
    private boolean stayJump() {
        return climbDest && !pGround && feetCol() == shape.dist() && climbable(pu, py) && py < destRow + 0.4;
    }

    // space for tick t of a schedule: the jump itself, a leap's climb out, or the climb back up to dest
    private boolean space(Sched sched, int t, boolean jump) {
        return jump || !shape.grab() && t < sched.hold() || stayJump();
    }

    // ---- planning

    // the whole schedule from wherever the p state is. ticks until we've got there and stayed, NaN if we don't
    private double fly(Sched sched, int age, boolean jumpFirst, boolean wantSprint) {
        boolean jump = jumpFirst;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (caught() || landed()) {
                return stay(tick, wantSprint);
            }
            int t = age + tick;
            int input = sched.input(t);
            step(input, space(sched, t, jump), wantSprint);
            jump = false;
            // down on something that isn't the landing, or off the map
            if (lost() || pGround && !caught() && !landed()) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // after the catch or the landing: keep trying to stay for a few ticks and see that we do
    private double stay(int tick, boolean wantSprint) {
        for (int i = 0; i < STAY; i++) {
            if (!(caught() || landed())) {
                return Double.NaN;
            }
            if (settled()) {
                return tick + i;
            }
            step(stayInput(), stayJump(), wantSprint);
            if (lost()) {
                return Double.NaN;
            }
        }
        return caught() || landed() ? tick + STAY : Double.NaN;
    }

    // this schedule and four shaken copies of it, so that a pretty one that only works to the hair isn't picked. off the
    // ground we shake u and y, on it we can't shake y (the floor is right there) so u gets shaken twice as hard instead
    private int robust(Sched sched, int age, boolean jumpFirst, boolean wantSprint, double u, double y, double vu, double vy, boolean ground, boolean sprint, boolean bump) {
        int count = 0;
        for (int i = 0; i < 5; i++) {
            double du = i == 1 ? SHAKE : i == 2 ? -SHAKE : 0, dy = i == 3 ? SHAKE : i == 4 ? -SHAKE : 0;
            if (jumpFirst) {
                du += dy * 2;
                dy = 0;
            }
            load(u + du, y + dy, vu, vy, ground, sprint, bump);
            if (!Double.isNaN(fly(sched, age, jumpFirst, wantSprint))) {
                count++;
            }
        }
        return count;
    }

    // best way to go from here. on the ground that's how many ticks of running (up to maxRunup) and then the schedule, in
    // the air or on a ladder it's just the schedule. in the air, pass last tick's plan: it gets carried on one tick and
    // kept unless something beats it, same reason as NeoJump.plan
    Plan plan(double u, double y, double vu, double vy, boolean ground, boolean sprint, boolean bump, boolean wantSprint, int maxRunup, Plan previous) {
        Plan best = null;
        boolean running = shape.grab() && ground;
        Sched[] family = running ? GRAB_FAMILY : shape.grab() || !climbable(u, y) ? AIR_FAMILY : LEAP_FAMILY;
        load(u, y, vu, vy, ground, sprint, bump);
        for (int runup = 0; runup <= (running ? maxRunup : 0); runup++) {
            if (runup > 0) {
                step(PUSH, false, wantSprint);
                // walked off the edge, or the floor's about to be gone. the box has to keep some floor under it
                if (!pGround || lost() || pu > 0.5 + HALF - 0.02) {
                    break;
                }
            }
            // nothing a jump from back here reaches. no point asking
            if (running && pu < RUN_FROM) {
                continue;
            }
            double ru = pu, ry = py, rvu = pvu, rvy = pvy;
            boolean rg = pGround, rs = pSprint, rb = pBump;
            for (Sched sched : family) {
                load(ru, ry, rvu, rvy, rg, rs, rb);
                double score = fly(sched, 0, running, wantSprint);
                if (Double.isNaN(score)) {
                    continue;
                }
                int robust = robust(sched, 0, running, wantSprint, ru, ry, rvu, rvy, rg, rs, rb);
                double total = robust * 100 - score - 0.01 * runup;
                if (best == null || total > best.score()) {
                    best = new Plan(total, robust, runup, sched, 0);
                }
            }
            load(ru, ry, rvu, rvy, rg, rs, rb);
        }
        if (!running && previous != null && previous.runup() == 0) {
            int age = previous.age() + 1;
            load(u, y, vu, vy, ground, sprint, bump);
            double score = fly(previous.sched(), age, false, wantSprint);
            if (!Double.isNaN(score)) {
                int robust = robust(previous.sched(), age, false, wantSprint, u, y, vu, vy, ground, sprint, bump);
                double total = robust * 100 - score;
                if (best == null || total >= best.score()) {
                    best = new Plan(total, robust, 0, previous.sched(), age);
                }
            }
        }
        return best;
    }

    // a run up that starts back past this can't reach anything, so we only look for the jump once we're closer
    private static final double RUN_FROM = -1.7;

    // what to press this tick. the movement does exactly this and the rollout does exactly this, which is the whole point
    Action decide(Control control, double u, double y, double vu, double vy, boolean ground, boolean sprint, boolean bump, boolean wantSprint) {
        Action action = decide0(control, u, y, vu, vy, ground, sprint, bump, wantSprint);
        // planning flew the p state all over the place
        load(u, y, vu, vy, ground, sprint, bump);
        return action;
    }

    private Action decide0(Control control, double u, double y, double vu, double vy, boolean ground, boolean sprint, boolean bump, boolean wantSprint) {
        load(u, y, vu, vy, ground, sprint, bump);
        if (control.launched && (caught() || landed())) {
            if (settled() || control.stay >= STAY) {
                return new Action(COAST, false, DONE);
            }
            control.stay++;
            return new Action(stayInput(), stayJump(), GO);
        }
        control.stay = 0;
        if (shape.grab() && ground) {
            if (control.launched) {
                return new Action(PUSH, false, FAIL); // came back down on the runway
            }
            Plan plan = plan(u, y, vu, vy, true, sprint, bump, wantSprint, MAX_RUNUP, null);
            if (plan != null && plan.robust() >= GO_ROBUST) {
                if (plan.runup() == 0) {
                    control.launched = true;
                    control.plan = plan;
                    return new Action(plan.sched().input(0), true, GO);
                }
                return new Action(PUSH, false, GO);
            }
            // nothing in reach yet, so keep running up on it until there's something
            return new Action(PUSH, false, u < RUN_UNTIL ? GO : FAIL);
        }
        Plan plan = plan(u, y, vu, vy, ground, sprint, bump, wantSprint, 0, control.plan);
        boolean hanging = !shape.grab() && !control.launched;
        if (plan == null || hanging && plan.robust() < GO_ROBUST) {
            return new Action(COAST, false, hanging ? STAGE : FAIL);
        }
        control.plan = plan;
        control.launched = true;
        load(u, y, vu, vy, ground, sprint, bump);
        return new Action(plan.sched().input(plan.age()), space(plan.sched(), plan.age(), false), GO);
    }

    // past this the floor is nearly gone and running further is just walking off
    private static final double RUN_UNTIL = 0.3;

    // closed loop, the way the movement runs it. the margin is the worst plan's robustness along the way, NaN if it
    // never gets there
    double rollout(double u, double y, double vu, double vy, boolean bump) {
        boolean grab = shape.grab();
        load(u, y, vu, vy, grab, false, bump);
        Control control = new Control(false);
        double worst = Double.POSITIVE_INFINITY;
        boolean wantSprint = shape.sprint() && grab;
        for (int tick = 0; tick < 90; tick++) {
            Action action = decide(control, pu, py, pvu, pvy, pGround, pSprint, pBump, wantSprint);
            if (action.status() == DONE) {
                return worst;
            }
            if (action.status() == FAIL || action.status() == STAGE) {
                return Double.NaN;
            }
            if (control.plan != null) {
                worst = Math.min(worst, control.plan.robust());
            }
            step(action.input(), action.jump(), wantSprint);
            if (lost()) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // for tests
    double[] state() {
        return new double[]{pu, py, pvu, pvy};
    }

    void put(double u, double y, double vu, double vy, boolean ground) {
        load(u, y, vu, vy, ground, false, false);
    }

    void tick(int input, boolean space) {
        step(input, space, false);
    }

    void tick(int input, boolean space, boolean sprintKey) {
        step(input, space, sprintKey);
    }
}
