/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.Settings;
import baritone.api.utils.BlockRange;
import baritone.api.utils.Dimension;
import baritone.api.utils.ForceFieldStrategy;
import baritone.api.utils.OverworldToNetherBehaviour;
import baritone.api.utils.SettingsUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the alto* settings through the same text path settings.txt goes through (settingToString out, parseAndApply back in).
// the file itself needs a running game for its path, but the lines are the whole format
public class AltoSettingsTest {

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

    // what save() writes, then what readAndApply does with each line
    private static void saveAndLoad(Settings from, Settings into) {
        for (Settings.Setting<?> setting : SettingsUtil.modifiedSettings(from)) {
            String line = SettingsUtil.settingToString(setting);
            int space = line.indexOf(' ');
            SettingsUtil.parseAndApply(into, line.substring(0, space).toLowerCase(), line.substring(space + 1));
        }
    }

    private static List<Settings.Setting<?>> altoSettings(Settings s) {
        List<Settings.Setting<?>> out = new ArrayList<>();
        for (Settings.Setting<?> setting : s.allSettings) {
            if (setting.getName().startsWith("alto")) {
                out.add(setting);
            }
        }
        return out;
    }

    @Test
    public void theEngageZoneSettingsAreGoneAndAnOldFileSkipsTheirLinesQuietly() throws Exception {
        Settings s = fresh();
        for (String gone : new String[]{"altoHostileEngageRange", "altoHostileEngageHeight", "altoPassByGraceTicks",
                "altoSwarmThreshold"}) {
            assertNull(gone, s.byLowerName.get(gone.toLowerCase()));
            // the settings file reader checks this before it parses a line, so an old file does not print "Unable to parse
            // line" and a stack trace for each of them
            assertTrue(gone, SettingsUtil.isRetired(gone));
            assertTrue(gone, SettingsUtil.isRetired(gone.toLowerCase()));
        }
        // a name that never existed is still an error, and a setting that exists is never skipped
        assertFalse(SettingsUtil.isRetired("altoNotAThing"));
        for (Settings.Setting<?> setting : altoSettings(s)) {
            assertFalse(setting.getName(), SettingsUtil.isRetired(setting.getName()));
        }
        // the commit switch stays, and it is on by default
        assertEquals(Boolean.TRUE, s.byLowerName.get("altocommitcombat").value);
    }

    @Test
    public void anOldSettingsFileWithTheEngageLinesLoadsWithoutAFuss() throws Exception {
        Settings s = fresh();
        // a line that cannot be parsed goes through the chat helper (which needs a running game), so this throwing is how a
        // missing skip shows up here
        for (String line : new String[]{"altoHostileEngageRange 12", "altoHostileEngageHeight 5.5", "altoPassByGraceTicks 40",
                "ALTOHOSTILEENGAGERANGE 8", "altoSwarmThreshold 5"}) {
            SettingsUtil.applyLine(s, line);
        }
        // and the lines around them still count
        SettingsUtil.applyLine(s, "altoReservedBuildingBlockCount 32");
        assertEquals(32, s.byLowerName.get("altoreservedbuildingblockcount").value);
    }

    @Test
    public void everyDefaultRoundTripsThroughItsText() throws Exception {
        Settings a = fresh();
        Settings b = fresh();
        List<Settings.Setting<?>> alto = altoSettings(a);
        // (54 since the engage zone settings went: altoHostileEngageRange, altoHostileEngageHeight, altoPassByGraceTicks;
        // 55 with altoIronArbiter, 54 again since crowds stopped being a reason to run: altoSwarmThreshold,
        // 55 with altoFoodKeepUnits, 56 with altoSleepAtNight, 57 with altoPearlNook)
        assertEquals(57, alto.size());
        for (Settings.Setting<?> setting : alto) {
            String text = SettingsUtil.settingDefaultToString(setting);
            SettingsUtil.parseAndApply(b, setting.getName().toLowerCase(), text);
            assertEquals(setting.getName(), setting.defaultValue, b.byLowerName.get(setting.getName().toLowerCase()).value);
        }
    }

    @Test
    public void nothingIsModifiedOnAFreshInstance() throws Exception {
        assertTrue(SettingsUtil.modifiedSettings(fresh()).isEmpty());
    }

    @Test
    public void changedValuesSurviveSaveAndLoad() throws Exception {
        Settings a = fresh();
        a.altoRunsWhenIdle.value = true;
        // on is the default now, so off is the value that has to survive the file
        a.altoButler.value = false;
        a.altoHudScale.value = 0.75f;
        a.altoMinimumFoodAllowed.value = 12;
        a.altoForceFieldStrategy.value = ForceFieldStrategy.DELAY;
        a.altoOverworldToNetherBehaviour.value = OverworldToNetherBehaviour.GO_TO_HOME_BASE;
        a.altoIdleCommand.value = "follow Jacob and then some";
        a.altoHomeBasePosition.value = new BlockPos(-100, 70, 2500);
        a.altoThrowawayItems.value = List.of(Items.DIRT, Items.COBBLESTONE);
        a.altoAreasToProtect.value = List.of(
                new BlockRange(new BlockPos(10, 255, 10), new BlockPos(-10, 0, -10), Dimension.OVERWORLD),
                new BlockRange(new BlockPos(1000, 50, 2000), new BlockPos(1200, 255, 2100), Dimension.NETHER));
        a.replantCrops.value = false;

        Settings b = fresh();
        saveAndLoad(a, b);

        assertTrue(b.altoRunsWhenIdle.value);
        assertFalse(b.altoButler.value);
        assertEquals(0.75f, b.altoHudScale.value, 0f);
        assertEquals(12, (int) b.altoMinimumFoodAllowed.value);
        assertEquals(ForceFieldStrategy.DELAY, b.altoForceFieldStrategy.value);
        assertEquals(OverworldToNetherBehaviour.GO_TO_HOME_BASE, b.altoOverworldToNetherBehaviour.value);
        assertEquals("follow Jacob and then some", b.altoIdleCommand.value);
        assertEquals(new BlockPos(-100, 70, 2500), b.altoHomeBasePosition.value);
        assertEquals(List.of(Items.DIRT, Items.COBBLESTONE), b.altoThrowawayItems.value);
        assertEquals(a.altoAreasToProtect.value, b.altoAreasToProtect.value);
        assertFalse(b.replantCrops.value);
        // and the ones nobody touched are still default, so they do not start showing up in the file
        assertEquals(11, SettingsUtil.modifiedSettings(b).size());
    }

    @Test
    public void inPlaceListMutationIsNotSaved() throws Exception {
        // the reason altoclef never mutates what it gets from the settings: save() looks at the reference only
        Settings a = fresh();
        try {
            a.altoThrowawayItems.value.add(Items.STICK);
            fail("the default list should not be mutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void emptyListsAndStringsComeBack() throws Exception {
        Settings a = fresh();
        a.altoThrowawayItems.value = new ArrayList<>();
        a.altoIdleCommand.value = "x";
        Settings b = fresh();
        b.altoIdleCommand.value = "something";
        // the empty text is what an emptied list saves as
        assertEquals("", SettingsUtil.settingValueToString(a.altoThrowawayItems));
        SettingsUtil.parseAndApply(b, "altothrowawayitems", "");
        assertTrue(b.altoThrowawayItems.value.isEmpty());
        SettingsUtil.parseAndApply(b, "altoidlecommand", "");
        assertEquals("", b.altoIdleCommand.value);
        SettingsUtil.parseAndApply(b, "altoareastoprotect", "");
        assertTrue(b.altoAreasToProtect.value.isEmpty());
    }

    @Test
    public void enumsParseWithoutCaringAboutCase() throws Exception {
        Settings s = fresh();
        SettingsUtil.parseAndApply(s, "altoforcefieldstrategy", "fastest");
        assertEquals(ForceFieldStrategy.FASTEST, s.altoForceFieldStrategy.value);
        SettingsUtil.parseAndApply(s, "altoforcefieldstrategy", "Off");
        assertEquals(ForceFieldStrategy.OFF, s.altoForceFieldStrategy.value);
        try {
            SettingsUtil.parseAndApply(s, "altoforcefieldstrategy", "extreme");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SMART"));
        }
    }

    @Test
    public void blockPosIsABlockPosAndNotAVec3i() throws Exception {
        Settings s = fresh();
        SettingsUtil.parseAndApply(s, "altohomebaseposition", "1,2,3");
        assertEquals(new BlockPos(1, 2, 3), s.altoHomeBasePosition.value);
        assertEquals("1,2,3", SettingsUtil.settingValueToString(s.altoHomeBasePosition));
        // the existing Vec3i setting is left alone
        SettingsUtil.parseAndApply(s, "buildrepeat", "4,5,6");
        assertEquals("4,5,6", SettingsUtil.settingValueToString(s.buildRepeat));
    }

    @Test
    public void blockRangeTextFormat() {
        BlockRange r = BlockRange.parse("10/255/10->-10/0/-10");
        assertEquals(new BlockPos(-10, 0, -10), r.start);
        assertEquals(new BlockPos(10, 255, 10), r.end);
        assertEquals(Dimension.OVERWORLD, r.dimension);
        assertEquals("-10/0/-10->10/255/10", r.format());
        assertEquals("1/2/3->4/5/6@nether", BlockRange.parse("1/2/3->4/5/6@NETHER").format());
        assertEquals(Dimension.END, BlockRange.parse("1/2/3->4/5/6@the_end").dimension);
        assertTrue(r.contains(new BlockPos(0, 64, 0), Dimension.OVERWORLD));
        assertFalse(r.contains(new BlockPos(0, 64, 0), Dimension.NETHER));
        assertFalse(r.contains(new BlockPos(11, 64, 0), Dimension.OVERWORLD));
        for (String bad : new String[]{"", "1/2/3", "1,2,3->4,5,6", "1/2/3->4/5/6@moon", "a/b/c->d/e/f"}) {
            try {
                BlockRange.parse(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void oldJsonMovesOverWhatWasChanged() throws Exception {
        Path json = tmp.newFile("altoclef_settings.json").toPath();
        Files.writeString(json, """
                {
                  "showTaskChains": false,
                  "hudScale": 0.75,
                  "commandPrefix": "!",
                  "chatLogPrefix": "[x] ",
                  "useCraftingBookToCraft": false,
                  "showTimer": false,
                  "entityReachRange": 6.0,
                  "replantCrops": false,
                  "forceFieldStrategy": "DELAY",
                  "overworldToNetherBehaviour": "BUILD_PORTAL_VANILLA",
                  "idleCommand": "idle",
                  "homeBasePosition": "10, 70, -5",
                  "throwawayItems": ["minecraft:dirt", "minecraft:diamond"],
                  "supportedFuels": ["minecraft:coal", "minecraft:charcoal"],
                  "areasToProtect": [
                    {"start": "-10, 0, -10", "end": "10, 255, 10"},
                    {"start": "1, 2, 3", "end": "4, 5, 6", "dimension": "NETHER"}
                  ],
                  "somethingFromTheFuture": 7,
                  "minimumFoodAllowed": "not a number"
                }
                """, StandardCharsets.UTF_8);
        Settings s = fresh();
        int moved = AltoSettingsMigration.migrate(json, s);

        assertFalse(s.altoShowTaskChains.value);
        assertEquals(0.75f, s.altoHudScale.value, 0f);
        assertEquals(6f, s.altoEntityReachRange.value, 0f);
        assertFalse(s.replantCrops.value);
        assertEquals(ForceFieldStrategy.DELAY, s.altoForceFieldStrategy.value);
        assertEquals("idle", s.altoIdleCommand.value);
        assertEquals(new BlockPos(10, 70, -5), s.altoHomeBasePosition.value);
        assertEquals(List.of(Items.DIRT, Items.DIAMOND), s.altoThrowawayItems.value);
        assertEquals(List.of(
                new BlockRange(new BlockPos(-10, 0, -10), new BlockPos(10, 255, 10), Dimension.OVERWORLD),
                new BlockRange(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), Dimension.NETHER)), s.altoAreasToProtect.value);
        // same as the default, so not "moved", and the junk value and the unknown keys are skipped without a fuss
        assertEquals(OverworldToNetherBehaviour.BUILD_PORTAL_VANILLA, s.altoOverworldToNetherBehaviour.value);
        assertSame(s.altoSupportedFuels.defaultValue, s.altoSupportedFuels.value);
        assertEquals(0, (int) s.altoMinimumFoodAllowed.value);
        assertEquals(9, moved);
        // what did not carry over is exactly what was dropped, none of them has a setting to land on
        assertNull(s.byLowerName.get("altocommandprefix"));
        assertNull(s.byLowerName.get("altochatlogprefix"));
        assertNull(s.byLowerName.get("altousecraftingbooktocraft"));
    }
}
