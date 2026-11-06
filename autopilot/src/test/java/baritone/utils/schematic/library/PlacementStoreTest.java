package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlacementStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private Path file() throws Exception {
        return folder.getRoot().toPath().resolve("altoclef").resolve(PlacementStore.FILE_NAME);
    }

    @Test
    public void roundTripsEveryField() throws Exception {
        Map<String, SchematicPlacement> in = new LinkedHashMap<>();
        in.put("castle.litematic", new SchematicPlacement(new BlockPos(-120, 70, 4400), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, "minecraft:overworld"));
        in.put("sub/tower.schem", new SchematicPlacement(new BlockPos(1, -60, 2), Rotation.NONE, Mirror.NONE, "minecraft:the_nether"));
        Path file = file();
        // the altoclef folder doesn't exist yet, save makes it
        assertTrue(PlacementStore.save(file, in));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        assertEquals(in, PlacementStore.load(file, path -> true));
    }

    @Test
    public void entriesForFilesThatAreGoneArePruned() throws Exception {
        Map<String, SchematicPlacement> in = new LinkedHashMap<>();
        in.put("kept.schem", SchematicPlacement.at(BlockPos.ZERO, "minecraft:overworld"));
        in.put("deleted.schem", SchematicPlacement.at(BlockPos.ZERO, "minecraft:overworld"));
        PlacementStore.save(file(), in);
        Map<String, SchematicPlacement> out = PlacementStore.load(file(), Set.of("kept.schem")::contains);
        assertEquals(Set.of("kept.schem"), out.keySet());
    }

    @Test
    public void aCheckThatThrowsCountsAsGone() throws Exception {
        PlacementStore.save(file(), Map.of("a.schem", SchematicPlacement.at(BlockPos.ZERO, "d")));
        assertTrue(PlacementStore.load(file(), path -> {
            throw new IllegalArgumentException("bad path");
        }).isEmpty());
    }

    @Test
    public void noFileOrNoWorldIsEmpty() throws Exception {
        assertTrue(PlacementStore.load(file(), path -> true).isEmpty());
        assertTrue(PlacementStore.load(null, path -> true).isEmpty());
    }

    @Test
    public void aCorruptFileIsMovedAsideAndReadsEmpty() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void aNewerSchemaIsMovedAsideNotOverwritten() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\": 9, \"placements\": {}}", StandardCharsets.UTF_8);
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void oneBadEntryDoesNotSinkTheRest() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"schema": 1, "placements": {
              "good.schem": {"x": 1, "y": 2, "z": 3, "rotation": "CLOCKWISE_180", "mirror": "NONE", "dimension": "minecraft:overworld"},
              "weird.schem": {"x": 1, "y": 2, "z": 3, "rotation": "SIDEWAYS", "dimension": "minecraft:overworld"},
              "nowhere.schem": {"x": 1, "y": 2, "z": 3},
              "bare.schem": {"x": 4, "y": 5, "z": 6, "dimension": "minecraft:the_end"},
              "lr.schem": {"x": 0, "y": 0, "z": 0, "rotation": "NONE", "mirror": "LEFT_RIGHT", "dimension": "d"},
              "typo.schem": {"x": "abc", "y": 2, "z": 3, "dimension": "minecraft:overworld"},
              "half.schem": {"x": 1.5, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
              "noz.schem": {"x": 1, "y": 2, "dimension": "minecraft:overworld"}
            }}
            """, StandardCharsets.UTF_8);
        Map<String, SchematicPlacement> out = PlacementStore.load(file, path -> true);
        assertEquals(Set.of("good.schem", "bare.schem", "lr.schem", "half.schem"), out.keySet());
        // a hand typed 1.5 is read as 1 rather than losing the entry, the typo and the missing z are dropped
        assertEquals(1, out.get("half.schem").origin().getX());
        assertEquals(Rotation.CLOCKWISE_180, out.get("good.schem").rotation());
        // a missing turn or flip is none of either
        assertEquals(Rotation.NONE, out.get("bare.schem").rotation());
        assertEquals(new BlockPos(4, 5, 6), out.get("bare.schem").origin());
        // and a hand written LEFT_RIGHT comes back as the one mirror plus half a turn
        assertEquals(Mirror.FRONT_BACK, out.get("lr.schem").mirror());
        assertEquals(Rotation.CLOCKWISE_180, out.get("lr.schem").rotation());
    }

    @Test
    public void aFileThatCannotBeReadIsNullNotEmpty() throws Exception {
        // a folder squatting on the name: something is there, but no text comes out. null, and nothing is touched
        Path file = file();
        Files.createDirectories(file.resolve("x"));
        assertEquals(null, PlacementStore.load(file, path -> true));
        assertTrue(Files.isDirectory(file.resolve("x")));
    }

    @Test
    public void bytesThatAreNotTextAreCorruptNotLocked() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void choicesRoundTripNextToThePlacements() throws Exception {
        Map<String, SchematicPlacement> placed = Map.of("a.schem", SchematicPlacement.at(BlockPos.ZERO, "d"));
        Map<String, BuildChoices> chosen = new LinkedHashMap<>();
        chosen.put("a.schem", new BuildChoices(Set.of("minecraft:glass")));
        // a file with no placement yet can still have its rows unchecked
        chosen.put("b.schem", new BuildChoices(Set.of("minecraft:brewing_stand", "minecraft:torch")));
        // and an empty one isn't written at all
        chosen.put("c.schem", BuildChoices.NONE);
        assertTrue(PlacementStore.save(file(), placed, chosen));
        PlacementStore.Saved out = PlacementStore.loadAll(file(), path -> true);
        assertEquals(placed, out.placements());
        assertEquals(Set.of("a.schem", "b.schem"), out.choices().keySet());
        assertEquals(Set.of("minecraft:brewing_stand", "minecraft:torch"), out.choices().get("b.schem").excluded());
        // the old reader still gets its placements out of the same file
        assertEquals(placed, PlacementStore.load(file(), path -> true));
        // and a gone file loses its choices like it loses its placement
        assertEquals(Set.of("a.schem"), PlacementStore.loadAll(file(), "a.schem"::equals).choices().keySet());
    }

    @Test
    public void aBrokenChoicesBlockOnlyCostsTheChoices() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"schema": 1, "placements": {"good.schem": {"x": 1, "y": 2, "z": 3, "dimension": "d"}},
             "choices": {"good.schem": {"excluded": ["minecraft:glass", 3, null, ""]}, "bad.schem": "nope"}}
            """, StandardCharsets.UTF_8);
        PlacementStore.Saved out = PlacementStore.loadAll(file, path -> true);
        assertEquals(Set.of("good.schem"), out.placements().keySet());
        assertEquals(Set.of("minecraft:glass"), out.choices().get("good.schem").excluded());
        assertFalse(out.choices().containsKey("bad.schem"));
        // and a choices block that isn't an object at all is just ignored
        Files.writeString(file, "{\"schema\": 1, \"placements\": {}, \"choices\": []}", StandardCharsets.UTF_8);
        assertTrue(PlacementStore.loadAll(file, path -> true).choices().isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    public void theNetherAnswerRoundTrips() throws Exception {
        Map<String, BuildChoices> chosen = new LinkedHashMap<>();
        chosen.put("yes.schem", new BuildChoices(Set.of(), true));
        chosen.put("no.schem", new BuildChoices(Set.of("minecraft:brewing_stand"), false));
        PlacementStore.save(file(), Map.of(), chosen);
        Map<String, BuildChoices> out = PlacementStore.loadAll(file(), path -> true).choices();
        // a yes with nothing unticked is still a choice worth keeping
        assertEquals(chosen, out);
        Files.writeString(file(), "{\"schema\": 1, \"placements\": {}, \"choices\": {\"a.schem\": {\"nether\": \"maybe\"}}}",
            StandardCharsets.UTF_8);
        // a hand edit we don't follow reads as never asked, which asks again rather than going anywhere
        assertTrue(PlacementStore.loadAll(file(), path -> true).choices().isEmpty());
    }

    @Test
    public void tickingARowBackOnForgetsANo() {
        BuildChoices no = new BuildChoices(Set.of("minecraft:brewing_stand"), false);
        assertEquals(null, no.withIncluded("minecraft:brewing_stand", true).nether());
        // something else changing keeps it
        assertEquals(Boolean.FALSE, no.withIncluded("minecraft:glass", false).nether());
        // and a yes is a yes for anything
        assertEquals(Boolean.TRUE, new BuildChoices(Set.of("minecraft:glass"), true).withIncluded("minecraft:glass", true).nether());
    }

    @Test
    public void noChoicesLeavesTheFileAsItWas() {
        String json = PlacementStore.toJson(Map.of("a.schem", SchematicPlacement.at(BlockPos.ZERO, "d")), Map.of());
        assertFalse(json.contains("choices"));
    }

    @Test
    public void theJsonIsReadableByAHuman() {
        String json = PlacementStore.toJson(Map.of("a.schem",
            new SchematicPlacement(new BlockPos(1, 2, 3), Rotation.COUNTERCLOCKWISE_90, Mirror.FRONT_BACK, "minecraft:overworld")));
        assertTrue(json.contains("\"rotation\": \"COUNTERCLOCKWISE_90\""));
        assertTrue(json.contains("\"mirror\": \"FRONT_BACK\""));
        assertTrue(json.contains("\"dimension\": \"minecraft:overworld\""));
    }

    @Test
    public void aSaveThatThrowsInsideSaysNoInsteadOfThrowing() throws Exception {
        Path file = file();
        Map<String, SchematicPlacement> good = Map.of("a.schem", SchematicPlacement.at(new BlockPos(1, 2, 3), "d"));
        assertTrue(PlacementStore.save(file, good));
        // stands in for gson tripping over our own classes, the way it did on a release jar
        Map<String, SchematicPlacement> bad = new LinkedHashMap<>() {
            @Override
            public void forEach(java.util.function.BiConsumer<? super String, ? super SchematicPlacement> action) {
                throw new IllegalArgumentException("declares multiple JSON fields named 'a'");
            }
        };
        assertFalse(PlacementStore.save(file, bad));
        // and the old file is left alone
        assertEquals(good, PlacementStore.load(file, path -> true));
    }

    @Test
    public void aLoadThatThrowsInsideIsCouldNotReadNotCorrupt() {
        // every call on this path throws, which is as close to "we broke, not the file" as a test gets without gson
        Path cursed = (Path) java.lang.reflect.Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
        assertEquals(null, PlacementStore.loadAll(cursed, path -> true));
    }
}
