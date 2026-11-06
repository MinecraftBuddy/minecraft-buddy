package adris.altoclef.ui.gui.facade;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// the schematics tab's whole world: the <gameDir>/schematics folder, what's in each file, what building it costs and
// the buttons around that. nothing here parses on the render thread. list() and materials() hand back the latest
// snapshot straight away (LOADING until the background thread has an answer), so a tab can call them every frame
public interface SchematicsAccess {

    enum State {
        // a load is running. rows / lines are whatever the last finished load had (empty the first time)
        LOADING,
        READY
    }

    // one file in the library. when the file didn't parse, error is set and the numbers are zero. unknownSummary is
    // null unless the file named blocks this version doesn't know ("12 blocks unrecognized: minecraft:foo (10), ...")
    record Row(String name, String relativePath, int sizeX, int sizeY, int sizeZ, long blockCount, String format,
               int version, long fileSize, String error, String unknownSummary) {

        public boolean ok() {
            return error == null;
        }

        // "12 x 8 x 20" (width, height, length), "" when the file didn't parse
        public String sizeText() {
            return ok() ? sizeX + " x " + sizeY + " x " + sizeZ : "";
        }

        // "Litematica v6", or just "MCEdit" for formats with no version
        public String formatText() {
            return version > 0 ? format + " v" + version : format;
        }
    }

    // rows are sorted by relative path, never null, immutable
    record Listing(State state, List<Row> rows) {
    }

    enum Kind {
        // an item the player has to hold
        ITEM,
        // a block that has no item at all (fire, frosted ice), so it can't be placed
        CANT_PLACE,
        // counted for information only (fluid sources)
        NOT_COUNTED
    }

    // one row of a material list. itemId is an item id for ITEM rows ("minecraft:oak_stairs") and a block id for the
    // other two kinds. iconItemId is what to draw with Canvas.itemIcon, "" when no item stands for it. have is what the
    // player carries right now (0 for the non-ITEM kinds). gettable: AltoClef's catalogue knows how to obtain this item
    // (true/false only for now). note is "" when there's nothing to say
    record Line(String itemId, String iconItemId, String displayName, int needed, int have, Kind kind, String note,
                boolean gettable) {

        public int missing() {
            return Math.max(0, needed - have);
        }

        public boolean covered() {
            return have >= needed;
        }
    }

    // what building a schematic costs. items is the shopping list (biggest first), cantPlace the blocks no survival
    // build can place, notCounted the fluid sources, notes plain sentences for the footer ("entities are not counted").
    // error is set (state READY, no lines) when the file couldn't be read
    record Materials(State state, String relativePath, List<Line> items, List<Line> cantPlace, List<Line> notCounted,
                     List<String> notes, String error) {

        public static Materials loading(String relativePath) {
            return new Materials(State.LOADING, relativePath, List.of(), List.of(), List.of(), List.of(), null);
        }

        public long totalNeeded() {
            return items.stream().mapToLong(Line::needed).sum();
        }

        public long totalMissing() {
            return items.stream().mapToLong(Line::missing).sum();
        }

        // the player holds everything on the list (an empty list counts)
        public boolean allCovered() {
            return items.stream().allMatch(Line::covered);
        }
    }

    // what an import did. imported are library paths ("castle (2).litematic"), skipped are files left alone and why,
    // errors are files that couldn't be copied. cancelled: the picker was closed without choosing anything
    record ImportReport(List<String> imported, List<String> skipped, List<String> errors, boolean cancelled) {

        public static ImportReport nothingChosen() {
            return new ImportReport(List.of(), List.of(), List.of(), true);
        }

        public static ImportReport failed(String error) {
            return new ImportReport(List.of(), List.of(), List.of(error), false);
        }

        // the toast: ok when anything came in, the first problem when nothing did
        public Result toResult() {
            if (cancelled) {
                return Result.ok("Nothing chosen");
            }
            if (imported.isEmpty()) {
                if (!errors.isEmpty()) {
                    return Result.fail(errors.get(0));
                }
                return skipped.isEmpty() ? Result.fail("No schematics to import") : Result.fail(skipped.get(0));
            }
            String head = imported.size() == 1 ? "Imported " + imported.get(0) : "Imported " + imported.size() + " schematics";
            int left = skipped.size() + errors.size();
            return Result.ok(left == 0 ? head : head + ", " + left + (left == 1 ? " file" : " files") + " left out");
        }
    }

    // where a schematic sits in this world. x y z is the min corner of the footprint as built (after the turn), turns is
    // quarter turns clockwise 0..3, mirrored = flipped east to west. the build is turn(mirror(file)), so the turn is
    // applied to the already flipped file. inThisDimension is false when it was placed in another dimension than the
    // player is in now: draw it as "placed in the nether", don't draw it here, and build() refuses
    record Placement(int x, int y, int z, int turns, boolean mirrored, String dimension, boolean inThisDimension) {
    }

    // the box a placement covers, min corner plus the turned size. maxX/maxZ are inclusive
    record Footprint(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {

        public int maxX() {
            return x + sizeX - 1;
        }

        public int maxY() {
            return y + sizeY - 1;
        }

        public int maxZ() {
            return z + sizeZ - 1;
        }
    }

    enum BuildState {
        // nothing asked for since the game started
        IDLE,
        // build() was clicked, the file is being read in the background
        PARSING,
        // handed to the builder, which says the rest itself
        STARTED,
        // didn't start, message says why (also said in chat, since the tab may be closed by then)
        ERROR
    }

    // the last build() and how far it got. relativePath is "" while IDLE
    record Building(BuildState state, String relativePath, String message) {

        public static final Building IDLE = new Building(BuildState.IDLE, "", "");
    }

    // a top down colour map, one ARGB per block column, vanilla map colours with vanilla's slope shading. covers
    // [minX, minX + size) x [minZ, minZ + size), chunk aligned. 0 = a chunk nobody has seen loaded in this level yet,
    // anything else is opaque. like a paper map it remembers: a chunk that unloads keeps its last colours, so "known"
    // below means "has data", not "loaded right now". immutable: the render thread can keep one as long as it likes, and stamp
    // changes whenever a new one has different pixels, so a texture only needs re-uploading when the stamp moved
    final class Terrain {

        // 8 chunks each way = 16 x 16 chunks = 256 x 256 blocks, the size the placement widget is drawn around
        public static final int RADIUS_CHUNKS = 8;
        public static final int MAX_RADIUS_CHUNKS = 16;

        private final int minX;
        private final int minZ;
        private final int size;
        private final int[] argb;
        private final int knownChunks;
        private final int totalChunks;
        private final long stamp;

        // argb is kept, not copied: whoever builds one hands it over and never touches it again
        public Terrain(int minX, int minZ, int size, int[] argb, int knownChunks, int totalChunks, long stamp) {
            if (argb.length != size * size) {
                throw new IllegalArgumentException("argb is " + argb.length + " for a " + size + " square");
            }
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.argb = argb;
            this.knownChunks = knownChunks;
            this.totalChunks = totalChunks;
            this.stamp = stamp;
        }

        public int minX() {
            return minX;
        }

        public int minZ() {
            return minZ;
        }

        public int size() {
            return size;
        }

        public long stamp() {
            return stamp;
        }

        public int knownChunks() {
            return knownChunks;
        }

        public int totalChunks() {
            return totalChunks;
        }

        // every chunk in the window has been sampled
        public boolean complete() {
            return knownChunks == totalChunks;
        }

        // world coordinates. 0 outside the window or where the chunk was never sampled
        public int colorAt(int x, int z) {
            int lx = x - minX;
            int lz = z - minZ;
            if (lx < 0 || lz < 0 || lx >= size || lz >= size) {
                return 0;
            }
            return argb[lz * size + lx];
        }

        public boolean knownAt(int x, int z) {
            return colorAt(x, z) != 0;
        }

        // row major, z rows of x, size * size ints. for uploading as a texture without a copy per pixel call
        public void copyPixels(int[] into) {
            System.arraycopy(argb, 0, into, 0, argb.length);
        }
    }

    // the library as of now, starting the first load when nobody has asked yet. never blocks
    Listing list();

    // rescans the folder in the background. state flips to LOADING until it's done. cheap to call twice
    void refresh();

    // the cost of building one file (relativePath as in Row.relativePath), counted in the background and kept, so a
    // second call is free. have is re-read from the player's inventory on every call, so the numbers follow what the
    // player picks up. an unknown path comes back READY with an error
    Materials materials(String relativePath);

    // where relativePath goes in this world, made at the player's feet (no turn, no flip) the first time anyone asks.
    // a made-up default isn't saved until something changes it or it gets built, so browsing the list writes nothing.
    // null when there's no world, or the path isn't a row that parsed (the turn needs its size)
    Placement placement(String relativePath);

    // edits of the placement, saved straight away. each hands back the new placement, null in the same cases as
    // placement(). move is in world blocks. rotate is a quarter turn about the middle of the footprint. mirror flips
    // what's in the world now east to west in place (so mirror after a turn still flips east to west on screen).
    // placeAtFeet moves the corner to the player's feet in the player's dimension, keeping the turn and flip
    Placement move(String relativePath, int dx, int dy, int dz);

    Placement rotate(String relativePath, boolean clockwise);

    Placement mirror(String relativePath);

    Placement placeAtFeet(String relativePath);

    // the box the placement covers, null when placement() is
    Footprint footprint(String relativePath);

    // the corner sits on the player's feet right now, so the build button can say "at your feet" instead of numbers
    default boolean atFeet(String relativePath) {
        return false;
    }

    // starts building at the placement as it is at the time of the click. the file is read in the background and the
    // build starts on the game thread a moment later, watch building() for how it went. ok = the request was taken
    Result build(String relativePath);

    // the last build request and how far it got. never null, cheap, safe per frame
    Building building();

    // the ITEM rows (Line.itemId) the player unchecked for relativePath in this world, empty = all checked. saved with
    // the placements, so it survives a restart. cheap, safe per frame. a build reads it once when it starts, so a
    // change while one runs is for the next start
    default Set<String> excluded(String relativePath) {
        return Set.of();
    }

    // checks (included) or unchecks one ITEM row and saves it. quietly nothing without a world or for an unknown path
    default void setIncluded(String relativePath, String itemId, boolean included) {
    }

    // the colour map around a block position, radiusChunks clamped to 1..Terrain.MAX_RADIUS_CHUNKS. samples a few more
    // chunks on every call, so call it at most once a tick (the tab's poll), never per frame; the frame draws the last
    // snapshot. null when there's no world, and leaving the world forgets the whole map
    Terrain terrain(int centerX, int centerZ, int radiusChunks);

    // the schematic drawn as see-through blocks at its placement, null for none. it outlives the gui (walk around it
    // with the screen shut) and is dropped on leaving the world. defaults so the fakes and NONE don't care
    default void setGhost(String relativePath) {
    }

    default String ghost() {
        return null;
    }

    // move mode: the gui steps aside for a free camera and arrows on the build (MoveScreen). the state lives here and
    // not in a screen, so the tab can see it. fails when there's no placement in this dimension to move
    default Result startMoving(String relativePath) {
        return Result.fail("Not wired yet");
    }

    default void stopMoving() {
    }

    // the path being moved, null when move mode is off
    default String moving() {
        return null;
    }

    // opens the native multi select picker (.litematic / .schem / .schematic) on a helper thread so the game keeps
    // drawing, copies the choice into the library and then calls done on the game thread. the library refreshes itself.
    // a picker that can't open gets an ImportReport with an error saying to use drag and drop or Open folder instead
    void importFiles(Consumer<ImportReport> done);

    // files dropped on the window (the gui screen forwards Screen#onFilesDrop here). copies synchronously, schematics are small
    ImportReport importDropped(List<Path> files);

    // opens <gameDir>/schematics in the file manager, creating it first when it doesn't exist
    Result openFolder();

    // the folder for display ("Drop files here or put them in .../schematics"), "" when there is none
    String folderPath();

    SchematicsAccess NONE = new SchematicsAccess() {
        private final Result nope = Result.fail("Not wired yet");

        @Override
        public Listing list() {
            return new Listing(State.READY, List.of());
        }

        @Override
        public void refresh() {
        }

        @Override
        public Materials materials(String relativePath) {
            return new Materials(State.READY, relativePath, List.of(), List.of(), List.of(), List.of(), "Not wired yet");
        }

        @Override
        public Placement placement(String relativePath) {
            return null;
        }

        @Override
        public Placement move(String relativePath, int dx, int dy, int dz) {
            return null;
        }

        @Override
        public Placement rotate(String relativePath, boolean clockwise) {
            return null;
        }

        @Override
        public Placement mirror(String relativePath) {
            return null;
        }

        @Override
        public Placement placeAtFeet(String relativePath) {
            return null;
        }

        @Override
        public Footprint footprint(String relativePath) {
            return null;
        }

        @Override
        public Result build(String relativePath) {
            return nope;
        }

        @Override
        public Building building() {
            return Building.IDLE;
        }

        @Override
        public Terrain terrain(int centerX, int centerZ, int radiusChunks) {
            return null;
        }

        @Override
        public void importFiles(Consumer<ImportReport> done) {
            done.accept(ImportReport.failed("Not wired yet"));
        }

        @Override
        public ImportReport importDropped(List<Path> files) {
            return ImportReport.failed("Not wired yet");
        }

        @Override
        public Result openFolder() {
            return nope;
        }

        @Override
        public String folderPath() {
            return "";
        }
    };
}
