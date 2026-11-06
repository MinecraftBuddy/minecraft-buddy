package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.WorldAccess.EntityEntry;
import adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry;
import adris.altoclef.ui.gui.model.EntityGroups.EntityRow;
import adris.altoclef.ui.gui.model.EntityGroups.Group;
import adris.altoclef.ui.gui.model.EntityGroups.GroupRow;
import adris.altoclef.ui.gui.model.EntityGroups.Row;

import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EntityGroupsTest {

    static EntityEntry e(int id, String type, String name, String display, double d, String icon) {
        return new EntityEntry(id, "minecraft:" + type, name, display, d, icon);
    }

    static final List<EntityEntry> WORLD = List.of(
            e(1, "zombie", "Zombie", "", 14.9, "minecraft:zombie_spawn_egg"),
            e(2, "cow", "Cow", "", 5.1, "minecraft:cow_spawn_egg"),
            e(3, "zombie", "Zombie", "", 8.2, "minecraft:zombie_spawn_egg"),
            e(4, "wolf", "Wolf", "Biscuit", 2.0, null),
            e(5, "zombie", "Zombie", "", 31.0, null),
            e(6, "cow", "Cow", "", 6.3, "minecraft:cow_spawn_egg"));

    @Test
    public void groupsByTypeNearestFirst() {
        List<Group> g = EntityGroups.group(WORLD, "");
        assertEquals(List.of("minecraft:wolf", "minecraft:cow", "minecraft:zombie"), g.stream().map(Group::typeId).toList());
        Group z = g.get(2);
        assertEquals(3, z.count());
        assertEquals(8.2, z.nearest(), 0.0001);
        assertEquals(List.of(3, 1, 5), z.members().stream().map(EntityEntry::id).toList());
        assertEquals("Zombie", z.typeName());
    }

    @Test
    public void iconComesFromTheFirstMemberThatHasOne() {
        Group z = EntityGroups.group(WORLD, "").get(2);
        assertEquals("minecraft:zombie_spawn_egg", z.iconItemId());
        assertNull(EntityGroups.group(WORLD, "wolf").get(0).iconItemId());
    }

    @Test
    public void searchMatchesTypeNameOrDisplayNameIgnoringCase() {
        assertEquals(1, EntityGroups.group(WORLD, "ZOM").size());
        assertEquals(3, EntityGroups.group(WORLD, "zom").get(0).count());
        // a type match keeps the whole group, a name match keeps just that entity
        List<Group> biscuit = EntityGroups.group(WORLD, "biscu");
        assertEquals(1, biscuit.size());
        assertEquals("minecraft:wolf", biscuit.get(0).typeId());
        assertTrue(EntityGroups.group(WORLD, "nothing like this").isEmpty());
    }

    @Test
    public void searchDoesNotMatchTheNamespace() {
        assertTrue(EntityGroups.group(WORLD, "minecraft").isEmpty());
    }

    @Test
    public void searchKeepsOnlyMatchingMembersWhenTheTypeDoesNot() {
        List<EntityEntry> w = List.of(
                e(1, "wolf", "Wolf", "Biscuit", 3, null),
                e(2, "wolf", "Wolf", "Rex", 4, null));
        List<Group> g = EntityGroups.group(w, "rex");
        assertEquals(1, g.get(0).count());
        assertEquals(2, g.get(0).members().get(0).id());
    }

    @Test
    public void rowsInterleaveMembersOnlyForExpandedGroups() {
        List<Group> g = EntityGroups.group(WORLD, "");
        List<Row> collapsed = EntityGroups.rows(g, Set.of());
        assertEquals(3, collapsed.size());
        assertTrue(collapsed.stream().allMatch(r -> r instanceof GroupRow));
        List<Row> open = EntityGroups.rows(g, Set.of("minecraft:cow"));
        assertEquals(5, open.size());
        assertTrue(open.get(1) instanceof GroupRow);
        assertTrue(open.get(2) instanceof EntityRow);
        assertEquals("e:2", open.get(2).key());
        assertEquals("g:minecraft:cow", open.get(1).key());
    }

    @Test
    public void labelsUseTheOwnNameOrTypeAndId() {
        assertEquals("Biscuit", EntityGroups.label(WORLD.get(3)));
        assertEquals("Zombie #1", EntityGroups.label(WORLD.get(0)));
        assertFalse(EntityGroups.named(WORLD.get(0)));
        assertTrue(EntityGroups.named(WORLD.get(3)));
    }

    @Test
    public void missingTypeNameFallsBackToThePath() {
        EntityEntry odd = e(9, "glow_squid", "", "", 3, null);
        assertEquals("Glow squid", EntityGroups.group(List.of(odd), "").get(0).typeName());
    }

    @Test
    public void playersNearbyFirstUnloadedLastYouAtTheTop() {
        List<PlayerEntry> in = List.of(
                new PlayerEntry("zed", -1, false),
                new PlayerEntry("Alex", 48, false),
                new PlayerEntry("aver", 0, true),
                new PlayerEntry("Amy", -1, false),
                new PlayerEntry("Steve", 12.4, false));
        List<String> names = EntityGroups.players(in, "").stream().map(PlayerEntry::name).toList();
        assertEquals(List.of("aver", "Steve", "Alex", "Amy", "zed"), names);
    }

    @Test
    public void playerSearchIsCaseInsensitiveContains() {
        List<PlayerEntry> in = List.of(new PlayerEntry("Steve", 3, false), new PlayerEntry("Alex", 4, false));
        assertEquals(1, EntityGroups.players(in, "TEV").size());
        assertTrue(EntityGroups.players(in, "zzz").isEmpty());
    }

    @Test
    public void nearbyCountSkipsYouAndTheUnloaded() {
        List<PlayerEntry> in = List.of(new PlayerEntry("aver", 0, true), new PlayerEntry("Steve", 3, false), new PlayerEntry("Far", -1, false));
        assertEquals(1, EntityGroups.nearbyPlayers(in));
    }

    @Test
    public void metersRound() {
        assertEquals("12 m", EntityGroups.meters(12.4));
        assertEquals("13 m", EntityGroups.meters(12.6));
        assertEquals("0 m", EntityGroups.meters(0.2));
    }

    @Test
    public void groupsFoundOnlyByAMembersNameAreMarked() {
        assertTrue(EntityGroups.group(WORLD, "biscu").get(0).memberOnly());
        assertFalse(EntityGroups.group(WORLD, "wolf").get(0).memberOnly());
        assertFalse(EntityGroups.group(WORLD, "").get(0).memberOnly());
    }

    // what the real facade says: a lone mob is its bare name, a type is "every <type>", the rest is fixed text
    @Test
    public void followingMatchesWhatTheRealFacadeSays() {
        Group zombies = EntityGroups.group(WORLD, "zom").get(0);
        assertTrue(EntityGroups.followsOneOf("Zombie", zombies));
        assertFalse(EntityGroups.followsGroup("Zombie", zombies));
        assertTrue(EntityGroups.followsOneOf("Biscuit", EntityGroups.group(WORLD, "wolf").get(0)));
        assertFalse(EntityGroups.followsOneOf("Cow", zombies));
        assertFalse(EntityGroups.followsOneOf("all players", zombies));
        assertFalse(EntityGroups.followsOneOf("3 entities", zombies));
        assertFalse(EntityGroups.followsOneOf("every zombie", EntityGroups.group(WORLD, "cow").get(0)));
        Group spider = EntityGroups.group(List.of(e(7, "cave_spider", "Cave Spider", "", 4, null)), "").get(0);
        assertTrue(EntityGroups.followsGroup("every cave spider", spider));
        assertFalse(EntityGroups.followsGroup("all entities", spider));
    }

    @Test
    public void followingMatchesLoosely() {
        Group z = EntityGroups.group(WORLD, "zom").get(0);
        assertTrue(EntityGroups.followsGroup("every zombie", z));
        assertTrue(EntityGroups.followsGroup("Every Zombies", z));
        assertFalse(EntityGroups.followsGroup("all players", z));
        assertFalse(EntityGroups.followsGroup(null, z));
        assertTrue(EntityGroups.followsPlayer("steve", "Steve"));
        assertFalse(EntityGroups.followsPlayer("every steve", "Steve"));
        assertFalse(EntityGroups.followsPlayer(null, "Steve"));
        assertTrue(EntityGroups.followsEntity("zombie #1", WORLD.get(0)));
        assertTrue(EntityGroups.followsEntity("Biscuit", WORLD.get(3)));
        assertFalse(EntityGroups.followsEntity("every zombie", WORLD.get(0)));
    }
}
