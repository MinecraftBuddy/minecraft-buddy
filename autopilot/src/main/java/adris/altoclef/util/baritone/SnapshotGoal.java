package adris.altoclef.util.baritone;

// goals that read entities copy what they need once per tick, so the pathfinder thread never touches the entity
// tracker (or its lock) per node. the owning task calls refresh from the main thread
public interface SnapshotGoal {

    void refresh();
}
