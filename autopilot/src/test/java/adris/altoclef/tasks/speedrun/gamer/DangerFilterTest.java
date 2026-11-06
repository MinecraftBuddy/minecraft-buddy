package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DangerFilterTest {
    @Test
    public void aTableWePlacedIsNeverAWitchTable() {
        List<RunState.Pos> placed = List.of(new RunState.Pos(10, 64, -5));
        assertTrue(DangerFilter.ownStation(placed, new BlockPos(10, 64, -5)));
        assertFalse(DangerFilter.ownStation(placed, new BlockPos(10, 65, -5)));
        assertFalse(DangerFilter.ownStation(List.of(), new BlockPos(10, 64, -5)));
        // no state yet (before the run begins) means nothing is ours
        assertFalse(DangerFilter.ownStation(null, new BlockPos(10, 64, -5)));
    }

    // two pillagers standing at the origin long enough to be an outpost, then nobody for longer than it lives
    private static PillagerWatch outpostThatWentQuiet() {
        PillagerWatch watch = new PillagerWatch(10, 100);
        Map<Integer, double[]> two = Map.of(1, new double[]{0, 0}, 2, new double[]{1, 1});
        watch.update(0, two);
        watch.update(10, two);
        assertEquals(1, watch.outposts());
        watch.update(200, Map.of());
        assertEquals(0, watch.outposts());
        return watch;
    }

    @Test
    public void aQuietOutpostLiftsOnlyTheOutpostBansAroundIt() {
        Bans bans = new Bans(line -> {
        });
        BanPolicy.outpost(bans, List.of(Bans.Key.block(Dimension.OVERWORLD, 5, 70, 5)), 0, 0);
        BanPolicy.outpost(bans, List.of(Bans.Key.block(Dimension.OVERWORLD, 300, 70, 5)), 300, 0);
        BanPolicy.blockStrike(bans, Dimension.OVERWORLD, 6, 70, 5, 0, 4, "couldn't reach it");
        BanPolicy.outpost(bans, List.of(Bans.Key.block(Dimension.OVERWORLD, 6, 70, 5)), 0, 0);
        assertEquals(2, DangerFilter.liftExpiredBans(bans, outpostThatWentQuiet()));
        assertFalse(bans.blockBanned(Dimension.OVERWORLD, 5, 70, 5));
        assertTrue("another outpost's", bans.blockBanned(Dimension.OVERWORLD, 300, 70, 5));
        assertTrue("unreachable anyway, not ours to lift", bans.blockBanned(Dimension.OVERWORLD, 6, 70, 5));
    }

    @Test
    public void nothingExpiredLiftsNothing() {
        Bans bans = new Bans(line -> {
        });
        BanPolicy.outpost(bans, List.of(Bans.Key.block(Dimension.OVERWORLD, 5, 70, 5)), 0, 0);
        assertEquals(0, DangerFilter.liftExpiredBans(bans, new PillagerWatch(10, 100)));
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 5, 70, 5));
    }

    @Test
    public void onePassIsOneLinePerOutpost() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        Bans bans = new Bans(lines::add);
        PillagerWatch watch = new PillagerWatch(10, 100000);
        Map<Integer, double[]> two = Map.of(1, new double[]{0, 0}, 2, new double[]{1, 1}, 3, new double[]{500, 0}, 4, new double[]{501, 1});
        watch.update(0, two);
        watch.update(10, two);
        assertEquals(2, watch.outposts());
        List<BlockPos> near = List.of(new BlockPos(5, 70, 5), new BlockPos(6, 70, 5), new BlockPos(505, 70, 0), new BlockPos(250, 70, 0));
        assertEquals(3, DangerFilter.banNearOutposts(bans, watch, Dimension.OVERWORLD, near));
        assertEquals(lines.toString(), 2, lines.size());
        assertTrue(bans.blockBanned(Dimension.OVERWORLD, 505, 70, 0));
        assertFalse("between the two, near neither", bans.blockBanned(Dimension.OVERWORLD, 250, 70, 0));
        // the next pass finds them all banned already and says nothing
        assertEquals(0, DangerFilter.banNearOutposts(bans, watch, Dimension.OVERWORLD, near));
        assertEquals(2, lines.size());
    }
}
