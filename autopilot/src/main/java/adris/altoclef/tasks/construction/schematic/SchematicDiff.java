package adris.altoclef.tasks.construction.schematic;

import baritone.api.schematic.ISchematic;
import baritone.process.BuilderProcess;
import baritone.utils.schematic.library.MaterialList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// the bill for what's left of a build: every cell the world doesn't have right yet, priced by MaterialList's rules.
// "right" is BuilderProcess.valid, the same question the ghost asks (GhostScan.classify). a few slices a tick, a big
// file on the game thread in one go is a frame you feel
public final class SchematicDiff {

    // the world as the diff sees it, a seam so the tests hand it a fake. game thread only for the real one
    public interface World {
        boolean loaded(int chunkX, int chunkZ);

        BlockState state(int x, int y, int z);
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ISchematic schematic;
    private final int ox;
    private final int oy;
    private final int oz;
    private final Predicate<Block> skip;
    // cells the build leaves out (the unchecked items, SkipItems.leftOut): a standing one still counts as right, a
    // missing one costs nothing and isn't wrong either
    private final Predicate<BlockState> leftOut;
    private final MaterialList.Builder left = new MaterialList.Builder();
    // the cells that are already right, for keeping the gather's pickaxe off them. null for a watch (see watching)
    private final LongOpenHashSet built;
    private final long cells;
    private long correct;
    private long wrong;
    private long cursor;
    private MaterialList result;

    // schematic is the laid out file (BuilderProcess.placed), its local 0,0,0 at ox, oy, oz. skip = buildSkipBlocks
    public SchematicDiff(ISchematic schematic, int ox, int oy, int oz, Predicate<Block> skip) {
        this(schematic, ox, oy, oz, skip, state -> false, true);
    }

    public SchematicDiff(ISchematic schematic, int ox, int oy, int oz, Predicate<Block> skip, Predicate<BlockState> leftOut) {
        this(schematic, ox, oy, oz, skip, leftOut, true);
    }

    // the same walk that only counts, for keeping an eye on the builder. no position set and no bill (result() is
    // empty), a big build re-counted every half a minute shouldn't leave a few megabytes behind each time
    public static SchematicDiff watching(ISchematic schematic, int ox, int oy, int oz, Predicate<Block> skip) {
        return watching(schematic, ox, oy, oz, skip, state -> false);
    }

    public static SchematicDiff watching(ISchematic schematic, int ox, int oy, int oz, Predicate<Block> skip,
                                         Predicate<BlockState> leftOut) {
        return new SchematicDiff(schematic, ox, oy, oz, skip, leftOut, false);
    }

    private SchematicDiff(ISchematic schematic, int ox, int oy, int oz, Predicate<Block> skip,
                          Predicate<BlockState> leftOut, boolean keepBuilt) {
        this.built = keepBuilt ? new LongOpenHashSet() : null;
        this.schematic = schematic;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.skip = skip;
        this.leftOut = leftOut;
        this.cells = (long) schematic.widthX() * schematic.heightY() * schematic.lengthZ();
    }

    // looks at up to budget more cells, true once every cell has been looked at
    public boolean step(World world, int budget) {
        if (result != null) {
            return true;
        }
        int w = schematic.widthX();
        int l = schematic.lengthZ();
        long end = Math.min(cells, cursor + budget);
        for (; cursor < end; cursor++) {
            int x = (int) (cursor % w);
            int z = (int) (cursor / w % l);
            int y = (int) (cursor / ((long) w * l));
            look(world, x, y, z);
        }
        if (cursor >= cells) {
            result = left.build();
            return true;
        }
        return false;
    }

    // null until step said true
    public MaterialList result() {
        return result;
    }

    // BlockPos.asLong of every loaded cell that already has what the file wants (air cells left out). read it after
    // step said true. empty for a watch
    public LongOpenHashSet built() {
        return built == null ? new LongOpenHashSet() : built;
    }

    // how many of those there are. the one number that says the build got further (GatherGuard)
    public long correct() {
        return correct;
    }

    // cells that still cost something: not right yet, or in a chunk nobody has seen
    public long wrong() {
        return wrong;
    }

    // the builder's missing map (IBuilderProcess.missingAtPause) by item, priced the same way as the diff. a lone
    // upper door half or bed foot comes out as nothing: no item puts one of those down by itself
    public static Map<String, Integer> priced(Map<BlockState, Integer> missing) {
        Map<String, Integer> out = new LinkedHashMap<>();
        missing.forEach((state, positions) ->
            MaterialList.itemsFor(state).forEach((item, each) -> out.merge(item, each * positions, Integer::sum)));
        return out;
    }

    private void look(World world, int x, int y, int z) {
        int wx = x + ox;
        int wy = y + oy;
        int wz = z + oz;
        boolean loaded = world.loaded(wx >> 4, wz >> 4);
        BlockState have = loaded ? world.state(wx, wy, wz) : AIR;
        // inSchematic before desiredState, a multi region litematic has gaps desiredState throws on
        if (!schematic.inSchematic(x, y, z, have)) {
            return;
        }
        BlockState want = schematic.desiredState(x, y, z, have, List.of());
        if (want == null || want.isAir() || skip.test(want.getBlock())) {
            return;
        }
        // a chunk nobody has seen is a chunk nobody has built, so it costs the full price. same as the builder,
        // which marks every unseen cell incorrect until it has been there
        if (loaded && BuilderProcess.valid(have, want, false)) {
            correct++;
            if (built != null) {
                built.add(BlockPos.asLong(wx, wy, wz));
            }
            return;
        }
        if (leftOut.test(want)) {
            return;
        }
        wrong++;
        if (built != null) {
            left.add(want);
        }
    }
}
