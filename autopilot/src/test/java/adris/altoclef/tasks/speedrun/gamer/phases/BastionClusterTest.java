package adris.altoclef.tasks.speedrun.gamer.phases;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// a handful of bastion blocks is a ruined portal, a crowd of them is a bastion
public class BastionClusterTest {

    private static List<BlockPos> blocks(int n, int baseX) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BlockPos(baseX + i % 10, 60 + i / 10, 0));
        }
        return out;
    }

    @Test
    public void nineteenBlocksIsNotABastionAndTwentyIs() {
        assertFalse(NetherPhase.bastionCluster(blocks(19, 0), new BlockPos(0, 60, 0)));
        assertTrue(NetherPhase.bastionCluster(blocks(20, 0), new BlockPos(0, 60, 0)));
    }

    @Test
    public void aForestWantsTwelveAndASliverDoesNot() {
        assertFalse(NetherPhase.bastionCluster(blocks(11, 0), new BlockPos(0, 60, 0), NetherPhase.WARPED_MIN_BLOCKS));
        assertTrue(NetherPhase.bastionCluster(blocks(12, 0), new BlockPos(0, 60, 0), NetherPhase.WARPED_MIN_BLOCKS));
        assertEquals(12, NetherPhase.clusterCount(blocks(12, 0), new BlockPos(0, 60, 0)));
        assertEquals(0, NetherPhase.clusterCount(blocks(12, 500), new BlockPos(0, 60, 0)));
    }

    // the cluster is counted over every known block, a known bastion's near edge included
    @Test
    public void blocksOnTheNearSideOfAKnownBastionStillCount() {
        List<BlockPos> all = new ArrayList<>();
        all.add(new BlockPos(63, 60, 0));
        for (int i = 0; i < 19; i++) {
            all.add(new BlockPos(65 + i % 5, 60 + i / 5, 0));
        }
        assertTrue(NetherPhase.bastionCluster(all, new BlockPos(65, 60, 0)));
    }

    @Test
    public void blocksFarFromTheCandidateDoNotCount() {
        List<BlockPos> known = blocks(19, 0);
        known.addAll(blocks(30, 500));
        assertFalse(NetherPhase.bastionCluster(known, new BlockPos(0, 60, 0)));
        assertTrue(NetherPhase.bastionCluster(known, new BlockPos(500, 60, 0)));
    }
}
