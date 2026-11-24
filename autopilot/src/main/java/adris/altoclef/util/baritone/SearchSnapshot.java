package adris.altoclef.util.baritone;

// goals that judge one path search by one look at the world: the search calls searchStarts on its own thread before its first
// node and searchEnds when it is done (AStarPathFinder.calculate0). refresh swaps a goal's copy every tick and a search runs
// over many ticks, so a snapshot taken per node would change halfway through the search
public interface SearchSnapshot {

    void searchStarts();

    void searchEnds();
}
