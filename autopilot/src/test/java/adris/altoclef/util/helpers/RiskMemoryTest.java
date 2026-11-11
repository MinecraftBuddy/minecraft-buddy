package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// S137, a place goal by a safer route: the mob field's memory. a mob a route was given up for stays in the field at its last
// place once it is out of the live one, at its want for risk_memory_full_s, then fading to nothing at risk_memory_s
public class RiskMemoryTest {

    private static final MobThreat ZOMBIE = MobThreat.of("zombie", 1);
    private static final Object WORLD = new Object();

    private String tuningWas;
    private Path tuning;

    @Before
    public void setUp() throws Exception {
        ItemFixture.boot();
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        keys("risk_flee=1\n");
        RiskField.clear(0);
    }

    @After
    public void tearDown() throws Exception {
        keys("");
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        RiskField.clear(0);
    }

    private void keys(String text) throws Exception {
        Files.writeString(tuning, text);
        Tuning.now("risk_flee", 0);
    }

    private static RiskField.Source zombie(int id, double x, double want) {
        return new RiskField.Source(id, ZOMBIE, x, 64, 0.5, want, 1, true);
    }

    @Test
    public void theFadeGoesInSteps() {
        // full for a minute, gone at three: 1 till a quarter of the rest is over, then 0.75, 0.5, 0.25
        assertEquals(1, RiskField.fade(0, 1200, 3600), 0);
        assertEquals(1, RiskField.fade(1200, 1200, 3600), 0);
        assertEquals(1, RiskField.fade(1700, 1200, 3600), 0);
        assertEquals(0.75, RiskField.fade(1801, 1200, 3600), 0);
        assertEquals(0.5, RiskField.fade(2401, 1200, 3600), 0);
        assertEquals(0.25, RiskField.fade(3001, 1200, 3600), 0);
        assertEquals(0, RiskField.fade(3600, 1200, 3600), 0);
    }

    @Test
    public void aMobARouteWasGivenUpForStaysAtItsLastPlaceAndFades() {
        RiskField.offer(100, WORLD, false, List.of(zombie(1, 10, 0.5)));
        assertFalse(RiskField.current().remembers());
        assertEquals(1, RiskField.remember(100));
        // (again is no news)
        assertEquals(0, RiskField.remember(105));
        // it lost interest: out of the live field, kept in the whole one where it was, at the want it had
        assertTrue(RiskField.offer(120, WORLD, false, List.of()));
        List<RiskField.Source> now = RiskField.current().sources();
        assertEquals(1, now.size());
        assertTrue(now.get(0).kept());
        assertEquals(10, now.get(0).x(), 0);
        assertEquals(0.5, now.get(0).want(), 0);
        assertTrue(RiskField.current().remembers());
        // no new version while nothing changes
        assertFalse(RiskField.offer(125, WORLD, false, List.of()));
        // two minutes after the last time it counted: half
        assertTrue(RiskField.offer(105 + 2401, WORLD, false, List.of()));
        assertEquals(0.25, RiskField.current().sources().get(0).want(), 0);
        // three minutes: gone
        assertTrue(RiskField.offer(105 + 3600, WORLD, false, List.of()));
        assertTrue(RiskField.current().sources().isEmpty());
        assertTrue(RiskField.remembered().isEmpty());
    }

    @Test
    public void aRememberedMobThatIsLiveIsTheLiveOneAndItsPlaceIsKept() {
        RiskField.offer(100, WORLD, false, List.of(zombie(1, 10, 1)));
        RiskField.remember(100);
        RiskField.offer(110, WORLD, false, List.of(zombie(1, 20, 1)));
        assertEquals(1, RiskField.current().sources().size());
        assertFalse(RiskField.current().sources().get(0).kept());
        RiskField.offer(120, WORLD, false, List.of());
        assertEquals(20, RiskField.current().sources().get(0).x(), 0);
        assertTrue(RiskField.current().sources().get(0).kept());
    }

    @Test
    public void theGameSideMovesAndForgets() {
        RiskField.offer(100, WORLD, false, List.of(zombie(1, 10, 1), zombie(2, 14, 1)));
        // only the one that hit
        assertTrue(RiskField.remember(1, 100));
        assertFalse(RiskField.remember(1, 101));
        assertFalse(RiskField.remember(7, 101));
        RiskField.offer(120, WORLD, false, List.of());
        assertEquals(1, RiskField.current().sources().size());
        assertEquals(1, RiskField.current().sources().get(0).id());
        // seen out of the live field, 30 blocks on
        RiskField.moved(1, 40, 64, 0.5);
        assertTrue(RiskField.offer(125, WORLD, false, List.of()));
        assertEquals(40, RiskField.current().sources().get(0).x(), 0);
        // dead
        RiskField.forget(1);
        assertTrue(RiskField.offer(130, WORLD, false, List.of()));
        assertTrue(RiskField.current().sources().isEmpty());
    }

    @Test
    public void anotherWorldForgets() {
        RiskField.offer(100, WORLD, false, List.of(zombie(1, 10, 1)));
        RiskField.remember(100);
        RiskField.offer(120, new Object(), false, List.of());
        assertTrue(RiskField.current().sources().isEmpty());
    }

    @Test
    public void noMemoryWithoutRiskFleeOrWithTheKeyAtNothing() throws Exception {
        keys("");
        RiskField.offer(100, WORLD, false, List.of(zombie(1, 10, 1)));
        assertEquals(0, RiskField.remember(100));
        RiskField.offer(120, WORLD, false, List.of());
        assertTrue(RiskField.current().sources().isEmpty());
        keys("risk_flee=1\nrisk_memory_s=0\n");
        RiskField.offer(130, WORLD, false, List.of(zombie(1, 10, 1)));
        assertEquals(0, RiskField.remember(130));
    }

    @Test
    public void theOldestGoFirstPastTheMost() {
        for (int i = 0; i < RiskField.MEMORY_MAX + 3; i++) {
            RiskField.offer(100 + i, WORLD, false, List.of(zombie(i, 10 + 2 * i, 1)));
            RiskField.remember(100 + i);
        }
        assertEquals(RiskField.MEMORY_MAX, RiskField.remembered().size());
        assertTrue(RiskField.remembered().stream().noneMatch(s -> s.id() < 3));
    }
}
