package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// pure: tells a pillager outpost from a patrol walking past. a patrol keeps moving, outpost pillagers stand around the
// tower, so pillagers that stay put for a while mark an outpost and only then do the trees and sheep near it get written
// off. one pillager standing still is not enough (a patrol captain stops too) and an outpost nobody has seen a live
// pillager at for a while expires, so a cleared outpost stops costing us its forest
public final class PillagerWatch {
    private static final double STAY_RADIUS = 12;
    // outposts closer than this to a known one are the same outpost, and pillagers this close to one keep it alive
    private static final double MERGE_RADIUS = 24;
    // distinct pillagers that have to be standing around before it is an outpost
    private static final int MIN_STAYERS = 2;
    // in whatever unit update() is fed, a few minutes of it
    private static final long DEFAULT_EXPIRE = 6000;

    private final long stayTicks;
    private final long expireTicks;
    private final Map<Integer, double[]> anchors = new HashMap<>();
    private final Map<Integer, Long> since = new HashMap<>();
    // {x, z, last tick a live pillager was near it}
    private final List<double[]> outposts = new ArrayList<>();
    private final List<double[]> expired = new ArrayList<>();

    public PillagerWatch(long stayTicks) {
        this(stayTicks, DEFAULT_EXPIRE);
    }

    public PillagerWatch(long stayTicks, long expireTicks) {
        this.stayTicks = stayTicks;
        this.expireTicks = expireTicks;
    }

    // positions: entity id -> {x, z} of every LIVE pillager we can see right now
    public void update(long tick, Map<Integer, double[]> positions) {
        anchors.keySet().retainAll(positions.keySet());
        since.keySet().retainAll(positions.keySet());
        List<double[]> stayers = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : positions.entrySet()) {
            double[] now = e.getValue();
            double[] anchor = anchors.get(e.getKey());
            if (anchor == null || Math.hypot(now[0] - anchor[0], now[1] - anchor[1]) > STAY_RADIUS) {
                anchors.put(e.getKey(), now);
                since.put(e.getKey(), tick);
            } else if (tick - since.get(e.getKey()) >= stayTicks) {
                stayers.add(anchor);
            }
        }
        // any live pillager near a known outpost keeps it alive, it does not have to be standing still
        for (double[] o : outposts) {
            for (double[] now : positions.values()) {
                if (Math.hypot(o[0] - now[0], o[1] - now[1]) < MERGE_RADIUS) {
                    o[2] = tick;
                    break;
                }
            }
        }
        for (double[] anchor : stayers) {
            int together = 0;
            for (double[] other : stayers) {
                if (Math.hypot(other[0] - anchor[0], other[1] - anchor[1]) < MERGE_RADIUS) {
                    together++;
                }
            }
            if (together >= MIN_STAYERS) {
                addOutpost(anchor, tick);
            }
        }
        for (int i = outposts.size() - 1; i >= 0; i--) {
            if (tick - outposts.get(i)[2] > expireTicks) {
                expired.add(outposts.remove(i));
            }
        }
    }

    private void addOutpost(double[] at, long tick) {
        for (double[] known : outposts) {
            if (Math.hypot(known[0] - at[0], known[1] - at[1]) < MERGE_RADIUS) {
                known[2] = tick;
                return;
            }
        }
        outposts.add(new double[]{at[0], at[1], tick});
    }

    public boolean nearOutpost(double x, double z, double radius) {
        for (double[] o : outposts) {
            if (Math.hypot(o[0] - x, o[1] - z) <= radius) {
                return true;
            }
        }
        return false;
    }

    // {x, z} of the first known outpost within radius, null when none. for the "near the outpost at x z" ban line
    public double[] outpostNear(double x, double z, double radius) {
        for (double[] o : outposts) {
            if (Math.hypot(o[0] - x, o[1] - z) <= radius) {
                return new double[]{o[0], o[1]};
            }
        }
        return null;
    }

    public int outposts() {
        return outposts.size();
    }

    // {x, z} of outposts that ran out since the last call, for whoever banned things around them to lift it
    public List<double[]> drainExpired() {
        List<double[]> out = new ArrayList<>(expired);
        expired.clear();
        return out;
    }
}
