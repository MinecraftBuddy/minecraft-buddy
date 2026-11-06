package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.After;
import org.junit.Test;

// the respawn tracker's rules and what hangs off them: who may break our spawn bed, when a night bed is kept, the bed by the
// stronghold
public class RespawnTest {
    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    @After
    public void unbind() {
        Respawn.unbind();
    }

    @Test
    public void aBedIsOnlyWorthAQuietNightIfItCoversWhereWeAre() {
        RunState s = new RunState();
        s.respawnDim = "OVERWORLD";
        s.respawnPos = pos(0, 64, 0);
        s.strongholdStart = pos(100, 64, 0);
        assertTrue(Respawn.worthKeeping(GamerPhase.ROOM, s));
        assertTrue(Respawn.worthKeeping(GamerPhase.OPEN, s));
        // the old bed back at the nether portal
        s.strongholdStart = pos(1000, 64, 0);
        assertFalse(Respawn.worthKeeping(GamerPhase.ROOM, s));
        assertFalse(Respawn.worthKeeping(GamerPhase.OPEN, s));
        // END_PREP goes by the portal, the rest take any bed
        s.endPortalCenter = pos(1000, 30, 0);
        assertFalse(Respawn.worthKeeping(GamerPhase.END_PREP, s));
        assertTrue(Respawn.worthKeeping(GamerPhase.NETHER, s));
        s.endPortalCenter = pos(100, 30, 0);
        assertTrue(Respawn.worthKeeping(GamerPhase.END_PREP, s));
    }

    // ---- the lines

    @Test
    public void theTwoLinesAreRead() {
        assertEquals(Respawn.Line.SET, Respawn.read("Respawn point set"));
        assertEquals(Respawn.Line.LOST, Respawn.read("You have no home bed or charged respawn anchor, or it was obstructed"));
        assertEquals(Respawn.Line.OTHER, Respawn.read("You may not rest now; there are monsters nearby"));
        assertEquals(Respawn.Line.OTHER, Respawn.read(null));
    }

    @Test
    public void setAndLoseMoveTheState() {
        RunState s = new RunState();
        assertNull(Respawn.bed(s));
        Respawn.set(s, pos(908, 66, 1605), "OVERWORLD");
        assertEquals(pos(908, 66, 1605), Respawn.bed(s));
        // a new line moves it, it does not stack
        Respawn.set(s, pos(10, 70, 10), "OVERWORLD");
        assertEquals(pos(10, 70, 10), s.respawnPos);
        Respawn.lose(s);
        assertNull(s.respawnPos);
    }

    @Test
    public void aRespawnOutsideTheOverworldIsNotAnOverworldBed() {
        RunState s = new RunState();
        Respawn.set(s, pos(0, 70, 0), "NETHER");
        assertNull(Respawn.bed(s));
    }

    @Test
    public void theClickedBedIsTheClosestInReach() {
        List<RunState.Pos> beds = List.of(pos(5, 64, 0), pos(2, 64, 0), pos(40, 64, 0));
        assertEquals(pos(2, 64, 0), Respawn.nearest(beds, 0.5, 65, 0.5));
        // nothing in reach: the line was about an anchor or something, no bed to write down
        assertNull(Respawn.nearest(List.of(pos(40, 64, 0)), 0.5, 65, 0.5));
        assertNull(Respawn.nearest(List.of(), 0.5, 65, 0.5));
    }

    @Test
    public void aSpotWeCannotSeeIsNeverReadAsGone() {
        // unloaded chunk = air to the client
        assertFalse(Respawn.stale("OVERWORLD", "OVERWORLD", false, false));
        assertFalse(Respawn.stale("OVERWORLD", "OVERWORLD", true, true));
        assertTrue(Respawn.stale("OVERWORLD", "OVERWORLD", true, false));
        // standing in the nether says nothing about a bed in the overworld
        assertFalse(Respawn.stale("OVERWORLD", "NETHER", true, false));
    }

    // ---- who may break the spawn bed

    @Test
    public void bothHalvesOfTheSpawnBedAreOffLimits() {
        RunState s = new RunState();
        Respawn.set(s, pos(908, 66, 1605), "OVERWORLD");
        Respawn.bind(s);
        assertTrue(Respawn.isSpawnBed(908, 66, 1605));
        assertTrue(Respawn.isSpawnBed(908, 66, 1606));
        assertFalse(Respawn.isSpawnBed(910, 66, 1605));
        assertFalse(Respawn.isSpawnBed(908, 67, 1605));
        Respawn.unbind();
        assertFalse(Respawn.isSpawnBed(908, 66, 1605));
    }

    @Test
    public void theVillageBedHuntDoesNotCountTheSpawnBedAsAVillage() {
        List<RunState.Pos> own = BedRules.ownBeds(null, null, pos(5, 64, 5));
        assertEquals(1, own.size());
        List<RunState.Pos> bed = List.of(pos(5, 64, 5), pos(5, 64, 6));
        assertNull(BedRules.pick(bed, List.of(pos(8, 64, 5)), own, List.of(), List.of(), p -> true, 0.5, 64, 0.5, 48, 16));
    }

    // ---- the night bed on the way to the stronghold

    @Test
    public void aNightBedThatHoldsTheRespawnIsKeptOnTheStrongholdLeg() {
        for (GamerPhase phase : new GamerPhase[]{GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.OPEN}) {
            RunState s = new RunState();
            s.phase = phase;
            s.nightBed = pos(908, 66, 1605);
            Respawn.set(s, pos(908, 66, 1606), "OVERWORLD");
            assertTrue(Respawn.keepNightBed(phase, s));
            assertTrue(Respawn.adoptNightBed(s, 5, 5));
            assertEquals(pos(908, 66, 1605), s.spawnBed);
            // the next night trip is a fresh decision and not a pickup
            assertNull(s.nightBed);
            assertFalse(Respawn.adoptNightBed(s, 5, 5));
        }
    }

    @Test
    public void otherPhasesTakeTheirBedBackAsBefore() {
        for (GamerPhase phase : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.PORTAL, GamerPhase.NETHER,
                GamerPhase.EYES, GamerPhase.RETURN, GamerPhase.END_PREP}) {
            RunState s = new RunState();
            s.phase = phase;
            s.nightBed = pos(1, 64, 1);
            Respawn.set(s, pos(1, 64, 1), "OVERWORLD");
            assertFalse(Respawn.adoptNightBed(s, 5, 5));
            assertNotNull(s.nightBed);
        }
    }

    @Test
    public void aNightBedThatIsNotTheRespawnIsNotKept() {
        RunState s = new RunState();
        s.phase = GamerPhase.LOCATE;
        s.nightBed = pos(1, 64, 1);
        // no respawn known
        assertFalse(Respawn.adoptNightBed(s, 5, 5));
        // respawn somewhere else
        Respawn.set(s, pos(500, 64, 500), "OVERWORLD");
        assertFalse(Respawn.adoptNightBed(s, 5, 5));
        assertNotNull(s.nightBed);
    }

    // ---- the spawn by the portal

    @Test
    public void aRespawnNearThePortalIsEnoughAndAFarOneIsNot() {
        RunState s = new RunState();
        assertFalse(Respawn.coversPortal(s));
        Respawn.set(s, pos(908, 66, 1605), "OVERWORLD");
        // no portal known yet
        assertTrue(Respawn.coversPortal(s));
        s.endPortalCenter = pos(1000, 30, 1700);
        assertTrue(Respawn.coversPortal(s));
        s.endPortalCenter = pos(-900, 30, 1700);
        assertFalse(Respawn.coversPortal(s));
    }

    // ---- the fallback bed at the stronghold

    @Test
    public void aBedGoesDownAtTheStartWhenNothingHoldsTheRespawnNearIt() {
        RunState s = new RunState();
        s.strongholdStart = pos(1000, 0, 1600);
        assertTrue(Respawn.wantRoomBed(s, true, 1));
        // not there yet, or no bed to place
        assertFalse(Respawn.wantRoomBed(s, false, 1));
        assertFalse(Respawn.wantRoomBed(s, true, 0));
        // the world spawn a thousand blocks off is not near
        Respawn.set(s, pos(0, 70, 0), "OVERWORLD");
        assertTrue(Respawn.wantRoomBed(s, true, 1));
        // the night bed from the walk in is
        Respawn.set(s, pos(908, 66, 1605), "OVERWORLD");
        assertFalse(Respawn.wantRoomBed(s, true, 1));
        // a respawn in another dimension is no use here
        Respawn.set(s, pos(1000, 66, 1600), "NETHER");
        assertTrue(Respawn.wantRoomBed(s, true, 1));
    }

    @Test
    public void noStartNoBed() {
        assertFalse(Respawn.wantRoomBed(new RunState(), true, 3));
    }

    @Test
    public void aCarriedBedGoesDownByThePortalOnlyWhenNothingHoldsTheRespawnThere() {
        RunState s = new RunState();
        assertTrue(Respawn.wantPortalBed(s, 1, 100, 100));
        // no bed in the bag (none gets made for this)
        assertFalse(Respawn.wantPortalBed(s, 0, 100, 100));
        // a respawn right by the portal is already the answer, one a long way off is not
        Respawn.set(s, pos(130, 66, 90), "OVERWORLD");
        assertFalse(Respawn.wantPortalBed(s, 1, 100, 100));
        Respawn.set(s, pos(900, 66, 90), "OVERWORLD");
        assertTrue(Respawn.wantPortalBed(s, 1, 100, 100));
        // a respawn in another dimension holds nothing here
        Respawn.set(s, pos(130, 66, 90), "NETHER");
        assertTrue(Respawn.wantPortalBed(s, 1, 100, 100));
    }

    @Test
    public void aNightBedOnlyStaysUpWhenTheBagHasTheEndBedsWithoutIt() {
        RunState s = new RunState();
        s.phase = GamerPhase.LOCATE;
        s.nightBed = pos(908, 66, 1605);
        Respawn.set(s, pos(908, 66, 1606), "OVERWORLD");
        // one short: the bed comes back into the bag, the spawn is not claimed
        assertFalse(Respawn.adoptNightBed(s, 4, 5));
        assertNotNull(s.nightBed);
        assertNull(s.spawnBed);
        assertTrue(Respawn.adoptNightBed(s, 5, 5));
    }
}
