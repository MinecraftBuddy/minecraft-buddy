package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

public class SearchChunkForBlockTask extends SearchChunksExploreTask {

    private final HashSet<Block> _toSearchFor = new HashSet<>();

    public SearchChunkForBlockTask(Block... blocks) {
        _toSearchFor.addAll(Arrays.asList(blocks));
    }

    @Override
    protected boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos) {
        // state predicate so the tracker can skip whole sections by palette. this ran on every chunk load and was
        // walking ~90k blocks each time
        return mod.getChunkTracker().chunkHas(pos, state -> _toSearchFor.contains(state.getBlock()));
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SearchChunkForBlockTask blockTask) {
            return Arrays.equals(blockTask._toSearchFor.toArray(Block[]::new), _toSearchFor.toArray(Block[]::new));
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Searching for " + HudText.blocks(_toSearchFor.toArray(Block[]::new));
    }

    @Override
    protected boolean isHudPlumbing() {
        // the explore base is plumbing, this one is the actual search
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Searching chunk for blocks " + ArrayUtils.toString(_toSearchFor.toArray(Block[]::new));
    }
}
