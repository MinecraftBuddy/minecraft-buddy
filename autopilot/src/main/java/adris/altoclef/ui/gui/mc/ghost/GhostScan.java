package adris.altoclef.ui.gui.mc.ghost;

import baritone.api.schematic.ISchematic;
import baritone.process.BuilderProcess;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// the diff of one 16^3 section: what the schematic wants against what the world has. no client classes, so the
// tests can hand it a fake world (it does read the build settings, through BuilderProcess.valid)
public final class GhostScan {

    public enum Kind {
        // nothing there yet (air, or something you place straight over like grass or water)
        MISSING,
        // the right block the wrong way round (facing, half, slab type...)
        WRONG_STATE,
        // a different block is in the way
        WRONG_BLOCK,
        // the schematic wants air and the world has a block
        EXTRA
    }

    // world coords. faces has bit Direction.get3DDataValue() set for every side that still needs drawing
    public record Ghost(int x, int y, int z, BlockState want, BlockState have, Kind kind, int faces) {
    }

    public interface World {
        boolean loaded(int chunkX, int chunkZ);

        BlockState state(int x, int y, int z);
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // the section plus a one block rim, so the culling can look at neighbours without asking twice
    private static final int PAD = 18;
    private final BlockState[] want = new BlockState[PAD * PAD * PAD];
    private final BlockState[] have = new BlockState[PAD * PAD * PAD];

    // null when there is nothing to draw. "right" is whatever BuilderProcess.valid says, so buildIgnoreDirection,
    // buildIgnoreProperties, buildValidSubstitutes, buildIgnoreBlocks, okIfAir (and okIfWater, buildIgnoreExisting)
    // count the same here as in #build
    public static Kind classify(BlockState want, BlockState have) {
        if (want == null || want == have || BuilderProcess.valid(have, want, false)) {
            return null;
        }
        if (want.isAir()) {
            // the builder breaks grass and snow in the way, so they get the red box too. fluids don't: it can't place
            // air, it just stands on top of the water. and a build dropped in a lake would be thousands of boxes with
            // every face open, water doesn't cull
            return have.isAir() || have.getBlock() instanceof LiquidBlock ? null : Kind.EXTRA;
        }
        if (have.getBlock() == want.getBlock()) {
            return Kind.WRONG_STATE;
        }
        // water, tall grass, snow layers: the builder places over them, so it reads as missing, not as in the way
        if (have.isAir() || have.canBeReplaced()) {
            return Kind.MISSING;
        }
        return Kind.WRONG_BLOCK;
    }

    // every ghost in section (sx, sy, sz) for a schematic whose local 0,0,0 sits at (ox, oy, oz). unloaded columns
    // give nothing, a chunk that isn't there reads as air and would be one big ghost otherwise
    public List<Ghost> section(ISchematic s, int ox, int oy, int oz, World world, int sx, int sy, int sz) {
        int baseX = (sx << 4) - 1;
        int baseY = (sy << 4) - 1;
        int baseZ = (sz << 4) - 1;
        fill(s, ox, oy, oz, world, baseX, baseY, baseZ);
        List<Ghost> out = new ArrayList<>();
        for (int x = 1; x <= 16; x++) {
            for (int z = 1; z <= 16; z++) {
                for (int y = 1; y <= 16; y++) {
                    int i = index(x, y, z);
                    BlockState w = want[i];
                    BlockState h = have[i];
                    if (w == null || h == null) {
                        continue;
                    }
                    Kind kind = classify(w, h);
                    if (kind == null) {
                        continue;
                    }
                    // walled in on all six sides it can't be seen, and the inside of a solid build is most of it
                    // (a 100x100x50 block of stone is 500k ghosts and 19k visible faces)
                    int faces = faces(x, y, z);
                    if (faces != 0) {
                        out.add(new Ghost(baseX + x, baseY + y, baseZ + z, w, h, kind, faces));
                    }
                }
            }
        }
        return out;
    }

    private void fill(ISchematic s, int ox, int oy, int oz, World world, int baseX, int baseY, int baseZ) {
        int sizeX = s.widthX();
        int sizeY = s.heightY();
        int sizeZ = s.lengthZ();
        Arrays.fill(want, null);
        Arrays.fill(have, null);
        // only the footprint and the block around it, an edge section that clips the corner of a build shouldn't
        // read the other 5000 blocks for nothing
        int x0 = Math.max(0, ox - 1 - baseX);
        int x1 = Math.min(PAD - 1, ox + sizeX - baseX);
        int y0 = Math.max(0, oy - 1 - baseY);
        int y1 = Math.min(PAD - 1, oy + sizeY - baseY);
        int z0 = Math.max(0, oz - 1 - baseZ);
        int z1 = Math.min(PAD - 1, oz + sizeZ - baseZ);
        for (int x = x0; x <= x1; x++) {
            int wx = baseX + x;
            for (int z = z0; z <= z1; z++) {
                int wz = baseZ + z;
                boolean loaded = world.loaded(wx >> 4, wz >> 4);
                for (int y = y0; y <= y1; y++) {
                    int wy = baseY + y;
                    int i = index(x, y, z);
                    have[i] = loaded ? world.state(wx, wy, wz) : null;
                    want[i] = wanted(s, wx - ox, wy - oy, wz - oz, sizeX, sizeY, sizeZ);
                }
            }
        }
    }

    // what the file wants at a local position, null outside it. air for current, same as the 2a contract says
    private static BlockState wanted(ISchematic s, int lx, int ly, int lz, int sizeX, int sizeY, int sizeZ) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ || !s.inSchematic(lx, ly, lz, AIR)) {
            return null;
        }
        return s.desiredState(lx, ly, lz, AIR, List.of());
    }

    // a side is hidden when the neighbour is a full opaque cube in the world (depth would hide it anyway) or in the
    // schematic (either it's built, or its own ghost sits there and an inside face just makes the ghost look muddy)
    private int faces(int x, int y, int z) {
        int faces = 0;
        for (Direction d : DIRECTIONS) {
            int n = index(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (!solid(have[n]) && !solid(want[n])) {
                faces |= 1 << d.get3DDataValue();
            }
        }
        return faces;
    }

    private static boolean solid(BlockState state) {
        return state != null && state.isSolidRender();
    }

    private static int index(int x, int y, int z) {
        return (x * PAD + z) * PAD + y;
    }
}
