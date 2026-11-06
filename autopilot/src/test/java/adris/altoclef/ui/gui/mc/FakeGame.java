package adris.altoclef.ui.gui.mc;

import adris.altoclef.tasks.construction.schematic.BuildMemory;
import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// the world McSchematics talks to, by hand: a dimension, a pair of feet, a placements file, and a record of every build
// and chat line instead of a builder and a chat
final class FakeGame implements McSchematics.Game {

    record Built(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror,
                 BuildMemory memory) {
    }

    String dimension = "minecraft:overworld";
    BlockPos feet = new BlockPos(10, 64, -20);
    Path file;
    Object level = new Object();
    TerrainMap.Source terrain;
    final List<Built> built = new ArrayList<>();
    final List<String> said = new ArrayList<>();
    // every move screen McSchematics.startMoving asked for
    final List<String> movers = new ArrayList<>();
    int moveRange = 128;
    // the builder's serial: running is what it's building now (0 = idle), serial is the last one it handed out
    long running;
    long serial;
    // non null = build says no with this, the way LiveGame does in adventure mode
    String refuse;
    // non null = build falls over with this, the way a bug would
    RuntimeException boom;

    void leaveWorld() {
        dimension = null;
        feet = null;
        level = null;
    }

    @Override
    public String dimension() {
        return dimension;
    }

    @Override
    public BlockPos feet() {
        return feet;
    }

    @Override
    public Path placementsFile() {
        return file;
    }

    @Override
    public Object level() {
        return level;
    }

    @Override
    public TerrainMap.Source terrain() {
        return terrain;
    }

    @Override
    public long build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror,
                      BuildMemory memory) {
        if (refuse != null) {
            throw new McSchematics.Refused(refuse);
        }
        if (boom != null) {
            throw boom;
        }
        built.add(new Built(name, schematic, origin, rotation, mirror, memory));
        running = ++serial;
        return running;
    }

    @Override
    public long runningBuild() {
        return running;
    }

    @Override
    public void say(String message) {
        said.add(message);
    }

    @Override
    public void openMover(String relativePath) {
        movers.add(relativePath);
    }

    @Override
    public int moveRange() {
        return moveRange;
    }
}
