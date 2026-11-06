package adris.altoclef.ui.gui.facade;

import java.util.List;

// the player's surroundings and the actions on them: go to, follow, waypoints. lists are snapshots the tabs
// refresh on tick, never live views
public interface WorldAccess {

    record Position(double x, double y, double z) {
    }

    // distance is -1 when the player is on the tab list but not loaded nearby
    record PlayerEntry(String name, double distance, boolean self) {
    }

    // typeId "minecraft:zombie", typeName "Zombie", displayName the entity's own name (a named pet, a player),
    // iconItemId the spawn egg (or another stand-in item) for the icon, null when there is none
    record EntityEntry(int id, String typeId, String typeName, String displayName, double distance, String iconItemId) {
    }

    record WaypointEntry(String name, String tag, int x, int y, int z, long createdMillis) {
    }

    // null when there is no player
    Position playerPos();

    // where the player looks, minecraft's yaw in degrees: 0 south, 90 west, 180 north, 270 east. 0 without a player
    default float playerYaw() {
        return 0f;
    }

    // "overworld", "the_nether", "the_end" or whatever the dimension id's path is
    String dimension();

    List<PlayerEntry> players();

    List<EntityEntry> entities();

    Result followPlayer(String name);

    Result followEntity(int id);

    Result followType(String typeId);

    Result followAllPlayers();

    Result followAllEntities();

    Result stopFollowing();

    // "Steve", "every zombie", "all players", or null when nothing is followed
    String following();

    List<WaypointEntry> waypoints();

    List<String> waypointTags();

    Result addWaypointHere(String name, String tag);

    Result deleteWaypoint(WaypointEntry waypoint);

    // any of the three may be null: x and z only is a GoalXZ, y only is a GoalYLevel, all three a GoalBlock
    Result goTo(Integer x, Integer y, Integer z);

    Result setGoal(Integer x, Integer y, Integer z);
}
