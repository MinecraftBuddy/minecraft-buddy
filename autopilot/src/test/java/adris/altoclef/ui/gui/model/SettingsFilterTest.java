package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingsFilterTest {

    private static SettingView v(String name, boolean modified) {
        return new SettingView(name, Kind.BOOLEAN, "Boolean", "true", "true", modified, List.of());
    }

    private static final Map<String, String> DESC = Map.of(
            "allowParkour", "Allow descending diagonally and parkour jumps over gaps.",
            "allowParkourPlace", "Place blocks mid air while doing a parkour jump.",
            "sprintAscends", "Sprint up stairs, also helps with parkour.",
            "renderPath", "Render the path.",
            "mineScanDroppedItems", "Scan for dropped items while mining.",
            "legitMine", "Only mine ore you can see.");

    private static List<SettingView> all() {
        List<SettingView> out = new ArrayList<>();
        out.add(v("allowParkour", false));
        out.add(v("allowParkourPlace", true));
        out.add(v("legitMine", true));
        out.add(v("mineScanDroppedItems", false));
        out.add(v("renderPath", false));
        out.add(v("sprintAscends", false));
        out.add(v("altoMobDefense", true));
        return out;
    }

    private static List<String> names(List<SettingView> l) {
        return l.stream().map(SettingView::name).toList();
    }

    private static List<String> run(String category, boolean modified, String query) {
        return names(SettingsFilter.apply(all(), category, modified, query, n -> DESC.getOrDefault(n, "")));
    }

    @Test
    public void noFiltersKeepsTheOrder() {
        assertEquals(names(all()), run("All", false, ""));
        assertEquals(names(all()), run(null, false, null));
    }

    @Test
    public void categoryNarrows() {
        assertEquals(List.of("allowParkour", "allowParkourPlace", "sprintAscends"), run(SettingCategories.MOVEMENT, false, ""));
        assertEquals(List.of("altoMobDefense"), run(SettingCategories.ALTO, false, ""));
        assertEquals(List.of(), run(SettingCategories.ELYTRA, false, ""));
    }

    @Test
    public void modifiedOnlyNarrows() {
        assertEquals(List.of("allowParkourPlace", "legitMine", "altoMobDefense"), run("All", true, ""));
    }

    @Test
    public void filtersCompose() {
        assertEquals(List.of("allowParkourPlace"), run(SettingCategories.MOVEMENT, true, ""));
        assertEquals(List.of("allowParkourPlace"), run(SettingCategories.MOVEMENT, true, "parkour"));
        assertEquals(List.of(), run(SettingCategories.MINING, true, "parkour"));
    }

    @Test
    public void searchRanksPrefixThenNameThenDescription() {
        // allowParkour* has the word in the name (not at the start), sprintAscends only in its description
        assertEquals(List.of("allowParkour", "allowParkourPlace", "sprintAscends"), run("All", false, "parkour"));
        // a real prefix goes first even though legitMine comes earlier in the list
        assertEquals(List.of("mineScanDroppedItems", "legitMine"), run("All", false, "mine"));
        assertEquals(List.of(), run("All", false, "zzz"));
    }

    @Test
    public void descriptionAndCategoryMatch() {
        assertEquals(List.of("legitMine"), run("All", false, "ore you"));
        // only the category name says "tasks"
        assertEquals(List.of("altoMobDefense"), run("All", false, "tasks"));
        assertTrue(run("All", false, "movement").containsAll(List.of("allowParkour", "sprintAscends")));
    }

    @Test
    public void everyWordHasToMatch() {
        assertEquals(List.of("allowParkourPlace"), run("All", false, "parkour place"));
        assertEquals(List.of(), run("All", false, "parkour render"));
    }

    @Test
    public void queryIsCaseAndSpaceInsensitive() {
        assertEquals(run("All", false, "parkour"), run("All", false, "  PARKOUR  "));
    }

    @Test
    public void countsFollowTheColumnOrderAndSkipEmpty() {
        Map<String, Integer> c = SettingsFilter.counts(all());
        assertEquals(List.of(SettingCategories.ALTO, SettingCategories.MOVEMENT, SettingCategories.MINING, SettingCategories.RENDERING),
                new ArrayList<>(c.keySet()));
        assertEquals(1, (int) c.get(SettingCategories.ALTO));
        assertEquals(3, (int) c.get(SettingCategories.MOVEMENT));
        assertEquals(2, (int) c.get(SettingCategories.MINING));
        assertEquals(3, SettingsFilter.modifiedCount(all()));
    }
}
