package adris.altoclef.world;

import adris.altoclef.world.StrongholdEstimator.Advice;
import adris.altoclef.world.StrongholdEstimator.Params;
import adris.altoclef.world.StrongholdEstimator.Ray;
import adris.altoclef.world.StrongholdRings.Stronghold;

import java.util.List;
import java.util.Random;

// test only: a bot that does exactly what advise() says, in a world made of real vanilla ring positions. walking
// speed does not matter, only distance. the eye points at the nearest start corner from the throw spot, with
// gaussian bearing noise, and dives when it is within 12 blocks in XZ
final class StrongholdSimulation {
    static final double STEP = 10;
    static final int TICK_CAP = 4000;

    static final class Result {
        boolean reachedDig;
        int throwsAtFirstArriveOrDig = -1;
        int throwsAtDig = -1;
        double digErrorBlocks = Double.NaN;
        double walked;
        double straightLine;
        boolean stuck;
        int rejected;
        String trace = "";
    }

    // start position -> run the whole locate loop
    static Result run(List<Stronghold> world, double startX, double startZ, double noiseDeg, Params params, Random rng) {
        StrongholdEstimator est = new StrongholdEstimator(params);
        Result res = new Result();
        double px = startX;
        double pz = startZ;
        Stronghold first = StrongholdRings.nearest(world, px, pz);
        res.straightLine = Math.hypot(first.cornerX() - px, first.cornerZ() - pz);
        double walkedSince = 0;
        int throwsDone = 0;
        StringBuilder trace = new StringBuilder();
        for (int tick = 0; tick < TICK_CAP; tick++) {
            Advice a = est.advise(px, pz, walkedSince);
            if (res.throwsAtFirstArriveOrDig < 0 && (a.step() == StrongholdEstimator.Step.ARRIVE || a.step() == StrongholdEstimator.Step.DIG)) {
                res.throwsAtFirstArriveOrDig = throwsDone;
            }
            switch (a.step()) {
                case THROW -> {
                    throwsDone++;
                    if (throwsDone > 40) {
                        res.stuck = true;
                        res.trace = trace.toString();
                        return res;
                    }
                    Stronghold target = StrongholdRings.nearest(world, px, pz);
                    double tx = target.cornerX() - px;
                    double tz = target.cornerZ() - pz;
                    double dist = Math.hypot(tx, tz);
                    boolean dived = dist <= 12;
                    double yaw = Math.atan2(tz, tx) + Math.toRadians(rng.nextGaussian() * noiseDeg);
                    Ray ray = new Ray(px, pz, Math.cos(yaw), Math.sin(yaw), dived);
                    boolean ok = est.addRay(ray);
                    if (!ok) {
                        res.rejected++;
                    }
                    trace.append(String.format("[t%d at %.0f,%.0f d=%.0f%s%s] ", throwsDone, px, pz, dist, dived ? " DIVE" : "", ok ? "" : " REJECT"));
                    walkedSince = 0;
                }
                case WALK, ARRIVE -> {
                    double dx = a.x() - px;
                    double dz = a.z() - pz;
                    double d = Math.hypot(dx, dz);
                    if (d < 0.5) {
                        res.stuck = true;
                        res.trace = trace.toString();
                        return res;
                    }
                    double move = Math.min(STEP, d);
                    px += dx / d * move;
                    pz += dz / d * move;
                    walkedSince += move;
                    res.walked += move;
                }
                case DIG -> {
                    Stronghold t = StrongholdRings.nearest(world, px, pz);
                    res.reachedDig = true;
                    res.throwsAtDig = throwsDone;
                    res.digErrorBlocks = Math.hypot(a.x() - 4 - t.cornerX(), a.z() - 4 - t.cornerZ());
                    res.trace = trace.toString();
                    return res;
                }
            }
        }
        res.stuck = true;
        res.trace = trace.toString();
        return res;
    }

    // random start the way the tests describe it: mostly near spawn, sometimes far out to hit the later rings
    static double[] randomStart(Random rng) {
        if (rng.nextDouble() < 0.2) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double r = 2500 + rng.nextDouble() * 2000;
            return new double[]{Math.cos(ang) * r, Math.sin(ang) * r};
        }
        double ang = rng.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(rng.nextDouble()) * 150;
        return new double[]{Math.cos(ang) * r, Math.sin(ang) * r};
    }

    private StrongholdSimulation() {
    }
}
