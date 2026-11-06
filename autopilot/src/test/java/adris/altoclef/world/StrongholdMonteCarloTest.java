package adris.altoclef.world;

import adris.altoclef.world.StrongholdEstimator.Params;
import adris.altoclef.world.StrongholdRings.Stronghold;
import adris.altoclef.world.StrongholdSimulation.Result;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// monte carlo over real vanilla ring layouts: 300 random world seeds, a bot that follows advise() to the letter, eyes with
// gaussian bearing noise. fixed rng seed so it is the same 300 worlds every run (and fast, a second or two for all of them)
public class StrongholdMonteCarloTest {
    private static final int RUNS = 300;
    private static final long RNG_SEED = 20260101L;
    // the sigma the estimator assumes, the noise level of the world is what varies
    private static final Params DEFAULT = Params.defaults();

    static final class Stats {
        final String label;
        int runs;
        int arriveOrDigInTen;
        int dugInFourteen;
        int reachedDig;
        int accurate;
        int stuck;
        int rejected;
        double ratioSum;
        final List<Integer> throwsAtDig = new ArrayList<>();
        final List<String> worst = new ArrayList<>();

        Stats(String label) {
            this.label = label;
        }

        double pct(int n, int of) {
            return of == 0 ? 100 : 100.0 * n / of;
        }

        int percentileThrows(double p) {
            if (throwsAtDig.isEmpty()) {
                return -1;
            }
            List<Integer> sorted = new ArrayList<>(throwsAtDig);
            Collections.sort(sorted);
            return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
        }

        double avgThrows() {
            return throwsAtDig.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
        }

        double avgRatio() {
            return reachedDig == 0 ? Double.NaN : ratioSum / reachedDig;
        }

        @Override
        public String toString() {
            return String.format("%-28s runs=%d  arrive/dig<=10 throws: %.1f%%  dig<=14: %.1f%%  dig error<=24: %.1f%% of dug  "
                            + "stuck=%d  walk/straight=%.2f  throws at dig avg=%.2f p95=%d max=%d  rejected rays=%d",
                    label, runs, pct(arriveOrDigInTen, runs), pct(dugInFourteen, runs), pct(accurate, reachedDig),
                    stuck, avgRatio(), avgThrows(), percentileThrows(0.95), percentileThrows(1.0), rejected);
        }
    }

    // mode: 0 = the mixed 80/20 start distribution, 1 = near spawn only, 2 = far out only
    static Stats run(String label, double noiseDeg, Params params, int mode) {
        Random rng = new Random(RNG_SEED);
        Stats st = new Stats(label);
        for (int i = 0; i < RUNS; i++) {
            long seed = rng.nextLong();
            double[] start = StrongholdSimulation.randomStart(rng);
            boolean far = Math.hypot(start[0], start[1]) > 1000;
            if ((mode == 1 && far) || (mode == 2 && !far)) {
                // keep the rng stream identical across modes by still consuming the run's draws
                continue;
            }
            List<Stronghold> world = StrongholdRings.generate(seed);
            Result r = StrongholdSimulation.run(world, start[0], start[1], noiseDeg, params, rng);
            st.runs++;
            st.rejected += r.rejected;
            if (r.stuck) {
                st.stuck++;
            }
            if (r.throwsAtFirstArriveOrDig >= 0 && r.throwsAtFirstArriveOrDig <= 10) {
                st.arriveOrDigInTen++;
            }
            if (r.reachedDig) {
                st.reachedDig++;
                st.throwsAtDig.add(r.throwsAtDig);
                st.ratioSum += r.walked / Math.max(1, r.straightLine);
                if (r.throwsAtDig <= 14) {
                    st.dugInFourteen++;
                }
                if (r.digErrorBlocks <= 24) {
                    st.accurate++;
                } else {
                    st.worst.add("seed " + seed + " start " + (int) start[0] + "," + (int) start[1] + " err " + r.digErrorBlocks);
                }
            }
        }
        System.out.println("[monte carlo] " + st);
        return st;
    }

    private static void assertHonest(Stats s, double minArrivePct, double minDigPct, double minAccuratePct) {
        assertEquals("nothing may deadlock: " + s, 0, s.stuck);
        assertTrue("arrive/dig within 10 throws: " + s, s.pct(s.arriveOrDigInTen, s.runs) >= minArrivePct);
        assertTrue("dug within 14 throws: " + s, s.pct(s.dugInFourteen, s.runs) >= minDigPct);
        assertTrue("dig spot within 24 blocks of the corner: " + s, s.pct(s.accurate, s.reachedDig) >= minAccuratePct);
        assertTrue("walked too much compared to the straight line: " + s, s.avgRatio() <= 1.6);
    }

    @Test
    public void realisticNoiseConvergesFast() {
        Stats s = run("sigma 0.25 (assumed 0.25)", 0.25, DEFAULT, 0);
        assertEquals(RUNS, s.runs);
        assertHonest(s, 95, 95, 95);
    }

    @Test
    public void quietNoiseIsNoWorse() {
        Stats s = run("sigma 0.05 (assumed 0.25)", 0.05, DEFAULT, 0);
        assertHonest(s, 95, 95, 95);
    }

    @Test
    public void nearSpawnStartsAndFarOutStartsBothWork() {
        Stats near = run("sigma 0.25, near spawn", 0.25, DEFAULT, 1);
        Stats far = run("sigma 0.25, far out", 0.25, DEFAULT, 2);
        assertTrue(near.runs > 200 && far.runs > 30);
        assertHonest(near, 95, 95, 95);
        assertHonest(far, 90, 90, 90);
    }

    @Test
    public void aOneDegreeStressStillFindsItWithTheOptimisticSigma() {
        // four times the noise the estimator assumes. not a target, a check that it degrades instead of breaking
        Stats s = run("sigma 1.0 (assumed 0.25)", 1.0, DEFAULT, 0);
        assertHonest(s, 75, 90, 95);
    }

    @Test
    public void aOneDegreeStressWithTheRightSigma() {
        Stats s = run("sigma 1.0 (assumed 1.0)", 1.0, new Params(1.0, 12, 12), 0);
        assertHonest(s, 75, 90, 95);
    }

    @Test
    public void runsAreDeterministic() {
        Stats a = run("determinism a", 0.25, DEFAULT, 0);
        Stats b = run("determinism b", 0.25, DEFAULT, 0);
        assertEquals(a.toString().replace("determinism a", ""), b.toString().replace("determinism b", ""));
    }

    @Test
    public void everyRunTerminatesWithoutAnException() {
        // 0.25 and 1.0 degrees from a spread of starts, run() throws if anything inside blows up
        for (double noise : new double[]{0.0, 0.25, 1.0, 3.0}) {
            Stats s = run("termination noise " + noise, noise, DEFAULT, 0);
            assertEquals(RUNS, s.runs);
        }
    }
}
