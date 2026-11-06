package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StoneReachPickTest {
    private static final double BEST = 5.0;

    private static StoneDigRank.Scored at(int x, double score) {
        return new StoneDigRank.Scored(new BlockPos(x, 64, 0), score);
    }

    private static BlockPos pick(List<StoneDigRank.Scored> list, Set<Integer> reachableX) {
        return StoneDigRank.preferReachable(list, BEST, StoneDigRank.REACH_MARGIN, StoneDigRank.REACH_CHECKS, p -> reachableX.contains(p.getX()));
    }

    @Test
    public void aHittableOnePastTheMarginDoesNotWin() {
        // in reach but a shaft away: walking to the best is still the deal
        assertNull(pick(List.of(at(1, BEST + StoneDigRank.REACH_MARGIN + 0.01)), Set.of(1)));
    }

    @Test
    public void rightAtTheMarginStillCounts() {
        assertEquals(new BlockPos(1, 64, 0), pick(List.of(at(1, BEST + StoneDigRank.REACH_MARGIN)), Set.of(1)));
    }

    @Test
    public void theBestScoringHittableOneWins() {
        List<StoneDigRank.Scored> list = List.of(at(1, BEST + 1.5), at(2, BEST + 0.2), at(3, BEST + 0.9));
        assertEquals(new BlockPos(2, 64, 0), pick(list, Set.of(1, 2, 3)));
        // and the order is by score, not by the list: 2 is out of reach, 3 is next
        assertEquals(new BlockPos(3, 64, 0), pick(list, Set.of(1, 3)));
    }

    @Test
    public void noMoreThanFourRaycasts() {
        List<StoneDigRank.Scored> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            list.add(at(i, BEST + 0.1 * i));
        }
        int[] casts = {0};
        BlockPos got = StoneDigRank.preferReachable(list, BEST, StoneDigRank.REACH_MARGIN, StoneDigRank.REACH_CHECKS, p -> {
            casts[0]++;
            return p.getX() == 6;
        });
        // the hittable one is fifth in line, it never gets asked
        assertNull(got);
        assertEquals(StoneDigRank.REACH_CHECKS, casts[0]);
    }

    @Test
    public void nothingInReachIsNull() {
        assertNull(pick(List.of(at(1, BEST), at(2, BEST + 1)), Set.of()));
        assertNull(pick(List.of(), Set.of(1)));
    }
}
