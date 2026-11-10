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

package baritone.pathing.movement;

import adris.altoclef.util.helpers.PlaceWord;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.ExperimentalMovement;

// the buddy's fork (S137 step 1, risk in the path search): what the search adds to a node for the kind of place it ends in.
// a path costs its ticks plus lambda times the hp it is expected to lose, plus what a death would throw away:
//   Tuning risk_table 0 (the default): CliffAvoidance.penalty as it was, a hand-set cost a node (v0)
//   Tuning risk_table 1: the node's place word (PlaceWord) priced from a table of hp lost a minute and deaths an hour in
//     that place and dimension (risk_hp_<dim>_<word>, risk_die_<dim>_<word>, measured by the rig's riskmap), times the
//     time the move into the node takes. lambda, ticks an hp: risk_lambda at full health, more at low health
//     (risk_lambda_curve), from the health at the search's start. a death costs risk_death ticks whatever the health: a
//     knock into the lava sea costs the run, not hp. CalculationContext's cliffEdges and lavaEdges (Tuning
//     nether_lava_edges) do not apply under the table: its keys say what each place costs in each dimension
// A* only, as CliffAvoidance is, never calculateCost: the executor's per tick recheck would cancel a good path over it
public final class RiskCost {

    public static final double TICKS_A_MINUTE = 1200;
    public static final double TICKS_AN_HOUR = 72000;
    // ticks an hp at full health (his decision, 10 Nov 2026: about the 4 s one hp takes to come back at full food)
    public static final double LAMBDA = 80;
    // the health lambda is set at: below it an hp is worth more, (FULL / hp) to the curve's power
    public static final double FULL = 20;
    // what a death throws away, in ticks: half an hour of the run (a placeholder until the table is measured: no place has
    // a death rate before then)
    public static final double DEATH_TICKS = 36000;
    // the longest a single move counts as time spent in its place. a move's cost is its time, except where the search adds a
    // price that is not time (a block from a short bag, BridgeBudget); a second is about a bridge step with its block placed
    public static final double EXPOSURE_CAP = 20;

    // the table's defaults: v0 as rates, so a word the rig has not measured costs at a walk's time and full health what
    // CliffAvoidance charged it (rim 18.75 hp a minute, sea 300, fortress rim a third of rim, lava as rim). outside the
    // nether and the End v0 priced only lava below, never a plain drop: rim 0 there
    static final double RIM_RATE = CliffAvoidance.NODE_COST / ActionCosts.WALK_ONE_BLOCK_COST * TICKS_A_MINUTE / LAMBDA;
    static final double SEA_RATE = CliffAvoidance.SEA_NODE_COST / ActionCosts.WALK_ONE_BLOCK_COST * TICKS_A_MINUTE / LAMBDA;
    static final double FORT_RIM_RATE = CliffAvoidance.FORTRESS_NODE_COST / ActionCosts.WALK_ONE_BLOCK_COST * TICKS_A_MINUTE / LAMBDA;

    private RiskCost() {}

    // one search's prices and memos, built at its start: the health, the keys and the columns as they are then
    public static final class Search {
        final CliffAvoidance.Memo cliffs = new CliffAvoidance.Memo();
        // null under table 0
        final PlaceWord.Memo words;
        final double[] perTick;
        public final int table;
        public final double lambda;

        Search(int table, double lambda, double[] perTick, PlaceWord.Memo words) {
            this.table = table;
            this.lambda = lambda;
            this.perTick = perTick;
            this.words = words;
        }
    }

    public static Search start(CalculationContext context) {
        int table = (int) Tuning.get("risk_table", 0);
        if (table != 1) {
            return new Search(0, 0, null, null);
        }
        String dim = ExperimentalMovement.inNether(context.world) ? "nether" : ExperimentalMovement.inEnd(context.world) ? "end" : "overworld";
        double lambda = lambda(context.health, Tuning.get("risk_lambda", LAMBDA), Tuning.get("risk_lambda_curve", 1));
        double[] hp = new double[PlaceWord.WORDS.length];
        double[] die = new double[PlaceWord.WORDS.length];
        double[] fallback = defaults(dim);
        for (int w = 0; w < hp.length; w++) {
            hp[w] = Tuning.get("risk_hp_" + dim + "_" + PlaceWord.WORDS[w], fallback[w]);
            die[w] = Tuning.get("risk_die_" + dim + "_" + PlaceWord.WORDS[w], 0);
        }
        double[] perTick = perTick(lambda, hp, die, Tuning.get("risk_death", DEATH_TICKS));
        return new Search(1, lambda, perTick, new PlaceWord.Memo(CliffAvoidance.of(context.bsi)));
    }

    // the extra cost of ending a move with our feet at (x, y, z); moveCost is that move's own cost in ticks
    public static double penalty(CalculationContext context, Search search, int x, int y, int z, double moveCost) {
        if (search.table != 1) {
            return CliffAvoidance.penalty(context, search.cliffs, x, y, z);
        }
        boolean fortress = CliffAvoidance.onFortressBricks(context.get(x, y - 1, z));
        return search.perTick[search.words.index(fortress, x, y, z)] * Math.min(moveCost, EXPOSURE_CAP);
    }

    // for the search's log line: which table priced it, and lambda under the table
    public static String describe(Search search) {
        return search.table != 1 ? "" : String.format(java.util.Locale.ROOT, " table 1 lambda %.0f", search.lambda);
    }

    // ticks an hp at this health (hp plus absorption): base at FULL, (FULL / hp) to the curve's power below it, and less
    // above it with absorption. an hp at 10 is worth twice one at 20 with the curve at 1; 0 makes it flat
    static double lambda(double health, double base, double curve) {
        return base * Math.pow(FULL / Math.max(1, health), curve);
    }

    // ticks of cost per tick spent in each place: lambda times the hp it loses, plus the chance of a death times what one
    // throws away. the cheapest place's cost is taken off every place: the search's heuristic knows nothing of risk, so a
    // cost every node pays only widens the search without changing which way it goes
    static double[] perTick(double lambda, double[] hpAMinute, double[] deathsAnHour, double deathTicks) {
        double[] cost = new double[hpAMinute.length];
        double floor = Double.MAX_VALUE;
        for (int w = 0; w < cost.length; w++) {
            cost[w] = lambda * Math.max(0, hpAMinute[w]) / TICKS_A_MINUTE + Math.max(0, deathsAnHour[w]) / TICKS_AN_HOUR * deathTicks;
            floor = Math.min(floor, cost[w]);
        }
        for (int w = 0; w < cost.length; w++) {
            cost[w] -= floor;
        }
        return cost;
    }

    // hp a minute by word (PlaceWord.WORDS' order) where the rig has set none
    static double[] defaults(String dim) {
        boolean cliffs = dim.equals("nether") || dim.equals("end");
        return new double[]{
                cliffs ? SEA_RATE : RIM_RATE,   // sea: outside the nether and the End, priced as lava
                cliffs ? FORT_RIM_RATE : 0,     // fort_rim
                cliffs ? RIM_RATE : 0,          // rim
                RIM_RATE,                       // lava
                0, 0, 0                         // fortress, open, cave
        };
    }
}
