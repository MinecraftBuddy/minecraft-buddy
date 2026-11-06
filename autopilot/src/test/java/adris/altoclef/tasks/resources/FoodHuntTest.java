package adris.altoclef.tasks.resources;

import adris.altoclef.tasks.resources.FoodHunt.Candidate;
import adris.altoclef.tasks.resources.FoodHunt.Kind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// which animal gets chased: nearest meal per walk, not first in the list
public class FoodHuntTest {

    private static Candidate c(int id, Kind kind, double distance) {
        return new Candidate(id, kind, distance);
    }

    private static int pick(int current, boolean wool, Candidate... all) {
        Candidate best = FoodHunt.choose(List.of(all), current, wool);
        return best == null ? -99 : best.id();
    }

    @Test
    public void nobodyToChaseIsNull() {
        assertNull(FoodHunt.choose(List.of(), -1, false));
    }

    @Test
    public void nearChickenBeatsFarPig() {
        assertEquals(2, pick(-1, false, c(1, Kind.PIG, 30), c(2, Kind.CHICKEN, 5)));
    }

    @Test
    public void aPigNextDoorStillBeatsAChickenNextDoor() {
        assertEquals(1, pick(-1, false, c(1, Kind.PIG, 5), c(2, Kind.CHICKEN, 5)));
    }

    @Test
    public void nearCowBeatsNearerChicken() {
        assertEquals(1, pick(-1, false, c(1, Kind.COW, 10), c(2, Kind.CHICKEN, 6)));
    }

    @Test
    public void aRabbitIsWorthTheWalkWhenItIsTheOnlyMeat() {
        assertEquals(1, pick(-1, false, c(1, Kind.RABBIT, 20)));
        // but a chicken the same distance is more meat
        assertEquals(2, pick(-1, false, c(1, Kind.RABBIT, 10), c(2, Kind.CHICKEN, 10)));
    }

    @Test
    public void nearSheepBeatsFarPig() {
        // the bug report itself: the old rule took the pig regardless
        assertEquals(2, pick(-1, false, c(1, Kind.PIG, 80), c(2, Kind.SHEEP, 5)));
    }

    @Test
    public void verticalDistanceCountsDouble() {
        assertEquals(10.0, FoodHunt.distance(0, 5, 0), 1e-9);
        assertEquals(5.0, FoodHunt.distance(3, 0, 4), 1e-9);
    }

    @Test
    public void staysOnTheCurrentAnimalUnlessTheNewOneIsMuchBetter() {
        // chicken is chased, a pig at the same spot is 16/18 against 6/14: about 2x, so it wins and we turn around
        assertEquals(1, pick(2, false, c(1, Kind.PIG, 10), c(2, Kind.CHICKEN, 6)));
        // a cow two blocks closer than the one we run at is better, but nowhere near 1.5x, so we stay
        assertEquals(2, pick(2, false, c(1, Kind.COW, 12), c(2, Kind.COW, 14)));
        // and the pig we chase is fine even when a cow is a bit closer
        assertEquals(1, pick(1, false, c(1, Kind.PIG, 20), c(2, Kind.COW, 12)));
    }

    @Test
    public void thresholdIsOneAndAHalf() {
        Candidate current = c(1, Kind.COW, 12);
        double currentScore = FoodHunt.score(Kind.COW, 12, false);
        // find a distance for the challenger that sits just either side of 1.5x
        double justUnder = Kind.COW.units / (currentScore * 1.49) - FoodHunt.OVERHEAD_BLOCKS;
        double justOver = Kind.COW.units / (currentScore * 1.51) - FoodHunt.OVERHEAD_BLOCKS;
        assertEquals(1, pick(1, false, current, c(2, Kind.COW, justUnder)));
        assertEquals(2, pick(1, false, current, c(2, Kind.COW, justOver)));
    }

    @Test
    public void aDeadCurrentIsNotInTheListSoItIsForgotten() {
        // the caller drops dead/blacklisted animals, the id of the old one matches nobody and the best just wins
        assertEquals(2, pick(7, false, c(1, Kind.CHICKEN, 40), c(2, Kind.COW, 15)));
    }

    @Test
    public void sheepLoseTheirEdgeWhileTheKitWantsWool() {
        // sheep 9 units, cow 16: cow at 12 vs sheep at 3 -> sheep 0.82, cow 0.8. close enough to be a coin flip normally
        Candidate sheep = c(1, Kind.SHEEP, 3);
        Candidate cow = c(2, Kind.COW, 12);
        assertEquals(1, pick(-1, false, sheep, cow));
        assertEquals(2, pick(-1, true, sheep, cow));
    }

    @Test
    public void aMuchCloserSheepStillGetsEatenForWool() {
        // the penalty is 1.3x, not a ban: a sheep underfoot against a cow 40 blocks off is still dinner
        assertEquals(1, pick(-1, true, c(1, Kind.SHEEP, 2), c(2, Kind.COW, 40)));
    }

    @Test
    public void woolRuleDoesNotTouchOtherAnimals() {
        assertEquals(FoodHunt.score(Kind.COW, 10, false), FoodHunt.score(Kind.COW, 10, true), 1e-12);
    }

    @Test
    public void onlyTheSheepCandidateLeftStillWinsWhenWoolIsWanted() {
        assertEquals(1, pick(-1, true, c(1, Kind.SHEEP, 20)));
    }

    @Test
    public void aHuntInProgressIsKeptWhileAliveAndClose() {
        assertTrue(FoodHunt.keepHunting(true, 3));
        assertTrue(FoodHunt.keepHunting(true, FoodHunt.COMMIT_RADIUS));
    }

    @Test
    public void aHuntIsDroppedWhenTheAnimalIsDeadOrTooFar() {
        assertFalse(FoodHunt.keepHunting(false, 3));
        assertFalse(FoodHunt.keepHunting(true, FoodHunt.COMMIT_RADIUS + 0.1));
    }

    @Test
    public void onlyNearbyAnimalsAreWorthAKillOnTheWay() {
        List<Candidate> all = List.of(c(1, Kind.PIG, 5), c(2, Kind.COW, 7), c(3, Kind.CHICKEN, 6));
        List<Candidate> near = FoodHunt.within(all, FoodHunt.ALONG_THE_WAY_RADIUS);
        assertEquals(2, near.size());
        assertEquals(1, near.get(0).id());
        assertEquals(3, near.get(1).id());
        assertTrue(FoodHunt.within(List.of(c(1, Kind.PIG, 30)), FoodHunt.ALONG_THE_WAY_RADIUS).isEmpty());
    }

    @Test
    public void killOnTheWayPicksTheBestOfTheNearOnes() {
        // the cow at 9 is the better meal overall, but it is out of passing range
        List<Candidate> near = FoodHunt.within(List.of(c(1, Kind.COW, 9), c(2, Kind.CHICKEN, 4), c(3, Kind.PIG, 5)), FoodHunt.ALONG_THE_WAY_RADIUS);
        assertEquals(3, FoodHunt.choose(near, -1, false).id());
    }
}
