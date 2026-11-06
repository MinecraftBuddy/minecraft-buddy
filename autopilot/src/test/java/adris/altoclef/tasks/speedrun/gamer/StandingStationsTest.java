package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationHook.Kind;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// which registry entries GamerTask boxes in (StationBox): the ones standing in this dimension, none on their way out
public class StandingStationsTest {
    private static final String OVERWORLD = Workbenches.OVERWORLD;
    private static final String NETHER = "NETHER";

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    @Test
    public void everyKindStandingInThisDimensionCounts() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(2, 64, 2));
        state.placedSmokers.add(pos(3, 64, 3));
        Workbenches.sync(state, 100);
        List<BlockPos> stations = GamerTask.standingStations(state, OVERWORLD);
        assertEquals(3, stations.size());
        assertTrue(stations.contains(new BlockPos(1, 64, 1)));
        assertTrue(stations.contains(new BlockPos(2, 64, 2)));
        assertTrue(stations.contains(new BlockPos(3, 64, 3)));
    }

    @Test
    public void anotherDimensionsStationsDoNotCount() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(2, 64, 2));
        state.placedDimension.put("FURNACE:2,64,2", NETHER);
        Workbenches.sync(state, 100);
        assertEquals(List.of(new BlockPos(1, 64, 1)), GamerTask.standingStations(state, OVERWORLD));
        assertEquals(List.of(new BlockPos(2, 64, 2)), GamerTask.standingStations(state, NETHER));
    }

    // the pickup may have to build its way to a station, so one coming down is not boxed
    @Test
    public void aStationBeingPickedUpIsNotBoxed() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedTables.add(pos(9, 64, 9));
        Workbenches.sync(state, 100);
        Bench coming = Workbenches.find(state, Kind.TABLE, pos(9, 64, 9));
        coming.state = Bench.State.PICKING_UP;
        assertEquals(List.of(new BlockPos(1, 64, 1)), GamerTask.standingStations(state, OVERWORLD));
        coming.state = Bench.State.IN_BAG;
        assertEquals(List.of(new BlockPos(1, 64, 1)), GamerTask.standingStations(state, OVERWORLD));
    }

    @Test
    public void noStateOrNoStationsIsNoBox() {
        assertTrue(GamerTask.standingStations(null, OVERWORLD).isEmpty());
        assertTrue(GamerTask.standingStations(new RunState(), OVERWORLD).isEmpty());
    }
}
