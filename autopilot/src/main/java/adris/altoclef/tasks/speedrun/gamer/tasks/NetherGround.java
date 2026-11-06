package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Ground;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Look;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Spot;
import adris.altoclef.tasks.speedrun.gamer.tasks.StandScan.Block;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntSupplier;

// the real blocks behind StandScan. it only answers once every chunk the search could touch is in, a half loaded ring
// would call a floor that has not arrived yet a lava sea
public final class NetherGround implements Ground {
    private final AltoClef mod;
    private final IntSupplier ring;
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

    public NetherGround(AltoClef mod, IntSupplier ring) {
        this.mod = mod;
        this.ring = ring;
    }

    @Override
    public Spot look(int x, int z) {
        ClientLevel level = mod.getWorld();
        if (level == null) {
            return new Spot(Look.UNLOADED, x, z);
        }
        // +1: the lava beside a ledge is read one block past the ring
        int reach = ring.getAsInt() + 1;
        for (int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
            for (int cz = (z - reach) >> 4; cz <= (z + reach) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return new Spot(Look.UNLOADED, x, z);
                }
            }
        }
        return StandScan.look((bx, by, bz) -> read(level, bx, by, bz), x, z, ring.getAsInt());
    }

    private Block read(ClientLevel level, int x, int y, int z) {
        BlockState s = level.getBlockState(at.set(x, y, z));
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return Block.LAVA;
        }
        if (s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE)) {
            return Block.HARM;
        }
        if (!s.getFluidState().isEmpty()) {
            return Block.OTHER;
        }
        if (s.isAir() || s.getCollisionShape(level, at).isEmpty()) {
            return Block.AIR;
        }
        return s.isFaceSturdy(level, at, Direction.UP) ? Block.SOLID : Block.OTHER;
    }
}
