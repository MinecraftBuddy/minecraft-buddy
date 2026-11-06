package adris.altoclef.tasks.speedrun.gamer.phases;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import java.lang.reflect.Constructor;
import net.minecraft.server.Bootstrap;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

// what GATHER and IRON hand back on exit: our hold, never a stale copy of the value from when they started
public class AsyncReleaseTest {

    private Settings s;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Before
    public void fresh() throws Exception {
        Constructor<Settings> ctor = Settings.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        s = ctor.newInstance();
        SettingsOverrides.restoreAll();
    }

    @After
    public void clean() {
        SettingsOverrides.restoreAll();
    }

    @Test
    public void exitGivesTheUsersValueBackWhenTheHoldIsStillOurs() {
        s.altoAsyncSmelting.value = false;
        SettingsOverrides.put(s.altoAsyncSmelting, true);
        IronPhase.releaseAsync(s.altoAsyncSmelting, false);
        assertFalse(s.altoAsyncSmelting.value);
        assertFalse(SettingsOverrides.isHeld(s.altoAsyncSmelting));
    }

    @Test
    public void aSetDuringThePhaseSurvivesTheExit() {
        s.altoAsyncSmelting.value = false;
        SettingsOverrides.put(s.altoAsyncSmelting, true);
        // #set altoAsyncSmelting false: the value changes and the hold is dropped
        s.altoAsyncSmelting.value = false;
        SettingsOverrides.userChanged(s.altoAsyncSmelting);
        IronPhase.releaseAsync(s.altoAsyncSmelting, false);
        assertFalse(s.altoAsyncSmelting.value);
        assertFalse(SettingsOverrides.isHeld(s.altoAsyncSmelting));
    }

    @Test
    public void aSetToTrueDuringThePhaseIsNotTurnedBackOff() {
        s.altoAsyncSmelting.value = false;
        SettingsOverrides.put(s.altoAsyncSmelting, true);
        SettingsOverrides.userChanged(s.altoAsyncSmelting);
        IronPhase.releaseAsync(s.altoAsyncSmelting, false);
        assertTrue(s.altoAsyncSmelting.value);
        assertEquals(0, SettingsOverrides.heldCount());
    }

    @Test
    public void nothingToReleaseWhenWeNeverTouchedIt() {
        s.altoAsyncSmelting.value = true;
        SettingsOverrides.put(s.altoAsyncSmelting, true);
        IronPhase.releaseAsync(s.altoAsyncSmelting, null);
        assertTrue(s.altoAsyncSmelting.value);
    }
}
