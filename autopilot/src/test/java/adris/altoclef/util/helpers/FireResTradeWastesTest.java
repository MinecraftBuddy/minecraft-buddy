package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.FireResTradeRules.Spot;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

// Tuning fr_trade_biome: which loaded chunk of nether wastes the trade's search walks to
public class FireResTradeWastesTest {

    private static Spot pick(List<Spot> candidates, Spot spawner, List<Spot> bastions) {
        return FireResTradeRules.wastesTarget(candidates, 0, 0, spawner, bastions);
    }

    @Test
    public void withoutASpawnerTheNearest() {
        List<Spot> candidates = List.of(new Spot(100, 0), new Spot(30, 40), new Spot(-60, 0));
        assertEquals(new Spot(30, 40), pick(candidates, null, List.of()));
    }

    // the near one off to the side costs 56 blocks more on the way, the far one on the way 1
    @Test
    public void withASpawnerTheFewestExtraBlocksOnTheWay() {
        Spot spawner = new Spot(200, 0);
        List<Spot> candidates = List.of(new Spot(0, 50), new Spot(100, 10));
        assertEquals(new Spot(100, 10), pick(candidates, spawner, List.of()));
        assertEquals(56, FireResTradeRules.extraOnTheWay(0, 0, new Spot(0, 50), spawner));
        assertEquals(1, FireResTradeRules.extraOnTheWay(0, 0, new Spot(100, 10), spawner));
    }

    // extra counts in whole blocks: of two on the way that cost the same, the nearer
    @Test
    public void ofEqualExtraTheNearer() {
        Spot spawner = new Spot(200, 0);
        List<Spot> candidates = List.of(new Spot(104, 8), new Spot(40, 8));
        assertEquals(new Spot(40, 8), pick(candidates, spawner, List.of()));
    }

    @Test
    public void noneWhenNoCandidate() {
        assertNull(pick(List.of(), null, List.of()));
        assertNull(pick(List.of(), new Spot(200, 0), List.of()));
    }

    // never towards a bastion: a candidate within 64 blocks of a known bastion block is skipped
    @Test
    public void aCandidateByABastionIsSkipped() {
        List<Spot> candidates = List.of(new Spot(20, 0), new Spot(-90, 0));
        List<Spot> bastions = List.of(new Spot(70, 10));
        assertEquals(new Spot(-90, 0), pick(candidates, null, bastions));
        assertNull(pick(List.of(new Spot(20, 0)), null, bastions));
    }

    // the trade gives up by the spawner, so wastes there are no place to search
    @Test
    public void aCandidateByTheSpawnerIsSkipped() {
        Spot spawner = new Spot(200, 0);
        List<Spot> candidates = List.of(new Spot(190, 8), new Spot(60, 30));
        assertEquals(new Spot(60, 30), pick(candidates, spawner, List.of()));
    }
}
