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

import adris.altoclef.util.baritone.GoalFlee;
import adris.altoclef.util.helpers.PlaceWord;
import adris.altoclef.util.helpers.RiskField;
import adris.altoclef.util.helpers.Tuning;
import baritone.api.pathing.goals.Goal;
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
// the mobs (S137 step 2): Tuning risk_mobs 1 adds lambda times the hp the live mob field (RiskField) expects the mobs to
// take while we are in the node, at the tick we get there. the field is the newest version at the search's start, the
// health the search's; a mob the goal is at (the one we go to fight) is left out. the walk checks the rest of its path
// against each new version (RiskWatch). off, the field is still priced along the chosen path for the search line
// the flee search (S137 step 3): a search toward a GoalFlee prices the mobs whatever risk_mobs says, with lambda times
// risk_flee_lambda, and binds the goal to its own view of the field (GoalFlee.Judge) before its first node
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
    // how far ahead the field guesses where a melee mob can be, in seconds: DangerMeter's horizon
    public static final double MOB_HORIZON = 3;
    // sight lines a search may cast for the shooters; past it a cell in a shooter's range counts as seen
    public static final double MOB_RAYS = 20000;

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
        // the mob field as this search sees it, null when it has no mob that counts; priced in the search only under
        // risk_mobs, with lambda at the search's health. charged: the ticks each mob added to the search's moves
        public RiskField.Pricer mobs;
        public boolean mobsPriced;
        public double mobLambda;
        double[] charged;

        Search(int table, double lambda, double[] perTick, PlaceWord.Memo words) {
            this.table = table;
            this.lambda = lambda;
            this.perTick = perTick;
            this.words = words;
        }
    }

    public static Search start(CalculationContext context) {
        return start(context, null, 0);
    }

    // goal: the search's (a mob at it is not priced), offset: the ticks before its start node is reached
    public static Search start(CalculationContext context, Goal goal, double offset) {
        Search search = places(context);
        RiskField.Version field = RiskField.current();
        GoalFlee flee = goal instanceof GoalFlee f ? f : null;
        // (a flee has no mob it goes to: every one counts)
        RiskField.Pricer mobs = pricer(context, field, flee != null ? null : goal, offset);
        double lambda = lambda(context.health, Tuning.get("risk_lambda", LAMBDA), Tuning.get("risk_lambda_curve", 1));
        if (flee != null) {
            lambda *= GoalFlee.lambdaFactor();
            flee.bind(new GoalFlee.Judge(field, mobs, CliffAvoidance.of(context.bsi), GoalFlee.calm(), lambda, Thread.currentThread()));
        }
        if (mobs == null) {
            return search;
        }
        search.mobs = mobs;
        search.mobsPriced = flee != null || Tuning.get("risk_mobs", 0) >= 1;
        search.mobLambda = lambda;
        search.charged = new double[mobs.size()];
        return search;
    }

    // a version of the mob field as a search (or the walk) toward this goal prices it; null when no mob in it counts
    public static RiskField.Pricer pricer(CalculationContext context, RiskField.Version field, Goal goal, double offset) {
        if (field.world() != context.world || field.sources().isEmpty()) {
            return null;
        }
        RiskField.Pricer mobs = new RiskField.Pricer(field, CliffAvoidance.of(context.bsi), offset,
                Tuning.get("risk_mob_horizon", MOB_HORIZON) * 20, (int) Tuning.get("risk_mob_rays", MOB_RAYS), s -> atGoal(goal, s));
        return mobs.isEmpty() ? null : mobs;
    }

    private static Search places(CalculationContext context) {
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

    // what the mobs add to a move into (x, y, z) that ends `arrival` ticks after the search's start: lambda times the hp they
    // are expected to take while we are there (the move's time, at most EXPOSURE_CAP). nothing without risk_mobs
    public static double mobs(Search search, int x, int y, int z, double moveCost, double arrival) {
        if (!search.mobsPriced) {
            return 0;
        }
        return search.mobLambda * search.mobs.hp(x, y, z, arrival, Math.min(moveCost, EXPOSURE_CAP), search.charged);
    }

    // the same hp, uncounted and priced or not: for the search line and the walk
    public static double mobHp(Search search, int x, int y, int z, double moveCost, double arrival) {
        if (search.mobs == null) {
            return 0;
        }
        return search.mobs.hp(x, y, z, arrival, Math.min(moveCost, EXPOSURE_CAP), null);
    }

    // the mobs that added at least minTicks to the search's moves: the ones that steered it
    public static int[] shaped(Search search, double minTicks) {
        if (search == null || search.charged == null || !search.mobsPriced) {
            return new int[0];
        }
        return java.util.stream.IntStream.range(0, search.charged.length)
                .filter(i -> search.mobLambda * search.charged[i] >= minTicks)
                .map(search.mobs::id).toArray();
    }

    // a mob the goal is at, or next to: the one a fight walks up to is no hazard of the way there
    public static boolean atGoal(Goal goal, RiskField.Source mob) {
        if (goal == null) {
            return false;
        }
        // (a fight's goal stands a block or two off the mob, whatever its reach)
        int r = Math.max(2, (int) Math.ceil(mob.row().reach())) + 1;
        int mx = (int) Math.floor(mob.x()), my = (int) Math.floor(mob.y()), mz = (int) Math.floor(mob.z());
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (goal.isInGoal(mx + dx, my + dy, mz + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // for the search's log line: which table priced it, and lambda under the table
    public static String describe(Search search) {
        return search.table != 1 ? "" : String.format(java.util.Locale.ROOT, " table 1 lambda %.0f", search.lambda);
    }

    // ticks an hp at this health (hp plus absorption): base at FULL, (FULL / hp) to the curve's power below it, and less
    // above it with absorption. an hp at 10 is worth twice one at 20 with the curve at 1; 0 makes it flat
    public static double lambda(double health, double base, double curve) {
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
