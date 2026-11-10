package baritone.pathing.movement;

import adris.altoclef.util.helpers.MobThreat;
import adris.altoclef.util.helpers.PlaceWord;
import adris.altoclef.util.helpers.RiskField;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RiskCostTest {
    private static final double WALK = ActionCosts.WALK_ONE_BLOCK_COST;
    private static final double[] NO_DEATHS = new double[PlaceWord.WORDS.length];

    private static double[] table(String dim, double lambda) {
        return RiskCost.perTick(lambda, RiskCost.defaults(dim), NO_DEATHS, RiskCost.DEATH_TICKS);
    }

    @Test
    public void theDefaultTableIsV0AtAWalkAndFullHealth() {
        double[] nether = table("nether", RiskCost.LAMBDA);
        assertEquals(CliffAvoidance.SEA_NODE_COST, nether[PlaceWord.SEA] * WALK, 1e-9);
        assertEquals(CliffAvoidance.NODE_COST, nether[PlaceWord.RIM] * WALK, 1e-9);
        assertEquals(CliffAvoidance.FORTRESS_NODE_COST, nether[PlaceWord.FORT_RIM] * WALK, 1e-9);
        assertEquals(CliffAvoidance.NODE_COST, nether[PlaceWord.LAVA] * WALK, 1e-9);
        assertEquals(0, nether[PlaceWord.FORTRESS], 0);
        assertEquals(0, nether[PlaceWord.OPEN], 0);
        assertEquals(0, nether[PlaceWord.CAVE], 0);
        // the overworld: lava near costs, a plain drop does not (v0 priced it only in the nether and the End)
        double[] overworld = table("overworld", RiskCost.LAMBDA);
        assertEquals(CliffAvoidance.NODE_COST, overworld[PlaceWord.LAVA] * WALK, 1e-9);
        assertEquals(0, overworld[PlaceWord.RIM], 0);
        assertEquals(0, overworld[PlaceWord.FORT_RIM], 0);
        assertEquals(CliffAvoidance.NODE_COST, table("end", RiskCost.LAMBDA)[PlaceWord.RIM] * WALK, 1e-9);
    }

    @Test
    public void anHpIsWorthMoreAtLowHealth() {
        assertEquals(80, RiskCost.lambda(20, 80, 1), 1e-9);
        assertEquals(160, RiskCost.lambda(10, 80, 1), 1e-9);
        assertEquals(320, RiskCost.lambda(5, 80, 1), 1e-9);
        // absorption above full health: a little less
        assertEquals(80 * 20 / 24.0, RiskCost.lambda(24, 80, 1), 1e-9);
        // the curve at 0: flat
        assertEquals(80, RiskCost.lambda(5, 80, 0), 1e-9);
        // no health is read as one hp, never a division by nothing
        assertEquals(1600, RiskCost.lambda(0, 80, 1), 1e-9);
        // and the place costs scale with it
        assertEquals(2 * CliffAvoidance.NODE_COST, table("nether", RiskCost.lambda(10, 80, 1))[PlaceWord.RIM] * WALK, 1e-9);
    }

    @Test
    public void theCheapestPlaceCostsNothing() {
        double[] hp = {40, 10, 12, 8, 3, 2.3, 4};
        double[] cost = RiskCost.perTick(80, hp, NO_DEATHS, RiskCost.DEATH_TICKS);
        assertEquals(0, cost[PlaceWord.OPEN], 0);
        assertEquals(80 * (12 - 2.3) / 1200, cost[PlaceWord.RIM], 1e-9);
        assertEquals(80 * (40 - 2.3) / 1200, cost[PlaceWord.SEA], 1e-9);
    }

    @Test
    public void aDeathCostsTheSameAtAnyHealth() {
        double[] hp = new double[PlaceWord.WORDS.length];
        double[] die = new double[PlaceWord.WORDS.length];
        die[PlaceWord.SEA] = 2;
        // two deaths an hour at sea and half an hour lost to each: one tick of cost a tick there
        double full = RiskCost.perTick(RiskCost.lambda(20, 80, 1), hp, die, 36000)[PlaceWord.SEA];
        double low = RiskCost.perTick(RiskCost.lambda(4, 80, 1), hp, die, 36000)[PlaceWord.SEA];
        assertEquals(1, full, 1e-9);
        assertEquals(full, low, 1e-9);
    }

    @Test
    public void aNegativeRateCountsAsNone() {
        double[] hp = {-5, 0, 0, 0, 0, 0, 0};
        double[] cost = RiskCost.perTick(80, hp, NO_DEATHS, RiskCost.DEATH_TICKS);
        for (double c : cost) assertEquals(0, c, 0);
    }

    @Test
    public void aMobTheGoalIsAtIsNoHazardOfTheWay() {
        RiskField.Source zombie = new RiskField.Source(1, MobThreat.of("zombie", 1), 10.5, 64, 0.5, 1, 1, true);
        // a fight's goal two blocks off the mob
        assertTrue(RiskCost.atGoal(new GoalBlock(12, 64, 0), zombie));
        assertFalse(RiskCost.atGoal(new GoalBlock(20, 64, 0), zombie));
        assertFalse(RiskCost.atGoal(null, zombie));
    }
}
