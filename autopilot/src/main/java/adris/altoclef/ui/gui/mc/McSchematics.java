package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.SchematicBuildTask;
import adris.altoclef.tasks.construction.schematic.BuildMemory;
import adris.altoclef.tasks.construction.schematic.SkipItems;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.move.MoveSession;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.SchematicTransforms;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.Helper;
import baritone.altoclef.AltoClefBridge;
import baritone.cache.WorldData;
import baritone.utils.schematic.library.BuildChoices;
import baritone.utils.schematic.library.InventoryCounts;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import baritone.utils.schematic.library.PlacementStore;
import baritone.utils.schematic.library.SchematicImporter;
import baritone.utils.schematic.library.SchematicInfo;
import baritone.utils.schematic.library.SchematicLibrary;
import baritone.utils.schematic.library.SchematicPlacement;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

// the real SchematicsAccess. the folder scan and every parse run on one daemon thread, the gui only ever reads the
// latest finished snapshot. one instance is shared by every gui screen (a gui scale change rebuilds the screen and its
// facade), so the parsed list survives that and a rebuild doesn't re-read the whole folder
public final class McSchematics implements SchematicsAccess {

    private static McSchematics shared;

    public static synchronized McSchematics shared() {
        if (shared == null) {
            File gameDir = Minecraft.getInstance().gameDirectory;
            shared = new McSchematics(SchematicLibrary.forGameDir(gameDir), worker(), McSchematics::playerItems,
                McSchematics::catalogueKnows, runnable -> Minecraft.getInstance().execute(runnable), FilePicker::pick,
                new LiveGame(), System::currentTimeMillis);
        }
        return shared;
    }

    private static ExecutorService worker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "soprano-schematics");
            t.setDaemon(true);
            return t;
        });
    }

    private static final int MATERIAL_CACHE = 12;
    // how often a read gives an unreadable placements file another go. it's a disk hit, not something for every frame
    private static final long RETRY_MS = 5_000;

    // what the file picker is: extensions in, a choice out. a seam so the tests don't open dialogs
    interface Picker {
        FilePicker.Choice pick(List<String> extensions, String title);
    }

    // Game.build saying no on purpose, with the reason the player gets to read
    static final class Refused extends RuntimeException {
        Refused(String why) {
            super(why, null, false, false);
        }
    }

    // the world side of placing and building, a seam so the tests run without a game. game thread only, all of it
    interface Game {
        // the level id the player is in ("minecraft:overworld"), null when there's no world
        String dimension();

        // null when there's no player
        BlockPos feet();

        // where this world's placements live, null when it has no folder (replay), then they only live in memory
        Path placementsFile();

        // anything that changes with the level, so the terrain cache knows to start over. null without a world
        Object level();

        TerrainMap.Source terrain();

        // starts it and hands back its serial, see runningBuild. in survival that's the gathering task's, not the builder's.
        // memory is the file's choices: the unchecked items (both modes cut them) and the nether answer (survival only)
        long build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror, BuildMemory memory);

        // the serial of whatever is building right now (our gathering task while it runs, else the builder), 0 when idle
        long runningBuild();

        // a line in chat, for when a build fails after the click (the tab may be closed by then)
        void say(String message);

        // swaps the gui for the move screen, next tick. a default so the test game doesn't have to care
        default void openMover(String relativePath) {
        }

        // how far from the feet a build can be for move mode, the free camera's leash
        default int moveRange() {
            return 128;
        }
    }

    private static final class Cached {
        final long lastModified;
        final long size;
        volatile boolean done;
        volatile MaterialList list;
        volatile String error;
        // the last answer handed out and the inventory it was made for, so a steady inventory costs nothing per frame
        Map<String, Integer> mergedFor;
        Materials merged;

        Cached(SchematicInfo info) {
            this.lastModified = info.lastModified();
            this.size = info.fileSize();
        }
    }

    private static final class Parsed {
        final long lastModified;
        final long size;
        volatile IStaticSchematic schematic;
        volatile boolean failed;
        // game thread only
        ISchematic turned;
        Rotation rotation;
        Mirror mirror;

        Parsed(SchematicInfo info) {
            this.lastModified = info.lastModified();
            this.size = info.fileSize();
        }
    }

    private final SchematicLibrary library;
    private final Executor background;
    private final Supplier<Map<String, Integer>> inventory;
    private final Predicate<String> gettable;
    private final Executor gameThread;
    private final Picker picker;
    private final Game game;
    private final LongSupplier clock;
    private final TerrainMap terrainMap;

    private final Map<String, Cached> materials = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MATERIAL_CACHE;
        }
    };
    private final AtomicBoolean pickerOpen = new AtomicBoolean();

    // the parsed file behind transformed(), kept strong while the preview wants it. two, a preview shows one at a time
    private final Map<String, Parsed> parsed = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > 2;
        }
    };

    // game thread only: this world's placements. placements = chosen or built at (saved), defaults = made up at the
    // feet and not saved
    private final Map<String, SchematicPlacement> placements = new LinkedHashMap<>();
    private final Map<String, SchematicPlacement> defaults = new HashMap<>();
    // game thread only: this world's build choices, saved in the same file as the placements
    private final Map<String, BuildChoices> choices = new LinkedHashMap<>();
    private boolean placementsLoaded;
    private boolean saveBlocked;
    private long lastRetry;
    private Path placementsFile;
    private int buildTicket;
    // the builder's serial for the build we started, so building() can tell when it's done or a #build replaced it
    private long ourBuild;
    private volatile Building building = Building.IDLE;

    // guarded by this
    private boolean started;
    private boolean loading;
    private boolean again;
    private Listing listing = new Listing(State.LOADING, List.of());
    private Map<String, SchematicInfo> infos = Map.of();

    McSchematics(SchematicLibrary library, Executor background, Supplier<Map<String, Integer>> inventory,
                 Predicate<String> gettable, Executor gameThread, Picker picker, Game game, LongSupplier clock) {
        this.library = library;
        this.background = background;
        this.inventory = inventory;
        this.gettable = gettable;
        this.gameThread = gameThread;
        this.picker = picker;
        this.game = game;
        this.clock = clock;
        this.terrainMap = new TerrainMap(clock);
    }

    // ----- the list

    @Override
    public Listing list() {
        boolean first;
        synchronized (this) {
            first = !started;
            started = true;
        }
        if (first) {
            refresh();
        }
        synchronized (this) {
            return listing;
        }
    }

    @Override
    public void refresh() {
        synchronized (this) {
            started = true;
            if (loading) {
                // a scan is already running, it'll go around once more when it's done in case the folder changed since
                again = true;
                return;
            }
            loading = true;
            listing = new Listing(State.LOADING, listing.rows());
        }
        background.execute(this::scan);
    }

    private void scan() {
        while (true) {
            List<SchematicInfo> found = null;
            try {
                found = library.list();
            } catch (Throwable t) {
                Safe.report("gui schematics list", t);
            }
            synchronized (this) {
                if (found != null) {
                    Map<String, SchematicInfo> byPath = new LinkedHashMap<>();
                    List<Row> rows = new ArrayList<>(found.size());
                    for (SchematicInfo info : found) {
                        byPath.put(info.relativePath(), info);
                        rows.add(row(info));
                    }
                    infos = Collections.unmodifiableMap(byPath);
                    listing = new Listing(State.LOADING, List.copyOf(rows));
                }
                if (again) {
                    again = false;
                    continue;
                }
                loading = false;
                listing = new Listing(State.READY, listing.rows());
                break;
            }
        }
        // a failed parse (out of memory, a file that was half copied) gets another go after a refresh
        synchronized (materials) {
            materials.values().removeIf(c -> c.done && c.error != null);
        }
    }

    private static Row row(SchematicInfo info) {
        return new Row(info.name(), info.relativePath(), info.sizeX(), info.sizeY(), info.sizeZ(), info.blockCount(),
            info.format(), info.version(), info.fileSize(), info.error(), info.unknownSummary());
    }

    // ----- the cost of a build

    @Override
    public Materials materials(String relativePath) {
        list();
        SchematicInfo info;
        State state;
        synchronized (this) {
            info = infos.get(relativePath);
            state = listing.state();
        }
        if (info == null) {
            return state == State.LOADING ? Materials.loading(relativePath) : failed(relativePath, "Not in the library, refresh the list");
        }
        if (!info.ok()) {
            return failed(relativePath, info.error());
        }
        Cached entry;
        boolean fresh = false;
        synchronized (materials) {
            entry = materials.get(relativePath);
            if (entry == null || entry.lastModified != info.lastModified() || entry.size != info.fileSize()) {
                entry = new Cached(info);
                materials.put(relativePath, entry);
                fresh = true;
            }
        }
        if (fresh) {
            Cached target = entry;
            background.execute(() -> count(info, target));
        }
        if (!entry.done) {
            return Materials.loading(relativePath);
        }
        if (entry.error != null) {
            return failed(relativePath, entry.error);
        }
        return merge(relativePath, entry);
    }

    private void count(SchematicInfo info, Cached entry) {
        try {
            IStaticSchematic parsed = library.load(info);
            entry.list = MaterialList.of(parsed);
        } catch (OutOfMemoryError e) {
            entry.error = "Too big to count (out of memory)";
        } catch (Throwable t) {
            Safe.report("gui schematic materials", t);
            entry.error = "Couldn't read this file";
        } finally {
            entry.done = true;
        }
    }

    private static Materials failed(String relativePath, String error) {
        return new Materials(State.READY, relativePath, List.of(), List.of(), List.of(), List.of(), error);
    }

    // the needs never change for a file, what the player carries does, so only this half is redone per inventory
    private Materials merge(String relativePath, Cached entry) {
        Map<String, Integer> have = inventory.get();
        synchronized (entry) {
            if (entry.merged != null && have.equals(entry.mergedFor)) {
                return entry.merged;
            }
            MaterialList list = entry.list;
            Materials out = new Materials(State.READY, relativePath, lines(list.items(), Kind.ITEM, have),
                lines(list.cantPlace(), Kind.CANT_PLACE, have), lines(list.notCounted(), Kind.NOT_COUNTED, have),
                list.notes(), null);
            entry.mergedFor = new HashMap<>(have);
            entry.merged = out;
            return out;
        }
    }

    private List<Line> lines(List<MaterialLine> source, Kind kind, Map<String, Integer> have) {
        List<Line> out = new ArrayList<>(source.size());
        for (MaterialLine line : source) {
            String id = line.item();
            boolean item = kind == Kind.ITEM;
            out.add(new Line(id, item ? id : iconFor(id), item ? itemName(id) : blockName(id), line.count(),
                item ? have.getOrDefault(id, 0) : 0, kind, line.note(), item && gettable.test(id)));
        }
        return List.copyOf(out);
    }

    // ----- names and icons

    private static Item itemOf(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
    }

    private static String itemName(String id) {
        try {
            Item item = itemOf(id);
            if (item != Items.AIR) {
                String name = item.getDefaultInstance().getItemName().getString();
                // a name that is still a translation key means the language isn't loaded, which is no help to anyone
                if (!name.isEmpty() && !name.startsWith("item.") && !name.startsWith("block.")) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // fall through to the id
        }
        return ItemEntry.titleCase(id);
    }

    private static String blockName(String id) {
        try {
            Identifier key = Identifier.tryParse(id);
            Block block = key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(key);
            if (block != Blocks.AIR) {
                String name = block.getName().getString();
                if (!name.isEmpty() && !name.startsWith("block.")) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // fall through to the id
        }
        return ItemEntry.titleCase(id);
    }

    // the rows that aren't items have a block id. a few of those still have something to draw
    private static String iconFor(String blockId) {
        if (itemOf(blockId) != Items.AIR) {
            return blockId;
        }
        return switch (blockId) {
            case "minecraft:water" -> "minecraft:water_bucket";
            case "minecraft:lava" -> "minecraft:lava_bucket";
            case "minecraft:fire" -> "minecraft:flint_and_steel";
            case "minecraft:soul_fire" -> "minecraft:flint_and_steel";
            default -> "";
        };
    }

    // ----- the game side

    private static Map<String, Integer> playerItems() {
        try {
            var player = Minecraft.getInstance().player;
            return player == null ? Map.of() : InventoryCounts.of(player.getInventory());
        } catch (Throwable t) {
            Safe.report("gui schematics inventory", t);
            return Map.of();
        }
    }

    private static boolean catalogueKnows(String itemId) {
        try {
            Item item = itemOf(itemId);
            return item != Items.AIR && TaskCatalogue.isObtainable(item);
        } catch (Throwable t) {
            // the catalogue builds itself on first touch, and a tab that throws every frame is worse than no flag
            Safe.report("gui schematics catalogue", t);
            return false;
        }
    }

    // ----- placements. game thread only, the gui and the tick are the only callers

    @Override
    public Placement placement(String relativePath) {
        return view(current(relativePath));
    }

    @Override
    public Placement move(String relativePath, int dx, int dy, int dz) {
        return edit(relativePath, p -> p.moved(dx, dy, dz));
    }

    @Override
    public Placement rotate(String relativePath, boolean clockwise) {
        SchematicInfo info = info(relativePath);
        return info == null ? null : edit(relativePath, p -> p.rotated(clockwise, info.sizeX(), info.sizeY(), info.sizeZ()));
    }

    @Override
    public Placement mirror(String relativePath) {
        return edit(relativePath, SchematicPlacement::flipped);
    }

    @Override
    public Placement placeAtFeet(String relativePath) {
        BlockPos feet = game.feet();
        String dimension = game.dimension();
        if (feet == null || dimension == null) {
            return null;
        }
        return edit(relativePath, p -> p.movedTo(feet, dimension));
    }

    @Override
    public Footprint footprint(String relativePath) {
        SchematicPlacement p = current(relativePath);
        SchematicInfo info = info(relativePath);
        if (p == null || info == null) {
            return null;
        }
        Vec3i size = p.size(info.sizeX(), info.sizeY(), info.sizeZ());
        return new Footprint(p.origin().getX(), p.origin().getY(), p.origin().getZ(), size.getX(), size.getY(), size.getZ());
    }

    @Override
    public boolean atFeet(String relativePath) {
        try {
            SchematicPlacement p = current(relativePath);
            BlockPos feet = game.feet();
            return p != null && feet != null && p.origin().equals(feet) && p.dimension().equals(game.dimension());
        } catch (Throwable t) {
            Safe.report("gui schematic feet", t);
            return false;
        }
    }

    // #build's end: the placements somebody actually chose (or built at) in this dimension, never the made-up feet
    // ones. straight from memory, no folder scan, so a chat command doesn't wait on the library. game thread only
    public Map<String, SchematicPlacement> pinnedHere() {
        try {
            if (!syncWorld()) {
                return Map.of();
            }
            String dimension = game.dimension();
            Map<String, SchematicPlacement> out = new LinkedHashMap<>();
            placements.forEach((path, p) -> {
                if (p.dimension().equals(dimension)) {
                    out.put(path, p);
                }
            });
            return out;
        } catch (Throwable t) {
            Safe.report("gui schematic pinned", t);
            return Map.of();
        }
    }

    // the placement the gui sees and the build uses, without the minecraft types
    private Placement view(SchematicPlacement p) {
        if (p == null) {
            return null;
        }
        return new Placement(p.origin().getX(), p.origin().getY(), p.origin().getZ(), p.turns(), p.isMirrored(),
            p.dimension(), p.dimension().equals(game.dimension()));
    }

    private Placement edit(String relativePath, UnaryOperator<SchematicPlacement> change) {
        try {
            SchematicPlacement p = current(relativePath);
            if (p == null) {
                return null;
            }
            SchematicPlacement next = change.apply(p);
            if (!next.equals(p)) {
                // somewhere else (or the other way round) is a new build, and a new build gets asked again
                BuildChoices chosen = choicesOf(relativePath);
                if (chosen.nether() != null) {
                    choices.put(relativePath, chosen.withNether(null));
                    if (choices.get(relativePath).isEmpty()) {
                        choices.remove(relativePath);
                    }
                }
            }
            pin(relativePath, next);
            return view(next);
        } catch (Throwable t) {
            Safe.report("gui schematic placement", t);
            // a move that quietly does nothing reads as a broken button, so at least say it didn't take
            game.say("Could not change the placement: " + t.getClass().getSimpleName());
            return null;
        }
    }

    // a listed row that parsed, the only kind with a size to turn
    private SchematicInfo info(String relativePath) {
        list();
        SchematicInfo info;
        synchronized (this) {
            info = infos.get(relativePath);
        }
        return info != null && info.ok() ? info : null;
    }

    // this world's placement for the path, making the feet default when there is none yet
    private SchematicPlacement current(String relativePath) {
        if (relativePath == null || !syncWorld() || info(relativePath) == null) {
            return null;
        }
        SchematicPlacement saved = placements.get(relativePath);
        if (saved != null) {
            return saved;
        }
        String dimension = game.dimension();
        SchematicPlacement made = defaults.get(relativePath);
        // an unsaved default follows the player through a portal, nobody chose the old spot
        if (made == null || !made.dimension().equals(dimension)) {
            BlockPos feet = game.feet();
            if (feet == null) {
                return null;
            }
            made = SchematicPlacement.at(feet, dimension);
            defaults.put(relativePath, made);
        }
        return made;
    }

    // the file the placements come from follows the world. a new world drops what the old one had in memory
    private boolean syncWorld() {
        if (game.dimension() == null) {
            return false;
        }
        Path file = game.placementsFile();
        if (!placementsLoaded || !Objects.equals(file, placementsFile)) {
            placementsLoaded = true;
            placementsFile = file;
            defaults.clear();
            placements.clear();
            choices.clear();
            PlacementStore.Saved loaded = PlacementStore.loadAll(file, this::exists);
            saveBlocked = loaded == null;
            lastRetry = clock.getAsLong();
            if (loaded != null) {
                placements.putAll(loaded.placements());
                choices.putAll(loaded.choices());
            }
        } else if (saveBlocked && clock.getAsLong() - lastRetry >= RETRY_MS) {
            // the gui reads every frame, so it only knocks on the file now and then. a file that comes back shows its
            // placements without anyone having to move something first
            lastRetry = clock.getAsLong();
            reloadIfBlocked();
        }
        return true;
    }

    // the file was there but unreadable when the world loaded. read it again and merge, the edits made since win over
    // what's on disk. false while it still won't read
    private boolean reloadIfBlocked() {
        if (!saveBlocked || placementsFile == null) {
            return !saveBlocked;
        }
        PlacementStore.Saved loaded = PlacementStore.loadAll(placementsFile, this::exists);
        if (loaded == null) {
            return false;
        }
        Map<String, SchematicPlacement> merged = new LinkedHashMap<>(loaded.placements());
        merged.putAll(placements);
        placements.clear();
        placements.putAll(merged);
        Map<String, BuildChoices> mergedChoices = new LinkedHashMap<>(loaded.choices());
        mergedChoices.putAll(choices);
        choices.clear();
        choices.putAll(mergedChoices);
        saveBlocked = false;
        return true;
    }

    private boolean exists(String relativePath) {
        return fileFor(relativePath) != null;
    }

    // a placement somebody chose (or built at) is saved, the made-up ones aren't
    private void pin(String relativePath, SchematicPlacement p) {
        defaults.remove(relativePath);
        placements.put(relativePath, p);
        save();
    }

    private void save() {
        if (placementsFile == null) {
            return;
        }
        // saving over an unreadable file would wipe everything else in it, so read it again first and only save once
        // that works. until then the edits live in memory. not throttled, a save is rare and it's the one that matters
        if (!reloadIfBlocked()) {
            return;
        }
        PlacementStore.save(placementsFile, placements, choices);
    }

    // ----- build choices. game thread only, like the placements

    @Override
    public Set<String> excluded(String relativePath) {
        if (relativePath == null || !syncWorld()) {
            return Set.of();
        }
        return choicesOf(relativePath).excluded();
    }

    @Override
    public void setIncluded(String relativePath, String itemId, boolean included) {
        try {
            if (relativePath == null || itemId == null || !syncWorld() || fileFor(relativePath) == null) {
                return;
            }
            choose(relativePath, choicesOf(relativePath).withIncluded(itemId, included));
        } catch (Throwable t) {
            Safe.report("gui schematic choices", t);
        }
    }

    private BuildChoices choicesOf(String relativePath) {
        return choices.getOrDefault(relativePath, BuildChoices.NONE);
    }

    private void choose(String relativePath, BuildChoices next) {
        if (next.equals(choicesOf(relativePath))) {
            return;
        }
        if (next.isEmpty()) {
            choices.remove(relativePath);
        } else {
            choices.put(relativePath, next);
        }
        save();
    }

    // the build's end of the choices. it can outlive the world it started in (a quit with the task still around), and
    // a stale one must not write into the next world's file. the unchecked rows are the ones at the click: the tab
    // greys the boxes from then on, and a tick during the read would otherwise sneak into the build it says it won't
    private BuildMemory memoryFor(String relativePath, Path world, Set<String> atClick) {
        return new BuildMemory() {
            @Override
            public Set<String> excluded() {
                return atClick;
            }

            @Override
            public Boolean nether() {
                return live() ? choicesOf(relativePath).nether() : null;
            }

            @Override
            public void netherAnswered(boolean yes, Set<String> leftOut) {
                if (!live()) {
                    return;
                }
                BuildChoices next = choicesOf(relativePath).withNether(yes);
                for (String id : leftOut) {
                    next = next.withIncluded(id, false);
                }
                choose(relativePath, next);
            }

            @Override
            public void finished() {
                if (live()) {
                    choose(relativePath, choicesOf(relativePath).withNether(null));
                }
            }

            private boolean live() {
                return syncWorld() && Objects.equals(world, placementsFile);
            }
        };
    }

    // ----- building

    @Override
    public Result build(String relativePath) {
        try {
            if (game.dimension() == null || game.feet() == null) {
                return Result.fail("Join a world first");
            }
            SchematicInfo info = info(relativePath);
            if (info == null) {
                return Result.fail(fileFor(relativePath) == null ? "Can't find that schematic, refresh the list"
                    : "That schematic didn't load");
            }
            SchematicPlacement p = current(relativePath);
            if (p == null) {
                return Result.fail("Join a world first");
            }
            if (!p.dimension().equals(game.dimension())) {
                return Result.fail("It's placed in another dimension, move it here first");
            }
            // building somewhere is choosing it
            pin(relativePath, p);
            int ticket = ++buildTicket;
            Path world = placementsFile;
            building = new Building(BuildState.PARSING, relativePath, "Reading " + info.name());
            BuildMemory memory = memoryFor(relativePath, world, choicesOf(relativePath).excluded());
            background.execute(() -> read(ticket, world, info, p, memory));
            BlockPos o = p.origin();
            return Result.ok("Building " + info.name() + " at " + o.getX() + ", " + o.getY() + ", " + o.getZ());
        } catch (Throwable t) {
            Safe.report("gui schematic build", t);
            return Result.fail(couldNotStart(t));
        }
    }

    // the gui ticks on the game thread, so asking the builder from here is fine
    @Override
    public Building building() {
        Building now = building;
        // done, cancelled, or a chat #build took the builder over. either way it's not our build running anymore
        if (now.state() == BuildState.STARTED && game.runningBuild() != ourBuild) {
            now = Building.IDLE;
            building = now;
        }
        return now;
    }

    // the background half: the same parse (and the same soft cache) the material count used, then back to the game thread
    private void read(int ticket, Path world, SchematicInfo info, SchematicPlacement p, BuildMemory memory) {
        IStaticSchematic parsedFile = null;
        String error = null;
        try {
            parsedFile = library.load(info);
        } catch (OutOfMemoryError e) {
            error = "Too big to build (out of memory)";
        } catch (Throwable t) {
            Safe.report("gui schematic build read", t);
            error = "Couldn't read " + info.name();
        }
        IStaticSchematic schematic = parsedFile;
        String failure = error;
        gameThread.execute(() -> Safe.run("gui schematic build start", () -> start(ticket, world, info, p, schematic, failure, memory)));
    }

    private void start(int ticket, Path world, SchematicInfo info, SchematicPlacement p, IStaticSchematic schematic, String error,
                       BuildMemory memory) {
        // a second click while the first was reading wins
        if (ticket != buildTicket) {
            return;
        }
        String why = error;
        if (why == null && (!p.dimension().equals(game.dimension()) || !Objects.equals(world, game.placementsFile()))) {
            why = "The world changed before " + info.name() + " was read, nothing built";
        }
        if (why != null) {
            building = new Building(BuildState.ERROR, info.relativePath(), why);
            game.say(why);
            return;
        }
        try {
            ourBuild = game.build(info.file().getName(), schematic, p.origin(), p.rotation(), p.mirror(), memory);
            building = new Building(BuildState.STARTED, info.relativePath(), "Building " + info.name());
        } catch (Refused r) {
            building = new Building(BuildState.ERROR, info.relativePath(), r.getMessage());
            game.say(r.getMessage());
        } catch (Throwable t) {
            Safe.report("gui schematic build", t);
            String failed = couldNotStart(t);
            building = new Building(BuildState.ERROR, info.relativePath(), failed);
            game.say(failed + " (" + info.name() + ")");
        }
    }

    // a bare "could not start building" sends people guessing. the ones we can name get words, the rest at least get
    // the exception's name, which is a lot more to go on in a bug report
    static String couldNotStart(Throwable t) {
        if (t instanceof OutOfMemoryError) {
            return "Too big to build (out of memory)";
        }
        if (t instanceof java.util.concurrent.RejectedExecutionException) {
            return "Could not start building: the schematic reader stopped, restart the game";
        }
        if (t instanceof java.io.UncheckedIOException || t instanceof SecurityException) {
            return "Could not start building: couldn't read or write a file";
        }
        return "Could not start building: " + t.getClass().getSimpleName();
    }

    // ----- the turned file, for drawing the build where it goes

    // the file as it will be built at its placement: SchematicTransforms.apply with the placement's turn and flip, the
    // same wrappers the build gets. read in the background on first ask, null until then (and when there's no
    // placement or the file won't read, see previewFailed). local 0,0,0 is the footprint's min corner. it's the plain
    // turned file: map art, buildOnlySelection, substitutes and skip blocks are build settings and aren't in it.
    // game thread only
    public ISchematic transformed(String relativePath) {
        try {
            SchematicPlacement p = current(relativePath);
            SchematicInfo info = info(relativePath);
            if (p == null || info == null) {
                return null;
            }
            Parsed entry;
            boolean fresh = false;
            synchronized (parsed) {
                entry = parsed.get(relativePath);
                if (entry == null || entry.lastModified != info.lastModified() || entry.size != info.fileSize()) {
                    entry = new Parsed(info);
                    parsed.put(relativePath, entry);
                    fresh = true;
                }
            }
            if (fresh) {
                Parsed target = entry;
                background.execute(() -> {
                    try {
                        target.schematic = library.load(info);
                    } catch (Throwable t) {
                        Safe.report("gui schematic preview", t);
                        target.failed = true;
                    }
                });
            }
            IStaticSchematic raw = entry.schematic;
            if (raw == null) {
                return null;
            }
            if (entry.turned == null || entry.rotation != p.rotation() || entry.mirror != p.mirror()) {
                entry.turned = SchematicTransforms.apply(raw, p.rotation(), p.mirror());
                entry.rotation = p.rotation();
                entry.mirror = p.mirror();
            }
            return entry.turned;
        } catch (Throwable t) {
            Safe.report("gui schematic preview", t);
            return null;
        }
    }

    // the background read behind transformed() threw. it isn't retried until the file changes on disk (and a refresh
    // notices), so the preview can say so instead of waiting forever
    public boolean previewFailed(String relativePath) {
        synchronized (parsed) {
            Parsed entry = parsed.get(relativePath);
            return entry != null && entry.failed;
        }
    }

    // ----- the ghost in the world (SchematicGhostRenderer draws it)

    // game thread only. the gui sets it, it stays after the gui closes so you can walk around the thing
    private String ghost;

    @Override
    public void setGhost(String relativePath) {
        ghost = relativePath;
    }

    // null once the world is gone, a ghost doesn't follow you into the next world you join
    @Override
    public String ghost() {
        if (ghost != null && game.dimension() == null) {
            ghost = null;
        }
        return ghost;
    }

    public void clearGhost() {
        ghost = null;
    }

    // ----- move mode (mc/move/MoveSession runs it, this only remembers which file)

    // game thread only, like the ghost
    private String moving;

    @Override
    public Result startMoving(String relativePath) {
        try {
            Placement p = placement(relativePath);
            if (p == null) {
                return Result.fail("Join a world first");
            }
            if (!p.inThisDimension()) {
                return Result.fail("It's placed in another dimension, move it here first");
            }
            // the free camera is leashed to the player (render distance, 8 chunks tops), and past that the chunks
            // around the build aren't loaded anyway. a camera stuck on its leash staring at nothing is worse than saying so
            Footprint fp = footprint(relativePath);
            BlockPos feet = game.feet();
            int range = game.moveRange();
            if (fp != null && feet != null && tooFar(fp, feet, range)) {
                return Result.fail("It's over " + range + " blocks away, walk closer or use Move to me first");
            }
            moving = relativePath;
            // the arrows go on whatever the ghost shows, so it had better be this one
            ghost = relativePath;
            game.openMover(relativePath);
            return Result.ok("Moving " + relativePath);
        } catch (Throwable t) {
            Safe.report("gui schematic move mode", t);
            moving = null;
            return Result.fail("Could not start moving");
        }
    }

    @Override
    public void stopMoving() {
        moving = null;
    }

    private static boolean tooFar(Footprint fp, BlockPos feet, int range) {
        double dx = fp.x() + fp.sizeX() / 2.0 - feet.getX();
        double dy = fp.y() + fp.sizeY() / 2.0 - feet.getY();
        double dz = fp.z() + fp.sizeZ() / 2.0 - feet.getZ();
        return dx * dx + dy * dy + dz * dz > (double) range * range;
    }

    // a world that's gone takes move mode with it, same as the ghost
    @Override
    public String moving() {
        if (moving != null && game.dimension() == null) {
            moving = null;
        }
        return moving;
    }

    // ----- the map

    @Override
    public Terrain terrain(int centerX, int centerZ, int radiusChunks) {
        try {
            Object level = game.level();
            TerrainMap.Source source = game.terrain();
            if (level == null || source == null) {
                terrainMap.reset();
                return null;
            }
            return terrainMap.snapshot(level, source, centerX, centerZ, radiusChunks);
        } catch (Throwable t) {
            Safe.report("gui schematic terrain", t);
            return null;
        }
    }

    // a listed file, or failing that a file that is inside the folder and exists. a relative path with .. in it is not
    private File fileFor(String relativePath) {
        SchematicInfo info;
        synchronized (this) {
            info = infos.get(relativePath);
        }
        if (info != null) {
            return info.file();
        }
        Path root = library.directory().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        return resolved.startsWith(root) && Files.isRegularFile(resolved) ? resolved.toFile() : null;
    }

    @Override
    public void importFiles(Consumer<ImportReport> done) {
        if (!pickerOpen.compareAndSet(false, true)) {
            gameThread.execute(() -> done.accept(ImportReport.failed("The file picker is already open")));
            return;
        }
        Runnable job = () -> {
            ImportReport report;
            try {
                FilePicker.Choice choice = picker.pick(SchematicImporter.extensions(), "Import schematics");
                if (choice.error() != null) {
                    report = ImportReport.failed(choice.error());
                } else if (choice.wasCancelled()) {
                    report = ImportReport.nothingChosen();
                } else {
                    report = importDropped(choice.files());
                }
            } catch (Throwable t) {
                Safe.report("gui schematic import", t);
                report = ImportReport.failed("Import failed");
            } finally {
                pickerOpen.set(false);
            }
            ImportReport result = report;
            gameThread.execute(() -> Safe.run("gui schematic import done", () -> done.accept(result)));
        };
        // the helper thread, not the scan thread: a dialog can sit open for minutes and the list shouldn't wait on it
        Thread thread = new Thread(job, "soprano-schematic-picker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public ImportReport importDropped(List<Path> files) {
        try {
            SchematicImporter.Report report = new SchematicImporter(library.directory().toPath()).importAll(files);
            if (!report.imported().isEmpty()) {
                refresh();
            }
            return new ImportReport(report.imported(), report.skipped(), report.errors(), false);
        } catch (Throwable t) {
            Safe.report("gui schematic drop", t);
            return ImportReport.failed("Import failed");
        }
    }

    @Override
    public Result openFolder() {
        try {
            Path dir = library.directory().toPath();
            Files.createDirectories(dir);
            Blaze3D.openPath(dir);
            return Result.ok("Opened the schematics folder");
        } catch (Throwable t) {
            Safe.report("gui schematic folder", t);
            return Result.fail("Couldn't open the folder");
        }
    }

    @Override
    public String folderPath() {
        return library.directory().getPath();
    }

    // ----- the real world

    private static final class LiveGame implements Game {

        @Override
        public String dimension() {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : level.dimension().identifier().toString();
        }

        @Override
        public BlockPos feet() {
            if (Minecraft.getInstance().player == null) {
                return null;
            }
            return BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerFeet().immutable();
        }

        // next to gamer.json: two up from baritone's per dimension folder, the same walk RunStateStore.resolvePath does.
        // singleplayer that's <save>/baritone/altoclef/, a server <gameDir>/baritone/<ip>/altoclef/
        @Override
        public Path placementsFile() {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (!(primary instanceof Baritone baritone)) {
                return null;
            }
            WorldData world = baritone.getWorldProvider().getCurrentWorld();
            if (world == null || world.directory.getParent() == null || world.directory.getParent().getParent() == null) {
                return null;
            }
            return world.directory.getParent().getParent().resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        }

        @Override
        public Object level() {
            return Minecraft.getInstance().level;
        }

        @Override
        public TerrainMap.Source terrain() {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : new LevelSource(level);
        }

        // survival goes through altoclef so the materials get gathered first, creative builds straight away (the
        // builder places from thin air there). adventure and spectator can't place blocks at all. decided now and not
        // at the click, the read in between can take a while
        @Override
        public long build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror,
                          BuildMemory memory) {
            GameType mode = gameMode();
            if (mode != GameType.SURVIVAL && mode != GameType.CREATIVE) {
                throw new Refused("Can't build in " + (mode == null ? "this game mode" : mode.getName() + " mode")
                    + ", switch to survival or creative");
            }
            AltoClef alto = mode == GameType.SURVIVAL ? altoClef() : null;
            if (alto != null) {
                SchematicBuildTask task = new SchematicBuildTask(name, schematic, origin, rotation, mirror, memory);
                alto.runUserTask(task);
                return task.serial();
            }
            // creative places from thin air, but an unchecked row is still a row the player doesn't want built
            IBuilderProcess builder = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();
            builder.build(name, SkipItems.leaveOut(schematic, memory.excluded()), origin, rotation, mirror);
            return builder.buildSerial();
        }

        @Override
        public long runningBuild() {
            // the task holds the build the whole way, gathering included, so the tab keeps saying building
            AltoClef alto = AltoClef.getInstance();
            if (alto != null && alto.getUserTaskChain().getCurrentTask() instanceof SchematicBuildTask task && task.running()) {
                return task.serial();
            }
            return BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().buildSerial();
        }

        private static GameType gameMode() {
            var gameMode = Minecraft.getInstance().gameMode;
            return gameMode == null ? null : gameMode.getPlayerMode();
        }

        // null when altoclef is off this session, then it's the plain builder with whatever the player carries
        private AltoClef altoClef() {
            try {
                return AltoClefBridge.require();
            } catch (CommandException e) {
                say("Altoclef is off, building without gathering anything");
                return null;
            }
        }

        @Override
        public void say(String message) {
            Helper.HELPER.logDirect(message);
        }

        // the click that asked is still being handled by the gui, swapping the screen under it is asking for trouble
        @Override
        public void openMover(String relativePath) {
            Minecraft.getInstance().schedule(() -> MoveScreen.open(relativePath));
        }

        @Override
        public int moveRange() {
            return (int) MoveSession.leash(Minecraft.getInstance());
        }
    }

    // the client level as the map sees it. WORLD_SURFACE is one of the heightmaps the server sends, so it's there for
    // every loaded chunk, and it counts water as the top (which is what a map wants)
    private static final class LevelSource implements TerrainMap.Source {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelSource(ClientLevel level) {
            this.level = level;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            // not level.hasChunk, ClientLevel's is a flat 'return true'. the chunk source says null for what never arrived
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        }

        @Override
        public int surfaceY(int x, int z) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        }

        @Override
        public int minY() {
            return level.getMinY();
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        @Override
        public MapColor color(BlockState state, int x, int y, int z) {
            return state.getMapColor(level, pos.set(x, y, z));
        }
    }
}
