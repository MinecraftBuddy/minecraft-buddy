package adris.altoclef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// the file handling around the old altoclef_settings.json, against temp folders. the value conversion itself is in
// AltoSettingsTest
public class AltoSettingsMigrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        // the default lists are full of Items
        ItemFixture.boot();
    }

    private static Settings fresh() throws Exception {
        Constructor<Settings> ctor = Settings.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private Path dirWith(byte[] content) throws Exception {
        Path dir = tmp.newFolder().toPath();
        Files.write(dir.resolve(AltoSettingsMigration.OLD_FILE), content);
        return dir;
    }

    private Path dirWith(String content) throws Exception {
        return dirWith(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String GOOD = "{\"showTaskChains\": false, \"hudScale\": 0.75}";

    @Test
    public void goodFileIsImportedSavedAndRenamed() throws Exception {
        Path dir = dirWith(GOOD);
        Settings s = fresh();
        AtomicInteger saves = new AtomicInteger();

        assertEquals(AltoSettingsMigration.Outcome.IMPORTED, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));

        assertFalse(s.altoShowTaskChains.value);
        assertEquals(0.75f, s.altoHudScale.value, 0f);
        assertEquals(1, saves.get());
        assertFalse(Files.exists(dir.resolve(AltoSettingsMigration.OLD_FILE)));
        assertTrue(Files.exists(dir.resolve(AltoSettingsMigration.MIGRATED_FILE)));
        // and the next start has nothing to do
        assertEquals(AltoSettingsMigration.Outcome.NO_FILE, AltoSettingsMigration.migrateIfNeeded(dir, fresh(), saves::incrementAndGet));
        assertEquals(1, saves.get());
    }

    @Test
    public void badFilesAreRenamedToFailedAndImportNothing() throws Exception {
        byte[][] bad = {
                new byte[0],
                "   ".getBytes(StandardCharsets.UTF_8),
                "[1, 2]".getBytes(StandardCharsets.UTF_8),
                "{\"hudScale\": 0.75".getBytes(StandardCharsets.UTF_8),
                // not utf-8: a lone continuation byte and a bad start byte, around something that looks like json
                new byte[]{'{', '"', 'h', 'u', 'd', 'S', 'c', 'a', 'l', 'e', '"', ':', '0', '.', '7', '5', (byte) 0x80, (byte) 0xff, '}'}
        };
        for (byte[] content : bad) {
            Path dir = dirWith(content);
            // an older .failed is replaced, not a reason to give up
            Files.writeString(dir.resolve(AltoSettingsMigration.FAILED_FILE), "old");
            Settings s = fresh();
            AtomicInteger saves = new AtomicInteger();

            assertEquals(AltoSettingsMigration.Outcome.BAD_FILE, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));

            assertTrue(SettingsUtil.modifiedSettings(s).isEmpty());
            assertEquals(0, saves.get());
            assertFalse(Files.exists(dir.resolve(AltoSettingsMigration.OLD_FILE)));
            assertFalse(Files.exists(dir.resolve(AltoSettingsMigration.MIGRATED_FILE)));
            assertEquals(content.length, Files.readAllBytes(dir.resolve(AltoSettingsMigration.FAILED_FILE)).length);
            // never retried
            assertEquals(AltoSettingsMigration.Outcome.NO_FILE, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));
        }
    }

    @Test
    public void anEmptyKeyOnlyCostsThatEntry() throws Exception {
        Path dir = dirWith("{\"\": 1, \"hudScale\": 0.75}");
        Settings s = fresh();
        assertEquals(1, AltoSettingsMigration.migrate(dir.resolve(AltoSettingsMigration.OLD_FILE), s));
        assertEquals(0.75f, s.altoHudScale.value, 0f);
    }

    @Test
    public void settingsAlreadyChangedKeepTheirValue() throws Exception {
        Path dir = dirWith(GOOD);
        Settings s = fresh();
        s.altoHudScale.value = 2f;

        assertEquals(1, AltoSettingsMigration.migrate(dir.resolve(AltoSettingsMigration.OLD_FILE), s));

        assertEquals(2f, s.altoHudScale.value, 0f);
        assertFalse(s.altoShowTaskChains.value);
    }

    @Test
    public void aRenameThatFailsFallsBackToDeletingSoItIsImportedOnce() throws Exception {
        Path dir = dirWith(GOOD);
        // a folder with something in it sitting on the new name, so the rename can't work
        Path squatter = dir.resolve(AltoSettingsMigration.MIGRATED_FILE);
        Files.createDirectory(squatter);
        Files.writeString(squatter.resolve("keep"), "me");
        Settings s = fresh();
        AtomicInteger saves = new AtomicInteger();

        assertEquals(AltoSettingsMigration.Outcome.IMPORTED, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));

        assertFalse(s.altoShowTaskChains.value);
        assertEquals(1, saves.get());
        assertFalse(Files.exists(dir.resolve(AltoSettingsMigration.OLD_FILE)));
        // a later #set is not put back by the next start
        s.altoShowTaskChains.value = true;
        assertEquals(AltoSettingsMigration.Outcome.NO_FILE, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));
        assertTrue(s.altoShowTaskChains.value);
    }

    @Test
    public void aFileThatCannotBeRetiredIsNeverImported() throws Exception {
        Path dir = dirWith(GOOD);
        Settings s = fresh();
        AtomicInteger saves = new AtomicInteger();

        assertEquals(AltoSettingsMigration.Outcome.STUCK, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet, (file, target) -> false));

        assertTrue(SettingsUtil.modifiedSettings(s).isEmpty());
        assertEquals(0, saves.get());
        assertTrue(Files.exists(dir.resolve(AltoSettingsMigration.OLD_FILE)));
        // not twice in the same session either, even if the second try could have moved it
        assertEquals(AltoSettingsMigration.Outcome.SKIPPED, AltoSettingsMigration.migrateIfNeeded(dir, s, saves::incrementAndGet));
        assertTrue(SettingsUtil.modifiedSettings(s).isEmpty());
        assertTrue(Files.exists(dir.resolve(AltoSettingsMigration.OLD_FILE)));
    }
}
