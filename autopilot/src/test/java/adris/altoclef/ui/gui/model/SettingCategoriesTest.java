package adris.altoclef.ui.gui.model;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingCategoriesTest {

    private static void is(String category, String... names) {
        for (String n : names) {
            assertEquals(n, category, SettingCategories.categoryOf(n));
        }
    }

    @Test
    public void altoNamesAreTasks() {
        is(SettingCategories.ALTO, "altoMobDefense", "altoRunsWhenIdle", "altoAutoMLGBucket", "altoIdleCommand");
    }

    @Test
    public void movementAndSafety() {
        is(SettingCategories.MOVEMENT, "allowBreak", "allowParkour", "assumeWalkOnWater", "avoidance", "sprintAscends",
                "walkOnWaterOnePenalty", "jumpPenalty", "fallDamageCost", "mobAvoidanceRadius", "allowOnlyExposedOres");
        // the keyword can sit anywhere in the name
        is(SettingCategories.MOVEMENT, "maxFallHeightNoWater", "strictFallCheck");
    }

    @Test
    public void mining() {
        is(SettingCategories.MINING, "legitMine", "legitMineYLevel", "mineScanDroppedItems", "mineMaxOreLocationsCount",
                "minYLevelWhileMining", "maxYLevelWhileMining", "oreBlocks");
    }

    @Test
    public void exploreIsNotOre() {
        is(SettingCategories.PATHING, "exploreForBlocks", "exploreChunkSetMinimumSize");
    }

    @Test
    public void building() {
        is(SettingCategories.BUILDING, "buildIgnoreBlocks", "buildInLayers", "schematicOrientationX", "mapArtMode", "buildSchematicMirror");
    }

    @Test
    public void rendering() {
        is(SettingCategories.RENDERING, "renderPath", "renderGoal", "colorCurrentPath", "fadePath", "selectionOpacity",
                "renderCachedChunks");
    }

    @Test
    public void elytraPrefixBeatsKeywordFurtherIn() {
        is(SettingCategories.ELYTRA, "elytraFireworkSpeed", "elytraAutoJump", "elytraRenderSimulation", "elytraSmoothLook");
    }

    @Test
    public void chatAndCommands() {
        is(SettingCategories.CHAT, "chatControl", "prefix", "prefixControl", "echoCommands", "logAsToast", "notificationOnPathComplete");
    }

    @Test
    public void worldCache() {
        is(SettingCategories.CACHE, "chunkCaching", "pruneRegionsFromRAM", "repackOnAnyBlockChange", "cachedChunksOpacity",
                "extendCacheOnThreshold", "maxCachedWorldScanCount");
    }

    @Test
    public void pathingAndProcesses() {
        is(SettingCategories.PATHING, "followRadius", "farmMaxScanSize", "goalBreakFromAbove", "pathCutoffFactor",
                "planAheadPrimaryTimeoutMS", "primaryTimeoutMS", "costHeuristic", "pathThroughCachedOnly", "pathingMaxChunkBorderFetch");
    }

    @Test
    public void interfaceSettings() {
        is(SettingCategories.INTERFACE, "guiKey", "guiKeyEnabled");
    }

    @Test
    public void everythingElseIsGeneral() {
        is(SettingCategories.GENERAL, "keepFpsWhileBotting", "blockReachDistance", "freeLook", "smoothLook", "itemSaver", "axisHeight");
        is(SettingCategories.GENERAL, "", "x");
        assertEquals(SettingCategories.GENERAL, SettingCategories.categoryOf(null));
    }

    @Test
    public void camelCaseWordsSplitSanely() {
        assertEquals(List.of("build", "ignore", "blocks"), SettingCategories.words("buildIgnoreBlocks"));
        assertEquals(List.of("mine", "drop", "loiter", "duration", "ms", "thanks", "louca"),
                SettingCategories.words("mineDropLoiterDurationMSThanksLouca"));
        assertEquals(List.of("mapart", "mode"), SettingCategories.words("mapArtMode"));
        assertEquals(List.of("random", "looking113"), SettingCategories.words("randomLooking113"));
    }

    @Test
    public void namesListsEveryCategoryOnceInColumnOrder() {
        List<String> names = SettingCategories.names();
        assertEquals(SettingCategories.ALTO, names.get(0));
        assertEquals(SettingCategories.GENERAL, names.get(names.size() - 1));
        assertEquals(names.size(), names.stream().distinct().count());
        assertEquals(11, names.size());
    }

    @Test
    public void everyRealSettingLandsSomewhereReasonable() throws IOException {
        var parsed = SettingDescriptionsGenerator.parse(Files.readString(SettingDescriptionsGenerator.settingsJava(), StandardCharsets.UTF_8));
        Map<String, Integer> counts = new TreeMap<>();
        for (var p : parsed) {
            if (p.javaOnly()) {
                continue;
            }
            String c = SettingCategories.categoryOf(p.name());
            assertTrue(p.name(), SettingCategories.names().contains(c));
            counts.merge(c, 1, Integer::sum);
        }
        for (String c : SettingCategories.names()) {
            assertTrue(c + " has no settings: " + counts, counts.getOrDefault(c, 0) > 0);
        }
        // a catch-all that swallows half the list is a rule table that stopped working
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue("general is too big: " + counts, counts.get(SettingCategories.GENERAL) * 4 < total);
    }
}
