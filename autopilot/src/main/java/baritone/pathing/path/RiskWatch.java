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

package baritone.pathing.path;

import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.RiskCost;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// the buddy's fork (S137 step 2, risk in the path search): the walk checks the rest of its path against each new version of
// the mob field (RiskField), under Tuning risk_mobs. two reasons to plan again at once:
//   more: the rest is expected to cost much more hp than when the walk began on it (a mob came, or now wants us), by over
//     risk_replan_more ticks at lambda
//   less: a mob that steered the search (added at least risk_replan_less ticks to its moves) has left the field while close
//     (dead, despawned, calm now), so the detour it forced does not outlive it. one that walked out of the field far off
//     only fell behind
// at most one such replan every risk_replan_gap ticks, over all paths: a mob that keeps coming and going does not keep the
// buddy searching
public final class RiskWatch {

    // an hp at full health
    static final double MORE = 80;
    // two hp at full health
    public static final double LESS = 160;
    static final double GAP = 20;
    // a steering mob last seen closer than this that is gone from the field has gone; farther, it walked out of it
    static final double CLOSE = 12;

    private static long lastReplan = Long.MIN_VALUE / 2;

    // the version last checked, -1 before the first
    private long version = -1;
    // per movement: the hp the rest was expected to cost when the walk began on it
    private double[] base;
    // the mobs that steered the search, and where each was last seen
    private int[] shaped = new int[0];
    private final Map<Integer, double[]> lastSeen = new HashMap<>();

    void shapedBy(int[] ids) {
        shaped = ids;
    }

    int[] shaped() {
        return shaped;
    }

    // the same mobs for a path that replaces this one (spliced or cut): its own baseline at its first check
    RiskWatch carry(int[] more) {
        RiskWatch next = new RiskWatch();
        next.shaped = java.util.stream.IntStream.concat(java.util.Arrays.stream(shaped), java.util.Arrays.stream(more)).distinct().toArray();
        next.lastSeen.putAll(lastSeen);
        return next;
    }

    // the reason to plan again now, or null. game thread, a tick the movement can be cancelled in
    String check(IPath path, int pos, CalculationContext context, BetterBlockPos feet, long now) {
        if (Tuning.get("risk_mobs", 0) < 1) {
            return null;
        }
        RiskField.Version field = RiskField.current();
        if (field.id() == version) {
            return null;
        }
        long previous = version;
        version = field.id();
        RiskField.Pricer pricer = RiskCost.pricer(context, field, path.getGoal(), 0);
        double[] rest = rest(path, pos, pricer);
        if (previous < 0 || base == null) {
            base = rest;
            see(field);
            return null;
        }
        String why = null;
        double was = sum(base, pos);
        double is = sum(rest, pos);
        double lambda = RiskCost.lambda(context.health, Tuning.get("risk_lambda", RiskCost.LAMBDA), Tuning.get("risk_lambda_curve", 1));
        if (lambda * (is - was) > Tuning.get("risk_replan_more", MORE)) {
            why = String.format(Locale.ROOT, "more, %.1f hp expected on the rest against %.1f planned", is, was);
        }
        Integer gone = null;
        if (why == null) {
            gone = gone(field, feet);
            if (gone != null) {
                why = "less, a mob that steered the search has gone";
            }
        }
        if (why == null) {
            see(field);
            return null;
        }
        if (now - lastReplan < Tuning.get("risk_replan_gap", GAP)) {
            // not yet: the same version is checked again next tick
            version = previous;
            return null;
        }
        lastReplan = now;
        if (gone != null) {
            lastSeen.remove(gone);
        }
        return why + ", " + (path.movements().size() - pos) + " moves left";
    }

    private void see(RiskField.Version field) {
        for (int id : shaped) {
            RiskField.Source s = field.source(id);
            if (s != null) {
                lastSeen.put(id, new double[]{s.x(), s.y(), s.z()});
            }
        }
    }

    // a steering mob that was close when last seen and is not in this version
    private Integer gone(RiskField.Version field, BetterBlockPos feet) {
        for (int id : shaped) {
            double[] at = lastSeen.get(id);
            if (at == null || field.source(id) != null) {
                continue;
            }
            double dx = at[0] - (feet.x + 0.5), dy = at[1] - feet.y, dz = at[2] - (feet.z + 0.5);
            if (dx * dx + dy * dy + dz * dz <= CLOSE * CLOSE) {
                return id;
            }
            // walked out of the field far off: forget it
            lastSeen.remove(id);
        }
        return null;
    }

    // per movement from pos on: the hp the mobs are expected to take in the cell it ends in, at the tick the walk gets there
    static double[] rest(IPath path, int pos, RiskField.Pricer pricer) {
        List<IMovement> moves = path.movements();
        List<BetterBlockPos> at = path.positions();
        double[] out = new double[moves.size()];
        if (pricer == null) {
            return out;
        }
        double arrival = 0;
        for (int i = Math.max(0, pos); i < moves.size() && i + 1 < at.size(); i++) {
            double cost = moves.get(i).getCost();
            arrival += cost;
            BetterBlockPos p = at.get(i + 1);
            out[i] = pricer.hp(p.x, p.y, p.z, arrival, Math.min(cost, RiskCost.EXPOSURE_CAP), null);
        }
        return out;
    }

    static double sum(double[] hp, int from) {
        double total = 0;
        for (int i = Math.max(0, from); i < hp.length; i++) {
            total += hp[i];
        }
        return total;
    }
}
