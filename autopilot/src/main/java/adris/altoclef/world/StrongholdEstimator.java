package adris.altoclef.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// pure math for finding the stronghold from eye of ender rays (gamer-design.md 5.6, numbers in gamer-research-route.md
// D.2/D.3). no minecraft types, no randomness, nothing here knows what a tick is.
//
// the target is the start chunk corner (16cx, 16cz) of the NEAREST stronghold from the throw spot. the only prior is
// the ring layout: ring i sits at (128 + 192 i) chunks +-40, plus the 112 block biome shift
public final class StrongholdEstimator {
    // origin and unit direction in XZ, dived = the eye sank into the ground (target within ~12 blocks)
    public record Ray(double ox, double oz, double dx, double dz, boolean dived) {
    }

    public record Estimate(double x, double z, double radius, double confidence, int rays, boolean snapped, int ring) {
    }

    public enum Step {THROW, WALK, ARRIVE, DIG}

    // x,z = where to walk / stand / dig, why = one line for the log
    public record Advice(Step step, double x, double z, String why) {
    }

    // maxThrows is a budget for the caller (throwsUsed() counts against it), the estimator itself never stops advising
    public record Params(double sigmaDeg, int maxRays, double snapRadius, int maxThrows) {
        public Params(double sigmaDeg, int maxRays, double snapRadius) {
            this(sigmaDeg, maxRays, snapRadius, 14);
        }

        public static Params defaults() {
            return new Params(0.25, 12, 12, 14);
        }
    }

    // ring prior, everything vanilla gave us in one place
    private static final int[] RING_COUNTS = {3, 6, 10, 15, 21, 28, 36, 9};
    private static final int RING_BASE_CHUNKS = 128;
    private static final int RING_STEP_CHUNKS = 192;
    private static final int RING_JITTER_CHUNKS = 40;
    private static final int BIOME_SHIFT_BLOCKS = 112;

    // eye mechanics: it sinks when the target is within this many blocks in XZ
    private static final double DIVE_RADIUS = 12;

    // how the fit trusts things. all guesses except the dive numbers, see the tests for what they buy
    private static final double RANGE_FLOOR = 20;         // never trust a ray as if it were 3 blocks long
    private static final double PARALLEL_EPS = 0.0002;    // |cross| of unit rays, about 0.01 degrees. closer than that is the same line, wider is just blurry
    private static final double OUTLIER_Z = 4;            // standardized residual of a new ray
    private static final double PRIOR_M = 4;              // how far outside the ring annulus the fit may sit, in sigmas
    private static final double RING_TRUST_RADIUS = 300;   // below this the fit picks its own ring, else the ray does
    private static final double BEHIND_SLACK = 30;
    private static final double INFLATE_NU0 = 2;
    private static final int FLIP_STREAK = 3;           // this many rejected rays in a row get to overrule the old fit
    private static final double DIVE_ALONG_MID = 6;       // uniform over 0..12 so mean 6, sigma 3.5
    private static final double DIVE_ALONG_SIGMA = 3.5;
    private static final double DIVE_PERP_SIGMA = 1;

    // advice numbers, straight from the design
    private static final double ONE_RAY_STEP = 200;
    private static final double TURN_COS = Math.cos(Math.toRadians(25));
    private static final double TURN_SIN = Math.sin(Math.toRadians(25));
    private static final double ONE_RAY_THROW_WALK = 120;
    private static final double WAYPOINT_SLACK = 25;   // close enough counts as there, pathing never lands on the exact point
    private static final double FAR = 150;
    private static final double FAR_MIN_WALK = 120;
    private static final double FAR_WALK_FRACTION = 0.4;
    // a tight fit this far out stops asking for new baselines: more throws from here burn eyes for a few blocks of gain
    private static final double SETTLED_RADIUS = 24;
    private static final int SETTLED_MIN_RAYS = 3;
    private static final double SETTLED_VALVE = 2;
    // same idea with a looser fit once the bag is running low, the ring needs those eyes more than the fit does
    private static final double LOW_EYES_RADIUS = 100;
    private static final int LOW_EYES = 12;
    private static final double NEAR_RADIUS = 24;
    private static final double NEAR_THROW_WALK = 40;
    private static final double NEAR_THROW_RANGE = 60;
    private static final double ARRIVE_RANGE = 80;
    private static final double MIN_THROW_GAP = 10;
    // not in the design: the sideways aim of leadWalk, a fraction of the range capped in blocks
    private static final double LEAD_FRACTION = 0.3;
    private static final double LEAD_MAX = 400;
    private static final double EXIT_GAP = MIN_THROW_GAP + 1;

    private final Params params;
    private final double sigmaRad;
    private final List<Ray> rays = new ArrayList<>();
    private Fit fit;
    private int throwsUsed;
    // throws a previous run of the task made that never became rays (rejected), so a reload still knows the fit was contradicted
    private int restoredRejects;
    private boolean hasLastOrigin;
    private double lastOriginX;
    private double lastOriginZ;
    private String lastRejection = "";
    private final List<Ray> rejectedStreak = new ArrayList<>();

    public StrongholdEstimator(Params params) {
        this.params = params;
        this.sigmaRad = Math.toRadians(Math.max(1e-4, params.sigmaDeg()));
    }

    // inner and outer edge (blocks from 0,0) of ring i's band, shift allowance included. ring 0 is 1296..2800
    public static double ringLo(int ring) {
        return (RING_BASE_CHUNKS + RING_STEP_CHUNKS * ring - RING_JITTER_CHUNKS) * 16.0 - BIOME_SHIFT_BLOCKS;
    }

    public static double ringHi(int ring) {
        return (RING_BASE_CHUNKS + RING_STEP_CHUNKS * ring + RING_JITTER_CHUNKS) * 16.0 + BIOME_SHIFT_BLOCKS;
    }

    public static int ringCount(int ring) {
        return RING_COUNTS[ring];
    }

    public static int rings() {
        return RING_COUNTS.length;
    }

    // false = rejected (parallel to everything, points the wrong way, or an outlier against the current fit).
    // a rejected ray still counts as a throw spot for the "not twice within 10 blocks" rule, it just never touches the fit
    public boolean addRay(Ray ray) {
        throwsUsed++;
        double len = Math.hypot(ray.dx(), ray.dz());
        if (!(len > 1e-9) || !Double.isFinite(ray.ox()) || !Double.isFinite(ray.oz()) || !Double.isFinite(len)) {
            lastRejection = "no direction";
            return false;
        }
        Ray r = new Ray(ray.ox(), ray.oz(), ray.dx() / len, ray.dz() / len, ray.dived());
        hasLastOrigin = true;
        lastOriginX = r.ox();
        lastOriginZ = r.oz();
        // a dived ray is the one thing that is allowed to disagree with everything else, the fit drops what it contradicts
        if (!r.dived() && !rays.isEmpty() && !acceptable(r)) {
            return recover(r);
        }
        rejectedStreak.clear();
        rays.add(r);
        fit = computeFit(rays);
        trim();
        return true;
    }

    public Optional<Estimate> estimate() {
        return fit == null ? Optional.empty() : Optional.of(fit.est);
    }

    public String lastRejection() {
        return lastRejection;
    }

    // addRay calls so far, accepted or not, for the caller's throw budget
    public int throwsUsed() {
        return throwsUsed;
    }

    public void restoreRejects(int n) {
        restoredRejects = Math.max(0, n);
    }

    public boolean throwsExhausted() {
        return throwsUsed >= params.maxThrows();
    }

    // walkedSinceLastThrow in blocks. runs every tick so it only reads the cached fit and builds one Advice
    public Advice advise(double playerX, double playerZ, double walkedSinceLastThrow) {
        return advise(playerX, playerZ, walkedSinceLastThrow, Integer.MAX_VALUE);
    }

    // heldEyes lets a far, already decent fit stop spending eyes once the bag gets thin
    public Advice advise(double playerX, double playerZ, double walkedSinceLastThrow, int heldEyes) {
        if (fit == null) {
            return new Advice(Step.THROW, playerX, playerZ, "no rays yet");
        }
        Ray last = rays.get(rays.size() - 1);
        if (last.dived()) {
            return new Advice(Step.DIG, fit.snapX + 4, fit.snapZ + 4, "the eye dived, dig at the start chunk");
        }
        if (fit.used <= 1) {
            return adviseOneRay(last, playerX, playerZ, walkedSinceLastThrow);
        }
        double range = Math.hypot(fit.x - playerX, fit.z - playerZ);
        if (range > FAR) {
            // already snapped onto a start chunk: more rays from out here add nothing, they just burn eyes. walk, and let
            // the close path below do the one on-the-spot dive throw
            if (fit.est.snapped() && fit.used >= 2) {
                return new Advice(Step.WALK, fit.snapX, fit.snapZ, "estimate is snapped, walking there without more throws");
            }
            // the valve: a confidently wrong fit must not be walked at forever, so a long silent walk earns a new baseline
            if (settled(heldEyes) && walkedSinceLastThrow < SETTLED_VALVE * Math.max(FAR_MIN_WALK, FAR_WALK_FRACTION * range)) {
                return new Advice(Step.WALK, fit.x, fit.z, "estimate is stable enough, walking there without more throws");
            }
            if (walkedSinceLastThrow >= Math.max(FAR_MIN_WALK, FAR_WALK_FRACTION * range)) {
                return throwOrWalk(last, playerX, playerZ, fit.x, fit.z, "walked far enough for a new baseline");
            }
            return leadWalk(playerX, playerZ, "walking to the estimate, bending off the line for parallax");
        }
        if (fit.est.radius() > NEAR_RADIUS) {
            if (walkedSinceLastThrow >= NEAR_THROW_WALK || range <= NEAR_THROW_RANGE) {
                return throwOrWalk(last, playerX, playerZ, fit.x, fit.z, "close but still blurry, another ray");
            }
            return leadWalk(playerX, playerZ, "closing in, estimate still blurry");
        }
        double toSnap = Math.hypot(fit.snapX - playerX, fit.snapZ - playerZ);
        if (toSnap <= DIVE_RADIUS) {
            return throwOrWalk(last, playerX, playerZ, fit.snapX, fit.snapZ, "on the spot, throw to see if the eye dives");
        }
        if (range <= ARRIVE_RANGE) {
            return new Advice(Step.ARRIVE, fit.snapX, fit.snapZ, "estimate is tight, go stand on it");
        }
        return new Advice(Step.WALK, fit.x, fit.z, "estimate is tight, almost there");
    }

    private boolean settled(int heldEyes) {
        // a rejected ray means the world disagreed with the fit at least once, so it is not settled whatever its radius says
        if (fit.used < SETTLED_MIN_RAYS || throwsUsed + restoredRejects > fit.used) {
            return false;
        }
        double r = fit.est.radius();
        return r <= SETTLED_RADIUS || (heldEyes <= LOW_EYES && r <= LOW_EYES_RADIUS);
    }

    public List<Ray> rays() {
        return List.copyOf(rays);
    }

    public void clear() {
        rays.clear();
        fit = null;
        throwsUsed = 0;
        restoredRejects = 0;
        hasLastOrigin = false;
        lastRejection = "";
        rejectedStreak.clear();
    }

    // direction from the throw position to where the eye was seen, normalised
    public static Ray rayFromPoints(double originX, double originZ, double eyeX, double eyeZ, boolean dived) {
        double dx = eyeX - originX;
        double dz = eyeZ - originZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        return new Ray(originX, originZ, len == 0 ? 0 : dx / len, len == 0 ? 0 : dz / len, dived);
    }

    // ---- advice ----

    private Advice adviseOneRay(Ray last, double px, double pz, double walked) {
        // the gap rule counts every throw, rejected ones included, the waypoint hangs off the last one that made it in
        double gap = hasLastOrigin ? Math.hypot(px - lastOriginX, pz - lastOriginZ) : Double.MAX_VALUE;
        if (gap >= MIN_THROW_GAP && walked >= ONE_RAY_THROW_WALK) {
            return new Advice(Step.THROW, px, pz, "walked a baseline, second ray");
        }
        double mid = (ringLo(fit.est.ring()) + ringHi(fit.est.ring())) / 2;
        double cos = TURN_COS;
        double sin = TURN_SIN;
        // both turns make progress along the ray, take the one that ends up closer to the middle of the ring.
        // exact ties (throw spot on the line through the origin) go counterclockwise so it is deterministic
        double lx = last.ox() + ONE_RAY_STEP * (last.dx() * cos - last.dz() * sin);
        double lz = last.oz() + ONE_RAY_STEP * (last.dx() * sin + last.dz() * cos);
        double rx = last.ox() + ONE_RAY_STEP * (last.dx() * cos + last.dz() * sin);
        double rz = last.oz() + ONE_RAY_STEP * (-last.dx() * sin + last.dz() * cos);
        boolean left = Math.abs(Math.hypot(lx, lz) - mid) <= Math.abs(Math.hypot(rx, rz) - mid) + 1e-6;
        double hx = (left ? lx : rx) - last.ox();
        double hz = (left ? lz : rz) - last.oz();
        // the heading is anchored on the throw spot so the waypoint does not run away every tick. it sits on the next
        // multiple of 200 blocks along that heading (25 short counts as reached), so if a second ray gets turned away and we walk past the first
        // waypoint there is always a new one ahead (standing on the goal with nothing to do was a real deadlock)
        double along = ((px - last.ox()) * hx + (pz - last.oz()) * hz) / ONE_RAY_STEP;
        double k = Math.max(1, Math.floor((along + WAYPOINT_SLACK) / ONE_RAY_STEP) + 1);
        return new Advice(Step.WALK, last.ox() + hx * k, last.oz() + hz * k, "one ray only, walk off the line for a baseline");
    }
    // walking straight at a blurry estimate retraces the line the rays already share and never learns how far along it
    // the target is (rays from one line are all parallel to each other). so aim off to the side by a slice of the range,
    // the throw from there crosses the old rays at a real angle.
    // measured from the last throw spot, not from us (same idea as adviseOneRay): off our own position the aim slid about
    // 8 blocks for every 27 we walked, which is exactly the walk leg's replan gap, so it threw the path away every leg
    private Advice leadWalk(double px, double pz, String why) {
        double ax = hasLastOrigin ? lastOriginX : px;
        double az = hasLastOrigin ? lastOriginZ : pz;
        double perpX = -fit.axisZ;
        double perpZ = fit.axisX;
        double side = (ax - fit.x) * perpX + (az - fit.z) * perpZ >= 0 ? 1 : -1;
        double lead = Math.min(LEAD_MAX, LEAD_FRACTION * Math.hypot(fit.x - ax, fit.z - az)) * side;
        return new Advice(Step.WALK, fit.x + perpX * lead, fit.z + perpZ * lead, why);
    }

    // a throw that is too close to the last one tells us nothing, so move on first
    private Advice throwOrWalk(Ray last, double px, double pz, double goalX, double goalZ, String why) {
        double gap = hasLastOrigin ? Math.hypot(px - lastOriginX, pz - lastOriginZ) : Double.MAX_VALUE;
        if (gap >= MIN_THROW_GAP) {
            return new Advice(Step.THROW, px, pz, why);
        }
        if (Math.hypot(goalX - px, goalZ - pz) > MIN_THROW_GAP) {
            return new Advice(Step.WALK, goalX, goalZ, "too close to the last throw, keep walking");
        }
        // standing basically on the goal and on the last throw: step just outside the 10 block circle on the goal's side
        double dx = goalX - lastOriginX;
        double dz = goalZ - lastOriginZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            dx = last.dx();
            dz = last.dz();
            len = 1;
        }
        return new Advice(Step.WALK, lastOriginX + dx / len * EXIT_GAP, lastOriginZ + dz / len * EXIT_GAP,
                "threw here already, step out of the 10 block circle");
    }

    // ---- accepting rays ----

    private boolean acceptable(Ray r) {
        boolean parallelToAll = true;
        for (Ray o : rays) {
            if (Math.abs(r.dx() * o.dz() - r.dz() * o.dx()) >= PARALLEL_EPS) {
                parallelToAll = false;
                break;
            }
        }
        if (parallelToAll) {
            lastRejection = "parallel to every ray we have";
            return false;
        }
        Ray dive = lastDived(rays);
        if (dive != null) {
            // the dive pinned the target to a 12 block disc, a ray that misses it is a different stronghold
            double tx = dive.ox() + DIVE_ALONG_MID * dive.dx();
            double tz = dive.oz() + DIVE_ALONG_MID * dive.dz();
            if (!consistentWithDive(r, dive, tx, tz)) {
                lastRejection = "does not pass through the spot the dived eye pinned down";
                return false;
            }
        } else if (fit != null && fit.used >= 2 && outlier(r)) {
            lastRejection = "does not fit the current estimate (target flipped to another stronghold?)";
            return false;
        }
        List<Ray> tmp = new ArrayList<>(rays.size() + 1);
        tmp.addAll(rays);
        tmp.add(r);
        Fit f = computeFit(tmp);
        if (f.used >= 2 && f.priorM > PRIOR_M) {
            lastRejection = "puts the target far outside the ring it should be in";
            return false;
        }
        double along = r.dx() * (f.x - r.ox()) + r.dz() * (f.z - r.oz());
        if (along < -(f.est.radius() + BEHIND_SLACK)) {
            lastRejection = "points away from the estimate";
            return false;
        }
        return true;
    }

    // a ray we turned away. one is noise, a few in a row that agree with each other are the old fit being wrong (the eye
    // switched to another stronghold when we crossed the bisector, or the first ray was garbage), so they take over
    private boolean recover(Ray r) {
        rejectedStreak.add(r);
        while (rejectedStreak.size() > FLIP_STREAK * 2) {
            rejectedStreak.remove(0);
        }
        if (rejectedStreak.size() < FLIP_STREAK) {
            return false;
        }
        List<Ray> newer = new ArrayList<>(rejectedStreak.subList(rejectedStreak.size() - FLIP_STREAK, rejectedStreak.size()));
        Fit f = computeFit(newer);
        double along = r.dx() * (f.x - r.ox()) + r.dz() * (f.z - r.oz());
        if (f.used < 2 || f.priorM > PRIOR_M || along < -(f.est.radius() + BEHIND_SLACK)) {
            return false;
        }
        rays.clear();
        rays.addAll(newer);
        rejectedStreak.clear();
        fit = f;
        lastRejection = "";
        return true;
    }

    // standardized residual of the new ray against the fit we have, with the fit's own blur counted in
    private boolean outlier(Ray r) {
        double nx = -r.dz();
        double nz = r.dx();
        double e = nx * (fit.rawX - r.ox()) + nz * (fit.rawZ - r.oz());
        double range = Math.max(RANGE_FLOOR, Math.hypot(fit.x - r.ox(), fit.z - r.oz()));
        double det = fit.a11 * fit.a22 - fit.a12 * fit.a12;
        // n^T A^-1 n
        double blur = (fit.a22 * nx * nx - 2 * fit.a12 * nx * nz + fit.a11 * nz * nz) / det;
        double sigma = sigmaRad * range;
        return Math.abs(e) / Math.sqrt(sigma * sigma + blur) > OUTLIER_Z;
    }

    // keeps the list at maxRays by dropping the lowest weight one (the far away old throws), never the newest
    private void trim() {
        int cap = Math.max(2, params.maxRays());
        while (rays.size() > cap) {
            int worst = 0;
            double worstW = Double.MAX_VALUE;
            for (int i = 0; i < rays.size() - 1; i++) {
                Ray o = rays.get(i);
                double range = Math.max(RANGE_FLOOR, Math.hypot(fit.x - o.ox(), fit.z - o.oz()));
                double w = 1 / (range * range);
                if (w < worstW) {
                    worstW = w;
                    worst = i;
                }
            }
            rays.remove(worst);
            fit = computeFit(rays);
        }
    }

    // ---- the fit ----

    private static final class Fit {
        double x;
        double z;
        double rawX;
        double rawZ;
        double a11;
        double a12;
        double a22;
        double priorM;
        double axisX;
        double axisZ;
        int used;
        boolean dived;
        double snapX;
        double snapZ;
        Estimate est;
    }

    private Fit computeFit(List<Ray> all) {
        int n = all.size();
        Ray dive = lastDived(all);
        boolean[] use = new boolean[n];
        int used = 0;
        double gx = Double.NaN;
        double gz = Double.NaN;
        if (dive != null) {
            gx = dive.ox() + DIVE_ALONG_MID * dive.dx();
            gz = dive.oz() + DIVE_ALONG_MID * dive.dz();
        }
        for (int i = 0; i < n; i++) {
            use[i] = dive == null || consistentWithDive(all.get(i), dive, gx, gz);
            if (use[i]) {
                used++;
            }
        }
        Ray latest = all.get(n - 1);
        if (used == 1 && dive == null) {
            return priorFit(latest, n);
        }
        Sol s = null;
        for (int pass = 0; pass < 3; pass++) {
            s = solve(all, use, gx, gz);
            if (s == null) {
                return priorFit(latest, n);
            }
            if (dive == null) {
                place(s, latest);
            } else {
                s.x = s.rawX;
                s.z = s.rawZ;
            }
            gx = s.x;
            gz = s.z;
        }
        return finish(s, all, use, used, dive, n);
    }

    private static Ray lastDived(List<Ray> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).dived()) {
                return all.get(i);
            }
        }
        return null;
    }

    private boolean consistentWithDive(Ray r, Ray dive, double tx, double tz) {
        if (r == dive) {
            return true;
        }
        double range = Math.max(RANGE_FLOOR, Math.hypot(tx - r.ox(), tz - r.oz()));
        double perp = Math.abs(-r.dz() * (tx - r.ox()) + r.dx() * (tz - r.oz()));
        return perp <= DIVE_RADIUS + 4 * sigmaRad * range;
    }

    // normal equations of the perpendicular distances, weights 1/(sigma*range)^2 with ranges taken to the guess
    private static final class Sol {
        double a11;
        double a12;
        double a22;
        double rawX;
        double rawZ;
        double x;
        double z;
        double mu1;
        double mu2;
        double axisX;
        double axisZ;
        double var;        // along the weak axis, after the ring truncation
        double priorM;
        int ring;
    }

    private Sol solve(List<Ray> all, boolean[] use, double gx, double gz) {
        double a11 = 0;
        double a12 = 0;
        double a22 = 0;
        double b1 = 0;
        double b2 = 0;
        for (int i = 0; i < all.size(); i++) {
            if (!use[i]) {
                continue;
            }
            Ray r = all.get(i);
            double nx = -r.dz();
            double nz = r.dx();
            if (r.dived()) {
                double wp = 1 / (DIVE_PERP_SIGMA * DIVE_PERP_SIGMA);
                double wa = 1 / (DIVE_ALONG_SIGMA * DIVE_ALONG_SIGMA);
                double cx = r.ox() + DIVE_ALONG_MID * r.dx();
                double cz = r.oz() + DIVE_ALONG_MID * r.dz();
                double pn = nx * r.ox() + nz * r.oz();
                double pu = r.dx() * cx + r.dz() * cz;
                a11 += wp * nx * nx + wa * r.dx() * r.dx();
                a12 += wp * nx * nz + wa * r.dx() * r.dz();
                a22 += wp * nz * nz + wa * r.dz() * r.dz();
                b1 += wp * nx * pn + wa * r.dx() * pu;
                b2 += wp * nz * pn + wa * r.dz() * pu;
                continue;
            }
            double w = 1;
            if (!Double.isNaN(gx)) {
                double sigma = sigmaRad * Math.max(RANGE_FLOOR, Math.hypot(gx - r.ox(), gz - r.oz()));
                w = 1 / (sigma * sigma);
            }
            double pn = nx * r.ox() + nz * r.oz();
            a11 += w * nx * nx;
            a12 += w * nx * nz;
            a22 += w * nz * nz;
            b1 += w * nx * pn;
            b2 += w * nz * pn;
        }
        double tr = a11 + a22;
        double det = a11 * a22 - a12 * a12;
        if (!(det > 1e-12 * tr * tr)) {
            return null;
        }
        Sol s = new Sol();
        s.a11 = a11;
        s.a12 = a12;
        s.a22 = a22;
        s.rawX = (a22 * b1 - a12 * b2) / det;
        s.rawZ = (a11 * b2 - a12 * b1) / det;
        double half = Math.sqrt(Math.max(0, (a11 - a22) * (a11 - a22) / 4 + a12 * a12));
        s.mu1 = tr / 2 + half;
        s.mu2 = det / s.mu1;
        // eigenvector of the weak direction (smallest information), two ways to write it, take the better conditioned
        double vx1 = a12;
        double vz1 = s.mu2 - a11;
        double vx2 = s.mu2 - a22;
        double vz2 = a12;
        double n1 = Math.hypot(vx1, vz1);
        double n2 = Math.hypot(vx2, vz2);
        if (Math.max(n1, n2) < 1e-15) {
            s.axisX = a11 < a22 ? 1 : 0;
            s.axisZ = a11 < a22 ? 0 : 1;
        } else if (n1 >= n2) {
            s.axisX = vx1 / n1;
            s.axisZ = vz1 / n1;
        } else {
            s.axisX = vx2 / n2;
            s.axisZ = vz2 / n2;
        }
        s.x = s.rawX;
        s.z = s.rawZ;
        s.var = 1 / s.mu2;
        inflate(s, all, use, gx, gz);
        return s;
    }

    // if the rays disagree with each other more than sigma says they should, sigma was too optimistic (wind, lag,
    // a lying server) so blur the covariance by the same factor. the NU0 pseudo observations keep two or three rays
    // from crying wolf. without this an overconfident fit rejects every honest ray and never recovers
    private void inflate(Sol s, List<Ray> all, boolean[] use, double gx, double gz) {
        if (Double.isNaN(gx)) {
            return;
        }
        double chi = 0;
        int m = 0;
        for (int i = 0; i < all.size(); i++) {
            Ray r = all.get(i);
            if (!use[i] || r.dived()) {
                continue;
            }
            double sigma = sigmaRad * Math.max(RANGE_FLOOR, Math.hypot(gx - r.ox(), gz - r.oz()));
            double e = -r.dz() * (s.rawX - r.ox()) + r.dx() * (s.rawZ - r.oz());
            chi += e * e / (sigma * sigma);
            m++;
        }
        double infl2 = Math.max(1, (chi + INFLATE_NU0) / (Math.max(0, m - 2) + INFLATE_NU0));
        s.a11 /= infl2;
        s.a12 /= infl2;
        s.a22 /= infl2;
        s.mu1 /= infl2;
        s.mu2 /= infl2;
        s.var *= infl2;
    }

    // pulls the least squares point into the ring band. the blur along the weak axis is a gaussian, the band along that
    // line is one or two intervals, so the estimate is the mean of the gaussian truncated to the band (the single ray
    // midpoint is the same thing with an infinitely wide gaussian)
    private void place(Sol s, Ray latest) {
        double sig = Math.sqrt(s.var);
        double rawR = Math.hypot(s.rawX, s.rawZ);
        // a tight fit knows which ring it is in, a blurry one asks the first ring ahead of the newest ray
        int ring = 2 * sig < RING_TRUST_RADIUS
                ? nearestRing(rawR)
                : (int) firstInterval(latest.ox(), latest.oz(), latest.dx(), latest.dz())[2];
        s.ring = ring;
        double lo = ringLo(ring);
        double hi = ringHi(ring);
        double ux = s.axisX;
        double uz = s.axisZ;
        // the line raw + t u meets the circle of radius r where t^2 + 2 b t + (c - r^2) = 0
        double b = s.rawX * ux + s.rawZ * uz;
        double c = rawR * rawR;
        // straight to the nearest ring point, the fallback and also the other way to measure "outside the band"
        double rr = Math.max(lo, Math.min(hi, rawR));
        double shrink = rawR > 1e-9 ? rr / rawR : 0;
        double radialX = rawR > 1e-9 ? s.rawX * shrink : rr;
        double radialZ = rawR > 1e-9 ? s.rawZ * shrink : 0;
        double radialM = mahalanobis(s, radialX - s.rawX, radialZ - s.rawZ);
        double discOut = b * b - (c - hi * hi);
        if (discOut <= 0) {
            // the weak axis line misses the band altogether
            s.x = radialX;
            s.z = radialZ;
            s.priorM = radialM;
            return;
        }
        double so = Math.sqrt(discOut);
        double[] iv = new double[4];
        int count;
        double discIn = b * b - (c - lo * lo);
        if (discIn <= 0) {
            iv[0] = -b - so;
            iv[1] = -b + so;
            count = 1;
        } else {
            double si = Math.sqrt(discIn);
            iv[0] = -b - so;
            iv[1] = -b - si;
            iv[2] = -b + si;
            iv[3] = -b + so;
            count = 2;
        }
        // iv holds the band as distances t from the raw point along u (inside the outer circle minus the hole)
        double mass = 0;
        double mean = 0;
        double second = 0;
        for (int k = 0; k < count; k++) {
            double a = iv[2 * k];
            double e = iv[2 * k + 1];
            if (e <= a) {
                continue;
            }
            double al = a / sig;
            double be = e / sig;
            double z = cdf(be) - cdf(al);
            if (z <= 0) {
                continue;
            }
            double pa = pdf(al);
            double pb = pdf(be);
            double m = sig * (pa - pb) / z;
            double v = sig * sig * (1 + (al * pa - be * pb) / z - ((pa - pb) / z) * ((pa - pb) / z));
            mass += z;
            mean += z * m;
            second += z * (v + m * m);
        }
        double edge = nearestEnd(iv, count);
        double shift;
        double var;
        if (mass < 1e-6) {
            // the raw point is way out in the tail, take the closest end of the band along the line
            shift = edge;
            var = s.var * 0.01;
        } else {
            shift = mean / mass;
            var = Math.max(0, second / mass - shift * shift);
        }
        s.priorM = Math.min(Math.abs(edge) / sig, radialM);
        s.x = s.rawX + shift * ux;
        s.z = s.rawZ + shift * uz;
        s.var = Math.max(var, 1e-6);
    }

    // signed s of the band edge closest to 0, or 0 when 0 is inside a band
    private static double nearestEnd(double[] iv, int count) {
        double best = Double.MAX_VALUE;
        double bestS = 0;
        for (int k = 0; k < count; k++) {
            double a = iv[2 * k];
            double e = iv[2 * k + 1];
            if (a <= 0 && 0 <= e) {
                return 0;
            }
            double d = a > 0 ? a : e;
            if (Math.abs(d) < best) {
                best = Math.abs(d);
                bestS = d;
            }
        }
        return bestS;
    }

    private static double mahalanobis(Sol s, double dx, double dz) {
        return Math.sqrt(Math.max(0, s.a11 * dx * dx + 2 * s.a12 * dx * dz + s.a22 * dz * dz));
    }

    private Fit finish(Sol s, List<Ray> all, boolean[] use, int used, Ray dive, int n) {
        Fit f = new Fit();
        f.x = s.x;
        f.z = s.z;
        f.rawX = s.rawX;
        f.rawZ = s.rawZ;
        f.a11 = s.a11;
        f.a12 = s.a12;
        f.a22 = s.a22;
        f.axisX = s.axisX;
        f.axisZ = s.axisZ;
        f.used = used;
        f.dived = dive != null;
        f.priorM = dive != null ? 0 : s.priorM;
        double radius = 2 * Math.sqrt(Math.max(s.var, 1 / s.mu1));
        if (dive == null) {
            radius = Math.min(radius, (ringHi(s.ring) - ringLo(s.ring)) / 2);
        }
        boolean snapped = dive != null || radius < params.snapRadius();
        double ex = s.x;
        double ez = s.z;
        if (dive != null) {
            snapToDive(f, s, dive);
        } else {
            snapToLattice(f, s, all, use);
        }
        if (snapped) {
            ex = f.snapX;
            ez = f.snapZ;
        }
        f.est = new Estimate(ex, ez, radius, confidence(n, radius), n, snapped, dive != null ? ringNear(s.x, s.z) : s.ring);
        return f;
    }

    // nearest lattice point as the fit sees it: of the 3x3 around the rounded estimate, the one that agrees best with
    // the rays, minus every point within 12 of a throw that did NOT dive (it would have if the corner were there)
    private void snapToLattice(Fit f, Sol s, List<Ray> all, boolean[] use) {
        double cx0 = lattice(s.x);
        double cz0 = lattice(s.z);
        f.snapX = cx0;
        f.snapZ = cz0;
        double best = Double.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                double px = cx0 + 16 * i;
                double pz = cz0 + 16 * j;
                if (divedHere(all, use, px, pz)) {
                    continue;
                }
                // tiny euclid term so a perfect tie goes to the closer point
                double m = mahalanobis(s, px - s.x, pz - s.z) + 1e-6 * Math.hypot(px - s.x, pz - s.z);
                if (m < best) {
                    best = m;
                    f.snapX = px;
                    f.snapZ = pz;
                }
            }
        }
    }

    // true when a used throw close enough to have dived (<= 12 blocks) did not, so this point is not the corner
    private static boolean divedHere(List<Ray> all, boolean[] use, double px, double pz) {
        for (int k = 0; k < all.size(); k++) {
            Ray r = all.get(k);
            if (use[k] && !r.dived() && Math.hypot(px - r.ox(), pz - r.oz()) <= DIVE_RADIUS) {
                return true;
            }
        }
        return false;
    }
    // a dived eye means the corner is within 12 blocks of that throw: pick the lattice point in that disc that agrees
    // best with everything we know (the fit already holds the dive constraint and any consistent rays)
    private void snapToDive(Fit f, Sol s, Ray dive) {
        double best = Double.MAX_VALUE;
        int x0 = (int) Math.floor((dive.ox() - DIVE_RADIUS) / 16);
        int x1 = (int) Math.ceil((dive.ox() + DIVE_RADIUS) / 16);
        int z0 = (int) Math.floor((dive.oz() - DIVE_RADIUS) / 16);
        int z1 = (int) Math.ceil((dive.oz() + DIVE_RADIUS) / 16);
        f.snapX = lattice(s.x);
        f.snapZ = lattice(s.z);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                double px = cx * 16.0;
                double pz = cz * 16.0;
                if (Math.hypot(px - dive.ox(), pz - dive.oz()) > DIVE_RADIUS + 0.5) {
                    continue;
                }
                double m = mahalanobis(s, px - s.x, pz - s.z);
                if (m < best) {
                    best = m;
                    f.snapX = px;
                    f.snapZ = pz;
                }
            }
        }
    }

    private Fit priorFit(Ray r, int n) {
        double[] iv = firstInterval(r.ox(), r.oz(), r.dx(), r.dz());
        double t0 = iv[0];
        double t1 = iv[1];
        int ring = (int) iv[2];
        Fit f = new Fit();
        double mid = (t0 + t1) / 2;
        f.x = r.ox() + mid * r.dx();
        f.z = r.oz() + mid * r.dz();
        f.rawX = f.x;
        f.rawZ = f.z;
        f.used = 1;
        f.snapX = lattice(f.x);
        f.snapZ = lattice(f.z);
        double radius = (t1 - t0) / 2;
        f.est = new Estimate(f.x, f.z, radius, confidence(n, radius), n, false, ring);
        return f;
    }

    // first annulus along the ray (t >= 0), as {t0, t1, ring}. a ray from near 0,0 is all about ring 0, a ray from far
    // out meets the next ring ahead. falls back to something silly rather than crash when nothing is ahead
    static double[] firstInterval(double px, double pz, double dx, double dz) {
        double b = px * dx + pz * dz;
        double c = px * px + pz * pz;
        double bestT0 = Double.MAX_VALUE;
        double[] best = {0, ringHi(0), 0};
        for (int i = 0; i < RING_COUNTS.length; i++) {
            double hi = ringHi(i);
            double lo = ringLo(i);
            double discOut = b * b - (c - hi * hi);
            if (discOut <= 0) {
                continue;
            }
            double so = Math.sqrt(discOut);
            double discIn = b * b - (c - lo * lo);
            double a1 = -b - so;
            double a2 = -b + so;
            if (discIn <= 0) {
                bestT0 = consider(a1, a2, i, bestT0, best);
            } else {
                double si = Math.sqrt(discIn);
                bestT0 = consider(a1, -b - si, i, bestT0, best);
                bestT0 = consider(-b + si, a2, i, bestT0, best);
            }
        }
        return best;
    }

    private static double consider(double a, double e, int ring, double bestT0, double[] best) {
        double s0 = Math.max(a, 0);
        if (e <= s0 || s0 >= bestT0) {
            return bestT0;
        }
        best[0] = s0;
        best[1] = e;
        best[2] = ring;
        return s0;
    }

    private static int nearestRing(double r) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < RING_COUNTS.length; i++) {
            double d = r < ringLo(i) ? ringLo(i) - r : r > ringHi(i) ? r - ringHi(i) : 0;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static int ringNear(double x, double z) {
        return nearestRing(Math.hypot(x, z));
    }

    private static double lattice(double v) {
        return Math.round(v / 16.0) * 16.0;
    }

    // monotone in rays and in radius: more rays up, wider blur down. one ray at 750 blocks of blur is ~1%
    private static double confidence(int rays, double radius) {
        double byRays = 1 - Math.pow(0.5, rays);
        double byRadius = 1 / (1 + Math.max(0, radius) / 20);
        return byRays * byRadius;
    }

    private static double pdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    // abramowitz and stegun 7.1.26 for erf, good to 1.5e-7 which is plenty for a prior
    private static double cdf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x) / Math.sqrt(2));
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = 1 - poly * Math.exp(-x * x / 2);
        return 0.5 * (1 + (x >= 0 ? erf : -erf));
    }
}
