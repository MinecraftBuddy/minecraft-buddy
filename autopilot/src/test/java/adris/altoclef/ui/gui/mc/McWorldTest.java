package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import baritone.api.cache.IWaypoint;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class McWorldTest {

    @BeforeClass
    public static void bootstrap() {
        // entity types are looked up in the registry
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Recorder implements CommandsAccess {
        final List<String> lines = new ArrayList<>();
        boolean fail;

        @Override
        public List<CommandInfo> commands() {
            return List.of();
        }

        @Override
        public Result run(String line) {
            lines.add(line);
            return fail ? Result.fail("no valid entities in range!", line) : Result.ok("Ran " + line, line);
        }
    }

    @Test
    public void goalWordsMatchWhatTheCommandTakes() {
        assertEquals("120 64 -30", McWorld.coords(120, 64, -30));
        assertEquals("120 -30", McWorld.coords(120, null, -30));
        assertEquals("64", McWorld.coords(null, 64, null));
        assertNull(McWorld.coords(null, null, null));
        assertNull(McWorld.coords(120, 64, null));
        assertNull(McWorld.coords(null, 64, -30));
        assertNull(McWorld.coords(120, null, null));
    }

    @Test
    public void goToRunsGotoAndSetGoalRunsGoal() {
        Recorder r = new Recorder();
        McWorld w = new McWorld(r);
        Result a = w.goTo(120, 64, -30);
        assertTrue(a.ok());
        assertEquals("goto 120 64 -30", a.command());
        assertEquals("Going to 120 64 -30", a.message());
        Result b = w.goTo(5, null, 7);
        assertEquals("Going to x 5, z 7", b.message());
        Result c = w.setGoal(null, 12, null);
        assertEquals("goal 12", c.command());
        assertEquals("Goal set to y 12", c.message());
        assertEquals(List.of("goto 120 64 -30", "goto 5 7", "goal 12"), r.lines);
    }

    @Test
    public void halfAGoalIsRefusedBeforeAnythingRuns() {
        Recorder r = new Recorder();
        McWorld w = new McWorld(r);
        assertFalse(w.goTo(null, null, null).ok());
        assertFalse(w.goTo(1, 2, null).ok());
        assertFalse(w.setGoal(null, 2, 3).ok());
        assertTrue(r.lines.isEmpty());
    }

    @Test
    public void aRefusedGotoKeepsTheLine() {
        Recorder r = new Recorder();
        r.fail = true;
        Result res = new McWorld(r).goTo(1, 2, 3);
        assertFalse(res.ok());
        assertEquals("goto 1 2 3", res.command());
        assertEquals("No valid entities in range!", res.message());
    }

    @Test
    public void followLinesAreTheChatLines() {
        // no client in a unit test: the command is refused or the process is missing, so only the failure path and
        // the line it tried are worth pinning down here
        Recorder r = new Recorder();
        r.fail = true;
        McWorld w = new McWorld(r);
        assertEquals("follow player Steve", w.followPlayer("Steve").command());
        assertEquals("follow players", w.followAllPlayers().command());
        assertEquals("follow entities", w.followAllEntities().command());
        assertEquals("follow entity minecraft:zombie", w.followType("minecraft:zombie").command());
        assertEquals(4, r.lines.size());
    }

    @Test
    public void followRefusesNonsenseBeforeRunning() {
        Recorder r = new Recorder();
        McWorld w = new McWorld(r);
        assertFalse(w.followPlayer(null).ok());
        assertFalse(w.followPlayer("  ").ok());
        assertFalse(w.followPlayer("two words").ok());
        assertFalse(w.followType("not a type").ok());
        assertFalse(w.followType("minecraft:not_a_mob_at_all").ok());
        assertTrue(r.lines.isEmpty());
    }

    @Test
    public void waypointLinesCanBeTypedBack() {
        BetterBlockPos at = new BetterBlockPos(10, 64, -5);
        assertEquals("wp save user home 10 64 -5", McWorld.saveLine("home", IWaypoint.Tag.USER, at));
        assertEquals("wp save bed 10 64 -5", McWorld.saveLine("", IWaypoint.Tag.BED, at));
        assertNull(McWorld.saveLine("my base", IWaypoint.Tag.USER, at));
        WaypointEntry w = new WaypointEntry("home", "user", 10, 64, -5, 1731000000123L);
        assertEquals("wp delete user @ 1731000000123", McWorld.deleteLine(w));
    }

    @Test
    public void tagsAreTheOnesTheCommandKnows() {
        List<String> tags = new McWorld(new Recorder()).waypointTags();
        assertEquals(List.of("home", "death", "bed", "user"), tags);
        for (String t : tags) {
            assertTrue(IWaypoint.Tag.getByName(t) != null);
        }
    }

    @Test
    public void nothingThrowsWithoutAClient() {
        McWorld w = new McWorld(new Recorder());
        assertNull(w.playerPos());
        assertEquals("overworld", w.dimension());
        assertTrue(w.players().isEmpty());
        assertTrue(w.entities().isEmpty());
        assertTrue(w.waypoints().isEmpty());
        assertNull(w.following());
        assertFalse(w.followEntity(7).ok());
        assertFalse(w.addWaypointHere("home", "user").ok());
        assertFalse(w.addWaypointHere("home", "nonsense").ok());
        assertFalse(w.deleteWaypoint(null).ok());
    }
}
