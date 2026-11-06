package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class McSettingsKindTest {

    private enum Fruit { APPLE, PEAR }

    @BeforeClass
    public static void bootstrap() {
        // the default item lists in Settings need the registries
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void valueClassesMapToKinds() {
        assertEquals(Kind.BOOLEAN, McSettings.kindOf("allowBreak", Boolean.class));
        assertEquals(Kind.INTEGER, McSettings.kindOf("x", Integer.class));
        assertEquals(Kind.LONG, McSettings.kindOf("x", Long.class));
        assertEquals(Kind.FLOAT, McSettings.kindOf("x", Float.class));
        assertEquals(Kind.DOUBLE, McSettings.kindOf("x", Double.class));
        assertEquals(Kind.STRING, McSettings.kindOf("x", String.class));
        assertEquals(Kind.COLOR, McSettings.kindOf("colorCurrentPath", Color.class));
        assertEquals(Kind.ENUM, McSettings.kindOf("x", Fruit.class));
        assertEquals(Kind.OTHER, McSettings.kindOf("x", List.class));
    }

    @Test
    public void onlyTheGuiKeyIsAKey() {
        assertEquals(Kind.KEY, McSettings.kindOf("guiKey", Integer.class));
        assertEquals(Kind.INTEGER, McSettings.kindOf("guiKeyEnabledCount", Integer.class));
        assertEquals(Kind.BOOLEAN, McSettings.kindOf("guiKeyEnabled", Boolean.class));
    }

    @Test
    public void everySettingGetsAKindThatMatchesItsType() {
        // the real list, so a setting type nobody planned for shows up here as OTHER and not as an exception
        Settings settings = Baritone.settings();
        for (Settings.Setting<?> s : settings.allSettings) {
            if (s.isJavaOnly()) {
                continue;
            }
            Kind k = McSettings.kindOf(s.getName(), s.getValueClass());
            if (s.getValueClass() == Boolean.class) {
                assertEquals(s.getName(), Kind.BOOLEAN, k);
            }
            if (s.getValueClass().isEnum()) {
                assertEquals(s.getName(), Kind.ENUM, k);
            }
        }
    }

    @Test
    public void snapshotHoldsTheGuiKeyAndNoJavaOnlySettings() {
        SettingsAccess access = new McSettings();
        List<SettingView> all = access.all();
        assertFalse(all.isEmpty());
        // every setting that is not java only must be in the list: a type the text parser cannot handle would
        // otherwise vanish from the gui without a sound
        long expected = Baritone.settings().allSettings.stream().filter(s -> !s.isJavaOnly()).count();
        assertEquals(expected, all.size());

        SettingView key = access.get("guiKey");
        assertNotNull(key);
        assertEquals(Kind.KEY, key.kind());
        assertEquals("344", key.value());
        assertEquals("344", key.defaultValue());
        assertFalse(key.modified());
        assertNotNull(access.get("guikeyenabled"));

        for (SettingView v : all) {
            assertFalse(v.name(), Baritone.settings().findByLowerName(v.name().toLowerCase()).isJavaOnly());
        }
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).name().compareToIgnoreCase(all.get(i).name()) <= 0);
        }
    }

    @Test
    public void enumSettingsListTheirConstants() {
        SettingView v = new McSettings().get("altoForceFieldStrategy");
        assertNotNull(v);
        assertEquals(Kind.ENUM, v.kind());
        assertTrue(v.options().contains("SMART"));
    }

    @Test
    public void strangersAndBadValuesFailWithoutTouchingAnything() {
        SettingsAccess access = new McSettings();
        assertNull(access.get("nonsense"));

        Result missing = access.set("nonsense", "1");
        assertFalse(missing.ok());

        // parses before it saves, so a bad number never reaches the file
        Result bad = access.set("guiKey", "abc");
        assertFalse(bad.ok());
        assertEquals("set guiKey abc", bad.command());
        assertEquals(344, (int) Baritone.settings().guiKey.value);

        assertFalse(access.toggle("guiKey").ok());
        assertFalse(access.reset("nonsense").ok());
    }

    @Test
    public void noDescriptionsFileMeansEmptyDescriptions() {
        assertEquals("", new McSettings().description("nonsense"));
    }
}
