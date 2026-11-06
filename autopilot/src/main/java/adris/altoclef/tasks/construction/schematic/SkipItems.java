package adris.altoclef.tasks.construction.schematic;

import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.library.MaterialList;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;

import java.util.List;
import java.util.Map;
import java.util.Set;

// the file with the cells we can't get anything for cut out, so the builder never walks over to stare at them. same
// idea as the buildSkipBlocks MaskSchematic in BuilderProcess.start, but by item: a cell goes when every item it costs
// is on the list. a cut cell is outside the schematic, so whatever stands there is left alone too. leaveOut is the
// other rule: the items the player unchecked in the tab, where one unchecked item is enough to drop the cell
public final class SkipItems implements IStaticSchematic {

    private final IStaticSchematic inner;
    private final Set<String> skip;
    // true = a cell goes when any of its items is on the list, false = only when all of them are
    private final boolean any;

    public SkipItems(IStaticSchematic inner, Set<String> skip) {
        this(inner, skip, false);
    }

    private SkipItems(IStaticSchematic inner, Set<String> skip, boolean any) {
        this.inner = inner;
        this.skip = Set.copyOf(skip);
        this.any = any;
    }

    // nothing to cut is the plain file, not a wrapper that asks every cell a question with one answer
    public static IStaticSchematic of(IStaticSchematic inner, Set<String> skip) {
        return skip.isEmpty() ? inner : new SkipItems(inner, skip, false);
    }

    // the unchecked items. "any", not "all": a cell that costs a brewing stand and something else still can't be
    // built without the brewing stand, and a cell the count bills but the builder never finishes is a build that
    // never ends
    public static IStaticSchematic leaveOut(IStaticSchematic inner, Set<String> excluded) {
        return excluded.isEmpty() ? inner : new SkipItems(inner, excluded, true);
    }

    public boolean skipped(BlockState state) {
        if (state == null) {
            return false;
        }
        Map<String, Integer> cost = MaterialList.itemsFor(paired(state));
        if (cost.isEmpty()) {
            return false;
        }
        return any ? cost.keySet().stream().anyMatch(skip::contains) : skip.containsAll(cost.keySet());
    }

    // leaveOut's rule for one state, without a file around it. the count asks this so it can still see the left out
    // cells that are already standing (and keep the pickaxe off them) while not billing the ones that aren't
    public static boolean leftOut(Set<String> excluded, BlockState state) {
        if (excluded.isEmpty() || state == null) {
            return false;
        }
        return MaterialList.itemsFor(paired(state)).keySet().stream().anyMatch(excluded::contains);
    }

    // a second half costs nothing by itself, so it goes by the half that pays for it. a door top whose bottom is cut
    // would otherwise stay in, and the builder would stand there trying to place half a door
    static BlockState paired(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return state.setValue(BedBlock.PART, BedPart.HEAD);
        }
        // an extended piston's head comes out of the piston, sticky or not
        if (state.getBlock() instanceof PistonHeadBlock) {
            return (state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON)
                .defaultBlockState();
        }
        return state;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        return inner.inSchematic(x, y, z, current) && !skipped(inner.desiredState(x, y, z, current, List.of()));
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        return inner.desiredState(x, y, z, current, approxPlaceable);
    }

    // map art reads the raw columns for its height map, so a cut cell still counts as the top of its column there.
    // the builder asks inSchematic before it places, so the cut holds anyway
    @Override
    public BlockState getDirect(int x, int y, int z) {
        return inner.getDirect(x, y, z);
    }

    @Override
    public BlockState[] getColumn(int x, int z) {
        return inner.getColumn(x, z);
    }

    @Override
    public void reset() {
        inner.reset();
    }

    @Override
    public int widthX() {
        return inner.widthX();
    }

    @Override
    public int heightY() {
        return inner.heightY();
    }

    @Override
    public int lengthZ() {
        return inner.lengthZ();
    }
}
