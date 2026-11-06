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

import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemFixture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

// every crafting recipe TaskCatalogue hand-registers, checked against the recipe json the game itself ships.
// the data is read out of whatever jar Items came from, so it is right for whatever version is being built.
// smelting and the custom tasks (boats, signs, beds, wool...) are not in here, they never go through a recipe slot list
public class TaskCatalogueRecipeTest {

    // name -> why the catalogue is allowed to disagree with vanilla. keep it empty if you can
    private static final Map<String, String> ALLOWED = new HashMap<>();

    // there are ~290 today. this only exists so the test cannot quietly check nothing
    private static final int MIN_CHECKED = 200;

    @BeforeClass
    public static void bootstrap() {
        // the catalogue is full of Items
        ItemFixture.boot();
    }

    // a recipe as a grid of item id sets. shapeless ones are one row, order means nothing
    private record Grid(int w, int h, List<Set<String>> cells, boolean shapeless, int count) {
    }

    private record Vanilla(String file, Grid grid) {
    }

    private record Crafted(String name, Item output, CraftingRecipe recipe) {
    }

    @Test
    public void catalogueRecipesMatchVanilla() throws Exception {
        Map<String, List<Vanilla>> vanilla = loadVanilla();
        assertFalse("found no vanilla recipes at all, the data lookup is broken", vanilla.isEmpty());

        List<String> problems = new ArrayList<>();
        int checked = 0;
        int skipped = 0;
        Set<String> allowedUsed = new HashSet<>();
        for (Crafted c : craftedRecipes()) {
            if (ALLOWED.containsKey(c.name())) {
                allowedUsed.add(c.name());
                skipped++;
                continue;
            }
            checked++;
            String outId = BuiltInRegistries.ITEM.getKey(c.output()).toString();
            String problem = uncollectable(c.name(), c.recipe());
            if (problem == null) {
                problem = check(c.name(), c.recipe(), vanilla.getOrDefault(outId, List.of()));
            }
            if (problem != null) {
                problems.add(problem);
            }
        }
        for (String name : ALLOWED.keySet()) {
            if (!allowedUsed.contains(name)) {
                problems.add(name + ": allowlisted but not a crafted catalogue recipe anymore, drop it from ALLOWED");
            }
        }

        System.out.println("TaskCatalogueRecipeTest: checked " + checked + " catalogue recipes against " + vanilla.size()
                + " vanilla results (" + skipped + " allowlisted)");
        if (!problems.isEmpty()) {
            fail(problems.size() + " catalogue recipe(s) disagree with vanilla (" + checked + " checked):\n" + String.join("\n", problems));
        }
        assertTrue("only " + checked + " recipes checked, expected at least " + MIN_CHECKED, checked >= MIN_CHECKED);
    }

    // the wood blocks keep their own tasks, but the gather bill needs their recipes. the sweep above checks the shapes,
    // this one checks they're there at all (and that bamboo, which has no planks field, stays out)
    @Test
    public void woodBlocksHaveRecipes() {
        for (String part : List.of("stairs", "slab", "fence", "fence_gate", "door", "trapdoor", "sign", "pressure_plate")) {
            assertNotNull(part, TaskCatalogue.recipeOf("spruce_" + part));
            assertNotNull(part, TaskCatalogue.recipeOf("birch_" + part));
        }
        assertEquals(4, TaskCatalogue.recipeOf("spruce_stairs").getRecipe().outputCount());
        assertEquals(2, TaskCatalogue.recipeOf("oak_trapdoor").getRecipe().outputCount());
        assertNull(TaskCatalogue.recipeOf("bamboo_stairs"));
    }

    // the template copy recipe lives in GetSmithingTemplateTask, not the catalogue, so the sweep above never sees it
    @Test
    public void templateCopyMatchesVanilla() throws Exception {
        List<Vanilla> vanilla = loadVanilla().getOrDefault("minecraft:netherite_upgrade_smithing_template", List.of());
        assertNull(check("netherite_upgrade_smithing_template", adris.altoclef.tasks.resources.GetSmithingTemplateTask.dupeRecipe(), vanilla));
    }

    // ---- the catalogue side ----

    // building a CraftIn*Task needs a running client (Task wants the player), so the catalogue hands over the recipes directly
    private static List<Crafted> craftedRecipes() {
        List<Crafted> out = new ArrayList<>();
        for (Map.Entry<String, RecipeTarget> e : TaskCatalogue.craftedRecipes().entrySet()) {
            out.add(new Crafted(e.getKey(), e.getValue().getOutputItem(), e.getValue().getRecipe()));
        }
        return out;
    }

    private static Set<String> idsOf(ItemTarget target) {
        Set<String> ids = new TreeSet<>();
        for (Item item : target.getMatches()) {
            if (item != null) {
                ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
        }
        return ids;
    }

    private static boolean hasNullMatch(ItemTarget target) {
        for (Item item : target.getMatches()) {
            if (item == null) {
                return true;
            }
        }
        return false;
    }

    // CollectRecipeCataloguedResourcesTask only fetches slots that are a catalogue name or exactly one item, and skips any other
    // multi-item slot with a log line, so the bot would never gather it. a null item in a slot is the same kind of silent hole
    static String uncollectable(String name, CraftingRecipe recipe) {
        for (int i = 0; i < recipe.getSlotCount(); i++) {
            ItemTarget slot = recipe.getSlot(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (hasNullMatch(slot)) {
                return name + ": slot " + i + " has a null item in it";
            }
            if (!slot.isCatalogueItem() && slot.getMatches().length != 1) {
                return name + ": slot " + i + " takes " + slot.getMatches().length + " items but isn't a catalogue name, so the recipe collector would skip it";
            }
        }
        return null;
    }

    // null if the recipe is fine, else a one line description of what is wrong with it
    static String check(String name, CraftingRecipe recipe, List<Vanilla> vanillaRecipes) {
        List<Set<String>> raw = new ArrayList<>();
        for (int i = 0; i < recipe.getSlotCount(); i++) {
            ItemTarget slot = recipe.getSlot(i);
            Set<String> ids = idsOf(slot);
            // an unregistered slot name ("flowr") silently matches nothing, which looks exactly like an empty slot
            if (ids.isEmpty() && slot.isCatalogueItem()) {
                return name + ": slot " + i + " names '" + slot.getCatalogueName() + "' which matches no items (typo, or registered after this recipe)";
            }
            raw.add(ids);
        }
        Grid cat = trim(raw, recipe.getWidth(), recipe.getHeight(), false, recipe.outputCount());
        if (cat == null) {
            return name + ": catalogue recipe has no items in it at all";
        }

        // merged variants first so they lead the error message, it's the most useful one when there are 16 flower files
        List<Vanilla> candidates = new ArrayList<>(mergeVariants(vanillaRecipes));
        candidates.addAll(vanillaRecipes);
        if (candidates.isEmpty()) {
            return name + ": catalogue " + fmt(cat) + " but vanilla has no shaped/shapeless crafting recipe for it";
        }

        List<String> why = new ArrayList<>();
        for (Vanilla v : candidates) {
            String reason = compare(cat, v.grid());
            if (reason == null) {
                return null;
            }
            why.add(v.file() + " " + fmt(v.grid()) + " (" + reason + ")");
        }
        String shown = why.stream().limit(2).collect(Collectors.joining("; "));
        if (why.size() > 2) {
            shown += "; +" + (why.size() - 2) + " more vanilla recipes";
        }
        return name + ": catalogue " + fmt(cat) + " vs vanilla " + shown;
    }

    // ---- comparing ----

    // null if the catalogue grid is something vanilla accepts, else why not
    private static String compare(Grid cat, Grid van) {
        if (cat.count() != van.count()) {
            return "output count " + cat.count() + " vs " + van.count();
        }
        if (van.shapeless()) {
            // the catalogue always lays things out in slots, but vanilla doesn't care where a shapeless recipe's items go
            List<Set<String>> catItems = cat.cells().stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (catItems.size() != van.cells().size()) {
                return "ingredient count " + catItems.size() + " vs " + van.cells().size();
            }
            if (pairUp(catItems, new ArrayList<>(van.cells()), 0, new boolean[catItems.size()])) {
                return null;
            }
            Set<String> extra = new TreeSet<>();
            catItems.forEach(extra::addAll);
            van.cells().forEach(extra::removeAll);
            return extra.isEmpty() ? "no way to pair the slots up with vanilla's ingredients without a slot accepting something extra"
                    : "slots accept " + strip(extra) + " which vanilla rejects";
        }
        if (cat.w() != van.w() || cat.h() != van.h()) {
            return "dims " + cat.w() + "x" + cat.h() + " vs " + van.w() + "x" + van.h();
        }
        String plain = cellsFit(cat.cells(), van.cells());
        if (plain == null) {
            return null;
        }
        // vanilla retries a shaped pattern flipped left to right, so a mirrored layout crafts fine too
        if (cellsFit(cat.cells(), mirror(van).cells()) == null) {
            return null;
        }
        return plain;
    }

    // every vanilla ingredient gets one unused catalogue slot whose items are all acceptable to it
    private static boolean pairUp(List<Set<String>> catItems, List<Set<String>> vanItems, int next, boolean[] used) {
        if (next == vanItems.size()) {
            return true;
        }
        for (int i = 0; i < catItems.size(); i++) {
            if (!used[i] && vanItems.get(next).containsAll(catItems.get(i))) {
                used[i] = true;
                if (pairUp(catItems, vanItems, next + 1, used)) {
                    return true;
                }
                used[i] = false;
            }
        }
        return false;
    }

    // the catalogue may be narrower than vanilla in a cell, never wider
    private static String cellsFit(List<Set<String>> cat, List<Set<String>> van) {
        for (int i = 0; i < cat.size(); i++) {
            Set<String> c = cat.get(i);
            Set<String> v = van.get(i);
            if (c.isEmpty() != v.isEmpty()) {
                return "cell " + i + " is " + (c.isEmpty() ? "empty but vanilla needs " : "filled but vanilla wants it empty: ") + fmtCell(c.isEmpty() ? v : c);
            }
            if (!v.containsAll(c)) {
                Set<String> extra = new TreeSet<>(c);
                extra.removeAll(v);
                return "cell " + i + " accepts " + strip(extra) + " which vanilla rejects";
            }
        }
        return null;
    }

    private static Grid mirror(Grid g) {
        List<Set<String>> flipped = new ArrayList<>();
        for (int y = 0; y < g.h(); y++) {
            for (int x = g.w() - 1; x >= 0; x--) {
                flipped.add(g.cells().get(y * g.w() + x));
            }
        }
        return new Grid(g.w(), g.h(), flipped, g.shapeless(), g.count());
    }

    // cut a row-major grid down to the box around its non-empty cells. null if there are none
    private static Grid trim(List<Set<String>> cells, int w, int h, boolean shapeless, int count) {
        int minX = w, maxX = -1, minY = h, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!cells.get(y * w + x).isEmpty()) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        List<Set<String>> out = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                out.add(cells.get(y * w + x));
            }
        }
        return new Grid(maxX - minX + 1, maxY - minY + 1, out, shapeless, count);
    }

    // vanilla often splits one result into a recipe per variant (suspicious_stew_from_<flower>, rabbit_stew_from_<mushroom>).
    // when variants only differ in a single ingredient, a slot taking any of them is fine, so union that ingredient.
    // kept this strict on purpose: with every other ingredient identical, the union is exactly what vanilla accepts in that slot.
    // variants that differ in two places would let the catalogue mix halves of different recipes, so those are never merged
    private static List<Vanilla> mergeVariants(List<Vanilla> all) {
        Map<String, List<Vanilla>> groups = new LinkedHashMap<>();
        for (Vanilla v : all) {
            Grid g = v.grid();
            groups.computeIfAbsent(g.shapeless() + " " + g.w() + "x" + g.h() + " x" + g.count(), k -> new ArrayList<>()).add(v);
        }
        List<Vanilla> merged = new ArrayList<>();
        for (List<Vanilla> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Grid base = group.get(0).grid();
            List<Set<String>> union = new ArrayList<>();
            for (Set<String> cell : base.cells()) {
                union.add(new TreeSet<>(cell));
            }
            int folded = 1;
            // every folded variant has to vary the same ingredient, or [A'|B] + [A|B'] would add up to an [A'|B'] vanilla has no file for
            int varying = -1;
            for (int k = 1; k < group.size(); k++) {
                List<Set<String>> other = group.get(k).grid().cells();
                int[] pair = differingPair(base, other);
                if (pair == null || (varying >= 0 && pair[0] != varying)) {
                    continue;
                }
                varying = pair[0];
                union.get(pair[0]).addAll(other.get(pair[1]));
                folded++;
            }
            if (folded > 1) {
                merged.add(new Vanilla("(" + folded + " variants merged)", new Grid(base.w(), base.h(), union, base.shapeless(), base.count())));
            }
        }
        return merged;
    }

    // {index in base, index in other} of the one ingredient that differs, or null if it's not exactly one
    private static int[] differingPair(Grid base, List<Set<String>> other) {
        List<Set<String>> b = base.cells();
        if (!base.shapeless()) {
            int found = -1;
            for (int i = 0; i < b.size(); i++) {
                if (!b.get(i).equals(other.get(i))) {
                    if (found >= 0) {
                        return null;
                    }
                    found = i;
                }
            }
            return found < 0 ? null : new int[]{found, found};
        }
        boolean[] otherUsed = new boolean[other.size()];
        List<Integer> baseLeft = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) {
            boolean hit = false;
            for (int j = 0; j < other.size() && !hit; j++) {
                if (!otherUsed[j] && other.get(j).equals(b.get(i))) {
                    otherUsed[j] = true;
                    hit = true;
                }
            }
            if (!hit) {
                baseLeft.add(i);
            }
        }
        List<Integer> otherLeft = new ArrayList<>();
        for (int j = 0; j < other.size(); j++) {
            if (!otherUsed[j]) {
                otherLeft.add(j);
            }
        }
        if (baseLeft.size() != 1 || otherLeft.size() != 1) {
            return null;
        }
        return new int[]{baseLeft.get(0), otherLeft.get(0)};
    }

    // ---- printing ----

    private static String strip(Collection<String> ids) {
        return ids.stream().map(TaskCatalogueRecipeTest::strip).collect(Collectors.toList()).toString();
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String fmtCell(Set<String> cell) {
        if (cell.isEmpty()) {
            return ".";
        }
        if (cell.size() == 1) {
            return strip(cell.iterator().next());
        }
        if (cell.size() <= 4) {
            return "{" + cell.stream().map(TaskCatalogueRecipeTest::strip).collect(Collectors.joining(",")) + "}";
        }
        return "{" + cell.size() + " items}";
    }

    private static String fmt(Grid g) {
        StringBuilder sb = new StringBuilder("[");
        for (int y = 0; y < g.h(); y++) {
            if (y > 0) {
                sb.append(" / ");
            }
            for (int x = 0; x < g.w(); x++) {
                if (x > 0) {
                    sb.append(" | ");
                }
                sb.append(fmtCell(g.cells().get(y * g.w() + x)));
            }
        }
        return sb.append(g.shapeless() ? "] shapeless" : "]").toString();
    }

    // ---- the vanilla side ----

    private static Map<String, List<Vanilla>> loadVanilla() throws Exception {
        Path root = dataRoot();
        Map<String, String> recipeFiles = readJson(root, "data/minecraft/recipe/");
        tags = readJson(root, "data/minecraft/tags/item/");
        tagCache.clear();

        Map<String, List<Vanilla>> byResult = new TreeMap<>();
        for (Map.Entry<String, String> e : recipeFiles.entrySet()) {
            JsonObject json = JsonParser.parseString(e.getValue()).getAsJsonObject();
            String type = json.get("type").getAsString();
            boolean shaped = type.endsWith("crafting_shaped");
            if (!shaped && !type.endsWith("crafting_shapeless")) {
                continue;
            }
            JsonElement result = json.get("result");
            String resultId;
            int count = 1;
            if (result.isJsonObject()) {
                JsonObject r = result.getAsJsonObject();
                resultId = r.has("id") ? r.get("id").getAsString() : r.get("item").getAsString();
                if (r.has("count")) {
                    count = r.get("count").getAsInt();
                }
            } else {
                resultId = result.getAsString();
            }
            Grid grid;
            if (shaped) {
                JsonArray pattern = json.getAsJsonArray("pattern");
                JsonObject key = json.getAsJsonObject("key");
                int h = pattern.size();
                int w = 0;
                for (JsonElement row : pattern) {
                    w = Math.max(w, row.getAsString().length());
                }
                List<Set<String>> cells = new ArrayList<>();
                for (JsonElement row : pattern) {
                    String s = row.getAsString();
                    for (int x = 0; x < w; x++) {
                        char ch = x < s.length() ? s.charAt(x) : ' ';
                        cells.add(ch == ' ' ? new TreeSet<>() : ingredient(key.get(String.valueOf(ch))));
                    }
                }
                grid = trim(cells, w, h, false, count);
            } else {
                List<Set<String>> cells = new ArrayList<>();
                for (JsonElement ing : json.getAsJsonArray("ingredients")) {
                    cells.add(ingredient(ing));
                }
                grid = new Grid(cells.size(), 1, cells, true, count);
            }
            if (grid != null) {
                byResult.computeIfAbsent(normalize(resultId), k -> new ArrayList<>()).add(new Vanilla(e.getKey(), grid));
            }
        }
        return byResult;
    }

    private static Map<String, String> tags = new HashMap<>();
    private static final Map<String, Set<String>> tagCache = new HashMap<>();

    private static String normalize(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    // an item id, a #tag, or an array of those (older formats wrapped them in {"item"} / {"tag"})
    private static Set<String> ingredient(JsonElement el) {
        Set<String> out = new TreeSet<>();
        if (el.isJsonArray()) {
            for (JsonElement inner : el.getAsJsonArray()) {
                out.addAll(ingredient(inner));
            }
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("item")) {
                out.add(normalize(o.get("item").getAsString()));
            } else if (o.has("tag")) {
                out.addAll(tagItems(normalize(o.get("tag").getAsString())));
            }
        } else {
            String s = el.getAsString();
            if (s.startsWith("#")) {
                out.addAll(tagItems(normalize(s.substring(1))));
            } else {
                out.add(normalize(s));
            }
        }
        return out;
    }

    // tags nest (#minecraft:planks style lists can point at other tags), so expand all the way down
    private static Set<String> tagItems(String tagId) {
        Set<String> cached = tagCache.get(tagId);
        if (cached != null) {
            return cached;
        }
        String path = tagId.substring(tagId.indexOf(':') + 1);
        String text = tags.get(path);
        if (text == null) {
            throw new IllegalStateException("recipe uses tag " + tagId + " but there is no tags/item/" + path + ".json");
        }
        // guards a cycle, there aren't any but a hang is a rude way to find one
        tagCache.put(tagId, new TreeSet<>());
        Set<String> out = new TreeSet<>();
        for (JsonElement v : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("values")) {
            String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
            if (s.startsWith("#")) {
                out.addAll(tagItems(normalize(s.substring(1))));
            } else {
                out.add(normalize(s));
            }
        }
        tagCache.put(tagId, out);
        return out;
    }

    // the jar (or dir) Items was loaded from, it carries the game's data folder
    private static Path dataRoot() throws URISyntaxException {
        return Paths.get(Items.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    // path under the prefix (no .json) -> file text, for a jar or an unpacked dir
    private static Map<String, String> readJson(Path root, String prefix) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (Files.isDirectory(root)) {
            Path dir = root.resolve(prefix);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList())) {
                        String rel = dir.relativize(p).toString().replace('\\', '/');
                        out.put(rel.substring(0, rel.length() - ".json".length()), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
            return out;
        }
        try (ZipFile zip = new ZipFile(root.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry entry = en.nextElement();
                String n = entry.getName();
                if (n.startsWith(prefix) && n.endsWith(".json")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        out.put(n.substring(prefix.length(), n.length() - ".json".length()), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return out;
    }

    // ---- can this test actually fail? ----

    private static ItemTarget[] slots(Object... items) {
        ItemTarget[] out = new ItemTarget[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null ? null : new ItemTarget((Item) items[i]);
        }
        return out;
    }

    private static Set<String> ids(String... ids) {
        return new TreeSet<>(List.of(ids));
    }

    private static final Set<String> NONE = Collections.emptySet();

    private static Vanilla van(boolean shapeless, int w, int h, int count, List<Set<String>> cells) {
        return new Vanilla("hand_built", new Grid(w, h, cells, shapeless, count));
    }

    // fishing rod top left, carrot bottom right
    private static Vanilla diagonalStick() {
        return van(false, 2, 2, 1, List.of(ids("minecraft:fishing_rod"), NONE, NONE, ids("minecraft:carrot")));
    }

    @Test
    public void comparatorAcceptsTheRightLayout() {
        CraftingRecipe good = CraftingRecipe.newShapedRecipe("t", slots(Items.FISHING_ROD, null, null, Items.CARROT), 1);
        assertNull(check("t", good, List.of(diagonalStick())));
    }

    @Test
    public void comparatorAcceptsMirroredAndNarrowerAndShapeless() {
        // bottom left carrot is the mirror of the diagonal, vanilla would take that one too
        CraftingRecipe mirrored = CraftingRecipe.newShapedRecipe("t", slots(null, Items.FISHING_ROD, Items.CARROT, null), 1);
        assertNull(check("t", mirrored, List.of(diagonalStick())));

        // a slot that is a subset of what vanilla takes is fine
        Vanilla wide = van(false, 1, 1, 1, List.of(ids("minecraft:cobblestone", "minecraft:blackstone")));
        CraftingRecipe narrow = CraftingRecipe.newShapedRecipe("t", slots(Items.COBBLESTONE, null, null, null), 1);
        assertNull(check("t", narrow, List.of(wide)));

        // shapeless vanilla doesn't care where the catalogue put things
        Vanilla shapeless = van(true, 2, 1, 1, List.of(ids("minecraft:bowl"), ids("minecraft:red_mushroom")));
        CraftingRecipe laidOut = CraftingRecipe.newShapedRecipe("t", slots(null, Items.RED_MUSHROOM, Items.BOWL, null), 1);
        assertNull(check("t", laidOut, List.of(shapeless)));
    }

    @Test
    public void comparatorCatchesAWrongLayout() {
        // the carrot_on_a_stick bug: side by side instead of diagonal
        CraftingRecipe sideBySide = CraftingRecipe.newShapedRecipe("t", slots(Items.FISHING_ROD, Items.CARROT, null, null), 1);
        String problem = check("t", sideBySide, List.of(diagonalStick()));
        assertNotNull(problem);
        assertTrue(problem, problem.contains("dims 2x1 vs 2x2"));
    }

    @Test
    public void comparatorCatchesATooWideSlot() {
        // the suspicious_stew bug: a slot that takes something vanilla won't
        Vanilla onlyPoppy = van(false, 1, 1, 1, List.of(ids("minecraft:poppy")));
        CraftingRecipe tooWide = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget(new Item[]{Items.POPPY, Items.PEONY}), null, null, null}, 1);
        String problem = check("t", tooWide, List.of(onlyPoppy));
        assertNotNull(problem);
        assertTrue(problem, problem.contains("peony"));

        // and a wrong output count
        CraftingRecipe wrongCount = CraftingRecipe.newShapedRecipe("t", slots(Items.POPPY, null, null, null), 4);
        assertNotNull(check("t", wrongCount, List.of(onlyPoppy)));
    }

    @Test
    public void comparatorMergesVariantsThatDifferInOneIngredient() {
        // rabbit_stew_from_brown_mushroom / _red_mushroom shape: a slot taking both is what the two files add up to
        Vanilla brown = van(true, 2, 1, 1, List.of(ids("minecraft:bowl"), ids("minecraft:brown_mushroom")));
        Vanilla red = van(true, 2, 1, 1, List.of(ids("minecraft:bowl"), ids("minecraft:red_mushroom")));
        CraftingRecipe either = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget(Items.BOWL), new ItemTarget(new Item[]{Items.BROWN_MUSHROOM, Items.RED_MUSHROOM}), null, null}, 1);
        assertNull(check("t", either, List.of(brown, red)));
        // but not something neither variant has
        CraftingRecipe stray = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget(Items.BOWL), new ItemTarget(new Item[]{Items.BROWN_MUSHROOM, Items.CRIMSON_FUNGUS}), null, null}, 1);
        assertNotNull(check("t", stray, List.of(brown, red)));
    }

    @Test
    public void comparatorDoesNotMixVariantsThatVaryDifferentIngredients() {
        Vanilla base = van(true, 2, 1, 1, List.of(ids("minecraft:bowl"), ids("minecraft:poppy")));
        Vanilla varyFirst = van(true, 2, 1, 1, List.of(ids("minecraft:stick"), ids("minecraft:poppy")));
        Vanilla varySecond = van(true, 2, 1, 1, List.of(ids("minecraft:bowl"), ids("minecraft:allium")));
        // stick + allium is in none of the three files
        CraftingRecipe mixed = CraftingRecipe.newShapedRecipe("t", slots(Items.STICK, Items.ALLIUM, null, null), 1);
        assertNotNull(check("t", mixed, List.of(base, varyFirst, varySecond)));
        CraftingRecipe real = CraftingRecipe.newShapedRecipe("t", slots(Items.STICK, Items.POPPY, null, null), 1);
        assertNull(check("t", real, List.of(base, varyFirst, varySecond)));
    }

    @Test
    public void collectorBlindSpotsAreCaught() {
        // several items in a slot with no catalogue name: the recipe collector skips it
        CraftingRecipe wide = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget(new Item[]{Items.POPPY, Items.ALLIUM}), null, null, null}, 1);
        assertNotNull(uncollectable("t", wide));
        // a null item (what bamboo's missing planks field used to give the button recipe)
        CraftingRecipe nothing = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget((Item) null), null, null, null}, 1);
        assertNotNull(uncollectable("t", nothing));
        CraftingRecipe fine = CraftingRecipe.newShapedRecipe("t", slots(Items.POPPY, null, null, null), 1);
        assertNull(uncollectable("t", fine));
    }

    @Test
    public void comparatorCatchesAnUnregisteredSlotName() {
        CraftingRecipe typo = CraftingRecipe.newShapedRecipe("t", new ItemTarget[]{new ItemTarget("definitely_not_a_catalogue_name"), null, null, null}, 1);
        String problem = check("t", typo, List.of(van(false, 1, 1, 1, List.of(ids("minecraft:poppy")))));
        assertNotNull(problem);
        assertTrue(problem, problem.contains("definitely_not_a_catalogue_name"));
    }
}
