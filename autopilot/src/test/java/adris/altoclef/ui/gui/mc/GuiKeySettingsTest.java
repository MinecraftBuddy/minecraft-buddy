package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.model.KeyBind;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GuiKeySettingsTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void defaultsAreRightShiftAndOn() {
        Settings settings = BaritoneAPI.getSettings();
        assertEquals(344, (int) settings.guiKey.value);
        assertTrue(settings.guiKeyEnabled.value);
    }

    @Test
    public void bothAreRealUserSettings() {
        Settings settings = BaritoneAPI.getSettings();
        assertSame(settings.guiKey, settings.findByLowerName("guikey"));
        assertSame(settings.guiKeyEnabled, settings.findByLowerName("guikeyenabled"));
        assertFalse(settings.guiKey.isJavaOnly());
        assertFalse(settings.guiKeyEnabled.isJavaOnly());
        assertTrue(settings.allSettings.contains(settings.guiKey));
        assertTrue(settings.allSettings.contains(settings.guiKeyEnabled));
    }

    @Test
    public void theKeyIsSettableLikeAnyOtherInteger() {
        Settings settings = BaritoneAPI.getSettings();
        try {
            baritone.api.utils.SettingsUtil.parseAndApply(settings, "guikey", "-1");
            assertEquals(-1, (int) settings.guiKey.value);
            assertEquals("-1", baritone.api.utils.SettingsUtil.settingValueToString(settings.guiKey));
        } finally {
            settings.guiKey.reset();
        }
        assertEquals(344, (int) settings.guiKey.value);
    }

    @Test
    public void unboundOrDisabledNeverOpens() {
        // the same question the hotkey asks, through the same model
        KeyBind bind = new KeyBind(344);
        assertTrue(bind.opens(344, false));
        assertFalse(bind.opens(344, true));
        assertFalse(bind.opens(345, false));
        bind.enabled = false;
        assertFalse(bind.opens(344, false));
        bind.enabled = true;
        bind.key = -1;
        assertFalse(bind.opens(-1, false));
    }

    @Test
    public void onlyAPressOpensIt() {
        // no game in a unit test: anything that is not a press never gets as far as asking for one
        KeyEvent shift = new KeyEvent(InputConstants.KEY_RSHIFT, 0, 0);
        assertFalse(GuiHotkey.onKey(shift, InputConstants.RELEASE));
        assertFalse(GuiHotkey.onKey(shift, InputConstants.REPEAT));
        // a press of nothing at all
        assertFalse(GuiHotkey.onKey(new KeyEvent(0, 0, 0), InputConstants.PRESS));
    }
}
